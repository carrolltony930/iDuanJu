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
}

final class PlayerPresenter: ObservableObject {
    static let shared = PlayerPresenter()

    /// 非 nil 时播放器以全屏 modal 呈现。
    @Published var route: PlayerRoute?

    private init() {}

    func present(_ series: HGSeries, at index: Int? = nil) {
        route = PlayerRoute(series: series, startIndex: index)
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
