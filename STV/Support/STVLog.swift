import Foundation

/// 运行日志：同时写 NSLog 和 Documents/stv.log。
/// 目的只有一个 —— 播放失败时用户能直接把日志取出来（「文件」App → 我的 iPhone → i短剧），
/// 不用再靠截图猜。纯旁路，不参与任何播放决策。
enum STVLog {
    private static let q = DispatchQueue(label: "stv.log")
    private static let url: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return d.appendingPathComponent("stv.log")
    }()
    private static let fmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    /// 最近一条诊断，供播放失败浮层直接显示（用户拍屏即可定位）。
    private static let dq = DispatchQueue(label: "stv.log.diag")
    private static var _diag = ""
    static var diag: String {
        dq.sync { _diag }
    }

    static func w(_ s: String) {
        NSLog("%@", "[STV] " + s)
        dq.sync { _diag = s }
        q.async {
            guard let data = ("\(fmt.string(from: Date())) \(s)\n").data(using: .utf8) else { return }
            if let h = try? FileHandle(forWritingTo: url) {
                defer { try? h.close() }
                _ = try? h.seekToEnd()
                try? h.write(contentsOf: data)
            } else {
                try? data.write(to: url, options: .atomic)
            }
            if let n = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int,
               n > 512 * 1024 {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }
}
