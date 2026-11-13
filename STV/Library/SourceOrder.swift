import SwiftUI

extension HomeSource {
    /// 持久化用的稳定键。label 是展示文案（会改），rawValue 不会。
    var storeKey: String {
        switch self {
        case .hongguo: "hongguo"
        case .huangguo: "huangguo"
        case .ai: "ai"
        case .channels: "channels"
        case .manga: "manga"
        case .novel: "novel"
        case .site(let s): "site." + s.rawValue
        }
    }
}

/// 站源的显示顺序与隐藏状态，落在 UserDefaults。
/// 频道面板里长按拖动改顺序、点 × 移除；「恢复默认」把两项都清空。
@MainActor @Observable
final class SourceOrder {
    static let shared = SourceOrder()

    private enum Key {
        static let order = "stv.source.order.v1"
        static let hidden = "stv.source.hidden.v1"
    }

    private let d = UserDefaults.standard
    private(set) var order: [String]
    private(set) var hidden: Set<String>

    private init() {
        order = d.stringArray(forKey: Key.order) ?? []
        hidden = Set(d.stringArray(forKey: Key.hidden) ?? [])
    }

    /// 有没有被用户改过（改过才显示「恢复默认」）。
    var hasCustom: Bool { !order.isEmpty || !hidden.isEmpty }

    /// 展示顺序：已登记的按自定义顺序在前，新出现的源按内置顺序接在后面，被移除的不显示。
    func arrange(_ all: [HomeSource]) -> [HomeSource] {
        var builtin: [String: Int] = [:]
        for (i, s) in all.enumerated() where builtin[s.storeKey] == nil { builtin[s.storeKey] = i }
        var rank: [String: Int] = [:]
        for (i, k) in order.enumerated() where rank[k] == nil { rank[k] = i }
        let tail = order.count
        return all.filter { !hidden.contains($0.storeKey) }.sorted { a, b in
            let ra = rank[a.storeKey] ?? (tail + (builtin[a.storeKey] ?? 0))
            let rb = rank[b.storeKey] ?? (tail + (builtin[b.storeKey] ?? 0))
            return ra < rb
        }
    }

    /// 拖动结束后写回可见项的新顺序；已移除的项留在尾部，恢复时还能回到原来的位置附近。
    func setOrder(_ keys: [String]) {
        let keep = order.filter { !keys.contains($0) && hidden.contains($0) }
        let next = keys + keep
        guard next != order else { return }
        order = next
        d.set(next, forKey: Key.order)
    }

    func hide(_ s: HomeSource) {
        guard hidden.insert(s.storeKey).inserted else { return }
        d.set(Array(hidden), forKey: Key.hidden)
    }

    func show(_ s: HomeSource) {
        guard hidden.remove(s.storeKey) != nil else { return }
        d.set(Array(hidden), forKey: Key.hidden)
    }

    func reset() {
        order = []
        hidden = []
        d.removeObject(forKey: Key.order)
        d.removeObject(forKey: Key.hidden)
    }
}
