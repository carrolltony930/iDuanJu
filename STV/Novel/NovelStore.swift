import Foundation
import Observation

/// A book on the shelf. Imported files are converted once to `book.txt` (UTF-8) + chapter ranges, so
/// TXT and EPUB read the same way. Stored under Documents/Novels/<id>/.
struct NovelBook: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var author: String?
    var format: String
    var chapters: [NovelChapter]
    var hasCover: Bool
    var addedAt: Date
}

struct NovelProgress: Codable, Hashable {
    var chapter: Int
    /// UTF-16 offset inside the chapter where the current page starts.
    var offset: Int
    var updatedAt: Date
}

@MainActor @Observable
final class NovelStore {
    static let shared = NovelStore()
    private(set) var books: [NovelBook] = []
    private(set) var progress: [String: NovelProgress] = [:]
    @ObservationIgnored private var textCache: [String: String] = [:]
    private let d = UserDefaults.standard

    private init() {
        books = load("stv.novel.books") ?? []
        progress = load("stv.novel.progress") ?? [:]
    }

    nonisolated static var root: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Novels", isDirectory: true)
    }
    nonisolated static func dir(_ id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }
    nonisolated static func coverURL(_ id: String) -> URL { dir(id).appendingPathComponent("cover") }

    /// Shelf order: last read first, then newest imports.
    var shelf: [NovelBook] {
        books.sorted { (progress[$0.id]?.updatedAt ?? $0.addedAt) > (progress[$1.id]?.updatedAt ?? $1.addedAt) }
    }

    /// Imports files picked in the document picker (security-scoped URLs). Returns per-file errors.
    func importFiles(_ urls: [URL]) async -> [String] {
        var errors: [String] = []
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let name = url.lastPathComponent
                let parsed = try await Task.detached(priority: .userInitiated) { try NovelImport.parse(data, fileName: name) }.value
                try add(parsed, format: url.pathExtension.lowercased())
            } catch {
                errors.append("\(url.lastPathComponent)：\(error.localizedDescription)")
            }
        }
        return errors
    }

    @discardableResult
    func add(_ p: NovelParsed, format: String) throws -> NovelBook {
        let id = UUID().uuidString
        let dir = Self.dir(id)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data(p.text.utf8).write(to: dir.appendingPathComponent("book.txt"))
        if let c = p.cover { try? c.write(to: Self.coverURL(id)) }
        let book = NovelBook(id: id, title: p.title, author: p.author, format: format.isEmpty ? "txt" : format,
            chapters: p.chapters, hasCover: p.cover != nil, addedAt: Date())
        books.insert(book, at: 0)
        save(books, "stv.novel.books")
        return book
    }

    func remove(_ b: NovelBook) {
        books.removeAll { $0.id == b.id }
        progress[b.id] = nil
        textCache[b.id] = nil
        try? FileManager.default.removeItem(at: Self.dir(b.id))
        save(books, "stv.novel.books")
        save(progress, "stv.novel.progress")
    }

    func text(_ b: NovelBook) -> String {
        if let t = textCache[b.id] { return t }
        let t = (try? String(contentsOf: Self.dir(b.id).appendingPathComponent("book.txt"), encoding: .utf8)) ?? ""
        textCache = [b.id: t]   // one book at a time; texts can be tens of MB
        return t
    }

    func chapterText(_ b: NovelBook, _ i: Int) -> String {
        guard b.chapters.indices.contains(i) else { return "" }
        let c = b.chapters[i]
        let ns = text(b) as NSString
        guard c.end <= ns.length else { return "" }
        return ns.substring(with: NSRange(location: c.start, length: c.length))
    }

    func record(_ b: NovelBook, chapter: Int, offset: Int) {
        progress[b.id] = NovelProgress(chapter: chapter, offset: offset, updatedAt: Date())
        save(progress, "stv.novel.progress")
    }

    /// 0…1 through the whole book (by characters).
    func fraction(_ b: NovelBook) -> Double? {
        guard let p = progress[b.id], let last = b.chapters.last, last.end > 0, b.chapters.indices.contains(p.chapter) else { return nil }
        return min(1, Double(b.chapters[p.chapter].start + p.offset) / Double(last.end))
    }

    private func load<T: Decodable>(_ key: String) -> T? { d.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) } }
    private func save<T: Encodable>(_ v: T, _ key: String) { d.set(try? JSONEncoder().encode(v), forKey: key) }
}

/// Reader appearance (WeRead-style): font size, line spacing, paper colour, page turning, typeface.
@MainActor @Observable
final class NovelPrefs {
    static let shared = NovelPrefs()
    enum Theme: Int, CaseIterable { case white, paper, green, night }
    enum Turn: Int, CaseIterable { case slide, scroll }

    var fontSize: Double { didSet { d.set(fontSize, forKey: "stv.novel.font") } }
    var spacing: Int { didSet { d.set(spacing, forKey: "stv.novel.spacing") } }
    var theme: Theme { didSet { d.set(theme.rawValue, forKey: "stv.novel.theme") } }
    var turn: Turn { didSet { d.set(turn.rawValue, forKey: "stv.novel.turn") } }
    var serif: Bool { didSet { d.set(serif, forKey: "stv.novel.serif") } }
    private let d = UserDefaults.standard

    private init() {
        fontSize = d.object(forKey: "stv.novel.font") as? Double ?? 19
        spacing = d.object(forKey: "stv.novel.spacing") as? Int ?? 1
        theme = Theme(rawValue: d.integer(forKey: "stv.novel.theme")) ?? .white
        turn = Turn(rawValue: d.integer(forKey: "stv.novel.turn")) ?? .slide
        serif = d.object(forKey: "stv.novel.serif") as? Bool ?? true
    }
}
