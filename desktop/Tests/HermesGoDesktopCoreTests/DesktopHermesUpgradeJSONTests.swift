import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeJSONTests: XCTestCase {
    func testLegitimateEscapesNumbersAndSameKeysInSeparateObjectsRemainReadable() throws {
        let text = #"{"services":[{"label":"a","quoted":"key:\"phase\": is a value"},{"label":"b"}],"escaped\u004bey":"用户🤖","decimal":-1.2e+3,"small":1e-3,"zero":0,"boolean":true,"none":null}"#
        XCTAssertNoThrow(try JSONSerialization.jsonObject(with: Data(text.utf8)))
        XCTAssertTrue(DesktopHermesUpgradeJSON.isUnambiguous(Data(text.utf8)))
        let object: [String: Any] = ["path": "/private/用户/🤖\"quoted\"", "values": [true, false, NSNull()],
                                     "rows": [["name": "one"], ["name": "two"]]]
        XCTAssertTrue(DesktopHermesUpgradeJSON.isUnambiguous(try JSONSerialization.data(withJSONObject: object)))
    }

    func testMalformedAmbiguousOrOutOfBudgetDocumentsAreRejected() {
        let malformed: [String] = ["", "{} {}", "[1,]", "{\"a\":1,}", "[01]", "[-01]", "[1e]", "[+1]", "[.1]", "[truex]",
                                   #"{"a":"\x"}"#, #"{"phase":1,"ph\u0061se":2}"#,
                                   #"{"nested":{"a":1,"a":2}}"#]
        // Keep each synthesized input separately typed for the older CI Swift compiler.
        let nested = String(repeating: "[", count: 20) + "0" + String(repeating: "]", count: 20)
        let manyNodes = "[" + Array(repeating: "0", count: 5000).joined(separator: ",") + "]"
        let manyBytes = "\"" + String(repeating: "x", count: 70 * 1024) + "\""
        for value in malformed + [nested, manyNodes, manyBytes] {
            XCTAssertFalse(DesktopHermesUpgradeJSON.isUnambiguous(Data(value.utf8)), String(value.prefix(80)))
        }
        XCTAssertFalse(DesktopHermesUpgradeJSON.isUnambiguous(Data([0xff])))
    }
}
