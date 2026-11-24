import MetalKit
import UIKit
import os

/// The game loop (SimRenderer.kt), run from MTKView's display link on the main thread.
final class SimRenderer: NSObject, MTKViewDelegate {
  private static let EYE_OFFSET: Float = 0.032, MAX_RENDER_HEIGHT = 1080
  private static let log = Logger(subsystem: "com.skycop.sim", category: "SkyCopSim")

  private(set) var game: Game
  private let settings: Settings
  private let gamepad: GamepadInput
  private let head: HeadTracker
  let touch = TouchSticks()
  private let audio: RotorAudio
  private let device: MTLDevice
  private let queue: MTLCommandQueue
  private let world: WorldRenderer
  private let live: LiveScreen
  private var startLive: Bool
  private let hud = Hud()
  private let hudPipeline: MTLRenderPipelineState
  private let presentPipeline: MTLRenderPipelineState
  private let font: MTLTexture
  private let fontSampler: MTLSamplerState
  private let imageSampler: MTLSamplerState
  private var hudBuffers: [MTLBuffer]
  private let inFlight = DispatchSemaphore(value: 3)
  private var frameIndex = 0
  private var sceneColor: MTLTexture?
  private var msaaColor: MTLTexture?
  private var msaaDepth: MTLTexture?
  private var targetSize = (0, 0)
  private let governor = QualityGovernor()
  private let pacer = FramePacer(period: 1 / 60)
  private let pad = PadState()
  private var headPose = [Float](repeating: 0, count: 16)
  private var lastFrame: CFTimeInterval = 0
  private var vignette: Float = 0
  private var hadPad = false
  private var resumed = true
  var touchPresses = 0
  var debug = UserDefaults.standard.bool(forKey: "SkyCopDebug")
  private var screenWidth = 1, screenHeight = 1

  init(view: MTKView, game: Game, gamepad: GamepadInput, head: HeadTracker, audio: RotorAudio, startLive: Bool) throws {
    guard let device = view.device, let queue = device.makeCommandQueue() else { throw NSError(domain: "SkyCop", code: 1) }
    self.device = device
    self.queue = queue
    self.game = game
    self.settings = game.settings
    self.gamepad = gamepad
    self.head = head
    self.audio = audio
    let library = try device.makeLibrary(source: MetalShaders.source, options: nil)
    world = try WorldRenderer(device: device, library: library, city: game.city, colorFormat: .bgra8Unorm, samples: 4)
    live = try LiveScreen(device: device, colorFormat: .bgra8Unorm, samples: 4)
    self.startLive = startLive

    let hudVertex = MTLVertexDescriptor()
    hudVertex.attributes[0].format = .float2; hudVertex.attributes[0].offset = 0; hudVertex.attributes[0].bufferIndex = 0
    hudVertex.attributes[1].format = .float2; hudVertex.attributes[1].offset = 8; hudVertex.attributes[1].bufferIndex = 0
    hudVertex.attributes[2].format = .float4; hudVertex.attributes[2].offset = 16; hudVertex.attributes[2].bufferIndex = 0
    hudVertex.layouts[0].stride = 32
    let h = MTLRenderPipelineDescriptor()
    h.vertexFunction = library.makeFunction(name: "hudVertex")
    h.fragmentFunction = library.makeFunction(name: "hudFragment")
    h.vertexDescriptor = hudVertex
    h.colorAttachments[0].pixelFormat = .bgra8Unorm
    h.colorAttachments[0].isBlendingEnabled = true
    h.colorAttachments[0].sourceRGBBlendFactor = .sourceAlpha
    h.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
    hudPipeline = try device.makeRenderPipelineState(descriptor: h)
    let p = MTLRenderPipelineDescriptor()
    p.vertexFunction = library.makeFunction(name: "fullscreenVertex")
    p.fragmentFunction = library.makeFunction(name: "presentFragment")
    p.colorAttachments[0].pixelFormat = view.colorPixelFormat
    presentPipeline = try device.makeRenderPipelineState(descriptor: p)
    font = SimRenderer.makeFont(device)
    let s = MTLSamplerDescriptor()
    s.minFilter = .linear; s.magFilter = .linear; s.mipFilter = .linear
    fontSampler = device.makeSamplerState(descriptor: s)!
    let i = MTLSamplerDescriptor()
    i.minFilter = .linear; i.magFilter = .linear
    imageSampler = device.makeSamplerState(descriptor: i)!
    hudBuffers = (0..<3).map { _ in device.makeBuffer(length: HudGeometry.MAX_VERTICES * 32, options: .storageModeShared)! }
    super.init()
  }

  /// The glyph atlas, drawn once with Core Text (same cell grid as HudBatch.kt).
  private static func makeFont(_ device: MTLDevice) -> MTLTexture {
    let cw = Int(HudGeometry.CELL_W), ch = Int(HudGeometry.CELL_H)
    let width = cw * HudGeometry.COLUMNS, height = ch * HudGeometry.ROWS
    var pixels = [UInt8](repeating: 0, count: width * height)
    let space = CGColorSpaceCreateDeviceGray()
    pixels.withUnsafeMutableBytes { raw in
      guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                                    space: space, bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
      context.translateBy(x: 0, y: CGFloat(height))
      context.scaleBy(x: 1, y: -1)
      UIGraphicsPushContext(context)
      let font = UIFont.monospacedSystemFont(ofSize: 44, weight: .bold)
      let style = NSMutableParagraphStyle()
      style.alignment = .center
      let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white, .paragraphStyle: style]
      for index in 0..<HudGeometry.SOLID {
        let character = String(UnicodeScalar(UInt8(index + 32)))
        let x = CGFloat(index % HudGeometry.COLUMNS * cw), y = CGFloat(index / HudGeometry.COLUMNS * ch)
        let rect = CGRect(x: x, y: y + CGFloat(ch) * 0.79 - font.ascender, width: CGFloat(cw), height: CGFloat(ch))
        (character as NSString).draw(in: rect, withAttributes: attributes)
      }
      let sx = CGFloat(HudGeometry.SOLID % HudGeometry.COLUMNS * cw), sy = CGFloat(HudGeometry.SOLID / HudGeometry.COLUMNS * ch)
      context.setFillColor(gray: 1, alpha: 1)
      context.fill(CGRect(x: sx, y: sy, width: CGFloat(cw), height: CGFloat(ch)))
      UIGraphicsPopContext()
    }
    let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: width, height: height, mipmapped: true)
    d.usage = .shaderRead
    let texture = device.makeTexture(descriptor: d)!
    texture.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: pixels, bytesPerRow: width)
    if let queue = device.makeCommandQueue(), let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() {
      blit.generateMipmaps(for: texture)
      blit.endEncoding(); commands.commit(); commands.waitUntilCompleted()
    }
    return texture
  }

  func resume() { resumed = true }

  /// A new flight on another map: the city's meshes are swapped, shaders and textures stay.
  func replace(_ next: Game) {
    game = next
    world.load(next.city)
    resumed = true
  }

  /// Vertical drag in points while a menu is open.
  func drag(_ points: Float, _ height: Float) {
    let rows = Int(points / height * 540 / 34)
    if rows != 0 { game.scroll(-rows) }
  }

  func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
    screenWidth = max(1, Int(size.width)); screenHeight = max(1, Int(size.height))
    let shrink = min(1, Double(SimRenderer.MAX_RENDER_HEIGHT) / Double(screenHeight))
    let w = Int(Double(screenWidth) * shrink), h = Int(Double(screenHeight) * shrink)
    if (w, h) == targetSize { return }
    targetSize = (w, h)
    let color = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
    color.usage = [.renderTarget, .shaderRead]
    color.storageMode = .private
    sceneColor = device.makeTexture(descriptor: color)
    let msaa = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false)
    msaa.textureType = .type2DMultisample; msaa.sampleCount = 4; msaa.usage = .renderTarget
    msaa.storageMode = .memoryless
    msaaColor = device.makeTexture(descriptor: msaa)
    let depth = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .depth32Float, width: w, height: h, mipmapped: false)
    depth.textureType = .type2DMultisample; depth.sampleCount = 4; depth.usage = .renderTarget
    depth.storageMode = .memoryless
    msaaDepth = device.makeTexture(descriptor: depth)
  }

  func draw(in view: MTKView) {
    let now = CACurrentMediaTime()
    if resumed {
      resumed = false
      lastFrame = now
      governor.start(Float(view.window?.screen.maximumFramesPerSecond ?? 60), 0)
      pacer.reset()
    }
    let elapsed = Float(now - lastFrame)
    lastFrame = now
    let measured = governor.frame(elapsed)
    pacer.period = governor.period
    let dt = elapsed == 0 ? 0 : pacer.next(elapsed)

    let connected = gamepad.read(pad, Float(settings.deadzone) / 100)
    if connected { game.controllerName = gamepad.name } else if hadPad { game.padLost() }
    hadPad = connected
    if !connected && !settings.vr && game.screen == .flight {
      pad.leftX += touch.x[0]; pad.leftY += touch.y[0]
      pad.rightX += touch.x[1]; pad.rightY += touch.y[1]
    }
    let pressed = gamepad.takePresses() | touchPresses
    touchPresses = 0

    game.liveAvailable = LiveFeed.shared.available
    if startLive {
      startLive = false
      game.watchLive(only: true)
    }
    head.orientation = view.window?.windowScene?.interfaceOrientation ?? .landscapeRight
    let tracked = settings.headTracking && settings.vr && head.available
    head.read(&headPose, tracked)
    game.update(dt, pad, pressed, gamepad.mapping, gamepad.raw, headPose, tracked)
    game.headSensorMissing = settings.headTracking && settings.vr && !head.available
    audio.set(game.drone.render.rotor, settings.sound && game.screen == .flight && !game.watchingLive)

    render(view, dt)
    if measured && debug { report() }
  }

  private func render(_ view: MTKView, _ dt: Float) {
    guard let sceneColor, let msaaColor, let msaaDepth, let drawable = view.currentDrawable else { return }
    inFlight.wait()
    guard let commands = queue.makeCommandBuffer() else { inFlight.signal(); return }
    commands.addCompletedHandler { [inFlight] _ in inFlight.signal() }
    frameIndex = (frameIndex + 1) % 3

    let eyes = settings.vr ? 2 : 1
    let usedWidth = max(2, Int(Float(targetSize.0) * governor.scale) / 2 * 2)
    let usedHeight = max(2, Int(Float(targetSize.1) * governor.scale))
    let eyeWidth = usedWidth / eyes
    let aspect = Float(screenWidth) / Float(eyes) / Float(screenHeight)
    let tanHalf = settings.vr ? tan(43 * DEG) : tan(31 * DEG)

    let watching = game.watchingLive
    if watching {
      live.upload()
      game.liveHasVideo = live.hasVideo
    } else {
      world.prepare(game, QUALITY_LEVELS[governor.level], frameIndex, commands)
    }
    hud.build(game, pad, aspect, governor.fps, touch)
    let hudBuffer = hudBuffers[frameIndex]
    _ = hud.batch.data.withUnsafeBytes { memcpy(hudBuffer.contents(), $0.baseAddress!, hud.batch.count * 32) }

    let scene = MTLRenderPassDescriptor()
    scene.colorAttachments[0].texture = msaaColor
    scene.colorAttachments[0].resolveTexture = sceneColor
    scene.colorAttachments[0].loadAction = .clear
    scene.colorAttachments[0].storeAction = .multisampleResolve
    scene.colorAttachments[0].clearColor = watching ? LiveScreen.ROOM : MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
    scene.depthAttachment.texture = msaaDepth
    scene.depthAttachment.loadAction = .clear
    scene.depthAttachment.storeAction = .dontCare
    scene.depthAttachment.clearDepth = 1
    if let encoder = commands.makeRenderCommandEncoder(descriptor: scene) {
      let basis = watching ? game.liveBasis : game.camera.basis, eye = game.camera.eye
      for i in 0..<eyes {
        encoder.setViewport(MTLViewport(originX: Double(i * eyeWidth), originY: 0, width: Double(eyeWidth), height: Double(usedHeight), znear: 0, zfar: 1))
        encoder.setScissorRect(MTLScissorRect(x: i * eyeWidth, y: 0, width: eyeWidth, height: usedHeight))
        let offset: Float = eyes == 1 ? 0 : Float(i * 2 - 1) * SimRenderer.EYE_OFFSET
        if watching {
          live.drawEye(encoder, basis, 0, 0, 0, tanHalf, aspect, settings.vr)
        } else {
          world.drawEye(encoder, basis, eye[0] + basis[0] * offset, eye[1] + basis[1] * offset, eye[2] + basis[2] * offset, tanHalf, aspect)
        }
      }
      encoder.endEncoding()
    }

    let overlay = MTLRenderPassDescriptor()
    overlay.colorAttachments[0].texture = sceneColor
    overlay.colorAttachments[0].loadAction = .load
    overlay.colorAttachments[0].storeAction = .store
    if hud.batch.count > 0, let encoder = commands.makeRenderCommandEncoder(descriptor: overlay) {
      encoder.setRenderPipelineState(hudPipeline)
      encoder.setVertexBuffer(hudBuffer, offset: 0, index: 0)
      var half = SIMD2<Float>(Hud.HALF_HEIGHT * aspect / hud.scale, Hud.HALF_HEIGHT / hud.scale)
      encoder.setVertexBytes(&half, length: 8, index: 1)
      encoder.setFragmentTexture(font, index: 0)
      encoder.setFragmentSamplerState(fontSampler, index: 0)
      for i in 0..<eyes {
        encoder.setViewport(MTLViewport(originX: Double(i * eyeWidth), originY: 0, width: Double(eyeWidth), height: Double(usedHeight), znear: 0, zfar: 1))
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: hud.batch.count)
      }
      encoder.endEncoding()
    }

    let wanted: Float = settings.vr && settings.comfort ? ((game.camera.turnRate - 0.35) * 0.8).clamped(0, 0.7) : 0
    vignette += (wanted - vignette) * blend(6, dt)
    if let pass = view.currentRenderPassDescriptor {
      pass.colorAttachments[0].loadAction = .clear
      pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
      if let encoder = commands.makeRenderCommandEncoder(descriptor: pass) {
        encoder.setRenderPipelineState(presentPipeline)
        encoder.setFragmentTexture(sceneColor, index: 0)
        encoder.setFragmentSamplerState(imageSampler, index: 0)
        for i in 0..<eyes {
          encoder.setViewport(MTLViewport(originX: Double(i * screenWidth / eyes), originY: 0, width: Double(screenWidth / eyes), height: Double(screenHeight), znear: 0, zfar: 1))
          let part = Float(eyeWidth * eyes) / Float(targetSize.0) / Float(eyes)
          var params = (
            SIMD4<Float>(Float(i) * part, 0, part, Float(usedHeight) / Float(targetSize.1)),
            SIMD4<Float>(0.5 / Float(targetSize.0), 0.5 / Float(targetSize.1), Float(screenWidth) / Float(eyes) / Float(screenHeight), lensShift(i)),
            SIMD4<Float>(0, 0, vignette, 0))
          encoder.setFragmentBytes(&params, length: MemoryLayout<(SIMD4<Float>, SIMD4<Float>, SIMD4<Float>)>.stride, index: 0)
          encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        }
        encoder.endEncoding()
      }
    }
    commands.present(drawable)
    commands.commit()
  }

  private func lensShift(_ eye: Int) -> Float { settings.vr ? Float(eye * 2 - 1) * Float(settings.lensSpacing) / 100 : 0 }

  /// A tap in the headset opens the menu; in the menu it picks the row under the finger.
  func tap(_ px: Float, _ py: Float, _ width: Float, _ height: Float) -> Int {
    // A tap while the live view is still opening must not count as a press in it (Start would exit).
    if startLive { return 0 }
    if game.screen != .flight && game.screen != .menu { game.back(); return 0 }
    let eyes = settings.vr ? 2 : 1
    let eyeWidth = width / Float(eyes)
    let eye = Int(px / eyeWidth).clamped(0, eyes - 1)
    let x = (px - Float(eye) * eyeWidth) / eyeWidth * 2 - 1 - lensShift(eye)
    let y = py / height * 2 - 1
    let aspect = eyeWidth / height
    let hudX = x * Hud.HALF_HEIGHT * aspect / hud.scale, hudY = y * Hud.HALF_HEIGHT / hud.scale
    if game.screen == .flight && game.watchingLive && game.liveOnly {
      game.liveTap(onExit: hud.exitHit(hudX, hudY))
      return 0
    }
    if game.screen == .flight { return settings.vr || game.watchingLive ? Button.START : 0 }
    let row = hud.menuRowAt(hudX, hudY)
    if row >= 0 { game.activate(row) }
    return 0
  }

  private func report() {
    let d = game.drone, p = d.pose, b = game.camera.basis
    SimRenderer.log.notice("fps=\(self.governor.fps) worst=\(self.governor.worstFrameMs) scale=\(self.governor.scale) level=\(self.governor.level) screen=\(String(describing: self.game.screen)) phase=\(d.phase.label) mode=\(d.mode.label) x=\(p.x) y=\(p.y) z=\(p.z) yaw=\(p.yaw) speed=\(d.speed) view=\(self.game.camera.view.label) forward=\(-b[8]),\(-b[9]),\(-b[10]) pad=\(self.game.controllerName ?? "none")")
  }
}
