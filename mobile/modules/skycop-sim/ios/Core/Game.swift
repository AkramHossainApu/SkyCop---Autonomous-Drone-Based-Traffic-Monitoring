import Foundation

/// Platform services the game needs (see Game.kt).
protocol GameHost: AnyObject {
  func exit(toLiveVideo: Bool)
  func settingsChanged()
  func calibrationSaved(_ mapping: String)
  func calibrationCleared()
  func recenterHead()
  func rumble(_ strength: Float)
  /// The pilot picked another map; the platform rebuilds the city and starts a new flight there.
  func mapChanged()
}

enum MenuItem: Int, CaseIterable {
  case resume, exit, map, time, weather, layout, controls, calibrate, defaultMapping, sensitivity, deadzone, invertClimb, invertLook
  case vr, head, lens, spacing, comfort, shadows, wind, sound, restart

  var label: String {
    ["RESUME FLIGHT", "EXIT SIMULATION", "MAP", "TIME OF DAY", "WEATHER", "CONTROL LAYOUT", "SHOW CONTROLS", "CALIBRATE CONTROLLER", "RESET CONTROLLER MAPPING",
     "TURN SENSITIVITY", "STICK DEADZONE", "INVERT CLIMB STICK", "INVERT CAMERA TILT", "DISPLAY", "HEAD TRACKING",
     "LENS CORRECTION", "LENS SPACING", "COMFORT VIGNETTE", "SHADOWS", "WIND", "ROTOR SOUND", "RESTART FLIGHT"][rawValue]
  }
}

enum Screen { case flight, menu, controls, calibration }

final class Game {
  let city: City
  let settings: Settings
  private weak var host: GameHost?
  var liveAvailable: Bool
  let traffic: Traffic
  let drone: DroneSim
  let environment = Environment()
  let pedestrians: Pedestrians
  let camera: CameraRig
  let calibration = Calibration()
  let menu = MenuItem.allCases

  private(set) var screen = Screen.flight
  private(set) var selected = 0
  var controllerName: String?
  var headSensorMissing = false
  private(set) var toast: String?
  private(set) var clock: Float = 0
  private(set) var propellerAngle: Float = 0
  /// Watching the live camera instead of flying; the flight waits, paused, until X or B.
  private(set) var watchingLive = false
  /// Set by the renderer: a frame of live video has arrived.
  var liveHasVideo = false
  /// The live picture is fixed to the view, not the room: no head tracking, always straight ahead.
  let liveBasis: [Float] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
  private(set) var liveSince: Float = 0

  private let input = PilotInput()
  private var toastUntil: Float = 0
  private var repeatIn: Float = 0
  private var lastDirection = 0
  private var crashes = 0
  private var depletions = 0
  private var autoReturns = 0
  private var wasCharging = false

  init(city: City, settings: Settings, host: GameHost, liveAvailable: Bool) {
    self.city = city
    self.settings = settings
    self.host = host
    self.liveAvailable = liveAvailable
    traffic = Traffic(city: city)
    pedestrians = Pedestrians(city: city)
    drone = DroneSim(city: city, traffic: traffic)
    camera = CameraRig(city: city)
  }

  private func show(_ message: String, _ seconds: Float = 2.5) {
    toast = message; toastUntil = clock + seconds
  }

  func attach(_ newHost: GameHost, live: Bool) { host = newHost; liveAvailable = live }

  func openMenu() {
    if screen != .menu { screen = .menu; selected = 0 }
    calibration.cancel()
  }

  func update(_ dt: Float, _ pad: PadState, _ pressed: Int, _ mapping: InputMapping, _ raw: [Float], _ head: [Float], _ tracked: Bool) {
    clock += dt
    if toast != nil && clock > toastUntil { toast = nil }
    if watchingLive && !liveAvailable {
      watchingLive = false
      show("LIVE CAMERA DISCONNECTED")
    }
    switch screen {
    case .flight: if watchingLive { watch(pressed) } else { fly(dt, pad, pressed) }
    case .menu: navigate(dt, pad, pressed)
    case .controls: if pressed & (Button.A | Button.B | Button.START) != 0 { screen = .menu }
    case .calibration: calibrate(dt, pressed, mapping, raw)
    }
    if screen == .flight && !watchingLive {
      traffic.update(dt)
      pedestrians.update(dt)
      propellerAngle = fmodf(propellerAngle + drone.render.rotor * 31 * dt, 2 * PI_F)
    }
    camera.update(screen == .flight ? dt : 0, drone.render, settings.vr, tracked, head)
  }

  func watchLive() {
    guard liveAvailable else { return }
    watchingLive = true
    liveSince = clock
    screen = .flight
    host?.recenterHead()
  }

  private func watch(_ pressed: Int) {
    if pressed & Button.START != 0 { openMenu(); return }
    if pressed & (Button.X | Button.B) != 0 {
      watchingLive = false
      show("SIMULATION", 1.2)
    }
    if pressed & (Button.R3 | Button.SELECT) != 0 {
      host?.recenterHead()
      show("VIEW RECENTERED", 1.2)
    }
  }

  private func fly(_ dt: Float, _ pad: PadState, _ pressed: Int) {
    if pressed & Button.START != 0 { openMenu(); return }
    if pressed & Button.B != 0 {
      drone.toggleReturnHome()
      if drone.phase == .returnHome { show("RETURNING TO NEAREST DOCK") }
      else if drone.phase == .flying { show("RETURN CANCELLED", 1.5) }
    }
    if pressed & Button.Y != 0 { camera.next(); show(camera.view.label) }
    if pressed & Button.X != 0 {
      if liveAvailable { watchLive() } else { show("NO LIVE CAMERA CONNECTED") }
    }
    if pressed & (Button.R3 | Button.SELECT) != 0 {
      camera.recenter(); host?.recenterHead()
      show("VIEW RECENTERED", 1.2)
    }
    var modeStep = 0
    if pressed & Button.RIGHT != 0 { modeStep += 1 }
    if pressed & Button.LEFT != 0 { modeStep -= 1 }
    if !settings.classicLayout {
      if pressed & Button.RB != 0 { modeStep += 1 }
      if pressed & Button.LB != 0 { modeStep -= 1 }
    }
    if modeStep != 0 {
      drone.mode = FlightMode(rawValue: (drone.mode.rawValue + modeStep).clamped(0, FlightMode.allCases.count - 1))!
      show(drone.mode.label, 1.5)
    }

    ControlScheme.apply(pad, settings, input)
    var tilt: Float = (pad.held & Button.UP != 0 ? 1 : 0) - (pad.held & Button.DOWN != 0 ? 1 : 0)
    var pan: Float = 0
    if settings.classicLayout {
      tilt += ControlScheme.curve(pad.rightY)
      pan = ControlScheme.curve(pad.rightX)
    }
    if settings.invertLook { tilt = -tilt }
    camera.look(pan * Float(settings.sensitivity) / 100, tilt.clamped(-1, 1), dt)

    drone.yawSensitivity = Float(settings.sensitivity) / 100
    drone.windEnabled = settings.wind
    environment.update(dt, settings)
    drone.windScale = environment.gust
    let before = drone.phase
    if pressed & Button.A != 0 {
      if drone.phase == .landed && !drone.canTakeOff { show("BATTERY EMPTY - LET IT CHARGE ON A DOCK") }
      drone.toggleTakeoffLanding()
    }
    drone.update(dt, input)
    if drone.impact > 0 {
      host?.rumble((drone.impact / 9).clamped(0.2, 1))
      drone.impact = 0
    }
    if drone.crashes != crashes {
      crashes = drone.crashes
      show("CRASHED - RESPAWNING AT NEAREST DOCK", 3.4)
    }
    if drone.depletions != depletions {
      depletions = drone.depletions
      show("BATTERY DEAD - RESPAWNING AT NEAREST DOCK", 3.6)
    }
    if drone.autoReturns != autoReturns {
      autoReturns = drone.autoReturns
      show("LOW BATTERY - RETURNING TO NEAREST DOCK", 3)
    }
    let charging = drone.charging
    if wasCharging && !charging && drone.onDock { show("FULLY CHARGED", 2) }
    wasCharging = charging
    if before == .crashed && drone.phase == .landed { camera.snap(drone.pose.yaw) }
    if before == .landed && drone.phase == .takeoff { host?.recenterHead() }
  }

  private func navigate(_ dt: Float, _ pad: PadState, _ pressed: Int) {
    if pressed & (Button.B | Button.START) != 0 { screen = .flight; return }
    var direction = 0
    if pad.held & Button.DOWN != 0 || pad.leftY < -0.6 { direction = Button.DOWN }
    else if pad.held & Button.UP != 0 || pad.leftY > 0.6 { direction = Button.UP }
    else if pad.held & Button.RIGHT != 0 || pad.leftX > 0.6 { direction = Button.RIGHT }
    else if pad.held & Button.LEFT != 0 || pad.leftX < -0.6 { direction = Button.LEFT }
    var act = 0
    if direction != lastDirection {
      act = direction; repeatIn = 0.4
    } else if direction != 0 {
      repeatIn -= dt
      if repeatIn <= 0 { act = direction; repeatIn = 0.12 }
    }
    lastDirection = direction
    if act == 0 && direction == 0 { act = pressed & (Button.UP | Button.DOWN | Button.LEFT | Button.RIGHT) }
    switch act {
    case Button.DOWN: selected = (selected + 1) % menu.count
    case Button.UP: selected = (selected + menu.count - 1) % menu.count
    case Button.LEFT: adjust(menu[selected], -1)
    case Button.RIGHT: adjust(menu[selected], 1)
    default: break
    }
    if pressed & Button.A != 0 { activate(selected) }
  }

  func activate(_ index: Int) {
    guard menu.indices.contains(index) else { return }
    selected = index
    let item = menu[index]
    switch item {
    case .resume: screen = .flight
    case .controls: screen = .controls
    case .calibrate:
      if controllerName == nil { show("CONNECT A CONTROLLER FIRST") } else { screen = .calibration; calibration.start() }
    case .defaultMapping:
      host?.calibrationCleared()
      show("AUTOMATIC MAPPING RESTORED")
    case .restart:
      watchingLive = false
      drone.reset(); camera.snap(0); host?.recenterHead()
      screen = .flight
    case .exit: host?.exit(toLiveVideo: false)
    default: adjust(item, 1)
    }
  }

  /// Touch scrolling in the menu: moves the selection, which scrolls the list with it.
  func scroll(_ rows: Int) {
    if screen == .menu { selected = (selected + rows).clamped(0, menu.count - 1) }
  }

  func back() {
    switch screen {
    case .flight: openMenu()
    case .menu: screen = .flight
    case .controls, .calibration:
      calibration.cancel()
      screen = .menu
    }
  }

  private func adjust(_ item: MenuItem, _ by: Int) {
    let s = settings
    switch item {
    case .layout: s.classicLayout.toggle()
    case .invertClimb: s.invertClimb.toggle()
    case .invertLook: s.invertLook.toggle()
    case .vr: s.vr.toggle()
    case .head: s.headTracking.toggle(); host?.recenterHead()
    case .lens: s.lensCorrection.toggle()
    case .comfort: s.comfort.toggle()
    case .shadows: s.shadows.toggle()
    case .wind: s.wind.toggle()
    case .sound: s.sound.toggle()
    case .map:
      s.map = cycle(s.map, by, 0, MapData.FILES.count - 1)
      host?.settingsChanged()
      host?.mapChanged()
      return
    case .time: s.timeOfDay = cycle(s.timeOfDay, by, 0, Environment.TIMES.count - 1)
    case .weather: s.weather = cycle(s.weather, by, 0, Environment.WEATHERS.count - 1)
    case .sensitivity: s.sensitivity = cycle(s.sensitivity, by * 10, 50, 150)
    case .deadzone: s.deadzone = cycle(s.deadzone, by * 2, 4, 30)
    case .spacing: s.lensSpacing = cycle(s.lensSpacing, by * 2, -10, 10)
    default: return
    }
    host?.settingsChanged()
  }

  private func cycle(_ value: Int, _ step: Int, _ low: Int, _ high: Int) -> Int {
    let next = value + step
    return next > high ? low : next < low ? high : next
  }

  private func calibrate(_ dt: Float, _ pressed: Int, _ mapping: InputMapping, _ raw: [Float]) {
    if pressed & Button.START != 0 { calibration.cancel(); screen = .menu; return }
    if pressed & Button.B != 0 { calibration.skip() }
    if calibration.update(raw, dt, mapping) {
      host?.calibrationSaved(mapping.encode())
      screen = .menu
      show("CONTROLLER CALIBRATED")
    }
  }

  func padLost() {
    controllerName = nil
    if screen == .calibration { calibration.cancel(); screen = .menu }
    if screen == .flight && drone.airborne { show("CONTROLLER LOST - HOVERING", 4) }
  }
}

