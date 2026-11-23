import Foundation

/** Material ids travel in the alpha byte of each vertex color (see MeshBuilder.kt). */
enum Mat {
  static let PLAIN = 0, GLOSSY = 1, GLASS = 2, GROUND = 3, EMISSIVE = 4, TINT = 5, WATER = 6, STROBE = 7
  static let SIGNAL = 10
  static let TEXTURED = 32
  static func of(_ tex: Tex) -> Int { TEXTURED + tex.rawValue }
}

/// Packs non-indexed triangles into five 32-bit words per vertex: position, normal + face, color + material.
final class MeshBuilder {
  private(set) var data: [UInt32] = []
  var vertices: Int { data.count / 5 }

  init() { data.reserveCapacity(4096) }

  private static func packByte(_ v: Float) -> UInt32 { UInt32(bitPattern: Int32(floor(v * 127 + 0.5))) & 255 }

  private static func face(_ nx: Float, _ ny: Float, _ nz: Float) -> UInt32 {
    let x = abs(nx), y = abs(ny), z = abs(nz)
    if y >= x && y >= z { return ny >= 0 ? 4 : 5 }
    if x >= z { return nx >= 0 ? 0 : 1 }
    return nz >= 0 ? 2 : 3
  }

  private static func packNormal(_ nx: Float, _ ny: Float, _ nz: Float, _ face: UInt32) -> UInt32 {
    packByte(nx) | (packByte(ny) << 8) | (packByte(nz) << 16) | (face << 24)
  }

  private static func packColor(_ color: Int, _ material: Int) -> UInt32 {
    UInt32((color >> 16) & 255) | UInt32(color & 0xff00) | (UInt32(color & 255) << 16) | (UInt32(material) << 24)
  }

  @inline(__always) private func vertex(_ x: Float, _ y: Float, _ z: Float, _ normal: UInt32, _ color: UInt32) {
    data.append(x.bitPattern); data.append(y.bitPattern); data.append(z.bitPattern)
    data.append(normal); data.append(color)
  }

  func triangle(_ ax: Float, _ ay: Float, _ az: Float, _ bx: Float, _ by: Float, _ bz: Float,
                _ cx: Float, _ cy: Float, _ cz: Float, _ color: Int, _ material: Int = Mat.PLAIN) {
    let ux = bx - ax, uy = by - ay, uz = bz - az
    let vx = cx - ax, vy = cy - ay, vz = cz - az
    var nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx
    let length = sqrt(nx * nx + ny * ny + nz * nz)
    if length < 1e-7 { return }
    nx /= length; ny /= length; nz /= length
    let normal = MeshBuilder.packNormal(nx, ny, nz, MeshBuilder.face(nx, ny, nz))
    let packed = MeshBuilder.packColor(color, material)
    vertex(ax, ay, az, normal, packed)
    vertex(bx, by, bz, normal, packed)
    vertex(cx, cy, cz, normal, packed)
  }

  func quad(_ ax: Float, _ ay: Float, _ az: Float, _ bx: Float, _ by: Float, _ bz: Float,
            _ cx: Float, _ cy: Float, _ cz: Float, _ dx: Float, _ dy: Float, _ dz: Float,
            _ color: Int, _ material: Int = Mat.PLAIN) {
    triangle(ax, ay, az, bx, by, bz, cx, cy, cz, color, material)
    triangle(ax, ay, az, cx, cy, cz, dx, dy, dz, color, material)
  }

  func box(_ x0: Float, _ y0: Float, _ z0: Float, _ x1: Float, _ y1: Float, _ z1: Float, _ color: Int,
           _ material: Int = Mat.PLAIN, top: Int? = nil, topMaterial: Int? = nil, bottom: Bool = true) {
    quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color, material)
    quad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color, material)
    quad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color, material)
    quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color, material)
    quad(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, top ?? color, topMaterial ?? material)
    if bottom { quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color, material) }
  }

  func centered(_ x: Float, _ y: Float, _ z: Float, _ w: Float, _ h: Float, _ d: Float, _ color: Int, _ material: Int = Mat.PLAIN) {
    box(x - w / 2, y - h / 2, z - d / 2, x + w / 2, y + h / 2, z + d / 2, color, material)
  }

  func cylinder(_ x: Float, _ y0: Float, _ z: Float, _ r0: Float, _ y1: Float, _ r1: Float, _ sides: Int,
                _ color: Int, _ material: Int = Mat.PLAIN, cap: Bool = true, capMaterial: Int? = nil) {
    let rise = y1 - y0
    let length = hypot(rise, r0 - r1)
    let nh = rise / length, ny = (r0 - r1) / length
    let packed = MeshBuilder.packColor(color, material)
    for i in 0..<sides {
      let a = Float(i) * 2 * PI_F / Float(sides), b = Float(i + 1) * 2 * PI_F / Float(sides)
      let ca = cos(a), sa = sin(a), cb = cos(b), sb = sin(b)
      if sides >= 5 {
        let middle = (a + b) / 2
        let face = MeshBuilder.face(cos(middle) * nh, ny, sin(middle) * nh)
        let na = MeshBuilder.packNormal(ca * nh, ny, sa * nh, face), nb = MeshBuilder.packNormal(cb * nh, ny, sb * nh, face)
        vertex(x + ca * r0, y0, z + sa * r0, na, packed)
        vertex(x + ca * r1, y1, z + sa * r1, na, packed)
        vertex(x + cb * r1, y1, z + sb * r1, nb, packed)
        vertex(x + ca * r0, y0, z + sa * r0, na, packed)
        vertex(x + cb * r1, y1, z + sb * r1, nb, packed)
        vertex(x + cb * r0, y0, z + sb * r0, nb, packed)
      } else {
        quad(x + ca * r0, y0, z + sa * r0, x + ca * r1, y1, z + sa * r1, x + cb * r1, y1, z + sb * r1, x + cb * r0, y0, z + sb * r0, color, material)
      }
      if cap { triangle(x, y1, z, x + cb * r1, y1, z + sb * r1, x + ca * r1, y1, z + sa * r1, color, capMaterial ?? material) }
    }
  }

  func axle(_ x0: Float, _ x1: Float, _ y: Float, _ z: Float, _ radius: Float, _ sides: Int, _ color: Int, _ hub: Int, _ material: Int = Mat.PLAIN) {
    let packed = MeshBuilder.packColor(color, material)
    for i in 0..<sides {
      let a = Float(i) * 2 * PI_F / Float(sides), b = Float(i + 1) * 2 * PI_F / Float(sides)
      let ca = cos(a), sa = sin(a), cb = cos(b), sb = sin(b)
      let face = MeshBuilder.face(0, cos((a + b) / 2), sin((a + b) / 2))
      let na = MeshBuilder.packNormal(0, ca, sa, face), nb = MeshBuilder.packNormal(0, cb, sb, face)
      vertex(x0, y + ca * radius, z + sa * radius, na, packed)
      vertex(x1, y + cb * radius, z + sb * radius, nb, packed)
      vertex(x1, y + ca * radius, z + sa * radius, na, packed)
      vertex(x0, y + ca * radius, z + sa * radius, na, packed)
      vertex(x0, y + cb * radius, z + sb * radius, nb, packed)
      vertex(x1, y + cb * radius, z + sb * radius, nb, packed)
      triangle(x1, y, z, x1, y + ca * radius, z + sa * radius, x1, y + cb * radius, z + sb * radius, hub)
      triangle(x0, y, z, x0, y + cb * radius, z + sb * radius, x0, y + ca * radius, z + sa * radius, hub)
    }
  }

  func ellipsoid(_ x: Float, _ y: Float, _ z: Float, _ rx: Float, _ ry: Float, _ rz: Float, _ sides: Int, _ rings: Int,
                 _ color: Int, _ material: Int = Mat.PLAIN, bumps: Float = 0) {
    let packed = MeshBuilder.packColor(color, material)
    func point(_ i: Int, _ j: Int, _ face: UInt32) {
      let around = Float(i % sides) * 2 * PI_F / Float(sides)
      let down = Float(j) * PI_F / Float(rings)
      let nx = cos(around) * sin(down), ny = cos(down), nz = sin(around) * sin(down)
      let swell = 1 + bumps * sin(Float(i % sides) * 2.4 + x) * sin(Float(j) * 1.9 + z) * sin(down)
      vertex(x + nx * rx * swell, y + ny * ry * swell, z + nz * rz * swell, MeshBuilder.packNormal(nx, ny, nz, face), packed)
    }
    for j in 0..<rings {
      for i in 0..<sides {
        let around = (Float(i) + 0.5) * 2 * PI_F / Float(sides), down = (Float(j) + 0.5) * PI_F / Float(rings)
        let face = MeshBuilder.face(cos(around) * sin(down), cos(down), sin(around) * sin(down))
        if j > 0 { point(i, j, face); point(i + 1, j, face); point(i + 1, j + 1, face) }
        if j < rings - 1 { point(i, j, face); point(i + 1, j + 1, face); point(i, j + 1, face) }
      }
    }
  }

  func hull(_ z: [Float], _ halfWidth: [Float], _ bottom: [Float], _ shoulder: [Float], _ top: [Float],
            _ color: Int, _ material: Int, upperColor: Int? = nil, upper: Int? = nil) {
    func px(_ s: Int, _ p: Int) -> Float {
      let f: [Float] = [-0.9, 0.9, 1, 0.82, -0.82, -1]
      return halfWidth[s] * f[p % 6]
    }
    func py(_ s: Int, _ p: Int) -> Float {
      switch p % 6 { case 0, 1: return bottom[s]; case 2, 5: return shoulder[s]; default: return top[s] }
    }
    let last = z.count - 1
    for s in 0..<last {
      for p in 0...5 {
        let high = (2...4).contains(p)
        quad(px(s, p), py(s, p), z[s], px(s, p + 1), py(s, p + 1), z[s],
             px(s + 1, p + 1), py(s + 1, p + 1), z[s + 1], px(s + 1, p), py(s + 1, p), z[s + 1],
             high ? (upperColor ?? color) : color, high ? (upper ?? material) : material)
      }
    }
    for p in 1...4 {
      triangle(px(0, 0), py(0, 0), z[0], px(0, p + 1), py(0, p + 1), z[0], px(0, p), py(0, p), z[0], color, material)
      triangle(px(last, 0), py(last, 0), z[last], px(last, p), py(last, p), z[last], px(last, p + 1), py(last, p + 1), z[last], color, material)
    }
  }

  func beam(_ x0: Float, _ z0: Float, _ x1: Float, _ z1: Float, _ y: Float, _ w0: Float, _ w1: Float, _ h: Float, _ color: Int, _ material: Int = Mat.PLAIN) {
    let length = hypot(x1 - x0, z1 - z0)
    let sx = -(z1 - z0) / length, sz = (x1 - x0) / length
    let ax = x0 + sx * w0, az = z0 + sz * w0, bx = x0 - sx * w0, bz = z0 - sz * w0
    let cx = x1 - sx * w1, cz = z1 - sz * w1, dx = x1 + sx * w1, dz = z1 + sz * w1
    let t = y + h / 2, b = y - h / 2
    quad(ax, t, az, dx, t, dz, cx, t, cz, bx, t, bz, color, material)
    quad(bx, b, bz, cx, b, cz, dx, b, dz, ax, b, az, color, material)
    quad(ax, b, az, dx, b, dz, dx, t, dz, ax, t, az, color, material)
    quad(cx, b, cz, bx, b, bz, bx, t, bz, cx, t, cz, color, material)
  }
}
