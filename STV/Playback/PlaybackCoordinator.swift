import AetherEngine
import AVFoundation
import AVKit
import MediaPlayer
import Observation
import UIKit

/// Drives the vertical episode feed: one playback core (AetherEngine by default, AVPlayer as the
/// fallback — see `PlayerEngine`), per-episode prepared sources, neighbour preloading, resume and
/// local progress persistence.
@MainActor @Observable
final class PlaybackCoordinator: NSObject, @preconcurrency AVPictureInPictureControllerDelegate {
    /// `.remote`: upstream MP4 played by AVFoundation directly (proxy fallback).
    enum Source { case file(URL), stream(HLSPlayback, String), direct(URL), remote(URL, [String: String]) }

    /// AVPlayerItem for a source. Progressive MP4s get an explicit out-of-band MIME type so AVFoundation
    /// never has to guess the container from an extensionless / query-string URL.
    static func makeItem(_ src: Source) -> AVPlayerItem? {
        switch src {
        case .file(let u): return AVPlayerItem(url: u)
        case .direct(let u):
            let mp4 = u.path.hasSuffix(".mp4") || u.path.hasPrefix("/tg/")
            return AVPlayerItem(asset: AVURLAsset(url: u, options: mp4 ? [AVURLAssetOverrideMIMETypeKey: "video/mp4"] : nil))
        case .remote(let u, let headers):
            var o: [String: Any] = [AVURLAssetOverrideMIMETypeKey: "video/mp4"]
            if !headers.isEmpty { o["AVURLAssetHTTPHeaderFieldsKey"] = headers }
            return AVPlayerItem(asset: AVURLAsset(url: u, options: o))
        case .stream(let loader, let url):
            return loader.makeAsset(url: url).map { AVPlayerItem(asset: $0) }
        }
    }

    /// A loopback-proxied item that failed gets one retry straight from the CDN (fresh resolve → fresh token).
    static func canBypassProxy(_ src: Source?, stream: HGStream?) -> Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_NO_FALLBACK"] == "1" { return false }
        #endif
        guard case .direct(let u)? = src, u.host == "127.0.0.1", let stream else { return false }
        return !stream.url.hasPrefix("tdfile://") && !stream.url.hasPrefix("file://") && LocalHLSProxy.token(of: stream.url) == nil
    }

    /// User-facing failure text with the AVFoundation code (so a device report pins the cause).
    static func failureText(_ e: Error?) -> String {
        guard let ns = e as NSError? else { return "播放失败，请重试" }
        let under = (ns.userInfo[NSUnderlyingErrorKey] as? NSError).map { " / \($0.code)" } ?? ""
        return "\(ns.localizedDescription)（\(ns.code)\(under)）"
    }

    static let rateSteps: [Double] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    let series: HGSeries
    var detail: HGSeriesDetail?
    private(set) var current: Int?
    private(set) var readyIndex: Int?
    private(set) var errors: [Int: String] = [:]
    /// Set when an episode failed on a Cloudflare interstitial (player shows 去验证).
    private(set) var verifyTargets: [Int: VerifyTarget] = [:]
    private(set) var streams: [Int: [HGStream]] = [:]
    private(set) var chosen: [Int: HGStream] = [:]
    var time: Double = 0
    var duration: Double = 0
    /// Furthest playable position buffered by AVPlayer around the playhead (seconds).
    private(set) var loadedUntil: Double = 0
    /// Buffered position for the progress bar: AVPlayer buffer or the byte pre-buffer, whichever is ahead.
    var bufferedUntil: Double { max(loadedUntil, prebuffer?.until ?? 0) }
    private(set) var paused = false
    /// Active playback rate (1.0 default). Locked rate persists after speed-gesture ends.
    private(set) var playbackRate: Double = 1.0
    /// Non-nil when user locked a non-1x rate via speed gesture.
    private(set) var lockedRate: Double? = nil
    private(set) var pipPossible = false
    /// Design v3 chrome: rendered video size, tech strip, audio / subtitle options, auto-advance.
    private(set) var videoSize: CGSize = .zero
    private(set) var tech: PlayerTech?
    private(set) var audioOptions: [MediaTrack] = []
    private(set) var subtitleOptions: [MediaTrack] = []
    private(set) var audioSelected: Int?
    private(set) var subtitleSelected: Int?
    /// Host-drawn subtitle line (Aether loopback / software routes).
    private(set) var subtitleText: String?
    /// Core playing the current episode (the view mounts its surface).
    private(set) var engineKind: PlayerEngineKind = .avplayer
    private(set) var muted = false
    var autoNext: Bool = UserDefaults.standard.object(forKey: "stv.player.autoNext") as? Bool ?? true {
        didSet { UserDefaults.standard.set(autoNext, forKey: "stv.player.autoNext") }
    }
    @ObservationIgnored let thumbnails = PlayerThumbnailer()
    /// Time under the finger while the progress bar is dragged (time row follows it).
    var scrubPreview: Double?
    #if DEBUG
    /// Screenshot hook: frozen drag fraction (STV_SCRUB_DEMO), shared by whichever bar instance is on screen.
    var scrubDemo: Double?
    #endif
    @ObservationIgnored private var lastTech = Date.distantPast
    /// Long-video byte pre-buffer: bytes cached ahead of the playhead and the time the contiguous run reaches.
    private(set) var prebuffer: (bytes: Int, until: Double, lowStorage: Bool)?
    @ObservationIgnored private var prefetchTokens: [Int: String] = [:]
    /// Episodes whose proxied playback failed once → played straight from the CDN.
    @ObservationIgnored private var bypassProxy: Set<Int> = []
    @ObservationIgnored private var lastPrefetchPoll = Date.distantPast

    @ObservationIgnored let av: AVPlayerEngine
    @ObservationIgnored private(set) lazy var aether: AetherPlayerEngine? = {
        do { return try AetherPlayerEngine() } catch {
            #if DEBUG
            NSLog("%@", "[STV] AetherEngine unavailable: \(error)")
            #endif
            return nil
        }
    }()
    @ObservationIgnored private(set) var engine: PlayerEngine
    /// Episodes AetherEngine could not open → AVPlayer.
    @ObservationIgnored private var aetherFailed: Set<Int> = []
    @ObservationIgnored private var pipIdentity: ObjectIdentifier?
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var sharedCommandsRegistered = false
    /// Aspect fill (view toggle) applied to whichever core is mounted.
    @ObservationIgnored var fill = false { didSet { engine.fill = fill } }
    /// Current playhead of the active core (danmaku clock).
    /// The core's clock only moves ~5×/s (Aether publishes at ~10 Hz), so drawing straight from it makes
    /// scrolling comments step. Between core ticks this extrapolates with wall time at the playback rate;
    /// a jump (seek, stall, new episode) re-anchors immediately, small drift is pulled in gently.
    var playhead: Double {
        let raw = engine.currentTime, now = CACurrentMediaTime()
        let rate = paused || readyIndex == nil ? 0 : min(playbackRate, engine.maxRate)
        guard let a = clockAnchor else { clockAnchor = (raw, now, raw); return raw }
        var est = a.time + (now - a.at) * rate
        if raw != a.raw {
            // New core sample: snap if far off, otherwise ease towards it so motion stays continuous.
            if abs(raw - est) > 0.35 { est = raw } else { est += (raw - est) * 0.25 }
            clockAnchor = (est, now, raw)
        } else if rate == 0 || est - raw > 0.6 {
            // Paused, or the core stopped advancing (buffering): hold at the core time.
            est = raw; clockAnchor = (raw, now, raw)
        }
        return est
    }
    @ObservationIgnored private var clockAnchor: (time: Double, at: CFTimeInterval, raw: Double)?
    private var prepared: [Int: Source] = [:]
    private var tasks: [Int: Task<Source, Error>] = [:]
    private var observer: Any?
    private var endObserver: NSObjectProtocol?
    private var lastSave = Date.distantPast
    private var activation = UUID()
    private var pip: AVPictureInPictureController?
    private var pipObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var statusObservation: NSKeyValueObservation?
    private(set) var waitingForMedia = false
    private var audioObservers: [NSObjectProtocol] = []
    private var nowPlaying: MPNowPlayingSession?
    private(set) var pipActive = false
    var pipError: String?
    private var restoringPiP = false
    /// 仅当用户点击手动画中画按钮时为 true；用于区分系统后台自动启动。
    private var userInitiatedPiP = false
    private var backgrounded = false
    private var interrupted = false
    private var audioSessionActive = false

    init(series: HGSeries) {
        self.series = series
        let a = AVPlayerEngine()
        self.av = a
        self.engine = a
        super.init()
    }

    func start() {
        guard observer == nil else { return }
        let player = av.player
        // 不创建 MPNowPlayingSession：无灵动岛/锁屏播放标志，也避免它对
        // AVPlayer.rate 的 KVO 在退出 teardown 时留下悬空观察者（v6 返回闪退根因）。
        nowPlaying = nil
        let center = NotificationCenter.default
        audioObservers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] note in
            MainActor.assumeIsolated { self?.handleInterruption(note) }
        })
        audioObservers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: AVAudioSession.sharedInstance(), queue: .main) { [weak self] note in
            if note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue {
                MainActor.assumeIsolated { self?.setPaused(true) }
            }
        })
        audioObservers.append(center.addObserver(forName: AVPlayer.rateDidChangeNotification, object: player, queue: .main) { [weak self] note in
            // PiP controls target AVPlayer directly. System background/interruption pauses must not erase play intent.
            guard note.userInfo?[AVPlayer.rateDidChangeReasonKey] as? String == AVPlayer.RateDidChangeReason.setRateCalled.rawValue else { return }
            MainActor.assumeIsolated {
                guard let self, self.engineKind == .avplayer, self.pipActive, !self.restoringPiP, !self.interrupted, self.readyIndex != nil else { return }
                self.paused = self.av.player.rate == 0
            }
        })
        // One clock for both cores (AVPlayer periodic observers do not exist on Aether's software route).
        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        observer = t
        // 不注册锁屏/耳机远程命令：不发布 Now Playing。
    }

    /// Lock-screen / headset commands while Aether plays (AVPlayer's own MPNowPlayingSession covers the fallback).
    private func registerSharedCommands() {
        guard !sharedCommandsRegistered else { return }
        sharedCommandsRegistered = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget(self, action: #selector(remotePlay))
        c.pauseCommand.addTarget(self, action: #selector(remotePause))
        c.togglePlayPauseCommand.addTarget(self, action: #selector(remoteToggle))
    }

    private func publishNowPlaying() {
        // 不发布任何 Now Playing 信息（灵动岛/锁屏不显示播放标志）。
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func activateAudioSession() {
        guard !audioSessionActive else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .moviePlayback, options: [])
            try session.setActive(true)
            audioSessionActive = true
        } catch {
            // Best-effort; playback still works without background continuity.
        }
    }

    func teardown() {
        activation = UUID()
        save(force: true)
        pip?.delegate = nil
        pipObservation = nil
        statusObservation = nil
        itemObservation = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        audioObservers.forEach(NotificationCenter.default.removeObserver)
        audioObservers.removeAll()
        if let commands = nowPlaying?.remoteCommandCenter {
            [commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand].forEach { $0.removeTarget(self) }
        }
        if sharedCommandsRegistered {
            let c = MPRemoteCommandCenter.shared()
            [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand].forEach { $0.removeTarget(self) }
            sharedCommandsRegistered = false
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        }
        nowPlaying = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        userInitiatedPiP = false
        if pip?.isPictureInPictureActive == true {
            pip?.stopPictureInPicture()
        }
        pipObservation = nil
        pip?.delegate = nil
        pip = nil
        pipIdentity = nil
        pipActive = false
        pipPossible = false
        timer?.invalidate(); timer = nil
        observer = nil
        av.stop()
        aether?.stop()
        readyIndex = nil
        current = nil
        backgrounded = false
        interrupted = false
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        prefetchTokens.values.forEach { LocalHLSProxy.shared.release($0) }
        prefetchTokens.removeAll()
        prebuffer = nil
        if audioSessionActive {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            audioSessionActive = false
        }
    }

    var episodes: [HGEpisode] { detail?.episodes ?? [] }

    // MARK: PiP

    /// (Re)build the PiP controller when the active core's picture route changes.
    private func refreshPiP() {
        // 关键：默认【不创建】任何 AVPictureInPictureController。
        // 系统只有在手里存在一个 controller 时，才会在 App 进入后台自动拉起画中画；
        // 既然平时没有，就不可能出现「自动画中画闪一下又消失」。
        // 手动画中画由 togglePiP() / ensurePiPController() 按需创建。
        pipPossible = AVPictureInPictureController.isPictureInPictureSupported()
        guard !userInitiatedPiP, !pipActive else { return }
        if pip != nil {
            pipObservation = nil
            pip?.delegate = nil
            pip = nil
            pipIdentity = nil
        }
    }

    /// 仅在用户主动点「画中画」按钮时创建 controller；平时系统手里没有 controller。
    private func ensurePiPController() -> AVPictureInPictureController? {
        if let pip, pipIdentity == engine.pipIdentity { return pip }
        guard engine.pipIdentity != nil, AVPictureInPictureController.isPictureInPictureSupported(),
              let controller = engine.makePiPController() else { return nil }
        controller.delegate = self
        controller.canStartPictureInPictureAutomaticallyFromInline = false
        pipIdentity = engine.pipIdentity
        pip = controller
        pipObservation = controller.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] c, _ in
            Task { @MainActor in
                guard self?.pip === c else { return }
                self?.pipPossible = AVPictureInPictureController.isPictureInPictureSupported()
            }
        }
        return controller
    }

    func togglePiP() {
        // 已开启 → 关闭
        if let pip, pip.isPictureInPictureActive {
            restoringPiP = true
            pip.stopPictureInPicture()
            return
        }
        guard let controller = ensurePiPController() else { return }
        userInitiatedPiP = true
        if controller.isPictureInPicturePossible {
            controller.startPictureInPicture()
        } else {
            // 刚创建时 isPictureInPicturePossible 可能还没翻上来，稍等再启动一次。
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 250_000_000)
                guard self.pip === controller, self.userInitiatedPiP,
                      controller.isPictureInPicturePossible,
                      !controller.isPictureInPictureActive else {
                    // 启动不了就复位，避免后台清理逻辑被永久跳过。
                    if self.pip === controller { self.userInitiatedPiP = false }
                    return
                }
                controller.startPictureInPicture()
            }
        }
    }

    func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        // 只有用户主动点按钮才允许进入画中画。
        guard userInitiatedPiP else { return }
        pipActive = true
        engine.setPiPActive(true)
    }

    func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        // 兜底：只拦截「非用户主动」的自动拉起；用户手动开的画中画切后台应继续播放。
        if !userInitiatedPiP {
            controller.stopPictureInPicture()
        }
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        pipActive = false
        userInitiatedPiP = false
        if !backgrounded { pipError = error.localizedDescription }
    }

    func pictureInPictureController(_ controller: AVPictureInPictureController,
                                    restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completion: @escaping (Bool) -> Void) {
        // 播放器 UI 本来就在，必须告诉系统「已恢复」。
        // 回 false 会让系统留下黑屏 + 「返回播放器」的恢复占位界面（v7 的黑屏卡死根因）。
        restoringPiP = true
        completion(true)
    }

    func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        pipActive = false
        engine.setPiPActive(false)
        userInitiatedPiP = false
        restoringPiP = false
        // 任何 PiP 结束都销毁 controller，系统手里没有 controller 就不会再自动拉起。
        pipObservation = nil
        pip?.delegate = nil
        pip = nil
        pipIdentity = nil
        // 回前台无条件续播，避免断流。
        if !backgrounded, !paused, readyIndex != nil {
            activateAudioSession()
            play()
        }
    }

    func didEnterBackground() {
        backgrounded = true
        // 保险：确保后台不残留任何 PiP controller（平时本来就没有）。
        if !userInitiatedPiP {
            pipObservation = nil
            pip?.delegate = nil
            pip = nil
            pipIdentity = nil
            pipActive = false
        }
        // 显式激活 .playback 音频会话并落盘进度，保证切后台音视频会话不被拆掉。
        activateAudioSession()
        save(force: true)
    }

    func didBecomeActive() {
        guard backgrounded else { return }
        backgrounded = false
        // 不重建任何 PiP controller；手动画中画按钮会在点击时按需创建。
        // 重新武装音频会话、清除过期中断标志并无条件续播，避免回前台断流。
        interrupted = false
        activateAudioSession()
        if !paused, readyIndex != nil { play() }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt else { return }
        if type == AVAudioSession.InterruptionType.began.rawValue {
            audioSessionActive = false
            interrupted = true
            save(force: true)
        } else {
            interrupted = false
            let options = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            if !AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { setPaused(true) }
            else if !paused, readyIndex != nil { play() }
        }
    }

    @objc private func remotePlay(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        setPaused(false)
        return .success
    }

    @objc private func remotePause(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        setPaused(true)
        return .success
    }

    @objc private func remoteToggle(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        togglePause()
        return .success
    }

    // MARK: Rate

    func applyRate(_ rate: Double) {
        let r = Self.nearestStep(rate)
        let clamped = min(r, engine.maxRate)
        playbackRate = clamped
        if !paused, !interrupted, readyIndex != nil { engine.setRate(clamped) }
    }

    func lockRate(_ rate: Double) {
        let r = Self.nearestStep(rate)
        lockedRate = r == 1.0 ? nil : r
        applyRate(r)
    }

    func unlockRate() {
        lockedRate = nil
        applyRate(1.0)
    }

    func restoreLockedOrNormal() {
        applyRate(lockedRate ?? 1.0)
    }

    static func nearestStep(_ rate: Double) -> Double {
        rateSteps.min(by: { abs($0 - rate) < abs($1 - rate) }) ?? 1.0
    }

    static func stepIndex(of rate: Double) -> Int {
        let r = nearestStep(rate)
        return rateSteps.firstIndex(of: r) ?? rateSteps.firstIndex(of: 1.0) ?? 0
    }

    static func formatRate(_ rate: Double) -> String {
        if rate == Double(Int(rate)) { return "\(Int(rate))x" }
        let s = String(format: "%.2f", rate)
        return s.replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\.$", with: "", options: .regularExpression) + "x"
    }

    // MARK: Activation

    func select(_ index: Int?) {
        guard let index, index != current, episodes.indices.contains(index) else { return }
        current = index
        paused = false
        activate(index)
    }

    /// Last frame of the episode being left, shown over the (cleared) video surface until the next one is
    /// ready, so switching never flashes black.
    private(set) var heldFrame: (image: UIImage, index: Int)?
    /// The feed calls this once the swipe has settled on a ready episode.
    func releaseHeldFrame() { if heldFrame != nil { heldFrame = nil } }

    func activate(_ i: Int) {
        guard i == current, episodes.indices.contains(i), observer != nil else { return }
        save(force: true)
        let token = UUID()
        activation = token
        if let r = readyIndex, heldFrame == nil, let img = Self.snapshot(engine.surface) { heldFrame = (img, r) }
        readyIndex = nil
        engine.pause()
        time = 0; loadedUntil = 0
        duration = Double(episodes[i].duration)
        for k in tasks.keys where k != i && k != i + 1 {
            tasks.removeValue(forKey: k)?.cancel()
        }
        for (k, tok) in prefetchTokens where k != i {
            // Only the playing episode pre-buffers; others drop their proxy route + cache.
            LocalHLSProxy.shared.release(tok); prefetchTokens[k] = nil; prepared[k] = nil
        }
        prebuffer = nil
        for k in prepared.keys where abs(k - i) > 1 {
            prepared[k] = nil
            streams[k] = nil
            chosen[k] = nil
        }
        errors[i] = nil
        verifyTargets[i] = nil
        Task { [self] in
            guard activation == token else { return }
            do {
                let src = try await prepare(i)
                guard activation == token else { return }
                let next = chooseEngine(i, src)
                switchEngine(to: next)
                tech = nil; videoSize = .zero; audioOptions = []; subtitleOptions = []; audioSelected = nil; subtitleSelected = nil; subtitleText = nil
                thumbnails.attach(Self.thumbnailAsset(src, stream: chosen[i]))
                var start: Double?
                if let p = LibraryStore.shared.progress(for: series), p.episodeIndex == i, p.seconds > 3,
                   p.duration <= 0 || p.seconds < p.duration - 5 { start = p.seconds }
                let title = detail?.title ?? series.title
                next.onFailed = { [weak self, weak next] err in
                    guard let self, let next, self.activation == token, self.engine === next else { return }
                    self.playbackFailed(i, src: src, engine: next.kind, error: err)
                }
                next.onEnded = { [weak self, weak next] in
                    guard let self, let next, self.activation == token, self.engine === next, self.current == i else { return }
                    if i + 1 < self.episodes.count, self.autoNext { self.select(i + 1) } else { self.setPaused(true) }
                }
                do {
                    try await next.load(src, stream: chosen[i], start: start, title: title)
                } catch is CancellationError {
                    return
                } catch where next.kind == .aether {
                    guard activation == token else { return }
                    playbackFailed(i, src: src, engine: .aether, error: error)
                    return
                }
                guard activation == token else { return }
                #if DEBUG
                NSLog("%@", "[STV] item prepared engine=\(next.kind.short) src=\(String(describing: src).prefix(120))")
                #endif
                next.muted = muted
                readyIndex = i
                refreshPiP()
                if !paused, !interrupted { play() }
                if episodes.indices.contains(i + 1) {
                    Task { [weak self] in
                        guard let self, self.activation == token else { return }
                        if let nextSrc = try? await self.prepare(i + 1), self.engineKind == .aether {
                            await Self.prewarm(nextSrc)
                        }
                    }
                }
                if series.source == .hongguo {
                    // Warm play URLs for the next few episodes (180 s cache) so swipes resolve instantly.
                    let next = episodes.indices.filter { $0 > i && $0 <= i + 3 }.map { episodes[$0].vid }
                    Task { await HongguoClient.shared.prefetch(vids: next) }
                }
                // 不激活 Now Playing 会话、不发布媒体信息。
            } catch {
                guard activation == token else { return }
                errors[i] = error.localizedDescription
                verifyTargets[i] = error.verifyTarget
            }
        }
    }

    /// Aether unless the user chose the system player, the source needs AVPlayer (resource-loader AES,
    /// FairPlay), Aether could not be created, or it already failed on this episode.
    private func chooseEngine(_ i: Int, _ src: Source) -> PlayerEngine {
        guard PlayerEngineSettings.preferred == .aether, !aetherFailed.contains(i),
              let a = aether, a.supports(src) else { return av }
        return a
    }

    private func switchEngine(to next: PlayerEngine) {
        if next !== engine {
            engine.onFailed = nil; engine.onEnded = nil
            engine.stop()
            engine = next
        }
        engine.fill = fill
        if engineKind != next.kind { engineKind = next.kind }
    }

    /// Fallback ladder: Aether → AVPlayer on the same source → AVPlayer straight from the CDN
    /// (loopback-proxied MP4, fresh resolve) → error with the code.
    private func playbackFailed(_ i: Int, src: Source, engine kind: PlayerEngineKind, error: Error?) {
        readyIndex = nil
        if kind == .aether {
            #if DEBUG
            NSLog("%@", "[STV] aether fallback → AVPlayer: \(error?.localizedDescription ?? "-")")
            #endif
            aetherFailed.insert(i)
            activate(i)
            return
        }
        if Self.canBypassProxy(src, stream: chosen[i]), !bypassProxy.contains(i) {
            #if DEBUG
            NSLog("%@", "[STV] proxy fallback → direct CDN")
            #endif
            bypassProxy.insert(i)
            if let tok = prefetchTokens.removeValue(forKey: i) { LocalHLSProxy.shared.release(tok) }
            prepared[i] = nil
            chosen[i] = nil
            activate(i)
            return
        }
        prepared[i] = nil
        chosen[i] = nil
        errors[i] = Self.failureText(error)
    }

    /// What the video surface shows right now (Metal / sample-buffer layers included).
    static func snapshot(_ v: UIView) -> UIImage? {
        guard v.bounds.width > 0, v.bounds.height > 0, v.window != nil else { return nil }
        let f = UIGraphicsImageRendererFormat.preferred(); f.opaque = true
        return UIGraphicsImageRenderer(bounds: v.bounds, format: f).image { _ in
            _ = v.drawHierarchy(in: v.bounds, afterScreenUpdates: false)
        }
    }

    /// Scrub thumbnails come from a separate AVURLAsset (any core): files and progressive MP4s only.
    static func thumbnailAsset(_ src: Source, stream: HGStream?) -> AVAsset? {
        switch src {
        case .file(let u): return AVURLAsset(url: u)
        case .direct(let u):
            guard u.path.hasSuffix(".mp4") else { return nil }
            return AVURLAsset(url: u, options: [AVURLAssetOverrideMIMETypeKey: "video/mp4"])
        case .remote(let u, let h):
            return AVURLAsset(url: u, options: [AVURLAssetOverrideMIMETypeKey: "video/mp4", "AVURLAssetHTTPHeaderFieldsKey": h])
        case .stream(let loader, let s):
            guard let u = URL(string: s), !AetherPlayerEngine.isPlaylist(u) else { return nil }
            return AVURLAsset(url: u, options: ["AVURLAssetHTTPHeaderFieldsKey": loader.headers])
        }
    }

    /// Preload the next episode's opening bytes into Aether (remote progressive sources only).
    nonisolated static func prewarm(_ src: Source) async {
        let target: (URL, [String: String])?
        switch src {
        case .remote(let u, let h): target = (u, h)
        case .stream(let loader, let s):
            if let u = URL(string: s), !AetherPlayerEngine.isPlaylist(u), loader.keyHex.isEmpty { target = (u, loader.headers) } else { target = nil }
        default: target = nil
        }
        guard let (u, h) = target else { return }
        _ = await AetherEngine.prewarm(url: u, httpHeaders: AetherPlayerEngine.headers(h, for: u))
    }

    private func prepare(_ i: Int) async throws -> Source {
        if let source = prepared[i] {
            if case .file(let url) = source, !FileManager.default.fileExists(atPath: url.path) {
                prepared[i] = nil
            } else { return source }
        }
        if let t = tasks[i] { return try await t.value }
        let ep = episodes[i]
        let series = self.series
        let want = chosen[i]
        let preferredId = SitePrefs.preferredStream[series.id]
        let bypass = bypassProxy.contains(i)
        let viaAether = PlayerEngineSettings.preferred == .aether && !aetherFailed.contains(i) && aether != nil
        let task = Task<Source, Error> { [weak self] in
            var stream = want
            if stream == nil {
                let r = try await Catalog.resolve(series, ep)
                if let e = r.err, r.streams.isEmpty { throw ProviderError.upstream(e) }
                guard let best = r.streams.first(where: { $0.id == preferredId }) ?? r.streams.first else { throw ProviderError.upstream("没有可用的视频流") }
                try Task.checkCancellation()
                self?.streams[i] = r.streams
                self?.chosen[i] = best
                stream = best
            }
            guard let s = stream else { throw ProviderError.upstream("没有可用的视频流") }
            switch s.kind {
            case .mp4Cenc:
                // Already decrypted on disk → play the file. Otherwise stream-decrypt through the proxy; the
                // whole-file download + decrypt is only the fallback (not streamable, or streaming failed once).
                if let f = EpisodePlaybackLoader.cachedFile(vid: ep.vid, stream: s) { return .file(f) }
                if !bypass, let src = await self?.cencStream(s, index: i) { return src }
                return .file(try await EpisodePlaybackLoader.playableFile(vid: ep.vid, stream: s))
            case .mp4Clear where s.url.hasPrefix("tdfile://"):
                // TDLib video: plain progressive file from the loopback proxy, downloaded window by window.
                guard let ref = TDFileRef(s.url) else { throw ProviderError.invalid(s.url) }
                let source = try await TDLibService.shared.streamSource()
                guard let src = await self?.tdStream(ref, source: source, budget: BufferSettings.budget(for: series.source), index: i) else {
                    throw ProviderError.upstream("本地播放代理启动失败")
                }
                return src
            case .mp4Clear where s.url.hasPrefix("file://"):
                // Downloaded (offline) Telegram video.
                guard let u = URL(string: s.url) else { throw ProviderError.upstream("文件地址无效") }
                return .file(u)
            case .mp4Clear where bypass:
                guard let u = URL(string: s.url) else { throw ProviderError.invalid(s.url) }
                return .remote(u, s.headers)
            case .mp4Clear, .hls, .hlsAES:
                let budget = bypass ? 0 : BufferSettings.budget(for: series.source)
                if budget > 0, let proxied = await self?.prebuffered(s, budget: budget, index: i) { return proxied }
                if viaAether, s.kind != .mp4Clear, s.key.isEmpty {
                    // Aether hands HLS to AVPlayer on a URL: keep our playlist rewriting (ad-strip, headers,
                    // SupJav fake-PNG) by serving it from the loopback proxy.
                    if LocalHLSProxy.token(of: s.url) != nil, let u = URL(string: s.url) { return .direct(u) }
                    if let p = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: 0),
                       let u = URL(string: p) { return .direct(u) }
                }
                return .stream(HLSPlayback(headers: s.headers, keyHex: s.key), s.url)
            }
        }
        tasks[i] = task
        defer { if tasks[i] == task { tasks[i] = nil } }
        let src = try await task.value
        guard tasks[i] == task, !task.isCancelled else { throw CancellationError() }
        prepared[i] = src
        return src
    }

    /// Long-video sources go through the loopback proxy so segments / MP4 ranges come from the byte
    /// pre-buffer. AES-128 streams with an out-of-band key keep the resource-loader path.
    private func prebuffered(_ s: HGStream, budget: Int, index i: Int) -> Source? {
        if let tok = LocalHLSProxy.token(of: s.url) {           // SupJav: already proxied
            LocalHLSProxy.shared.enablePrefetch(tok, budget: budget)
            prefetchTokens[i] = tok
            return nil
        }
        let proxied: String?
        switch s.kind {
        case .hls: proxied = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: budget)
        case .hlsAES where s.key.isEmpty: proxied = LocalHLSProxy.shared.playlistURL(s.url, headers: s.headers, strip: false, budget: budget)
        case .mp4Clear: proxied = LocalHLSProxy.shared.mp4URL(s.url, headers: s.headers, budget: budget)
        default: proxied = nil
        }
        guard let proxied, let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    /// 红果 CENC MP4 served decrypted by the loopback proxy while it downloads (nil: not streamable).
    private func cencStream(_ s: HGStream, index i: Int) async -> Source? {
        guard let proxied = LocalHLSProxy.shared.cencMP4URL(s.url, headers: s.headers, key: s.key, budget: Self.cencBudget),
              let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        do {
            try await LocalHLSProxy.shared.prepareCENC(proxied)
        } catch {
            #if DEBUG
            NSLog("%@", "[STV] cenc stream unavailable → full download: \(error.localizedDescription)")
            #endif
            LocalHLSProxy.shared.release(tok)
            return nil
        }
        if let old = prefetchTokens[i], old != tok { LocalHLSProxy.shared.release(old) }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    /// Read-ahead for a streamed 红果 episode (a 1080p episode is roughly 5–40 MB).
    static let cencBudget = 64 * 1_048_576

    private func tdStream(_ ref: TDFileRef, source: TDFileStreamSource, budget: Int, index i: Int) -> Source? {
        guard let proxied = LocalHLSProxy.shared.tdURL(ref, budget: budget, source: source),
              let u = URL(string: proxied), let tok = LocalHLSProxy.token(of: proxied) else { return nil }
        prefetchTokens[i] = tok
        return .direct(u)
    }

    func choose(_ s: HGStream, for i: Int) {
        guard chosen[i] != s else { return }
        if let tok = prefetchTokens.removeValue(forKey: i) { LocalHLSProxy.shared.release(tok) }
        tasks.removeValue(forKey: i)?.cancel()
        chosen[i] = s; prepared[i] = nil
        if current == i { activate(i) }
    }

    // MARK: Controls

    func togglePause() {
        setPaused(!paused)
    }

    private func setPaused(_ value: Bool) {
        paused = value
        if value {
            engine.pause()
            save(force: true)
        } else if !interrupted, readyIndex != nil {
            if duration > 0, engine.currentTime >= duration - 0.15, engine.kind == .avplayer { seek(fraction: 0) }
            play()
        }
        publishNowPlaying()
    }

    private func play() {
        activateAudioSession()
        engine.play(rate: min(playbackRate, engine.maxRate))
    }

    func setMuted(_ on: Bool) {
        muted = on
        engine.muted = on
    }

    /// ±10 s buttons / double tap.
    func seek(by seconds: Double) {
        guard duration > 0 else { return }
        let t = max(0, min(duration - 0.5, engine.currentTime + seconds))
        seek(fraction: t / duration)
    }

    private func refreshMediaOptions() {
        let a = engine.audioTracks, st = engine.subtitleTracks
        if a != audioOptions { audioOptions = a }
        if st != subtitleOptions { subtitleOptions = st }
        if engine.selectedAudio != audioSelected { audioSelected = engine.selectedAudio }
        if engine.selectedSubtitle != subtitleSelected { subtitleSelected = engine.selectedSubtitle }
    }

    /// Track ids are `MediaTrack.id`; nil subtitle = off.
    func selectAudio(_ id: Int) { engine.selectAudio(id); audioSelected = id }
    func selectSubtitle(_ id: Int?) { engine.selectSubtitle(id); subtitleSelected = id }

    func seek(fraction: Double) {
        guard duration > 0 else { return }
        let target = duration * fraction
        time = target
        Task { await engine.seek(to: target) }
        updatePrefetch(at: target, force: true)
    }

    /// Follow the playhead (seek restarts prefetch from the new position) and refresh the HUD figure.
    private func updatePrefetch(at t: Double, force: Bool = false) {
        guard let i = readyIndex, let tok = prefetchTokens[i] else { return }
        guard force || Date().timeIntervalSince(lastPrefetchPoll) > 1 else { return }
        lastPrefetchPoll = Date()
        if let td = LocalHLSProxy.shared.tdSession(tok) {
            // TDLib stream: seek moves the download window; the buffer layer = bytes TDLib has from here on.
            let d = duration, frac = d > 0 ? t / d : 0
            Task { [weak self] in
                if force { await td.seek(fraction: frac) }
                guard let b = await td.buffered(from: frac), d > 0 else { return }
                await MainActor.run {
                    guard let self, self.readyIndex == i, self.prefetchTokens[i] == tok else { return }
                    self.prebuffer = (b.bytes, min(d, b.until * d), false)
                }
            }
            return
        }
        // MP4 units are chunk indices; map playback time to a chunk by fraction of the duration.
        let pos: Double
        if chosen[i]?.kind.isProgressiveMP4 == true, duration > 0, let st = LocalHLSProxy.shared.status(tok), st.total >= 0 {
            pos = (t / duration) * Double(LocalHLSProxy.shared.mp4Chunks(tok))
        } else { pos = t }
        LocalHLSProxy.shared.setPosition(tok, pos)
        if let st = LocalHLSProxy.shared.status(tok) {
            let until = chosen[i]?.kind.isProgressiveMP4 == true
                ? min(duration, st.until / Double(max(1, LocalHLSProxy.shared.mp4Chunks(tok))) * duration) : st.until
            prebuffer = (st.bytes, until, st.lowStorage)
        }
    }

    private func tick() {
        guard readyIndex != nil else { return }
        let t = engine.currentTime
        guard t.isFinite else { return }
        time = t
        let waiting = engine.isWaiting
        if waiting != waitingForMedia { waitingForMedia = waiting }
        let d = engine.duration
        if d.isFinite, d > 0, abs(d - duration) > 0.01 { duration = d }
        let s = engine.presentationSize
        if s.width > 0, s != videoSize { videoSize = s }
        let sub = engine.subtitleText
        if sub != subtitleText { subtitleText = sub }
        loadedUntil = engine.bufferedUntil
        if Date().timeIntervalSince(lastTech) > 2, let i = readyIndex {
            lastTech = Date()
            let stream = chosen[i]
            let eng = engine
            Task { [weak self] in
                let t = await eng.tech(stream: stream)
                guard let self, self.engine === eng else { return }
                if t != self.tech { self.tech = t }
                self.refreshMediaOptions()
                self.refreshPiP()
                self.publishNowPlaying()
            }
        }
        updatePrefetch(at: t)
        save(force: false)
    }

    private func save(force: Bool) {
        guard let i = readyIndex, episodes.indices.contains(i) else { return }
        guard force || Date().timeIntervalSince(lastSave) > 4 else { return }
        let seconds = force ? engine.currentTime : time
        guard seconds.isFinite, seconds > 0 else { return }
        lastSave = Date()
        // Deep links / season switches may carry a bare series; fill title & cover from the loaded detail.
        var rec = series
        if let d = detail {
            if rec.title.isEmpty { rec.title = d.title }
            if rec.cover.isEmpty { rec.cover = d.cover }
            if rec.episodeCount == 0 { rec.episodeCount = max(d.totalEps, d.episodes.count) }
        }
        LibraryStore.shared.record(rec, index: i, ep: episodes[i].ep, seconds: seconds, duration: duration)
    }
}
