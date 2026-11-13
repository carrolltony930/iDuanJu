import SwiftUI

/// 播放器的呈现路由。
///
/// 为什么不再用 NavigationStack push 播放器：
///
/// iOS 17 上 push 播放器会被三类东西打断 ——
/// 1. 方向掩码刷新（`setNeedsUpdateOfSupportedInterfaceOrientations`，OrientationGate 里每
///    次 unlock/lockPortrait 都会调）；
/// 2. 导航控制器的 `interactivePopGestureRecognizer`（本项目为了让全屏页也能边缘返回，一直
///    把它 enable 着）；
/// 3. 播放器自己挂的 7 个 UIKit 手势（tap / doubleTap / longPress / adjustPan / seekPan /
///    pinch / 边缘 pan）。
///
/// 任意一次打断，UIKit 都会把正在进行的转场切成 interactive，然后没有人再给它 update / end。
/// 结果就是 `UITransitionView` 停在半路：
///   - 导航栏已经切到播放器（左上角出现 `< Back`）
///   - 内容只推进了一部分，下半屏露出窗口黑底
///   - 那个转场容器盖在整个窗口上，吃掉所有触摸
///   - 但底下的 SwiftUI 视图树还活着，动画继续跑（所以轮播阴影还在动）
///
/// 这正好解释了「看着像卡死、其实图还在动、点哪都没反应」。
///
/// 先改成 fullScreenCover 后仍能复现（进出五六次触发，且**没有** `< Back`、画面干净、
/// 轮播还在动）—— 说明残留的不是导航转场，而是 modal 的 presentation 容器：一个透明、
/// 全屏、吃触摸的 view 盖在窗口上。
///
/// 所以最终方案是「SwiftUI 叠层」：播放器作为 RootTabView 的 overlay 插入 / 移除，
/// 完全不创建 UINavigationTransitionView 或 UIPresentationController，没有任何转场
/// 控制器可残留，也没有可被打断的动画。
struct PlayerRoute: Identifiable, Hashable {
    let id = UUID()
    let series: HGSeries
    let startIndex: Int?
    /// 呈现时刻。用来判断「关闭得太早」——见 `dismiss()`。
    let shownAt = Date()
}

final class PlayerPresenter: ObservableObject {
    static let shared = PlayerPresenter()

    /// 非 nil 时播放器以全屏 modal 呈现。
    @Published var route: PlayerRoute?

    private init() {}

    func present(_ series: HGSeries, at index: Int? = nil) {
        STVLog.w("[ui] present player")
        route = PlayerRoute(series: series, startIndex: index)
    }

    /// 关闭播放器。
    ///
    /// 用户实测的复现条件非常明确：**反复进出二十次都不触发，但「点播放之后立刻点返回」
    /// 必触发**。也就是说真正的成因不是「返回」，而是「呈现还没落地就收到关闭」——
    /// 两次状态变更叠在同一小段时间里。播放器现在虽然没有转场控制器，但插入／移除全屏视图
    /// 仍会让 UIKit 做一次布局与安全区重算；紧接着又反向撤掉，就可能留下一个已经无效、
    /// 却仍参与命中测试的层。
    ///
    /// 所以这里做一道时间隔离：呈现之后不足 500ms 就要求关闭时，等满 500ms 再真正移除。
    /// 2.9 用 260ms 仍被击穿，说明插入全屏视图（含状态栏 / 系统覆盖层切换）的完整事务
    /// 比 260ms 长。500ms 比一次系统 dismiss 动画更宽裕，正常使用（看了一会儿才退）延迟为 0。
    func dismiss() {
        guard let r = route else { return }
        let elapsed = Date().timeIntervalSince(r.shownAt)
        let wait = 0.5 - elapsed
        guard wait > 0 else { route = nil; return }
        STVLog.w(String(format: "[ui] dismiss delayed %.0fms (elapsed %.0fms)", wait * 1000, elapsed * 1000))
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            guard let self, self.route?.id == r.id else { return }
            self.route = nil
        }
    }

    /// 关闭动画结束后再把路由清空。
    ///
    /// 别在 `dismiss()` 同一帧里清：SwiftUI 收尾与系统转场收尾会撞在一起，iOS 17 上偶发
    /// 「modal 已消失但绑定还非 nil」，再次点播放就没反应。这里带 id 校验，保证只清自己那次。
    func clear(after delay: TimeInterval = 0.5) {
        let token = route?.id
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, self.route?.id == token else { return }
            self.route = nil
        }
    }
}
