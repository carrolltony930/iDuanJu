import SwiftUI
import UIKit

/// 窗口 / 触摸自愈守卫（只做**不会改变画面**的修复）。
///
/// 2.9 的首版在这里踩了坑：把 `hitTest` 返回的东西当成「遮罩层」隐藏掉，而它返回的
/// 可能就是 **UIWindow 自己** —— 窗口一隐藏就是全黑、状态栏还在、点哪都没反应。
/// 用户日志里那行 `[ui] 遮罩层 UIWindow 正在吃触摸，已隐藏` 就是现场。
///
/// `hitTest` 返回 window 自己（或返回 nil）真正说明的是：**那一刻根视图不在窗口的正常位置上**，
/// 触摸点落在了 root view 之外。所以正确的修复不是「隐藏谁」，而是**把根视图摆回窗口大小**。
///
/// 现在的规则很简单：只做三件事，且都不碰画面内容。
/// 1. 根视图被隐藏 / 被禁用 → 复位这两个开关；
/// 2. 根视图 frame 与窗口 bounds 不一致 → 摆回去并重排；
/// 3. 其余只写日志（限流 3 秒一条），诊断用。
/// 绝不再修改 `window.frame`，绝不隐藏除「已失效的转场容器」以外的任何视图。
@MainActor
enum WindowGuard {
    private static var timer: Timer?
    private static var lastLog = Date.distantPast

    static func start() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 1.0, repeats: true) { _ in
            guard Thread.isMainThread else { return }
            MainActor.assumeIsolated { WindowGuard.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    /// 播放器关闭后强制主窗口重排一次，确保根视图回到窗口大小。
    /// 只碰 frame 与布局，不碰任何内容。
    static func relayout() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first else { return }
        win.setNeedsLayout()
        win.layoutIfNeeded()
        if let rv = win.rootViewController?.view,
           abs(rv.frame.width - win.bounds.width) > 1 || abs(rv.frame.height - win.bounds.height) > 1 {
            STVLog.w("[ui] relayout after close root=\(rect(rv.frame)) win=\(rect(win.bounds))")
            rv.frame = win.bounds
            rv.setNeedsLayout()
        }
    }

    private static func tick() {
        guard PlayerPresenter.shared.route == nil else { return }
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              let root = win.rootViewController else { return }
        if let p = root.presentedViewController {
            if p.isBeingPresented || p.isBeingDismissed || p.viewIfLoaded?.window != nil { return }
        }
        let rv = root.view!

        // 1. 根视图的开关被改坏
        if rv.isHidden || !rv.isUserInteractionEnabled {
            STVLog.w("[ui] root disabled hidden=\(rv.isHidden) user=\(rv.isUserInteractionEnabled) -> restore")
            rv.isHidden = false
            rv.isUserInteractionEnabled = true
        }

        // 2. 根视图没有铺满窗口 —— 这是「点哪都没反应」的直接原因
        let wb = win.bounds
        let rb = rv.frame
        if abs(rb.minX) > 1 || abs(rb.minY) > 1 || abs(rb.width - wb.width) > 1 || abs(rb.height - wb.height) > 1 {
            STVLog.w("[ui] root frame \(rect(rb)) != win \(rect(wb)) -> relayout")
            rv.frame = wb
            rv.setNeedsLayout()
            win.layoutIfNeeded()
        }

        // 3. 只诊断，不动作：命中测试拿不到内容，说明几何仍不对
        let hit = win.hitTest(CGPoint(x: wb.midX, y: wb.midY), with: nil)
        if hit == nil || hit === win {
            if Date().timeIntervalSince(lastLog) > 3 {
                lastLog = Date()
                STVLog.w("[ui] hitTest \(hit == nil ? "nil" : "window") win=\(rect(wb)) root=\(rect(rv.frame)) scene=\(rect(scene.coordinateSpace.bounds)) masked=\(rv.layer.mask != nil)")
            }
        }
        // 兜底：失效的转场容器仍然要摘掉（只认这一类，且没有动画在跑）
        for v in win.subviews where v !== rv {
            let n = String(describing: type(of: v))
            guard n.contains("TransitionView"), v.layer.animationKeys() == nil else { continue }
            v.isUserInteractionEnabled = false
            v.isHidden = true
            STVLog.w("[ui] dropped stale \(n)")
        }
    }

    private static func rect(_ r: CGRect) -> String {
        String(format: "%.0f,%.0f %.0fx%.0f", r.minX, r.minY, r.width, r.height)
    }
}
