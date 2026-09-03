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

    public static func redact(_ text: String, knownSecrets: [String] = []) -> String {
        var result = replace(headerPattern, in: text, template: "$1$2<redacted>")
        result = replace(environmentPattern, in: result, template: "$1$2<redacted>")
        result = replace(queryPattern, in: result, template: "$1<redacted>")

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
