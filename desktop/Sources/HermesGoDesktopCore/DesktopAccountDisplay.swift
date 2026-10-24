import Foundation

public enum DesktopAccountDisplay {
    public static func name(displayName: String?, email: String?) -> String {
        let name = displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let email = email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !name.isEmpty, !name.contains("@") { return name }
        let address = email.isEmpty ? name : email
        if let at = address.firstIndex(of: "@"), at != address.startIndex {
            return String(address[..<at])
        }
        return name.isEmpty ? "我的账号" : name
    }
}
