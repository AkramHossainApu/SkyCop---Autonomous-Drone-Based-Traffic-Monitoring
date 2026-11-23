import CoreMotion
import UIKit

/// Head orientation from CoreMotion's gyro + accelerometer fusion, with the same conventions and
/// gyro look-ahead as the Android HeadTracker.
final class HeadTracker {
  private let manager = CMMotionManager()
  private let pose = HeadPose()
  private var world = [Float](repeating: 0, count: 16)
  private var wantRecenter = true
  private var transposeVotes = 0
  var orientation: UIInterfaceOrientation = .landscapeRight

  var available: Bool { manager.isDeviceMotionAvailable }

  func start() {
    guard manager.isDeviceMotionAvailable else { return }
    manager.deviceMotionUpdateInterval = 1.0 / 200.0
    // Gyro and accelerometer only: magnets in a headset cannot pull the view around.
    manager.startDeviceMotionUpdates(using: .xArbitraryZVertical)
    wantRecenter = true
  }

  func stop() { manager.stopDeviceMotionUpdates() }

  func recenter() { wantRecenter = true }

  /// Writes the head rotation relative to the last recenter, or identity.
  func read(_ out: inout [Float], _ enabled: Bool) {
    guard enabled, let motion = manager.deviceMotion else {
      Mat4.identity(&out)
      return
    }
    let q = motion.attitude.quaternion
    let x = Float(q.x), y = Float(q.y), z = Float(q.z), w = Float(q.w)
    // Columns: the device's x, y and z axes in the reference frame (x, y horizontal, z up).
    var r: [Float] = [
      1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w),
      2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w),
      2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y),
    ]
    // Gravity in device coordinates settles which way the attitude rotation runs, without
    // relying on documentation: down expressed in device axes is the third row of R negated.
    let g = motion.gravity
    let byRows = abs(-r[2] - Float(g.x)) + abs(-r[5] - Float(g.y)) + abs(-r[8] - Float(g.z))
    let byColumns = abs(-r[6] - Float(g.x)) + abs(-r[7] - Float(g.y)) + abs(-r[8] - Float(g.z))
    if abs(byRows - byColumns) > 0.2 { transposeVotes = (transposeVotes + (byColumns < byRows ? 1 : -1)).clamped(-50, 50) }
    if transposeVotes > 0 {
      r = [r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8]]
    }
    // Device axes in the reference frame: device x = (r0, r1, r2), y = (r3, r4, r5), z = (r6, r7, r8).
    let dx = [r[0], r[1], r[2]], dy = [r[3], r[4], r[5]]
    var right: [Float], up: [Float]
    var sx = Float(motion.rotationRate.x), sy = Float(motion.rotationRate.y)
    let rx = sx, ry = sy
    switch orientation {
    case .landscapeLeft: right = dy; up = dx.map { -$0 }; sx = ry; sy = -rx
    case .portraitUpsideDown: right = dx.map { -$0 }; up = dy.map { -$0 }; sx = -rx; sy = -ry
    case .portrait: right = dx; up = dy
    default: right = dy.map { -$0 }; up = dx; sx = -ry; sy = rx
    }
    // Reference x, y, z (horizontal, horizontal, up) to GL right, up, backward.
    func gl(_ v: [Float]) -> (Float, Float, Float) { (v[0], v[2], -v[1]) }
    let back = (gl(right).1 * gl(up).2 - gl(right).2 * gl(up).1,
                gl(right).2 * gl(up).0 - gl(right).0 * gl(up).2,
                gl(right).0 * gl(up).1 - gl(right).1 * gl(up).0)
    let rr = gl(right), uu = gl(up)
    world[0] = rr.0; world[1] = rr.1; world[2] = rr.2; world[3] = 0
    world[4] = uu.0; world[5] = uu.1; world[6] = uu.2; world[7] = 0
    world[8] = back.0; world[9] = back.1; world[10] = back.2; world[11] = 0
    world[12] = 0; world[13] = 0; world[14] = 0; world[15] = 1
    let age = Float(ProcessInfo.processInfo.systemUptime - motion.timestamp).clamped(0, 0.02)
    pose.resolve(&out, world, sx, sy, Float(motion.rotationRate.z), HeadPose.LOOKAHEAD + age, wantRecenter)
    wantRecenter = false
  }
}
