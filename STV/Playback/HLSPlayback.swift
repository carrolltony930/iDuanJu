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
            finishKey(req, id: id)
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
        guard let key = Bytes.fromHex(keyHex), key.count == 16 else {
            finish(req, with: URLError(.userAuthenticationRequired))
            return
        }
        req.contentInformationRequest?.contentType = "public.data"
        req.contentInformationRequest?.contentLength = 16
        req.contentInformationRequest?.isByteRangeAccessSupported = false
        req.dataRequest?.respond(with: Data(key))
        finish(req, with: nil)
    }

    private func complete(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier,
                          data: Data?, resp: URLResponse?, err: Error?, real: URL) {
        tasks.removeValue(forKey: id)
        guard claim(req, id: id) else { return }
        guard let data, err == nil,
              (resp as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false else {
            NSLog("%@", "[STV] hls-fail status=\((resp as? HTTPURLResponse)?.statusCode ?? -1) host=\(real.host ?? "?") path=\(real.path.prefix(70)) ct=\(resp?.mimeType ?? "-") err=\(err.map { "\($0)" } ?? "-")")
            finish(req, with: err ?? URLError(.badServerResponse))
            return
        }
        let text = rewrite(HLSAdFilter.apply(String(decoding: data, as: UTF8.self), base: real), base: real)
        req.contentInformationRequest?.contentType = "public.m3u-playlist"
        req.contentInformationRequest?.contentLength = Int64(text.utf8.count)
        req.dataRequest?.respond(with: Data(text.utf8))
        finish(req, with: nil)
    }

    /// Atomically take ownership of the right to finish. AVFoundation itself may have already
    /// retired the request (cancelled after teardown), so check its own flags too.
    private func claim(_ req: AVAssetResourceLoadingRequest, id: ObjectIdentifier) -> Bool {
        guard pending.remove(id) != nil, !req.isCancelled, !req.isFinished else { return false }
        return true
    }

    /// The single exit that touches AVFoundation's finishLoading. No other call site exists.
    private func finish(_ req: AVAssetResourceLoadingRequest, with error: Error?) {
        guard !req.isCancelled, !req.isFinished else { return }
        if let error { req.finishLoading(with: error) } else { req.finishLoading() }
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
                if isKey { repl = keyHex.isEmpty ? u.absoluteString : Self.keyScheme + "://key" }
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
