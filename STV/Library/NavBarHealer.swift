import Foundation

/// 已停用。
///
/// 这里曾经在每次返回 / 切 tab / 关闭播放器后遍历 `UIWindow.subviews`，把类名含
/// "TransitionView" 的视图关掉交互甚至隐藏。那是错的：iOS 用 `UITransitionView`
/// 作为 **承载整个 App 界面的正常容器**，从启动到退出一直存在，而且通常没有动画在跑。
/// 关掉它的 `isUserInteractionEnabled` 就是「画面正常、轮播还在动、点哪都没反应」；
/// 把它 `isHidden = true` 就是黑屏。
///
/// 播放器现在是纯 SwiftUI overlay，不经过任何 UIKit 转场控制器，也就不需要任何
/// 窗口级清理。保留类型和两个空实现，避免改动工程结构与调用点。
enum NavBarHealer {
    static func healIfRoot() {}
    static func releaseStuckTransitionLayers() {}
}
