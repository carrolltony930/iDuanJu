import SwiftUI
import UIKit
import MediaPlayer
import AVFoundation
import Observation

/// Brightness vertical-swipe adjustment for the player (left zone).
/// Volume is intentionally NOT controlled here: no hidden MPVolumeView is installed, so iOS
/// shows its native volume HUD (the bars) when the hardware buttons are pressed. Swipe-to-adjust
/// volume is dropped on purpose per the user's request.
enum AdjustSide { case brightness, volume }

@MainActor @Observable
final class PlayerAdjuster {
    var hud: (side: AdjustSide, value: Double)?
    private var originalBrightness: CGFloat?
    private var startValue: Double = 0
    private var hideTask: Task<Void, Never>?
    /// Hidden MPVolumeView slider — the sanctioned way to set system volume.
    weak var volumeSlider: UISlider?

    // No system-volume observation here: the native iOS volume HUD is shown by the system
    // itself now that the hidden MPVolumeView is gone. Adding a custom HUD on top of it would
    // only double up the indicators.
    static func zone(x: CGFloat, width: CGFloat, landscape: Bool, edge: CGFloat) -> AdjustSide? {
        guard width > 0, x >= edge, x <= width - 8 else { return nil }
        // Only brightness is swipe-adjustable now. Volume is driven by the hardware buttons
        // and shown by the native iOS volume HUD; swipe-to-adjust-volume is dropped on purpose.
        if landscape { return x < width / 2 ? .brightness : nil }
        if x < width * 0.2 { return .brightness }
        return nil
    }

    func begin(_ side: AdjustSide) {
        hideTask?.cancel()
        switch side {
        case .brightness:
            if originalBrightness == nil { originalBrightness = UIScreen.main.brightness }
            startValue = Double(UIScreen.main.brightness)
        case .volume:
            startValue = Double(volumeSlider?.value ?? AVAudioSession.sharedInstance().outputVolume)
        }
        hud = (side, startValue)
    }

    /// `fraction` = total vertical travel / usable height (up = positive).
    func change(_ side: AdjustSide, fraction: Double) {
        let v = min(1, max(0, startValue + fraction))
        switch side {
        case .brightness: UIScreen.main.brightness = CGFloat(v)
        case .volume: volumeSlider?.value = Float(v); volumeSlider?.sendActions(for: .valueChanged)
        }
        hud = (side, v)
    }

    func end() {
        hideTask = Task {
            try? await Task.sleep(for: .seconds(0.8))
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.2)) { hud = nil }
        }
    }

    func restoreBrightness() {
        if let b = originalBrightness { UIScreen.main.brightness = b }
        originalBrightness = nil
    }
}

/// Kept for reference but intentionally NOT mounted: an on-screen MPVolumeView (even at
/// alpha 0.01) suppresses iOS's native volume HUD, which the user wants visible.
struct HiddenVolumeView: UIViewRepresentable {
    let adjuster: PlayerAdjuster
    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: CGRect(x: -100, y: -100, width: 10, height: 10))
        v.alpha = 0.01
        v.isUserInteractionEnabled = false
        DispatchQueue.main.async { adjuster.volumeSlider = v.subviews.compactMap { $0 as? UISlider }.first }
        return v
    }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {
        if adjuster.volumeSlider == nil { adjuster.volumeSlider = uiView.subviews.compactMap { $0 as? UISlider }.first }
    }
}

/// Compact HUD in the player's OSD style: icon + thin bar + percentage.
struct PlayerAdjustHUD: View {
    let side: AdjustSide
    let value: Double
    private var icon: String {
        switch side {
        case .brightness: value < 0.34 ? "sun.min.fill" : "sun.max.fill"
        case .volume: value <= 0.001 ? "speaker.slash.fill" : value < 0.34 ? "speaker.wave.1.fill" : value < 0.67 ? "speaker.wave.2.fill" : "speaker.wave.3.fill"
        }
    }
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold)).frame(width: 20)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25))
                Capsule().fill(.white).frame(width: 120 * value)
            }.frame(width: 120, height: 4)
            Text("\(Int((value * 100).rounded()))").font(.caption.monospacedDigit().weight(.semibold)).frame(width: 28, alignment: .trailing)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.black.opacity(0.62), in: Capsule())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(side == .brightness ? "亮度 \(Int(value * 100))%" : "音量 \(Int(value * 100))%")
    }
}
