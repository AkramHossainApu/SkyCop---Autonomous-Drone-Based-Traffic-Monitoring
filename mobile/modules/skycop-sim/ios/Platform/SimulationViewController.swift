import MetalKit
import UIKit

/// Keeps a flight alive across a switch to live video, like Session.kt.
enum Session {
  static let settings = Settings()
  static var game: Game?
  static var switchedToLive = false
  static var city: City?
}

/// Full-screen landscape host for the simulator.
final class SimulationViewController: UIViewController, GameHost {
  private let liveAvailable: Bool
  private let onFinish: (String) -> Void
  private let gamepad = GamepadInput()
  private let head = HeadTracker()
  private let audio = RotorAudio()
  private var renderer: SimRenderer?
  private var metalView: MTKView?
  private var finished = false
  private var dragStartY: CGFloat = 0, dragLastY: CGFloat = 0, dragged = false
  private let defaults = UserDefaults.standard

  init(liveAvailable: Bool, onFinish: @escaping (String) -> Void) {
    self.liveAvailable = liveAvailable
    self.onFinish = onFinish
    super.init(nibName: nil, bundle: nil)
    modalPresentationStyle = .fullScreen
  }

  required init?(coder: NSCoder) { fatalError("Not used from storyboards") }

  override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
  override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .landscapeRight }
  override var prefersStatusBarHidden: Bool { true }
  override var prefersHomeIndicatorAutoHidden: Bool { true }
  override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .black
    if let saved = defaults.string(forKey: "skycop-simulation.settings") { Session.settings.decode(saved) }
    guard let device = MTLCreateSystemDefaultDevice() else { finish("error"); return }
    let metal = MTKView(frame: view.bounds, device: device)
    metal.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    metal.colorPixelFormat = .bgra8Unorm
    metal.framebufferOnly = true
    metal.preferredFramesPerSecond = UIScreen.main.maximumFramesPerSecond
    metal.isMultipleTouchEnabled = true
    view.addSubview(metal)
    metalView = metal

    let city = Session.city ?? City()
    Session.city = city
    let game: Game
    if let kept = Session.game {
      kept.attach(self, live: liveAvailable)
      game = kept
      if !Session.switchedToLive { game.openMenu() }
    } else {
      game = Game(city: city, settings: Session.settings, host: self, liveAvailable: liveAvailable)
      game.camera.snap(0)
      Session.game = game
    }
    Session.switchedToLive = false
    do {
      let renderer = try SimRenderer(view: metal, game: game, gamepad: gamepad, head: head, audio: audio)
      self.renderer = renderer
      metal.delegate = renderer
      renderer.mtkView(metal, drawableSizeWillChange: metal.drawableSize)
    } catch {
      NSLog("SkyCop simulation failed to start: %@", String(describing: error))
      finish("error")
    }
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    gamepad.start()
    head.start()
    audio.start()
    renderer?.resume()
    UIApplication.shared.isIdleTimerDisabled = true
  }

  override func viewWillDisappear(_ animated: Bool) {
    super.viewWillDisappear(animated)
    gamepad.stop()
    head.stop()
    audio.stop()
    UIApplication.shared.isIdleTimerDisabled = false
  }

  override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
    super.viewWillTransition(to: size, with: coordinator)
    coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.head.recenter() }
  }

  // MARK: Touch

  override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let renderer, let view = metalView else { return }
    let size = view.bounds.size
    for t in touches {
      let p = t.location(in: view)
      if renderer.game.screen == .flight && !Session.settings.vr && renderer.game.controllerName == nil {
        renderer.touchPresses |= renderer.touch.began(ObjectIdentifier(t), Float(p.x), Float(p.y), Float(size.width), Float(size.height))
      } else {
        dragStartY = p.y; dragLastY = p.y; dragged = false
      }
    }
  }

  override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let renderer, let view = metalView else { return }
    let size = view.bounds.size
    for t in touches {
      let p = t.location(in: view)
      renderer.touch.moved(ObjectIdentifier(t), Float(p.x), Float(p.y), Float(size.width), Float(size.height))
      if renderer.game.screen != .flight || Session.settings.vr {
        if abs(p.y - dragStartY) > 12 { dragged = true }
        let rowPoints = size.height * 34 / 540
        if dragged && abs(p.y - dragLastY) >= rowPoints {
          renderer.drag(Float(p.y - dragLastY), Float(size.height))
          dragLastY = p.y
        }
      }
    }
  }

  override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
    guard let renderer, let view = metalView else { return }
    let size = view.bounds.size
    for t in touches {
      renderer.touch.ended(ObjectIdentifier(t))
      let flightSticks = renderer.game.screen == .flight && !Session.settings.vr && renderer.game.controllerName == nil
      if !flightSticks && !dragged {
        let p = t.location(in: view)
        renderer.touchPresses |= renderer.tap(Float(p.x), Float(p.y), Float(size.width), Float(size.height))
      }
    }
  }

  override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
    renderer?.touch.clear()
  }

  // MARK: GameHost

  private func finish(_ result: String) {
    guard !finished else { return }
    finished = true
    metalView?.isPaused = true
    dismiss(animated: false) { [onFinish] in onFinish(result) }
  }

  func exit(toLiveVideo: Bool) {
    DispatchQueue.main.async {
      if toLiveVideo { Session.switchedToLive = true } else { Session.game = nil; Session.switchedToLive = false }
      self.finish(toLiveVideo ? "live" : "exit")
    }
  }

  func settingsChanged() { defaults.set(Session.settings.encode(), forKey: "skycop-simulation.settings") }
  func calibrationSaved(_ mapping: String) { gamepad.saveMapping(mapping) }
  func calibrationCleared() { gamepad.clearMapping() }
  func recenterHead() { head.recenter() }
  func rumble(_ strength: Float) { gamepad.rumble(strength) }
}
