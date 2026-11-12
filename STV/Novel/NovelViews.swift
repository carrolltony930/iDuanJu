import SwiftUI
import UIKit
import UniformTypeIdentifiers

// MARK: - Layout (TextKit pagination)

enum NovelLayout {
    struct Palette { var bg: Color; var text: UIColor; var secondary: Color; var panel: Color }

    @MainActor static func palette(_ t: NovelPrefs.Theme) -> Palette {
        switch t {
        case .white: Palette(bg: Color(white: 0.985), text: UIColor(white: 0.11, alpha: 1), secondary: Color(white: 0.55), panel: Color(white: 1))
        case .paper: Palette(bg: Color(red: 0.96, green: 0.92, blue: 0.83), text: UIColor(red: 0.23, green: 0.19, blue: 0.14, alpha: 1), secondary: Color(red: 0.55, green: 0.48, blue: 0.38), panel: Color(red: 0.98, green: 0.95, blue: 0.88))
        case .green: Palette(bg: Color(red: 0.80, green: 0.91, blue: 0.81), text: UIColor(red: 0.12, green: 0.18, blue: 0.13, alpha: 1), secondary: Color(red: 0.36, green: 0.47, blue: 0.37), panel: Color(red: 0.87, green: 0.95, blue: 0.88))
        case .night: Palette(bg: Color(white: 0.07), text: UIColor(white: 0.56, alpha: 1), secondary: Color(white: 0.40), panel: Color(white: 0.13))
        }
    }

    static func font(_ size: CGFloat, serif: Bool, bold: Bool = false) -> UIFont {
        if serif, let f = UIFont(name: bold ? "STSongti-SC-Bold" : "STSongti-SC-Regular", size: size) { return f }
        return .systemFont(ofSize: size, weight: bold ? .semibold : .regular)
    }

    /// Chapter text → styled string. Characters are not changed, so ranges map 1:1 to chapter offsets.
    /// The first paragraph is the chapter heading when it matches `title`.
    static func attributed(_ text: String, title: String, size: CGFloat, spacing: Int, serif: Bool, color: UIColor) -> NSAttributedString {
        let out = NSMutableAttributedString()
        let lineGap = size * [0.35, 0.62, 0.95][max(0, min(2, spacing))]
        let body = NSMutableParagraphStyle()
        body.lineSpacing = lineGap
        body.paragraphSpacing = size * 0.55
        body.firstLineHeadIndent = size * 2
        body.alignment = .justified
        let head = NSMutableParagraphStyle()
        head.lineSpacing = lineGap
        head.paragraphSpacing = size * 1.6
        let ns = text as NSString
        var first = true
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byParagraphs, .substringNotRequired]) { _, _, enclosing, _ in
            let piece = ns.substring(with: enclosing)
            let isHead = first && piece.trimmingCharacters(in: .newlines) == title
            first = false
            out.append(NSAttributedString(string: piece, attributes: [
                            .font: isHead ? font(size * 1.35, serif: serif, bold: true) : font(size, serif: serif),
                            .foregroundColor: color,
                            .paragraphStyle: isHead ? head : body,
            ]))
        }
        return out
    }

    /// Page ranges for a page of `size` (same container settings as `NovelTextView`).
    static func paginate(_ s: NSAttributedString, size: CGSize) -> [NSRange] {
        guard s.length > 0, size.width > 20, size.height > 20 else { return [NSRange(location: 0, length: s.length)] }
        let storage = NSTextStorage(attributedString: s)
        let lm = NSLayoutManager()
        storage.addLayoutManager(lm)
        var pages: [NSRange] = []
        while true {
            let tc = NSTextContainer(size: size)
            tc.lineFragmentPadding = 0
            lm.addTextContainer(tc)
            let g = lm.glyphRange(for: tc)
            if g.length == 0 { break }
            pages.append(lm.characterRange(forGlyphRange: g, actualGlyphRange: nil))
            if NSMaxRange(g) >= lm.numberOfGlyphs { break }
        }
        return pages.isEmpty ? [NSRange(location: 0, length: s.length)] : pages
    }
}

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

// MARK: - Reader

/// WeRead-style reader: tap left / right third (or swipe) to turn pages, tap the middle for the menu
/// (目录 · 进度 · 夜间 · 设置); or continuous vertical scrolling per chapter.
struct NovelReaderView: View {
    let book: NovelBook
    private let store = NovelStore.shared
    @State private var prefs = NovelPrefs.shared
    @Environment(\.dismiss) private var dismiss

    @State private var chapter = 0
    @State private var attributed = NSAttributedString()
    @State private var pages: [NSRange] = [NSRange(location: 0, length: 0)]
    @State private var page = 0
    @State private var pageSize: CGSize = .zero
    @State private var forward = true
    @State private var menu = false
    @State private var panel: Panel?
    @State private var lastTheme: NovelPrefs.Theme = .white
    enum Panel { case toc, settings }

    private var pal: NovelLayout.Palette { NovelLayout.palette(prefs.theme) }
    private var chapterTitle: String { book.chapters.indices.contains(chapter) ? book.chapters[chapter].title : "" }
    private var layoutKey: String { "\(prefs.fontSize)-\(prefs.spacing)-\(prefs.serif)-\(prefs.theme.rawValue)-\(Int(pageSize.width))x\(Int(pageSize.height))" }

    var body: some View {
        GeometryReader { geo in
            let size = CGSize(width: geo.size.width - 48, height: geo.size.height - geo.safeAreaInsets.top - geo.safeAreaInsets.bottom - 64)
            ZStack {
                pal.bg.ignoresSafeArea()
                if prefs.turn == .slide { slideReader(geo) } else { scrollReader }
                if menu { menuLayer.transition(.opacity) }
            }
            .onAppear { pageSize = size; restore() }
            .onChange(of: size) { _, s in pageSize = s }
        }
        .statusBarHidden(!menu)
        .preferredColorScheme(prefs.theme == .night ? .dark : nil)
        .onChange(of: layoutKey) { _, _ in relayout(keepOffset: currentOffset) }
        .sheet(isPresented: Binding(get: { panel == .toc }, set: { if !$0 { panel = nil } })) { toc }
    }

    // MARK: Slide mode

    private func slideReader(_ geo: GeometryProxy) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(chapterTitle).font(.system(size: 12)).foregroundStyle(pal.secondary).lineLimit(1)
                .frame(height: 28, alignment: .bottom)
            pageBody.frame(width: pageSize.width, height: pageSize.height, alignment: .topLeading)
                .id("\(chapter)-\(page)-\(layoutKey)")
                .transition(.asymmetric(insertion: .move(edge: forward ? .trailing : .leading),
                    removal: .move(edge: forward ? .leading : .trailing)))
            HStack {
                Text(percentLabel)
                Spacer()
                Text("\(page + 1) / \(pages.count)").accessibilityIdentifier("novel.page")
            }
            .font(.system(size: 11).monospacedDigit()).foregroundStyle(pal.secondary)
            .frame(height: 36)
        }
        .padding(.horizontal, 24)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture(coordinateSpace: .local) { p in tap(at: p.x, width: geo.size.width) }
        .gesture(DragGesture(minimumDistance: 24).onEnded { v in
                if menu { return }
                if v.translation.width < -40 { turn(+1) } else if v.translation.width > 40 { turn(-1) }
        })
    }

    @ViewBuilder private var pageBody: some View {
        let r = pages.indices.contains(page) ? pages[page] : NSRange(location: 0, length: attributed.length)
        if NSMaxRange(r) <= attributed.length {
            NovelTextView(text: attributed.attributedSubstring(from: r))
        }
    }

    // MARK: Scroll mode

    private var scrollReader: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Color.clear.frame(height: 1).id("top")
                    NovelTextView(text: attributed)
                    HStack {
                        if chapter > 0 { Button("上一章") { go(chapter - 1); proxy.scrollTo("top") } }
                        Spacer()
                        if chapter + 1 < book.chapters.count { Button("下一章") { go(chapter + 1); proxy.scrollTo("top") } }
                    }
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(pal.secondary)
                    .padding(.vertical, 20)
                }
                .padding(.horizontal, 24).padding(.vertical, 12)
            }
            .contentShape(Rectangle())
            .onTapGesture { withAnimation(.easeOut(duration: 0.2)) { menu.toggle(); if !menu { panel = nil } } }
        }
    }

    // MARK: Menu

    private var menuLayer: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { dismiss() } label: { Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44) }
                    .accessibilityIdentifier("novel.back")
                Text(book.title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                Spacer()
            }
            .foregroundStyle(Color(pal.text))
            .padding(.horizontal, 8)
            .background(pal.panel.ignoresSafeArea(edges: .top)).shadow(color: .black.opacity(0.08), radius: 6)
            Color.clear.contentShape(Rectangle()).onTapGesture { withAnimation(.easeOut(duration: 0.2)) { menu = false; panel = nil } }
            VStack(spacing: 14) {
                if panel == .settings { settings }
                HStack(spacing: 12) {
                    Button("上一章") { go(chapter - 1) }.disabled(chapter == 0)
                    Slider(value: Binding(get: { Double(chapter) }, set: { go(Int($0.rounded())) }),
                        in: 0...Double(max(1, book.chapters.count - 1)), step: 1)
                        .tint(STVTheme.accent)
                    Button("下一章") { go(chapter + 1) }.disabled(chapter + 1 >= book.chapters.count)
                }
                .font(.system(size: 14))
                HStack {
                    menuButton("目录", "list.bullet") { panel = .toc }
                    menuButton(prefs.theme == .night ? "日间" : "夜间", prefs.theme == .night ? "sun.max" : "moon") { toggleNight() }
                    menuButton("设置", "textformat.size") { withAnimation(.easeOut(duration: 0.2)) { panel = panel == .settings ? nil : .settings } }
                }
            }
            .foregroundStyle(Color(pal.text))
            .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 8)
            .background(pal.panel.ignoresSafeArea(edges: .bottom)).shadow(color: .black.opacity(0.08), radius: 6)
        }
    }

    private func menuButton(_ t: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) { Image(systemName: icon).font(.system(size: 19)); Text(t).font(.system(size: 11)) }
                .frame(maxWidth: .infinity).contentShape(Rectangle())
        }
        .buttonStyle(.plain).accessibilityIdentifier("novel.menu.\(t)")
    }

    private var settings: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Text("字号").font(.system(size: 14)).frame(width: 40, alignment: .leading)
                Button { prefs.fontSize = max(13, prefs.fontSize - 1) } label: { Text("A−").frame(maxWidth: .infinity, minHeight: 34) }
                    .accessibilityIdentifier("novel.font.minus")
                Text("\(Int(prefs.fontSize))").font(.system(size: 14).monospacedDigit()).frame(width: 32)
                Button { prefs.fontSize = min(32, prefs.fontSize + 1) } label: { Text("A+").frame(maxWidth: .infinity, minHeight: 34) }
                    .accessibilityIdentifier("novel.font.plus")
            }
            .buttonStyle(.bordered)
            HStack(spacing: 10) {
                Text("行距").font(.system(size: 14)).frame(width: 40, alignment: .leading)
                Picker("行距", selection: $prefs.spacing) { Text("紧凑").tag(0); Text("适中").tag(1); Text("宽松").tag(2) }.pickerStyle(.segmented)
            }
            HStack(spacing: 14) {
                Text("背景").font(.system(size: 14)).frame(width: 40, alignment: .leading)
                ForEach(NovelPrefs.Theme.allCases, id: \.self) { t in
                    Circle().fill(NovelLayout.palette(t).bg)
                        .overlay(Circle().stroke(t == prefs.theme ? STVTheme.accent : Color.gray.opacity(0.35), lineWidth: t == prefs.theme ? 2 : 1))
                        .frame(width: 32, height: 32)
                        .onTapGesture { prefs.theme = t }
                        .accessibilityIdentifier("novel.theme.\(t.rawValue)")
                }
            }
            HStack(spacing: 10) {
                Text("翻页").font(.system(size: 14)).frame(width: 40, alignment: .leading)
                Picker("翻页", selection: $prefs.turn) { Text("左右翻页").tag(NovelPrefs.Turn.slide); Text("上下滚动").tag(NovelPrefs.Turn.scroll) }.pickerStyle(.segmented)
            }
            HStack(spacing: 10) {
                Text("字体").font(.system(size: 14)).frame(width: 40, alignment: .leading)
                Picker("字体", selection: $prefs.serif) { Text("宋体").tag(true); Text("系统").tag(false) }.pickerStyle(.segmented)
            }
        }
    }

    private var toc: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(book.chapters.indices, id: \.self) { i in
                    Button { go(i); panel = nil; menu = false } label: {
                        Text(book.chapters[i].title).foregroundStyle(i == chapter ? STVTheme.accent : STVTheme.text).lineLimit(1)
                    }
                    .id(i)
                }
                .listStyle(.plain)
                .onAppear { proxy.scrollTo(chapter, anchor: .center) }
            }
            .navigationTitle("目录 · 共 \(book.chapters.count) 章")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large, .medium])
    }

    // MARK: Logic

    private var currentOffset: Int { pages.indices.contains(page) ? pages[page].location : 0 }
    private var percentLabel: String {
        guard let last = book.chapters.last, last.end > 0, book.chapters.indices.contains(chapter) else { return "" }
        let f = Double(book.chapters[chapter].start + currentOffset) / Double(last.end)
        return String(format: "%.1f%%", f * 100)
    }

    private func restore() {
        lastTheme = prefs.theme == .night ? .white : prefs.theme
        let p = store.progress[book.id]
        chapter = min(max(0, p?.chapter ?? 0), max(0, book.chapters.count - 1))
        relayout(keepOffset: p?.offset ?? 0)
    }

    /// Re-paginates the current chapter and lands on the page containing `offset` (Int.max = last page).
    private func relayout(keepOffset offset: Int) {
        let text = store.chapterText(book, chapter)
        attributed = NovelLayout.attributed(text, title: chapterTitle, size: prefs.fontSize, spacing: prefs.spacing, serif: prefs.serif, color: pal.text)
        pages = NovelLayout.paginate(attributed, size: pageSize)
        page = offset == .max ? pages.count - 1 : (pages.lastIndex { $0.location <= offset } ?? 0)
        save()
    }

    private func go(_ i: Int, atEnd: Bool = false) {
        guard book.chapters.indices.contains(i) else { return }
        chapter = i
        relayout(keepOffset: atEnd ? .max : 0)
    }

    private func turn(_ d: Int) {
        forward = d > 0
        withAnimation(.easeInOut(duration: 0.25)) {
            if d > 0 {
                if page + 1 < pages.count { page += 1; save() } else if chapter + 1 < book.chapters.count { go(chapter + 1) }
            } else {
                if page > 0 { page -= 1; save() } else if chapter > 0 { go(chapter - 1, atEnd: true) }
            }
        }
    }

    private func tap(at x: CGFloat, width: CGFloat) {
        if menu { withAnimation(.easeOut(duration: 0.2)) { menu = false; panel = nil }; return }
        if x < width / 3 { turn(-1) } else if x > width * 2 / 3 { turn(+1) } else { withAnimation(.easeOut(duration: 0.2)) { menu = true } }
    }

    private func toggleNight() {
        if prefs.theme == .night { prefs.theme = lastTheme } else { lastTheme = prefs.theme; prefs.theme = .night }
    }

    private func save() { store.record(book, chapter: chapter, offset: currentOffset) }
}
