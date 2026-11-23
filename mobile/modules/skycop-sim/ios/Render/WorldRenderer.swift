import Metal
import simd
import Foundation

func matrix(_ m: [Float]) -> simd_float4x4 {
  simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
}

/// OpenGL clip depth runs -1...1, Metal's 0...1.
let clipFix = simd_float4x4(SIMD4(1, 0, 0, 0), SIMD4(0, 1, 0, 0), SIMD4(0, 0, 0.5, 0), SIMD4(0, 0, 0.5, 1))

struct FrameUniforms {
  var viewProjection: simd_float4x4
  var light: simd_float4x4
  var eye: SIMD4<Float>
  var params: SIMD4<Float>
  var params2: SIMD4<Float>
}

/// A static mesh, optionally drawn many times from a per-frame instance buffer.
final class GpuMesh {
  let buffer: MTLBuffer?
  let count: Int
  private let maxInstances: Int
  private var instanceBuffers: [MTLBuffer] = []
  private var pointer: UnsafeMutablePointer<Float>?
  private(set) var instanceCount = 0
  private var slot = 0

  init(_ device: MTLDevice, _ builder: MeshBuilder, maxInstances: Int = 0) {
    count = builder.vertices
    buffer = count == 0 ? nil : builder.data.withUnsafeBytes { device.makeBuffer(bytes: $0.baseAddress!, length: $0.count, options: .storageModeShared) }
    self.maxInstances = maxInstances
    if maxInstances > 0 {
      instanceBuffers = (0..<3).map { _ in device.makeBuffer(length: maxInstances * 32, options: .storageModeShared)! }
    }
  }

  func beginInstances(_ frame: Int) {
    guard maxInstances > 0 else { return }
    slot = frame
    pointer = instanceBuffers[frame].contents().bindMemory(to: Float.self, capacity: maxInstances * 8)
    instanceCount = 0
  }

  func addInstance(_ x: Float, _ y: Float, _ z: Float, _ yaw: Float, _ color: Int) {
    guard let p = pointer, instanceCount < maxInstances else { return }
    let at = instanceCount * 8
    p[at] = x; p[at + 1] = y; p[at + 2] = z; p[at + 3] = yaw
    p[at + 4] = Float((color >> 16) & 255) / 255; p[at + 5] = Float((color >> 8) & 255) / 255
    p[at + 6] = Float(color & 255) / 255; p[at + 7] = 1
    instanceCount += 1
  }

  func draw(_ encoder: MTLRenderCommandEncoder, single: MTLBuffer) {
    guard let buffer, count > 0 else { return }
    encoder.setVertexBuffer(buffer, offset: 0, index: 0)
    if maxInstances == 0 {
      encoder.setVertexBuffer(single, offset: 0, index: 1)
      encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: count)
    } else if instanceCount > 0 {
      encoder.setVertexBuffer(instanceBuffers[slot], offset: 0, index: 1)
      encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: count, instanceCount: instanceCount)
    }
  }
}

/// The city, traffic and drone (WorldRenderer.kt), drawn with Metal.
final class WorldRenderer {
  static let SHADOW_SIZE = 2048
  private static let SHADOW_HALF: Float = 85, NEAR: Float = 0.15, FAR: Float = 1400

  private let device: MTLDevice
  private var city: City
  let worldPipeline: MTLRenderPipelineState
  private let shadowPipeline: MTLRenderPipelineState
  private let skyPipeline: MTLRenderPipelineState
  private let glowPipeline: MTLRenderPipelineState
  private let depthWrite: MTLDepthStencilState
  private let depthRead: MTLDepthStencilState
  private let linear: MTLSamplerState
  private let compare: MTLSamplerState
  let shadowMap: MTLTexture
  private let albedo: MTLTexture
  private let relief: MTLTexture
  private let single: MTLBuffer

  private var terrain: GpuMesh
  private var blocks: [GpuMesh]
  private var details: [GpuMesh]
  private let droneBody: GpuMesh
  private let propeller: GpuMesh
  private let rotorDisc: GpuMesh
  private let vehicles: [GpuMesh]

  private var droneMatrix = [Float](repeating: 0, count: 16)
  private var propMatrices = [[Float]](repeating: [Float](repeating: 0, count: 16), count: 4)
  private var discMatrices = [[Float]](repeating: [Float](repeating: 0, count: 16), count: 4)
  private var local = [Float](repeating: 0, count: 16)
  private var projection = [Float](repeating: 0, count: 16)
  private var view = [Float](repeating: 0, count: 16)
  private var viewProjection = [Float](repeating: 0, count: 16)
  private var lightBasis = [Float](repeating: 0, count: 16)
  private var light = [Float](repeating: 0, count: 16)
  private var lightMetal = matrix_identity_float4x4
  private var planes = [Float](repeating: 0, count: 24)
  private var identity = [Float](repeating: 0, count: 16)
  private var quality = QUALITY_LEVELS[0]
  private var showDrone = true, showShadows = true
  private var rotor: Float = 0, time: Float = 0, signalTime: Float = 0

  init(device: MTLDevice, library: MTLLibrary, city: City, colorFormat: MTLPixelFormat, samples: Int) throws {
    self.device = device
    self.city = city
    Mat4.identity(&identity)

    let vertexDescriptor = MTLVertexDescriptor()
    vertexDescriptor.attributes[0].format = .float3; vertexDescriptor.attributes[0].offset = 0; vertexDescriptor.attributes[0].bufferIndex = 0
    vertexDescriptor.attributes[1].format = .char4Normalized; vertexDescriptor.attributes[1].offset = 12; vertexDescriptor.attributes[1].bufferIndex = 0
    vertexDescriptor.attributes[2].format = .uchar4Normalized; vertexDescriptor.attributes[2].offset = 16; vertexDescriptor.attributes[2].bufferIndex = 0
    vertexDescriptor.attributes[3].format = .float4; vertexDescriptor.attributes[3].offset = 0; vertexDescriptor.attributes[3].bufferIndex = 1
    vertexDescriptor.attributes[4].format = .float4; vertexDescriptor.attributes[4].offset = 16; vertexDescriptor.attributes[4].bufferIndex = 1
    vertexDescriptor.layouts[0].stride = 20
    vertexDescriptor.layouts[1].stride = 32
    vertexDescriptor.layouts[1].stepFunction = .perInstance

    func pipeline(_ vertex: String, _ fragment: String?, color: Bool, blend: Bool = false, samples: Int, depth: Bool = true, descriptor: MTLVertexDescriptor? = nil) throws -> MTLRenderPipelineState {
      let d = MTLRenderPipelineDescriptor()
      d.vertexFunction = library.makeFunction(name: vertex)
      d.fragmentFunction = fragment.flatMap { library.makeFunction(name: $0) }
      d.vertexDescriptor = descriptor
      d.rasterSampleCount = samples
      if color {
        d.colorAttachments[0].pixelFormat = colorFormat
        if blend {
          d.colorAttachments[0].isBlendingEnabled = true
          d.colorAttachments[0].sourceRGBBlendFactor = .sourceAlpha
          d.colorAttachments[0].destinationRGBBlendFactor = .oneMinusSourceAlpha
          d.colorAttachments[0].sourceAlphaBlendFactor = .one
          d.colorAttachments[0].destinationAlphaBlendFactor = .oneMinusSourceAlpha
        }
      }
      if depth { d.depthAttachmentPixelFormat = .depth32Float }
      return try device.makeRenderPipelineState(descriptor: d)
    }
    worldPipeline = try pipeline("worldVertex", "worldFragment", color: true, samples: samples, descriptor: vertexDescriptor)
    shadowPipeline = try pipeline("shadowVertex", nil, color: false, samples: 1, descriptor: vertexDescriptor)
    skyPipeline = try pipeline("fullscreenVertex", "skyFragment", color: true, samples: samples)
    glowPipeline = try pipeline("glowVertex", "glowFragment", color: true, blend: true, samples: samples, descriptor: vertexDescriptor)

    let write = MTLDepthStencilDescriptor()
    write.depthCompareFunction = .lessEqual; write.isDepthWriteEnabled = true
    depthWrite = device.makeDepthStencilState(descriptor: write)!
    let read = MTLDepthStencilDescriptor()
    read.depthCompareFunction = .lessEqual; read.isDepthWriteEnabled = false
    depthRead = device.makeDepthStencilState(descriptor: read)!

    let s = MTLSamplerDescriptor()
    s.minFilter = .linear; s.magFilter = .linear; s.mipFilter = .linear
    s.sAddressMode = .repeat; s.tAddressMode = .repeat
    s.maxAnisotropy = 8
    linear = device.makeSamplerState(descriptor: s)!
    let c = MTLSamplerDescriptor()
    c.minFilter = .linear; c.magFilter = .linear
    c.sAddressMode = .clampToEdge; c.tAddressMode = .clampToEdge
    c.compareFunction = .lessEqual
    compare = device.makeSamplerState(descriptor: c)!

    let shadow = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .depth32Float, width: WorldRenderer.SHADOW_SIZE, height: WorldRenderer.SHADOW_SIZE, mipmapped: false)
    shadow.usage = [.renderTarget, .shaderRead]
    shadow.storageMode = .private
    shadowMap = device.makeTexture(descriptor: shadow)!

    let pixels = Textures.get()
    func array(_ format: MTLPixelFormat, _ bytes: [UInt8], _ channels: Int) -> MTLTexture {
      let d = MTLTextureDescriptor()
      d.textureType = .type2DArray
      d.pixelFormat = format
      d.width = Textures.SIZE; d.height = Textures.SIZE
      d.arrayLength = Tex.allCases.count
      d.mipmapLevelCount = Int(log2(Double(Textures.SIZE))) + 1
      d.usage = .shaderRead
      let texture = device.makeTexture(descriptor: d)!
      let layer = Textures.SIZE * Textures.SIZE * channels
      bytes.withUnsafeBytes { raw in
        for slice in 0..<Tex.allCases.count {
          texture.replace(region: MTLRegionMake2D(0, 0, Textures.SIZE, Textures.SIZE), mipmapLevel: 0, slice: slice,
                          withBytes: raw.baseAddress! + slice * layer, bytesPerRow: Textures.SIZE * channels, bytesPerImage: layer)
        }
      }
      return texture
    }
    albedo = array(.rgba8Unorm, pixels.color, 4)
    relief = array(.rg8Unorm, pixels.normal, 2)
    if let queue = device.makeCommandQueue(), let commands = queue.makeCommandBuffer(), let blit = commands.makeBlitCommandEncoder() {
      blit.generateMipmaps(for: albedo)
      blit.generateMipmaps(for: relief)
      blit.endEncoding()
      commands.commit()
      commands.waitUntilCompleted()
    }

    var one: [Float] = [0, 0, 0, 0, 1, 1, 1, 1]
    single = device.makeBuffer(bytes: &one, length: 32, options: .storageModeShared)!

    terrain = GpuMesh(device, city.terrain)
    blocks = city.chunks.map { GpuMesh(device, $0.mesh) }
    details = city.chunks.map { GpuMesh(device, $0.detail) }
    droneBody = GpuMesh(device, Models.drone())
    propeller = GpuMesh(device, Models.propeller())
    rotorDisc = GpuMesh(device, Models.rotorDisc())
    vehicles = Models.vehicles().map { GpuMesh(device, $0, maxInstances: Traffic.MAX_VEHICLES) }

    // Same direction as SUN in the shaders.
    let length = sqrt(Float(0.45 * 0.45 + 0.78 * 0.78 + 0.38 * 0.38))
    let bx: Float = 0.45 / length, by: Float = 0.78 / length, bz: Float = 0.38 / length
    let h = hypot(bx, bz)
    let rx = bz / h, rz = -bx / h
    lightBasis[0] = rx; lightBasis[2] = rz
    lightBasis[4] = by * rz; lightBasis[5] = bz * rx - bx * rz; lightBasis[6] = -by * rx
    lightBasis[8] = bx; lightBasis[9] = by; lightBasis[10] = bz
  }

  func prepare(_ game: Game, _ level: QualityLevel, _ frame: Int, _ commands: MTLCommandBuffer) {
    quality = level
    time = fmodf(game.clock, 3600)
    signalTime = fmodf(game.traffic.time, Signals.CYCLE)
    showShadows = game.settings.shadows && level.shadows
    let pose = game.drone.render
    let eye = game.camera.eye
    showDrone = game.camera.view != .onboard
    rotor = pose.rotor
    Mat4.pose(&droneMatrix, pose.x, pose.y, pose.z, pose.yaw, pose.pitch, pose.roll)
    for i in 0...3 {
      let spin = i == 0 || i == 3 ? game.propellerAngle : -game.propellerAngle
      Mat4.pose(&local, Models.MOTOR_X[i], Models.MOTOR_Y[i], Models.MOTOR_Z[i], spin + Float(i) * 0.7, 0, 0)
      Mat4.multiply(&propMatrices[i], droneMatrix, local)
      Mat4.pose(&local, Models.MOTOR_X[i], Models.MOTOR_Y[i], Models.MOTOR_Z[i], 0, 0, 0)
      Mat4.multiply(&discMatrices[i], droneMatrix, local)
    }

    for mesh in vehicles { mesh.beginInstances(frame) }
    let traffic = game.traffic
    let b = game.camera.basis
    for car in 0..<traffic.count {
      let x = traffic.x[car], z = traffic.z[car]
      let dx = x - eye[0], dz = z - eye[2]
      if abs(dx) > level.carRange || abs(dz) > level.carRange { continue }
      if abs(dx) + abs(dz) > 70 && -(dx * b[8] + dz * b[10]) < -30 && abs(b[9]) < 0.45 { continue }
      vehicles[traffic.type[car]].addInstance(x, traffic.y[car], z, traffic.yaw[car], traffic.color[car])
    }
    if showShadows { renderShadowMap(pose, commands) }
  }

  private func setModel(_ encoder: MTLRenderCommandEncoder, _ m: [Float]) {
    var model = matrix(m)
    encoder.setVertexBytes(&model, length: MemoryLayout<simd_float4x4>.size, index: 3)
  }

  private func renderShadowMap(_ pose: DronePose, _ commands: MTLCommandBuffer) {
    let cx = pose.x + sin(pose.yaw) * 30, cy: Float = 10, cz = pose.z - cos(pose.yaw) * 30
    let texel = 2 * WorldRenderer.SHADOW_HALF / Float(WorldRenderer.SHADOW_SIZE)
    let b = lightBasis
    let sx = floor((cx * b[0] + cy * b[1] + cz * b[2]) / texel) * texel
    let sy = floor((cx * b[4] + cy * b[5] + cz * b[6]) / texel) * texel
    let sz = cx * b[8] + cy * b[9] + cz * b[10] + 220
    Mat4.view(&view, b, b[0] * sx + b[4] * sy + b[8] * sz, b[1] * sx + b[5] * sy + b[9] * sz, b[2] * sx + b[6] * sy + b[10] * sz)
    Mat4.ortho(&projection, WorldRenderer.SHADOW_HALF, 20, 420)
    Mat4.multiply(&light, projection, view)
    lightMetal = clipFix * matrix(light)

    let pass = MTLRenderPassDescriptor()
    pass.depthAttachment.texture = shadowMap
    pass.depthAttachment.loadAction = .clear
    pass.depthAttachment.storeAction = .store
    pass.depthAttachment.clearDepth = 1
    guard let encoder = commands.makeRenderCommandEncoder(descriptor: pass) else { return }
    encoder.setRenderPipelineState(shadowPipeline)
    encoder.setDepthStencilState(depthWrite)
    encoder.setCullMode(.none)
    encoder.setDepthBias(0.0008, slopeScale: 2.5, clamp: 0.01)
    var uniforms = FrameUniforms(viewProjection: lightMetal, light: lightMetal, eye: .zero, params: .zero, params2: .zero)
    encoder.setVertexBytes(&uniforms, length: MemoryLayout<FrameUniforms>.stride, index: 2)
    setModel(encoder, identity)
    extractPlanes(light)
    for i in blocks.indices where visible(city.chunks[i]) {
      blocks[i].draw(encoder, single: single)
      if hypot(city.chunks[i].x - pose.x, city.chunks[i].z - pose.z) < quality.shadowDetailRange { details[i].draw(encoder, single: single) }
    }
    for mesh in vehicles { mesh.draw(encoder, single: single) }
    setModel(encoder, droneMatrix)
    droneBody.draw(encoder, single: single)
    encoder.endEncoding()
  }

  func drawEye(_ encoder: MTLRenderCommandEncoder, _ basis: [Float], _ x: Float, _ y: Float, _ z: Float, _ tanHalfY: Float, _ aspect: Float) {
    Mat4.perspective(&projection, tanHalfY, aspect, WorldRenderer.NEAR, WorldRenderer.FAR)
    Mat4.view(&view, basis, x, y, z)
    Mat4.multiply(&viewProjection, projection, view)
    extractPlanes(viewProjection)

    encoder.setRenderPipelineState(worldPipeline)
    encoder.setDepthStencilState(depthWrite)
    encoder.setCullMode(.back)
    encoder.setFrontFacing(.counterClockwise)
    var uniforms = FrameUniforms(
      viewProjection: clipFix * matrix(viewProjection), light: lightMetal, eye: SIMD4(x, y, z, 1),
      params: SIMD4(time, signalTime, showShadows ? 1 : 0, 1 / Float(WorldRenderer.SHADOW_SIZE)),
      params2: SIMD4(quality.relief ? 1 : 0, 0, 0, 0))
    encoder.setVertexBytes(&uniforms, length: MemoryLayout<FrameUniforms>.stride, index: 2)
    encoder.setFragmentBytes(&uniforms, length: MemoryLayout<FrameUniforms>.stride, index: 2)
    encoder.setFragmentTexture(albedo, index: 0)
    encoder.setFragmentTexture(relief, index: 1)
    encoder.setFragmentTexture(shadowMap, index: 2)
    encoder.setFragmentSamplerState(linear, index: 0)
    encoder.setFragmentSamplerState(compare, index: 1)

    if showDrone {
      setModel(encoder, droneMatrix)
      droneBody.draw(encoder, single: single)
      for m in propMatrices { setModel(encoder, m); propeller.draw(encoder, single: single) }
    }
    setModel(encoder, identity)
    for band in 0...2 {
      let from: Float = band == 0 ? 0 : band == 1 ? 110 : 250
      let to: Float = band == 0 ? 110 : band == 1 ? 250 : .greatestFiniteMagnitude
      for i in blocks.indices {
        let chunk = city.chunks[i]
        let distance = hypot(chunk.x - x, chunk.z - z)
        // Past this the haze has swallowed everything anyway.
        if distance < from || distance >= to || distance - chunk.reach > 950 || !visible(chunk) { continue }
        blocks[i].draw(encoder, single: single)
        if distance < quality.detailRange { details[i].draw(encoder, single: single) }
      }
    }
    for mesh in vehicles { mesh.draw(encoder, single: single) }
    terrain.draw(encoder, single: single)

    encoder.setRenderPipelineState(skyPipeline)
    encoder.setDepthStencilState(depthRead)
    var sky = (matrix(basis), SIMD4<Float>(tanHalfY * aspect, tanHalfY, time, 0))
    encoder.setFragmentBytes(&sky, length: MemoryLayout<(simd_float4x4, SIMD4<Float>)>.stride, index: 0)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)

    if showDrone && rotor > 0.1 {
      encoder.setRenderPipelineState(glowPipeline)
      encoder.setCullMode(.none)
      encoder.setVertexBytes(&uniforms, length: MemoryLayout<FrameUniforms>.stride, index: 2)
      var glow = SIMD4<Float>(0.12, 0.14, 0.16, min(0.22, rotor * 0.17))
      encoder.setFragmentBytes(&glow, length: 16, index: 0)
      for m in discMatrices { setModel(encoder, m); rotorDisc.draw(encoder, single: single) }
    }
  }

  private func extractPlanes(_ m: [Float]) {
    for row in 0...2 {
      for side in 0...1 {
        let sign: Float = side == 0 ? 1 : -1
        for c in 0...3 { planes[(row * 2 + side) * 4 + c] = m[c * 4 + 3] + sign * m[c * 4 + row] }
      }
    }
  }

  /// Swaps in another map's city; the old meshes go when their last reference does.
  func load(_ next: City) {
    city = next
    terrain = GpuMesh(device, next.terrain)
    blocks = next.chunks.map { GpuMesh(device, $0.mesh) }
    details = next.chunks.map { GpuMesh(device, $0.detail) }
  }

  private func visible(_ chunk: City.Chunk) -> Bool {
    let half = chunk.reach + 2
    for p in 0...5 {
      let a = planes[p * 4], b = planes[p * 4 + 1], c = planes[p * 4 + 2], d = planes[p * 4 + 3]
      let reach = a * chunk.x + abs(a) * half + max(0, b * chunk.top) + c * chunk.z + abs(c) * half + d
      if reach < 0 { return false }
    }
    return true
  }
}
