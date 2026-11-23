import Foundation

// Shared by the Android (Kotlin) and iOS (Swift) builds; keep the two in step.
let PI_F: Float = .pi
let DEG: Float = .pi / 180
let GRAVITY: Float = 9.81

extension Comparable {
  @inline(__always) func clamped(_ low: Self, _ high: Self) -> Self { min(max(self, low), high) }
}

@inline(__always) func approach(_ value: Float, _ target: Float, _ step: Float) -> Float {
  value < target ? min(value + step, target) : max(value - step, target)
}

/** Frame-rate independent exponential smoothing factor. */
@inline(__always) func blend(_ rate: Float, _ dt: Float) -> Float { 1 - exp(-rate * dt) }

func wrapAngle(_ angle: Float) -> Float {
  var a = fmodf(angle, 2 * PI_F)
  if a > PI_F { a -= 2 * PI_F }
  if a < -PI_F { a += 2 * PI_F }
  return a
}

/// Column-major 4x4 matrices in plain arrays, identical to the Kotlin `Mat4`.
enum Mat4 {
  static func identity(_ m: inout [Float]) {
    for i in 0..<16 { m[i] = 0 }
    m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1
  }

  static func multiply(_ out: inout [Float], _ a: [Float], _ b: [Float]) {
    for c in 0..<4 {
      for r in 0..<4 {
        out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
      }
    }
  }

  /** A pose whose local -Z is forward. Positive pitch is nose down, positive roll is right side down. */
  static func pose(_ out: inout [Float], _ x: Float, _ y: Float, _ z: Float, _ yaw: Float, _ pitch: Float, _ roll: Float) {
    let cy = cos(yaw), sy = sin(yaw), cp = cos(pitch), sp = sin(pitch), cr = cos(roll), sr = sin(roll)
    out[0] = cy * cr - sy * sp * sr; out[1] = -cp * sr; out[2] = sy * cr + cy * sp * sr; out[3] = 0
    out[4] = cy * sr + sy * sp * cr; out[5] = cp * cr; out[6] = sy * sr - cy * sp * cr; out[7] = 0
    out[8] = -sy * cp; out[9] = sp; out[10] = cy * cp; out[11] = 0
    out[12] = x; out[13] = y; out[14] = z; out[15] = 1
  }

  /// OpenGL-style clip space (z from -1 to 1); the Metal renderer converts with `clipFix`.
  static func perspective(_ out: inout [Float], _ tanHalfY: Float, _ aspect: Float, _ near: Float, _ far: Float) {
    for i in 0..<16 { out[i] = 0 }
    out[0] = 1 / (tanHalfY * aspect)
    out[5] = 1 / tanHalfY
    out[10] = (far + near) / (near - far)
    out[11] = -1
    out[14] = 2 * far * near / (near - far)
  }

  static func ortho(_ out: inout [Float], _ half: Float, _ near: Float, _ far: Float) {
    for i in 0..<16 { out[i] = 0 }
    out[0] = 1 / half; out[5] = 1 / half
    out[10] = -2 / (far - near); out[14] = -(far + near) / (far - near)
    out[15] = 1
  }

  /** View matrix from a camera rotation (columns right, up, back) and an eye point. */
  static func view(_ out: inout [Float], _ basis: [Float], _ x: Float, _ y: Float, _ z: Float) {
    for i in 0..<3 {
      out[i] = basis[i * 4]
      out[4 + i] = basis[i * 4 + 1]
      out[8 + i] = basis[i * 4 + 2]
      out[12 + i] = -(basis[i * 4] * x + basis[i * 4 + 1] * y + basis[i * 4 + 2] * z)
    }
    out[3] = 0; out[7] = 0; out[11] = 0; out[15] = 1
  }
}

/// Snaps frame times to whole display periods; see FramePacer.kt.
final class FramePacer {
  var period: Float
  private var error: Float = 0
  init(period: Float) { self.period = period }

  func next(_ elapsed: Float) -> Float {
    let raw = elapsed.clamped(0, 0.1)
    let periods = max(1, Int((raw / period).rounded()))
    let snapped = Float(periods) * period
    let base = abs(raw - snapped) < 0.45 * period ? snapped : raw
    error += raw - base
    let correction = (error * 0.05).clamped(-0.05 * period, 0.05 * period)
    error -= correction
    return max(0, base + correction)
  }

  func reset() { error = 0 }
}
