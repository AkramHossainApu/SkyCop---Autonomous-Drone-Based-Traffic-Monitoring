import Foundation

/// Button bits, identical to InputMapping.kt.
enum Button {
  static let A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, LB = 1 << 4, RB = 1 << 5, LT = 1 << 6, RT = 1 << 7
  static let SELECT = 1 << 8, START = 1 << 9, L3 = 1 << 10, R3 = 1 << 11
  static let UP = 1 << 12, DOWN = 1 << 13, LEFT = 1 << 14, RIGHT = 1 << 15
}

/// Raw axis slots, numbered as on Android so saved calibrations mean the same thing. The
/// GameController framework names the sticks, so iOS fills only the slots of a controller with a
/// key layout (Y axes pointing down); slots 4, 5 and 8-11 stay zero.
enum Axis {
  static let X = 0, Y = 1, Z = 2, RZ = 3, LTRIGGER = 6, RTRIGGER = 7
  static let COUNT = 12
}

final class PadState {
  var leftX: Float = 0, leftY: Float = 0, rightX: Float = 0, rightY: Float = 0
  var leftTrigger: Float = 0, rightTrigger: Float = 0
  var held = 0
  func clear() { leftX = 0; leftY = 0; rightX = 0; rightY = 0; leftTrigger = 0; rightTrigger = 0; held = 0 }
}

/// Maps raw axes to two sticks and two triggers, with optional calibration (see InputMapping.kt).
final class InputMapping {
  private var channel = [Int](repeating: -1, count: 6)
  private var direction = [Float](repeating: 1, count: 6)
  private var rest = [Float](repeating: 0, count: 6)
  private var span = [Float](repeating: 1, count: 6)
  private var moved = [Bool](repeating: false, count: 6)
  /// Where each stick really rests (see InputMapping.kt): a small, perfectly still offset becomes the center.
  private var drift = [Float](repeating: 0, count: 4)
  private var lastRaw = [Float](repeating: 0, count: 4)
  private var still = [Int](repeating: 0, count: 4)
  private(set) var calibrated = false

  init() { configureStandard() }

  /// Every controller the GameController framework reports has named sticks and analog triggers.
  func configureStandard() {
    channel = [Axis.X, Axis.Y, Axis.Z, Axis.RZ, Axis.LTRIGGER, Axis.RTRIGGER]
    direction = [1, -1, 1, -1, 1, 1]
    rest = [Float](repeating: 0, count: 6)
    span = [Float](repeating: 1, count: 6)
    moved = [Bool](repeating: false, count: 6)
    calibrated = false
  }

  func read(_ raw: [Float], _ held: Int, _ deadzone: Float, _ out: PadState) {
    let lx = signed(0, raw), ly = signed(1, raw)
    var scale = radialScale(lx, ly, deadzone)
    out.leftX = lx * scale; out.leftY = ly * scale
    let rx = signed(2, raw), ry = signed(3, raw)
    scale = radialScale(rx, ry, deadzone)
    out.rightX = rx * scale; out.rightY = ry * scale
    out.leftTrigger = trigger(4, raw, held & Button.LT != 0)
    out.rightTrigger = trigger(5, raw, held & Button.RT != 0)
    out.held = held
  }

  private func signed(_ slot: Int, _ raw: [Float]) -> Float {
    let axis = channel[slot]
    if axis < 0 { return 0 }
    let value = (raw[axis] - rest[slot]) * direction[slot]
    if abs(raw[axis] - lastRaw[slot]) < 0.004 { still[slot] += 1 } else { still[slot] = 0 }
    lastRaw[slot] = raw[axis]
    if still[slot] > 90 && abs(value) < 0.28 { drift[slot] = value }
    return (value - drift[slot]).clamped(-1, 1)
  }

  private func trigger(_ slot: Int, _ raw: [Float], _ pressed: Bool) -> Float {
    var value: Float = 0
    let axis = channel[slot]
    if axis >= 0 {
      if !moved[slot] && abs(raw[axis]) > 0.001 { moved[slot] = true }
      if moved[slot] { value = ((raw[axis] - rest[slot]) * direction[slot] / span[slot]).clamped(0, 1) }
    }
    if pressed && !moved[slot] { value = 1 }
    return value < 0.05 ? 0 : (value - 0.05) / 0.95
  }

  private func radialScale(_ x: Float, _ y: Float, _ deadzone: Float) -> Float {
    let magnitude = hypot(x, y)
    if magnitude <= deadzone { return 0 }
    return min(1, (magnitude - deadzone) / (0.96 - deadzone)) / magnitude
  }

  func assign(_ slot: Int, _ axis: Int, _ sign: Float, _ restValue: Float) {
    channel[slot] = axis
    direction[slot] = sign
    rest[slot] = restValue
    span[slot] = slot >= 4 && abs(restValue) > 0.5 ? 2 : 1
    moved[slot] = false
    calibrated = true
  }

  func encode() -> String { (0..<6).map { "\(channel[$0]),\(direction[$0]),\(rest[$0])" }.joined(separator: ";") }

  @discardableResult
  func decode(_ text: String) -> Bool {
    let parts = text.split(separator: ";").map { $0.split(separator: ",") }
    guard parts.count == 6, parts.allSatisfy({ $0.count == 3 }) else { return false }
    var values: [(Int, Float, Float)] = []
    for p in parts {
      guard let a = Int(p[0]), let s = Float(p[1]), let r = Float(p[2]), (-1..<Axis.COUNT).contains(a) else { return false }
      values.append((a, s, r))
    }
    for (slot, v) in values.enumerated() { assign(slot, v.0, v.1, v.2) }
    return true
  }
}

/// Guided remapping, the same steps as Calibration in InputMapping.kt.
final class Calibration {
  static let PROMPTS = ["PUSH LEFT STICK RIGHT", "PUSH LEFT STICK UP", "PUSH RIGHT STICK RIGHT",
                        "PUSH RIGHT STICK UP", "PULL LEFT TRIGGER", "PULL RIGHT TRIGGER"]
  private(set) var step = -1
  private(set) var waitingForRelease = false
  var active: Bool { step >= 0 }
  private var base = [Float](repeating: 0, count: Axis.COUNT)
  private var axes = [Int](repeating: -1, count: 6)
  private var signs = [Float](repeating: 1, count: 6)
  private var rests = [Float](repeating: 0, count: 6)
  private var peak: Float = 0, last: Float = 0, still: Float = 0, settle: Float = 0

  func start() {
    step = 0; waitingForRelease = false; settle = 0.5
    axes = [Int](repeating: -1, count: 6); signs = [Float](repeating: 1, count: 6); rests = [Float](repeating: 0, count: 6)
  }

  func cancel() { step = -1 }

  func skip() {
    if active && !waitingForRelease { step += 1; settle = 0.3 }
  }

  func update(_ raw: [Float], _ dt: Float, _ mapping: InputMapping) -> Bool {
    if !active { return false }
    if step >= 6 {
      for slot in 0..<6 { mapping.assign(slot, axes[slot], signs[slot], rests[slot]) }
      step = -1
      return true
    }
    if settle > 0 {
      settle -= dt
      base = raw
      return false
    }
    if !waitingForRelease {
      var found = -1
      var largest: Float = 0.65
      for axis in 0..<Axis.COUNT {
        let used = axes[0..<step].contains(axis)
        let delta = abs(raw[axis] - base[axis])
        if !used && delta > largest { largest = delta; found = axis }
      }
      if found >= 0 {
        axes[step] = found
        signs[step] = raw[found] - base[found] >= 0 ? 1 : -1
        peak = raw[found]; last = peak; still = 0
        waitingForRelease = true
      }
    } else {
      let value = raw[axes[step]]
      if (value - peak) * signs[step] > 0 { peak = value }
      still = abs(value - last) < 0.03 ? still + dt : 0
      last = value
      if (peak - value) * signs[step] > 0.5 && still > 0.35 {
        rests[step] = value
        waitingForRelease = false
        step += 1
        settle = 0.25
      }
    }
    return false
  }
}

enum ControlScheme {
  static func curve(_ v: Float) -> Float { v * (0.6 + 0.4 * v * v) }

  static func apply(_ pad: PadState, _ settings: Settings, _ out: PilotInput) {
    let leftVertical = curve(pad.leftY)
    let triggers = curve(pad.rightTrigger) - curve(pad.leftTrigger)
    out.yaw = curve(pad.leftX)
    if settings.classicLayout {
      out.forward = leftVertical
      out.strafe = (pad.held & Button.RB != 0 ? 1 : 0) - (pad.held & Button.LB != 0 ? 1 : 0)
      out.climb = triggers
    } else {
      out.forward = curve(pad.rightY)
      out.strafe = curve(pad.rightX)
      out.climb = ((settings.invertClimb ? -leftVertical : leftVertical) + triggers).clamped(-1, 1)
    }
  }
}
