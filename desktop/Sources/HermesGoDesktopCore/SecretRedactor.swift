import Foundation

public enum SecretRedactor {
    private static let headerPattern = try! NSRegularExpression(
        pattern: #"(?im)(authorization|cookie|set-cookie|x-hermes-session-token)(\s*:\s*)[^\r\n]+"#
    )
    private static let environmentPattern = try! NSRegularExpression(
        pattern: #"(?i)(connector_token|app_token|password)(\s*=\s*)([^\s,;]+)"#
    )
    private static let queryPattern = try! NSRegularExpression(
        pattern: #"(?i)([?&](?:token|ticket|password)=)([^&#\s]+)"#
    )
    /// Home directories name a person; diagnostics keep the shape of a path without the name.
    private static let homeDirectoryPattern = try! NSRegularExpression(
        pattern: #"/Users/(?!Shared/|<user>)[^/\s'"]+"#
    )
    /// `scheme://user:password@host` — proxy URLs are the usual carrier. The password may itself
    /// contain `@` or `/`, so everything up to the last `@` of the token is taken.
    private static let urlUserPasswordPattern = try! NSRegularExpression(
        pattern: #"(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@:'"]+:[^\s'"]*@"#
    )
    /// `scheme://token@host` without a password (a token used as the user name).
    private static let urlUserInfoPattern = try! NSRegularExpression(
        pattern: #"(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@:'"]+@"#
    )
    /// `OPENAI_API_KEY=…`, `ANTHROPIC_API_KEY: …` — what an installer or a config dump prints.
    private static let apiKeyAssignmentPattern = try! NSRegularExpression(
        pattern: #"(?i)\b([a-z0-9_]*api_key)(\s*[=:]\s*)(["']?)[^\s"',;]+"#
    )
    /// Well-known credential shapes: GitHub tokens and `sk-` provider keys.
    private static let knownTokenPattern = try! NSRegularExpression(
        pattern: #"\b(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,})"#
    )
    private static let accountCredentialPattern = try! NSRegularExpression(
        pattern: #"\b(?:hga|hgr|hgg|hsi)_[A-Za-z0-9_-]+\b"#
    )

    public static func redact(_ text: String, knownSecrets: [String] = []) -> String {
        var result = replace(headerPattern, in: text, template: "$1$2<redacted>")
        result = replace(environmentPattern, in: result, template: "$1$2<redacted>")
        result = replace(queryPattern, in: result, template: "$1<redacted>")
        result = replace(accountCredentialPattern, in: result, template: "<redacted>")
        result = replace(urlUserPasswordPattern, in: result, template: "$1<redacted>@")
        result = replace(urlUserInfoPattern, in: result, template: "$1<redacted>@")
        result = replace(apiKeyAssignmentPattern, in: result, template: "$1$2$3<redacted>")
        result = replace(knownTokenPattern, in: result, template: "<redacted>")
        result = replace(homeDirectoryPattern, in: result, template: "/Users/<user>")

        for secret in knownSecrets.filter({ $0.count >= 4 }).sorted(by: { $0.count > $1.count }) {
            result = result.replacingOccurrences(of: secret, with: "<redacted>")
        }
        return result
    }

    private static func replace(
        _ expression: NSRegularExpression,
        in text: String,
        template: String
    ) -> String {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return expression.stringByReplacingMatches(in: text, range: range, withTemplate: template)
    }
}
