import Foundation

/// One layer of the texture array each; width and height are the meters one tile covers (Textures.kt).
enum Tex: Int, CaseIterable {
  case asphalt, paving, concrete, grass, brick, brickFlats, plasterFlats, office, curtainWall, stoneTower
  case shops, roof, roofTiles, bark, leaves, carbon, deck, metal

  var width: Float { [4, 3, 4, 3.5, 2, 6, 6, 6, 6, 6, 12, 6, 2.4, 1.2, 2.2, 0.16, 2.4, 2][rawValue] }
  var height: Float { [4, 3, 4, 3.5, 2, 6.6, 6.6, 6.6, 6.6, 6.6, 3.3, 6, 2.4, 1.6, 2.2, 0.16, 2.4, 2][rawValue] }
}

/// Paints every surface in code on first use, exactly as the Android build does.
enum Textures {
  static let SIZE = 512

  struct Pixels {
    let color: [UInt8]   // RGBA, layer after layer
    let normal: [UInt8]  // RG
  }

  private static let lock = NSLock()
  private static var cache: Pixels?

  static func get() -> Pixels {
    lock.lock(); defer { lock.unlock() }
    if let cache { return cache }
    let built = build()
    cache = built
    return built
  }

  static func prepare() {
    DispatchQueue.global(qos: .userInitiated).async { _ = get() }
  }

  private static func build() -> Pixels {
    let layers = Tex.allCases
    let n = SIZE * SIZE
    var color = [UInt8](repeating: 0, count: layers.count * n * 4)
    var normal = [UInt8](repeating: 0, count: layers.count * n * 2)
    color.withUnsafeMutableBufferPointer { c in
      normal.withUnsafeMutableBufferPointer { nb in
        let cBase = c.baseAddress!, nBase = nb.baseAddress!
        DispatchQueue.concurrentPerform(iterations: layers.count) { index in
          let painter = Painter(layers[index])
          painter.paint(into: cBase + index * n * 4, normals: nBase + index * n * 2)
        }
      }
    }
    return Pixels(color: color, normal: normal)
  }
}

private final class Painter {
  let n = Textures.SIZE
  let tex: Tex
  var u: Float = 0, v: Float = 0, x: Float = 0, y: Float = 0
  var r: Float = 0, g: Float = 0, b: Float = 0, gloss: Float = 0, depth: Float = 0

  init(_ tex: Tex) { self.tex = tex }

  func paint(into color: UnsafeMutablePointer<UInt8>, normals: UnsafeMutablePointer<UInt8>) {
    var relief = [Float](repeating: 0, count: n * n)
    for row in 0..<n {
      for column in 0..<n {
        u = (Float(column) + 0.5) / Float(n); v = (Float(row) + 0.5) / Float(n)
        x = u * tex.width; y = v * tex.height
        gloss = 0; depth = 0
        pixel()
        let i = row * n + column
        relief[i] = depth
        color[i * 4] = byte(r); color[i * 4 + 1] = byte(g); color[i * 4 + 2] = byte(b); color[i * 4 + 3] = byte(gloss)
      }
    }
    let stepX = 2 * tex.width / Float(n), stepY = 2 * tex.height / Float(n)
    for row in 0..<n {
      for column in 0..<n {
        let dx = (relief[row * n + (column + 1) % n] - relief[row * n + (column + n - 1) % n]) / stepX
        let dy = (relief[(row + 1) % n * n + column] - relief[(row + n - 1) % n * n + column]) / stepY
        let length = sqrt(dx * dx + dy * dy + 1)
        let i = (row * n + column) * 2
        normals[i] = byte(0.5 - 0.5 * dx / length)
        normals[i + 1] = byte(0.5 - 0.5 * dy / length)
      }
    }
  }

  @inline(__always) func byte(_ value: Float) -> UInt8 { UInt8(Int(value.clamped(0, 1) * 255 + 0.5)) }

  func pixel() {
    switch tex {
    case .asphalt: asphalt()
    case .paving: paving()
    case .concrete: concrete()
    case .grass: grass()
    case .brick: brickPattern()
    case .brickFlats: brickFlats()
    case .plasterFlats: plasterFlats()
    case .office: office()
    case .curtainWall: curtainWall()
    case .stoneTower: stoneTower()
    case .shops: shops()
    case .roof: roof()
    case .roofTiles: roofTiles()
    case .bark: bark()
    case .leaves: leaves()
    case .carbon: carbon()
    case .deck: deck()
    case .metal: metal()
    }
  }

  func rgb(_ color: Int, _ light: Float = 1) {
    r = Float((color >> 16) & 255) / 255 * light
    g = Float((color >> 8) & 255) / 255 * light
    b = Float(color & 255) / 255 * light
  }

  func mixTo(_ color: Int, _ amount: Float) {
    r += (Float((color >> 16) & 255) / 255 - r) * amount
    g += (Float((color >> 8) & 255) / 255 - g) * amount
    b += (Float(color & 255) / 255 - b) * amount
  }

  func shade(_ amount: Float) { r *= amount; g *= amount; b *= amount }

  /// Same integer hash as Kotlin, with Int32 wrap-around.
  func hash(_ i: Int, _ j: Int, _ seed: Int) -> Float {
    var h = Int32(truncatingIfNeeded: i) &* 374761393 &+ Int32(truncatingIfNeeded: j) &* 668265263 &+ Int32(truncatingIfNeeded: seed) &* 1442695041
    h = (h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 13)) &* 1274126177
    return Float((h ^ Int32(bitPattern: UInt32(bitPattern: h) >> 16)) & 0xffff) / 65536
  }

  func noise(_ fx: Int, _ fy: Int, _ seed: Int) -> Float {
    let px = u * Float(fx), py = v * Float(fy)
    let ix = Int(px), iy = Int(py)
    var tx = px - Float(ix), ty = py - Float(iy)
    tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
    let x0 = ix % fx, x1 = (ix + 1) % fx, y0 = iy % fy, y1 = (iy + 1) % fy
    let a = hash(x0, y0, seed), bb = hash(x1, y0, seed), c = hash(x0, y1, seed), d = hash(x1, y1, seed)
    let low = a + (bb - a) * tx
    return low + (c + (d - c) * tx - low) * ty
  }

  func fbm(_ f: Int, _ seed: Int) -> Float {
    noise(f, f, seed) * 0.55 + noise(f * 2, f * 2, seed + 1) * 0.3 + noise(f * 4, f * 4, seed + 2) * 0.15
  }

  func inBox(_ px: Float, _ py: Float, _ left: Float, _ bottom: Float, _ right: Float, _ top: Float) -> Bool {
    px >= left && px < right && py >= bottom && py < top
  }

  func asphalt() {
    let grain = noise(170, 170, 1), stones = noise(90, 90, 2), wear = fbm(4, 3)
    rgb(0x565B60, 0.78 + grain * 0.26 + (wear - 0.5) * 0.22)
    if stones > 0.8 { mixTo(0x9A9C98, (stones - 0.8) * 3) }
    let crack = abs(fbm(5, 9) - 0.5)
    if crack < 0.006 && noise(3, 3, 12) > 0.58 { shade(0.62) }
    depth = grain * 0.006
  }

  func paving() {
    let size = tex.width / 5
    let i = Int(x / size), j = Int(y / size)
    let lx = x - Float(i) * size, ly = y - Float(j) * size
    let edge = min(min(lx, size - lx), min(ly, size - ly))
    let grain = noise(150, 150, 20)
    if edge < 0.012 {
      rgb(0x6E6A63); depth = -0.008
    } else {
      rgb(0xB9B3A8, 0.82 + hash(i, j, 21) * 0.17 + grain * 0.1 + (fbm(5, 22) - 0.5) * 0.18)
      if edge < 0.03 { shade(0.93) }
      depth = grain * 0.002
    }
  }

  func concrete() {
    let grain = noise(140, 140, 30)
    rgb(0xC4C2BB, 0.8 + fbm(4, 31) * 0.2 + grain * 0.07)
    if noise(70, 70, 33) > 0.9 { shade(0.85) }
    depth = grain * 0.003
  }

  func grass() {
    let blades = noise(190, 90, 40), clumps = fbm(9, 41), dry = fbm(3, 45)
    rgb(0x55803A, 0.62 + blades * 0.38 + clumps * 0.24)
    mixTo(0x93975A, ((dry - 0.5) * 1.6).clamped(0, 0.5))
    if noise(120, 120, 49) > 0.94 { mixTo(0xC9CF8A, 0.4) }
    depth = blades * 0.025
  }

  func brickPattern() {
    let rows = Int((tex.height / 0.11).rounded()), columns = Int((tex.width / 0.27).rounded())
    let h = tex.height / Float(rows), w = tex.width / Float(columns)
    let row = Int(y / h)
    let shifted = x + (row % 2 == 1 ? w / 2 : 0)
    let column = Int(shifted / w)
    let lx = shifted - Float(column) * w, ly = y - Float(row) * h
    let grain = noise(180, 180, 50)
    if lx < 0.018 || ly < 0.018 {
      rgb(0xC3BBAE, 0.85 + grain * 0.15); depth = -0.008
    } else {
      let pick = hash(column % columns, row, 51)
      rgb(pick < 0.3 ? 0x9A5A45 : pick < 0.8 ? 0xA96A50 : 0x86503F, 0.85 + pick * 0.17 + (grain - 0.5) * 0.16)
      depth = grain * 0.003
    }
  }

  func window(_ lx: Float, _ ly: Float, _ w: Float, _ h: Float, _ frame: Int, _ seed: Float, _ panes: Int) {
    let border = min(min(lx, w - lx), min(ly, h - ly))
    let pane = w / Float(panes)
    let divider = abs(lx - (lx / pane).rounded() * pane)
    if border < 0.065 || (panes > 1 && divider < 0.025) {
      rgb(frame, border < 0.02 ? 0.75 : 1)
      gloss = 0.15; depth = -0.035
      return
    }
    depth = -0.07; gloss = 0.85
    rgb(0x25323B, 0.75 + 0.5 * (1 - ly / h) + seed * 0.2)
    if seed > 0.8 { mixTo(0xC9A86A, 0.3 * ly / h + 0.1) }
    if ly < h * 0.3 && abs(lx - w * (0.3 + seed * 0.4)) < w * 0.16 { shade(0.65) }
    if seed < 0.3 {
      let drop = h * (0.25 + seed * 2)
      if ly > h - drop {
        rgb(0xB5B2A6, Int((h - ly) / 0.07) % 2 == 0 ? 0.95 : 0.78)
        gloss = 0.45
      }
    } else if seed < 0.62 {
      if min(lx, w - lx) < w * 0.22 {
        rgb(seed < 0.45 ? 0xC8B9A4 : 0x7C8FA0, 0.8 + 0.2 * sin(lx * 45))
        gloss = 0.45
      }
    }
    if h - ly < 0.16 { shade(0.65) }
  }

  func brickFlats() {
    let bay = Int(x / 3), floor = Int(y / 3.3)
    let lx = x - Float(bay) * 3, ly = y - Float(floor) * 3.3
    if inBox(lx, ly, 0.85, 1.0, 2.15, 2.65) {
      window(lx - 0.85, ly - 1, 1.3, 1.65, 0xEFEDE6, hash(bay, floor, 60), 2)
    } else if inBox(lx, ly, 0.72, 0.86, 2.28, 1.0) {
      rgb(0xD8D2C6); depth = 0.04
    } else if inBox(lx, ly, 0.78, 2.65, 2.22, 2.86) {
      rgb(0xCFC6B8, 0.92); depth = 0.025
    } else {
      brickPattern()
      if inBox(lx, ly, 0.75, 0.45, 2.25, 0.86) { shade(0.72 + (0.86 - ly) * 0.5) }
      if ly < 0.12 { rgb(0xBDB4A6); depth = 0.02 }
      shade(0.92 + fbm(3, 61) * 0.12)
    }
  }

  func plasterFlats() {
    let bay = Int(x / 3), floor = Int(y / 3.3)
    let lx = x - Float(bay) * 3, ly = y - Float(floor) * 3.3
    let balcony = bay == 0
    if balcony && inBox(lx, ly, 0.35, 0.2, 2.65, 1.15) {
      if ly > 1.06 || ly < 0.32 {
        rgb(ly < 0.32 ? 0xB9B6AE : 0x464C50); depth = 0.07
      } else if Int(lx / 0.07) % 2 == 0 {
        rgb(0x50575C); depth = 0.04
      } else {
        rgb(0x39434A); depth = -0.05
      }
      return
    }
    if balcony && inBox(lx, ly, 0.7, 0.32, 2.3, 2.6) {
      window(lx - 0.7, ly - 0.32, 1.6, 2.28, 0x5B6166, hash(bay, floor, 70), 2)
      return
    }
    if !balcony && inBox(lx, ly, 0.9, 1.05, 2.1, 2.5) {
      window(lx - 0.9, ly - 1.05, 1.2, 1.45, 0xF4F2EC, hash(bay, floor, 70), 2)
      return
    }
    if !balcony && inBox(lx, ly, 2.2, 0.55, 2.8, 0.98) {
      rgb(0xB4B8B7, Int(ly / 0.06) % 2 == 0 ? 1 : 0.7)
      depth = 0.07
      return
    }
    let grain = noise(160, 160, 72)
    rgb(0xE6DFD2, 0.86 + fbm(4, 73) * 0.13 + grain * 0.04)
    if noise(40, 3, 74) > 0.72 { shade(0.94) }
    if inBox(lx, ly, 0.35, 0, 2.65, 0.2) && balcony { shade(0.7) }
    if !balcony && inBox(lx, ly, 0.85, 0.95, 2.15, 1.05) {
      rgb(0xC9C5BC); depth = 0.04
    } else {
      depth = grain * 0.003
    }
  }

  func office() {
    let bay = Int(x / 1.5), floor = Int(y / 3.3)
    let lx = x - Float(bay) * 1.5, ly = y - Float(floor) * 3.3
    let pillar = bay % 2 == 0 && lx < 0.22
    if !pillar && ly >= 0.95 && ly < 2.9 {
      let start: Float = bay % 2 == 0 ? 0.22 : 0
      window(lx - start, ly - 0.95, 1.5 - start, 1.95, 0x41484E, hash(bay, floor, 80), 1)
      return
    }
    let grain = noise(170, 170, 82)
    rgb(0xD2CFC6, 0.84 + fbm(4, 83) * 0.13 + grain * 0.05)
    depth = pillar ? 0.06 : grain * 0.003
    if !pillar && ly >= 0.45 && ly <= 0.48 { shade(0.7); depth = -0.01 }
  }

  func curtainWall() {
    let bay = Int(x / 1.5), floor = Int(y / 3.3)
    let lx = x - Float(bay) * 1.5, ly = y - Float(floor) * 3.3
    if lx < 0.07 || ly < 0.09 || (ly >= 0.8 && ly < 0.86) {
      rgb(0xA9B4BB); gloss = 0.4; depth = 0.035
    } else if ly < 0.8 {
      rgb(0x3C4E5A); gloss = 0.6
    } else {
      let seed = hash(bay, floor, 90)
      rgb(0x28465A, 0.85 + seed * 0.3 + 0.2 * (ly - 0.86) / 2.44)
      if seed < 0.2 && ly > 3.3 - 2 * (0.2 + seed * 3) { mixTo(0xAEB4B2, 0.45) }
      if seed > 0.85 && ly > 3.0 { mixTo(0xF5E2B0, 0.7) }
      if ly < 1.5 && hash(Int(x / 0.5), floor, 91) > 0.7 { shade(0.78) }
      gloss = 0.95; depth = -0.02
    }
  }

  func stoneTower() {
    let bay = Int(x / 1.5), floor = Int(y / 3.3)
    let lx = x - Float(bay) * 1.5, ly = y - Float(floor) * 3.3
    if inBox(lx, ly, 0.33, 0.65, 1.17, 2.85) {
      window(lx - 0.33, ly - 0.65, 0.84, 2.2, 0x6A6458, hash(bay, floor, 100), 1)
      return
    }
    let row = Int(y / 0.55)
    let shifted = x + (row % 2 == 1 ? 0.375 : 0)
    let column = Int(shifted / 0.75)
    let grain = noise(150, 150, 102)
    rgb(0xCFC1A8, 0.83 + hash(column % 8, row, 101) * 0.12 + grain * 0.07)
    if shifted - Float(column) * 0.75 < 0.014 || y - Float(row) * 0.55 < 0.014 {
      shade(0.72); depth = -0.008
    } else {
      depth = grain * 0.004
    }
  }

  func shops() {
    let shop = Int(x / 3)
    let lx = x - Float(shop) * 3
    let brand = [0x1F5F8B, 0xA33A2E, 0x2F6B45, 0x3A3F47][min(shop, 3)]
    if lx < 0.14 || lx > 2.86 {
      rgb(0x8D8A83, 0.9 + noise(90, 90, 110) * 0.15); depth = 0.05
    } else if y > 2.65 {
      rgb(brand); depth = 0.07
      if inBox(lx, y, 0.5, 2.82, 2.5, 3.12) {
        let letter = Int((lx - 0.5) / 0.25)
        let inside = lx - 0.5 - Float(letter) * 0.25
        if inside < 0.18 && hash(shop, letter, 111) > 0.15 {
          let hole = inside >= 0.06 && inside <= 0.12 && (y - 2.82) >= 0.07 && (y - 2.82) <= 0.22
          if !hole { rgb(0xF4F1E6) }
        }
      }
    } else if y > 2.2 {
      let stripe = Int((lx - 0.14) / 0.3) % 2 == 0
      rgb(stripe ? brand : 0xE9E4D8, 0.7 + (y - 2.2) * 0.8)
      depth = 0.12 - (y - 2.2) * 0.1
    } else if y < 0.22 {
      rgb(0x4B4D4E)
    } else if shop == 3 && lx < 1.9 {
      rgb(0x9BA1A4, 0.75 + 0.25 * sin(y * 45)); depth = sin(y * 45) * 0.006; gloss = 0.2
    } else if inBox(lx, y, 2.0, 0.22, 2.75, 2.1) {
      if inBox(lx, y, 2.1, 0.5, 2.65, 1.95) { rgb(0x2B3A42); gloss = 0.8 } else { rgb(brand, 0.75) }
      if inBox(lx, y, 2.12, 1.1, 2.17, 1.3) { rgb(0xD8D8D0) }
    } else {
      rgb(0x2A3238, 0.9 + (2.2 - y) * 0.2); gloss = 0.7; depth = -0.05
      let shelf = Int(y / 0.45), item = Int(x / 0.22)
      if (1...3).contains(shelf) && y - Float(shelf) * 0.45 < 0.1 + hash(item, shelf, 112) * 0.2 {
        rgb(0x8A6F52, 0.5 + hash(item, shelf, 113) * 0.9)
        if hash(item, shelf, 114) > 0.6 { mixTo(brand, 0.6) }
      }
      if abs(lx - 0.17) < 0.04 || abs(lx - 1.95) < 0.05 || abs(y - 2.17) < 0.04 { rgb(0x50565B) }
    }
  }

  func roof() {
    let stones = noise(200, 200, 120), dirt = fbm(3, 121)
    rgb(0x8B8A85, 0.62 + stones * 0.35 + dirt * 0.2)
    depth = stones * 0.01
    if fmodf(x, 2) < 0.05 { shade(0.75); depth = 0.012 }
    if fbm(5, 125) > 0.74 { shade(0.85) }
  }

  func roofTiles() {
    let row = Int(y / 0.3)
    let shifted = x + (row % 2 == 1 ? 0.12 : 0)
    let column = Int(shifted / 0.24)
    let lx = (shifted - Float(column) * 0.24) / 0.24, ly = (y - Float(row) * 0.3) / 0.3
    let curve = sin(lx * PI_F)
    rgb(0xD9D2CB, (0.62 + curve * 0.3 + hash(column % 10, row, 130) * 0.14) * (ly > 0.88 ? 0.6 : 1))
    depth = curve * 0.02 + (1 - ly) * 0.025
  }

  func bark() {
    let ridges = noise(28, 5, 140) * 0.7 + noise(70, 14, 141) * 0.3
    rgb(0x6E5844, 0.55 + ridges * 0.65)
    depth = ridges * 0.03
  }

  func leaves() {
    let small = noise(80, 80, 150), clump = noise(17, 17, 151), mass = fbm(5, 152)
    let light = small * 0.45 + clump * 0.4 + mass * 0.35
    rgb(0xB9D19A, 0.2 + light * 0.95)
    if small > 0.83 { mixTo(0xE6F0BE, 0.35) }
    depth = small * 0.05 + clump * 0.08
  }

  func carbon() {
    let i = Int(u * 16), j = Int(v * 16)
    let across = (i + j) % 4 < 2
    let f = across ? u * 16 - Float(i) : v * 16 - Float(j)
    rgb(0x3A4046, 0.6 + 0.55 * sin(f * PI_F) + (across ? 0.1 : 0))
    gloss = 0.35
    depth = sin(f * PI_F) * 0.0006
  }

  func deck() {
    let size = tex.width / 2
    let lx = fmodf(x, size), ly = fmodf(y, size)
    let grain = noise(170, 170, 170)
    rgb(0x4A5D68, 0.8 + grain * 0.15 + fbm(4, 171) * 0.2)
    depth = grain * 0.003
    if lx < 0.015 || ly < 0.015 { shade(0.6); depth = -0.008 }
    let bx = min(lx, size - lx) - 0.1, by = min(ly, size - ly) - 0.1
    if bx * bx + by * by < 0.0012 { rgb(0x93A0A6); depth = 0.01; gloss = 0.4 }
  }

  func metal() {
    let wave = sin(u * 2 * PI_F * 18)
    let streak = noise(90, 4, 180)
    rgb(0xB6BCBE, 0.72 + wave * 0.07 + streak * 0.14 + fbm(4, 181) * 0.1)
    if noise(12, 12, 185) > 0.86 { mixTo(0x8A6A50, 0.3) }
    depth = wave * 0.006
    gloss = 0.2
  }
}
