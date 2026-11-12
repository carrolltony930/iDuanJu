import Foundation

/// Regex "all in one" match (`:regex` list rules): group texts, `$0` = whole match.
struct LegadoGroups { var groups: [String] }

/// State shared by one operation (search / 详情 / 目录 / 正文): variables from `@put`, the book, the JS engine.
final class LegadoContext {
    let source: LegadoSource
    var vars: [String: String] = [:]
    var book: [String: Any] = [:]
    var chapter: [String: Any]?
    var key: String?
    var page: Int?
    var log: [String] = []
    let js = LegadoJS()
    fileprivate var stack: [LegadoAnalyzer] = []

    init(source: LegadoSource) {
        self.source = source
        js.jsLib = source.jsLib
        js.handler = { [unowned self] name, args in try self.host(name, args as? [String: Any] ?? [:]) }
    }

    var sourceJSON: [String: Any] {
        ["bookSourceUrl": source.bookSourceUrl, "bookSourceName": source.bookSourceName, "bookSourceGroup": source.bookSourceGroup ?? ""]
    }

    private func host(_ name: String, _ a: [String: Any]) throws -> Any? {
        func s(_ k: String) -> String { a[k] as? String ?? "" }
        switch name {
        case "ajax":
            let r = try LegadoURL.request(s("url"), ctx: self, base: source.bookSourceUrl)
            return try LegadoHTTP.fetch(r.request, charset: r.charset).text
        case "connect":
            var r = try LegadoURL.request(s("url"), ctx: self, base: source.bookSourceUrl)
            if let m = a["method"] as? String { r.request.httpMethod = m; r.request.httpBody = Data(s("body").utf8) }
            if let h = a["headers"] as? [String: Any] { for (k, v) in h { r.request.setValue("\(v)", forHTTPHeaderField: k) } }
            let resp = try LegadoHTTP.fetch(r.request, charset: r.charset)
            return ["body": resp.text, "status": resp.status, "url": resp.url.absoluteString, "headers": resp.headers]
        case "putVar": vars[s("k")] = s("v"); return nil
        case "getVar": return vars[s("k")] ?? ""
        case "getString", "getStringList":
            guard let cur = stack.last else { return name == "getString" ? "" : [] }
            let an = a["content"] is String ? LegadoAnalyzer(s("content"), baseURL: cur.baseURL, ctx: self) : cur
            return name == "getString" ? try an.string(s("rule")) : try an.strings(s("rule"))
        case "setContent":
            if let cur = stack.last { cur.setContent(s("content"), base: a["base"] as? String) }
            return nil
        case "b64d":
            var t = s("s")
            t = t.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while t.count % 4 != 0 { t += "=" }
            guard let d = Data(base64Encoded: t, options: .ignoreUnknownCharacters) else { return "" }
            return String(data: d, encoding: LegadoHTTP.encoding(a["cs"] as? String) ?? .utf8) ?? String(decoding: d, as: UTF8.self)
        case "b64e": return Data(s("s").utf8).base64EncodedString()
        case "md5": return LegadoJS.md5(s("s"))
        case "encodeURI": return LegadoHTTP.percentEncode(s("s"), charset: a["cs"] as? String)
        case "htmlFormat": return LegadoContent.text(fromHTML: s("s"))
        case "log": log.append(s("s")); return nil
        case "cookie":
            guard let u = URL(string: s("url")) else { return "" }
            let cs = HTTPCookieStorage.shared.cookies(for: u) ?? []
            if let k = a["k"] as? String { return cs.first { $0.name == k }?.value ?? "" }
            return cs.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
        default: return nil
        }
    }

    /// JS with Legado's globals.
    func evalJS(_ code: String, result: Any?, baseURL: String, extra: [String: Any] = [:]) throws -> Any? {
        var b: [String: Any] = ["baseUrl": baseURL, "source": sourceJSON, "book": book]
        b["result"] = LegadoAnalyzer.jsonable(result)
        if let key { b["key"] = key }
        if let page { b["page"] = page }
        if let chapter { b["chapter"] = chapter; b["title"] = chapter["title"] ?? "" }
        for (k, v) in extra { b[k] = v }
        return try js.eval(code, b)
    }
}

/// Legado's AnalyzeRule: evaluates list / string rules on HTML, JSON, regex groups or plain text.
final class LegadoAnalyzer {
    let ctx: LegadoContext
    private(set) var content: Any
    private(set) var baseURL: String
    private var parsedDoc: LegadoNode?
    private var parsedJSON: Any??

    init(_ content: Any, baseURL: String, ctx: LegadoContext) {
        self.content = content
        self.baseURL = baseURL
        self.ctx = ctx
    }

    func setContent(_ c: Any, base: String? = nil) {
        content = c
        parsedDoc = nil
        parsedJSON = nil
        if let base { baseURL = base }
    }

    // MARK: Public API

    func elements(_ rule0: String) throws -> [Any] {
        var rule = rule0.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rule.isEmpty else { return [] }
        var reverse = false
        if rule.hasPrefix("-") { reverse = true; rule.removeFirst() } else if rule.hasPrefix("+") { rule.removeFirst() }
        ctx.stack.append(self); defer { ctx.stack.removeLast() }
        rule = try applyPutGet(rule)
        var cur: Any = content
        for seg in Self.jsSegments(rule) {
            switch seg {
            case .js(let code): cur = try ctx.evalJS(code, result: cur, baseURL: baseURL) ?? NSNull()
            case .rule(let r):
                let target = LegadoAnalyzer(cur, baseURL: baseURL, ctx: ctx)
                cur = try target.listCombined(r)
            }
        }
        var list = Self.asList(cur, parseJSON: true)
        if reverse { list.reverse() }
        return list
    }

    func string(_ rule0: String) throws -> String {
        try strings(rule0).joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func strings(_ rule0: String) throws -> [String] {
        var rule = rule0.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !rule.isEmpty else { return [] }
        // A rule that is only `@get:{k}` is the stored value itself.
        if let m = rule.range(of: #"^@get:\{([^}]+)\}$"#, options: .regularExpression) {
            return [ctx.vars[String(rule[m].dropFirst(6).dropLast())] ?? ""]
        }
        ctx.stack.append(self); defer { ctx.stack.removeLast() }
        rule = try applyPutGet(rule)
        var cur: Any = content
        var touched = false
        for seg in Self.jsSegments(rule) {
            switch seg {
            case .js(let code): cur = try ctx.evalJS(code, result: touched ? cur : Self.textOf(cur), baseURL: baseURL) ?? ""
            case .rule(let r):
                let target = LegadoAnalyzer(cur, baseURL: baseURL, ctx: ctx)
                cur = try target.stringsCombined(r)
            }
            touched = true
        }
        if let a = cur as? [String] { return a }
        if let a = cur as? [Any] { return a.map(Self.textOf) }
        return [Self.textOf(cur)]
    }

    // MARK: Combinators (&& || %%) and replacement (##)

    private func listCombined(_ rule: String) throws -> [Any] {
        let (parts, op) = Self.splitTop(rule)
        if parts.count == 1 { return try listSingle(parts[0]) }
        var results: [[Any]] = []
        for p in parts {
            let r = try listSingle(p)
            if op == "||" && !r.isEmpty { return r }
            results.append(r)
        }
        if op == "%%" {
            var out: [Any] = []
            for i in 0..<(results.map(\.count).max() ?? 0) { for r in results where i < r.count { out.append(r[i]) } }
            return out
        }
        return results.flatMap { $0 }
    }

    private func stringsCombined(_ rule: String) throws -> [String] {
        if Self.isTemplate(rule) { return [try template(rule)] }
        let (parts, op) = Self.splitTop(rule)
        var results: [[String]] = []
        for p in parts {
            let r = try stringsSingle(p).filter { !$0.isEmpty }
            if op == "||" && !r.isEmpty { return r }
            results.append(r)
        }
        if op == "%%" {
            var out: [String] = []
            for i in 0..<(results.map(\.count).max() ?? 0) { for r in results where i < r.count { out.append(r[i]) } }
            return out
        }
        return results.flatMap { $0 }
    }

    /// `rule##regex##replacement`; with a trailing `###` only the first match is kept, replaced (Legado replaceFirst).
    private static func splitReplace(_ rule: String) -> (String, String?, String, Bool) {
        let parts = rule.components(separatedBy: "##")
        guard parts.count > 1 else { return (rule, nil, "", false) }
        return (parts[0], parts[1], parts.count > 2 ? parts[2] : "", rule.hasSuffix("###"))
    }

    static func replace(_ s: String, _ regex: String, _ repl: String, firstOnly: Bool) -> String {
        guard let re = try? NSRegularExpression(pattern: regex) else { return s.replacingOccurrences(of: regex, with: repl) }
        let r = NSRange(s.startIndex..., in: s)
        if firstOnly {
            guard let m = re.firstMatch(in: s, range: r) else { return "" }
            return re.replacementString(for: m, in: s, offset: 0, template: repl)
        }
        return re.stringByReplacingMatches(in: s, range: r, withTemplate: repl)
    }

    // MARK: Single rules

    private enum Mode { case json, xpath, css, regexAll, jsoup }

    private func mode(_ r: String) -> (Mode, String) {
        if r.hasPrefix("@@") { return (.jsoup, String(r.dropFirst(2))) }
        if r.lowercased().hasPrefix("@xpath:") { return (.xpath, String(r.dropFirst(7))) }
        if r.lowercased().hasPrefix("@json:") { return (.json, String(r.dropFirst(6))) }
        if r.lowercased().hasPrefix("@css:") { return (.css, String(r.dropFirst(5))) }
        if r.hasPrefix(":") { return (.regexAll, String(r.dropFirst())) }
        if r.hasPrefix("$.") || r.hasPrefix("$[") || (json != nil && !(content is LegadoNode)) { return (.json, r) }
        if r.hasPrefix("/") { return (.xpath, r) }
        return (.jsoup, r)
    }

    private func listSingle(_ r0: String) throws -> [Any] {
        let (r, regex, repl, first) = Self.splitReplace(r0)
        let (m, rule) = mode(r)
        _ = (regex, repl, first)
        switch m {
        case .regexAll:
            guard let re = try? NSRegularExpression(pattern: rule) else { throw LegadoError.rule("正则 \(rule)") }
            let s = Self.textOf(content)
            return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).map { mt in
                LegadoGroups(groups: (0..<mt.numberOfRanges).map { Range(mt.range(at: $0), in: s).map { String(s[$0]) } ?? "" })
            }
        case .json:
            guard let j = json else { return [] }
            let r = LegadoJSONPath.read(j, rule)
            if r.count == 1, let a = r[0] as? [Any] { return a }
            return r
        case .xpath:
            return doc.xpathRaw(rule).nodes.filter(\.isElement)
        case .css:
            let (sel, _) = Self.splitCSSAttr(rule, forList: true)
            return doc.css(sel)
        case .jsoup:
            var nodes = [doc]
            for seg in Self.splitAt(rule) { nodes = Self.select(seg, nodes) }
            return nodes
        }
    }

    private func stringsSingle(_ r0: String) throws -> [String] {
        let (r, regex, repl, firstOnly) = Self.splitReplace(r0)
        var out: [String]
        if let g = content as? LegadoGroups {
            out = [Self.groupRef(r, g)]
        } else if r.isEmpty {
            out = [Self.textOf(content)]
        } else {
            let (m, rule) = mode(r)
            switch m {
            case .regexAll:
                out = try listSingle(r).compactMap { ($0 as? LegadoGroups)?.groups.first }
            case .json:
                if rule.contains("{$.") { out = [jsonTemplate(rule)] }
                else if let j = json { out = LegadoJSONPath.read(j, rule).flatMap { ($0 as? [Any]) ?? [$0] }.map(LegadoJSONPath.text) }
                else { out = [] }
            case .xpath:
                let (nodes, scalar) = doc.xpathRaw(rule)
                out = scalar.map { [$0] } ?? nodes.map(\.stringValue)
            case .css:
                let (sel, attr) = Self.splitCSSAttr(rule, forList: false)
                out = Self.values(doc.css(sel), attr ?? "text")
            case .jsoup:
                var segs = Self.splitAt(rule)
                let attr = segs.count > 1 || Self.attributeNames.contains(segs[0]) ? segs.removeLast() : "text"
                var nodes = [doc]
                for seg in segs { nodes = Self.select(seg, nodes) }
                out = Self.values(nodes, attr)
            }
        }
        if let regex { out = out.map { Self.replace($0, regex, repl, firstOnly: firstOnly) } }
        return out
    }

    // MARK: Templates and variables

    private static func isTemplate(_ r: String) -> Bool { r.contains("{{") && r.contains("}}") }

    /// `text {{rule or js}} text`.
    private func template(_ r: String) throws -> String {
        var out = "", rest = Substring(r)
        while let open = rest.range(of: "{{"), let close = rest.range(of: "}}", range: open.upperBound..<rest.endIndex) {
            out += rest[..<open.lowerBound]
            let inner = String(rest[open.upperBound..<close.lowerBound])
            let ruleLike = inner.hasPrefix("@@") || inner.hasPrefix("@css:") || inner.lowercased().hasPrefix("@xpath:") || inner.hasPrefix("@json:")
            || inner.hasPrefix("$.") || inner.hasPrefix("//")
            if ruleLike { out += try string(inner) }
            else { out += Self.textOf(try ctx.evalJS(inner, result: Self.textOf(content), baseURL: baseURL) ?? "") }
            rest = rest[close.upperBound...]
        }
        return out + rest
    }

    private func jsonTemplate(_ r: String) -> String {
        guard let j = json, let re = try? NSRegularExpression(pattern: #"\{(\$\.[^{}]+)\}"#) else { return r }
        var out = r
        for m in re.matches(in: r, range: NSRange(r.startIndex..., in: r)).reversed() {
            guard let whole = Range(m.range, in: out), let p = Range(m.range(at: 1), in: r) else { continue }
            out.replaceSubrange(whole, with: LegadoJSONPath.read(j, String(r[p])).map(LegadoJSONPath.text).joined(separator: ","))
        }
        return out
    }

    /// `@put:{k:rule}` stores values; `@get:{k}` substitutes them.
    private func applyPutGet(_ r0: String) throws -> String {
        var r = r0
        while let p = r.range(of: "@put:{"), let end = r.range(of: "}", range: p.upperBound..<r.endIndex) {
            let body = "{" + r[p.upperBound..<end.lowerBound] + "}"
            r.removeSubrange(p.lowerBound..<end.upperBound)
            if let d = body.data(using: .utf8), let m = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                for (k, v) in m { ctx.vars[k] = try string("\(v)") }
            }
        }
        while let g = r.range(of: "@get:{"), let end = r.range(of: "}", range: g.upperBound..<r.endIndex) {
            let k = String(r[g.upperBound..<end.lowerBound])
            r.replaceSubrange(g.lowerBound..<end.upperBound, with: ctx.vars[k] ?? "")
        }
        return r
    }

    private static func groupRef(_ r: String, _ g: LegadoGroups) -> String {
        guard r.contains("$") else { return r }
        var out = r
        for i in stride(from: g.groups.count - 1, through: 0, by: -1) { out = out.replacingOccurrences(of: "$\(i)", with: g.groups[i]) }
        return out
    }

    // MARK: Content forms

    private var doc: LegadoNode {
        if let n = content as? LegadoNode { return n }
        if let d = parsedDoc { return d }
        let d = LegadoDoc(Self.textOf(content)).node
        parsedDoc = d
        return d
    }

    private var json: Any? {
        if let p = parsedJSON { return p }
        var v: Any?
        switch content {
        case let d as [String: Any]: v = d
        case let a as [Any]: v = a
        case let s as String:
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.hasPrefix("{") || t.hasPrefix("[") { v = try? JSONSerialization.jsonObject(with: Data(t.utf8), options: [.fragmentsAllowed]) }
        default: v = nil
        }
        parsedJSON = .some(v)
        return v
    }

    // MARK: Jsoup-style default rules

    static let attributeNames: Set<String> = ["text", "textNodes", "ownText", "html", "all", "href", "src", "content", "value", "title", "alt"]

    /// Splits on `@` outside brackets / parens / quotes.
    static func splitAt(_ r: String) -> [String] {
        var out: [String] = [], cur = "", depth = 0
        var quote: Character?
        for c in r {
            if let q = quote { cur.append(c); if c == q { quote = nil }; continue }
            if c == "'" || c == "\"" { quote = c; cur.append(c); continue }
            if c == "[" || c == "(" { depth += 1 } else if c == "]" || c == ")" { depth -= 1 }
            if c == "@" && depth == 0 { out.append(cur); cur = "" } else { cur.append(c) }
        }
        out.append(cur)
        return out.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// `sel@attr` for @css: (the last part is an attribute when it is a bare word).
    static func splitCSSAttr(_ r: String, forList: Bool) -> (String, String?) {
        let parts = splitAt(r)
        guard parts.count > 1, let last = parts.last, last.range(of: #"^[A-Za-z][\w\-:]*$"#, options: .regularExpression) != nil else { return (r, nil) }
        return (parts.dropLast().joined(separator: "@"), last)
    }

    static func values(_ nodes: [LegadoNode], _ attr0: String) -> [String] {
        let attr = attr0.hasPrefix("abs:") ? String(attr0.dropFirst(4)) : attr0
        switch attr {
        case "text": return nodes.map(\.text).filter { !$0.isEmpty }
        case "textNodes": return nodes.map { $0.textNodes.joined(separator: "\n") }.filter { !$0.isEmpty }
        case "ownText": return nodes.map(\.ownText).filter { !$0.isEmpty }
        case "html": return nodes.map { stripScripts($0.html(outer: true)) }
        case "all": return nodes.map { $0.html(outer: true) }
        default: return nodes.map { $0.attr(attr) }.filter { !$0.isEmpty }
        }
    }

    private static func stripScripts(_ h: String) -> String {
        h.replacingOccurrences(of: "<(script|style)[\\s\\S]*?</\\1>", with: "", options: [.regularExpression, .caseInsensitive])
    }

    /// One `@` segment: class.x / id.x / tag.x / text.x / children (+ index) or a CSS selector (+ [index]).
    static func select(_ seg: String, _ parents: [LegadoNode]) -> [LegadoNode] {
        var body = seg, index: String?
        // trailing [..] index (digits, ranges, commas, !)
        if body.hasSuffix("]"), let open = body.lastIndex(of: "["),
        body[body.index(after: open)..<body.index(before: body.endIndex)].allSatisfy({ "0123456789-:,! ".contains($0) }) {
            index = String(body[body.index(after: open)..<body.index(before: body.endIndex)])
            body = String(body[..<open])
        }
        let typed = ["class.", "id.", "tag.", "text."].first { body.hasPrefix($0) }
        var name = body
        if let t = typed {
            name = String(body.dropFirst(t.count))
            if index == nil {
                if let bang = name.lastIndex(of: "!") { index = "!" + name[name.index(after: bang)...]; name = String(name[..<bang]) }
                else if let dot = name.lastIndex(of: "."), name[name.index(after: dot)...].allSatisfy({ "0123456789-:".contains($0) }), !name[name.index(after: dot)...].isEmpty {
                    index = String(name[name.index(after: dot)...]); name = String(name[..<dot])
                }
            }
        } else if body == "children" || body.hasPrefix("children.") {
            if body.hasPrefix("children."), index == nil { index = String(body.dropFirst(9)) }
            return parents.flatMap { pick($0.children, index) }
        }
        return parents.flatMap { p -> [LegadoNode] in
            let found: [LegadoNode]
            switch typed {
            case "class.":
                let want = name.split(separator: " ").map(String.init)
                found = p.descendants.filter { n in let c = Set(n.attr("class").split(separator: " ").map(String.init)); return want.allSatisfy(c.contains) }
            case "id.": found = p.descendants.filter { $0.attr("id") == name }
            case "tag.": found = p.descendants.filter { $0.name == name.lowercased() }
            case "text.": found = p.descendants.filter { $0.ownText.contains(name) }
            default: found = cssWithEq(name, p)
            }
            return pick(found, index)
        }
    }

    /// Jsoup pseudo `:eq(n)` / `:lt(n)` / `:gt(n)` at the end of a selector.
    private static func cssWithEq(_ sel: String, _ p: LegadoNode) -> [LegadoNode] {
        if let m = sel.range(of: #":(eq|lt|gt)\((-?\d+)\)$"#, options: .regularExpression) {
            let base = String(sel[..<m.lowerBound])
            let spec = sel[m]
            let n = Int(spec.filter { $0.isNumber || $0 == "-" }) ?? 0
            let all = p.css(base)
            if spec.hasPrefix(":eq") { let k = n < 0 ? all.count + n : n; return all.indices.contains(k) ? [all[k]] : [] }
            if spec.hasPrefix(":lt") { return Array(all.prefix(max(0, n))) }
            return Array(all.dropFirst(n + 1))
        }
        return p.css(sel)
    }

    /// Legado indexes: `0`, `-1`, `1,3`, `1:3` (inclusive), `!0` (exclude).
    static func pick(_ items: [LegadoNode], _ spec: String?) -> [LegadoNode] {
        guard var s = spec?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return items }
        let exclude = s.hasPrefix("!")
        if exclude { s.removeFirst() }
        let n = items.count
        var idx: [Int] = []
        for part in s.split(separator: ",") {
            let p = part.split(separator: ":", omittingEmptySubsequences: false).map { Int($0.trimmingCharacters(in: .whitespaces)) }
            if p.count >= 2 {
                var a = p[0] ?? 0, b = p[1] ?? (n - 1)
                if a < 0 { a += n }; if b < 0 { b += n }
                if a <= b { idx += Array(max(0, a)...min(n - 1, b)) } else { idx += stride(from: min(n - 1, a), through: max(0, b), by: -1).map { $0 } }
            } else if let i = p[0] {
                idx.append(i < 0 ? n + i : i)
            }
        }
        idx = idx.filter { $0 >= 0 && $0 < n }
        if exclude { let ex = Set(idx); return items.indices.filter { !ex.contains($0) }.map { items[$0] } }
        return idx.map { items[$0] }
    }

    // MARK: JS segments

    enum Segment { case rule(String), js(String) }

    /// `rule<js>code</js>rule@js:code` → ordered segments.
    static func jsSegments(_ r: String) -> [Segment] {
        var out: [Segment] = []
        var rest = Substring(r)
        while !rest.isEmpty {
            let tag = rest.range(of: "<js>", options: .caseInsensitive)
            let at = rest.range(of: "@js:", options: .caseInsensitive)
            if let t = tag, at == nil || t.lowerBound < at!.lowerBound {
                let before = rest[..<t.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
                if !before.isEmpty { out.append(.rule(before)) }
                let close = rest.range(of: "</js>", options: .caseInsensitive, range: t.upperBound..<rest.endIndex)
                out.append(.js(String(rest[t.upperBound..<(close?.lowerBound ?? rest.endIndex)])))
                rest = close.map { rest[$0.upperBound...] } ?? ""
            } else if let a = at {
                let before = rest[..<a.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
                if !before.isEmpty { out.append(.rule(before)) }
                out.append(.js(String(rest[a.upperBound...])))
                rest = ""
            } else {
                out.append(.rule(String(rest)))
                rest = ""
            }
        }
        return out
    }

    /// Top-level `&&`, `||` or `%%` (first one found decides; brackets and quotes respected).
    static func splitTop(_ r: String) -> ([String], String) {
        for op in ["&&", "||", "%%"] {
            var parts: [String] = [], cur = "", depth = 0
            var quote: Character?
            var i = r.startIndex
            while i < r.endIndex {
                let c = r[i]
                if let q = quote { cur.append(c); if c == q { quote = nil }; i = r.index(after: i); continue }
                if c == "'" || c == "\"" { quote = c }
                if "[({".contains(c) { depth += 1 } else if "])}".contains(c) { depth -= 1 }
                if depth == 0, r[i...].hasPrefix(op) { parts.append(cur); cur = ""; i = r.index(i, offsetBy: 2); continue }
                cur.append(c)
                i = r.index(after: i)
            }
            parts.append(cur)
            if parts.count > 1 { return (parts.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }, op) }
        }
        return ([r], "")
    }

    // MARK: Value helpers

    static func textOf(_ v: Any?) -> String {
        switch v {
        case nil: return ""
        case let s as String: return s
        case let n as LegadoNode: return n.html(outer: true)
        case let g as LegadoGroups: return g.groups.first ?? ""
        case let a as [Any]: return a.map { textOf($0) }.joined(separator: "\n")
        default: return LegadoJSONPath.text(v!)
        }
    }

    static func asList(_ v: Any, parseJSON: Bool) -> [Any] {
        switch v {
        case let a as [Any]: return a
        case let s as String:
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if parseJSON, t.hasPrefix("["), let a = (try? JSONSerialization.jsonObject(with: Data(t.utf8))) as? [Any] { return a }
            return t.isEmpty ? [] : [s]
        case is NSNull: return []
        default: return [v]
        }
    }

    /// Value for JS `result` (nodes become their HTML).
    static func jsonable(_ v: Any?) -> Any {
        switch v {
        case nil: return NSNull()
        case let n as LegadoNode: return n.html(outer: true)
        case let g as LegadoGroups: return g.groups
        case let a as [Any]: return a.map { jsonable($0) }
        case let d as [String: Any]: return d
        case let s as String: return s
        case let n as NSNumber: return n
        default: return "\(v!)"
        }
    }
}

// MARK: - URL rules (AnalyzeUrl)

struct LegadoRequest {
    var request: URLRequest
    var charset: String?
}

enum LegadoURL {
    /// `searchUrl` / `tocUrl` / … → request. Handles `{{key}}`, `{{page}}`, `{{js}}`, `<a,b,c>` page lists,
    /// `@js:` URLs and the `,{"method":…,"body":…,"charset":…,"headers":…}` option suffix.
    static func request(_ rule0: String, ctx: LegadoContext, base: String, analyzer: LegadoAnalyzer? = nil) throws -> LegadoRequest {
        var rule = rule0.trimmingCharacters(in: .whitespacesAndNewlines)
        let segs = LegadoAnalyzer.jsSegments(rule)
        if segs.contains(where: { if case .js = $0 { true } else { false } }) {
            var cur: Any = ""
            for s in segs {
                switch s {
                case .rule(let r): cur = r
                case .js(let code): cur = try ctx.evalJS(code, result: LegadoAnalyzer.textOf(cur), baseURL: base) ?? ""
                }
            }
            rule = LegadoAnalyzer.textOf(cur)
        }
        // <1,2,3> page lists
        if let page = ctx.page, let re = try? NSRegularExpression(pattern: "<([^<>]+,[^<>]+)>") {
            for m in re.matches(in: rule, range: NSRange(rule.startIndex..., in: rule)).reversed() {
                guard let whole = Range(m.range, in: rule), let inner = Range(m.range(at: 1), in: rule) else { continue }
                let items = rule[inner].split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }
                rule.replaceSubrange(whole, with: items[min(max(0, page - 1), items.count - 1)])
            }
        }
        // options suffix
        var options: [String: Any] = [:]
        if let r = rule.range(of: ",{", options: .backwards) {
            let tail = String(rule[rule.index(after: r.lowerBound)...])
            if let d = tail.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
                options = o
                rule = String(rule[..<r.lowerBound])
            }
        }
        let charset = options["charset"] as? String
        // {{ }} templates
        rule = try substitute(rule, ctx: ctx, base: base, charset: charset, encodeKey: true)
        guard let url = resolve(rule, base: base) else { throw LegadoError.rule("网址无效：\(rule)") }
        var req = URLRequest(url: url)
        req.setValue(LegadoHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        for (k, v) in try headers(ctx: ctx) { req.setValue(v, forHTTPHeaderField: k) }
        if let h = options["headers"] as? [String: Any] { for (k, v) in h { req.setValue("\(v)", forHTTPHeaderField: k) } }
        else if let hs = options["headers"] as? String, let d = hs.data(using: .utf8), let h = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] {
            for (k, v) in h { req.setValue("\(v)", forHTTPHeaderField: k) }
        }
        if (options["method"] as? String)?.uppercased() == "POST" {
            req.httpMethod = "POST"
            var body: String
            if let b = options["body"] as? String { body = b }
            else if let b = options["body"], JSONSerialization.isValidJSONObject(b), let d = try? JSONSerialization.data(withJSONObject: b) { body = String(decoding: d, as: UTF8.self) }
            else { body = "" }
            body = try substitute(body, ctx: ctx, base: base, charset: charset, encodeKey: !body.hasPrefix("{"))
            let isJSON = body.trimmingCharacters(in: .whitespaces).hasPrefix("{")
            if req.value(forHTTPHeaderField: "Content-Type") == nil {
                req.setValue(isJSON ? "application/json; charset=UTF-8" : "application/x-www-form-urlencoded; charset=\(charset ?? "UTF-8")", forHTTPHeaderField: "Content-Type")
            }
            req.httpBody = isJSON ? Data(body.utf8) : (body.data(using: LegadoHTTP.encoding(charset) ?? .utf8) ?? Data(body.utf8))
        }
        return LegadoRequest(request: req, charset: charset)
    }

    /// Replaces `{{key}}`, `{{page}}` and `{{js expression}}`; the keyword is percent-encoded in `charset`.
    static func substitute(_ s: String, ctx: LegadoContext, base: String, charset: String?, encodeKey: Bool) throws -> String {
        var out = "", rest = Substring(s)
        while let open = rest.range(of: "{{"), let close = rest.range(of: "}}", range: open.upperBound..<rest.endIndex) {
            out += rest[..<open.lowerBound]
            let inner = rest[open.upperBound..<close.lowerBound].trimmingCharacters(in: .whitespaces)
            if inner == "key" { out += encodeKey ? LegadoHTTP.percentEncode(ctx.key ?? "", charset: charset) : (ctx.key ?? "") }
            else if inner == "page" { out += String(ctx.page ?? 1) }
            else { out += LegadoAnalyzer.textOf(try ctx.evalJS(inner, result: nil, baseURL: base)) }
            rest = rest[close.upperBound...]
        }
        out += rest
        // Legacy `searchKey` / `searchPage` placeholders.
        return out.replacingOccurrences(of: "searchKey", with: LegadoHTTP.percentEncode(ctx.key ?? "", charset: charset))
            .replacingOccurrences(of: "searchPage", with: String(ctx.page ?? 1))
    }

    static func resolve(_ s: String, base: String) -> URL? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if let u = URL(string: t), u.scheme != nil { return u }
        let b = URL(string: base) ?? URL(string: base.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? "")
        if let u = URL(string: t, relativeTo: b)?.absoluteURL { return u }
        return URL(string: t.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? t, relativeTo: b)?.absoluteURL
    }

    /// Absolute URL text for links found in pages (href / src values).
    static func absolute(_ s: String, base: String) -> String {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return "" }
        if t.contains(",{") { return t }   // keeps request options
        return resolve(t, base: base)?.absoluteString ?? t
    }

    static func headers(ctx: LegadoContext) throws -> [String: String] {
        guard var h = ctx.source.header?.trimmingCharacters(in: .whitespacesAndNewlines), !h.isEmpty else { return [:] }
        if h.hasPrefix("@js:") || h.hasPrefix("<js>") {
            let code = h.hasPrefix("@js:") ? String(h.dropFirst(4)) : h.replacingOccurrences(of: "<js>", with: "").replacingOccurrences(of: "</js>", with: "")
            h = LegadoAnalyzer.textOf(try ctx.evalJS(code, result: nil, baseURL: ctx.source.bookSourceUrl))
        }
        guard let d = h.data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return [:] }
        return o.mapValues { "\($0)" }
    }
}
