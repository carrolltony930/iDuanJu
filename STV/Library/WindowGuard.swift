import SwiftUI
import UIKit

/// 窗口 / 触摸自愈守卫。
///
/// 用户反复遇到的现象是同一个：**画面还在动（首页轮播阴影继续变），但点哪里都没反应**。
/// 主线程没死锁，动画就会继续跑；所以问题只能是「触摸没有落到业务视图上」。能造成这件事的
/// 只有四类东西，这里逐条兜住，不管它是由哪个版本、哪条路径留下的：
///
/// 1. 窗口最上层多了一个**空但可交互**的视图（转场容器 / presentation 容器 / 野视图）。
///    这种视图看不见内容，只接管 hitTest —— 画面完全正常，触摸全被吃掉。
/// 2. `rootViewController.view` 被改成了 hidden / 不可交互 / alpha=0。
/// 3. 窗口尺寸与 `UIWindowScene` 不一致（旋转事务被打断后的典型残留）。
///    画面看起来没变，但触摸坐标系错位 —— 点在视觉按钮上，落点在窗口之外，一样是「点不动」。
///    用户描述的「底部菜单上方一小块黑屏」正是尺寸/安全区算错的产物。
/// 4. 中心点 `hitTest` 返回 nil（整块区域没有可接收触摸的视图）。
///
/// 检测方式刻意选了 **hitTest 实测**而不是猜类名：正常运行时命中的一定是业务视图（在
/// `root.view` 子树里），一旦命中的不是，那就是遮罩层，不需要知道它到底叫什么。
///
/// 保护条件（防止误伤）：
/// - 播放器在屏上时完全不介入（它有自己的一整套全屏手势）；
/// - 有 modal / sheet / alert 时跳过（它们的视图本来就不在 root 子树里）；
/// - 只处理「没有动画在跑」的视图，正在转场的绝不动。
///
/// 频率 1 秒一次，单次成本是一次递归 hitTest，可以忽略。真出问题时用户最多卡 1 秒就自动恢复，
/// 不会再到「必须杀进程」的程度。
@MainActor
enum WindowGuard {
    private static var timer: Timer?

    static func start() {
        guard timer == nil else { return }
        let t = Timer(timeInterval: 1.0, repeats: true) { _ in
            guard Thread.isMainThread else { return }
            MainActor.assumeIsolated { WindowGuard.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private static func tick() {
        guard PlayerPresenter.shared.route == nil else { return }
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
              let win = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first,
              let root = win.rootViewController else { return }
        // 有**真正呈现在屏幕上**的 modal / sheet / alert 时不动：它们的容器同样不在
        // root 子树里，但那是正常的。反过来，如果 modal 已经不在窗口上却还挂在
        // presentedViewController 上（iOS 17 的另一种残留），这里不跳过，交给下面的检查清理。
        if let p = root.presentedViewController {
            if p.isBeingPresented || p.isBeingDismissed || p.viewIfLoaded?.window != nil { return }
        }

        let rv = root.view!

        // —— 3. 窗口尺寸错位 ——
        let expect = scene.coordinateSpace.bounds.size
        if expect.width > 1, win.bounds.size != expect {
            STVLog.w("[ui] 窗口尺寸错位 \(fmt(win.bounds.size)) → \(fmt(expect))，已复位")
            win.frame = CGRect(origin: .zero, size: expect)
        }

        // —— 2. 根视图状态异常 ——
        if rv.isHidden || !rv.isUserInteractionEnabled || rv.alpha < 0.01 {
            STVLog.w("[ui] 根视图异常 hidden=\(rv.isHidden) user=\(rv.isUserInteractionEnabled) alpha=\(rv.alpha)，已复位")
            rv.isHidden = false
            rv.isUserInteractionEnabled = true
            rv.alpha = 1
        }

        // —— 1 / 4. 命中测试 ——
        let p = CGPoint(x: win.bounds.midX, y: win.bounds.midY)
        guard let hit = win.hitTest(p, with: nil) else {
            STVLog.w("[ui] 中心 hitTest 为空：清理窗口上层")
            rv.isHidden = false
            rv.isUserInteractionEnabled = true
            for v in win.subviews where v !== rv { neutralize(v) }
            return
        }
        if hit === rv || hit.isDescendant(of: rv) { return }

        // 命中的不是业务界面 —— 顺着 superview 找到挂在窗口上的那一层。
        var top = hit
        while let s = top.superview, s !== win { top = s }
        if top === rv { return }
        STVLog.w("[ui] 遮罩层 \(type(of: top)) 正在吃触摸，已隐藏")
        neutralize(top)
    }

    /// 只关掉交互并隐藏，**不 removeFromSuperview** —— 删视图容易让 UIKit 内部状态错乱，
    /// 而 hidden 的视图连 hitTest 都不参加，效果一样且可逆。
    private static func neutralize(_ v: UIView) {
        guard v.layer.animationKeys() == nil else { return }
        v.isUserInteractionEnabled = false
        v.isHidden = true
    }

    private static func fmt(_ s: CGSize) -> String {
        String(format: "%.0fx%.0f", s.width, s.height)
    }
}
