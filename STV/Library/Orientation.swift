import SwiftUI
import UIKit

/// 全局竖屏；只有用户主动点「横屏 / 退出横屏」按钮时才请求旋转。
///
/// 3.1 之前这里有一套「播放器出现时 unlock 方向、退出时先转正再消失、旋转期间屏蔽
/// refresh」的自动编排。它试图规避一个并不存在的「几何事务冲突」，却让每次进出播放器
/// 都要往 UIWindow / UIWindowScene 上发方向请求 —— 这正是那串「画面正常但点哪都没反应」
/// 的副作用来源之一。现在全部删掉：播放器是普通 SwiftUI 叠层，插入 / 移除不改变窗口，
/// 也不需要任何方向补偿。
final class OrientationGate: NSObject, UIApplicationDelegate {
    static var mask: UIInterfaceOrientationMask = .portrait
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { Self.mask }

    /// 横屏按钮：用户主动触发，这是唯一会发起旋转的入口之一。
    @MainActor static func enterLandscape() {
        mask = .allButUpsideDown
        refresh()
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
        }
    }

    /// 退出横屏按钮。
    @MainActor static func exitLandscape() {
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { _ in }
        }
        mask = .portrait
        refresh()
    }

    /// 关闭播放器时把掩码改回竖屏。不主动 requestGeometryUpdate —— 让系统按新的
    /// supportedInterfaceOrientations 自己收尾，避免插进任何正在进行的布局事务。
    @MainActor static func lockPortrait() {
        mask = .portrait
        refresh()
    }

    @MainActor private static func refresh() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for win in scene.windows {
                guard let vc = win.rootViewController else { continue }
                vc.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        }
    }
}
