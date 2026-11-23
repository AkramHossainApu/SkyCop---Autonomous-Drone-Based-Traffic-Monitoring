import Foundation

enum CameraView: Int, CaseIterable {
  case chase, onboard, survey
  var label: String { ["CHASE", "DRONE CAMERA", "SURVEY"][rawValue] }
}

/// Port of Camera.kt: follows position exactly, eases only heading, and lets the head alone
/// decide pitch and roll when tracking is on.
final class CameraRig {
  private static let NECK_UP: Float = 0.075, NECK_FORWARD: Float = 0.08, FOLLOW: Float = 10

  private let city: City
  private(set) var eye: [Float] = [0, 0, 0]
  private(set) var basis = [Float](repeating: 0, count: 16)
  private(set) var view = CameraView.chase
  private(set) var gimbal: Float = 0
  private(set) var turnRate: Float = 0

  private var base = [Float](repeating: 0, count: 16)
  private var yaw: Float = 0, yawVelocity: Float = 0
  private var pan: Float = 0, panVelocity: Float = 0, tiltVelocity: Float = 0
  private var distance: Float = 4.2

  init(city: City) { self.city = city }

  func next() {
    view = CameraView(rawValue: (view.rawValue + 1) % CameraView.allCases.count)!
    recenter()
    distance = view == .survey ? 16 : 4.3
  }

  func recenter() { gimbal = 0; pan = 0; panVelocity = 0; tiltVelocity = 0 }

  func snap(_ toYaw: Float) {
    yaw = toYaw; yawVelocity = 0; turnRate = 0
    recenter()
  }

  func look(_ panRate: Float, _ tiltRate: Float, _ dt: Float) {
    panVelocity += (panRate * 1.8 - panVelocity) * blend(14, dt)
    tiltVelocity += (tiltRate * 1.2 - tiltVelocity) * blend(14, dt)
    pan = wrapAngle(pan + panVelocity * dt)
    gimbal = (gimbal + tiltVelocity * dt).clamped(-85 * DEG, 30 * DEG)
  }

  func update(_ dt: Float, _ drone: DronePose, _ vr: Bool, _ tracked: Bool, _ head: [Float]) {
    let before = yaw
    let onboard = view == .onboard
    if onboard {
      yaw = drone.yaw; yawVelocity = 0
    } else {
      let F = CameraRig.FOLLOW
      let offset = yaw - drone.yaw
      let impulse = (yawVelocity + F * offset) * dt
      let decay = exp(-F * dt)
      yawVelocity = (yawVelocity - F * impulse) * decay
      yaw = drone.yaw + (offset + impulse) * decay
    }
    if dt > 0 { turnRate = abs(yaw - before) / dt }
    let heading = yaw + pan

    var pitch: Float
    let wantedDistance: Float, lift: Float, elevation: Float
    switch view {
    case .onboard:
      pitch = gimbal; wantedDistance = 0; lift = 0; elevation = 0
    case .chase:
      let rest: Float = vr ? 0 : -9 * DEG
      pitch = (rest + gimbal).clamped(-85 * DEG, 35 * DEG)
      wantedDistance = 4.3; lift = 1.15
      elevation = (-gimbal).clamped(-0.2, 1.35)
    case .survey:
      let rest: Float = tracked ? 28 : 55
      pitch = (-rest * DEG + gimbal).clamped(-88 * DEG, -10 * DEG)
      wantedDistance = tracked ? 16 : 20; lift = 1
      elevation = -pitch
    }
    if tracked { pitch = 0 }

    let fx = sin(heading), fz = -cos(heading)
    if onboard {
      eye[0] = drone.x + sin(drone.yaw) * 0.5
      eye[1] = max(0.25, drone.y - 0.05)
      eye[2] = drone.z - cos(drone.yaw) * 0.5
    } else {
      let dx = -fx * wantedDistance * cos(elevation)
      let dy = lift + wantedDistance * sin(elevation)
      let dz = -fz * wantedDistance * cos(elevation)
      let length = sqrt(dx * dx + dy * dy + dz * dz)
      let hit = city.raycast(drone.x, drone.y, drone.z, dx / length, dy / length, dz / length, length + 0.4, opaqueOnly: true)
      let allowed = max(0.8, min(length, hit - 0.4))
      distance += (allowed - distance) * blend(allowed < distance ? 18 : 3, dt)
      eye[0] = drone.x + dx / length * distance
      eye[1] = max(0.5, drone.y + dy / length * distance)
      eye[2] = drone.z + dz / length * distance
    }

    Mat4.pose(&base, 0, 0, 0, heading, -pitch, 0)
    Mat4.multiply(&basis, base, head)
    if tracked {
      for i in 0..<3 {
        eye[i] += (basis[4 + i] - base[4 + i]) * CameraRig.NECK_UP - (basis[8 + i] - base[8 + i]) * CameraRig.NECK_FORWARD
      }
    }
  }
}

/// Port of HeadPose.kt: fused orientation plus gyro look-ahead, recentered on heading only.
final class HeadPose {
  static let LOOKAHEAD: Float = 0.032
  private static let STILL: Float = 0.15
  private var delta = [Float](repeating: 0, count: 16)
  private var predicted = [Float](repeating: 0, count: 16)
  private var reference = [Float](repeating: 0, count: 16)
  private var yawOffset: Float = 0

  func resolve(_ out: inout [Float], _ world: [Float], _ wx: Float, _ wy: Float, _ wz: Float, _ seconds: Float, _ recenter: Bool) {
    let rate = sqrt(wx * wx + wy * wy + wz * wz)
    let angle = min(0.35, rate * seconds * min(1, rate / HeadPose.STILL))
    if angle > 1e-5 {
      axisAngle(&delta, wx / rate, wy / rate, wz / rate, angle)
      Mat4.multiply(&predicted, world, delta)
    } else {
      predicted = world
    }
    if recenter {
      let fx = -predicted[8], fy = -predicted[9], fz = -predicted[10]
      if hypot(fx, fz) > 0.2 {
        yawOffset = atan2(fx, -fz)
      } else {
        let sign: Float = fy < 0 ? 1 : -1
        yawOffset = atan2(predicted[4] * sign, -predicted[6] * sign)
      }
    }
    Mat4.pose(&reference, 0, 0, 0, -yawOffset, 0, 0)
    Mat4.multiply(&out, reference, predicted)
  }

  private func axisAngle(_ m: inout [Float], _ x: Float, _ y: Float, _ z: Float, _ angle: Float) {
    let c = cos(angle), s = sin(angle), t = 1 - c
    m[0] = t * x * x + c; m[1] = t * x * y + s * z; m[2] = t * x * z - s * y; m[3] = 0
    m[4] = t * x * y - s * z; m[5] = t * y * y + c; m[6] = t * y * z + s * x; m[7] = 0
    m[8] = t * x * z + s * y; m[9] = t * y * z - s * x; m[10] = t * z * z + c; m[11] = 0
    m[12] = 0; m[13] = 0; m[14] = 0; m[15] = 1
  }
}
