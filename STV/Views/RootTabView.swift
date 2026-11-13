import SwiftUI

/// 四个根页面 + PlutoFish 式底部悬浮玻璃胶囊导航（系统 TabBar 隐藏）。
/// 胶囊只在根页面显示；进入详情 / 播放器等二级页面时隐藏。
struct RootTabView: View {
    @State private var tab = Int(ProcessInfo.processInfo.environment["STV_TAB"] ?? "") ?? 0
    /// 播放器走 fullScreenCover，不走 NavigationStack push。原因见 PlayerPresenter 注释：
    /// push 转场会被方向刷新 / 边缘返回手势打断，停在半路就会「下半屏黑、触摸全死、图还在动」。
    @ObservedObject private var player = PlayerPresenter.shared
    @State private var paths = [NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath()]
    static let items: [(String, String)] = [("首页", "play.house.fill"), ("搜索", "magnifyingglass"),
                                            ("找剧", "square.grid.2x2.fill"), ("我的", "person.crop.circle.fill")]
    var body: some View {
        TabView(selection: $tab) {
            Stack(path: $paths[0]) { HomeView() }.tag(0)
            Stack(path: $paths[1]) { SearchView() }.tag(1)
            Stack(path: $paths[2]) { CategoryView() }.tag(2)
            Stack(path: $paths[3]) { ProfileView() }.tag(3)
        }
        .tint(STVTheme.accent)
        .overlay(alignment: .bottom) {
            // 动画只能加在 overlay 自己的容器里。之前挂在整个 TabView 上，
            // 每次 push（path 由空变非空）都会连带 NavigationStack 的转场一起做隐式动画，
            // iOS 17 上两个事务撞在一起会让 push 卡在「导航栏已出、内容没换」的中间态。
            ZStack {
                if paths[tab].isEmpty {
                    GlassTabBar(selection: $tab, items: Self.items) { i in
                        if i == tab { paths[i] = NavigationPath() }
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.32, dampingFraction: 0.86), value: paths[tab].isEmpty)
            .allowsHitTesting(paths[tab].isEmpty)
        }
        .onAppear {
            Router.handler = { r in
                // 任何地方想 push 播放器，都改成全屏 modal —— push 转场在 iOS 17 上会被
                // 方向刷新和边缘返回手势打断，停在半路就是「下半屏黑、触摸全死」。
                if case .play(let s, let i) = r { PlayerPresenter.shared.present(s, at: i); return }
                paths[tab].append(r)
            }
            // 回到根页面后纠正一次导航栏：转场被打断时 UIKit 会把 Back 留在首页左上角。
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { MainActor.assumeIsolated { NavBarHealer.healIfRoot() } }
            #if DEBUG
            if ProcessInfo.processInfo.environment["STV_TD_OPEN"] == "1" { Router.push(.tdlib) }
            #endif
        }
        // pop 回根页面、或切 tab 到根页面之后，再纠正一次。
        .onChange(of: paths[tab].isEmpty) { _, empty in
            guard empty else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { MainActor.assumeIsolated { NavBarHealer.healIfRoot() } }
        }
        .onChange(of: tab) { _, _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { MainActor.assumeIsolated { NavBarHealer.healIfRoot() } }
        }
        // 播放器：全屏 modal。关闭后顺手纠正一次导航栏（历史版本 push 残留的 Back）。
        .fullScreenCover(item: $player.route) { r in
            VerticalPlayerView(series: r.series, startIndex: r.startIndex)
        }
        .onChange(of: player.route) { _, v in
            guard v == nil else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { MainActor.assumeIsolated { NavBarHealer.healIfRoot() } }
            // 关闭动画彻底结束后再放开可能残留的转场容器，否则整屏触摸会一直没人接收。
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                MainActor.assumeIsolated { NavBarHealer.releaseStuckTransitionLayers() }
            }
        }
        // Cold start: restore a saved TDLib login (no network login, nothing is sent).
        .task { await TDLibService.shared.bootstrap() }
    }
}

private struct Stack<V: View>: View {
    @Binding var path: NavigationPath
    @ViewBuilder var content: V
    @Namespace private var ns
    var body: some View {
        NavigationStack(path: $path) {
            content
                .toolbar(.hidden, for: .tabBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: GlassTabBar.clearance) }
                .routes()
        }.environment(\.zoomNS, ns)
    }
}

struct GlassTabBar: View {
    static let clearance: CGFloat = 72
    @Binding var selection: Int
    let items: [(String, String)]
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        HStack(spacing: 4) {
            ForEach(items.indices, id: \.self) { i in
                let on = i == selection
                Button {
                    if on { onReselect(i) } else { withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) { selection = i } }
                } label: {
                    VStack(spacing: 2) {
                        Image(systemName: items[i].1).font(.system(size: 18, weight: on ? .semibold : .regular))
                        Text(items[i].0).font(.system(size: 11, weight: on ? .semibold : .medium))
                    }
                    .foregroundStyle(on ? STVTheme.text : STVTheme.secondary)
                    .frame(maxWidth: .infinity).frame(height: 48)
                    .background {
                        if on { Capsule().fill(STVTheme.text.opacity(0.1)).matchedGeometryEffect(id: "pill", in: ns) }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(items[i].0)
                .accessibilityIdentifier("tab.\(i)")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(6)
        .frame(maxWidth: 400)
        .background(.ultraThinMaterial, in: Capsule())
        .background(STVTheme.surface.opacity(0.55), in: Capsule())
        .overlay {
            Capsule().strokeBorder(LinearGradient(colors: [STVTheme.text.opacity(0.18), STVTheme.text.opacity(0.05)],
                                                  startPoint: .top, endPoint: .bottom), lineWidth: 0.8)
        }
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
        .padding(.horizontal, 16)
        .padding(.bottom, 4)
    }
}
