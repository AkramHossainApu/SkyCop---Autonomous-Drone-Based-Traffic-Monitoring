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

  static func vehicles() -> [MeshBuilder] { [sedan(), hatchback(), van(), bus(), police(), rickshaw()] }

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

  private static func hatchback() -> MeshBuilder {
    let m = MeshBuilder()
    m.hull([-1.82, -1.65, -0.75, 1.55, 1.82], [0.72, 0.85, 0.88, 0.87, 0.76], [0.36, 0.28, 0.27, 0.28, 0.36],
           [0.58, 0.67, 0.75, 0.76, 0.64], [0.72, 0.84, 0.96, 0.96, 0.82], TINTED, Mat.TINT)
    trim(m, 0.88, -1.83, 1.83, -0.72, 1.0)
    cabin(m, 0.83, 0.7, 0.94, 1.58, -0.8, -0.3, 1.7, 1.45, TINTED)
    wheels(m, 0.78, -1.15, 1.2, 0.33)
    lights(m, 0.5, -1.83, 1.83, 0.63)
    return m
  }

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

  private static func bus() -> MeshBuilder {
    let m = MeshBuilder()
    m.box(-1.25, 0.45, -5, 1.25, 3.1, 5, TINTED, Mat.TINT)
    for x: Float in [-1.26, 1.26] { for k in 0...5 { m.centered(x, 2.15, -3.7 + Float(k) * 1.48, 0.03, 1, 1.25, WINDOW, Mat.GLASS) } }
    m.centered(0, 2.05, -5.015, 2.2, 1.4, 0.03, WINDOW, Mat.GLASS)
    m.centered(0, 2.3, 5.015, 1.9, 0.8, 0.03, WINDOW, Mat.GLASS)
    wheels(m, 1.15, -3.2, 3.3, 0.48)
    lights(m, 0.9, -5.02, 5.02, 0.85)
    return m
  }

  private static func police() -> MeshBuilder {
    let m = MeshBuilder()
    saloon(m)
    m.box(-0.955, 0.5, -0.9, -0.93, 0.74, 1.3, BLUE, Mat.GLOSSY)
    m.box(0.93, 0.5, -0.9, 0.955, 0.74, 1.3, BLUE, Mat.GLOSSY)
    m.box(-0.6, 1.5, 0.1, -0.02, 1.66, 0.4, 0xFF2020, Mat.STROBE)
    m.box(0.02, 1.5, 0.1, 0.6, 1.66, 0.4, 0x205CFF, Mat.STROBE)
    return m
  }

  private static func rickshaw() -> MeshBuilder {
    let m = MeshBuilder()
    m.box(-0.45, 0.3, -0.5, 0.45, 0.8, 1.05, 0x2F7A4B, Mat.GLOSSY)
    m.box(-0.25, 0.4, -1.05, 0.25, 0.85, -0.5, TINTED, Mat.TINT)
    cabin(m, 0.43, 0.4, 0.8, 1.45, -0.6, -0.4, 1.0, 0.95, TINTED)
    m.axle(-0.08, 0.08, 0.25, -0.8, 0.25, 8, TIRE, 0x8E969B)
    m.axle(-0.48, -0.32, 0.25, 0.7, 0.25, 8, TIRE, 0x8E969B)
    m.axle(0.32, 0.48, 0.25, 0.7, 0.25, 8, TIRE, 0x8E969B)
    m.centered(0, 0.75, -1.065, 0.18, 0.14, 0.03, 0xFFF6CF, Mat.EMISSIVE)
    return m
  }
}
