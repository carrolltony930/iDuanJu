import SwiftUI
import UIKit

/// 导航栏兜底修复。
///
/// iOS 17 的 `NavigationStack` 有一个已知毛病：push 转场被中途打断（方向解锁、旋转请求、
/// 两次 dismiss 都会触发）之后，UIKit 会把导航栏留在「显示」状态，于是根页面左上角残留
/// 一个 `< Back`；同时窗口最上层可能留下一个不再移除的转场容器，它会吃掉所有触摸 ——
/// 用户看到的就是「点了视频没进去、只剩一个 Back、点哪都没反应」。
///
/// 这里只在「导航栈确实只剩一个页面」时做纠正，不碰任何业务状态，也不改 SwiftUI 的 path。
/// 调用点放在根页面 onAppear / path 变空之后，带一点延迟，避开正在进行的动画。
enum NavBarHealer {
    @MainActor static func healIfRoot() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              let nav = findNav(win.rootViewController) else { return }
        guard nav.viewControllers.count <= 1 else { return }
        if !nav.isNavigationBarHidden {
            nav.setNavigationBarHidden(true, animated: false)
        }
        // 兜底：把仍是 hidden 的导航栏手势恢复可用，避免根页面侧滑返回失效。
        nav.interactivePopGestureRecognizer?.isEnabled = nav.viewControllers.count > 1
    }

    /// 释放卡在半路的转场容器。
    ///
    /// UIKit 的 modal / navigation 转场都会在窗口上挂一个 `UITransitionView`，动画结束时由
    /// 系统自己移除。如果是被打断的转场，它可能一直留着，并且 `isUserInteractionEnabled = true`
    /// —— 整块屏幕的触摸会被它吃掉，但底下的视图树还在跑动画（轮播阴影继续动）。
    ///
    /// 这里**不删视图**（删了可能让 UIKit 内部状态错乱），只关掉它的交互。三重保险：
    /// 1. 只在没有其它 modal 呈现时执行；
    /// 2. 只处理已经没有动画在跑的容器；
    /// 3. 调用点都在关闭动画结束后 1 秒以上。
    @MainActor static func releaseStuckTransitionLayers() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first else { return }
        guard win.rootViewController?.presentedViewController == nil else { return }
        for v in win.subviews where String(describing: type(of: v)).contains("TransitionView") {
            guard v.isUserInteractionEnabled, v.layer.animationKeys() == nil else { continue }
            v.isUserInteractionEnabled = false
        }
    }

    private static func findNav(_ vc: UIViewController?) -> UINavigationController? {
        guard let vc else { return nil }
        if let n = vc as? UINavigationController { return n }
        if let n = vc.navigationController { return n }
        for c in vc.children {
            if let n = findNav(c) { return n }
        }
        return vc.presentedViewController.flatMap(findNav)
    }
}
