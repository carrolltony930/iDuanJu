import SwiftUI

/// 四个根页面 + PlutoFish 式底部悬浮玻璃胶囊导航（系统 TabBar 隐藏）。
/// 胶囊只在根页面显示；进入详情 / 播放器等二级页面时隐藏。
struct RootTabView: View {
    @State private var tab = Int(ProcessInfo.processInfo.environment["STV_TAB"] ?? "") ?? 0
    /// 播放器以 SwiftUI 叠层呈现，既不走 NavigationStack push，也不走 fullScreenCover。
    /// 两种 UIKit 转场在 iOS 17 上反复开关后都可能留下透明容器吃掉触摸。详见 PlayerPresenter 注释。
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
            // 版本锚点：装上新包后打开软件，stv.log 里会有这一行，可直接确认版本。
            STVLog.w("[ui] STV_3_1_CLEAN_OVERLAY ready")
            Router.handler = { r in
                // 播放器一律走 SwiftUI 叠层，不经过任何 UIKit 转场控制器。
                if case .play(let s, let i) = r { PlayerPresenter.shared.present(s, at: i); return }
                paths[tab].append(r)
            }
            #if DEBUG
            if ProcessInfo.processInfo.environment["STV_TD_OPEN"] == "1" { Router.push(.tdlib) }
            #endif
        }
        // 播放器：直接叠在 TabView 上面的普通 SwiftUI 视图。
        //
        // 不用 push、也不用 fullScreenCover —— 这两条路都会经过 UIKit 的转场控制器
        // （UINavigationTransitionView / UIPresentationController），在 iOS 17 上被反复
        // 开关五六次后可能留下一个**透明**的容器盖在窗口上：底层首页看着完全正常、
        // 轮播还在动，但所有触摸都被它吃掉，和「卡死」一模一样。
        //
        // 叠层只有视图的插入与移除，没有转场控制器可残留，也就没有可被打断的动画。
        // 关闭是瞬时的（不加 transition），宁可少一个淡出效果，也不要多一层能卡住的东西。
        .overlay {
            if let r = player.route {
                VerticalPlayerView(series: r.series, startIndex: r.startIndex,
                                   onClose: { player.dismiss() })
                    // 每次呈现都用新的 id：连点「返回 → 再进」时，中间那次 nil 可能被
                    // SwiftUI 合并掉，id 不变就不会重建，表现成「点了没反应」。
                    .id(r.id)
                    .zIndex(1)
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
