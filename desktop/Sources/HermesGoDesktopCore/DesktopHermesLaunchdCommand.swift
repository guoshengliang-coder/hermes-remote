import Foundation

/// Read-only decoder for upstream gateway_launchd.launchd_program_arguments (AppleScript and JXA).
/// Never runs osascript, a shell or Hermes. Unknown wrappers remain unverified.
enum DesktopHermesLaunchdCommand {
    static func unwrap(_ arguments: [String]) -> [String]? {
        guard let shell = shellCommand(arguments),
              let tokens = split(shell), tokens.count >= 6,
              tokens.first == "exec", tokens[tokens.count - 4] == ">>", tokens[tokens.count - 2] == "2>>" else { return nil }
        let command = Array(tokens.dropFirst().dropLast(4))
        let stdout = tokens[tokens.count - 3], stderr = tokens[tokens.count - 1]
        guard command.first?.hasPrefix("/") == true, stdout.hasPrefix("/"), stderr.hasPrefix("/"),
              !tokens.contains(where: { $0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }) else { return nil }
        // Regenerate upstream's shlex.join/quote form. A path in a comment, substitution or second
        // command cannot count as evidence; operators occur only in the two fixed redirections.
        let expected = "exec " + command.map(quote).joined(separator: " ") + " >> " + quote(stdout) + " 2>> " + quote(stderr)
        guard expected == shell else { return nil }
        return command
    }

    private static func shellCommand(_ arguments: [String]) -> String? {
        guard let script = arguments.last, script.utf8.count <= 64 * 1024 else { return nil }
        if arguments.count == 3, Array(arguments.prefix(2)) == ["/usr/bin/osascript", "-e"] {
            let prefix = "do shell script "
            guard script.hasPrefix(prefix),
                  let shell = try? JSONDecoder().decode(String.self, from: Data(script.dropFirst(prefix.count).utf8))
            else { return nil }
            // Upstream's historical AppleScript escapes only backslashes and double quotes.
            // JSON decoding is a superset; exact reconstruction rejects other JSON escapes and
            // appended AppleScript expressions without ever interpreting either language.
            let escaped = shell.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
            guard script == prefix + "\"" + escaped + "\"" else { return nil }
            return shell
        }
        guard arguments.count == 5,
              Array(arguments.prefix(4)) == ["/usr/bin/osascript", "-l", "JavaScript", "-e"] else { return nil }
        let prefix = "ObjC.import(\"stdlib\"); const status=$.system("
        let suffix = "); const signal=status & 127; $.exit(status === -1 ? 1 : signal === 0 ? (status >> 8) & 255 : 128 + signal);"
        guard script.hasPrefix(prefix), script.hasSuffix(suffix) else { return nil }
        let literal = String(script.dropFirst(prefix.count).dropLast(suffix.count))
        return try? JSONDecoder().decode(String.self, from: Data(literal.utf8))
    }

    private static func quote(_ value: String) -> String {
        let safe = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_@%+=:,./-")
        if !value.isEmpty && value.unicodeScalars.allSatisfy({ safe.contains($0) }) { return value }
        return "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
    }

    /// Minimal POSIX word decoding, followed by exact canonical reconstruction above. No expansion.
    private static func split(_ text: String) -> [String]? {
        var words: [String] = [], word = ""
        var quote: Character?, escaped = false, started = false
        for char in text {
            if escaped { word.append(char); escaped = false; started = true; continue }
            if let delimiter = quote {
                if char == delimiter { quote = nil }
                else if char == "\\" && delimiter == "\"" { escaped = true }
                else { word.append(char) }
            } else if char == "'" || char == "\"" { quote = char; started = true }
            else if char == "\\" { escaped = true; started = true }
            else if char == " " {
                if started { words.append(word); word = ""; started = false }
            } else { word.append(char); started = true }
        }
        guard quote == nil, !escaped else { return nil }
        if started { words.append(word) }
        return words
    }
}
