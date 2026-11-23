import Foundation

/// A real Dhaka neighbourhood read from a .skymap file (MapData.kt). Coordinates are meters from
/// the map center: x east, z south. Map data (c) OpenStreetMap contributors, ODbL.
final class MapData {
  static let UNIT: Float = 0.05
  static let FILES = ["bashundhara", "mohammadpur"]
  static let TITLES = ["BASHUNDHARA R/A", "MOHAMMADPUR"]

  static let APARTMENT = 0, HOUSE = 1, SHED = 2, COMMERCIAL = 3, TOWER = 4, MALL = 5, EDUCATION = 6, HOSPITAL = 7
  static let MOSQUE = 8, INDUSTRIAL = 9, CONSTRUCTION = 10
  static let GRASS = 0, WATER = 1, PITCH = 2, PAVED = 3, GRAVEYARD = 4, BARE = 5
  static let FOOTPATH = 0, CANAL = 1, MINOR_ROAD = 2
  static let SEVEN_DOMES = 0, MALL_SIGN = 1, CAMPUS = 2, HOTEL = 3

  let title: String
  let half: Float

  let buildingCount: Int
  private(set) var buildingKind: [Int] = [], floors: [Int] = [], floorHeight: [Float] = [], tint: [Int] = [], shops: [Bool] = []
  private(set) var boxX: [Float] = [], boxZ: [Float] = [], boxAngle: [Float] = [], boxHalfX: [Float] = [], boxHalfZ: [Float] = []
  private(set) var buildingStart: [Int] = [0], buildingX: [Float] = [], buildingZ: [Float] = []

  let areaCount: Int
  private(set) var areaKind: [Int] = [], areaStart: [Int] = [0], areaX: [Float] = [], areaZ: [Float] = []

  let ribbonCount: Int
  private(set) var ribbonKind: [Int] = [], ribbonWidth: [Float] = [], ribbonStart: [Int] = [0], ribbonX: [Float] = [], ribbonZ: [Float] = []

  let nodeCount: Int
  private(set) var nodeX: [Float] = [], nodeZ: [Float] = [], nodeY: [Float] = [], nodeFlags: [Int] = []

  let edgeCount: Int
  private(set) var edgeA: [Int] = [], edgeB: [Int] = [], edgeClass: [Int] = [], lanesForward: [Int] = [], lanesBackward: [Int] = []
  private(set) var edgeWidth: [Float] = [], edgeBridge: [Bool] = [], edgeTraffic: [Bool] = [], edgeStart: [Int] = [0]
  private(set) var edgeX: [Float] = [], edgeZ: [Float] = [], edgeY: [Float] = []

  let dockCount: Int
  private(set) var dockX: [Float] = [], dockZ: [Float] = []
  let home: Int

  let landmarkCount: Int
  private(set) var landmarkKind: [Int] = [], landmarkX: [Float] = [], landmarkZ: [Float] = [], landmarkBuilding: [Int] = [], landmarkName: [String] = []

  init(_ bytes: [UInt8]) throws {
    var o = 0
    func u8() -> Int { let v = Int(bytes[o]); o += 1; return v }
    func u16() -> Int { let v = Int(bytes[o]) | Int(bytes[o + 1]) << 8; o += 2; return v }
    func s16() -> Int { let v = u16(); return v >= 0x8000 ? v - 0x10000 : v }
    func u32() -> Int { let v = Int(bytes[o]) | Int(bytes[o + 1]) << 8 | Int(bytes[o + 2]) << 16 | Int(bytes[o + 3]) << 24; o += 4; return v }
    func f32() -> Float { let bits = UInt32(bytes[o]) | UInt32(bytes[o + 1]) << 8 | UInt32(bytes[o + 2]) << 16 | UInt32(bytes[o + 3]) << 24; o += 4; return Float(bitPattern: bits) }
    func text(_ length: Int) -> String { let t = String(decoding: bytes[o..<(o + length)], as: UTF8.self); o += length; return t }
    guard bytes.count > 8, String(decoding: bytes[0..<8], as: UTF8.self) == "SKYMAP01" else {
      throw NSError(domain: "SkyCop", code: 2, userInfo: [NSLocalizedDescriptionKey: "Not a SkyCop map"])
    }
    o = 8
    let U = MapData.UNIT
    title = text(u16())
    half = f32()
    _ = f32(); _ = f32()

    buildingCount = u32()
    for _ in 0..<buildingCount {
      buildingKind.append(u8()); floors.append(u8()); floorHeight.append(Float(u8()) / 10); tint.append(u8()); shops.append(u8() != 0)
      boxX.append(Float(s16()) * U); boxZ.append(Float(s16()) * U); boxAngle.append(Float(s16()) / 10000)
      boxHalfX.append(Float(u16()) * U); boxHalfZ.append(Float(u16()) * U)
      let n = u16()
      for _ in 0..<n { buildingX.append(Float(s16()) * U); buildingZ.append(Float(s16()) * U) }
      buildingStart.append(buildingX.count)
    }

    areaCount = u32()
    for _ in 0..<areaCount {
      areaKind.append(u8())
      let n = u16()
      for _ in 0..<n { areaX.append(Float(s16()) * U); areaZ.append(Float(s16()) * U) }
      areaStart.append(areaX.count)
    }

    ribbonCount = u32()
    for _ in 0..<ribbonCount {
      ribbonKind.append(u8()); ribbonWidth.append(Float(u8()) / 10)
      let n = u16()
      for _ in 0..<n { ribbonX.append(Float(s16()) * U); ribbonZ.append(Float(s16()) * U) }
      ribbonStart.append(ribbonX.count)
    }

    nodeCount = u32()
    for _ in 0..<nodeCount {
      nodeX.append(Float(s16()) * U); nodeZ.append(Float(s16()) * U); nodeY.append(Float(s16()) / 10); nodeFlags.append(u8())
    }

    edgeCount = u32()
    for _ in 0..<edgeCount {
      edgeA.append(u16()); edgeB.append(u16()); edgeClass.append(u8()); lanesForward.append(u8()); lanesBackward.append(u8())
      edgeWidth.append(Float(u8()) / 5); edgeBridge.append(u8() != 0); edgeTraffic.append(u8() != 0)
      let n = u16()
      for _ in 0..<n { edgeX.append(Float(s16()) * U); edgeZ.append(Float(s16()) * U); edgeY.append(Float(s16()) / 10) }
      edgeStart.append(edgeX.count)
    }

    dockCount = u32()
    for _ in 0..<dockCount {
      dockX.append(Float(s16()) * U); dockZ.append(Float(s16()) * U); _ = u16()
    }
    home = u16()

    landmarkCount = u32()
    for _ in 0..<landmarkCount {
      landmarkKind.append(u8()); landmarkX.append(Float(s16()) * U); landmarkZ.append(Float(s16()) * U)
      landmarkBuilding.append(u16())
      landmarkName.append(text(u16()))
    }
  }
}
