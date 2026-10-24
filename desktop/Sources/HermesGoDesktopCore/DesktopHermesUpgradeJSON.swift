import Foundation

/// The decoder's duplicate-key choice must never decide whether recovery is still owed.
/// Parse bounded JSON first, comparing decoded keys within EACH object (including escaped keys).
/// Foundation still performs typed decoding after this syntax/ambiguity check.
enum DesktopHermesUpgradeJSON {
    static func isUnambiguous(_ data: Data) -> Bool {
        guard !data.isEmpty, data.count <= 64 * 1024, String(data: data, encoding: .utf8) != nil else { return false }
        var parser = Parser(bytes: Array(data))
        do {
            try parser.value(depth: 0)
            parser.whitespace()
            return parser.offset == parser.bytes.count
        } catch { return false }
    }

    private enum Invalid: Error { case json }

    private struct Parser {
        let bytes: [UInt8]
        var offset = 0
        var remainingNodes = 4096

        mutating func whitespace() {
            while offset < bytes.count, [9, 10, 13, 32].contains(bytes[offset]) { offset += 1 }
        }

        mutating func take(_ byte: UInt8) -> Bool {
            whitespace()
            guard offset < bytes.count, bytes[offset] == byte else { return false }
            offset += 1
            return true
        }

        mutating func value(depth: Int) throws {
            guard depth <= 16, remainingNodes > 0 else { throw Invalid.json }
            remainingNodes -= 1
            whitespace()
            guard offset < bytes.count else { throw Invalid.json }
            switch bytes[offset] {
            case 123: // object
                offset += 1
                var keys = Set<String>()
                if take(125) { return }
                while true {
                    let key = try string()
                    guard keys.insert(key).inserted, take(58) else { throw Invalid.json }
                    try value(depth: depth + 1)
                    if take(125) { return }
                    guard take(44) else { throw Invalid.json }
                }
            case 91: // array
                offset += 1
                if take(93) { return }
                while true {
                    try value(depth: depth + 1)
                    if take(93) { return }
                    guard take(44) else { throw Invalid.json }
                }
            case 34: _ = try string()
            case 116: try literal("true")
            case 102: try literal("false")
            case 110: try literal("null")
            default: try number()
            }
        }

        mutating func literal(_ text: String) throws {
            let expected = Array(text.utf8)
            guard offset + expected.count <= bytes.count,
                  Array(bytes[offset..<(offset + expected.count)]) == expected else { throw Invalid.json }
            offset += expected.count
        }

        mutating func string() throws -> String {
            whitespace()
            let start = offset
            guard offset < bytes.count, bytes[offset] == 34 else { throw Invalid.json }
            offset += 1
            while offset < bytes.count {
                let byte = bytes[offset]
                offset += 1
                if byte == 34 {
                    guard let value = try JSONSerialization.jsonObject(with: Data(bytes[start..<offset]), options: .fragmentsAllowed) as? String
                    else { throw Invalid.json }
                    return value
                }
                guard byte >= 32 else { throw Invalid.json }
                if byte == 92 {
                    guard offset < bytes.count else { throw Invalid.json }
                    let escaped = bytes[offset]
                    offset += 1
                    if escaped == 117 {
                        guard offset + 4 <= bytes.count,
                              bytes[offset..<(offset + 4)].allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) })
                        else { throw Invalid.json }
                        offset += 4
                    } else if ![34, 92, 47, 98, 102, 110, 114, 116].contains(escaped) {
                        throw Invalid.json
                    }
                }
            }
            throw Invalid.json
        }

        mutating func digits() -> Int {
            let start = offset
            while offset < bytes.count, (48...57).contains(bytes[offset]) { offset += 1 }
            return offset - start
        }

        mutating func number() throws {
            if offset < bytes.count, bytes[offset] == 45 { offset += 1 }
            guard offset < bytes.count else { throw Invalid.json }
            if bytes[offset] == 48 { offset += 1 }
            else {
                guard (49...57).contains(bytes[offset]), digits() > 0 else { throw Invalid.json }
            }
            if offset < bytes.count, bytes[offset] == 46 {
                offset += 1
                guard digits() > 0 else { throw Invalid.json }
            }
            if offset < bytes.count, bytes[offset] == 101 || bytes[offset] == 69 {
                offset += 1
                if offset < bytes.count, bytes[offset] == 43 || bytes[offset] == 45 { offset += 1 }
                guard digits() > 0 else { throw Invalid.json }
            }
        }
    }
}
