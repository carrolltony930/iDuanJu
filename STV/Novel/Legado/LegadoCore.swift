import Foundation
import CryptoKit
import libxml2
import CQuickJS

// MARK: - HTML nodes (libxml2)

/// One parsed HTML document; nodes keep it alive.
final class LegadoDoc: @unchecked Sendable {
    let doc: htmlDocPtr
    init(_ html: String) {
        let opts = Int32(HTML_PARSE_RECOVER.rawValue | HTML_PARSE_NOERROR.rawValue | HTML_PARSE_NOWARNING.rawValue | HTML_PARSE_NONET.rawValue)
        let src = html.isEmpty ? "<html></html>" : html
        doc = src.withCString { p in htmlReadMemory(p, Int32(strlen(p)), "about:blank", "UTF-8", opts) } ?? htmlNewDoc(nil, nil)
    }
    deinit { xmlFreeDoc(doc) }
    var node: LegadoNode { LegadoNode(owner: self, ptr: UnsafeMutableRawPointer(doc).assumingMemoryBound(to: xmlNode.self)) }
}

struct LegadoNode {
    let owner: LegadoDoc
    let ptr: xmlNodePtr

    var isElement: Bool { ptr.pointee.type == XML_ELEMENT_NODE }
    var name: String { ptr.pointee.name.map { String(cString: $0) }?.lowercased() ?? "" }

    var children: [LegadoNode] {
        var out: [LegadoNode] = []
        var c = ptr.pointee.children
        while let n = c { if n.pointee.type == XML_ELEMENT_NODE { out.append(LegadoNode(owner: owner, ptr: n)) }; c = n.pointee.next }
        return out
    }

    /// Any XPath result: elements, attributes and text nodes as nodes; scalars as strings.
    func xpathRaw(_ expr: String) -> (nodes: [LegadoNode], scalar: String?) {
        guard let ctx = xmlXPathNewContext(ptr.pointee.doc) else { return ([], nil) }
        defer { xmlXPathFreeContext(ctx) }
        ctx.pointee.node = ptr
        guard let obj = xmlXPathEvalExpression(expr, ctx) else { return ([], nil) }
        defer { xmlXPathFreeObject(obj) }
        switch obj.pointee.type {
        case XPATH_NODESET:
            guard let set = obj.pointee.nodesetval else { return ([], nil) }
            return ((0..<Int(set.pointee.nodeNr)).compactMap { set.pointee.nodeTab[$0].map { LegadoNode(owner: owner, ptr: $0) } }, nil)
        case XPATH_STRING: return ([], obj.pointee.stringval.map { String(cString: $0) })
        case XPATH_NUMBER: let v = obj.pointee.floatval; return ([], v == v.rounded() ? String(Int(v)) : String(v))
        case XPATH_BOOLEAN: return ([], obj.pointee.boolval != 0 ? "true" : "false")
        default: return ([], nil)
        }
    }

    func css(_ selector: String) -> [LegadoNode] {
        guard let x = try? CSSXPath.convert(selector) else { return [] }
        return xpathRaw(x).nodes.filter(\.isElement)
    }

    /// Jsoup `text()`: all descendant text, whitespace collapsed.
    var text: String {
        guard let c = xmlNodeGetContent(ptr) else { return "" }
        defer { xmlFree(c) }
        let s = String(cString: c)
        return s.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    /// Text of direct text children, one per line (Jsoup `textNodes`).
    var textNodes: [String] {
        var out: [String] = []
        var c = ptr.pointee.children
        while let n = c {
            if n.pointee.type == XML_TEXT_NODE || n.pointee.type == XML_CDATA_SECTION_NODE, let v = xmlNodeGetContent(n) {
                let s = String(cString: v).trimmingCharacters(in: .whitespacesAndNewlines)
                xmlFree(v)
                if !s.isEmpty { out.append(s) }
            }
            c = n.pointee.next
        }
        return out
    }

    var ownText: String { textNodes.joined(separator: " ") }

    func attr(_ name: String) -> String {
        guard isElement, let v = xmlGetProp(ptr, name.lowercased()) else { return "" }
        defer { xmlFree(v) }
        return String(cString: v)
    }

    func html(outer: Bool) -> String {
        guard let buf = xmlBufferCreate() else { return "" }
        defer { xmlBufferFree(buf) }
        if ptr.pointee.type == XML_HTML_DOCUMENT_NODE || ptr.pointee.type == XML_DOCUMENT_NODE {
            if let root = xmlDocGetRootElement(ptr.pointee.doc) { htmlNodeDump(buf, ptr.pointee.doc, root) }
        } else if outer {
            htmlNodeDump(buf, ptr.pointee.doc, ptr)
        } else {
            var c = ptr.pointee.children
            while let n = c { htmlNodeDump(buf, ptr.pointee.doc, n); c = n.pointee.next }
        }
        return xmlBufferContent(buf).map { String(cString: $0) } ?? ""
    }

    /// Value of a non-element XPath hit (attribute / text node).
    var stringValue: String {
        if isElement { return text }
        guard let c = xmlNodeGetContent(ptr) else { return "" }
        defer { xmlFree(c) }
        return String(cString: c)
    }

    /// Element and all element descendants (document order).
    var descendants: [LegadoNode] { children.flatMap { [$0] + $0.descendants } }
}

// MARK: - HTTP

struct LegadoResponse {
    var url: URL
    var status: Int
    var headers: [String: String]
    var body: Data
    var text: String
}

enum LegadoError: LocalizedError {
    case http(Int, String), network(String), rule(String), empty(String)
    var errorDescription: String? {
        switch self {
        case .http(let c, let u): "HTTP \(c)：\(u)"
        case .network(let m): m
        case .rule(let m): "书源规则错误：\(m)"
        case .empty(let m): m
        }
    }
}

enum LegadoHTTP {
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    /// Tests replace the network (URL → response).
    nonisolated(unsafe) static var transport: ((URLRequest) throws -> (Data, HTTPURLResponse))?

    /// Synchronous request (book-source rules run on a worker thread).
    static func fetch(_ req0: URLRequest, charset: String? = nil) throws -> LegadoResponse {
        var req = req0
        if req.value(forHTTPHeaderField: "User-Agent") == nil { req.setValue(userAgent, forHTTPHeaderField: "User-Agent") }
        if req.timeoutInterval > 20 || req.timeoutInterval <= 0 { req.timeoutInterval = 15 }
        let data: Data, resp: HTTPURLResponse
        if let t = transport {
            (data, resp) = try t(req)
        } else {
            let sem = DispatchSemaphore(value: 0)
            nonisolated(unsafe) var out: (Data?, URLResponse?, Error?)
            URLSession.shared.dataTask(with: req) { d, r, e in out = (d, r, e); sem.signal() }.resume()
            sem.wait()
            if let e = out.2 { throw LegadoError.network("网络错误：\(e.localizedDescription)") }
            guard let r = out.1 as? HTTPURLResponse else { throw LegadoError.network("没有响应") }
            (data, resp) = (out.0 ?? Data(), r)
        }
        let headers = Dictionary(resp.allHeaderFields.map { ("\($0.key)".lowercased(), "\($0.value)") }, uniquingKeysWith: { a, _ in a })
        guard (200..<400).contains(resp.statusCode) else { throw LegadoError.http(resp.statusCode, req.url?.absoluteString ?? "") }
        let text = decode(data, contentType: headers["content-type"], charset: charset)
        return LegadoResponse(url: resp.url ?? req.url!, status: resp.statusCode, headers: headers, body: data, text: text)
    }

    static func encoding(_ name: String?) -> String.Encoding? {
        guard let n = name?.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "\"' ")), !n.isEmpty else { return nil }
        if n.contains("utf-8") || n == "utf8" { return .utf8 }
        if n.contains("gb") { return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue))) }
        if n.contains("big5") { return String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.big5.rawValue))) }
        let cf = CFStringConvertIANACharSetNameToEncoding(n as CFString)
        return cf == kCFStringEncodingInvalidId ? nil : String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))
    }

    /// Charset: source option > Content-Type > <meta charset> > UTF-8 (GB18030 if UTF-8 fails).
    static func decode(_ d: Data, contentType: String?, charset: String?) -> String {
        if let e = encoding(charset), let s = String(data: d, encoding: e) { return s }
        if let ct = contentType, let r = ct.range(of: "charset=", options: .caseInsensitive),
        let e = encoding(String(ct[r.upperBound...]).components(separatedBy: ";")[0]), let s = String(data: d, encoding: e) { return s }
        let head = String(decoding: d.prefix(2048), as: UTF8.self)
        if let m = head.range(of: #"charset\s*=\s*["']?([A-Za-z0-9_\-]+)"#, options: [.regularExpression, .caseInsensitive]) {
            let name = head[m].components(separatedBy: "=").last?.trimmingCharacters(in: CharacterSet(charactersIn: "\"' ")) ?? ""
            if let e = encoding(name), let s = String(data: d, encoding: e) { return s }
        }
        if let s = String(data: d, encoding: .utf8) { return s }
        if let e = encoding("gbk"), let s = String(data: d, encoding: e) { return s }
        return String(decoding: d, as: UTF8.self)
    }

    /// Percent-encodes `s` in `charset` (GBK sites expect GBK-encoded keywords).
    static func percentEncode(_ s: String, charset: String?) -> String {
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.~")
        guard let e = encoding(charset), e != .utf8, let d = s.data(using: e) else {
            return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
        }
        return d.map { b in
            let c = Character(Unicode.Scalar(b))
            return b < 0x80 && allowed.contains(Unicode.Scalar(b)) ? String(c) : String(format: "%%%02X", b)
        }.joined()
    }
}

// MARK: - JavaScript (QuickJS sandbox)

/// Runs Legado `@js:` / `<js>` / `{{ }}` snippets. Each evaluation gets a fresh sandbox (top-level `let` in one
/// snippet must not clash with the next). `java.*` calls come back to the host through `handler`.
final class LegadoJS {
    /// Host handler: (function name, JSON args) → JSON result.
    var handler: (String, Any?) throws -> Any? = { _, _ in nil }
    var jsLib: String?

    static let prelude = #"""
    (function (h) {
            const call = (n, a) => { const r = h(n, JSON.stringify(a === undefined ? null : a)); return r === undefined ? undefined : JSON.parse(r); };
            const str = (v) => v == null ? '' : String(v);
            globalThis.java = {
                ajax: (u) => call('ajax', { url: str(Array.isArray(u) ? u[0] : u) }),
                connect: (u, h2) => { const r = call('connect', { url: str(u), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (k) => (r.headers || {})[String(k).toLowerCase()], headers: () => r.headers, raw: r }; },
                get: (k, h2) => { if (h2 === undefined && !/^https?:/.test(str(k))) return call('getVar', { k: str(k) }); const r = call('connect', { url: str(k), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (n) => (r.headers || {})[String(n).toLowerCase()] }; },
                post: (u, b, h2) => { const r = call('connect', { url: str(u), method: 'POST', body: str(b), headers: h2 || null }); return { body: () => r.body, code: () => r.status, url: () => r.url, header: (n) => (r.headers || {})[String(n).toLowerCase()] }; },
                put: (k, v) => { call('putVar', { k: str(k), v: str(v) }); return v; },
                getString: (rule, content) => call('getString', { rule: str(rule), content: content === undefined ? null : str(content) }),
                getStringList: (rule, content) => call('getStringList', { rule: str(rule), content: content === undefined ? null : str(content) }),
                setContent: (c, b) => call('setContent', { content: str(c), base: b === undefined ? null : str(b) }),
                base64Decode: (s, cs) => call('b64d', { s: str(s), cs: cs || null }),
                base64Encode: (s) => call('b64e', { s: str(s) }),
                md5Encode: (s) => call('md5', { s: str(s) }),
                md5Encode16: (s) => call('md5', { s: str(s) }).substring(8, 24),
                encodeURI: (s, cs) => call('encodeURI', { s: str(s), cs: cs || null }),
                timeFormat: (t) => { const d = new Date(Number(t)); const p = (n) => String(n).padStart(2, '0'); return d.getFullYear() + '/' + p(d.getMonth() + 1) + '/' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); },
                htmlFormat: (s) => call('htmlFormat', { s: str(s) }),
                log: (s) => { call('log', { s: str(s) }); return s; },
                toast: () => {}, longToast: () => {},
                getCookie: (u, k) => call('cookie', { url: str(u), k: k == null ? null : str(k) }),
            };
            globalThis.cookie = { getCookie: (u) => java.getCookie(u), getKey: (u, k) => java.getCookie(u, k), removeCookie: () => {} };
            globalThis.__legado_call = call;
    })(__host);
    """#

    func eval(_ code: String, _ bindings: [String: Any]) throws -> Any? {
        let box = idj_new(32 << 20, 1 << 20, legadoHostCallback, Unmanaged.passUnretained(self).toOpaque())
        guard let box else { throw LegadoError.rule("JS 引擎初始化失败") }
        defer { idj_free(box) }
        var err: UnsafeMutablePointer<CChar>?
        guard idj_load(box, Self.prelude, "export default {}", "legado", 5, &err) == 0 else {
            defer { if let err { idj_str_free(err) } }
            throw LegadoError.rule("JS 初始化失败：" + (err.map { String(cString: $0) } ?? ""))
        }
        var header = ""
        for (k, v) in bindings {
            let json = (JSONSerialization.isValidJSONObject([v]) ? try? JSONSerialization.data(withJSONObject: [v]) : nil).map { String(decoding: $0, as: UTF8.self) } ?? "[null]"
            header += "var \(k) = \(json)[0];\n"
        }
        // `book` / `source` / `chapter` objects also answer Legado's getter style (book.getVariable, source.getKey()).
        header += "if (typeof source === 'object' && source) { source.getKey = () => source.bookSourceUrl; source.getVariable = () => ''; }\n"
        header += "if (typeof book === 'object' && book) { book.getVariable = () => ''; }\n"
        let src = header + (jsLib.map { $0 + "\n" } ?? "") + code
        guard let r = idj_eval_sloppy(box, src, 8, &err) else {
            defer { if let err { idj_str_free(err) } }
            let msg = err.map { String(cString: $0) } ?? "unknown"
            let m = (try? JSONSerialization.jsonObject(with: Data(msg.utf8)) as? [String: Any])?["message"] as? String ?? msg
            throw LegadoError.rule("JS：\(m)")
        }
        defer { idj_str_free(r) }
        return try? JSONSerialization.jsonObject(with: Data(String(cString: r).utf8), options: [.fragmentsAllowed])
    }

    // Helpers used by the handler.
    static func md5(_ s: String) -> String { Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
}

private let legadoHostCallback: idj_host_fn = { opaque, name, args, isErr in
    guard let opaque, let name else { return nil }
    let js = Unmanaged<LegadoJS>.fromOpaque(opaque).takeUnretainedValue()
    let a = args.flatMap { try? JSONSerialization.jsonObject(with: Data(String(cString: $0).utf8), options: [.fragmentsAllowed]) }
    do {
        let r = try js.handler(String(cString: name), a)
        guard let r, !(r is NSNull) else { return strdup("null") }
        if let d = try? JSONSerialization.data(withJSONObject: r, options: [.fragmentsAllowed]) { return strdup(String(decoding: d, as: UTF8.self)) }
        return strdup("null")
    } catch {
        isErr?.pointee = 1
        let msg = (error as? LocalizedError)?.errorDescription ?? "\(error)"
        let d = (try? JSONSerialization.data(withJSONObject: ["code": "E_HOST", "message": msg])) ?? Data()
        return strdup(String(decoding: d, as: UTF8.self))
    }
}
