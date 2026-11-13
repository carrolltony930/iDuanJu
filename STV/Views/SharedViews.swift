import SwiftUI
import UIKit

enum Fmt {
    static func count(_ n: Int) -> String {
        if n >= 100_000_000 { return String(format: "%.1f亿", Double(n) / 100_000_000) }
        if n >= 10_000 { return String(format: "%.1f万", Double(n) / 10_000) }
        return "\(n)"
    }
    static func duration(_ sec: Int) -> String { String(format: "%d:%02d", sec / 60, sec % 60) }
    /// h:mm:ss for long videos (mm:ss under an hour).
    static func clock(_ sec: Int) -> String { sec >= 3600 ? String(format: "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60) : duration(sec) }
    static func bytes(_ n: Int) -> String { ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file) }
    /// Short meta line, e.g. "剧 / 80集" or "1.2万播放".
    static func meta(_ s: HGSeries) -> String {
        if s.source.isLongVideo {
            return [s.duration > 0 ? clock(s.duration) : nil, s.playCount > 0 ? count(s.playCount) + "观看" : nil]
                .compactMap { $0 }.joined(separator: " · ")
        }
        if s.episodeCount > 0 && s.playCount > 0 { return "\(s.episodeCount)集 · \(count(s.playCount))热度" }
        if s.episodeCount > 0 { return "\(s.episodeCount)集" }
        if s.playCount > 0 { return count(s.playCount) + "热度" }
        return s.subtitle ?? ""
    }
}

// MARK: Navigation

/// A rank board: a titled, pageable list (hongguo rank sub or huangguo channel).
struct BoardSpec: Hashable, Identifiable {
    enum Kind: Hashable { case hongguo(HGRankGenre, HGRankSub), huangguo(HuangguoChannel), site(DramaSource, SiteCategory) }
    let kind: Kind
    var id: String {
        switch kind {
        case .hongguo(let g, let s): "hg-\(g.rawValue)-\(s.rawValue)"
        case .huangguo(let c): "hu-" + c.id
        case .site(let src, let c): "site-\(src.rawValue)-\(c.id)"
        }
    }
    var title: String {
        switch kind {
        case .hongguo(let g, let s): (g == .all ? "" : g.label) + s.label + "榜"
        case .huangguo(let c): c.title.hasSuffix("榜") ? c.title : c.title + "榜"
        case .site(_, let c): c.title
        }
    }
    func fetch(_ prev: HGPage, _ seen: [HGSeries]) async throws -> HGPage {
        switch kind {
        case .hongguo(let g, let s):
            return try await HongguoClient.shared.rank(sub: s, genre: g, offset: prev.nextOffset ?? 0, seenIds: seen.map(\.seriesId))
        case .huangguo(let c):
            return try await HuangguoClient.shared.list(c, page: max(1, prev.nextOffset ?? 1))
        case .site(let src, let c):
            #if DEBUG
            if let fx = HeroFixtures.page(src, c) { return fx }
            #endif
            guard let p = Sites.provider(src) else { throw ProviderError.unsupported(src.label) }
            return try await p.list(c, page: max(1, prev.nextOffset ?? 1))
        }
    }
}

enum Route: Hashable {
    case series(HGSeries)
    case board(BoardSpec)
    case play(HGSeries, Int?)
    case actor(HGCelebrity)
    case search
    case searchFor(String)
    /// 从首页某个站源里点搜索：直接在该站源里搜，而不是全局搜索。
    case searchIn(DramaSource)
    case actorRank
    case favorites
    case history
    case sources
    case licenses
    case channels
    case tgManage
    case tgRules
    case tgSearch(String?)
    case downloads
    case tdlib
    case mangaHome
    case manga(MangaItem)
    case mangaLibrary
    case tdChat(Int64, String)
    case plugins
}

struct ZoomNamespaceKey: EnvironmentKey { static let defaultValue: Namespace.ID? = nil }
extension EnvironmentValues {
    var zoomNS: Namespace.ID? { get { self[ZoomNamespaceKey.self] } set { self[ZoomNamespaceKey.self] = newValue } }
}

extension View {
    /// iOS 18+ zoom transition source; no-op on 17.
    @ViewBuilder func zoomSource(_ id: String, _ ns: Namespace.ID?) -> some View {
        if #available(iOS 18.0, *), let ns { self.matchedTransitionSource(id: id, in: ns) } else { self }
    }
    @ViewBuilder func zoomDestination(_ id: String, _ ns: Namespace.ID?) -> some View {
        if #available(iOS 18.0, *), let ns { self.navigationTransition(.zoom(sourceID: id, in: ns)) } else { self }
    }
    func routes() -> some View {
        navigationDestination(for: Route.self) { r in
            switch r {
            case .series(let s): DramaDetailView(series: s)
            case .board(let b): BoardListView(board: b)
            // 兜底分支：正常路径已全部改走 PlayerPresenter.fullScreenCover（见 PlayerPresenter
            // 注释）。万一还有代码 push 到这里，宁可什么都不做，也不要再进导航栈。
            case .play: EmptyView()
            case .actor(let a): ActorView(actor: a)
            case .search: SearchView(pushed: true)
            case .searchFor(let q): SearchView(pushed: true, initialQuery: q)
            case .searchIn(let s): SearchView(pushed: true, initialSource: s)
            case .actorRank: ActorRankView()
            case .favorites: LibraryListView(kind: .favorites)
            case .history: LibraryListView(kind: .history)
            case .sources: SourcesView()
            case .licenses: LicensesView()
            case .channels: ChannelFeedView()
            case .tgManage: TGManageView()
            case .tgRules: TGRulesView()
            case .tgSearch(let c): TGSearchView(channel: c)
            case .downloads: DownloadsView()
            case .tdlib: TDLibSettingsView()
            case .mangaHome: MangaHomeView()
            case .manga(let m): MangaDetailView(item: m)
            case .mangaLibrary: MangaLibraryView()
            case .tdChat(let id, let t): TDChatView(chatId: id, title: t)
            case .plugins: PluginsView()
            }
        }
    }
}

/// Keep the interactive edge-swipe pop working when the nav bar is hidden.
/// Full-screen player gestures must not disable UINavigationController.interactivePopGestureRecognizer.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.isEnabled = true
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        // 转场动画进行中，绝不允许开启交互式返回。
        // UIKit 会把进行中的 push/pop 切成 interactive，之后没有任何人再给它 update / end，
        // 转场就停在半路：导航栏已经换了、内容只推进一半、转场容器留在窗口上吃掉全部触摸，
        // 而底层 SwiftUI 动画还在跑 —— 用户看到的就是「黑一块、点哪都没反应、图还在动」。
        if let tc = transitionCoordinator, tc.isAnimated { return false }
        guard viewControllers.count > 1 else { return false }
        guard g == interactivePopGestureRecognizer,
              let pan = g as? UIPanGestureRecognizer else {
            return viewControllers.count > 1
        }
        // Prefer horizontal rightward edge pans; ignore mostly-vertical flicks (episode paging).
        let v = pan.velocity(in: view)
        if abs(v.y) > abs(v.x) * 1.35 { return false }
        return true
    }

    public func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // Exclusive: an edge swipe that pops must not also scroll the page underneath (detail / lists).
        false
    }

    public func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        // Other pans wait for edge-pop to fail first (edge-back wins over page swipe / speed).
        gestureRecognizer == interactivePopGestureRecognizer
    }

    public func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRequireFailureOf otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        false
    }
}

/// Re-enable interactive pop on appear (SwiftUI may leave it disabled when the bar is hidden).
struct InteractivePopEnabler: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { PopEnablerView() }
    func updateUIView(_ uiView: UIView, context: Context) {
        (uiView as? PopEnablerView)?.enablePop()
    }

    final class PopEnablerView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            enablePop()
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            enablePop()
        }
        func enablePop() {
            guard let nav = nearestNavigationController() else { return }
            nav.interactivePopGestureRecognizer?.isEnabled = true
            nav.interactivePopGestureRecognizer?.delegate = nav
        }
        private func nearestNavigationController() -> UINavigationController? {
            var r: UIResponder? = self
            while let cur = r {
                if let nav = cur as? UINavigationController { return nav }
                if let vc = cur as? UIViewController, let nav = vc.navigationController { return nav }
                r = cur.next
            }
            return nil
        }
    }
}

// MARK: Common views

struct ErrorStateView: View {
    let error: Error
    let retry: () -> Void
    @State private var verify: VerifyTarget?
    var body: some View {
        ContentUnavailableView {
            Label(error.verifyTarget == nil ? "加载失败" : "需要站点验证（自动验证未通过）",
                  systemImage: error.verifyTarget == nil ? "wifi.exclamationmark" : "shield.lefthalf.filled")
        } description: {
            Text(error.localizedDescription)
        } actions: {
            if let t = error.verifyTarget {
                Button("去验证") { verify = t }.buttonStyle(.borderedProminent).tint(STVTheme.accent)
                Button("重试") { CFSolver.shared.resetCooldown(); retry() }.tint(STVTheme.secondary)
            } else {
                Button("重试") { CFSolver.shared.resetCooldown(); retry() }.buttonStyle(.borderedProminent).tint(STVTheme.accent)
            }
        }
        .sheet(item: $verify) { t in SiteVerifySheet(target: t, onDone: retry) }
        .task {
            #if DEBUG
            // Screenshot hook: STV_AUTO_VERIFY=1 opens the verify sheet straight away.
            if ProcessInfo.processInfo.environment["STV_AUTO_VERIFY"] == "1", let t = error.verifyTarget { verify = t }
            #endif
        }
    }
}

enum Catalog {
    @MainActor private static var details: [String: HGSeriesDetail] = [:]
    @MainActor static func detail(_ s: HGSeries) async throws -> HGSeriesDetail {
        if let d = details[s.id] { return d }
        let d: HGSeriesDetail
        switch s.source {
        case .hongguo: d = try await HongguoClient.shared.detail(seriesId: s.seriesId, web: false)
        case .huangguo: d = try await HuangguoClient.shared.detail(s)
        default:
            guard let p = Sites.provider(s.source) else { throw ProviderError.unsupported(s.source.label) }
            d = try await p.detail(s)
        }
        details[s.id] = d
        return d
    }

    /// 红果 second pass: cast / tags / rating from the official site, merged into the cached detail.
    @MainActor static func enriched(_ s: HGSeries, _ d: HGSeriesDetail) async -> HGSeriesDetail? {
        guard s.source == .hongguo, d.cast.isEmpty, d.tags.isEmpty,
              let w = try? await HongguoClient.shared.webDetail(seriesId: s.seriesId) else { return nil }
        let out = HongguoClient.enrich(details[s.id] ?? d, with: w)
        details[s.id] = out
        return out
    }
    static func resolve(_ s: HGSeries, _ ep: HGEpisode) async throws -> HGResolvedVideo {
        switch s.source {
        case .hongguo: return try await HongguoClient.shared.resolve(vid: ep.vid)
        case .huangguo: return try await HuangguoClient.shared.resolve(seriesId: s.seriesId, episode: ep)
        default:
            guard let p = Sites.provider(s.source) else { throw ProviderError.unsupported(s.source.label) }
            return try await p.resolve(s, ep)
        }
    }
}

#if DEBUG
/// DEBUG `STV_FIXTURE_SITES=1`: long-video boards return neutral placeholder items whose covers are
/// solid colours (`fixture://RRGGBB`), so hero/grid layout can be screenshotted without any real site imagery.
enum HeroFixtures {
    static var on: Bool { ProcessInfo.processInfo.environment["STV_FIXTURE_SITES"] == "1" }
    static let colors = ["3D5A80", "98C1D9", "EE6C4D", "293241", "6A994E", "BC4749", "F2CC8F", "81B29A"]
    static func page(_ s: DramaSource, _ c: SiteCategory) -> HGPage? {
        guard on else { return nil }
        let items = (0..<8).map { i in
            HGSeries(source: s, seriesId: "fx-\(c.id)-\(i)", title: "示例影片 \(c.title) 第 \(i + 1) 部（占位数据）",
                     cover: "fixture://" + colors[(i + c.id.count) % colors.count], subtitle: "2026-11-0\(i % 9 + 1)",
                     duration: 1800 + i * 431, playCount: 12_000 * (i + 1), orientation: "landscape",
                     tags: ["标签一", "标签二", "高清"], page: nil)
        }
        return HGPage(items: items, hasMore: false)
    }
}
#endif
