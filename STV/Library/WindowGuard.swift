import Foundation

/// 已停用。
///
/// 这里曾经有一个每秒扫描 `UIWindow.subviews`、把类名含 "TransitionView" 的视图
/// 隐藏 / 关闭交互的「自愈守卫」。那是错的：
///
/// iOS 的窗口层级是 `UIWindow -> UITransitionView -> rootViewController.view`。
/// `UITransitionView` 是 **UIKit 用来承载整个 App 界面的正常系统容器**，从启动到退出
/// 一直存在，而且大多数时候没有动画在跑。用类名字符串去匹配并隐藏它，等于把整个界面
/// 藏起来 —— 启动后第一次 tick（1 秒）就必然黑屏；只关交互时则是「画面正常、
/// 轮播还在动、点哪都没反应」。用户从 2.6 起遇到的所有「卡死 / 黑屏」都出自这里。
///
/// 现在整个机制不再运行。播放器只是 SwiftUI overlay 的插入与移除，不碰任何 UIKit
/// 窗口层级。保留类型和两个空实现，避免改动工程文件与调用点。
enum WindowGuard {
    static func start() {}
    static func relayout() {}
}
