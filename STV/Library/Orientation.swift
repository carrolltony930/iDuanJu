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
        // 只有当前真的是横屏才发旋转请求。竖屏状态下也调 requestGeometryUpdate 会白白开一次
        // 旋转事务；如果此时正好在 pop 动画里，主线程会一直等它，表现为「返回后卡死」。
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           !scene.interfaceOrientation.isPortrait {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
        refresh()
    }
    @MainActor private static func refresh() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for win in scene.windows {
                win.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        }
    }
}
