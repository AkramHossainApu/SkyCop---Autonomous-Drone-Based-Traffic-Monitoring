import Foundation

/// Swift port of City.kt: a real Dhaka neighbourhood built from OpenStreetMap data. The same map,
/// seed and calls in the same order give the same city on both platforms.
final class City {
  static let HALF: Float = 1000
  static let CELL: Float = 100
  static let CELLS = 20
  static let CURB: Float = 0.15
  static let BOUNDS: Float = HALF - 25
  static let DOCK_TOP: Float = 7.2
  static let DOCK_RADIUS: Float = 1.7
  static let ROAD_Y: Float = 0.06
  /// The low wall around a flat roof.
  static let PARAPET: Float = 1.0
  fileprivate static let GRID = 2000
  static let ASPHALT = 0xC9C9C6
  private static let WIRE = 0x23262A

  final class Chunk {
    let mesh = MeshBuilder()
    let detail = MeshBuilder()
    /// Soft darkening decals (contact shadows, grime, worn lanes) multiplied over what is drawn.
    let shade = MeshBuilder()
    /// Pools of lamp light, added over the streets at night: gray 0 is the brightest.
    let glow = MeshBuilder()
    let x: Float, z: Float
    var top: Float = 6
    var reach: Float = City.CELL / 2
    /// Oriented boxes, eight floats each: center x, center z, cos, sin, half x, half z, bottom, top.
    var boxes: [Float] = []
    var blocksView: [Bool] = []
    var count: Int { blocksView.count }

    init(x: Float, z: Float) { self.x = x; self.z = z }

    func add(_ cx: Float, _ cz: Float, _ c: Float, _ s: Float, _ hx: Float, _ hz: Float, _ y0: Float, _ y1: Float, _ opaque: Bool) {
      blocksView.append(opaque)
      boxes.append(contentsOf: [cx, cz, c, s, hx, hz, y0, y1])
      top = max(top, y1 + 3)
    }
  }

  private typealias C = City

  let map: MapData
  let title: String
  let chunks: [Chunk]
  let terrain = MeshBuilder()
  private(set) var tallest: Float = 0
  let dockCount: Int
  private(set) var docks: [Float]
  let homeDock: Int
  private(set) var junctionRadius: [Float]

  private var random = KotlinRandom(seed: 2026)
  /// Spots along the footpaths where a street tree may go, once the buildings are known.
  private var treeSpots: [Float] = []
  /// Footpath center lines for the walkers: x0, z0, x1, z1 for each stretch.
  private(set) var walkways: [Float] = []
  private(set) var walkwayCount = 0
  /// Each stretch as found: x0, z0, x1, z1, outward x, outward z, distance to the footpath's outer edge.
  private var rawWalks: [Float] = []
  private let adColors = [
    0xF6C324, 0xD11F2A, 0x1A1A1A, 0x1E5AA8, 0xF2F2F2, 0xF6C324, 0xE2342D, 0xF2F2F2, 0x1A1A1A,
    0x1F8A4C, 0xF6C324, 0xF2F2F2, 0x6B2C91, 0xF2F2F2, 0xF29A2E, 0xF2F2F2, 0xE2342D, 0x1E5AA8,
  ]
  private let bannerColors = [0xC8202A, 0x1D7A3E, 0xF0B323, 0xF4F4F0, 0x1F4E9A, 0xE8641C]
  private let fruitColors = [0xE8902A, 0xC8302A, 0xE8C838, 0x7CB342]
  /// One meter cells covered by a road, path, canal or junction, so street trees stay off them.
  private var paved = [Bool](repeating: false, count: City.GRID * City.GRID)
  /// Just the carriageways, so a footpath is never laid on or beside another road.
  private var roads = [Bool](repeating: false, count: City.GRID * City.GRID)
  private var point: [Float] = [0, 0, 0, 0]
  private let apartmentTints = [0xF4F1EA, 0xEFE4CF, 0xE9D9BE, 0xDCDCD6, 0xF3E3B5, 0xF0CDB4, 0xCFDDE6, 0xD9E6D1, 0xE8C9C0, 0xC7C2B8]
  private let houseTints = [0xEFE7D6, 0xE6C9A0, 0xD8E4D8, 0xF2DCC4, 0xD7CFC4, 0xF4E9C9]
  private let towerTints = [0xFFFFFF, 0xDCE6EC, 0xC9D8E2, 0xE8E0D4]
  private let signColors = [0xC8202A, 0x1D7A3E, 0xF0B323, 0x1F4E9A, 0xE8641C, 0x8A1F7A, 0x0E8C8C, 0xF4F4F0]
  private let tankColors = [0x1C1E20, 0x1C1E20, 0x2D5D9A, 0xE8E6DF, 0xD9B43A]
  private let leafColors = [0x6F9A4E, 0x83A85A, 0x5E8A47, 0x92AE5F]

  init(_ map: MapData) {
    self.map = map
    title = map.title
    chunks = (0..<(C.CELLS * C.CELLS)).map { Chunk(x: -C.HALF + (Float($0 % C.CELLS) + 0.5) * C.CELL, z: -C.HALF + (Float($0 / C.CELLS) + 0.5) * C.CELL) }
    dockCount = map.dockCount
    docks = [Float](repeating: 0, count: map.dockCount * 3)
    homeDock = max(0, min(map.home, map.dockCount - 1))
    junctionRadius = [Float](repeating: 0, count: map.nodeCount)
    for d in 0..<dockCount {
      docks[d * 3] = map.dockX[d]; docks[d * 3 + 1] = C.DOCK_TOP; docks[d * 3 + 2] = map.dockZ[d]
    }
    for i in 0..<map.areaCount { buildArea(i) }
    for i in 0..<map.ribbonCount { buildRibbon(i) }
    buildRoads()
    roads = []
    for i in 0..<map.buildingCount { buildBuilding(i) }
    finishWalkways()
    placeStalls()
    plantStreetTrees()
    for i in 0..<map.landmarkCount { buildLandmark(i) }
    for d in 0..<dockCount { buildDock(map.dockX[d], map.dockZ[d]) }
    buildTerrain()
    for chunk in chunks { tallest = max(tallest, chunk.top - 3) }
  }

  private func between(_ low: Float, _ high: Float) -> Float { low + random.nextFloat() * (high - low) }

  // MARK: Spatial bookkeeping

  private static func cell(_ v: Float) -> Int { Int(floor((v + HALF) / CELL)).clamped(0, CELLS - 1) }

  func chunkIndex(_ x: Float, _ z: Float) -> Int { C.cell(z) * C.CELLS + C.cell(x) }
  func chunkAt(_ x: Float, _ z: Float) -> Chunk { chunks[chunkIndex(x, z)] }

  private func meshChunk(_ x: Float, _ z: Float, _ extent: Float) -> Chunk {
    let c = chunkAt(x, z)
    c.reach = max(c.reach, max(abs(x - c.x), abs(z - c.z)) + extent)
    return c
  }

  func solid(_ cx: Float, _ cz: Float, _ angle: Float, _ hx: Float, _ hz: Float, _ y0: Float, _ y1: Float, _ opaque: Bool = true) {
    let c = cos(angle), s = sin(angle)
    let ex = abs(c) * hx + abs(s) * hz, ez = abs(s) * hx + abs(c) * hz
    for j in C.cell(cz - ez)...C.cell(cz + ez) {
      for i in C.cell(cx - ex)...C.cell(cx + ex) { chunks[j * C.CELLS + i].add(cx, cz, c, s, hx, hz, y0, y1, opaque) }
    }
  }

  // MARK: Geometry helpers

  private func flat(_ m: MeshBuilder, _ xs: [Float], _ zs: [Float], _ from: Int, _ to: Int, _ y: Float, _ color: Int, _ material: Int) {
    let n = to - from
    if n < 3 { return }
    var signed: Float = 0
    for k in 0..<n {
      let a = from + k, b = from + (k + 1) % n
      signed += xs[a] * zs[b] - xs[b] * zs[a]
    }
    var index = (0..<n).map { from + $0 }
    var left = n
    var guardCount = 0
    var k = 0
    while left > 3 && guardCount < n * n {
      guardCount += 1
      let ia = index[(k + left - 1) % left], ib = index[k % left], ic = index[(k + 1) % left]
      let cross = (xs[ib] - xs[ia]) * (zs[ic] - zs[ia]) - (zs[ib] - zs[ia]) * (xs[ic] - xs[ia])
      var ear = cross * signed > 0
      if ear {
        for t in 0..<left {
          let ip = index[t]
          if ip == ia || ip == ib || ip == ic { continue }
          if inTriangle(xs[ip], zs[ip], xs[ia], zs[ia], xs[ib], zs[ib], xs[ic], zs[ic]) { ear = false; break }
        }
      }
      if ear {
        upTriangle(m, xs[ia], zs[ia], xs[ib], zs[ib], xs[ic], zs[ic], y, color, material)
        var t = k % left
        while t < left - 1 { index[t] = index[t + 1]; t += 1 }
        left -= 1
      } else {
        k = (k + 1) % left
      }
    }
    if left == 3 { upTriangle(m, xs[index[0]], zs[index[0]], xs[index[1]], zs[index[1]], xs[index[2]], zs[index[2]], y, color, material) }
  }

  private func inTriangle(_ px: Float, _ pz: Float, _ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ cx: Float, _ cz: Float) -> Bool {
    let d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz)
    let d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz)
    let d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az)
    let negative = d1 < 0 || d2 < 0 || d3 < 0
    let positive = d1 > 0 || d2 > 0 || d3 > 0
    return !(negative && positive)
  }

  private func upTriangle(_ m: MeshBuilder, _ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ cx: Float, _ cz: Float, _ y: Float, _ color: Int, _ material: Int) {
    if (bz - az) * (cx - ax) - (bx - ax) * (cz - az) >= 0 { m.triangle(ax, y, az, bx, y, bz, cx, y, cz, color, material) }
    else { m.triangle(ax, y, az, cx, y, cz, bx, y, bz, color, material) }
  }

  private func wall(_ m: MeshBuilder, _ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ y0: Float, _ y1: Float, _ insideX: Float, _ insideZ: Float, _ color: Int, _ material: Int) {
    let out = -(bz - az) * ((ax + bx) / 2 - insideX) + (bx - ax) * ((az + bz) / 2 - insideZ)
    if out >= 0 { m.quad(ax, y0, az, bx, y0, bz, bx, y1, bz, ax, y1, az, color, material) }
    else { m.quad(bx, y0, bz, ax, y0, az, ax, y1, az, bx, y1, bz, color, material) }
  }

  private func strip(_ m: MeshBuilder, _ xs: [Float], _ zs: [Float], _ ys: [Float]?, _ from: Int, _ to: Int, _ width: Float, _ lift: Float, _ color: Int, _ material: Int) {
    let h = width / 2
    if to - from < 2 { return }
    for k in from..<(to - 1) {
      let dx = xs[k + 1] - xs[k], dz = zs[k + 1] - zs[k]
      let length = hypot(dx, dz)
      if length < 0.01 { continue }
      let px = -dz / length * h, pz = dx / length * h
      let y0 = (ys?[k] ?? 0) + lift, y1 = (ys?[k + 1] ?? 0) + lift
      let ax = xs[k], az = zs[k], bx = xs[k + 1], bz = zs[k + 1]
      m.quad(ax + px, y0, az + pz, bx + px, y1, bz + pz, bx - px, y1, bz - pz, ax - px, y0, az - pz, color, material)
      if k > from { disc(m, ax, y0, az, h, 8, color, material) }
    }
  }

  private func disc(_ m: MeshBuilder, _ x: Float, _ y: Float, _ z: Float, _ r: Float, _ sides: Int, _ color: Int, _ material: Int) {
    for i in 0..<sides {
      let a = Float(i) * 2 * PI_F / Float(sides), b = Float(i + 1) * 2 * PI_F / Float(sides)
      upTriangle(m, x, z, x + cos(a) * r, z + sin(a) * r, x + cos(b) * r, z + sin(b) * r, y, color, material)
    }
  }

  private func upQuad(_ m: MeshBuilder, _ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ cx: Float, _ cz: Float, _ dx: Float, _ dz: Float, _ y: Float, _ color: Int, _ material: Int) {
    upTriangle(m, ax, az, bx, bz, cx, cz, y, color, material)
    upTriangle(m, ax, az, cx, cz, dx, dz, y, color, material)
  }

  // MARK: Ground cover

  private func buildArea(_ i: Int) {
    let from = map.areaStart[i], to = map.areaStart[i + 1]
    var cx: Float = 0, cz: Float = 0
    for k in from..<to { cx += map.areaX[k]; cz += map.areaZ[k] }
    cx /= Float(to - from); cz /= Float(to - from)
    var extent: Float = 0
    for k in from..<to { extent = max(extent, max(abs(map.areaX[k] - cx), abs(map.areaZ[k] - cz))) }
    let c = meshChunk(cx, cz, extent)
    switch map.areaKind[i] {
    case MapData.GRASS:
      flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03, 0xFFFFFF, Mat.of(.grass))
      scatterTrees(from, to, cx, cz, extent, 0.0016)
    case MapData.PITCH: flat(c.mesh, map.areaX, map.areaZ, from, to, 0.035, 0xE4F0D0, Mat.of(.grass))
    case MapData.WATER: flat(c.mesh, map.areaX, map.areaZ, from, to, 0.025, 0x3F6E78, Mat.WATER)
    case MapData.PAVED: flat(c.mesh, map.areaX, map.areaZ, from, to, 0.04, 0xE2DED4, Mat.of(.paving))
    case MapData.GRAVEYARD:
      flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03, 0xB9C4A0, Mat.of(.grass))
      scatterTrees(from, to, cx, cz, extent, 0.0025)
    default: flat(c.mesh, map.areaX, map.areaZ, from, to, 0.03, 0xC9B79A, Mat.of(.concrete))
    }
  }

  private func inside(_ xs: [Float], _ zs: [Float], _ from: Int, _ to: Int, _ x: Float, _ z: Float) -> Bool {
    var hit = false
    var j = to - 1
    for i in from..<to {
      if (zs[i] > z) != (zs[j] > z) && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i] { hit.toggle() }
      j = i
    }
    return hit
  }

  private func scatterTrees(_ from: Int, _ to: Int, _ cx: Float, _ cz: Float, _ extent: Float, _ density: Float) {
    let tries = min(60, Int((2 * extent) * (2 * extent) * density))
    if tries <= 0 { return }
    for _ in 0..<tries {
      let x = cx + between(-extent, extent), z = cz + between(-extent, extent)
      let s = between(0.8, 1.4)
      if inside(map.areaX, map.areaZ, from, to, x, z) { tree(x, z, s) }
    }
  }

  private func tree(_ x: Float, _ z: Float, _ s: Float) {
    let m = meshChunk(x, z, 3).detail
    let color = leafColors[random.nextInt(leafColors.count)]
    m.cylinder(x, 0, z, 0.24 * s, 2.8 * s, 0.14 * s, 5, 0xFFFFFF, Mat.of(.bark), cap: false)
    let leaves = Mat.of(.leaves)
    m.ellipsoid(x, 3.4 * s, z, 2.1 * s, 1.5 * s, 2 * s, 6, 4, color, leaves, bumps: 0.25)
    m.ellipsoid(x + 0.4 * s, 4.5 * s, z - 0.3 * s, 1.3 * s, 1.1 * s, 1.3 * s, 5, 3, color, leaves, bumps: 0.3)
    m.ellipsoid(x - 0.9 * s, 3.9 * s, z + 0.7 * s, 1.15 * s, 0.95 * s, 1.1 * s, 5, 3, color, leaves, bumps: 0.35)
    solid(x, z, 0, 1.5 * s, 1.5 * s, 2.1 * s, 5.4 * s, false)
    solid(x, z, 0, 0.22, 0.22, 0, 2.1 * s, false)
  }

  private func buildRibbon(_ i: Int) {
    let from = map.ribbonStart[i], to = map.ribbonStart[i + 1]
    if to - from < 2 { return }
    let w = map.ribbonWidth[i]
    for k in from..<(to - 1) { pave(map.ribbonX[k], map.ribbonZ[k], map.ribbonX[k + 1], map.ribbonZ[k + 1], w / 2 + 1.2) }
    if map.ribbonKind[i] == MapData.MINOR_ROAD {
      for k in from..<(to - 1) { pave(map.ribbonX[k], map.ribbonZ[k], map.ribbonX[k + 1], map.ribbonZ[k + 1], w / 2 + 0.2, roadsOnly: true) }
    }
    let c = meshChunk(map.ribbonX[from], map.ribbonZ[from], w)
    for k in from..<to { c.reach = max(c.reach, max(abs(map.ribbonX[k] - c.x), abs(map.ribbonZ[k] - c.z)) + w) }
    switch map.ribbonKind[i] {
    case MapData.CANAL:
      strip(c.mesh, map.ribbonX, map.ribbonZ, nil, from, to, w + 2.4, 0.028, 0xB8B2A6, Mat.of(.concrete))
      strip(c.mesh, map.ribbonX, map.ribbonZ, nil, from, to, w, 0.034, 0x46705F, Mat.WATER)
    case MapData.MINOR_ROAD: strip(c.mesh, map.ribbonX, map.ribbonZ, nil, from, to, w, C.ROAD_Y, C.ASPHALT, Mat.of(.asphalt))
    default: strip(c.mesh, map.ribbonX, map.ribbonZ, nil, from, to, w, 0.045, 0xE0DCD2, Mat.of(.paving))
    }
  }

  // MARK: Roads, junctions, signals, wires

  private func buildRoads() {
    var degree = [Int](repeating: 0, count: map.nodeCount)
    var widest = [Float](repeating: 0, count: map.nodeCount)
    for e in 0..<map.edgeCount {
      let a = map.edgeA[e], b = map.edgeB[e]
      degree[a] += 1; degree[b] += 1
      let h = map.edgeWidth[e] / 2
      widest[a] = max(widest[a], h); widest[b] = max(widest[b], h)
    }
    for n in 0..<map.nodeCount { junctionRadius[n] = degree[n] >= 3 ? widest[n] + 1.2 : 0 }
    // Every carriageway first (see City.kt): the strip between a divided road's halves is a median.
    for e in 0..<map.edgeCount {
      let from = map.edgeStart[e], to = map.edgeStart[e + 1]
      if to - from < 2 { continue }
      for k in from..<(to - 1) { pave(map.edgeX[k], map.edgeZ[k], map.edgeX[k + 1], map.edgeZ[k + 1], map.edgeWidth[e] / 2 + 0.2, roadsOnly: true) }
    }
    for n in 0..<map.nodeCount where degree[n] >= 2 { pave(map.nodeX[n], map.nodeZ[n], map.nodeX[n], map.nodeZ[n], widest[n] + 0.2, roadsOnly: true) }

    for e in 0..<map.edgeCount {
      let from = map.edgeStart[e], to = map.edgeStart[e + 1]
      if to - from < 2 { continue }
      let width = map.edgeWidth[e]
      let c = meshChunk(map.edgeX[from], map.edgeZ[from], width)
      for k in from..<to { c.reach = max(c.reach, max(abs(map.edgeX[k] - c.x), abs(map.edgeZ[k] - c.z)) + width) }
      var elevated = false
      for k in from..<to where map.edgeY[k] > 0.5 { elevated = true }
      let footpath: Float = map.edgeClass[e] <= 4 && !elevated ? (map.edgeClass[e] <= 3 ? 2.6 : 1.9) : 0.3
      for k in from..<(to - 1) { pave(map.edgeX[k], map.edgeZ[k], map.edgeX[k + 1], map.edgeZ[k + 1], width / 2 + footpath) }
      strip(c.mesh, map.edgeX, map.edgeZ, map.edgeY, from, to, width, C.ROAD_Y, C.ASPHALT, Mat.of(.asphalt))
      roadGrime(e, c)
      if elevated { bridge(e, c) }
      markings(e, c)
      if map.edgeClass[e] <= 4 && !elevated { sidewalks(e, c) }
      if map.edgeClass[e] >= 3 && !elevated { wires(e) }
    }
    for n in 0..<map.nodeCount {
      if degree[n] >= 2 { pave(map.nodeX[n], map.nodeZ[n], map.nodeX[n], map.nodeZ[n], widest[n] + 2.6) }
      if degree[n] >= 2 {
        disc(meshChunk(map.nodeX[n], map.nodeZ[n], widest[n]).mesh, map.nodeX[n], map.nodeY[n] + C.ROAD_Y, map.nodeZ[n], widest[n], 12, C.ASPHALT, Mat.of(.asphalt))
      }
      if map.nodeFlags[n] & 1 != 0 && degree[n] >= 3 && map.nodeY[n] < 0.5 { signals(n) }
    }
  }

  /// Point and unit direction at distance `s` along edge `e`, into `out`: x, z, dx, dz.
  func edgePoint(_ e: Int, _ s: Float, _ out: inout [Float]) {
    let from = map.edgeStart[e], to = map.edgeStart[e + 1]
    var left = s
    for k in from..<(to - 1) {
      let dx = map.edgeX[k + 1] - map.edgeX[k], dz = map.edgeZ[k + 1] - map.edgeZ[k]
      let length = hypot(dx, dz)
      if left <= length || k == to - 2 {
        let t: Float = length > 0 ? (left / length).clamped(0, 1) : 0
        out[0] = map.edgeX[k] + dx * t; out[1] = map.edgeZ[k] + dz * t
        out[2] = length > 0 ? dx / length : 1; out[3] = length > 0 ? dz / length : 0
        return
      }
      left -= length
    }
    out[0] = map.edgeX[from]; out[1] = map.edgeZ[from]; out[2] = 1; out[3] = 0
  }

  func edgeLength(_ e: Int) -> Float {
    var total: Float = 0
    let from = map.edgeStart[e], to = map.edgeStart[e + 1]
    if to - from < 2 { return 0 }
    for k in from..<(to - 1) { total += hypot(map.edgeX[k + 1] - map.edgeX[k], map.edgeZ[k + 1] - map.edgeZ[k]) }
    return total
  }

  private func edgeHeight(_ e: Int, _ s: Float) -> Float {
    let from = map.edgeStart[e], to = map.edgeStart[e + 1]
    var left = s
    for k in from..<(to - 1) {
      let length = hypot(map.edgeX[k + 1] - map.edgeX[k], map.edgeZ[k + 1] - map.edgeZ[k])
      if left <= length || k == to - 2 {
        let t: Float = length > 0 ? (left / length).clamped(0, 1) : 0
        return map.edgeY[k] + (map.edgeY[k + 1] - map.edgeY[k]) * t
      }
      left -= length
    }
    return map.edgeY[from]
  }

  private func dashes(_ e: Int, _ m: MeshBuilder, _ start: Float, _ end: Float, _ offset: Float, _ dash: Float, _ gap: Float, _ color: Int, _ half: Float) {
    var s = start
    while s < end {
      let s2 = min(end, s + dash)
      edgePoint(e, s, &point)
      let ax = point[0] + point[3] * offset, az = point[1] - point[2] * offset
      let y = edgeHeight(e, s) + C.ROAD_Y + 0.025
      edgePoint(e, s2, &point)
      let bx = point[0] + point[3] * offset, bz = point[1] - point[2] * offset
      let dx = bx - ax, dz = bz - az, l = hypot(dx, dz)
      if l > 0.05 {
        let px = -dz / l * half, pz = dx / l * half
        m.quad(ax + px, y, az + pz, bx + px, y, bz + pz, bx - px, y, bz - pz, ax - px, y, az - pz, color)
      }
      s += dash + gap
    }
  }

  private func markings(_ e: Int, _ c: Chunk) {
    let cls = map.edgeClass[e]
    let length = edgeLength(e)
    let start = junctionRadius[map.edgeA[e]] + 1, end = length - junctionRadius[map.edgeB[e]] - 1
    if end - start < 4 { return }
    let forward = map.lanesForward[e], backward = map.lanesBackward[e]
    let laneWidth = map.edgeWidth[e] / Float(forward + backward)
    let white = 0xE8E8E2, yellow = 0xE0B23A
    if backward > 0 {
      if cls <= 3 { dashes(e, c.detail, start, end, 0, 6, 0, yellow, 0.07) }
      else if cls <= 6 { dashes(e, c.detail, start, end, 0, 3, 5, white, 0.06) }
      if forward > 1 { for k in 1..<forward { dashes(e, c.detail, start, end, Float(k) * laneWidth, 3, 6, white, 0.06) } }
      if backward > 1 { for k in 1..<backward { dashes(e, c.detail, start, end, -Float(k) * laneWidth, 3, 6, white, 0.06) } }
    } else if forward > 1 {
      for k in 1..<forward { dashes(e, c.detail, start, end, map.edgeWidth[e] / 2 - Float(k) * laneWidth, 3, 6, white, 0.06) }
    }
    if cls <= 2 && backward > 0 && end - start > 20 && map.edgeY[map.edgeStart[e]] < 0.5 { median(e, c, start, end) }
  }

  /// A soft darkening band along edge `e`, from lateral offset `o0` (gray `k0`) to `o1` (gray `k1`).
  private func shadeBand(_ e: Int, _ m: MeshBuilder, _ o0: Float, _ o1: Float, _ k0: Float, _ k1: Float, _ lift: Float) {
    let from = map.edgeStart[e], to = map.edgeStart[e + 1]
    for k in from..<(to - 1) {
      let ax = map.edgeX[k], az = map.edgeZ[k], bx = map.edgeX[k + 1], bz = map.edgeZ[k + 1]
      let l = hypot(bx - ax, bz - az)
      if l < 0.01 { continue }
      let px = -(bz - az) / l, pz = (bx - ax) / l
      let ya = map.edgeY[k] + lift, yb = map.edgeY[k + 1] + lift
      m.shadeQuad(ax + px * o0, ya, az + pz * o0, bx + px * o0, yb, bz + pz * o0,
                  bx + px * o1, yb, bz + pz * o1, ax + px * o1, ya, az + pz * o1, k0, k0, k1, k1)
    }
  }

  /// Grime in the gutters along both edges and the oil-darkened middle of every lane.
  private func roadGrime(_ e: Int, _ c: Chunk) {
    let half = map.edgeWidth[e] / 2
    let lift = C.ROAD_Y + 0.008
    shadeBand(e, c.shade, -half, -half + 0.9, 0.68, 1, lift)
    shadeBand(e, c.shade, half, half - 0.9, 0.68, 1, lift)
    if map.edgeClass[e] > 5 { return }
    let lanes = max(1, map.lanesForward[e] + map.lanesBackward[e])
    let laneWidth = map.edgeWidth[e] / Float(lanes)
    for i in 0..<lanes {
      let middle = -half + (Float(i) + 0.5) * laneWidth
      shadeBand(e, c.shade, middle - 0.75, middle, 1, 0.82, lift)
      shadeBand(e, c.shade, middle, middle + 0.75, 0.82, 1, lift)
    }
  }

  /// Marks the grid cells whose centers lie within `half` of the segment from a to b.
  private func pave(_ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ half: Float, roadsOnly: Bool = false) {
    if (roadsOnly ? roads : paved).isEmpty { return }
    let G = City.GRID
    let i0 = min(max(Int(floor(min(ax, bx) - half + City.HALF)), 0), G - 1)
    let i1 = min(max(Int(floor(max(ax, bx) + half + City.HALF)), 0), G - 1)
    let j0 = min(max(Int(floor(min(az, bz) - half + City.HALF)), 0), G - 1)
    let j1 = min(max(Int(floor(max(az, bz) + half + City.HALF)), 0), G - 1)
    let dx = bx - ax, dz = bz - az
    let length2 = dx * dx + dz * dz
    for j in j0...j1 {
      for i in i0...i1 {
        let px = -City.HALF + Float(i) + 0.5, pz = -City.HALF + Float(j) + 0.5
        let t = min(max(length2 > 0 ? ((px - ax) * dx + (pz - az) * dz) / length2 : 0, 0), 1)
        let ex = ax + dx * t - px, ez = az + dz * t - pz
        if ex * ex + ez * ez <= half * half { if roadsOnly { roads[j * G + i] = true } else { paved[j * G + i] = true } }
      }
    }
  }

  private func isPaved(_ x: Float, _ z: Float, roadsOnly: Bool = false) -> Bool {
    if (roadsOnly ? roads : paved).isEmpty { return false }
    let i = Int(floor(x + City.HALF)), j = Int(floor(z + City.HALF))
    if i < 0 || j < 0 || i >= City.GRID || j >= City.GRID { return true }
    return roadsOnly ? roads[j * City.GRID + i] : paved[j * City.GRID + i]
  }

  /// Street trees along the footpaths, wherever no road, building, pole or dock is in the way.
  private func plantStreetTrees() {
    for k in 0..<(treeSpots.count / 2) {
      let x = treeSpots[k * 2], z = treeSpots[k * 2 + 1]
      if isPaved(x, z) || isPaved(x - 0.7, z - 0.7) || isPaved(x + 0.7, z - 0.7) ||
        isPaved(x - 0.7, z + 0.7) || isPaved(x + 0.7, z + 0.7) { continue }
      if support(x, z, 1000, 2.2) > 0 { continue }
      var nearDock = false
      for d in 0..<dockCount where abs(docks[d * 3] - x) < 6 && abs(docks[d * 3 + 2] - z) < 6 { nearDock = true }
      if !nearDock { tree(x, z, 0.75 + 0.45 * spotHash(x, z, 3)) }
    }
    paved = []
  }

  private func spotHash(_ x: Float, _ z: Float, _ seed: Int) -> Float {
    var h = Int32(truncatingIfNeeded: javaRound(x * 10)) &* 374761393 &+ Int32(truncatingIfNeeded: javaRound(z * 10)) &* 668265263
      &+ Int32(truncatingIfNeeded: seed) &* 1442695041
    h = (h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 13)) &* 1274126177
    return Float((h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 16)) & 0xffff) / 65536
  }

  private func median(_ e: Int, _ c: Chunk, _ start: Float, _ end: Float) {
    var s = start + 4
    while s < end - 4 {
      let s2 = min(end - 4, s + 12)
      edgePoint(e, s, &point); let ax = point[0], az = point[1]
      edgePoint(e, s2, &point); let bx = point[0], bz = point[1]
      let dx = bx - ax, dz = bz - az, l = hypot(dx, dz)
      if l > 0.5 {
        let px = -dz / l * 0.45, pz = dx / l * 0.45
        let mx = (ax + bx) / 2, mz = (az + bz) / 2
        // Dhaka's medians: kerbs painted yellow and black around a strip of soil and shrubs.
        let h: Float = 0.35
        let kerb = Mat.STRIPED, yellow = 0xE8B52A
        wall(c.mesh, ax + px, az + pz, bx + px, bz + pz, 0, h, mx, mz, yellow, kerb)
        wall(c.mesh, bx - px, bz - pz, ax - px, az - pz, 0, h, mx, mz, yellow, kerb)
        wall(c.mesh, ax - px, az - pz, ax + px, az + pz, 0, h, mx, mz, yellow, kerb)
        wall(c.mesh, bx + px, bz + pz, bx - px, bz - pz, 0, h, mx, mz, yellow, kerb)
        upQuad(c.mesh, ax + px, az + pz, bx + px, bz + pz, bx - px, bz - pz, ax - px, az - pz, h, 0x8C7A5C, Mat.of(.grass))
        var t: Float = 1.5
        while t < l - 1 {
          let sx = ax + dx / l * t, sz = az + dz / l * t
          let leaf = leafColors[(Int(t * 3) + e) % leafColors.count]
          c.detail.ellipsoid(sx, h + 0.3, sz, 0.42, 0.38, 0.42, 5, 3, leaf, Mat.of(.leaves), bumps: 0.25)
          t += 3.5
        }
        solid(mx, mz, atan2(dz, dx), l / 2, 0.45, 0, h)
      }
      s = s2 + 1
    }
  }

  private func sidewalks(_ e: Int, _ c: Chunk) {
    let length = edgeLength(e)
    let start = junctionRadius[map.edgeA[e]] + 0.5, end = length - junctionRadius[map.edgeB[e]] - 0.5
    if end - start < 3 { return }
    let h = map.edgeWidth[e] / 2
    let main = map.edgeClass[e] <= 3
    let walk: Float = main ? 2.6 : 1.9
    // Kerb stones: black and yellow on the main roads, black and white elsewhere.
    let kerbColor = main ? 0xE8B52A : 0xE6E6E0
    let kerb: Float = 0.24
    for side: Float in [-1, 1] {
      var s = start
      while s < end {
        let s2 = min(end, s + 8)
        edgePoint(e, (s + s2) / 2, &point)
        let median = isPaved(point[0] + point[3] * side * (h + walk * 0.5), point[1] - point[2] * side * (h + walk * 0.5), roadsOnly: true) ||
          isPaved(point[0] + point[3] * side * (h + walk + 1), point[1] - point[2] * side * (h + walk + 1), roadsOnly: true)
        edgePoint(e, s, &point)
        let ix = point[0] + point[3] * side * h, iz = point[1] - point[2] * side * h
        let kx = point[0] + point[3] * side * (h + kerb), kz = point[1] - point[2] * side * (h + kerb)
        let ox = point[0] + point[3] * side * (h + walk), oz = point[1] - point[2] * side * (h + walk)
        edgePoint(e, s2, &point)
        let jx = point[0] + point[3] * side * h, jz = point[1] - point[2] * side * h
        let lx = point[0] + point[3] * side * (h + kerb), lz = point[1] - point[2] * side * (h + kerb)
        let px = point[0] + point[3] * side * (h + walk), pz = point[1] - point[2] * side * (h + walk)
        let top = C.CURB + 0.03
        upQuad(c.mesh, ix, iz, jx, jz, lx, lz, kx, kz, top, kerbColor, Mat.STRIPED)
        wall(c.mesh, ix, iz, jx, jz, 0, top, ox, oz, kerbColor, Mat.STRIPED)
        if median {
          wall(c.mesh, kx, kz, lx, lz, 0, top, ix, iz, kerbColor, Mat.STRIPED)
          s = s2
          continue
        }
        upQuad(c.mesh, kx, kz, lx, lz, px, pz, ox, oz, C.CURB, 0xDCD7CC, Mat.of(.paving))
        wall(c.detail, kx, kz, lx, lz, C.CURB, top, ix, iz, 0xC9C4B8, Mat.of(.concrete))
        wall(c.mesh, ox, oz, px, pz, 0, C.CURB, ix, iz, 0xBDB7AA, Mat.of(.concrete))
        let gx = (ox - ix) / walk * 0.9, gz = (oz - iz) / walk * 0.9
        c.shade.shadeQuad(ox, 0.05, oz, px, 0.05, pz, px + gx, 0.05, pz + gz, ox + gx, 0.05, oz + gz, 0.62, 0.62, 1, 1)
        let middle = h + kerb + (walk - kerb) / 2
        edgePoint(e, s, &point)
        let wx0 = point[0] + point[3] * side * middle, wz0 = point[1] - point[2] * side * middle
        let outX = point[3] * side, outZ = -point[2] * side
        edgePoint(e, s2, &point)
        let wx1 = point[0] + point[3] * side * middle, wz1 = point[1] - point[2] * side * middle
        rawWalks += [wx0, wz0, wx1, wz1, outX, outZ, h + walk - middle]
        s = s2
      }
      if map.edgeClass[e] <= 6 {
        var t = start + 6
        while t < end - 6 {
          edgePoint(e, t, &point)
          let x = point[0] + point[3] * side * (h + walk + 1.8), z = point[1] - point[2] * side * (h + walk + 1.8)
          if spotHash(x, z, 1) < 0.5 { treeSpots.append(x); treeSpots.append(z) }
          t += 16
        }
      }
    }
  }

  /// Keeps the stretches of footpath that do not run into a building.
  private func finishWalkways() {
    var out: [Float] = []
    for k in 0..<(rawWalks.count / 7) {
      let i = k * 7
      let x0 = rawWalks[i], z0 = rawWalks[i + 1], x1 = rawWalks[i + 2], z1 = rawWalks[i + 3]
      if inBuilding(x0, z0) || inBuilding(x1, z1) || inBuilding((x0 + x1) / 2, (z0 + z1) / 2) { continue }
      out += [x0, z0, x1, z1]
    }
    walkways = out
    walkwayCount = out.count / 4
  }

  private func inBuilding(_ x: Float, _ z: Float) -> Bool {
    let c = chunkAt(x, z), b = c.boxes
    for k in 0..<c.count {
      let i = k * 8
      if !c.blocksView[k] || b[i + 6] > 0.5 || b[i + 7] < 2.5 { continue }
      let dx = x - b[i], dz = z - b[i + 1]
      let u = dx * b[i + 2] + dz * b[i + 3], v = -dx * b[i + 3] + dz * b[i + 2]
      if abs(u) < b[i + 4] + 0.3 && abs(v) < b[i + 5] + 0.3 { return true }
    }
    return false
  }

  /// Tea stalls and fruit carts just off the footpath, facing the street.
  private func placeStalls() {
    for k in 0..<(rawWalks.count / 7) {
      let i = k * 7
      let mx = (rawWalks[i] + rawWalks[i + 2]) / 2, mz = (rawWalks[i + 1] + rawWalks[i + 3]) / 2
      if spotHash(mx, mz, 7) > 0.07 { continue }
      let ux = rawWalks[i + 4], uz = rawWalks[i + 5]
      let x = mx + ux * (rawWalks[i + 6] + 1.45), z = mz + uz * (rawWalks[i + 6] + 1.45)
      if isPaved(x, z) || isPaved(x + ux, z + uz) || isPaved(x - uz, z + ux) || isPaved(x + uz, z - ux) { continue }
      if support(x, z, 1000, 1.7) > 0 { continue }
      var nearDock = false
      for d in 0..<dockCount where abs(docks[d * 3] - x) < 7 && abs(docks[d * 3 + 2] - z) < 7 { nearDock = true }
      if nearDock { continue }
      if spotHash(x, z, 9) < 0.55 { teaStall(x, z, -ux, -uz) } else { fruitCart(x, z, -ux, -uz) }
      solid(x, z, 0, 1.3, 1.3, 0, 2.5, false)
    }
  }

  /// A box turned to face (fx, fz): u runs across, v toward the front.
  private func obox(_ m: MeshBuilder, _ cx: Float, _ cz: Float, _ fx: Float, _ fz: Float, _ u0: Float, _ u1: Float, _ v0: Float, _ v1: Float, _ y0: Float, _ y1: Float, _ color: Int, _ material: Int) {
    let rx = -fz, rz = fx
    let ax = cx + rx * u0 + fx * v0, az = cz + rz * u0 + fz * v0
    let bx = cx + rx * u1 + fx * v0, bz = cz + rz * u1 + fz * v0
    let qx = cx + rx * u1 + fx * v1, qz = cz + rz * u1 + fz * v1
    let dx = cx + rx * u0 + fx * v1, dz = cz + rz * u0 + fz * v1
    let mx = (ax + qx) / 2, mz = (az + qz) / 2
    wall(m, ax, az, bx, bz, y0, y1, mx, mz, color, material)
    wall(m, bx, bz, qx, qz, y0, y1, mx, mz, color, material)
    wall(m, qx, qz, dx, dz, y0, y1, mx, mz, color, material)
    wall(m, dx, dz, ax, az, y0, y1, mx, mz, color, material)
    upQuad(m, ax, az, bx, bz, qx, qz, dx, dz, y1, color, material)
  }

  private func sitter(_ m: MeshBuilder, _ x: Float, _ z: Float, _ seat: Float, _ shirt: Int) {
    m.box(x - 0.17, seat, z - 0.11, x + 0.17, seat + 0.52, z + 0.11, shirt)
    m.box(x - 0.16, seat - 0.02, z - 0.3, x + 0.16, seat + 0.12, z + 0.1, 0x2B2F3A)
    m.ellipsoid(x, seat + 0.66, z, 0.11, 0.13, 0.12, 6, 4, 0x8C5A3C)
    m.ellipsoid(x, seat + 0.72, z + 0.02, 0.115, 0.08, 0.12, 6, 3, 0x17181A)
  }

  private func stander(_ m: MeshBuilder, _ x: Float, _ z: Float, _ shirt: Int) {
    m.box(x - 0.15, 0, z - 0.09, x + 0.15, 0.85, z + 0.09, 0x2B2F3A)
    m.box(x - 0.19, 0.85, z - 0.12, x + 0.19, 1.45, z + 0.12, shirt)
    m.ellipsoid(x, 1.6, z, 0.11, 0.13, 0.12, 6, 4, 0x8C5A3C)
    m.ellipsoid(x, 1.66, z + 0.02, 0.115, 0.08, 0.12, 6, 3, 0x17181A)
  }

  /// A cha stall: counter, shelf of jars, kettle on the stove, blue tarp, bench and customers.
  private func teaStall(_ cx: Float, _ cz: Float, _ fx: Float, _ fz: Float) {
    let m = meshChunk(cx, cz, 4).detail
    let rx = -fz, rz = fx
    func px(_ u: Float, _ v: Float) -> Float { cx + rx * u + fx * v }
    func pz(_ u: Float, _ v: Float) -> Float { cz + rz * u + fz * v }
    let wood = 0x7A5230
    obox(m, cx, cz, fx, fz, -0.9, 0.9, -0.35, 0.35, 0, 0.95, wood, Mat.PLAIN)
    obox(m, cx, cz, fx, fz, -0.95, 0.95, -0.4, 0.4, 0.95, 1, 0x5A3A22, Mat.PLAIN)
    obox(m, cx, cz, fx, fz, -0.9, 0.9, 0.4, 0.6, 0, 1.9, 0x6A4628, Mat.PLAIN)
    for k in 0...3 { m.cylinder(px(-0.6 + Float(k) * 0.4, 0.15), 1, pz(-0.6 + Float(k) * 0.4, 0.15), 0.08, 1.28, 0.07, 6, 0xCFE3D8, Mat.GLASS) }
    let sx = px(0.55, -0.15), sz = pz(0.55, -0.15)
    m.box(sx - 0.15, 1, sz - 0.15, sx + 0.15, 1.12, sz + 0.15, 0x2A2C2E)
    m.cylinder(sx, 1.12, sz, 0.13, 1.36, 0.09, 8, 0xA9B0B4, Mat.GLOSSY)
    for u: Float in [-1.15, 1.15] {
      for v: Float in [-1, 0.65] {
        let x = px(u, v), z = pz(u, v)
        m.box(x - 0.04, 0, z - 0.04, x + 0.04, v < 0 ? 2.1 : 2.38, z + 0.04, 0x4A4E52)
      }
    }
    let ax = px(-1.3, -1.15), az = pz(-1.3, -1.15), bx = px(1.3, -1.15), bz = pz(1.3, -1.15)
    let qx = px(1.3, 0.8), qz = pz(1.3, 0.8), dx = px(-1.3, 0.8), dz = pz(-1.3, 0.8)
    m.quad(ax, 2.1, az, bx, 2.1, bz, qx, 2.4, qz, dx, 2.4, dz, 0x2D6EB0)
    m.quad(ax, 2.09, az, dx, 2.39, dz, qx, 2.39, qz, bx, 2.09, bz, 0x245A92)
    wall(m, px(-0.8, -1.17), pz(-0.8, -1.17), px(0.8, -1.17), pz(0.8, -1.17), 2.1, 2.4, cx, cz, 0xC8202A, Mat.PLAIN)
    wall(m, px(-0.6, -1.18), pz(-0.6, -1.18), px(0.6, -1.18), pz(0.6, -1.18), 2.19, 2.31, cx, cz, 0xF4F2EA, Mat.PLAIN)
    m.ellipsoid(px(-1, -1), 1.75, pz(-1, -1), 0.14, 0.22, 0.14, 6, 3, 0xE8C838)
    m.ellipsoid(px(1, -1), 1.75, pz(1, -1), 0.14, 0.22, 0.14, 6, 3, 0xD8B830)
    obox(m, cx, cz, fx, fz, -0.85, 0.85, -1.15, -0.85, 0.4, 0.47, wood, Mat.PLAIN)
    sitter(m, px(-0.4, -1), pz(-0.4, -1), 0.47, 0xF2F0E8)
    sitter(m, px(0.35, -1), pz(0.35, -1), 0.47, 0x2D5DA8)
    sitter(m, px(-0.3, 0), pz(-0.3, 0), 1, 0xE8E2D2)
  }

  /// A fruit seller's cart under a big umbrella, piled with oranges, apples, bananas and guavas.
  private func fruitCart(_ cx: Float, _ cz: Float, _ fx: Float, _ fz: Float) {
    let m = meshChunk(cx, cz, 4).detail
    let rx = -fz, rz = fx
    func px(_ u: Float, _ v: Float) -> Float { cx + rx * u + fx * v }
    func pz(_ u: Float, _ v: Float) -> Float { cz + rz * u + fz * v }
    obox(m, cx, cz, fx, fz, -0.9, 0.9, -0.55, 0.55, 0.7, 0.8, 0x6A4628, Mat.PLAIN)
    for u: Float in [-0.8, 0.8] {
      for v: Float in [-0.45, 0.45] {
        let x = px(u, v), z = pz(u, v)
        m.box(x - 0.04, 0, z - 0.04, x + 0.04, 0.7, z + 0.04, 0x3A2A1A)
      }
    }
    let first = Int(spotHash(cx, cz, 15) * 4)
    for k in 0...5 {
      let u = -0.6 + Float(k % 3) * 0.6, v: Float = k < 3 ? -0.25 : 0.25
      m.ellipsoid(px(u, v), 0.9, pz(u, v), 0.27, 0.14, 0.22, 7, 3, fruitColors[(k + first) % 4])
    }
    let ux = px(0, 0), uz = pz(0, 0)
    m.box(ux - 0.025, 0.8, uz - 0.025, ux + 0.025, 2.5, uz + 0.025, 0x5A5E60)
    m.cylinder(ux, 2.1, uz, 1.35, 2.55, 0.05, 10, 0xD84A3A, cap: false)
    stander(m, px(0.2, 0.9), pz(0.2, 0.9), 0xF2F0E8)
  }

  /// A cloth banner strung across the street from the pole at (x, z) to (x2, z2).
  private func banner(_ m: MeshBuilder, _ x: Float, _ z: Float, _ x2: Float, _ z2: Float, _ dx: Float, _ dz: Float) {
    thinLine(m, x, 6.3, z, x2, 6.3, z2)
    let color = bannerColors[min(bannerColors.count - 1, Int(spotHash(x, z, 14) * Float(bannerColors.count)))]
    let ax = x + (x2 - x) * 0.22, az = z + (z2 - z) * 0.22
    let bx = x + (x2 - x) * 0.78, bz = z + (z2 - z) * 0.78
    let mx = (ax + bx) / 2, mz = (az + bz) / 2
    for side: Float in [1, -1] {
      let o = 0.01 * side
      wall(m, ax + dx * o, az + dz * o, bx + dx * o, bz + dz * o, 5.35, 6.28, mx - dx * side, mz - dz * side, color, Mat.PLAIN)
      let o2 = 0.02 * side
      let lx = ax + (bx - ax) * 0.12 + dx * o2, lz = az + (bz - az) * 0.12 + dz * o2
      let rx = ax + (bx - ax) * 0.88 + dx * o2, rz = az + (bz - az) * 0.88 + dz * o2
      wall(m, lx, lz, rx, rz, 5.66, 5.96, mx - dx * side, mz - dz * side, 0xF4F2EA, Mat.PLAIN)
    }
  }

  /// A rooftop advertising hoarding on its steel frame, lit from above at night.
  private func billboard(_ i: Int, _ top: Float, _ chunk: Chunk) {
    let m = chunk.detail
    let a = map.boxAngle[i]
    var ux = cos(a), uz = sin(a), vx = -uz, vz = ux
    var hu = map.boxHalfX[i], hv = map.boxHalfZ[i]
    if hv > hu { let tx = ux, tz = uz; ux = vx; uz = vz; vx = -tx; vz = -tz; let t = hu; hu = hv; hv = t }
    if hu < 4 { return }
    let half = min(5, hu * 0.85)
    let cx = map.boxX[i] + vx * hv * 0.7, cz = map.boxZ[i] + vz * hv * 0.7
    let y0 = top + City.PARAPET + 1.3, y1 = y0 + 3.6
    let x0 = cx - ux * half, z0 = cz - uz * half, x1 = cx + ux * half, z1 = cz + uz * half
    for k in 0...3 {
      let t = -half * 0.8 + Float(k) * (half * 1.6 / 3)
      let px = cx + ux * t - vx * 0.35, pz = cz + uz * t - vz * 0.35
      m.box(px - 0.08, top, pz - 0.08, px + 0.08, y1, pz + 0.08, 0x3A3D40)
    }
    let pick = min(5, Int(spotHash(cx, cz, 12) * 6)) * 3
    let bg = adColors[pick], c1 = adColors[pick + 1], c2 = adColors[pick + 2]
    wall(m, x0, z0, x1, z1, y0, y1, cx - vx, cz - vz, bg, Mat.PLAIN)
    wall(m, x0 - vx * 0.15, z0 - vz * 0.15, x1 - vx * 0.15, z1 - vz * 0.15, y0, y1, cx + vx, cz + vz, 0x2A2C2E, Mat.PLAIN)
    func layer(_ u0: Float, _ u1: Float, _ b: Float, _ t: Float, _ color: Int) {
      wall(m, cx + ux * u0 + vx * 0.02, cz + uz * u0 + vz * 0.02, cx + ux * u1 + vx * 0.02, cz + uz * u1 + vz * 0.02, y0 + b, y0 + t, cx - vx, cz - vz, color, Mat.PLAIN)
    }
    layer(-half * 0.94, -half * 0.12, 0.25, 3.35, c1)
    layer(-half * 0.02, half * 0.9, 2.3, 2.85, c2)
    layer(-half * 0.02, half * 0.75, 1.5, 1.85, c2)
    layer(-half * 0.02, half * 0.55, 0.75, 1.05, c1)
    wall(m, x0 + vx * 0.5, z0 + vz * 0.5, x1 + vx * 0.5, z1 + vz * 0.5, y1 + 0.05, y1 + 0.14, cx - vx, cz - vz, 0xFFF2D0, Mat.LAMP)
    solid(cx, cz, atan2(uz, ux), half, 0.4, top, y1 + 0.2)
  }

  /// A sodium lamp on an arm reaching over the road from a pole at (x, z); (dx, dz) points at the road.
  private func streetLamp(_ m: MeshBuilder, _ c: Chunk, _ x: Float, _ z: Float, _ dx: Float, _ dz: Float) {
    let hx = x + dx * 1.7, hz = z + dz * 1.7
    m.box(min(x, hx) - 0.05, 7.05, min(z, hz) - 0.05, max(x, hx) + 0.05, 7.15, max(z, hz) + 0.05, 0x5B5E60)
    m.box(hx - 0.28, 6.9, hz - 0.28, hx + 0.28, 7.08, hz + 0.28, 0x45484A)
    m.box(hx - 0.22, 6.86, hz - 0.22, hx + 0.22, 6.9, hz + 0.22, 0xFFC27A, Mat.LAMP)
    let r: Float = 7.5, y = C.ROAD_Y + 0.012
    for i in 0..<10 {
      let a = Float(i) * 2 * PI_F / 10, b = Float(i + 1) * 2 * PI_F / 10
      c.glow.shadeQuad(hx, y, hz, hx + cos(a) * r, y, hz + sin(a) * r, hx + cos(b) * r, y, hz + sin(b) * r, hx, y, hz, 0, 1, 1, 0)
    }
  }

  private func wires(_ e: Int) {
    let length = edgeLength(e)
    let start = junctionRadius[map.edgeA[e]] + 3, end = length - junctionRadius[map.edgeB[e]] - 3
    if end - start < 20 { return }
    let side = map.edgeWidth[e] / 2 + 1.1
    let count = max(1, javaRound((end - start) / 30))
    var lastX: Float = 0, lastZ: Float = 0
    for k in 0...count {
      let s = start + (end - start) * Float(k) / Float(count)
      edgePoint(e, s, &point)
      let x = point[0] + point[3] * side, z = point[1] - point[2] * side
      let ux = point[2], uz = point[3]
      let m = meshChunk(x, z, 32).detail
      m.box(x - 0.13, 0, z - 0.13, x + 0.13, 9, z + 0.13, 0xB9B6AE, Mat.of(.concrete), bottom: false)
      let ax = -uz * 0.9, az = ux * 0.9
      m.box(min(x - ax, x + ax) - 0.05, 8.2, min(z - az, z + az) - 0.05, max(x - ax, x + ax) + 0.05, 8.32, max(z - az, z + az) + 0.05, 0x5B5E60)
      solid(x, z, 0, 0.15, 0.15, 0, 9, false)
      streetLamp(m, meshChunk(x, z, 10), x, z, -point[3], point[2])
      if k > 0 && (3...5).contains(map.edgeClass[e]) && spotHash(x, z, 13) < 0.35 {
        banner(m, x, z, point[0] - point[3] * side, point[1] + point[2] * side, point[2], point[3])
      }
      if k > 0 {
        for strand in 0...2 {
          let o = (Float(strand) - 1) * 0.5
          let y = 7.4 + Float(strand % 2) * 0.55
          let sag = 0.5 + Float(strand) * 0.12
          let x0 = lastX + ax / 0.9 * o, z0 = lastZ + az / 0.9 * o
          let x1 = x + ax / 0.9 * o, z1 = z + az / 0.9 * o
          let mx = (x0 + x1) / 2, mz = (z0 + z1) / 2
          thinLine(m, x0, y, z0, mx, y - sag, mz)
          thinLine(m, mx, y - sag, mz, x1, y, z1)
        }
      }
      lastX = x; lastZ = z
    }
  }

  private func thinLine(_ m: MeshBuilder, _ ax: Float, _ ay: Float, _ az: Float, _ bx: Float, _ by: Float, _ bz: Float) {
    let dx = bx - ax, dz = bz - az
    if hypot(dx, dz) < 0.01 { return }
    m.quad(ax, ay - 0.03, az, bx, by - 0.03, bz, bx, by + 0.03, bz, ax, ay + 0.03, az, C.WIRE)
    m.quad(bx, by - 0.03, bz, ax, ay - 0.03, az, ax, ay + 0.03, az, bx, by + 0.03, bz, C.WIRE)
  }

  private func bridge(_ e: Int, _ c: Chunk) {
    let from = map.edgeStart[e], to = map.edgeStart[e + 1]
    let h = map.edgeWidth[e] / 2
    for k in from..<(to - 1) {
      let ax = map.edgeX[k], az = map.edgeZ[k], bx = map.edgeX[k + 1], bz = map.edgeZ[k + 1]
      let ay = map.edgeY[k], by = map.edgeY[k + 1]
      let dx = bx - ax, dz = bz - az, l = hypot(dx, dz)
      if l < 0.1 { continue }
      let px = -dz / l * h, pz = dx / l * h
      let mx = (ax + bx) / 2, mz = (az + bz) / 2
      c.mesh.quad(ax - px, ay - 0.9, az - pz, bx - px, by - 0.9, bz - pz, bx + px, by - 0.9, bz + pz, ax + px, ay - 0.9, az + pz, 0xBDB8AD, Mat.of(.concrete))
      wall(c.mesh, ax + px, az + pz, bx + px, bz + pz, min(ay, by) - 0.9, max(ay, by) + 1.0, mx, mz, 0xCFCAC0, Mat.of(.concrete))
      wall(c.mesh, ax - px, az - pz, bx - px, bz - pz, min(ay, by) - 0.9, max(ay, by) + 1.0, mx, mz, 0xCFCAC0, Mat.of(.concrete))
      let mid = (ay + by) / 2
      if mid > 0.5 { solid(mx, mz, atan2(dz, dx), l / 2, h, mid - 0.9, mid + C.ROAD_Y) }
      var s: Float = 0
      while s < l {
        let t = s / l
        let y = ay + (by - ay) * t
        if y > 2.5 {
          let x = ax + dx * t, z = az + dz * t
          c.mesh.cylinder(x, 0, z, 0.8, y - 0.9, 0.8, 10, 0xC8C3B8, Mat.of(.concrete))
          solid(x, z, 0, 0.8, 0.8, 0, y - 0.9)
        }
        s += 24
      }
    }
  }

  private func signals(_ n: Int) {
    let x = map.nodeX[n], z = map.nodeZ[n]
    let group = n & 1
    let r = junctionRadius[n]
    let m = meshChunk(x, z, r + 12).detail
    let colors = [0x3BFF83, 0xFFC531, 0xFF392F]
    for e in 0..<map.edgeCount {
      let a = map.edgeA[e], b = map.edgeB[e]
      if a != n && b != n { continue }
      let arriving = b == n
      if arriving && map.lanesForward[e] == 0 { continue }
      if !arriving && map.lanesBackward[e] == 0 { continue }
      let length = edgeLength(e)
      edgePoint(e, arriving ? max(0, length - r) : min(length, r), &point)
      let hx = arriving ? point[2] : -point[2], hz = arriving ? point[3] : -point[3]
      let axis = abs(hx) > abs(hz) ? 1 : 0
      let leftX = hz, leftZ = -hx
      let side = map.edgeWidth[e] / 2 + 0.7
      let px = point[0] + leftX * side, pz = point[1] + leftZ * side
      m.box(px - 0.1, 0, pz - 0.1, px + 0.1, 4.6, pz + 0.1, 0x6E767C, Mat.GLOSSY, bottom: false)
      solid(px, pz, 0, 0.12, 0.12, 0, 4.6, false)
      m.box(px - 0.25, 3.3, pz - 0.25, px + 0.25, 4.6, pz + 0.25, 0x17191B)
      let fx = px - hx * 0.27, fz = pz - hz * 0.27
      let sx = leftX * 0.15, sz = leftZ * 0.15
      for light in 0...2 {
        let y = 3.55 + Float(2 - light) * 0.36
        let material = Mat.SIGNAL + group * 6 + axis * 3 + light
        m.quad(fx - sx, y - 0.14, fz - sz, fx + sx, y - 0.14, fz + sz, fx + sx, y + 0.14, fz + sz, fx - sx, y + 0.14, fz - sz, colors[light], material)
        m.quad(fx + sx, y - 0.14, fz + sz, fx - sx, y - 0.14, fz - sz, fx - sx, y + 0.14, fz - sz, fx + sx, y + 0.14, fz + sz, colors[light], material)
      }
      edgePoint(e, arriving ? max(0, length - r + 0.6) : min(length, r - 0.6), &point)
      let half = map.edgeWidth[e] / 2 - 0.3
      var o = -half + 0.3
      let y = edgeHeight(e, 0) + C.ROAD_Y + 0.025
      while o < half {
        let cx = point[0] + point[3] * o, cz = point[1] - point[2] * o
        let ux = point[2] * 1.4, uz = point[3] * 1.4
        let vx = point[3] * 0.25, vz = -point[2] * 0.25
        upQuad(m, cx - ux - vx, cz - uz - vz, cx + ux - vx, cz + uz - vz, cx + ux + vx, cz + uz + vz, cx - ux + vx, cz - uz + vz, y, 0xE8E8E2, Mat.PLAIN)
        o += 1.1
      }
    }
  }

  // MARK: Buildings

  private func buildBuilding(_ i: Int) {
    let from = map.buildingStart[i], to = map.buildingStart[i + 1]
    let n = to - from
    if n < 3 { return }
    let kind = map.buildingKind[i]
    let floors = map.floors[i]
    let tintRoll = map.tint[i]
    let cx = map.boxX[i], cz = map.boxZ[i]
    let chunk = meshChunk(cx, cz, max(map.boxHalfX[i], map.boxHalfZ[i]) * 1.5)
    let m = chunk.mesh
    let top: Float = kind == MapData.SHED ? 3.2 : Float(floors) * map.floorHeight[i]

    let facade: Int, color: Int
    switch kind {
    case MapData.APARTMENT:
      color = apartmentTints[tintRoll % apartmentTints.count]
      facade = Mat.of(tintRoll % 7 == 3 ? .brickFlats : .plasterFlats)
    case MapData.HOUSE: color = houseTints[tintRoll % houseTints.count]; facade = Mat.of(tintRoll % 3 == 0 ? .brickFlats : .plasterFlats)
    case MapData.SHED: color = 0xB8A890; facade = Mat.of(.brick)
    case MapData.COMMERCIAL: color = towerTints[tintRoll % towerTints.count]; facade = Mat.of(.office)
    case MapData.TOWER: color = towerTints[tintRoll % towerTints.count]; facade = Mat.of(.curtainWall)
    case MapData.MALL: color = 0xCFE0EA; facade = Mat.of(.curtainWall)
    case MapData.EDUCATION: color = tintRoll % 2 == 0 ? 0xE9D2C0 : 0xF1EDE4; facade = Mat.of(tintRoll % 2 == 0 ? .brickFlats : .office)
    case MapData.HOSPITAL: color = 0xF2F2EE; facade = Mat.of(.office)
    case MapData.MOSQUE: color = 0xF1EBDD; facade = Mat.of(.plasterFlats)
    case MapData.INDUSTRIAL: color = 0xC4C6C2; facade = Mat.of(.metal)
    default: color = 0xB9B5AC; facade = Mat.of(.concrete)
    }
    let shopHeight: Float = map.shops[i] && floors >= 2 && kind != MapData.SHED ? 3.3 : 0
    for k in 0..<n {
      let a = from + k, b = from + (k + 1) % n
      let ax = map.buildingX[a], az = map.buildingZ[a], bx = map.buildingX[b], bz = map.buildingZ[b]
      if shopHeight > 0 {
        wall(m, ax, az, bx, bz, 0, shopHeight, cx, cz, 0xFFFFFF, Mat.of(.shops))
        wall(m, ax, az, bx, bz, shopHeight, top, cx, cz, color, facade)
      } else {
        wall(m, ax, az, bx, bz, 0, top, cx, cz, color, facade)
      }
    }
    contactShade(from, n, cx, cz, chunk.shade)
    let shed = kind == MapData.SHED
    let walled = !shed && kind != MapData.TOWER && kind != MapData.MALL
    if walled { relief(i, from, n, top, shopHeight, color, chunk) }
    flat(m, map.buildingX, map.buildingZ, from, to, top, shed ? (tintRoll % 3 == 0 ? 0x5E7C9A : 0x9A6B4F) : 0xD9D6CF, Mat.of(shed ? .metal : .roof))
    solid(cx, cz, map.boxAngle[i], map.boxHalfX[i], map.boxHalfZ[i], 0, top + (walled ? City.PARAPET : 0.3))

    if shopHeight > 0 { signboards(from, n, cx, cz, top) }
    switch kind {
    case MapData.APARTMENT, MapData.HOUSE, MapData.COMMERCIAL, MapData.EDUCATION, MapData.HOSPITAL: rooftop(i, top, chunk)
    case MapData.MOSQUE: mosque(i, top, chunk)
    default: break
    }
    if (kind == MapData.APARTMENT || kind == MapData.COMMERCIAL) && floors >= 6 && spotHash(cx, cz, 11) < 0.15 { billboard(i, top, chunk) }
  }
  /// Ambient occlusion on the ground around a building's footprint, darkest against the wall.
  private func contactShade(_ from: Int, _ n: Int, _ cx: Float, _ cz: Float, _ m: MeshBuilder) {
    let y: Float = 0.05, w: Float = 1.7
    for k in 0..<n {
      let a = from + k, b = from + (k + 1) % n
      let ax = map.buildingX[a], az = map.buildingZ[a], bx = map.buildingX[b], bz = map.buildingZ[b]
      let length = hypotf(bx - ax, bz - az)
      if length < 0.8 { continue }
      var nx = -(bz - az) / length, nz = (bx - ax) / length
      if nx * ((ax + bx) / 2 - cx) + nz * ((az + bz) / 2 - cz) < 0 { nx = -nx; nz = -nz }
      m.shadeQuad(ax, y, az, bx, y, bz, bx + nx * w, y, bz + nz * w, ax + nx * w, y, az + nz * w, 0.5, 0.5, 1, 1)
    }
  }

  /// A parapet wall around the roof and a concrete sunshade (chajja) over the windows of every
  /// floor, lined up with the floors painted in the facade texture (City.kt relief).
  private func relief(_ i: Int, _ from: Int, _ n: Int, _ top: Float, _ shopHeight: Float, _ color: Int, _ chunk: Chunk) {
    let cx = map.boxX[i], cz = map.boxZ[i]
    let trim = Mat.of(.concrete)
    let parapet = City.PARAPET
    let ledges = (3...16).contains(map.floors[i])
    for k in 0..<n {
      let a = from + k, b = from + (k + 1) % n
      let ax = map.buildingX[a], az = map.buildingZ[a], bx = map.buildingX[b], bz = map.buildingZ[b]
      let length = hypotf(bx - ax, bz - az)
      if length < 0.5 { continue }
      let ux = (bx - ax) / length, uz = (bz - az) / length
      var nx = -uz, nz = ux
      if nx * ((ax + bx) / 2 - cx) + nz * ((az + bz) / 2 - cz) < 0 { nx = -nx; nz = -nz }

      wall(chunk.mesh, ax, az, bx, bz, top, top + parapet, cx, cz, color, trim)
      if length < 1.2 { continue }
      let t: Float = 0.2
      let ix0 = ax - nx * t + ux * t, iz0 = az - nz * t + uz * t
      let ix1 = bx - nx * t - ux * t, iz1 = bz - nz * t - uz * t
      wall(chunk.detail, ix0, iz0, ix1, iz1, top, top + parapet, ix0 + nx * 50, iz0 + nz * 50, color, trim)
      upQuad(chunk.detail, ax, az, bx, bz, ix1, iz1, ix0, iz0, top + parapet, color, trim)

      if !ledges || length < 6 { continue }
      let d: Float = 0.5
      let x0 = ax, z0 = az, x1 = bx, z1 = bz
      let ox0 = x0 + nx * d, oz0 = z0 + nz * d, ox1 = x1 + nx * d, oz1 = z1 + nz * d
      var y = City.CURB + 2.72
      while y + 0.6 < top {
        if y > shopHeight + 0.2 { upQuad(chunk.detail, x0, z0, x1, z1, ox1, oz1, ox0, oz0, y, color, Mat.LEDGE) }
        y += 3.3
      }
    }
  }


  private func signboards(_ from: Int, _ n: Int, _ cx: Float, _ cz: Float, _ top: Float) {
    let m = meshChunk(cx, cz, 30).detail
    var first = -1, second = -1
    var firstLength: Float = 0, secondLength: Float = 0
    for k in 0..<n {
      let a = from + k, b = from + (k + 1) % n
      let length = hypot(map.buildingX[b] - map.buildingX[a], map.buildingZ[b] - map.buildingZ[a])
      if length > firstLength { second = first; secondLength = firstLength; first = k; firstLength = length }
      else if length > secondLength { second = k; secondLength = length }
    }
    for k in [first, second] {
      if k < 0 { continue }
      let a = from + k, b = from + (k + 1) % n
      let ax = map.buildingX[a], az = map.buildingZ[a], bx = map.buildingX[b], bz = map.buildingZ[b]
      let length = hypot(bx - ax, bz - az)
      if length < 4 { continue }
      var nx = -(bz - az) / length, nz = (bx - ax) / length
      if nx * ((ax + bx) / 2 - cx) + nz * ((az + bz) / 2 - cz) < 0 { nx = -nx; nz = -nz }
      let x0 = ax + (bx - ax) * 0.06 + nx * 0.12, z0 = az + (bz - az) * 0.06 + nz * 0.12
      let x1 = ax + (bx - ax) * 0.94 + nx * 0.12, z1 = az + (bz - az) * 0.94 + nz * 0.12
      let color = signColors[random.nextInt(signColors.count)]
      let y0 = 2.6 + random.nextFloat() * 0.15
      wall(m, x0, z0, x1, z1, y0, y0 + 0.75, x0 - nx, z0 - nz, color, Mat.PLAIN)
      let lx0 = x0 + (x1 - x0) * 0.1 + nx * 0.01, lz0 = z0 + (z1 - z0) * 0.1 + nz * 0.01
      let lx1 = x0 + (x1 - x0) * 0.9 + nx * 0.01, lz1 = z0 + (z1 - z0) * 0.9 + nz * 0.01
      wall(m, lx0, lz0, lx1, lz1, y0 + 0.28, y0 + 0.47, lx0 - nx, lz0 - nz, 0xF4F2EA, Mat.PLAIN)
      if k == first && length > 8 { bladeSigns(m, ax, az, bx, bz, nx, nz, length, top) }
    }
  }

  /// Signs that stick out from the wall above the shops, readable from along the street.
  private func bladeSigns(_ m: MeshBuilder, _ ax: Float, _ az: Float, _ bx: Float, _ bz: Float, _ nx: Float, _ nz: Float, _ length: Float, _ top: Float) {
    let ux = (bx - ax) / length, uz = (bz - az) / length
    let y1 = min(6.4, top - 0.4)
    if y1 < 4.6 { return }
    for f: Float in [0.2, 0.72] {
      let x0 = ax + (bx - ax) * f + nx * 0.12, z0 = az + (bz - az) * f + nz * 0.12
      let x1 = x0 + nx * 0.95, z1 = z0 + nz * 0.95
      let color = signColors[min(signColors.count - 1, Int(spotHash(x0, z0, 17) * Float(signColors.count)))]
      wall(m, x0, z0, x1, z1, 3.7, y1, x0 - ux, z0 - uz, color, Mat.PLAIN)
      wall(m, x0, z0, x1, z1, 3.7, y1, x0 + ux, z0 + uz, color, Mat.PLAIN)
      for s: Float in [1, -1] {
        let o = 0.012 * s
        wall(m, x0 + nx * 0.15 + ux * o, z0 + nz * 0.15 + uz * o, x0 + nx * 0.8 + ux * o, z0 + nz * 0.8 + uz * o,
             3.9, y1 - 0.25, x0 - ux * s, z0 - uz * s, 0xF4F2EA, Mat.PLAIN)
      }
    }
  }

  private func rooftop(_ i: Int, _ top: Float, _ chunk: Chunk) {
    let m = chunk.detail
    let c = cos(map.boxAngle[i]), s = sin(map.boxAngle[i])
    let hx = map.boxHalfX[i], hz = map.boxHalfZ[i]
    if hx < 2.5 || hz < 2.5 { return }
    let cx = map.boxX[i], cz = map.boxZ[i]
    func atX(_ u: Float, _ v: Float) -> Float { cx + u * c - v * s }
    func atZ(_ u: Float, _ v: Float) -> Float { cz + u * s + v * c }
    if map.floors[i] >= 4 {
      let x = atX(hx * 0.25, -hz * 0.2), z = atZ(hx * 0.25, -hz * 0.2)
      m.box(x - 1.3, top, z - 1.3, x + 1.3, top + 2.6, z + 1.3, 0xD5D1C8, Mat.of(.concrete), bottom: false)
      solid(x, z, 0, 1.3, 1.3, top, top + 2.6)
    }
    let tanks = hx * hz > 80 && random.nextBoolean() ? 2 : 1
    for k in 0..<tanks {
      let u = -hx * 0.55 + Float(k) * 1.6
      let x = atX(u, hz * 0.45), z = atZ(u, hz * 0.45)
      let color = tankColors[random.nextInt(tankColors.count)]
      let stand: Float = random.nextFloat() < 0.3 ? 0.6 : 0
      if stand > 0 { m.box(x - 0.6, top, z - 0.6, x + 0.6, top + stand, z + 0.6, 0x8A8E90, bottom: false) }
      m.cylinder(x, top + stand, z, 0.62, top + stand + 1.4, 0.6, 6, color, Mat.GLOSSY)
    }
    if random.nextFloat() < 0.18 {
      let x = atX(-hx * 0.3, -hz * 0.5), z = atZ(-hx * 0.3, -hz * 0.5)
      m.ellipsoid(x, top + 0.9, z, 0.45, 0.45, 0.12, 6, 3, 0xD8D8D2)
      m.box(x - 0.04, top, z - 0.04, x + 0.04, top + 0.9, z + 0.04, 0x707070)
    }
    if random.nextFloat() < 0.12 {
      for k in 0...2 {
        let u = hx * 0.5 - Float(k) * 1.2
        let x = atX(u, hz * 0.55), z = atZ(u, hz * 0.55)
        m.box(x - 0.3, top, z - 0.3, x + 0.3, top + 0.4, z + 0.3, 0xA0583A)
        m.ellipsoid(x, top + 0.75, z, 0.45, 0.4, 0.45, 5, 3, leafColors[k % leafColors.count], Mat.of(.leaves))
      }
    }
  }

  private func mosque(_ i: Int, _ top: Float, _ chunk: Chunk) {
    let cx = map.boxX[i], cz = map.boxZ[i]
    let r = min(map.boxHalfX[i], map.boxHalfZ[i]) * 0.55
    if r < 1.2 { return }
    chunk.mesh.cylinder(cx, top, cz, r * 1.02, top + r * 0.35, r * 1.02, 16, 0xEFE8D8, Mat.of(.concrete))
    chunk.mesh.ellipsoid(cx, top + r * 0.35, cz, r, r * 0.95, r, 14, 7, 0xE8E2D2, Mat.GLOSSY)
    chunk.mesh.cylinder(cx, top + r * 1.25, cz, 0.08, top + r * 1.25 + 1.2, 0.03, 5, 0xC9A040, Mat.GLOSSY)
    solid(cx, cz, 0, r, r, top, top + r * 1.3)
    let c = cos(map.boxAngle[i]), s = sin(map.boxAngle[i])
    let u = map.boxHalfX[i] - 1.2, v = map.boxHalfZ[i] - 1.2
    let mx = cx + u * c - v * s, mz = cz + u * s + v * c
    let height = top + 10 + min(10, r * 3)
    chunk.mesh.cylinder(mx, 0, mz, 0.9, height, 0.75, 10, 0xF1EBDD, Mat.of(.concrete))
    chunk.mesh.cylinder(mx, height - 2.4, mz, 1.15, height - 2.1, 1.15, 10, 0xE4DCCB, Mat.of(.concrete))
    chunk.mesh.ellipsoid(mx, height, mz, 0.8, 1.1, 0.8, 8, 4, 0xE8E2D2, Mat.GLOSSY)
    solid(mx, mz, 0, 0.95, 0.95, 0, height + 1)
  }

  private func buildLandmark(_ i: Int) {
    let b = map.landmarkBuilding[i]
    if b < 0 || b >= map.buildingCount { return }
    let cx = map.boxX[b], cz = map.boxZ[b]
    let c = cos(map.boxAngle[b]), s = sin(map.boxAngle[b])
    let hx = map.boxHalfX[b], hz = map.boxHalfZ[b]
    let top = Float(map.floors[b]) * map.floorHeight[b]
    let chunk = meshChunk(cx, cz, max(hx, hz) + 10)
    switch map.landmarkKind[i] {
    case MapData.SEVEN_DOMES:
      let long = hx >= hz
      let span = long ? hx : hz
      let r = min(long ? hz : hx, span / 2.2) * 0.75
      for k in -1...1 {
        let u: Float = long ? Float(k) * span * 0.62 : 0
        let v: Float = long ? 0 : Float(k) * span * 0.62
        let x = cx + u * c - v * s, z = cz + u * s + v * c
        chunk.mesh.cylinder(x, top, z, r * 1.02, top + 0.5, r * 1.02, 14, 0xEEE6D6, Mat.of(.concrete))
        chunk.mesh.ellipsoid(x, top + 0.5, z, r, r * 1.05, r, 14, 7, 0xF2ECDF, Mat.GLOSSY)
        chunk.mesh.cylinder(x, top + 0.5 + r * 1.05, z, 0.07, top + 1.6 + r * 1.05, 0.02, 5, 0xC9A040, Mat.GLOSSY)
      }
      for su: Float in [-1, 1] {
        for sv: Float in [-1, 1] {
          let u = su * hx, v = sv * hz
          let x = cx + u * c - v * s, z = cz + u * s + v * c
          chunk.mesh.cylinder(x, 0, z, 1.1, top + 2.6, 1, 12, 0xEDE5D4, Mat.of(.concrete))
          chunk.mesh.ellipsoid(x, top + 2.6, z, 1.05, 1.2, 1.05, 10, 5, 0xF2ECDF, Mat.GLOSSY)
          solid(x, z, 0, 1.1, 1.1, 0, top + 3.8)
        }
      }
    case MapData.MALL_SIGN:
      let v = -hz + 0.5
      let x = cx - v * s, z = cz + v * c
      let w = min(hx * 0.6, 40)
      let x0 = x - w * c, z0 = z - w * s, x1 = x + w * c, z1 = z + w * s
      chunk.mesh.quad(x0, top, z0, x1, top, z1, x1, top + 6, z1, x0, top + 6, z0, 0xD0202A, Mat.EMISSIVE)
      chunk.mesh.quad(x1, top, z1, x0, top, z0, x0, top + 6, z0, x1, top + 6, z1, 0xD0202A, Mat.EMISSIVE)
    case MapData.HOTEL:
      chunk.mesh.box(cx - 3, top, cz - 3, cx + 3, top + 4, cz + 3, 0xDCE6EC, Mat.of(.curtainWall))
      chunk.mesh.cylinder(cx, top + 4, cz, 0.1, top + 14, 0.04, 5, 0xB7BDC0)
      chunk.mesh.centered(cx, top + 14, cz, 0.3, 0.3, 0.3, 0xFF4038, Mat.EMISSIVE)
      solid(cx, cz, 0, 3, 3, top, top + 4)
    default: break
    }
  }

  // MARK: Docks

  private func buildDock(_ x: Float, _ z: Float) {
    let c = meshChunk(x, z, 3)
    let m = c.mesh, d = c.detail
    let R = C.DOCK_RADIUS, TOP = C.DOCK_TOP
    let deck = TOP - 0.32
    m.cylinder(x, 0.9, z, 0.2, deck - 0.5, 0.16, 6, 0x8A949A, Mat.GLOSSY, cap: false)
    m.cylinder(x, deck, z, R, TOP, R, 16, 0x2B3136, Mat.GLOSSY, capMaterial: Mat.of(.deck))
    d.cylinder(x, 0, z, 0.32, 0.9, 0.26, 8, 0xE4E1D9, Mat.of(.concrete))
    d.cylinder(x, deck - 0.5, z, 0.16, deck, R * 0.75, 10, 0x5F686E, Mat.GLOSSY, cap: false)
    let mark = TOP + 0.012
    d.box(x - 0.62, TOP, z - 0.72, x - 0.38, mark, z + 0.72, 0xD9A928, bottom: false)
    d.box(x + 0.38, TOP, z - 0.72, x + 0.62, mark, z + 0.72, 0xD9A928, bottom: false)
    d.box(x - 0.38, TOP, z - 0.12, x + 0.38, mark, z + 0.12, 0xD9A928, bottom: false)
    for k in 0..<8 {
      let a = Float(k) * PI_F / 4 + PI_F / 8
      d.centered(x + cos(a) * (R - 0.08), TOP - 0.12, z + sin(a) * (R - 0.08), 0.12, 0.1, 0.12, 0x58E6FF, Mat.EMISSIVE)
    }
    solid(x, z, 0, 0.3, 0.3, 0, deck, false)
    solid(x, z, 0, R, R, deck, TOP, false)
  }

  func nearestDock(_ x: Float, _ z: Float) -> Int {
    var best = 0, bestDistance = Float.greatestFiniteMagnitude
    for d in 0..<dockCount {
      let distance = hypot(docks[d * 3] - x, docks[d * 3 + 2] - z)
      if distance < bestDistance { bestDistance = distance; best = d }
    }
    return best
  }

  func highestAlong(_ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float) -> Float {
    let length = hypot(x1 - x0, z1 - z0)
    let samples = max(1, Int(length / 6))
    var highest: Float = 0
    for k in 0...samples {
      let t = Float(k) / Float(samples)
      let px = x0 + (x1 - x0) * t, pz = z0 + (z1 - z0) * t
      let c = chunkAt(px, pz)
      let b = c.boxes
      var i = 0
      while i < c.count * 8 {
        let dx = px - b[i], dz = pz - b[i + 1]
        let u = dx * b[i + 2] + dz * b[i + 3], v = -dx * b[i + 3] + dz * b[i + 2]
        if abs(u) < b[i + 4] + 8 && abs(v) < b[i + 5] + 8 { highest = max(highest, b[i + 7]) }
        i += 8
      }
    }
    return highest
  }

  // MARK: Beyond the map

  private func buildTerrain() {
    let edge: Float = 3200
    terrain.quad(-edge, 0, edge, edge, 0, edge, edge, 0, -edge, -edge, 0, -edge, 0xFFFFFF, Mat.GROUND)
    for _ in 0..<900 {
      let a = between(0, 2 * PI_F)
      let distance = between(C.HALF * 1.08, C.HALF * 1.9)
      let x = cos(a) * distance, z = sin(a) * distance
      let w = between(10, 22), d = between(10, 22)
      let h = Float(3 + random.nextInt(8)) * 3.2
      let tint = apartmentTints[random.nextInt(apartmentTints.count)]
      if abs(x) > C.HALF + 15 || abs(z) > C.HALF + 15 {
        terrain.box(x - w / 2, 0, z - d / 2, x + w / 2, h, z + d / 2, tint, Mat.of(.plasterFlats), top: 0xD5D2CA, topMaterial: Mat.of(.roof), bottom: false)
      }
    }
  }

  // MARK: Queries

  func support(_ x: Float, _ z: Float, _ below: Float, _ radius: Float) -> Float {
    var best: Float = 0
    for j in C.cell(z - radius)...C.cell(z + radius) {
      for i in C.cell(x - radius)...C.cell(x + radius) {
        let c = chunks[j * C.CELLS + i]
        let b = c.boxes
        var k = 0
        while k < c.count * 8 {
          let top = b[k + 7]
          if top > best && top <= below {
            let dx = x - b[k], dz = z - b[k + 1]
            let u = dx * b[k + 2] + dz * b[k + 3], v = -dx * b[k + 3] + dz * b[k + 2]
            if abs(u) < b[k + 4] + radius && abs(v) < b[k + 5] + radius { best = top }
          }
          k += 8
        }
      }
    }
    return best
  }

  func raycast(_ ox: Float, _ oy: Float, _ oz: Float, _ dx: Float, _ dy: Float, _ dz: Float, _ range: Float,
               _ normal: inout [Float], opaqueOnly: Bool = false) -> Float {
    var best = range
    let ex = ox + dx * range, ez = oz + dz * range
    for j in C.cell(min(oz, ez))...C.cell(max(oz, ez)) {
      for i in C.cell(min(ox, ex))...C.cell(max(ox, ex)) {
        let c = chunks[j * C.CELLS + i]
        if min(oy, oy + dy * range) > c.top { continue }
        let b = c.boxes
        var k = 0
        while k < c.count * 8 {
          if opaqueOnly && !c.blocksView[k / 8] { k += 8; continue }
          let cs = b[k + 2], sn = b[k + 3]
          let rx = ox - b[k], rz = oz - b[k + 1]
          let lu = rx * cs + rz * sn, lv = -rx * sn + rz * cs
          let du = dx * cs + dz * sn, dv = -dx * sn + dz * cs
          var near: Float = 0, far = best, hitAxis = -1, hit = true
          for axis in 0...2 {
            let o = axis == 0 ? lu : axis == 1 ? oy : lv
            let d = axis == 0 ? du : axis == 1 ? dy : dv
            let low = axis == 0 ? -b[k + 4] : axis == 1 ? b[k + 6] : -b[k + 5]
            let high = axis == 0 ? b[k + 4] : axis == 1 ? b[k + 7] : b[k + 5]
            if abs(d) < 1e-6 {
              if o < low || o > high { hit = false; break }
            } else {
              let enter = ((d > 0 ? low : high) - o) / d
              let leave = ((d > 0 ? high : low) - o) / d
              if enter > near { near = enter; hitAxis = axis }
              far = min(far, leave)
              if near > far { hit = false; break }
            }
          }
          if hit && hitAxis >= 0 && near < best {
            best = near
            if normal.count >= 3 {
              let nu: Float = hitAxis == 0 ? -sgn(du) : 0
              let nv: Float = hitAxis == 2 ? -sgn(dv) : 0
              normal[0] = nu * cs - nv * sn
              normal[1] = hitAxis == 1 ? -sgn(dy) : 0
              normal[2] = nu * sn + nv * cs
            }
          }
          k += 8
        }
      }
    }
    return best
  }

  func raycast(_ ox: Float, _ oy: Float, _ oz: Float, _ dx: Float, _ dy: Float, _ dz: Float, _ range: Float, opaqueOnly: Bool = false) -> Float {
    var unused: [Float] = [0, 0, 0]
    return raycast(ox, oy, oz, dx, dy, dz, range, &unused, opaqueOnly: opaqueOnly)
  }

  private func sgn(_ v: Float) -> Float { v > 0 ? 1 : v < 0 ? -1 : 0 }
}
