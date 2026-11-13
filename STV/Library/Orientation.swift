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
    @MainActor private static func refresh() {
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
