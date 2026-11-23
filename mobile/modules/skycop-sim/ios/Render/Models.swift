import Foundation

/// Swift port of Models.kt. Local -Z is forward.
enum Models {
  static let MOTOR_X: [Float] = [-0.48, 0.48, -0.5, 0.5]
  static let MOTOR_Z: [Float] = [-0.4, -0.4, 0.42, 0.42]
  static let MOTOR_Y: [Float] = [0.12, 0.12, 0.16, 0.16]
  private static let BODY = 0xE3E6E8, BLUE = 0x1F5FA8, TINTED = 0xFFFFFF, WINDOW = 0x1B2730, TIRE = 0x17191B

  static func drone() -> MeshBuilder {
    let m = MeshBuilder()
    let carbon = Mat.of(.carbon)
    m.hull([-0.4, -0.33, -0.2, 0.12, 0.27, 0.37], [0.06, 0.125, 0.175, 0.175, 0.135, 0.07],
           [-0.03, -0.075, -0.1, -0.1, -0.08, -0.03], [-0.005, 0, 0.01, 0.02, 0.015, 0.01],
           [0.02, 0.065, 0.105, 0.12, 0.1, 0.05], 0xC8C8C8, carbon, upperColor: BODY, upper: Mat.GLOSSY)
    m.box(-0.177, -0.005, -0.15, -0.17, 0.03, 0.14, BLUE, Mat.GLOSSY)
    m.box(0.17, -0.005, -0.15, 0.177, 0.03, 0.14, BLUE, Mat.GLOSSY)
    m.box(-0.1, 0.115, 0.0, 0.1, 0.15, 0.22, 0xFFFFFF, carbon)
    m.ellipsoid(0, 0.15, 0.12, 0.075, 0.04, 0.075, 14, 6, BODY, Mat.GLOSSY)
    m.box(-0.1, 0.105, -0.13, -0.01, 0.15, -0.06, 0xFF2A2A, Mat.STROBE)
    m.box(0.01, 0.105, -0.13, 0.1, 0.15, -0.06, 0x2468FF, Mat.STROBE)
    for k in 0...3 { m.box(-0.05, 0.152, 0.19 + Float(k) * 0.012, 0.05, 0.156, 0.196 + Float(k) * 0.012, 0x101315) }
    for i in 0...3 {
      let x = MOTOR_X[i], mz = MOTOR_Z[i], y = MOTOR_Y[i]
      m.beam((x < 0 ? -1 : 1) * 0.1, mz < 0 ? -0.18 : 0.17, x, mz, y - 0.09, 0.048, 0.026, 0.045, 0xFFFFFF, carbon)
      m.cylinder(x, y - 0.13, mz, 0.06, y - 0.07, 0.062, 14, 0x24282C, Mat.GLOSSY)
      m.cylinder(x, y - 0.07, mz, 0.056, y - 0.01, 0.05, 14, 0x99A3A9, Mat.GLOSSY)
      m.cylinder(x, y - 0.052, mz, 0.0575, y - 0.037, 0.0575, 14, 0xD9A23A, Mat.GLOSSY)
      m.cylinder(x, y - 0.01, mz, 0.022, y + 0.04, 0.012, 8, 0xC9D1D6, Mat.GLOSSY)
      let light = mz > 0 ? 0xFFFFFF : x < 0 ? 0xFF3030 : 0x30FF70
      m.centered(x, y - 0.145, mz, 0.06, 0.025, 0.06, light, Mat.EMISSIVE)
      m.cylinder(x, -0.29, mz, 0.014, y - 0.15, 0.024, 6, 0xFFFFFF, carbon)
      m.ellipsoid(x, -0.28, mz, 0.026, 0.02, 0.032, 6, 4, 0x15181A)
    }
    m.centered(0, -0.12, -0.3, 0.14, 0.05, 0.09, 0xFFFFFF, carbon)
    m.box(-0.095, -0.22, -0.36, -0.075, -0.12, -0.31, 0x394148, Mat.GLOSSY)
    m.box(0.075, -0.22, -0.36, 0.095, -0.12, -0.31, 0x394148, Mat.GLOSSY)
    m.centered(0, -0.2, -0.345, 0.13, 0.105, 0.12, 0x2B3136, Mat.GLOSSY)
    m.centered(0, -0.2, -0.42, 0.085, 0.075, 0.04, 0x10161B, Mat.GLOSSY)
    m.centered(0, -0.2, -0.444, 0.05, 0.045, 0.012, 0x2B7FA8, Mat.GLASS)
    return m
  }

  static func propeller() -> MeshBuilder {
    let m = MeshBuilder()
    for side: Float in [-1, 1] {
      m.beam(side * 0.02, 0, side * 0.15, side * 0.006, 0.05, 0.014, 0.027, 0.008, 0x1A1D20, Mat.GLOSSY)
      m.beam(side * 0.15, side * 0.006, side * 0.25, 0, 0.05, 0.027, 0.014, 0.008, 0x1A1D20, Mat.GLOSSY)
      m.beam(side * 0.25, 0, side * 0.275, -side * 0.006, 0.05, 0.014, 0.008, 0.009, 0xF2B544, Mat.GLOSSY)
    }
    return m
  }

  static func rotorDisc() -> MeshBuilder {
    let m = MeshBuilder()
    for i in 0..<20 {
      let a = Float(i) * 2 * PI_F / 20, b = Float(i + 1) * 2 * PI_F / 20
      m.triangle(0, 0.06, 0, cos(b) * 0.28, 0.06, sin(b) * 0.28, cos(a) * 0.28, 0.06, sin(a) * 0.28, 0xFFFFFF)
    }
    return m
  }

  /// Index order matches Traffic's type ids: car, CNG, cycle rickshaw, bus, motorbike, microbus.
  static func vehicles() -> [MeshBuilder] { [sedan(), cng(), cycleRickshaw(), bus(), motorbike(), van()] }

  private static func wheels(_ m: MeshBuilder, _ x: Float, _ front: Float, _ back: Float, _ radius: Float) {
    for sideX in [-x, x] {
      for z in [front, back] {
        m.axle(sideX - 0.12, sideX + 0.12, radius, z, radius, 10, TIRE, 0x24272A)
        let face = sideX + (sideX < 0 ? -1 : 1) * 0.125
        m.axle(face - 0.01, face + 0.01, radius, z, radius * 0.55, 8, 0xA9B0B4, 0xC3CACE, Mat.GLOSSY)
      }
    }
  }

  private static func lights(_ m: MeshBuilder, _ halfWidth: Float, _ front: Float, _ back: Float, _ y: Float) {
    for x in [-halfWidth, halfWidth] {
      m.centered(x, y, front, 0.34, 0.14, 0.04, 0xFFF6CF, Mat.EMISSIVE)
      m.centered(x, y, back, 0.34, 0.14, 0.04, 0xC9201C, Mat.EMISSIVE)
    }
  }

  private static func trim(_ m: MeshBuilder, _ halfWidth: Float, _ front: Float, _ back: Float, _ mirror: Float, _ mirrorY: Float) {
    m.centered(0, 0.56, front, 0.62, 0.14, 0.03, 0x14181B, Mat.GLOSSY)
    m.centered(0, 0.4, front, halfWidth * 1.5, 0.1, 0.07, 0x30353A)
    m.centered(0, 0.4, back, halfWidth * 1.5, 0.1, 0.07, 0x30353A)
    m.centered(0, 0.43, front - 0.04, 0.4, 0.13, 0.02, 0xF1F1EA)
    m.centered(0, 0.43, back + 0.04, 0.4, 0.13, 0.02, 0xF1F1EA)
    m.centered(-halfWidth - 0.06, mirrorY, mirror, 0.16, 0.1, 0.09, TINTED, Mat.TINT)
    m.centered(halfWidth + 0.06, mirrorY, mirror, 0.16, 0.1, 0.09, TINTED, Mat.TINT)
  }

  private static func cabin(_ m: MeshBuilder, _ w0: Float, _ w1: Float, _ y0: Float, _ y1: Float, _ f0: Float, _ f1: Float, _ b0: Float, _ b1: Float, _ roof: Int) {
    m.quad(-w0, y0, f0, -w1, y1, f1, w1, y1, f1, w0, y0, f0, WINDOW, Mat.GLASS)
    m.quad(-w0, y0, b0, w0, y0, b0, w1, y1, b1, -w1, y1, b1, WINDOW, Mat.GLASS)
    m.quad(w0, y0, b0, w0, y0, f0, w1, y1, f1, w1, y1, b1, WINDOW, Mat.GLASS)
    m.quad(-w0, y0, f0, -w0, y0, b0, -w1, y1, b1, -w1, y1, f1, WINDOW, Mat.GLASS)
    m.quad(-w1, y1, b1, w1, y1, b1, w1, y1, f1, -w1, y1, f1, roof, Mat.TINT)
    let middle = (f1 + b1) / 2
    m.box(-w0 - 0.005, y0, middle - 0.07, -w1 + 0.01, y1, middle + 0.07, TINTED, Mat.TINT, bottom: false)
    m.box(w1 - 0.01, y0, middle - 0.07, w0 + 0.005, y1, middle + 0.07, TINTED, Mat.TINT, bottom: false)
  }

  private static func saloon(_ m: MeshBuilder) {
    m.hull([-2.12, -1.95, -1.0, 1.35, 1.95, 2.12], [0.78, 0.9, 0.93, 0.93, 0.9, 0.8],
           [0.36, 0.28, 0.27, 0.27, 0.29, 0.38], [0.58, 0.66, 0.74, 0.76, 0.72, 0.62],
           [0.72, 0.82, 0.94, 0.95, 0.9, 0.78], TINTED, Mat.TINT)
    trim(m, 0.93, -2.13, 2.13, -0.95, 0.98)
    cabin(m, 0.86, 0.72, 0.92, 1.5, -1.05, -0.45, 1.4, 0.95, TINTED)
    wheels(m, 0.82, -1.35, 1.35, 0.34)
    lights(m, 0.55, -2.13, 2.13, 0.63)
  }

  private static func sedan() -> MeshBuilder { let m = MeshBuilder(); saloon(m); return m }

  private static func van() -> MeshBuilder {
    let m = MeshBuilder()
    m.hull([-2.47, -2.3, 2.47], [0.88, 1, 1], [0.4, 0.33, 0.35], [0.8, 0.9, 0.9], [1, 1.15, 1.15], TINTED, Mat.TINT)
    trim(m, 1, -2.48, 2.48, -1.8, 1.2)
    m.box(-0.98, 1.15, -0.5, 0.98, 2.2, 2.45, TINTED, Mat.TINT)
    cabin(m, 0.95, 0.88, 1.15, 2.0, -1.9, -1.3, -0.48, -0.5, TINTED)
    wheels(m, 0.9, -1.6, 1.6, 0.38)
    lights(m, 0.62, -2.48, 2.48, 0.82)
    return m
  }

  /// A Dhaka local bus: painted body with a pale band, open door, roof rack and sliding windows.
  private static func bus() -> MeshBuilder {
    let m = MeshBuilder()
    m.box(-1.25, 0.45, -5.2, 1.25, 3.1, 5.2, TINTED, Mat.TINT)
    m.box(-1.27, 1.32, -5.21, 1.27, 1.52, 5.21, 0xF2EEE2)
    for x: Float in [-1.26, 1.26] { for k in 0...6 { m.centered(x, 2.2, -4.1 + Float(k) * 1.3, 0.03, 0.95, 1.1, WINDOW, Mat.GLASS) } }
    m.centered(1.265, 1.55, -3.9, 0.03, 2.1, 0.9, 0x15191C)
    m.centered(0, 2.15, -5.215, 2.25, 1.25, 0.03, WINDOW, Mat.GLASS)
    m.centered(0, 2.95, -5.22, 1.5, 0.24, 0.03, 0x101214)
    m.centered(0, 2.95, -5.235, 1.3, 0.12, 0.02, 0xF0C030, Mat.EMISSIVE)
    m.centered(0, 2.3, 5.215, 1.9, 0.8, 0.03, WINDOW, Mat.GLASS)
    for k in -2...2 { m.centered(0, 3.17, Float(k) * 1.6, 2.1, 0.06, 0.06, 0x3A3D40) }
    wheels(m, 1.15, -3.4, 3.5, 0.5)
    lights(m, 0.9, -5.22, 5.22, 0.85)
    return m
  }

  /// A rider or a rickshaw puller: shirt, trousers, head, and a helmet for the bikers.
  private static func person(_ m: MeshBuilder, _ x: Float, _ seat: Float, _ z: Float, _ shirt: Int, _ helmet: Bool) {
    m.centered(x, seat + 0.32, z, 0.36, 0.6, 0.24, shirt)
    m.centered(x - 0.1, seat - 0.05, z - 0.2, 0.13, 0.18, 0.42, 0x2B2F3A)
    m.centered(x + 0.1, seat - 0.05, z - 0.2, 0.13, 0.18, 0.42, 0x2B2F3A)
    m.ellipsoid(x, seat + 0.78, z, 0.11, 0.13, 0.12, 6, 4, helmet ? 0x1C1E22 : 0x8C5A3C)
    m.centered(x - 0.22, seat + 0.42, z - 0.25, 0.08, 0.08, 0.5, shirt)
    m.centered(x + 0.22, seat + 0.42, z - 0.25, 0.08, 0.08, 0.5, shirt)
  }

  /// A CNG auto-rickshaw: green three-wheeler with a canopy and caged doors.
  private static func cng() -> MeshBuilder {
    let m = MeshBuilder()
    m.box(-0.64, 0.28, -0.55, 0.64, 0.82, 1.25, TINTED, Mat.TINT)
    m.box(-0.42, 0.28, -1.35, 0.42, 1.05, -0.55, TINTED, Mat.TINT)
    m.quad(-0.4, 1.05, -1.3, 0.4, 1.05, -1.3, 0.6, 1.62, -0.95, -0.6, 1.62, -0.95, WINDOW, Mat.GLASS)
    m.box(-0.68, 1.62, -1.0, 0.68, 1.74, 1.3, TINTED, Mat.TINT)
    for x: Float in [-0.64, 0.64] { for z: Float in [-0.95, -0.2, 0.55, 1.25] { m.centered(x, 1.22, z, 0.05, 0.8, 0.05, 0x2C3034) } }
    for x: Float in [-0.645, 0.645] {
      for k in 0...6 { m.centered(x, 1.22, -0.15 + Float(k) * 0.2, 0.02, 0.78, 0.025, 0x3E4246) }
      for k in 0...2 { m.centered(x, 0.95 + Float(k) * 0.27, 0.45, 0.02, 0.025, 1.3, 0x3E4246) }
    }
    m.box(-0.62, 0.82, 0.85, 0.62, 1.62, 1.27, 0x1E2124)
    person(m, 0, 0.85, -0.35, 0xE8E2D2, false)
    m.axle(-0.07, 0.07, 0.22, -1.05, 0.22, 8, TIRE, 0x8E969B)
    m.axle(-0.7, -0.56, 0.22, 0.85, 0.22, 8, TIRE, 0x8E969B)
    m.axle(0.56, 0.7, 0.22, 0.85, 0.22, 8, TIRE, 0x8E969B)
    m.centered(0, 0.85, -1.365, 0.2, 0.14, 0.03, 0xFFF6CF, Mat.EMISSIVE)
    m.centered(-0.45, 0.6, 1.26, 0.12, 0.1, 0.03, 0xC9201C, Mat.EMISSIVE)
    m.centered(0.45, 0.6, 1.26, 0.12, 0.1, 0.03, 0xC9201C, Mat.EMISSIVE)
    return m
  }

  /// A cycle rickshaw: painted hood, cushioned seat, decorated back panel and its puller.
  private static func cycleRickshaw() -> MeshBuilder {
    let m = MeshBuilder()
    m.centered(0, 0.5, -0.25, 0.06, 0.06, 1.4, 0x2C3034)
    m.centered(0, 0.85, -0.75, 0.06, 0.5, 0.06, 0x2C3034)
    m.centered(0, 1.05, -0.82, 0.5, 0.04, 0.04, 0x2C3034)
    m.box(-0.5, 0.55, 0.25, 0.5, 0.68, 0.95, 0x5A3A22)
    m.box(-0.5, 0.68, 0.3, 0.5, 0.86, 0.85, 0xC0303A)
    m.box(-0.5, 0.86, 0.8, 0.5, 1.35, 0.92, TINTED, Mat.TINT)
    m.centered(0, 0.72, 0.96, 0.8, 0.3, 0.02, 0xF0C030)
    for k in 0...3 {
      let z0 = 0.95 - Float(k) * 0.18, z1 = z0 - 0.18
      let y0 = 1.35 + Float(k) * 0.12, y1 = y0 + 0.12
      m.quad(-0.52, y0, z0, 0.52, y0, z0, 0.52, y1, z1, -0.52, y1, z1, k % 2 == 0 ? TINTED : 0xF2EEE2, k % 2 == 0 ? Mat.TINT : Mat.PLAIN)
      m.quad(0.52, y0, z0, -0.52, y0, z0, -0.52, y1, z1, 0.52, y1, z1, 0x1E2124)
    }
    person(m, 0, 0.95, -0.45, 0xD9D2C2, false)
    m.axle(-0.05, 0.05, 0.33, -0.95, 0.33, 10, TIRE, 0xA0A6AA)
    m.axle(-0.58, -0.5, 0.33, 0.6, 0.33, 10, TIRE, 0xA0A6AA)
    m.axle(0.5, 0.58, 0.33, 0.6, 0.33, 10, TIRE, 0xA0A6AA)
    return m
  }

  /// A motorbike with its rider.
  private static func motorbike() -> MeshBuilder {
    let m = MeshBuilder()
    m.axle(-0.06, 0.06, 0.31, -0.7, 0.31, 10, TIRE, 0x9AA0A4)
    m.axle(-0.07, 0.07, 0.31, 0.68, 0.31, 10, TIRE, 0x9AA0A4)
    m.box(-0.16, 0.4, -0.45, 0.16, 0.7, 0.5, 0x2C3034)
    m.box(-0.18, 0.7, -0.45, 0.18, 0.92, -0.05, TINTED, Mat.TINT)
    m.box(-0.15, 0.72, -0.05, 0.15, 0.84, 0.6, 0x15181A)
    m.centered(0, 0.95, -0.62, 0.62, 0.04, 0.04, 0x2C3034)
    m.centered(0, 0.8, -0.82, 0.16, 0.14, 0.05, 0xFFF6CF, Mat.EMISSIVE)
    m.centered(0, 0.72, 0.72, 0.12, 0.08, 0.04, 0xC9201C, Mat.EMISSIVE)
    person(m, 0, 0.84, 0.15, 0x3A5C8C, true)
    return m
  }
}
