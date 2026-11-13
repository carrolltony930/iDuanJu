import AVFoundation

/// Plays huangguo HLS (incl. AES-128) through AVPlayer.
/// Playlists are fetched through a resourceLoader so we can attach Referer/Origin/X-User-Agent,
/// and EXT-X-KEY URIs are rewritten to a local scheme that serves the key we already resolved.
final class HLSPlayback: NSObject, AVAssetResourceLoaderDelegate {
    static let plScheme = "stvhls"
    static let keyScheme = "stvkey"

    let headers: [String: String]
    let keyHex: String
    /// AVFoundation invokes every delegate method on this serial queue. Keeping all the
    /// bookkeeping on it makes "delegate cancelled the request" vs. "our URLSession callback
    /// came back" a total order, so the two can never race.
    private let queue = DispatchQueue(label: "stv.hls.loader")
    private let session = URLSession(configuration: .default)

    /// Requests AVFoundation handed us that we still owe a finish to. Touched only on `queue`.
    /// AVFCore raises an *uncatchable* ObjC NSInternalInconsistencyException (Swift cannot catch
    /// it, so it becomes std::terminate -> SIGABRT) when finishLoading is called on a request
    /// that is already cancelled/finished, or more than once. That is exactly what happened when
    /// the user tapped Back: teardown released the asset while a playlist fetch was in flight.
    private var pending: Set<ObjectIdentifier> = []
    private var tasks: [ObjectIdentifier: URLSessionDataTask] = [:]

    init(headers: [String: String], keyHex: String) {
        self.headers = headers
        self.keyHex = keyHex
    }

    func makeAsset(url: String) -> AVURLAsset? {
        guard var c = URLComponents(string: url) else { return nil }
        let isHLS = url.lowercased().contains(".m3u8") || url.contains("/m3u8/")
        if isHLS { c.scheme = c.scheme == "http" ? Self.plScheme + "-http" : Self.plScheme }
        guard let u = c.url else { return nil }
        let asset = AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        if isHLS { asset.resourceLoader.setDelegate(self, queue: queue) }
        return asset
    }

    // MARK: - AVAssetResourceLoaderDelegate

    func resourceLoader(_ loader: AVAssetResourceLoader,
                        shouldWaitForLoadingOfRequestedResource req: AVAssetResourceLoadingRequest) -> Bool {
        guard let url = req.request.url, let scheme = url.scheme else { return false }
        if scheme == Self.keyScheme {
            let id = ObjectIdentifier(req)
            pending.insert(id)
            // iOS 17 崩溃根因（2026-11-13 1.8(15) 真机 ips，SIGABRT on stv.hls.loader）：
            // 不能在 shouldWaitFor... 同步 delegate 回调栈内调用 finishLoading。返回 true 的
            // 契约是「我会稍后完成」，同步 finish 时 AVFoundation 还没把 request 登记为
            // waiting，状态机冲突会抛不可捕获的 NSInternalInconsistencyException。
            // playlist 路径本来就是异步 finish（URLSession 回调 → queue.async），所以从不崩。
            // key 路径统一排到 delegate queue 的下一轮，delegate 先返回，再 finish。
            queue.async { [weak self] in self?.finishKey(req, id: id) }
            return true
        }
        guard scheme.hasPrefix(Self.plScheme), var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        c.scheme = scheme.hasSuffix("-http") ? "http" : "https"
        guard let real = c.url else { return false }
        var r = URLRequest(url: real)
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }

        let id = ObjectIdentifier(req)
        pending.insert(id)
        let task = session.dataTask(with: r) { [weak self] data, resp, err in
            // Jump back onto the delegate queue: didCancel and this callback are then serialised,
            // so a request cancelled by teardown is never finished afterwards.
            self?.queue.async { [weak self] in
                self?.complete(req, id: id, data: data, resp: resp, err: err, real: real)
            }
        }
        tasks[id] = task
        task.resume()
        return true
    }

    func resourceLoader(_ loader: AVAssetResourceLoader, didCancel req: AVAssetResourceLoadingRequest) {
        // AVFoundation cancelled this request (asset/player torn down). Drop it and stop its
        // network work; any late callback will find no pending entry and bail out safely.
        let id = ObjectIdentifier(req)
        pending.remove(id)
        tasks.removeValue(forKey: id)?.cancel()
    }

    // MARK: - Completion (always on `queue`)

    private func finishKey(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier) {
        guard claim(req, id: id) else { return }
        // AVFCore 抛的是 Objective-C 异常，Swift 无法 catch；一旦逃逸出这个 dispatch block
        // 就是 std::terminate -> SIGABRT。整段走 ObjC @try/@catch 兜底。
        STVAVLoadingGuard.guarded {
            guard let key = Bytes.fromHex(self.keyHex), key.count == 16 else {
                self.finish(req, with: URLError(.userAuthenticationRequired))
                return
            }
            req.contentInformationRequest?.contentType = "public.data"
            req.contentInformationRequest?.contentLength = 16
            req.contentInformationRequest?.isByteRangeAccessSupported = false
            req.dataRequest?.respond(with: Data(key))
            self.finish(req, with: nil)
        }
    }

    private func complete(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier,
                          data: Data?, resp: URLResponse?, err: Error?, real: URL) {
        tasks.removeValue(forKey: id)
        guard claim(req, id: id) else { return }
        // 同 finishKey：所有 respond / finishLoading 都在 ObjC @try/@catch 内。
        STVAVLoadingGuard.guarded {
            guard let data, err == nil,
                  (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else {
                NSLog("%@", "[STV] hls-fail status=\((resp as? HTTPURLResponse)?.statusCode ?? -1) host=\(real.host ?? "?") path=\(real.path.prefix(70)) ct=\(resp?.mimeType ?? "-") err=\(err.map { "\($0)" } ?? "-")")
                self.finish(req, with: err ?? URLError(.badServerResponse))
                return
            }
            let text = self.rewrite(HLSAdFilter.apply(String(decoding: data, as: UTF8.self), base: real), base: real)
            req.contentInformationRequest?.contentType = "public.m3u-playlist"
            req.contentInformationRequest?.contentLength = Int64(text.utf8.count)
            req.dataRequest?.respond(with: Data(text.utf8))
            self.finish(req, with: nil)
        }
    }

    /// Atomically take ownership of the right to finish. AVFoundation itself may have already
    /// retired the request (cancelled after teardown), so check its own flags too.
    private func claim(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier) -> Bool {
        guard pending.remove(id) != nil, !req.isCancelled, !req.isFinished else { return false }
        return true
    }

    /// The single exit that touches AVFoundation's finishLoading. No other call site exists.
    /// 真正的 finishLoading 调用下沉到 Objective-C 帧（STVAVLoadingGuard.finish），
    /// 在纯 ObjC 栈内 @try/@catch 兜住 NSInternalInconsistencyException —— Swift 无法
    /// 捕获 ObjC 异常，一旦逃逸出 dispatch block 就是 std::terminate -> SIGABRT。
    private func finish(_ req: AVAssetResourceLoadingRequest, with error: Error?) {
        STVAVLoadingGuard.finish(req, error: error.map { $0 as NSError })
    }

    /// Absolutize URIs; nested playlists go back through the loader, keys go to the local key scheme.
    private func rewrite(_ pl: String, base: URL) -> String {
        func abs(_ s: String) -> URL? { URL(string: s, relativeTo: base)?.absoluteURL }
        func via(_ u: URL, _ scheme: String) -> String {
            var c = URLComponents(url: u, resolvingAgainstBaseURL: false)
            c?.scheme = scheme == Self.plScheme && u.scheme == "http" ? Self.plScheme + "-http" : scheme
            return c?.url?.absoluteString ?? u.absoluteString
        }
        var expectPlaylist = false
        return pl.split(separator: "\n", omittingEmptySubsequences: false).map { raw -> String in
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.uppercased().hasPrefix("#EXT-X-STREAM-INF") { expectPlaylist = true; return line }
            if line.uppercased().hasPrefix("#EXT-X-KEY") || line.uppercased().hasPrefix("#EXT-X-MEDIA") || line.uppercased().hasPrefix("#EXT-X-MAP") {
                let isKey = line.uppercased().hasPrefix("#EXT-X-KEY")
                guard let m = HTML.groups("URI\\s*=\\s*\"([^\"]+)\"", line), let u = abs(m[1]) else { return line }
                let repl: String
                if isKey {
                    // 只有确实解析出合法 16 字节 AES key 才改写成本地 stvkey://。
                    // 黄果换线路后 key URI 常返回非密钥内容（HTML/空串/长度不对），
                    // 旧逻辑只要 keyHex 非空就改写，随后 finishKey 里 Bytes.fromHex 失败，
                    // 走 error 分支调用 finishLoadingWithError: —— 正是 1.8(15) 真机
                    // SIGABRT 的栈顶。保留原始 URI 时 AVURLAsset 已带 Referer/UA，
                    // 让 AVFoundation 自行取 key，最坏只是该集播不出，绝不会杀进程。
                    let valid = (Bytes.fromHex(self.keyHex)?.count ?? 0) == 16
                    repl = valid ? Self.keyScheme + "://key" : u.absoluteString
                }
                else if line.uppercased().hasPrefix("#EXT-X-MEDIA") { repl = via(u, Self.plScheme) }
                else { repl = u.absoluteString }
                return line.replacingOccurrences(of: m[1], with: repl)
            }
            if line.isEmpty || line.hasPrefix("#") { return line }
            guard let u = abs(line) else { return line }
            defer { expectPlaylist = false }
            return expectPlaylist || u.path.lowercased().hasSuffix(".m3u8") ? via(u, Self.plScheme) : u.absoluteString
        }.joined(separator: "\n")
    }
}
