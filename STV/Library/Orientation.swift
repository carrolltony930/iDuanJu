import SwiftUI
import UIKit

/// Portrait by default; unlock all orientations while the player is on screen.
final class OrientationGate: NSObject, UIApplicationDelegate {
    static var mask: UIInterfaceOrientationMask = .portrait
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { Self.mask }

    @MainActor static func unlock() {
        mask = .allButUpsideDown
        refresh()
    }
    /// 横屏 button: rotate now; rotation stays unlocked so turning the phone back to portrait exits.
    @MainActor static func enterLandscape() {
        mask = .allButUpsideDown
        refresh()
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
    }
    /// 关闭播放器时用：**先把画面转回竖屏，转完再回调**。
    ///
    /// 用户实测：横屏看一会儿再点返回也会卡死。原因是「播放器消失」和「窗口从横屏转回竖屏」
    /// 是两次独立的布局事务 —— 播放器先被撤掉、页面按横屏尺寸重排，紧接着系统开始旋转，
    /// 旋转一旦被这次重排打断，窗口就会停在中间尺寸。画面看起来没变（CA 动画照跑，轮播还在动），
    /// 但触摸坐标系跟着错位，于是「点哪都没反应」，并且会看到底部多出一小块黑。
    ///
    /// 所以这里把顺序定死：先转正（旋转在播放器仍占满屏幕时完成），再让播放器消失。
    /// 旋转有 completion，另外加 0.7 秒兜底，绝不因为等回调而关不掉。
    @MainActor static func exitToPortrait(completion: @escaping () -> Void) {
        var done = false
        let finish = {
            guard !done else { return }
            done = true
            mask = .portrait
            rotating = false
            completion()
        }
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { finish(); return }
        if scene.interfaceOrientation.isPortrait { finish(); return }
        // 关键：这段期间**屏蔽所有 refresh()**。
        // refresh 会调 setNeedsUpdateOfSupportedInterfaceOrientations，那也是一次方向事务；
        // 在旋转还没走完时插进来，就会把几何更新打断在半路 —— 窗口停在中间尺寸，
        // 于是「画面正常但点哪都不动」。之前 lockPortrait 在 onDisappear 后 0.5 秒触发，
        // 正好落在旋转窗口里，这就是横屏返回卡死的最后一环。
        rotating = true
        mask = .allButUpsideDown
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in finish() }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) { finish() }
    }

    /// 退出横屏 button.
    @MainActor static func exitLandscape() {
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
        mask = .allButUpsideDown
        refresh()
    }
    @MainActor static func lockPortrait() {
        mask = .portrait
        // 只改 mask + 让系统重新评估，不主动发 requestGeometryUpdate。
        // 改 mask 后系统会自己按新掩码转正；而主动请求旋转会开一个几何事务，
        // 如果这时正好在 push/pop 动画里，UIKit 会一直等它，表现为「返回卡死」或
        // 「点了视频只出现导航栏、内容不切」。这是 2.4/2.5 两次「卡死」的共同来源。
        refresh()
    }
    /// 正在做「转回竖屏」的几何更新时为 true，期间 refresh() 一律跳过。
    @MainActor private static var rotating = false

    @MainActor private static func refresh() {
        guard !rotating else { return }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for win in scene.windows {
                // 找到最上层 VC：播放器现在是 fullScreenCover 呈现的，只通知 root 有时不生效。
                var vc = win.rootViewController
                while let p = vc?.presentedViewController { vc = p }
                // 转场进行中改方向会打断 UIKit 的转场，留下吃触摸的残留层 —— 跳过这一次，
                // 调用方本来就已经把 unlock/lockPortrait 延后到转场之后了。
                if vc?.transitionCoordinator?.isAnimated == true { continue }
                vc?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        }
    }
}
