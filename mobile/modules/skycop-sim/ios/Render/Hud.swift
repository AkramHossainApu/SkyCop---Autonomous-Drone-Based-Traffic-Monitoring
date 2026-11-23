import Foundation

/// CPU side of the HUD: quads over one glyph atlas (see HudBatch.kt). Eight floats per vertex.
final class HudGeometry {
  static let CELL_W: Float = 34, CELL_H: Float = 56, COLUMNS = 16, ROWS = 6, SOLID = 95
  static let MAX_VERTICES = 24_000
  private(set) var data = [Float](repeating: 0, count: MAX_VERTICES * 8)
  private(set) var count = 0
  private var r: Float = 1, g: Float = 1, b: Float = 1, a: Float = 1
  private let solidU = (Float(SOLID % COLUMNS) + 0.5) / Float(COLUMNS)
  private let solidV = (Float(SOLID / COLUMNS) + 0.5) / Float(ROWS)

  func begin() { count = 0 }

  func color(_ rgb: Int, _ alpha: Float = 1) {
    r = Float((rgb >> 16) & 255) / 255; g = Float((rgb >> 8) & 255) / 255; b = Float(rgb & 255) / 255; a = alpha
  }

  @inline(__always) private func vertex(_ x: Float, _ y: Float, _ u: Float, _ v: Float) {
    let at = count * 8
    data[at] = x; data[at + 1] = y; data[at + 2] = u; data[at + 3] = v
    data[at + 4] = r; data[at + 5] = g; data[at + 6] = b; data[at + 7] = a
    count += 1
  }

  func triangle(_ ax: Float, _ ay: Float, _ bx: Float, _ by: Float, _ cx: Float, _ cy: Float) {
    if count + 3 > HudGeometry.MAX_VERTICES { return }
    vertex(ax, ay, solidU, solidV); vertex(bx, by, solidU, solidV); vertex(cx, cy, solidU, solidV)
  }

  func rect(_ x: Float, _ y: Float, _ width: Float, _ height: Float) {
    triangle(x, y, x + width, y, x + width, y + height)
    triangle(x, y, x + width, y + height, x, y + height)
  }

  func line(_ x0: Float, _ y0: Float, _ x1: Float, _ y1: Float, _ width: Float) {
    let length = hypot(x1 - x0, y1 - y0)
    if length < 0.001 { return }
    let sx = -(y1 - y0) / length * width / 2, sy = (x1 - x0) / length * width / 2
    triangle(x0 + sx, y0 + sy, x1 + sx, y1 + sy, x1 - sx, y1 - sy)
    triangle(x0 + sx, y0 + sy, x1 - sx, y1 - sy, x0 - sx, y0 - sy)
  }

  func text(_ value: String, _ x: Float, _ y: Float, _ size: Float, _ align: Float = 0) {
    let width = size * HudGeometry.CELL_W / HudGeometry.CELL_H
    let step = width * 0.86
    let characters = value.unicodeScalars
    var cx = x - align * Float(characters.count) * step
    for scalar in characters {
      let code = Int(scalar.value) - 32
      if code >= 1 && code < HudGeometry.SOLID && count + 6 <= HudGeometry.MAX_VERTICES {
        let columns = Float(HudGeometry.COLUMNS), rows = Float(HudGeometry.ROWS)
        let u0 = (Float(code % HudGeometry.COLUMNS) + 0.02) / columns, u1 = (Float(code % HudGeometry.COLUMNS) + 0.98) / columns
        let v0 = (Float(code / HudGeometry.COLUMNS) + 0.01) / rows, v1 = (Float(code / HudGeometry.COLUMNS) + 0.99) / rows
        let left = cx - (width - step) / 2, right = left + width
        vertex(left, y, u0, v0); vertex(right, y, u1, v0); vertex(right, y + size, u1, v1)
        vertex(left, y, u0, v0); vertex(right, y + size, u1, v1); vertex(left, y + size, u0, v1)
      }
      cx += step
    }
  }
}

/// On-screen sticks for full-screen mode without a controller (TouchSticks.kt).
final class TouchSticks {
  static let RADIUS: Float = 72, CENTER_Y: Float = 98, ACTION_X: Float = -68, MENU_X: Float = 68, ACTION_Y: Float = 150
  var x: [Float] = [0, 0], y: [Float] = [0, 0]
  private var owner: [ObjectIdentifier?] = [nil, nil]

  func centerX(_ index: Int, _ halfWidth: Float) -> Float { index == 0 ? -halfWidth + 130 : halfWidth - 130 }

  /// Positions are in points; returns a button mask for taps on the on-screen buttons.
  func began(_ id: ObjectIdentifier, _ px: Float, _ py: Float, _ width: Float, _ height: Float) -> Int {
    let scale = 540 / height, half = width * scale / 2
    let hx = px * scale - half, hy = py * scale - 270
    if abs(hy - TouchSticks.ACTION_Y) < 28 {
      if abs(hx - TouchSticks.ACTION_X) < 60 { return Button.A }
      if abs(hx - TouchSticks.MENU_X) < 60 { return Button.START }
    }
    let stick = hx < 0 ? 0 : 1
    if owner[stick] == nil && hypot(hx - centerX(stick, half), hy - TouchSticks.CENTER_Y) < TouchSticks.RADIUS * 1.6 {
      owner[stick] = id
      move(stick, hx, hy, half)
    }
    return 0
  }

  func moved(_ id: ObjectIdentifier, _ px: Float, _ py: Float, _ width: Float, _ height: Float) {
    let scale = 540 / height, half = width * scale / 2
    for stick in 0...1 where owner[stick] == id { move(stick, px * scale - half, py * scale - 270, half) }
  }

  func ended(_ id: ObjectIdentifier) {
    for stick in 0...1 where owner[stick] == id { release(stick) }
  }

  private func move(_ stick: Int, _ px: Float, _ py: Float, _ half: Float) {
    var dx = (px - centerX(stick, half)) / TouchSticks.RADIUS
    var dy = -(py - TouchSticks.CENTER_Y) / TouchSticks.RADIUS
    let magnitude = max(1, hypot(dx, dy))
    dx /= magnitude; dy /= magnitude
    x[stick] = abs(dx) < 0.12 ? 0 : dx
    y[stick] = abs(dy) < 0.12 ? 0 : dy
  }

  private func release(_ stick: Int) { owner[stick] = nil; x[stick] = 0; y[stick] = 0 }

  func clear() { release(0); release(1) }
}

/// Flight instruments, menu and help (Hud.kt). Centered coordinates, y down, 540 units per eye height.
final class Hud {
  static let HALF_HEIGHT: Float = 270
  private static let WHITE = 0xF2F6F7, MUTED = 0x9DB0B8, ACCENT = 0x58E6FF, WARN = 0xFFC247
  private static let DANGER = 0xFF5A4E, GOOD = 0x62E58F, PANEL = 0x0A1419
  private static let ROWS = 9
  private static let ROW_HEIGHT: Float = 34, LIST_TOP: Float = -158

  let batch = HudGeometry()
  private var halfWidth: Float = 300, menuHalf: Float = 300
  private var firstRow = 0
  private(set) var scale: Float = 1

  private let pilotHelp = [
    "LEFT STICK", "UP / DOWN + TURN", "RIGHT STICK", "MOVE IN ANY DIRECTION", "LT / RT", "DOWN / UP",
    "LB / RB", "SLOWER / FASTER MODE", "D-PAD UP/DOWN", "CAMERA HEIGHT", "A", "TAKE OFF / LAND",
    "B", "RETURN HOME", "X", "LIVE CAMERA", "Y", "CHANGE VIEW", "R3 / OPTIONS", "RECENTER VIEW", "MENU", "MENU",
  ]
  private let classicHelp = [
    "LEFT STICK", "FORWARD / BACK + TURN", "RIGHT STICK", "LOOK AROUND", "LT / RT", "DOWN / UP",
    "LB / RB", "SLIDE LEFT / RIGHT", "D-PAD LEFT/RIGHT", "FLIGHT MODE", "A", "TAKE OFF / LAND",
    "B", "RETURN HOME", "X", "LIVE CAMERA", "Y", "CHANGE VIEW", "R3 / OPTIONS", "RECENTER VIEW", "MENU", "MENU",
  ]

  private typealias H = Hud

  func build(_ game: Game, _ pad: PadState, _ eyeAspect: Float, _ fps: Int, _ touch: TouchSticks) {
    scale = game.settings.vr && game.screen != .flight ? 0.8 : 1
    halfWidth = H.HALF_HEIGHT * eyeAspect / scale
    menuHalf = min(310, halfWidth * 0.86)
    batch.begin()
    switch game.screen {
    case .flight:
      flight(game)
      if !game.settings.vr && game.controllerName == nil { sticks(touch) }
    case .menu: menu(game, pad, fps)
    case .controls: controls(game)
    case .calibration: calibration(game)
    }
    if let toast = game.toast {
      let y: Float = game.screen == .flight ? -100 : 222
      let w = Float(toast.count) * 10.5 + 28
      batch.color(H.PANEL, 0.82); batch.rect(-w / 2, y - 5, w, 32)
      batch.color(H.ACCENT); batch.text(toast, 0, y, 22, 0.5)
    }
  }

  private func panel(_ x: Float, _ y: Float, _ width: Float, _ height: Float) {
    batch.color(H.PANEL, 0.5); batch.rect(x, y, width, height)
  }

  private func flight(_ game: Game) {
    let vr = game.settings.vr
    let w = vr ? min(250, halfWidth * 0.76) : halfWidth - 28
    let h: Float = vr ? 178 : H.HALF_HEIGHT - 18
    let drone = game.drone, pose = drone.render

    panel(-w, -h, 152, 50)
    batch.color(H.ACCENT); batch.text(drone.mode.label, -w + 8, -h + 5, 19)
    batch.color(H.WHITE); batch.text(drone.phase.label, -w + 8, -h + 25, 17)

    panel(w - 128, -h, 128, 70)
    let battery = drone.battery
    batch.color(battery < 10 ? H.DANGER : battery < 25 ? H.WARN : H.GOOD)
    batch.text("BATT \(Int(ceil(battery)))%", w - 8, -h + 5, 19, 1)
    batch.rect(w - 120, -h + 27, 112 * battery / 100, 4)
    batch.color(H.MUTED); batch.text("HOME \(Int(drone.homeDistance))M", w - 8, -h + 38, 17, 1)

    compass(game, -h)

    panel(-w, h - 62, 120, 62)
    batch.color(H.MUTED); batch.text("SPEED KM/H", -w + 8, h - 58, 15)
    batch.color(H.WHITE); batch.text("\(javaRound(drone.speed * 3.6))", -w + 8, h - 40, 34)
    panel(w - 120, h - 62, 120, 62)
    batch.color(H.MUTED); batch.text("HEIGHT M", w - 8, h - 58, 15, 1)
    let height = javaRound(drone.height * 10)
    batch.color(H.WHITE); batch.text("\(height / 10).\(height % 10)", w - 8, h - 40, 34, 1)

    radar(pose, 0, h - 34)
    if game.camera.view == .onboard { horizon(pose) }

    let blink = fmodf(game.clock, 0.8) < 0.5
    if drone.phase == .landed {
      batch.color(H.PANEL, 0.7); batch.rect(-120, -62, 240, 54)
      batch.color(H.WHITE); batch.text("PRESS A TO TAKE OFF", 0, -57, 21, 0.5)
      batch.color(H.MUTED)
      batch.text(game.controllerName == nil && !vr ? "OR PUSH LEFT STICK UP" : "MENU: SETTINGS + CONTROLS", 0, -34, 17, 0.5)
    } else if blink && drone.obstacle < 14 {
      batch.color(H.WARN); batch.text("OBSTACLE", 0, 62, 22, 0.5)
    } else if blink && battery < 15 {
      batch.color(H.DANGER); batch.text("LOW BATTERY", 0, 62, 22, 0.5)
    }
    if game.headSensorMissing { batch.color(H.WARN); batch.text("THIS PHONE HAS NO MOTION SENSOR", 0, -118, 17, 0.5) }
    if game.controllerName == nil && vr { batch.color(H.WARN); batch.text("NO CONTROLLER - TAP FOR MENU", 0, 90, 17, 0.5) }
  }

  private func compass(_ game: Game, _ top: Float) {
    let span: Float = 62
    let b = game.camera.basis
    let heading = atan2(-b[8], b[10])
    batch.color(H.PANEL, 0.4); batch.rect(-span - 6, top, span * 2 + 12, 30)
    for k in 0..<24 {
      let offset = wrapAngle(Float(k) * PI_F / 12 - heading) * 70
      if abs(offset) > span { continue }
      batch.color(H.WHITE, 0.8)
      if k % 6 == 0 { batch.text(["N", "E", "S", "W"][k / 6], offset, top + 4, 18, 0.5) } else { batch.rect(offset - 1, top + 18, 2, 7) }
    }
  }

  private func horizon(_ pose: DronePose) {
    let c = cos(pose.roll) * 70, s = sin(pose.roll) * 70
    let offset = pose.pitch * 120
    batch.color(H.WHITE, 0.6)
    batch.line(-c, s - offset, -c * 0.3, s * 0.3 - offset, 2)
    batch.line(c * 0.3, -s * 0.3 - offset, c, -s - offset, 2)
    batch.rect(-2, -2, 4, 4)
  }

  private func radar(_ pose: DronePose, _ cx: Float, _ cy: Float) {
    let radius: Float = 38, scale = radius / 170
    batch.color(H.PANEL, 0.5)
    for i in 0..<20 {
      let a = Float(i) * PI_F / 10, b = Float(i + 1) * PI_F / 10
      batch.triangle(cx, cy, cx + cos(a) * radius, cy + sin(a) * radius, cx + cos(b) * radius, cy + sin(b) * radius)
    }
    let c = cos(pose.yaw), s = sin(pose.yaw)
    let dx = City.HOME_X - pose.x, dz = City.HOME_Z - pose.z
    var rx = (dx * c + dz * s) * scale, ry = (-dx * s + dz * c) * scale
    let d = hypot(rx, ry)
    if d > radius - 3 { rx *= (radius - 3) / d; ry *= (radius - 3) / d }
    batch.color(H.GOOD); batch.rect(cx + rx - 3, cy + ry - 3, 6, 6)
    batch.color(H.WHITE); batch.triangle(cx - 5, cy + 5, cx + 5, cy + 5, cx, cy - 7)
  }

  private func sticks(_ touch: TouchSticks) {
    for i in 0...1 {
      let cx = touch.centerX(i, halfWidth), cy = TouchSticks.CENTER_Y, R = TouchSticks.RADIUS
      batch.color(H.WHITE, 0.3)
      for k in 0..<32 {
        let a = Float(k) * PI_F / 16, b = Float(k + 1) * PI_F / 16
        batch.line(cx + cos(a) * R, cy + sin(a) * R, cx + cos(b) * R, cy + sin(b) * R, 3)
      }
      let x = cx + touch.x[i] * R, y = cy - touch.y[i] * R
      batch.color(H.ACCENT, 0.55); batch.rect(x - 15, y - 15, 30, 30)
    }
    batch.color(H.PANEL, 0.6)
    batch.rect(TouchSticks.ACTION_X - 55, TouchSticks.ACTION_Y - 20, 110, 40)
    batch.rect(TouchSticks.MENU_X - 55, TouchSticks.ACTION_Y - 20, 110, 40)
    batch.color(H.WHITE)
    batch.text("FLY/LAND", TouchSticks.ACTION_X, TouchSticks.ACTION_Y - 10, 19, 0.5)
    batch.text("MENU", TouchSticks.MENU_X, TouchSticks.ACTION_Y - 10, 19, 0.5)
  }

  private func header(_ title: String) {
    batch.color(H.PANEL, 0.93); batch.rect(-menuHalf - 10, -218, menuHalf * 2 + 20, 436)
    batch.color(H.ACCENT); batch.text(title, 0, -205, 28, 0.5)
  }

  private func menu(_ game: Game, _ pad: PadState, _ fps: Int) {
    header("SKYCOP FLIGHT")
    firstRow = (game.selected - H.ROWS / 2).clamped(0, game.menu.count - H.ROWS)
    let textSize = min(21, menuHalf / 13)
    for row in 0..<H.ROWS {
      let index = firstRow + row
      let item = game.menu[index]
      let y = H.LIST_TOP + Float(row) * H.ROW_HEIGHT
      let selected = index == game.selected
      if selected { batch.color(H.ACCENT, 0.22); batch.rect(-menuHalf, y - 3, menuHalf * 2, H.ROW_HEIGHT - 2) }
      batch.color(selected ? H.ACCENT : H.WHITE)
      batch.text(item.label, -menuHalf + 8, y + 2, textSize)
      if let value = value(item, game.settings) { batch.text(value, menuHalf - 8, y + 2, textSize, 1) }
    }
    let rows = Float(H.ROWS), items = Float(game.menu.count)
    batch.color(H.MUTED)
    batch.rect(menuHalf + 3, H.LIST_TOP + Float(firstRow) * H.ROW_HEIGHT * rows / items, 4, H.ROW_HEIGHT * rows * rows / items)
    let y: Float = 180
    stick(-menuHalf + 30, y, pad.leftX, pad.leftY)
    stick(-menuHalf + 92, y, pad.rightX, pad.rightY)
    trigger(-menuHalf + 132, y, pad.leftTrigger)
    trigger(-menuHalf + 152, y, pad.rightTrigger)
    batch.color(H.MUTED)
    batch.text(String((game.controllerName ?? "NO CONTROLLER").prefix(22)), menuHalf - 4, 162, 16, 1)
    batch.text("\(fps) FPS   A SELECT  B BACK", menuHalf - 4, 182, 16, 1)
    batch.text("SWIPE TO SCROLL", -menuHalf + 170, 182, 14)
  }

  private func stick(_ cx: Float, _ cy: Float, _ x: Float, _ y: Float) {
    batch.color(H.MUTED, 0.3); batch.rect(cx - 25, cy - 25, 50, 50)
    batch.color(H.ACCENT); batch.rect(cx + x * 20 - 4, cy - y * 20 - 4, 8, 8)
  }

  private func trigger(_ x: Float, _ cy: Float, _ value: Float) {
    batch.color(H.MUTED, 0.3); batch.rect(x, cy - 25, 12, 50)
    batch.color(H.ACCENT); batch.rect(x, cy + 25 - value * 50, 12, value * 50)
  }

  private func value(_ item: MenuItem, _ s: Settings) -> String? {
    func onOff(_ v: Bool) -> String { v ? "ON" : "OFF" }
    switch item {
    case .layout: return s.classicLayout ? "CLASSIC" : "PILOT"
    case .vr: return s.vr ? "VR HEADSET" : "FULL SCREEN"
    case .invertClimb: return onOff(s.invertClimb)
    case .invertLook: return onOff(s.invertLook)
    case .head: return onOff(s.headTracking)
    case .lens: return onOff(s.lensCorrection)
    case .comfort: return onOff(s.comfort)
    case .shadows: return onOff(s.shadows)
    case .wind: return onOff(s.wind)
    case .sound: return onOff(s.sound)
    case .sensitivity: return "\(s.sensitivity)%"
    case .deadzone: return "\(s.deadzone)%"
    case .spacing: return "\(s.lensSpacing)"
    default: return nil
    }
  }

  private func controls(_ game: Game) {
    let classic = game.settings.classicLayout
    header(classic ? "CLASSIC CONTROLS" : "PILOT CONTROLS")
    let rows = classic ? classicHelp : pilotHelp
    let size = min(19, menuHalf / 15)
    for i in 0..<(rows.count / 2) {
      let y = -156 + Float(i) * 30
      batch.color(H.ACCENT); batch.text(rows[i * 2], -menuHalf + 8, y, size)
      batch.color(H.WHITE); batch.text(rows[i * 2 + 1], menuHalf - 8, y, size, 1)
    }
    batch.color(H.MUTED); batch.text("B: BACK", 0, 186, 18, 0.5)
  }

  private func calibration(_ game: Game) {
    header("CALIBRATION")
    let c = game.calibration
    let step = c.step.clamped(0, 5)
    batch.color(H.MUTED); batch.text("STEP \(step + 1) OF 6", 0, -90, 20, 0.5)
    batch.color(H.WHITE)
    batch.text(c.waitingForRelease ? "NOW LET GO" : Calibration.PROMPTS[step], 0, -20, min(27, menuHalf / 11), 0.5)
    batch.color(H.MUTED)
    batch.text("B: SKIP STEP", 0, 90, 18, 0.5)
    batch.text("MENU: CANCEL", 0, 118, 18, 0.5)
  }

  func menuRowAt(_ x: Float, _ y: Float) -> Int {
    if abs(x) > menuHalf + 10 { return -1 }
    let row = Int(floor((y - H.LIST_TOP + 3) / H.ROW_HEIGHT))
    return (0..<H.ROWS).contains(row) ? firstRow + row : -1
  }
}

/// Port of QualityGovernor.kt with the levels from GpuProfile.kt.
struct QualityLevel { let detailRange: Float; let shadowDetailRange: Float; let carRange: Float; let shadows: Bool; let relief: Bool }
let QUALITY_LEVELS = [
  QualityLevel(detailRange: 210, shadowDetailRange: 120, carRange: 340, shadows: true, relief: true),
  QualityLevel(detailRange: 125, shadowDetailRange: 55, carRange: 230, shadows: true, relief: true),
  QualityLevel(detailRange: 90, shadowDetailRange: 0, carRange: 170, shadows: false, relief: false),
]

final class QualityGovernor {
  private(set) var scale: Float = 0.85
  private(set) var level = 0
  private(set) var period: Float = 1 / 60
  private(set) var fps = 0
  private(set) var worstFrameMs = 0
  private var bestLevel = 0
  private var refresh: Float = 1 / 60
  private var sum: Float = 0, frames = 0, worst: Float = 0, warmup: Float = 2
  private var good: Float = 0, bad: Float = 0, retryAfter: Float = 4, sinceIncrease: Float = 100

  func start(_ refreshRate: Float, _ startLevel: Int) {
    refresh = 1 / refreshRate.clamped(30, 165); period = refresh
    scale = 0.85; bestLevel = startLevel; level = startLevel
    sum = 0; frames = 0; worst = 0; good = 0; bad = 0; warmup = 2; retryAfter = 4
  }

  func frame(_ dt: Float) -> Bool {
    if dt > 0.5 { return false }
    sum += dt; frames += 1
    if dt > worst { worst = dt }
    if sum < 1 { return false }
    let average = sum / Float(frames)
    fps = Int(1 / average + 0.5)
    worstFrameMs = Int(worst * 1000)
    let window = sum
    sum = 0; frames = 0; worst = 0
    sinceIncrease += window
    if warmup > 0 { warmup -= window; return true }
    if average > period * 1.08 {
      good = 0
      if scale > 0.5 {
        if sinceIncrease < 3 { retryAfter = min(60, retryAfter * 2) }
        scale = max(0.5, scale - (average > period * 1.3 ? 0.12 : 0.06))
      } else {
        bad += window
        if level < QUALITY_LEVELS.count - 1 {
          if bad >= 2 { level += 1; scale = 0.65; bad = 0 }
          return true
        }
        let half = period * 2
        if bad > 2 && half <= 1 / 29 && abs(average - half) < half * 0.25 { period = half; scale = 0.7; bad = 0 }
      }
    } else {
      bad = 0
      good += window
      if good >= retryAfter && scale < 1 { scale = min(1, scale + 0.08); good = 0; sinceIncrease = 0 }
      if average < period * 0.7 { period = refresh }
      if scale >= 1 && level > bestLevel && good >= 10 { level -= 1; good = 0 }
    }
    return true
  }
}
