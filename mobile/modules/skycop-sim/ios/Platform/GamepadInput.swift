import CoreHaptics
import GameController

/// Routes controller events to whoever is listening: the simulator while it is open, otherwise the
/// receiver screen's X and B shortcuts.
final class ControllerHub {
  static let shared = ControllerHub()
  /// Element-level events for the simulator.
  var simulatorHandler: ((GCExtendedGamepad, GCControllerElement) -> Void)?
  /// Receiver shortcuts: "x" or "b".
  var shortcutHandler: ((String) -> Void)?
  private var observers: [NSObjectProtocol] = []

  private init() {
    let center = NotificationCenter.default
    observers.append(center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] note in
      if let controller = note.object as? GCController { self?.hook(controller) }
    })
    GCController.controllers().forEach(hook)
    GCController.startWirelessControllerDiscovery {}
  }

  private func hook(_ controller: GCController) {
    guard let pad = controller.extendedGamepad else { return }
    pad.valueChangedHandler = { [weak self] gamepad, element in
      guard let self else { return }
      if let handler = self.simulatorHandler {
        handler(gamepad, element)
        return
      }
      if element === gamepad.buttonX, gamepad.buttonX.isPressed { self.shortcutHandler?("x") }
      if element === gamepad.buttonB, gamepad.buttonB.isPressed { self.shortcutHandler?("b") }
    }
  }
}

/// Reads the current controller each frame; latches presses so a short tap is never lost.
final class GamepadInput {
  let mapping = InputMapping()
  private(set) var raw = [Float](repeating: 0, count: Axis.COUNT)
  private var presses = 0
  private var held = 0
  private var engine: CHHapticEngine?
  private weak var current: GCController?
  private let defaults = UserDefaults.standard
  private(set) var name: String?

  func start() {
    ControllerHub.shared.simulatorHandler = { [weak self] pad, element in self?.handle(pad, element) }
    select(GCController.current ?? GCController.controllers().first { $0.extendedGamepad != nil })
  }

  func stop() {
    ControllerHub.shared.simulatorHandler = nil
    release()
    engine?.stop()
    engine = nil
  }

  func release() { presses = 0; held = 0 }

  private func key() -> String? { current.map { "mapping:\($0.vendorName ?? "controller")" } }

  private func select(_ controller: GCController?) {
    guard let controller, controller.extendedGamepad != nil else {
      current = nil; name = nil
      return
    }
    if controller === current { return }
    current = controller
    name = (controller.vendorName ?? "CONTROLLER").uppercased()
    mapping.configureStandard()
    if let key = key(), let saved = defaults.string(forKey: key), !mapping.decode(saved) { mapping.configureStandard() }
    engine = controller.haptics?.createEngine(withLocality: .default)
    try? engine?.start()
  }

  private func bit(_ pad: GCExtendedGamepad, _ element: GCControllerElement) -> Int {
    if element === pad.buttonA { return Button.A }
    if element === pad.buttonB { return Button.B }
    if element === pad.buttonX { return Button.X }
    if element === pad.buttonY { return Button.Y }
    if element === pad.leftShoulder { return Button.LB }
    if element === pad.rightShoulder { return Button.RB }
    if element === pad.buttonMenu { return Button.START }
    if let options = pad.buttonOptions, element === options { return Button.SELECT }
    if let thumb = pad.leftThumbstickButton, element === thumb { return Button.L3 }
    if let thumb = pad.rightThumbstickButton, element === thumb { return Button.R3 }
    return 0
  }

  private func handle(_ pad: GCExtendedGamepad, _ element: GCControllerElement) {
    if pad.controller !== current { select(pad.controller) }
    if element === pad.dpad {
      var next = 0
      if pad.dpad.up.isPressed { next |= Button.UP }
      if pad.dpad.down.isPressed { next |= Button.DOWN }
      if pad.dpad.left.isPressed { next |= Button.LEFT }
      if pad.dpad.right.isPressed { next |= Button.RIGHT }
      let dpadBits = Button.UP | Button.DOWN | Button.LEFT | Button.RIGHT
      presses |= next & ~(held & dpadBits)
      held = (held & ~dpadBits) | next
      return
    }
    let b = bit(pad, element)
    guard b != 0, let button = element as? GCControllerButtonInput else { return }
    if button.isPressed {
      if held & b == 0 { presses |= b }
      held |= b
    } else {
      held &= ~b
    }
  }

  /// Returns whether a controller is connected.
  func read(_ out: PadState, _ deadzone: Float) -> Bool {
    if current == nil || current?.extendedGamepad == nil {
      select(GCController.current ?? GCController.controllers().first { $0.extendedGamepad != nil })
    }
    guard let pad = current?.extendedGamepad else {
      out.clear()
      return false
    }
    // Android's axis layout with a key layout file: Y axes point down.
    raw[Axis.X] = pad.leftThumbstick.xAxis.value
    raw[Axis.Y] = -pad.leftThumbstick.yAxis.value
    raw[Axis.Z] = pad.rightThumbstick.xAxis.value
    raw[Axis.RZ] = -pad.rightThumbstick.yAxis.value
    raw[Axis.LTRIGGER] = pad.leftTrigger.value
    raw[Axis.RTRIGGER] = pad.rightTrigger.value
    let triggers = (pad.leftTrigger.isPressed ? Button.LT : 0) | (pad.rightTrigger.isPressed ? Button.RT : 0)
    mapping.read(raw, held | triggers, deadzone, out)
    return true
  }

  func takePresses() -> Int {
    let value = presses
    presses = 0
    return value
  }

  func saveMapping(_ encoded: String) {
    if let key = key() { defaults.set(encoded, forKey: key) }
  }

  func clearMapping() {
    if let key = key() { defaults.removeObject(forKey: key) }
    mapping.configureStandard()
  }

  func rumble(_ strength: Float) {
    guard let engine else { return }
    let event = CHHapticEvent(eventType: .hapticContinuous, parameters: [
      CHHapticEventParameter(parameterID: .hapticIntensity, value: strength),
      CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5),
    ], relativeTime: 0, duration: TimeInterval(0.06 + strength * 0.16))
    if let pattern = try? CHHapticPattern(events: [event], parameters: []), let player = try? engine.makePlayer(with: pattern) {
      try? player.start(atTime: CHHapticTimeImmediate)
    }
  }
}
