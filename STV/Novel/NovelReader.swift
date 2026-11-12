import SwiftUI
import UIKit
import AVFoundation

// MARK: - Look (colours sampled from WeRead) and TextKit layout

enum NovelLayout {
    struct Palette {
        var bg: Color, panel: Color, track: Color, thumb: Color
        var text: UIColor, secondary: Color
        var ink: Color { Color(text) }
    }
    static let accent = Color(red: 0.19, green: 0.59, blue: 0.08)   // #309714

    @MainActor static func palette(_ t: NovelPrefs.Theme) -> Palette {
        switch t {
        case .white:
            Palette(bg: .white, panel: Color(white: 0.965), track: Color(white: 0.90), thumb: .white,
                text: UIColor(white: 0.12, alpha: 1), secondary: Color(white: 0.56))
        case .cream:
            Palette(bg: Color(hex: 0xF7F0DE), panel: Color(hex: 0xF1E8D0), track: Color(hex: 0xE6DABE), thumb: Color(hex: 0xFBF6EA),
                text: UIColor(red: 0.22, green: 0.18, blue: 0.13, alpha: 1), secondary: Color(hex: 0x9A8B70))
        case .green:
            Palette(bg: Color(hex: 0xBFEDC4), panel: Color(hex: 0xB8EDBD), track: Color(hex: 0xA9D9AF), thumb: Color(hex: 0xCCEFCF),
                text: UIColor(red: 0.12, green: 0.16, blue: 0.12, alpha: 1), secondary: Color(hex: 0x6E8E72))
        case .night:
            Palette(bg: Color(hex: 0x111111), panel: Color(hex: 0x1C1C1C), track: Color(hex: 0x2B2B2B), thumb: Color(hex: 0x4A4A4A),
                text: UIColor(white: 0.58, alpha: 1), secondary: Color(white: 0.40))
        }
    }

    static func font(_ size: CGFloat, serif: Bool, bold: Bool = false) -> UIFont {
        if serif, let f = UIFont(name: bold ? "STSongti-SC-Bold" : "STSongti-SC-Regular", size: size) { return f }
        return .systemFont(ofSize: size, weight: bold ? .semibold : .regular)
    }

    /// Horizontal page margin for the 边距 slider (0…1).
    static func margin(_ m: Double) -> CGFloat { 12 + 28 * m }

    /// Chapter text → styled string. Characters are not changed, so ranges map 1:1 to chapter offsets.
    /// The first paragraph is the chapter heading when it matches `title`.
    static func attributed(_ text: String, title: String, size: CGFloat, lineSpacing: Double, indent: Bool = true,
        serif: Bool, color: UIColor) -> NSAttributedString {
        let out = NSMutableAttributedString()
        let gap = size * (0.25 + 0.75 * lineSpacing)          // WeRead 适中 ≈ 0.6 em between lines
        let body = NSMutableParagraphStyle()
        body.lineSpacing = gap
        body.paragraphSpacing = gap * 0.35
        body.firstLineHeadIndent = indent ? size * 2 : 0
        body.alignment = .justified
        let head = NSMutableParagraphStyle()
        head.lineSpacing = gap * 0.6
        head.paragraphSpacing = size * 1.4
        let ns = text as NSString
        var first = true
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byParagraphs, .substringNotRequired]) { _, _, enclosing, _ in
            let piece = ns.substring(with: enclosing)
            let isHead = first && piece.trimmingCharacters(in: .newlines) == title
            first = false
            out.append(NSAttributedString(string: piece, attributes: [
                            .font: isHead ? font(size * 1.3, serif: serif, bold: true) : font(size, serif: serif),
                            .foregroundColor: color,
                            .paragraphStyle: isHead ? head : body,
            ]))
        }
        return out
    }

    /// Back-compat for tests: `spacing` 0/1/2 = 紧凑 / 适中 / 宽松.
    static func attributed(_ text: String, title: String, size: CGFloat, spacing: Int, serif: Bool, color: UIColor) -> NSAttributedString {
        attributed(text, title: title, size: size, lineSpacing: [0.1, 0.5, 0.9][max(0, min(2, spacing))], serif: serif, color: color)
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

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - 听 (text to speech)

@MainActor @Observable
final class NovelSpeaker: NSObject, AVSpeechSynthesizerDelegate {
    private(set) var speaking = false
    /// Chapter offset of the word being spoken.
    private(set) var position = 0
    var onFinish: (() -> Void)?
    @ObservationIgnored private let synth = AVSpeechSynthesizer()
    @ObservationIgnored private var base = 0

    override init() { super.init(); synth.delegate = self }

    func speak(_ chapterText: String, from offset: Int) {
        synth.stopSpeaking(at: .immediate)
        let ns = chapterText as NSString
        guard offset < ns.length else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        base = offset
        position = offset
        let u = AVSpeechUtterance(string: ns.substring(from: offset))
        u.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        speaking = true
        synth.speak(u)
    }

    func stop() {
        speaking = false
        onFinish = nil
        synth.stopSpeaking(at: .immediate)
    }

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, willSpeakRangeOfSpeechString r: NSRange, utterance: AVSpeechUtterance) {
        MainActor.assumeIsolated { position = base + r.location }
    }

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        MainActor.assumeIsolated { if speaking { onFinish?() } }
    }
}

// MARK: - Reader

/// Reader after WeRead (iOS): chapter name top-left, page count bottom-right, tap left / right third or
/// swipe to turn, tap the middle or swipe up for the toolbar (目录 · 书籍 · 进度 · 亮度 · 字体) and 听.
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
    @State private var tab: Tab?
    @State private var lastLight: NovelPrefs.Theme = .white
    @State private var autoTurn = false
    @State private var speaker = NovelSpeaker()
    @State private var opened = Date()
    @State private var brightness = 0.5
    @State private var tocSearch = false
    @State private var query = ""
    @State private var hits: [(chapter: Int, offset: Int, snippet: String)] = []
    enum Tab: String { case toc, info, progress, light, font }

    private var pal: NovelLayout.Palette { NovelLayout.palette(prefs.theme) }
    private var chapterTitle: String { book.chapters.indices.contains(chapter) ? book.chapters[chapter].title : "" }
    private var hMargin: CGFloat { NovelLayout.margin(prefs.margin) }
    private var layoutKey: String {
        "\(prefs.fontSize)-\(prefs.lineSpacing)-\(prefs.margin)-\(prefs.serif)-\(prefs.indent)-\(prefs.theme.rawValue)-\(Int(pageSize.width))x\(Int(pageSize.height))"
    }
    private var totalChars: Int { book.chapters.last?.end ?? 0 }
    private var bookOffset: Int { (book.chapters.indices.contains(chapter) ? book.chapters[chapter].start : 0) + currentOffset }
    private var fraction: Double { totalChars > 0 ? Double(bookOffset) / Double(totalChars) : 0 }
    /// Characters per page on this layout (WeRead shows whole-book page numbers).
    private var charsPerPage: Double { max(1, Double(attributed.length) / Double(max(1, pages.count))) }

    var body: some View {
        GeometryReader { geo in
            let size = CGSize(width: geo.size.width - 2 * hMargin, height: geo.size.height - 62)
            ZStack(alignment: .bottom) {
                pal.bg.ignoresSafeArea()
                if prefs.turn == .slide { slideReader(geo) } else { scrollReader }
                if menu { menuLayer.transition(.opacity) }
            }
            .onAppear { pageSize = size; restore(); brightness = Double(screen?.brightness ?? 0.5) }
            .onChange(of: size) { _, s in pageSize = s }
        }
        .statusBarHidden(!menu)
        .preferredColorScheme(prefs.theme == .night ? .dark : .light)
        .onChange(of: layoutKey) { _, _ in relayout(keepOffset: currentOffset) }
        .onChange(of: speaker.position) { _, p in followSpeech(p) }
        .onDisappear { store.addReadTime(book, Date().timeIntervalSince(opened)); speaker.stop() }
        .task(id: autoTurn) {
            while autoTurn, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                if autoTurn, !menu { turn(+1) }
            }
        }
    }

    // MARK: Page

    private func slideReader(_ geo: GeometryProxy) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(chapterTitle).font(.system(size: 12)).foregroundStyle(pal.secondary).lineLimit(1)
                .frame(height: 22, alignment: .bottom).padding(.bottom, 10)
            pageBody.frame(width: pageSize.width, height: pageSize.height, alignment: .topLeading)
                .id("\(chapter)-\(page)-\(layoutKey)")
                .transition(.asymmetric(insertion: .move(edge: forward ? .trailing : .leading),
                    removal: .move(edge: forward ? .leading : .trailing)))
            ZStack {
                if !menu { Text("↑上滑显示工具栏").font(.system(size: 12)).foregroundStyle(pal.secondary) }
                HStack {
                    Spacer()
                    Text("\(Int(Double(bookOffset) / charsPerPage) + 1) / \(Int(Double(totalChars) / charsPerPage) + 1)")
                        .font(.system(size: 12).monospacedDigit()).foregroundStyle(pal.secondary)
                        .accessibilityLabel("\(page + 1) / \(pages.count)").accessibilityIdentifier("novel.page")
                }
            }
            .frame(height: 30)
        }
        .padding(.horizontal, hMargin)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture(coordinateSpace: .local) { p in tap(at: p.x, width: geo.size.width) }
        .gesture(DragGesture(minimumDistance: 24).onEnded { v in
                if menu { return }
                if v.translation.height < -50, abs(v.translation.width) < 60 { showMenu(); return }
                if v.translation.width < -40 { turn(+1) } else if v.translation.width > 40 { turn(-1) }
        })
    }

    @ViewBuilder private var pageBody: some View {
        let r = pages.indices.contains(page) ? pages[page] : NSRange(location: 0, length: attributed.length)
        if NSMaxRange(r) <= attributed.length {
            NovelTextView(text: attributed.attributedSubstring(from: r))
        }
    }

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
                .padding(.horizontal, hMargin).padding(.vertical, 12)
            }
            .contentShape(Rectangle())
            .onTapGesture { menu ? hideMenu() : showMenu() }
        }
    }

    // MARK: Toolbar and panels

    private var menuLayer: some View {
        VStack(spacing: 0) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.system(size: 20, weight: .medium)).frame(width: 44, height: 44)
                }
                .accessibilityIdentifier("novel.back")
                Spacer()
            }
            .foregroundStyle(pal.ink)
            .padding(.horizontal, 6)
            .background(pal.bg.ignoresSafeArea(edges: .top))
            Color.clear.contentShape(Rectangle()).onTapGesture { hideMenu() }
                .overlay(alignment: .bottomTrailing) { listenButton.padding(.trailing, 16).padding(.bottom, 12) }
            VStack(spacing: 0) {
                if let tab { panel(tab).padding(.horizontal, 18).padding(.top, 18).padding(.bottom, 6) }
                toolbar
            }
            .background(pal.panel.ignoresSafeArea(edges: .bottom))
            .clipShape(UnevenRoundedRectangle(topLeadingRadius: tab == nil ? 0 : 16, topTrailingRadius: tab == nil ? 0 : 16))
            .shadow(color: .black.opacity(tab == nil ? 0.04 : 0.10), radius: 8, y: -2)
        }
    }

    private var toolbar: some View {
        HStack {
            toolIcon(.toc, "list.bullet", "目录")
            toolIcon(.info, tab == .info ? "safari.fill" : "safari", "书籍")
            toolIcon(.progress, "circle.and.line.horizontal", "进度")
            toolIcon(.light, tab == .light ? "sun.max.fill" : "sun.max", "亮度")
            toolIcon(.font, nil, "设置")
        }
        .padding(.horizontal, 12).frame(height: 56)
    }

    private func toolIcon(_ t: Tab, _ icon: String?, _ name: String) -> some View {
        Button { withAnimation(.easeOut(duration: 0.18)) { tab = tab == t ? nil : t } } label: {
            Group { if let icon { Image(systemName: icon).font(.system(size: 21, weight: .light)) } else { Text("A").font(.system(size: 23, weight: .light)) } }
                .foregroundStyle(tab == t ? NovelLayout.accent : pal.ink.opacity(0.85))
                .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain).accessibilityLabel(name).accessibilityIdentifier("novel.menu.\(name)")
    }

    private var listenButton: some View {
        Button {
            if speaker.speaking { speaker.stop() } else { startSpeaking() }
        } label: {
            Text(speaker.speaking ? "停" : "听").font(.system(size: 20, weight: .medium)).foregroundStyle(.white)
                .frame(width: 48, height: 48).background(Circle().fill(Color(white: 0.2).opacity(0.88)))
        }
        .buttonStyle(.plain).accessibilityIdentifier("novel.listen")
    }

    @ViewBuilder private func panel(_ t: Tab) -> some View {
        switch t {
        case .toc: tocPanel
        case .info: infoPanel
        case .progress: progressPanel
        case .light: lightPanel
        case .font: fontPanel
        }
    }

    private func pill(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(pal.ink)
                .frame(maxWidth: .infinity, minHeight: 40).background(Capsule().fill(pal.track.opacity(0.75)))
        }
        .buttonStyle(.plain)
    }

    // 进度
    private var progressPanel: some View {
        let read = store.readTime[book.id] ?? 0
        let left = Double(totalChars - bookOffset) / 450 / 60      // ~450 字/分钟
        return VStack(spacing: 18) {
            HStack(spacing: 0) {
                stat("\(Int((fraction * 100).rounded()))", "%", left >= 1 ? "约\(Int(left.rounded()))小时后读完" : "约\(max(1, Int(left * 60)))分钟后读完")
                Divider().frame(height: 34)
                stat(read >= 3600 ? "\(Int(read / 3600))" : "\(Int(read / 60))", read >= 3600 ? "小时\(Int(read.truncatingRemainder(dividingBy: 3600) / 60))分钟" : "分钟", "阅读时长")
                Divider().frame(height: 34)
                stat("\(chapter + 1)", "/\(book.chapters.count)", "章节")
            }
            HStack(spacing: 0) {
                Button { go(chapter - 1) } label: { Image(systemName: "chevron.left").frame(width: 40, height: 44) }.disabled(chapter == 0)
                PillSlider(value: Binding(get: { fraction }, set: { jump(toFraction: $0) }), track: .clear, thumb: pal.thumb)
                Button { go(chapter + 1) } label: { Image(systemName: "chevron.right").frame(width: 40, height: 44) }.disabled(chapter + 1 >= book.chapters.count)
            }
            .foregroundStyle(pal.ink).background(Capsule().fill(pal.track))
            HStack(spacing: 14) {
                pill("回到本章开头") { go(chapter) }
                pill(autoTurn ? "关闭自动翻页" : "开启自动翻页") { autoTurn.toggle(); if autoTurn { hideMenu() } }
            }
        }
    }

    private func stat(_ big: String, _ unit: String, _ sub: String) -> some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 1) {
                Text(big).font(.system(size: 22, weight: .semibold).monospacedDigit())
                Text(unit).font(.system(size: 12))
            }
            .foregroundStyle(pal.ink)
            Text(sub).font(.system(size: 11)).foregroundStyle(pal.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // 亮度 / 颜色
    private var lightPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            PillSlider(value: Binding(get: { brightness }, set: { brightness = $0; screen?.brightness = CGFloat($0) }),
                track: pal.track, thumb: pal.thumb, leading: AnyView(Image(systemName: "sun.min").font(.system(size: 14)).foregroundStyle(pal.ink)))
            Text("颜色").font(.system(size: 12)).foregroundStyle(pal.secondary)
            HStack(spacing: 12) {
                ForEach(NovelPrefs.Theme.allCases, id: \.self) { t in
                    let on = t == prefs.theme
                    RoundedRectangle(cornerRadius: 10).fill(NovelLayout.palette(t).bg)
                        .overlay { if t == .night { Image(systemName: "moon.fill").foregroundStyle(Color(hex: 0x4D7CFE)) } }
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(on ? NovelLayout.accent : .black.opacity(0.06), lineWidth: on ? 1.5 : 1))
                        .frame(height: 40)
                        .onTapGesture { prefs.theme = t }
                        .accessibilityIdentifier("novel.theme.\(t.rawValue)")
                }
            }
        }
    }

    // 字体
    private var fontPanel: some View {
        VStack(spacing: 14) {
            PillSlider(value: Binding(get: { (prefs.fontSize - 12) / 18 }, set: { prefs.fontSize = (12 + $0 * 18).rounded() }),
                track: pal.track, thumb: pal.thumb, label: "\(Int(prefs.fontSize))",
                leading: AnyView(Text("A").font(.system(size: 12)).foregroundStyle(pal.ink)),
                trailing: AnyView(Text("A").font(.system(size: 17)).foregroundStyle(pal.ink)))
                .accessibilityIdentifier("novel.font.size")
            HStack(spacing: 14) {
                PillSlider(value: $prefs.margin, track: pal.track, thumb: pal.thumb, label: "边距",
                    trailing: AnyView(Text("大").font(.system(size: 13)).foregroundStyle(pal.ink)))
                PillSlider(value: $prefs.lineSpacing, track: pal.track, thumb: pal.thumb, label: "行距",
                    leading: AnyView(Text("紧").font(.system(size: 13)).foregroundStyle(pal.ink)),
                    trailing: AnyView(Text("松").font(.system(size: 13)).foregroundStyle(pal.ink)))
            }
            HStack(spacing: 12) {
                Menu {
                    Picker("字体样式", selection: $prefs.serif) { Text("系统字体").tag(false); Text("宋体").tag(true) }
                } label: { menuPill("字体样式") }
                Button { prefs.indent.toggle() } label: { menuPill(prefs.indent ? "首行缩进" : "不缩进") }.buttonStyle(.plain)
                Menu {
                    Picker("翻页方式", selection: $prefs.turn) { Text("左右滑动").tag(NovelPrefs.Turn.slide); Text("上下滚动").tag(NovelPrefs.Turn.scroll) }
                } label: { menuPill(prefs.turn == .slide ? "左右滑动" : "上下滚动") }
            }
        }
    }

    private func menuPill(_ t: String) -> some View {
        HStack(spacing: 2) { Text(t); Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)) }
            .font(.system(size: 14, weight: .medium)).foregroundStyle(pal.ink)
            .frame(maxWidth: .infinity, minHeight: 40).background(Capsule().fill(pal.track.opacity(0.75)))
    }

    // 书籍信息
    private var infoPanel: some View {
        let read = store.readTime[book.id] ?? 0
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.title).font(.system(size: 18, weight: .bold)).foregroundStyle(pal.ink).lineLimit(2)
                    Text("\(book.author.map { $0 + " · " } ?? "")时长 \(Int(read / 3600))时\(Int(read.truncatingRemainder(dividingBy: 3600) / 60))分")
                        .font(.system(size: 13)).foregroundStyle(pal.secondary)
                }
                Spacer()
                NovelCover(book: book).frame(width: 52, height: 72)
            }
            Divider()
            HStack {
                infoCell("\(book.chapters.count)", "章节")
                infoCell(totalChars >= 10_000 ? String(format: "%.1f万", Double(totalChars) / 10_000) : "\(totalChars)", "字数")
                infoCell(book.format.uppercased(), "格式")
                infoCell(book.addedAt.formatted(.dateTime.month().day()), "导入")
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 14).fill(pal.bg.opacity(0.7)))
    }

    private func infoCell(_ v: String, _ k: String) -> some View {
        VStack(spacing: 4) {
            Text(v).font(.system(size: 15, weight: .semibold)).foregroundStyle(pal.ink)
            Text(k).font(.system(size: 11)).foregroundStyle(pal.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // 目录 / 搜本书
    private var tocPanel: some View {
        VStack(spacing: 10) {
            Capsule().fill(pal.secondary.opacity(0.4)).frame(width: 36, height: 4)
            HStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").font(.system(size: 13))
                    TextField("搜本书", text: $query).font(.system(size: 14)).submitLabel(.search)
                        .onSubmit { search() }
                        .simultaneousGesture(TapGesture().onEnded { tocSearch = true })
                }
                .padding(.horizontal, 12).frame(maxWidth: .infinity, minHeight: 34)
                .background(Capsule().fill(tocSearch ? pal.bg : .clear))
                Text("目录").font(.system(size: 14, weight: .medium))
                    .frame(maxWidth: .infinity, minHeight: 34)
                    .background(Capsule().fill(tocSearch ? .clear : pal.bg))
                    .onTapGesture { tocSearch = false; query = ""; hits = [] }
            }
            .foregroundStyle(pal.ink).padding(3).background(Capsule().fill(pal.track.opacity(0.7)))
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if tocSearch {
                            ForEach(hits.indices, id: \.self) { i in
                                let h = hits[i]
                                Button { jump(chapter: h.chapter, offset: h.offset) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(book.chapters[h.chapter].title).font(.system(size: 12)).foregroundStyle(pal.secondary)
                                        Text(h.snippet).font(.system(size: 15)).foregroundStyle(pal.ink).lineLimit(2)
                                    }
                                    .padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Divider()
                            }
                            if hits.isEmpty, !query.isEmpty { Text("没有找到").foregroundStyle(pal.secondary).padding(.top, 20) }
                        } else {
                            ForEach(book.chapters.indices, id: \.self) { i in tocRow(i) }
                        }
                    }
                }
                .onAppear { proxy.scrollTo(chapter, anchor: .center) }
            }
        }
        .frame(height: 460)
    }

    private func tocRow(_ i: Int) -> some View {
        let cur = i == chapter
        return VStack(alignment: .leading, spacing: 0) {
            Button { go(i); hideMenu() } label: {
                HStack {
                    Text(book.chapters[i].title).font(.system(size: 16, weight: cur ? .bold : .regular)).foregroundStyle(pal.ink).lineLimit(1)
                    Spacer()
                    Text("\(Int(Double(book.chapters[i].start) / charsPerPage) + 1)").font(.system(size: 13).monospacedDigit()).foregroundStyle(pal.secondary)
                }
                .padding(.vertical, 13).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(book.chapters[i].title)
            .accessibilityAddTraits(.isButton)
            if cur {
                HStack(spacing: 6) {
                    Image(systemName: "book").font(.system(size: 13))
                    Text("当前读到").font(.system(size: 14))
                    Spacer()
                    Text("\(Int(Double(bookOffset) / charsPerPage) + 1)").font(.system(size: 13).monospacedDigit()).foregroundStyle(pal.secondary)
                }
                .foregroundStyle(NovelLayout.accent).padding(.leading, 14).padding(.bottom, 12)
            }
            Divider().opacity(0.5)
        }
        .id(i)
    }

    // MARK: Logic

    private var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen
    }
    private var currentOffset: Int { pages.indices.contains(page) ? pages[page].location : 0 }

    private func showMenu() { withAnimation(.easeOut(duration: 0.2)) { menu = true } }
    private func hideMenu() { withAnimation(.easeOut(duration: 0.2)) { menu = false; tab = nil; tocSearch = false } }

    private func restore() {
        let p = store.progress[book.id]
        chapter = min(max(0, p?.chapter ?? 0), max(0, book.chapters.count - 1))
        relayout(keepOffset: p?.offset ?? 0)
    }

    /// Re-paginates the current chapter and lands on the page containing `offset` (Int.max = last page).
    private func relayout(keepOffset offset: Int) {
        let text = store.chapterText(book, chapter)
        attributed = NovelLayout.attributed(text, title: chapterTitle, size: prefs.fontSize, lineSpacing: prefs.lineSpacing,
            indent: prefs.indent, serif: prefs.serif, color: pal.text)
        pages = NovelLayout.paginate(attributed, size: pageSize)
        page = offset == .max ? pages.count - 1 : (pages.lastIndex { $0.location <= offset } ?? 0)
        save()
    }

    private func go(_ i: Int, atEnd: Bool = false) {
        guard book.chapters.indices.contains(i) else { return }
        chapter = i
        relayout(keepOffset: atEnd ? .max : 0)
        if speaker.speaking { startSpeaking() }
    }

    private func jump(chapter i: Int, offset: Int) {
        chapter = i
        relayout(keepOffset: offset)
        hideMenu()
    }

    private func jump(toFraction f: Double) {
        let target = Int(f * Double(totalChars))
        let i = book.chapters.lastIndex { $0.start <= target } ?? 0
        if i != chapter { chapter = i; relayout(keepOffset: target - book.chapters[i].start) }
        else { page = pages.lastIndex { $0.location <= target - book.chapters[i].start } ?? 0; save() }
    }

    private func turn(_ d: Int) {
        forward = d > 0
        withAnimation(.easeInOut(duration: 0.25)) {
            if d > 0 {
                if page + 1 < pages.count { page += 1; save() } else if chapter + 1 < book.chapters.count { go(chapter + 1) } else { autoTurn = false }
            } else {
                if page > 0 { page -= 1; save() } else if chapter > 0 { go(chapter - 1, atEnd: true) }
            }
        }
    }

    private func tap(at x: CGFloat, width: CGFloat) {
        if menu { hideMenu(); return }
        if x < width / 3 { turn(-1) } else if x > width * 2 / 3 { turn(+1) } else { showMenu() }
    }

    private func search() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { hits = []; return }
        let ns = store.text(book) as NSString
        var found: [(Int, Int, String)] = []
        var r = NSRange(location: 0, length: ns.length)
        while found.count < 200 {
            let m = ns.range(of: q, options: [.caseInsensitive], range: r)
            if m.location == NSNotFound { break }
            let ci = book.chapters.lastIndex { $0.start <= m.location } ?? 0
            let s = max(0, m.location - 16)
            let snippet = ns.substring(with: NSRange(location: s, length: min(60, ns.length - s))).replacingOccurrences(of: "\n", with: " ")
            found.append((ci, m.location - book.chapters[ci].start, snippet))
            r = NSRange(location: NSMaxRange(m), length: ns.length - NSMaxRange(m))
        }
        hits = found.map { (chapter: $0.0, offset: $0.1, snippet: $0.2) }
    }

    private func startSpeaking() {
        speaker.onFinish = { [chapter] in
            if chapter + 1 < book.chapters.count { forward = true; go(chapter + 1) } else { speaker.stop() }
        }
        speaker.speak(store.chapterText(book, chapter), from: currentOffset)
    }

    /// 听: keep the visible page on the sentence being read.
    private func followSpeech(_ p: Int) {
        guard speaker.speaking, let i = pages.lastIndex(where: { $0.location <= p }), i != page else { return }
        forward = i > page
        withAnimation(.easeInOut(duration: 0.25)) { page = i }
        save()
    }

    private func save() { store.record(book, chapter: chapter, offset: currentOffset) }
}

/// WeRead's capsule slider: a full-height round thumb (optionally labelled) on a pill track.
struct PillSlider: View {
    @Binding var value: Double
    var track: Color
    var thumb: Color
    var label: String? = nil
    var leading: AnyView? = nil
    var trailing: AnyView? = nil

    var body: some View {
        GeometryReader { g in
            let h = g.size.height, d = h - 6
            let x = 3 + (g.size.width - d - 6) * value.clamped
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                HStack {
                    leading.padding(.leading, 16)
                    Spacer()
                    trailing.padding(.trailing, 16)
                }
                Circle().fill(thumb).shadow(color: .black.opacity(0.12), radius: 3, y: 1)
                    .overlay { if let label { Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(.primary) } }
                    .frame(width: d, height: d)
                    .offset(x: x)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                    value = ((v.location.x - d / 2 - 3) / (g.size.width - d - 6)).clamped
            })
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityValue(label ?? "\(Int(value * 100))%")
        .accessibilityAdjustableAction { dir in
            value = (value + (dir == .increment ? 0.05 : -0.05)).clamped
        }
    }
}

private extension Double {
    var clamped: Double { Swift.min(1, Swift.max(0, self)) }
}
private extension CGFloat {
    var clamped: Double { Swift.min(1, Swift.max(0, Double(self))) }
}
