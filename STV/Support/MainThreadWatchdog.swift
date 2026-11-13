import Foundation

/// 「最近一步在做什么」的埋点。iOS 17 兼容版改为彻底 no-op：
/// 旧实现是一把全局 NSLock，被播放热路径（activate/prepare/choose/prebuffer）频繁加锁，
/// 在 Swift 6.2 运行时会与 @MainActor 隔离检查叠加触发崩溃。保留符号以便旧调用点无需改动。
enum AppTrace {
    static var tag: String { "-" }
    static func set(_ t: String) {}
}

/// 主线程卡顿看门狗 —— 已停用。
///
/// 旧实现是一个 `while true` 的后台死循环：每 0.4s 向主队列投递 block 并用
/// `DispatchSemaphore` 等待，超时后从后台线程调用 `SiteLog.add`。问题在于：
///  1. `SiteLog.add` 里的共享 `DateFormatter` 非线程安全，后台与主线程并发写日志会 EXC_BAD_ACCESS；
///  2. 主线程卡住时后台再 `sem.wait(20s)`，与播放器的主线程互相拖死；
///  3. 这个包是 iOS 18 工程硬降到 17.0，Swift 6.2 运行时对这类跨线程信号量/锁的
///     执行器检查更严格，是「动不动就闪退」的高发点。
/// 纯诊断功能，无播放价值，1.8 起彻底移除。
enum MainThreadWatchdog {
    static func start() {}
}
