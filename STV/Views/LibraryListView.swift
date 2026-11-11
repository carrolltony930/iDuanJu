import SwiftUI

struct LibraryListView: View {
    enum Kind { case favorites, history }
    let kind: Kind
    private let library = LibraryStore.shared
    @Environment(\.zoomNS) private var ns
    var body: some View {
        ScrollView {
            if kind == .favorites {
                if library.favorites.isEmpty { empty("还没有收藏", "heart") }
                else { NumberedGrid(items: library.favorites, numbered: false).padding(.vertical, 8) }
            } else {
                if library.continueWatching.isEmpty { empty("还没有观看记录", "clock") }
                else {
                    LazyVStack(spacing: 12) {
                        ForEach(library.continueWatching) { p in
                            NavigationLink(value: Route.series(p.series)) {
                                HStack(spacing: 12) {
                                    CoverView(url: p.series.cover, corner: 8).frame(width: 64, height: 86).zoomSource(p.series.id, ns)
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(p.series.title).font(.headline).foregroundStyle(STVTheme.text).lineLimit(1)
                                        Text(p.series.source.isLongVideo ? p.resumeLabel : "看到第\(p.episodeNumber)集 · \(Fmt.duration(Int(p.seconds)))").font(.caption).foregroundStyle(STVTheme.secondary)
                                        ProgressView(value: p.fraction).tint(STVTheme.accent)
                                    }
                                    Spacer()
                                }
                            }
                            .buttonStyle(.plain)
                            .contextMenu { Button("删除记录", role: .destructive) { library.removeProgress(p.series) } }
                        }
                    }.padding(16)
                }
            }
        }
        .background(STVTheme.bg.ignoresSafeArea())
        .navigationTitle(kind == .favorites ? "我的收藏" : "观看历史")
        .navigationBarTitleDisplayMode(.inline)
    }
    private func empty(_ t: String, _ icon: String) -> some View {
        ContentUnavailableView(t, systemImage: icon).padding(.top, 80)
    }
}
