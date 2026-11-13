import SwiftUI

/// 设置 → 站源管理：内置源和站点源都在这里排序、删除、恢复。
///
/// 用系统 `List` 而不是自绘网格，是为了拿到原生的拖动排序（右侧把手）和左滑删除，
/// 手感和系统其他页面一致；删除只是从首页隐藏，配置落在 `SourceOrder`（UserDefaults），
/// 首页和频道面板读的是同一份数据，两边永远同步。
struct SourceManageView: View {
    @State private var items: [HomeSource] = []
    @State private var removed: [HomeSource] = []
    @State private var editMode: EditMode = .inactive

    private var visibleNow: [HomeSource] { SourceOrder.shared.arrange(HomeSource.all) }

    var body: some View {
        List {
            Section {
                ForEach(items) { s in
                    row(s)
                }
                .onMove { from, to in
                    items.move(fromOffsets: from, toOffset: to)
                    SourceOrder.shared.setOrder(items.map(\.storeKey))
                }
                .onDelete { idx in
                    let gone = idx.map { items[$0] }
                    withAnimation(.snappy(duration: 0.22)) {
                        items.remove(atOffsets: idx)
                        gone.forEach { SourceOrder.shared.hide($0) }
                        reload()
                    }
                }
            } header: {
                Text("站源顺序")
            } footer: {
                Text("点右上角「排序」后可长按右侧把手拖动；左滑可移除。删除只是从首页隐藏，收藏和观看记录都还在，随时能在下面恢复。")
            }

            if !removed.isEmpty {
                Section("已移除") {
                    ForEach(removed) { s in
                        HStack(spacing: 12) {
                            icon(s).opacity(0.55)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.label).font(.subheadline).foregroundStyle(STVTheme.secondary)
                                Text(subtitle(s)).font(.caption2).foregroundStyle(STVTheme.secondary.opacity(0.7))
                            }
                            Spacer(minLength: 8)
                            Button("恢复") {
                                withAnimation(.snappy(duration: 0.22)) {
                                    SourceOrder.shared.show(s)
                                    reload()
                                }
                            }
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(STVTheme.accent)
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("sources.restore.\(s.storeKey)")
                        }
                        .padding(.vertical, 2)
                    }
                }
            }

            Section {
                Button {
                    withAnimation(.snappy(duration: 0.22)) {
                        SourceOrder.shared.reset()
                        reload()
                    }
                } label: {
                    Text("恢复默认顺序与全部站源")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(SourceOrder.shared.hasCustom ? STVTheme.accent : STVTheme.secondary)
                }
                .disabled(!SourceOrder.shared.hasCustom)
                .accessibilityIdentifier("sources.reset")
            } footer: {
                Text("「18+ 片源」的总开关仍在上方分组里：这里删掉的源不会自动关闭 18+ 开关，也不会绕过年龄确认。")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(STVTheme.bg.ignoresSafeArea())
        .environment(\.editMode, $editMode)
        .navigationTitle("站源管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(editMode == .active ? "完成" : "排序") {
                    withAnimation(.snappy(duration: 0.2)) {
                        editMode = editMode == .active ? .inactive : .active
                    }
                }
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("sources.edit")
            }
        }
        .task { reload() }
        .onChange(of: SourceGate.shared.visibleSites) { _, _ in reload() }
        .onChange(of: SourceGate.shared.huangguo) { _, _ in reload() }
    }

    @ViewBuilder private func row(_ s: HomeSource) -> some View {
        HStack(spacing: 12) {
            icon(s)
            VStack(alignment: .leading, spacing: 2) {
                Text(s.label).font(.subheadline.weight(.medium)).foregroundStyle(STVTheme.text)
                Text(subtitle(s)).font(.caption2).foregroundStyle(STVTheme.secondary)
            }
            Spacer(minLength: 8)
            if s == .hongguo {
                Text("内置")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(STVTheme.accent)
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(STVTheme.accent.opacity(0.12), in: Capsule())
            }
        }
        .padding(.vertical, 2)
        .accessibilityIdentifier("sources.row.\(s.storeKey)")
    }

    private func icon(_ s: HomeSource) -> some View {
        Image(systemName: symbol(s))
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(STVTheme.accent)
            .frame(width: 30, height: 30)
            .background(STVTheme.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func symbol(_ s: HomeSource) -> String {
        switch s {
        case .hongguo: return "play.rectangle.on.rectangle"
        case .huangguo: return "film.stack"
        case .ai: return "sparkles"
        case .channels: return "paperplane"
        case .manga: return "book.closed"
        case .novel: return "text.book.closed"
        case .site(let d): return d.isLongVideo ? "play.rectangle" : "square.grid.2x2"
        }
    }

    private func subtitle(_ s: HomeSource) -> String {
        switch s {
        case .hongguo: return "短剧 · 官方接口"
        case .huangguo: return "短剧 · 视频站 / App 源"
        case .ai: return "AI 剧集聚合"
        case .channels: return "Telegram 频道"
        case .manga: return "漫画书架"
        case .novel: return "本地小说"
        case .site(let d):
            return d.isLongVideo ? "长视频 · \(SourcesView.rootDomain(d))" : "站点 · \(SourcesView.rootDomain(d))"
        }
    }

    /// 不受 18+ 开关影响的全部站源。用它反查「已移除」，否则关掉 18+ 后
    /// 被删的成人站点会从列表里彻底消失，用户再也点不到「恢复」。
    private var universe: [HomeSource] {
        var out: [HomeSource] = [.hongguo, .huangguo, .ai]
        out += Sites.all.map { HomeSource.site($0) }
        out += [.manga, .novel]
        return out
    }

    private func reload() {
        let shown = SourceOrder.shared.arrange(HomeSource.all)
        let shownKeys = Set(shown.map(\.storeKey))
        var allByKey: [String: HomeSource] = [:]
        for s in universe { allByKey[s.storeKey] = s }
        let gone = SourceOrder.shared.hidden.compactMap { allByKey[$0] }
            .filter { !shownKeys.contains($0.storeKey) }
            .sorted { $0.label < $1.label }
        items = shown
        removed = gone
    }
}
