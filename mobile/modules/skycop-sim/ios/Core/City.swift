import Foundation

/// Swift port of City.kt: the same seed and the same calls in the same order give the same city.
final class City {
  static let BLOCKS = 12
  static let PITCH: Float = 72
  static let HALF: Float = Float(BLOCKS / 2) * PITCH
  static let ROAD_HALF: Float = 8
  static let CURB: Float = 0.15
  static let BOUNDS: Float = HALF + 40
  static let HOME_X: Float = 36, HOME_Y: Float = 0.35, HOME_Z: Float = 30

  static func road(_ index: Int) -> Float { Float(index - BLOCKS / 2) * PITCH }

  final class Chunk {
    let mesh = MeshBuilder()
    let detail = MeshBuilder()
    let x: Float, z: Float
    var top: Float = 6
    var boxes: [Float] = []
    var blocksView: [Bool] = []
    var count: Int { blocksView.count }

    init(x: Float, z: Float) { self.x = x; self.z = z }

    func solid(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, _ opaque: Bool = true) {
      blocksView.append(opaque)
      boxes.append(contentsOf: [x0, y0, z0, x1, y1, z1])
      top = max(top, y1 + 3)
    }
  }

  let chunks: [Chunk]
  let terrain = MeshBuilder()
  private(set) var tallest: Float = 0

  private var random: KotlinRandom
  private let plasterTints = [0xFFFFFF, 0xF6D9B8, 0xD8E6DA, 0xF2C9B4, 0xCCDBEA, 0xF3DF9F, 0xE6CFC6]
  private let brickTints = [0xFFFFFF, 0xEBD9CF, 0xD2C6C0, 0xFFE8D0]
  private let towerTints = [0xFFFFFF, 0xDCE6EC, 0xE8E0D4, 0xC9D3CF, 0xF4EEE6]
  private let leafColors = [0x7FA35C, 0x93B568, 0xA9B86A, 0x6E9254]

  private typealias C = City

  init(seed: Int32 = 1971) {
    random = KotlinRandom(seed: seed)
    chunks = (0..<(C.BLOCKS * C.BLOCKS)).map {
      Chunk(x: C.road($0 % C.BLOCKS) + C.PITCH / 2, z: C.road($0 / C.BLOCKS) + C.PITCH / 2)
    }
    for j in 0..<C.BLOCKS { for i in 0..<C.BLOCKS { buildBlock(chunks[j * C.BLOCKS + i], i - C.BLOCKS / 2, j - C.BLOCKS / 2) } }
    for j in 0...C.BLOCKS { for i in 0...C.BLOCKS { buildJunction(i, j) } }
    buildTerrain()
    for chunk in chunks { tallest = max(tallest, chunk.top - 3) }
  }

  private func between(_ low: Float, _ high: Float) -> Float { low + random.nextFloat() * (high - low) }

  private func buildBlock(_ c: Chunk, _ i: Int, _ j: Int) {
    let x0 = c.x - C.PITCH / 2 + C.ROAD_HALF, x1 = c.x + C.PITCH / 2 - C.ROAD_HALF
    let z0 = c.z - C.PITCH / 2 + C.ROAD_HALF, z1 = c.z + C.PITCH / 2 - C.ROAD_HALF
    c.mesh.box(x0, 0, z0, x1, C.CURB, z1, 0xD8D8D4, Mat.of(.concrete), top: 0xF2F0EA, topMaterial: Mat.of(.paving), bottom: false)
    c.solid(x0, 0, z0, x1, C.CURB, z1)

    let ring = hypot(Float(i) + 0.5, Float(j) + 0.5)
    if i == 0 && j == 0 {
      station(c, x0, z0, x1, z1)
    } else if (i == 0 && j == -1) || (i == -4 && j == 2) || random.nextFloat() < 0.09 {
      park(c, x0, z0, x1, z1)
    } else if random.nextFloat() < 0.05 {
      carPark(c, x0, z0, x1, z1)
    } else if ring < 3.3 {
      downtown(c, x0, z0, ring)
    } else if ring < 4.8 {
      midtown(c, x0, z0)
    } else {
      suburb(c, x0, z0)
    }

    var along = x0 + 7
    while along < x1 - 4 {
      if i != 0 || j != 0 {
        tree(c, along, z0 + 1.6, 0.75)
        tree(c, along, z1 - 1.6, 0.75)
      }
      along += 14
    }
    lamp(c, x0 + 0.5, c.z, 1)
    lamp(c, x1 - 0.5, c.z, -1)
  }

  private func building(_ c: Chunk, _ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float, _ base: Float, _ top: Float,
                        _ color: Int, _ style: Tex, _ shops: Bool = false) {
    let m = c.mesh
    var wallFrom = base
    if shops {
      wallFrom = base + 3.3
      m.box(x0, base, z0, x1, wallFrom, z1, 0xFFFFFF, Mat.of(.shops), bottom: false)
      let ledge: Float = 0.3
      let concrete = Mat.of(.concrete)
      m.box(x0 - ledge, wallFrom - 0.12, z0 - ledge, x1 + ledge, wallFrom + 0.1, z0, 0xDDDAD2, concrete)
      m.box(x0 - ledge, wallFrom - 0.12, z1, x1 + ledge, wallFrom + 0.1, z1 + ledge, 0xDDDAD2, concrete)
      m.box(x0 - ledge, wallFrom - 0.12, z0, x0, wallFrom + 0.1, z1, 0xDDDAD2, concrete)
      m.box(x1, wallFrom - 0.12, z0, x1 + ledge, wallFrom + 0.1, z1, 0xDDDAD2, concrete)
    }
    m.box(x0, wallFrom, z0, x1, top, z1, color, Mat.of(style), top: 0xE8E8E4, topMaterial: Mat.of(.roof), bottom: false)
    c.solid(x0, base, z0, x1, top + 0.45, z1)
    let t: Float = 0.35
    let lip = Mat.of(.concrete)
    m.box(x0 - 0.12, top - 0.05, z0 - 0.12, x1 + 0.12, top + 0.45, z0 + t, 0xE4E1D9, lip)
    m.box(x0 - 0.12, top - 0.05, z1 - t, x1 + 0.12, top + 0.45, z1 + 0.12, 0xE4E1D9, lip)
    m.box(x0 - 0.12, top - 0.05, z0 + t, x0 + t, top + 0.45, z1 - t, 0xE4E1D9, lip)
    m.box(x1 - t, top - 0.05, z0 + t, x1 + 0.12, top + 0.45, z1 - t, 0xE4E1D9, lip)
  }

  private func rooftop(_ c: Chunk, _ x: Float, _ z: Float, _ top: Float) {
    let m = c.mesh
    m.box(x - 2.2, top, z - 1.5, x + 1, top + 2, z + 1.5, 0xE0E0DC, Mat.of(.concrete), bottom: false)
    c.solid(x - 2.2, top, z - 1.5, x + 1, top + 2, z + 1.5)
    if random.nextBoolean() {
      m.cylinder(x + 3, top + 0.3, z, 1.1, top + 2.6, 1.1, 10, 0xE5E0D5, Mat.of(.metal))
      c.solid(x + 1.9, top, z - 1.1, x + 4.1, top + 2.6, z + 1.1)
    }
    let d = c.detail
    for k in 0..<random.nextInt(2, 4) {
      let ux = x - 2 + Float(k) * 2.1, uz = z + 3.2
      d.box(ux - 0.7, top, uz - 0.5, ux + 0.7, top + 0.8, uz + 0.5, 0xEEF0EE, Mat.of(.metal), top: 0x4A5054, topMaterial: Mat.PLAIN, bottom: false)
      d.cylinder(ux, top + 0.8, uz, 0.36, top + 0.88, 0.36, 8, 0x2C3033)
    }
    if random.nextBoolean() {
      d.cylinder(x - 0.6, top + 2, z, 0.07, top + 5.5, 0.02, 3, 0xB7BDC0)
      d.centered(x - 0.6, top + 5.5, z, 0.14, 0.14, 0.14, 0xFF4038, Mat.EMISSIVE)
    }
  }

  private func downtown(_ c: Chunk, _ x0: Float, _ z0: Float, _ ring: Float) {
    for a in 0...1 {
      for b in 0...1 {
        let left = x0 + 4 + Float(a) * 27, back = z0 + 4 + Float(b) * 27
        let width = 15 + Float(random.nextInt(3)) * 3
        let depth = 15 + Float(random.nextInt(3)) * 3
        let floors = max(5, Int(between(8, 19) - ring * 1.3))
        let roll = random.nextInt(7)
        let style: Tex = roll <= 2 ? .curtainWall : roll <= 4 ? .office : .stoneTower
        let color = towerTints[random.nextInt(towerTints.count)]
        let top = C.CURB + Float(floors) * 3.3
        let shops = random.nextInt(4) != 0
        if floors > 11 {
          let middle = C.CURB + Float(floors - 4) * 3.3
          building(c, left, back, left + width, back + depth, C.CURB, middle, color, style, shops)
          building(c, left + 3, back + 3, left + width - 3, back + depth - 3, middle, top, color, style)
        } else {
          building(c, left, back, left + width, back + depth, C.CURB, top, color, style, shops)
        }
        rooftop(c, left + width / 2, back + depth / 2, top)
      }
    }
  }

  private func midtown(_ c: Chunk, _ x0: Float, _ z0: Float) {
    for a in 0...2 {
      for b in 0...1 {
        let left = x0 + 4 + Float(a) * 18, back = z0 + 4 + Float(b) * 30
        let width = 12 + Float(random.nextInt(2)) * 3
        let depth = 12 + Float(random.nextInt(3)) * 3
        let top = C.CURB + Float(random.nextInt(3, 8)) * 3.3
        let roll = random.nextInt(5)
        let style: Tex = roll <= 1 ? .brickFlats : roll <= 3 ? .plasterFlats : .office
        let tints = style == .brickFlats ? brickTints : style == .office ? towerTints : plasterTints
        let tint = tints[random.nextInt(tints.count)]
        building(c, left, back, left + width, back + depth, C.CURB, top, tint, style, random.nextBoolean())
        rooftop(c, left + width / 2, back + depth / 2, top)
      }
    }
  }

  private func suburb(_ c: Chunk, _ x0: Float, _ z0: Float) {
    for a in 0...2 {
      for b in 0...1 {
        let left = x0 + 4 + Float(a) * 18, back = z0 + 7 + Float(b) * 27
        let right = left + 12, front = back + 12
        let top = C.CURB + Float(random.nextInt(1, 4)) * 3.3
        let ridge = top + 3.2, middle = (back + front) / 2
        let brick = random.nextInt(3) == 0
        let wall = brick ? brickTints[random.nextInt(brickTints.count)] : plasterTints[random.nextInt(plasterTints.count)]
        let roofRoll = random.nextInt(3)
        let roof = roofRoll == 0 ? 0xC7664A : roofRoll == 1 ? 0x7C8992 : 0x9C6B52
        let gable = Mat.of(brick ? .brick : .concrete)
        let tiles = Mat.of(.roofTiles)
        let m = c.mesh
        m.box(left, C.CURB, back, right, top, front, wall, Mat.of(brick ? .brickFlats : .plasterFlats), bottom: false)
        let e: Float = 0.5, eave = top - 0.2
        m.quad(left - e, eave, front + e, right + e, eave, front + e, right + e, ridge, middle, left - e, ridge, middle, roof, tiles)
        m.quad(right + e, eave, back - e, left - e, eave, back - e, left - e, ridge, middle, right + e, ridge, middle, roof, tiles)
        m.quad(left - e, eave, front + e, left - e, eave, back - e, right + e, eave, back - e, right + e, eave, front + e, 0xCBC5BB)
        m.triangle(right, top, front, right, top, back, right, ridge, middle, wall, gable)
        m.triangle(left, top, back, left, top, front, left, ridge, middle, wall, gable)
        c.solid(left, 0, back, right, top + 1.8, front)
        let d = c.detail
        let leaves = Mat.of(.leaves)
        d.box(left, C.CURB, front + 1.4, left + 4.8, 1.05, front + 2.2, 0x86A860, leaves, bottom: false)
        d.box(left + 7, C.CURB, front + 1.4, right, 1.05, front + 2.2, 0x86A860, leaves, bottom: false)
        tree(c, right + 3, front + 3, between(0.7, 1.1))
      }
    }
  }

  private func park(_ c: Chunk, _ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float) {
    let m = c.mesh
    m.box(x0 + 3, C.CURB, z0 + 3, x1 - 3, 0.28, z1 - 3, 0xFFFFFF, Mat.of(.grass), bottom: false)
    m.box(c.x - 1.6, 0.28, z0 + 3, c.x + 1.6, 0.34, z1 - 3, 0xF3DFC0, Mat.of(.paving), bottom: false)
    m.cylinder(c.x, 0.28, c.z, 10, 0.5, 10, 32, 0xEFE8DA, Mat.of(.concrete))
    m.cylinder(c.x, 0.5, c.z, 9, 0.6, 9, 32, 0x356E85, Mat.WATER)
    for side: Float in [-1, 1] {
      for along: Float in [-18, -13.5, 13.5, 18] {
        let bx = c.x + side * 2.3, bz = c.z + along
        c.detail.centered(bx, 0.72, bz, 0.5, 0.07, 1.6, 0xA8794C)
        c.detail.centered(bx + side * 0.25, 1.0, bz, 0.06, 0.4, 1.6, 0xA8794C)
        c.detail.centered(bx, 0.5, bz - 0.6, 0.4, 0.4, 0.07, 0x3A3F42)
        c.detail.centered(bx, 0.5, bz + 0.6, 0.4, 0.4, 0.07, 0x3A3F42)
      }
    }
    c.solid(x0 + 3, 0, z0 + 3, x1 - 3, 0.3, z1 - 3)
    for _ in 0..<26 {
      let x = between(x0 + 6, x1 - 6), z = between(z0 + 6, z1 - 6)
      if hypot(x - c.x, z - c.z) > 13 && abs(x - c.x) > 4 { tree(c, x, z, between(0.8, 1.5)) }
    }
  }

  private func carPark(_ c: Chunk, _ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float) {
    let m = c.mesh
    m.box(x0 + 3, C.CURB, z0 + 3, x1 - 3, 0.22, z1 - 3, 0xFFFFFF, Mat.of(.asphalt), bottom: false)
    let colors = [0xE8E8E4, 0x22262B, 0x9C2F2A, 0x2E5277, 0x8B9297]
    for row in 0...2 {
      var x = x0 + 7
      while x < x1 - 6 {
        let z = z0 + 10 + Float(row) * 17
        m.box(x, 0.22, z, x + 0.15, 0.25, z + 5.5, 0xDDDDD4, bottom: false)
        if random.nextFloat() < 0.6 {
          let color = colors[random.nextInt(colors.count)]
          c.detail.centered(x + 1.6, 0.85, z + 2.7, 1.8, 0.8, 4.1, color, Mat.GLOSSY)
          c.detail.centered(x + 1.6, 1.5, z + 2.8, 1.55, 0.55, 2.2, 0x26323A, Mat.GLASS)
          c.solid(x + 0.7, 0.2, z + 0.6, x + 2.5, 1.8, z + 4.8)
        }
        x += 3.2
      }
    }
  }

  private func station(_ c: Chunk, _ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float) {
    let m = c.mesh
    let top = C.CURB + 3 * 3.3
    building(c, 18, 45, 54, 60, C.CURB, top, 0xC4DCEF, .office)
    m.box(27, 4.0, 43.2, 45, 4.6, 45, 0x1F5FA8, Mat.GLOSSY)
    c.solid(27, 0, 43.2, 45, 4.6, 45)
    rooftop(c, 30, 53, top)
    let px = C.HOME_X, pz = C.HOME_Z
    let pad = C.HOME_Y - 0.03
    m.cylinder(px, C.CURB, pz, 5.4, pad, 5.4, 48, 0xFFFFFF, Mat.of(.concrete))
    m.cylinder(px, pad, pz, 4.8, C.HOME_Y, 4.8, 48, 0xC0CCD2, Mat.of(.deck))
    let mark = C.HOME_Y + 0.012
    m.box(px - 1.8, C.HOME_Y, pz - 2.2, px - 1.1, mark, pz + 2.2, 0xD9A928)
    m.box(px + 1.1, C.HOME_Y, pz - 2.2, px + 1.8, mark, pz + 2.2, 0xD9A928)
    m.box(px - 1.1, C.HOME_Y, pz - 0.35, px + 1.1, mark, pz + 0.35, 0xD9A928)
    c.solid(px - 4.8, 0, pz - 4.8, px + 4.8, C.HOME_Y, pz + 4.8)
    for k in 0..<12 {
      let a = Float(k) * 2 * PI_F / 12
      m.centered(px + cos(a) * 5.1, 0.42, pz + sin(a) * 5.1, 0.2, 0.2, 0.2, 0x58E6FF, Mat.EMISSIVE)
    }
    for x in [x0 + 5, x1 - 5] { for z in [z0 + 5, c.z, z1 - 5] { tree(c, x, z, 0.9) } }
  }

  private func tree(_ c: Chunk, _ x: Float, _ z: Float, _ s: Float) {
    let m = c.detail
    let color = leafColors[random.nextInt(leafColors.count)]
    m.cylinder(x, C.CURB, z, 0.22 * s, 2.6 * s, 0.13 * s, 5, 0xFFFFFF, Mat.of(.bark), cap: false)
    let leaves = Mat.of(.leaves)
    let lean = (random.nextFloat() - 0.5) * s
    m.ellipsoid(x, 3.0 * s, z, 1.75 * s, 1.35 * s, 1.65 * s, 6, 4, color, leaves, bumps: 0.25)
    m.ellipsoid(x + lean * 0.8, 4.0 * s, z + lean * 0.5, 1.15 * s, 1.05 * s, 1.15 * s, 5, 3, color, leaves, bumps: 0.3)
    c.solid(x - 1.3 * s, 1.9 * s, z - 1.3 * s, x + 1.3 * s, 4.8 * s, z + 1.3 * s, false)
    c.solid(x - 0.2, 0, z - 0.2, x + 0.2, 1.9 * s, z + 0.2, false)
  }

  private func lamp(_ c: Chunk, _ x: Float, _ z: Float, _ towardBlock: Float) {
    c.detail.box(x - 0.09, C.CURB, z - 0.09, x + 0.09, 6.2, z + 0.09, 0x8A9298, Mat.GLOSSY)
    let tip = x - towardBlock * 2
    c.detail.box(min(x, tip), 6.2, z - 0.08, max(x, tip), 6.35, z + 0.08, 0x555D62)
    c.detail.centered(tip, 6.15, z, 0.7, 0.12, 0.35, 0xFFF1C2, Mat.EMISSIVE)
    c.solid(x - 0.12, 0, z - 0.12, x + 0.12, 6.35, z + 0.12, false)
  }

  private func buildJunction(_ i: Int, _ j: Int) {
    let x = C.road(i), z = C.road(j)
    let c = chunks[chunkIndex(x.clamped(-C.HALF + 1, C.HALF - 1), z.clamped(-C.HALF + 1, C.HALF - 1))]
    let group = (i + j) & 1
    for k in 0...3 {
      let yaw = Float(k) * PI_F / 2
      let fx = floor(sin(yaw) + 0.5), fz = -floor(cos(yaw) + 0.5)
      let rx = -fz, rz = fx
      let px = x + fx * 8.7 + rx * 8.7, pz = z + fz * 8.7 + rz * 8.7
      c.detail.box(px - 0.13, 0, pz - 0.13, px + 0.13, 6.4, pz + 0.13, 0x7A8288, Mat.GLOSSY, bottom: false)
      c.solid(px - 0.15, 0, pz - 0.15, px + 0.15, 6.5, pz + 0.15, false)
      let ex = px - rx * 6.4, ez = pz - rz * 6.4
      c.detail.box(min(px, ex) - 0.08, 6.2, min(pz, ez) - 0.08, max(px, ex) + 0.08, 6.36, max(pz, ez) + 0.08, 0x7A8288, Mat.GLOSSY)
      c.solid(min(px, ex) - 0.3, 5.3, min(pz, ez) - 0.3, max(px, ex) + 0.3, 6.5, max(pz, ez) + 0.3, false)
      let axis = fz != 0 ? 0 : 1
      for back: Float in [3.0, 6.4] {
        let hx = px - rx * back, hz = pz - rz * back
        c.detail.centered(hx, 5.85, hz, axis == 0 ? 0.55 : 0.3, 1.4, axis == 0 ? 0.3 : 0.55, 0x17191B)
        let colors = [0x3BFF83, 0xFFC531, 0xFF392F]
        for light in 0...2 {
          let bx = hx - fx * 0.165, bz = hz - fz * 0.165
          let sx = rx * 0.16, sz = rz * 0.16
          let y = 5.43 + Float(light) * 0.42
          c.detail.quad(bx - sx, y - 0.16, bz - sz, bx + sx, y - 0.16, bz + sz,
                        bx + sx, y + 0.16, bz + sz, bx - sx, y + 0.16, bz - sz,
                        colors[light], Mat.SIGNAL + group * 6 + axis * 3 + light)
        }
      }
    }
  }

  private func buildTerrain() {
    let edge: Float = 3000
    terrain.quad(-edge, 0, edge, edge, 0, edge, edge, 0, -edge, -edge, 0, -edge, 0xFFFFFF, Mat.GROUND)
    for k in 0..<56 {
      let a = Float(k) * 2 * PI_F / 56
      let distance = between(850, 1000)
      let color = k % 3 == 0 ? 0x8FA592 : 0xA8B7A0
      let r0 = between(90, 150), height = between(20, 65)
      terrain.cylinder(cos(a) * distance, -2, sin(a) * distance, r0, height, 12, 12, color, Mat.of(.grass))
    }
    for _ in 0..<500 {
      let a = between(0, 2 * PI_F), distance = between(670, 800)
      let x = cos(a) * distance, z = sin(a) * distance
      let h = between(5, 10)
      terrain.cylinder(x, 0, z, h * 0.45, h, 0.1, 5, leafColors[random.nextInt(leafColors.count)], Mat.of(.leaves))
    }
  }

  private static func cell(_ v: Float) -> Int { Int(floor((v + HALF) / PITCH)).clamped(0, BLOCKS - 1) }

  func chunkIndex(_ x: Float, _ z: Float) -> Int { C.cell(z) * C.BLOCKS + C.cell(x) }

  /** Highest surface under a point that is no higher than `below`. */
  func support(_ x: Float, _ z: Float, _ below: Float, _ radius: Float) -> Float {
    var best: Float = 0
    for j in C.cell(z - 12)...C.cell(z + 12) {
      for i in C.cell(x - 12)...C.cell(x + 12) {
        let c = chunks[j * C.BLOCKS + i]
        c.boxes.withUnsafeBufferPointer { b in
          var k = 0
          while k < b.count {
            let top = b[k + 4]
            if top > best && top <= below && x + radius > b[k] && x - radius < b[k + 3] && z + radius > b[k + 2] && z - radius < b[k + 5] { best = top }
            k += 6
          }
        }
      }
    }
    return best
  }

  /** Distance along a normalized ray to the first solid, or `range`; writes the surface normal. */
  func raycast(_ ox: Float, _ oy: Float, _ oz: Float, _ dx: Float, _ dy: Float, _ dz: Float, _ range: Float,
               _ normal: inout [Float], opaqueOnly: Bool = false) -> Float {
    var best = range
    let ex = ox + dx * range, ez = oz + dz * range
    let o = [ox, oy, oz], d = [dx, dy, dz]
    for j in C.cell(min(oz, ez) - 12)...C.cell(max(oz, ez) + 12) {
      for i in C.cell(min(ox, ex) - 12)...C.cell(max(ox, ex) + 12) {
        let c = chunks[j * C.BLOCKS + i]
        if min(oy, oy + dy * range) > c.top { continue }
        for box in 0..<c.count {
          if opaqueOnly && !c.blocksView[box] { continue }
          let k = box * 6
          var near: Float = 0, far = best, hitAxis = -1, hit = true
          for axis in 0...2 {
            let low = c.boxes[k + axis], high = c.boxes[k + axis + 3]
            if abs(d[axis]) < 1e-6 {
              if o[axis] < low || o[axis] > high { hit = false; break }
            } else {
              let enter = ((d[axis] > 0 ? low : high) - o[axis]) / d[axis]
              let leave = ((d[axis] > 0 ? high : low) - o[axis]) / d[axis]
              if enter > near { near = enter; hitAxis = axis }
              far = min(far, leave)
              if near > far { hit = false; break }
            }
          }
          if hit && hitAxis >= 0 && near < best {
            best = near
            normal[0] = hitAxis == 0 ? -(dx > 0 ? 1 : dx < 0 ? -1 : 0) : 0
            normal[1] = hitAxis == 1 ? -(dy > 0 ? 1 : dy < 0 ? -1 : 0) : 0
            normal[2] = hitAxis == 2 ? -(dz > 0 ? 1 : dz < 0 ? -1 : 0) : 0
          }
        }
      }
    }
    return best
  }

  func raycast(_ ox: Float, _ oy: Float, _ oz: Float, _ dx: Float, _ dy: Float, _ dz: Float, _ range: Float, opaqueOnly: Bool = false) -> Float {
    var unused: [Float] = [0, 0, 0]
    return raycast(ox, oy, oz, dx, dy, dz, range, &unused, opaqueOnly: opaqueOnly)
  }
}
