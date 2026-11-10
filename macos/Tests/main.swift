import Foundation

var checks = 0
func check(_ value: Bool, _ label: String) {
  checks += 1
  if !value { fatalError("FAIL: " + label) }
}
func rejects(_ label: String, _ body: () throws -> Void) {
  checks += 1
  do {
    try body()
    fatalError("FAIL accepted " + label)
  } catch {}
}
func bytes(_ hex: String) -> [UInt8] {
  stride(from: 0, to: hex.count, by: 2).map { i in
    let a = hex.index(hex.startIndex, offsetBy: i)
    let b = hex.index(a, offsetBy: 2)
    return UInt8(hex[a..<b], radix: 16)!
  }
}
check(Wire.hex(Wire.read) == "0A00000000000000", "read frame")
check(Wire.hex(Wire.version) == "ACC105", "version frame")
check(Wire.hex(Wire.peer) == "ACC106", "peer frame")
check(Wire.hex(Wire.commit) == "1901AA0000000000", "commit frame")
check(try Wire.login(Data(repeating: 1, count: 8)).count == 17, "login length")
rejects("identity") { _ = try Wire.login(Data()) }
check(try Wire.hex(Wire.shift(3, count: 11)) == "1E03000000000000", "shift")
check(try Wire.hex(Wire.trim(2, delta: -600)) == "1E02015802000001", "trim sign/LE")
rejects("gear") { _ = try Wire.shift(-1, count: 11) }
rejects("overflow trim") { _ = try Wire.trim(2, delta: Int.min) }
rejects("empty trim") { _ = try Wire.trim(2, delta: 0) }
for p in [[Int](), [0], [0, 0, 10], [0, 30000], [-1, 100]] {
  rejects("positions") { try Wire.validate(p) }
}
let frames = try Wire.upload([0, 1500, 3000], adjustment: 600)
check(
  frames.map(Wire.hex) == ["190A000000000000", "190000DC05580200", "19CC02DC05000000"],
  "Android upload vector")
rejects("2-speed upload") { _ = try Wire.upload([0, 100], adjustment: 0) }
rejects("adjustment") { _ = try Wire.upload([0, 100, 200], adjustment: -1) }
var reader = TableReader()
check(try reader.accept(bytes("0AC00000DC05")) == nil, "first table part")
check(try reader.accept(bytes("0AC1DC050000")) == nil, "second table part")
let table = try reader.accept(bytes("0ACC580201"))
check(table == GearTable(positions: [0, 1500, 3000], adjustment: 600, gear: 1), "complete table")
var malformed = TableReader()
rejects("short table") { _ = try malformed.accept(bytes("0AC001")) }
check(Wire.firmware(bytes("ACC10576312E3130350000")) == "v1.105", "modern version")
check(Wire.firmware(bytes("3700")) == "27 (legacy reply)", "legacy version")
for h in ["37", "ACC105", "ACC105FF", "ACC1050031", "ACC1063131"] {
  check(Wire.firmware(bytes(h)) == nil, "bad version " + h)
}
check(Wire.battery(bytes("010101B004")) == 1200, "battery voltage")
check(Wire.battery(bytes("0101010000")) == nil, "bad battery")
for (cv, pct) in [
  (1020, 0), (1116, 20), (1140, 30), (1152, 50), (1173, 60), (1209, 80), (1230, 90), (1240, 100),
] { check(Wire.percent(cv) == pct, "battery threshold") }
let android = Data(
  "{\"schema\":1,\"name\":\"Rival 22\",\"count\":3,\"derailleur\":1,\"cassette\":1,\"positions\":[0,1500,3000],\"calibrated\":true}"
    .utf8)
let p = try Profile.decode(android)
check(p.teeth.isEmpty && p.adjustment == nil, "Android optional metadata")
check(try Profile.decode(p.encoded()).positions == p.positions, "profile roundtrip")
rejects("unknown schema") {
  _ = try Profile.decode(
    Data(
      String(decoding: android, as: UTF8.self).replacingOccurrences(
        of: "\"schema\":1", with: "\"schema\":2"
      ).utf8))
}
var wrong = p
wrong.teeth = [11, 10, 15]
rejects("sprocket order") { try wrong.validate() }
wrong = p
wrong.count = 11
rejects("count mismatch") { try wrong.validate() }
wrong = p
wrong.calibrated = false
rejects("uncalibrated upload") { try wrong.validate(requireCalibrated: true) }
print("PASS: \(checks) protocol/profile checks")
