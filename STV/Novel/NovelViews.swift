import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Non-scrolling text view: renders a page exactly as TextKit paginated it (and sizes itself in scroll mode).
struct NovelTextView: UIViewRepresentable {
    var text: NSAttributedString

    func makeUIView(context: Context) -> UITextView {
        let v = UITextView()
        v.isEditable = false
        v.isSelectable = false
        v.isScrollEnabled = false
        v.backgroundColor = .clear
        v.textContainerInset = .zero
        v.textContainer.lineFragmentPadding = 0
        v.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return v
    }

    func updateUIView(_ v: UITextView, context: Context) {
        if v.attributedText != text { v.attributedText = text }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let w = proposal.width ?? 320
        if proposal.height != nil, proposal.height != .infinity { return CGSize(width: w, height: proposal.height!) }
        return CGSize(width: w, height: ceil(uiView.sizeThatFits(CGSize(width: w, height: .greatestFiniteMagnitude)).height))
    }
}

// MARK: - Shelf

/// 小说 tab: local bookshelf (TXT / EPUB import). 书源 come in the next phase.
struct NovelHomeView: View {
    var embedded = false
    private let store = NovelStore.shared
    @State private var importing = false
    @State private var busy = false
    @State private var errors: [String] = []
    @State private var reading: NovelBook?
    @State private var deleting: NovelBook?

    private let cols = Array(repeating: GridItem(.flexible(), spacing: 14), count: 3)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("我的书架").font(.system(size: 20, weight: .bold)).foregroundStyle(STVTheme.text)
                    Spacer()
                    if busy { ProgressView().padding(.trailing, 6) }
                    Button { importing = true } label: {
                        Label("导入", systemImage: "plus").font(.system(size: 15, weight: .medium))
                    }
                    .buttonStyle(.plain).foregroundStyle(STVTheme.accent)
                    .accessibilityIdentifier("novel.import")
                }
                if store.books.isEmpty {
                    ContentUnavailableView {
                        Label("书架是空的", systemImage: "books.vertical")
                    } description: {
                        Text("点右上角「导入」添加本地 TXT / EPUB。\n支持 UTF-8、GBK、GB18030、Big5 编码，自动识别章节。")
                    }
                    .padding(.top, 60)
                } else {
                    LazyVGrid(columns: cols, spacing: 18) {
                        ForEach(store.shelf) { b in
                            Button { reading = b } label: { bookCell(b) }
                                .buttonStyle(.plain)
                                .contextMenu {
                                Button("删除", systemImage: "trash", role: .destructive) { deleting = b }
                            }
                            .accessibilityIdentifier("novel.book.\(b.title)")
                        }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 100)
        }
        .background(STVTheme.bg)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .text, .epub], allowsMultipleSelection: true) { r in
            guard case .success(let urls) = r, !urls.isEmpty else { return }
            busy = true
            Task { errors = await store.importFiles(urls); busy = false }
        }
        .alert("部分文件导入失败", isPresented: Binding(get: { !errors.isEmpty }, set: { if !$0 { errors = [] } })) {
            Button("好") { errors = [] }
        } message: { Text(errors.joined(separator: "\n")) }
        .confirmationDialog("从书架删除「\(deleting?.title ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("删除", role: .destructive) { if let b = deleting { store.remove(b) }; deleting = nil }
        } message: { Text("会同时删除阅读进度和本机保存的文件。") }
        .fullScreenCover(item: $reading) { b in NovelReaderView(book: b) }
        .task { seedFixtureIfNeeded() }
    }

    private func bookCell(_ b: NovelBook) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            NovelCover(book: b).aspectRatio(0.72, contentMode: .fit)
            Text(b.title).font(.system(size: 14, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(2)
            Text(store.fraction(b).map { "读到 \(Int(($0 * 100).rounded()))%" } ?? "未读")
                .font(.caption).foregroundStyle(STVTheme.secondary)
        }
    }

    /// UI tests: `STV_NOVEL_SEED=/abs/file.txt` imports that file once into an empty shelf.
    private func seedFixtureIfNeeded() {
        #if DEBUG
        guard store.books.isEmpty, let p = ProcessInfo.processInfo.environment["STV_NOVEL_SEED"], !p.isEmpty,
        let data = FileManager.default.contents(atPath: p),
        let parsed = try? NovelImport.parse(data, fileName: (p as NSString).lastPathComponent) else { return }
        try? store.add(parsed, format: (p as NSString).pathExtension)
        #endif
    }
}

struct NovelCover: View {
    let book: NovelBook
    var body: some View {
        ZStack {
            if book.hasCover, let img = UIImage(contentsOfFile: NovelStore.coverURL(book.id).path) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                let h = abs(book.title.hashValue % 360)
                LinearGradient(colors: [Color(hue: Double(h) / 360, saturation: 0.35, brightness: 0.72),
                        Color(hue: Double(h) / 360, saturation: 0.45, brightness: 0.48)],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
                VStack(spacing: 6) {
                    Text(book.title).font(.custom("STSongti-SC-Bold", size: 15)).foregroundStyle(.white)
                        .multilineTextAlignment(.center).lineLimit(4)
                    if let a = book.author { Text(a).font(.system(size: 10)).foregroundStyle(.white.opacity(0.8)).lineLimit(1) }
                }.padding(10)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .shadow(color: .black.opacity(0.18), radius: 4, y: 2)
    }
}
