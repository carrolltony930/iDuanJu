import Foundation

/// 最近一步「正在做什么」，供主线程看门狗记录用。
enum AppTrace {
    private static let lock = NSLock()
    private static var _tag = "-"
    static var tag: String {
        lock.lock(); defer { lock.unlock() }; return _tag
    }
    static func set(_ t: String) {
        lock.lock(); _tag = t; lock.unlock()
    }
}

/// 主线程卡顿看门狗。
///
/// 后台每 0.4 秒往主队列投递一个空块并等待：正常情况立刻回来；
/// 超过 1.5 秒说明主线程被堵（同步 I/O、锁等待、死循环）。
/// 记录会写进「我的 → 站点管理 → 站点诊断日志」，可一键复制。
enum MainThreadWatchdog {
    private static var started = false
    private static let queue = DispatchQueue(label: "stv.main.watchdog", qos: .utility)

    static func start() {
        guard !started else { return }
        started = true
        queue.async {
            while true {
                let sent = Date()
                let sem = DispatchSemaphore(value: 0)
                DispatchQueue.main.async { sem.signal() }
                if sem.wait(timeout: .now() + 4) == .timedOut {
                    // 主线程 4 秒都没响应：先记一条，然后继续等它恢复
                    SiteLog.add("⛔️ 主线程无响应 >4s · 当时: \(AppTrace.tag)")
                    _ = sem.wait(timeout: .now() + 20)
                    SiteLog.add("↩️ 主线程恢复 · 当时: \(AppTrace.tag)")
                } else {
                    let dt = Date().timeIntervalSince(sent)
                    if dt > 1.5 {
                        SiteLog.add("⚠️ 主线程卡顿 \(String(format: "%.1f", dt))s · 当时: \(AppTrace.tag)")
                    }
                }
                Thread.sleep(forTimeInterval: 0.4)
            }
        }
    }
}
