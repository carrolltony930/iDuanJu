import AVFoundation

/// Plays huangguo HLS (incl. AES-128) through AVPlayer.
/// Playlists AND AES keys are fetched through a resourceLoader so we can attach
/// Referer/Origin/X-User-Agent/x-cdn-auth to every request. A key URI is NEVER left as a bare
/// https URI: AVFoundation does not propagate AVURLAssetHTTPHeaderFieldsKey to HLS sub-resource
/// (key/segment) requests, so a bare key that needs those headers times out ->
/// CoreMediaErrorDomain -19152 ("encryption key timeout"). We rewrite every key to our own custom
/// scheme and serve it ourselves, exactly like the (working) stvhls playlist path.
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
    /// that is already cancelled/finished, or more than once. Every finish therefore goes through
    /// STVAVLoadingGuard.finish (pure ObjC @try/@catch) and is issued at most once per id.
    private var pending: Set<ObjectIdentifier> = []
    private var tasks: [ObjectIdentifier: URLSessionDataTask] = [:]

    init(headers: [String: String], keyHex: String) {
        self.headers = headers
        self.keyHex = keyHex
    }

    // MARK: - Playlist seed（省一次完整往返）

    /// resolve() 为了判断加密/时长已经拉过 master 和子 playlist。AVFoundation 起播时会用
    /// resourceLoader 再拉一次同样的 URL —— 代理环境下每次往返 1–2 秒，用户感知很明显。
    /// 这里按真实 URL 存 60 秒原文，命中就直接交给 complete() 做改写，网络请求整个跳过。
    private final class SeedStore: @unchecked Sendable {
        static let shared = SeedStore()
        private let lock = NSLock()
        private var map: [String: (Date, String)] = [:]

        func put(_ url: String, _ text: String) {
            lock.lock(); defer { lock.unlock() }
            let now = Date()
            map = map.filter { now.timeIntervalSince($0.value.0) < 60 }
            map[url] = (now, text)
            if map.count > 24 { map.removeAll() }
        }

        func get(_ url: String) -> String? {
            lock.lock(); defer { lock.unlock() }
            guard let (t, s) = map[url], Date().timeIntervalSince(t) < 60 else { return nil }
            return s
        }
    }

    /// resolve() 拉到原文后写入；HLSPlayback 起播时命中就省一个往返。
    static func seed(_ url: String, _ text: String) { SeedStore.shared.put(url, text) }

    func makeAsset(url: String) -> AVURLAsset? {
        guard var c = URLComponents(string: url) else { return nil }
        let isHLS = url.lowercased().contains(".m3u8") || url.contains("/m3u8/")
        STVLog.w("hls asset isHLS=\(isHLS) keyHex=\(keyHex.count) hdr=\(headers.count) u=\(String(url.suffix(80)))")
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
        let isKey = scheme == Self.keyScheme || scheme == Self.keyScheme + "-http"
        if isKey {
            let id = ObjectIdentifier(req)
            pending.insert(id)
            // NEVER finish synchronously inside this delegate callback on iOS 17: AVFCore has not
            // yet registered the request as "waiting", so a synchronous finishLoading throws an
            // uncatchable NSInternalInconsistencyException (1.8(15) SIGABRT on stv.hls.loader).
            // host == "key" 只由本地快路径产生。URLComponents 会把 `stvkey://key` 的 path 规范化
            // 成 "/"，所以不能用 path.isEmpty 判断（那会让本地 key 掉进远程分支去请求 https://key）。
            if url.host == "key" {
                // Local fast path: stvkey://key — serve the key resolve() already fetched.
                queue.async { [weak self] in
                    guard let self else { return }
                    self.finishKey(req, id: id, keyData: Bytes.fromHex(self.keyHex).map { Data($0) })
                }
            } else {
                // -19152 fix: fetch the real key ourselves, with the FULL set of headers, by
                // restoring the original scheme (host/path/query were kept by rewrite).
                guard var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
                c.scheme = scheme.hasSuffix("-http") ? "http" : "https"
                guard let real = c.url else { return false }
                var r = URLRequest(url: real, timeoutInterval: 10)
                for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
                let task = session.dataTask(with: r) { [weak self] data, resp, err in
                    self?.queue.async { [weak self] in
                        self?.completeKey(req, id: id, data: data, resp: resp, err: err)
                    }
                }
                tasks[id] = task
                task.resume()
            }
            return true
        }
        guard scheme.hasPrefix(Self.plScheme), var c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        c.scheme = scheme.hasSuffix("-http") ? "http" : "https"
        guard let real = c.url else { return false }
        let id = ObjectIdentifier(req)
        pending.insert(id)
        // 命中 resolve() 留下的原文：不发起网络请求，直接走同一个 complete()（改写/去广告都在里面）。
        if let cached = Self.SeedStore.shared.get(real.absoluteString) {
            let resp = HTTPURLResponse(url: real, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/vnd.apple.mpegurl"])
            STVLog.w("hls-pl seed hit \(cached.utf8.count)B host=\(real.host ?? "?")")
            queue.async { [weak self] in
                self?.complete(req, id: id, data: Data(cached.utf8), resp: resp, err: nil, real: real)
            }
            return true
        }
        var r = URLRequest(url: real, timeoutInterval: 10)
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }

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

    // MARK: - Key completion (always on `queue`)

    /// Remote key fetch landed. Validate the bytes, then hand off to the single finishKey exit.
    private func completeKey(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier,
                             data: Data?, resp: URLResponse?, err: Error?) {
        tasks.removeValue(forKey: id)
        var kd: Data?
        if err == nil,
           (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false,
           let d = data {
            kd = Self.keyBytes(d)
        }
        if kd == nil {
            STVLog.w("hls-key fail status=\((resp as? HTTPURLResponse)?.statusCode ?? -1) bytes=\(data?.count ?? 0) host=\(req.request.url?.host ?? "-") err=\(err.map { "\($0)" } ?? "-")")
        } else {
            STVLog.w("hls-key ok \(kd?.count ?? 0)B status=\((resp as? HTTPURLResponse)?.statusCode ?? -1)")
        }
        finishKey(req, id: id, keyData: kd)
    }

    /// The ONE exit for every AVAssetResourceLoadingRequest we owe a key to.
    /// The setup/respond calls may throw an ObjC exception (guarded); finishLoading MUST run even
    /// then, otherwise AVFoundation waits forever for the key -> -19152. So finish sits OUTSIDE
    /// the guarded block and is unconditional.
    private func finishKey(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier, keyData: Data?) {
        guard claim(req, id: id) else { return }
        var ok = false
        STVAVLoadingGuard.guarded {
            guard let key = keyData, key.count == 16 else { return }
            req.contentInformationRequest?.contentType = "public.data"
            req.contentInformationRequest?.contentLength = 16
            req.contentInformationRequest?.isByteRangeAccessSupported = false
            req.dataRequest?.respond(with: key)
            ok = true
        }
        self.finish(req, with: ok ? nil : URLError(.userAuthenticationRequired))
    }

    // MARK: - Playlist completion (always on `queue`)

    private func complete(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier,
                          data: Data?, resp: URLResponse?, err: Error?, real: URL) {
        tasks.removeValue(forKey: id)
        guard claim(req, id: id) else { return }
        var ok = false
        STVAVLoadingGuard.guarded {
            guard let data, err == nil,
                  (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else {
                STVLog.w("hls-fail status=\((resp as? HTTPURLResponse)?.statusCode ?? -1) host=\(real.host ?? "?") path=\(real.path.prefix(70)) ct=\(resp?.mimeType ?? "-") err=\(err.map { "\($0)" } ?? "-")")
                return
            }
            let text = self.rewrite(HLSAdFilter.apply(String(decoding: data, as: UTF8.self), base: real), base: real)
            req.contentInformationRequest?.contentType = "public.m3u-playlist"
            req.contentInformationRequest?.contentLength = Int64(text.utf8.count)
            req.dataRequest?.respond(with: Data(text.utf8))
            ok = true
            let nk = text.components(separatedBy: "#EXT-X-KEY").count - 1
            let ns = text.components(separatedBy: "stvkey").count - 1
            STVLog.w("hls-pl ok \(text.utf8.count)B keys=\(nk) stvkey=\(ns) segs=\(text.components(separatedBy: ".ts").count - 1)")
        }
        // Same as finishKey: unconditional finish (in ObjC frame) so a swallowed setup exception
        // can never leave the request hanging.
        self.finish(req, with: ok ? nil : (err ?? URLError(.badServerResponse)))
    }

    /// Atomically take ownership of the right to finish. AVFoundation itself may have already
    /// retired the request (cancelled after teardown), so check its own flags too.
    private func claim(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier) -> Bool {
        guard pending.remove(id) != nil, !req.isCancelled, !req.isFinished else { return false }
        return true
    }

    /// The single exit that touches AVFoundation's finishLoading. No other call site exists.
    /// The real finishLoading call lives in the Objective-C frame (STVAVLoadingGuard.finish), so
    /// any NSInternalInconsistencyException is thrown AND caught without unwinding a Swift frame.
    private func finish(_ req: AVAssetResourceLoadingRequest, with error: Error?) {
        STVAVLoadingGuard.finish(req, error: error.map { $0 as NSError })
    }

    // MARK: - Key helpers

    /// A 16-byte AES key from a fetch response: raw 16 bytes, 32-char hex text, or 16 bytes
    /// followed by nothing but whitespace. Anything else is not a usable key.
    private static func keyBytes(_ d: Data) -> Data? {
        if d.count == 16 { return d }
        if let s = String(data: d, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
           s.count == 32, let b = Bytes.fromHex(s), b.count == 16 { return Data(b) }
        if d.count > 16,
           String(decoding: d[16...], as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return Data(d.prefix(16))
        }
        return nil
    }

    /// Absolutize URIs; nested playlists go back through the loader, keys go to our key scheme.
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
                    // 1.8 left a bare https URI when keyHex was empty  -> AVFoundation fetched it
                    //     without our headers -> -19152 "encryption key timeout" (真机 2026-11-13).
                    // 1.9 only rewrote to stvkey://key when keyHex was valid, else kept the bare
                    //     https URI -> same timeout. Both let AVFoundation fetch a key it cannot
                    //     authenticate. Now EVERY key goes through OUR loader, which attaches the
                    //     full header set: keep the original host/path/query, just swap the scheme.
                    let valid = (Bytes.fromHex(self.keyHex)?.count ?? 0) == 16
                    if valid {
                        // 走 loopback http：key 变成普通 http 资源，AVFoundation 自己取，不经过
                        // resourceLoader。iOS 17 上自定义 scheme 作 key URI 时它根本不发请求，
                        // 一路等到 -19152（真机 2.2 仍复现）。
                        if let lk = LocalHLSProxy.shared.keyURL(hex: self.keyHex) {
                            repl = lk
                            STVLog.w("key route loopback \(lk.prefix(46))")
                        } else {
                            repl = Self.keyScheme + "://key"
                            STVLog.w("key route stvkey://key (loopback unavailable)")
                        }
                    } else {
                        let ks = u.scheme == "http" ? Self.keyScheme + "-http" : Self.keyScheme
                        repl = via(u, ks)                                     // self-fetched with headers
                    }
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
