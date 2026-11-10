import Foundation

enum ShiftError: LocalizedError {
  case invalid(String)
  var errorDescription: String? {
    if case .invalid(let s) = self { return s }
    return nil
  }
}

enum Wire {
  static let service = "00001523-0000-1000-8000-00805F9B34FB"
  static let write = "00001524-0000-1000-8000-00805F9B34FB"
  static let notify = "00001525-0000-1000-8000-00805F9B34FB"
  static let read = Data([10, 0, 0, 0, 0, 0, 0, 0])
  static let version = Data([0xac, 0xc1, 5])
  static let peer = Data([0xac, 0xc1, 6])
  static let commit = Data([25, 1, 0xaa, 0, 0, 0, 0, 0])
  static func login(_ identity: Data) throws -> Data {
    guard identity.count == 8 else { throw ShiftError.invalid("Identity must contain 8 bytes") }
    return Data([0xdf, 0x89]) + identity + Data(repeating: 0, count: 7)
  }
  static func validate(_ p: [Int]) throws {
    guard (2...17).contains(p.count) else { throw ShiftError.invalid("Use 2–17 positions") }
    for i in p.indices {
      guard (0...25200).contains(p[i]), i == 0 || p[i] > p[i - 1] else {
        throw ShiftError.invalid("Positions must increase strictly within 0–42 mm")
      }
    }
  }
  static func shift(_ gear: Int, count: Int) throws -> Data {
    guard (2...17).contains(count), (0..<count).contains(gear) else {
      throw ShiftError.invalid("Invalid gear")
    }
    return Data([30, UInt8(gear), 0, 0, 0, 0, 0, 0])
  }
  static func trim(_ gear: Int, delta: Int) throws -> Data {
    guard (0..<17).contains(gear), delta != 0, (-25200...25200).contains(delta) else {
      throw ShiftError.invalid("Invalid trim")
    }
    let n = abs(delta)
    return Data([30, UInt8(gear), delta < 0 ? 1 : 0, UInt8(n & 255), UInt8(n >> 8), 0, 0, 1])
  }
  static func upload(_ p: [Int], adjustment: Int) throws -> [Data] {
    try validate(p)
    guard p.count >= 3, (0...25200).contains(adjustment) else {
      throw ShiftError.invalid("Table upload requires 3–17 gears and a valid adjustment")
    }
    return p.indices.map { i in
      let n = i == 0 ? p[i] : p[i] - p[i - 1]
      return Data([
        25, i == 1 ? 0 : i == p.count - 1 ? 0xcc : 10,
        i == 1 ? 0 : UInt8(i), UInt8(n & 255), UInt8(n >> 8),
        i == 1 ? UInt8(adjustment & 255) : 0,
        i == 1 ? UInt8(adjustment >> 8) : 0, 0,
      ])
    }
  }
  static func le(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) | (Int(b[i + 1]) << 8) }
  static func firmware(_ b: [UInt8]) -> String? {
    if b.count > 1 && b[0] == 0x37 { return "27 (legacy reply)" }
    guard b.count > 3, Array(b.prefix(3)) == [0xac, 0xc1, 5] else { return nil }
    var bytes: [UInt8] = []
    var ended = false
    for c in b.dropFirst(3) {
      if c == 0 {
        ended = true
        continue
      }
      if ended || !(32...126).contains(c) { return nil }
      bytes.append(c)
    }
    guard bytes.contains(where: { (48...57).contains($0) }) else { return nil }
    return String(bytes: bytes, encoding: .ascii)?.trimmingCharacters(in: .whitespaces)
  }
  static func battery(_ b: [UInt8]) -> Int? {
    guard b.count >= 5, Array(b.prefix(3)) == [1, 1, 1] else { return nil }
    let cv = le(b, 3)
    return (600...1500).contains(cv) ? cv : nil
  }
  static func percent(_ cv: Int) -> Int {
    let volts = [1020, 1116, 1140, 1152, 1173, 1209, 1230, 1240]
    let levels = [0, 20, 30, 50, 60, 80, 90, 100]
    if cv <= volts[0] { return 0 }
    for i in 1..<volts.count where cv <= volts[i] {
      return levels[i - 1]
        + Int(
          (Double(cv - volts[i - 1]) * Double(levels[i] - levels[i - 1])
            / Double(volts[i] - volts[i - 1])).rounded())
    }
    return 100
  }
  static func hex(_ d: Data) -> String { d.map { String(format: "%02X", $0) }.joined() }
}

struct GearTable: Equatable {
  var positions: [Int]
  var adjustment: Int
  var gear: Int
}
struct TableReader {
  private var parts: [Int: [UInt8]] = [:]
  mutating func accept(_ b: [UInt8]) throws -> GearTable? {
    guard b.count >= 2, b[0] == 10 else { return nil }
    let slot: Int
    switch b[1] {
    case 0xc0: slot = 0
    case 0xc1: slot = 1
    case 0xcc: slot = 2
    default: return nil
    }
    guard slot < 2 ? (b.count <= 20 && b.count % 2 == 0) : b.count >= 5 else {
      throw ShiftError.invalid("Truncated gear table")
    }
    parts[slot] = b
    guard let a = parts[0], let c = parts[1], let tail = parts[2] else { return nil }
    var inc: [Int] = []
    for part in [a, c] {
      for i in stride(from: 2, to: part.count, by: 2) { inc.append(Wire.le(part, i)) }
    }
    while inc.last == 0 { inc.removeLast() }
    var total = 0
    let p = inc.map {
      total += $0
      return total
    }
    if !p.isEmpty { try Wire.validate(p) }
    let adjustment = Wire.le(tail, 2)
    guard adjustment <= 25200 else { throw ShiftError.invalid("Invalid cable adjustment") }
    return GearTable(positions: p, adjustment: adjustment, gear: Int(tail[4]))
  }
}

// Same schema and integer cable units as Android: 600 units = 1 millimetre.
struct Profile: Codable {
  var schema = 1
  var name = "SRAM Rival · custom calibration"
  var count = 11
  var teeth: [Int] = []
  var positions: [Int] = []
  var derailleur = 0
  var cassette = 0
  var calibrated = false
  var adjustment: Int? = 0
  var device: String?
  func validate(requireCalibrated: Bool = false) throws {
    guard schema == 1, (2...17).contains(count), (0...4).contains(derailleur),
      (0...8).contains(cassette),
      (0...25200).contains(adjustment ?? 0)
    else { throw ShiftError.invalid("Unsupported profile metadata") }
    if !positions.isEmpty {
      try Wire.validate(positions)
      guard count == positions.count else {
        throw ShiftError.invalid("Profile gear count differs from its positions")
      }
    }
    if !teeth.isEmpty {
      guard teeth.count == count else {
        throw ShiftError.invalid("Provide one tooth count per gear")
      }
      for i in teeth.indices {
        guard (1...200).contains(teeth[i]), i == 0 || teeth[i] > teeth[i - 1] else {
          throw ShiftError.invalid("Sprocket teeth must increase from smallest to largest")
        }
      }
    }
    if requireCalibrated && (!calibrated || positions.count != count) {
      throw ShiftError.invalid("A full, explicitly calibrated table is required")
    }
  }
  static func decode(_ data: Data) throws -> Profile {
    guard data.count <= 65536 else { throw ShiftError.invalid("Profile is too large") }
    // Android omits teeth on a new/custom profile; normalize optional schema-1 metadata.
    guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw ShiftError.invalid("Expected a profile object")
    }
    if object["teeth"] == nil { object["teeth"] = [Int]() }
    let normalized = try JSONSerialization.data(withJSONObject: object)
    let p = try JSONDecoder().decode(Profile.self, from: normalized)
    try p.validate()
    return p
  }
  func encoded() throws -> Data {
    try validate()
    let e = JSONEncoder()
    e.outputFormatting = [.prettyPrinted, .sortedKeys]
    return try e.encode(self)
  }
}
