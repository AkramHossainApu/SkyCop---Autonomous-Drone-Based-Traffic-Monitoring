import Metal
import simd

/// The live camera in VR (LiveScreen.kt): a still picture in each eye, filling it as far as the
/// video's shape allows, with no head tracking. It takes the video's own upright shape, after the
/// rotation a phone camera sends with each frame, so nothing is cropped whichever way it is held.
final class LiveScreen {
  private static let DISTANCE: Float = 3
  static let ROOM = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
  private static let source = """
  #include <metal_stdlib>
  using namespace metal;
  struct LiveParams { float4x4 matrix; float4 rect; float4 uvLow; float4 uvHigh; float4 solid; };
  struct LiveOut { float4 position [[position]]; float2 uv; };
  vertex LiveOut liveVertex(uint id [[vertex_id]], constant LiveParams &p [[buffer(0)]]) {
    uint c = id < 3 ? id : (id == 3 ? 0 : id - 2);
    float x = (c == 1 || c == 2) ? 1.0 : -1.0;
    float y = c >= 2 ? 1.0 : -1.0;
    LiveOut o;
    o.uv = c == 0 ? p.uvLow.xy : c == 1 ? p.uvLow.zw : c == 2 ? p.uvHigh.xy : p.uvHigh.zw;
    o.position = p.matrix * float4(x * p.rect.x, y * p.rect.y, -p.rect.z, 1.0);
    return o;
  }
  fragment float4 liveFragment(LiveOut in [[stage_in]], constant LiveParams &p [[buffer(0)]],
                               texture2d<float> ty [[texture(0)]], texture2d<float> tu [[texture(1)]],
                               texture2d<float> tv [[texture(2)]], sampler s [[sampler(0)]]) {
    if (p.solid.a > 0.5) return float4(p.solid.rgb, 1.0);
    float y = ty.sample(s, in.uv).r - 0.0625;
    float u = tu.sample(s, in.uv).r - 0.5;
    float v = tv.sample(s, in.uv).r - 0.5;
    float3 rgb = float3(1.164 * y + 1.596 * v, 1.164 * y - 0.392 * u - 0.813 * v, 1.164 * y + 2.017 * u);
    return float4(clamp(rgb, 0.0, 1.0), 1.0);
  }
  """

  private struct Params { var matrix: simd_float4x4; var rect: SIMD4<Float>; var uvLow: SIMD4<Float>; var uvHigh: SIMD4<Float>; var solid: SIMD4<Float> }

  private let device: MTLDevice
  private let pipeline: MTLRenderPipelineState
  private let depthOff: MTLDepthStencilState
  private let sampler: MTLSamplerState
  private var planes: [MTLTexture] = []
  private var serial = -1
  private var videoWidth = 0, videoHeight = 0, rotation = 0
  private var projection = [Float](repeating: 0, count: 16)
  private var view = [Float](repeating: 0, count: 16)
  private var viewProjection = [Float](repeating: 0, count: 16)

  var hasVideo: Bool { videoWidth > 0 }

  init(device: MTLDevice, colorFormat: MTLPixelFormat, samples: Int) throws {
    self.device = device
    let library = try device.makeLibrary(source: LiveScreen.source, options: nil)
    let d = MTLRenderPipelineDescriptor()
    d.vertexFunction = library.makeFunction(name: "liveVertex")
    d.fragmentFunction = library.makeFunction(name: "liveFragment")
    d.colorAttachments[0].pixelFormat = colorFormat
    d.depthAttachmentPixelFormat = .depth32Float
    d.rasterSampleCount = samples
    pipeline = try device.makeRenderPipelineState(descriptor: d)
    let depth = MTLDepthStencilDescriptor()
    depth.isDepthWriteEnabled = false
    depth.depthCompareFunction = .always
    depthOff = device.makeDepthStencilState(descriptor: depth)!
    let s = MTLSamplerDescriptor()
    s.minFilter = .linear; s.magFilter = .linear
    s.sAddressMode = .clampToEdge; s.tAddressMode = .clampToEdge
    sampler = device.makeSamplerState(descriptor: s)!
  }

  /// Uploads the newest decoded frame, if there is one. Called once per rendered frame.
  func upload() {
    serial = LiveFeed.shared.latest(since: serial) { w, h, turn, y, u, v in
      if w == 0 { videoWidth = 0; return }
      if w != videoWidth || h != videoHeight || planes.isEmpty {
        planes = [(w, h), ((w + 1) / 2, (h + 1) / 2), ((w + 1) / 2, (h + 1) / 2)].map { size in
          let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .r8Unorm, width: size.0, height: size.1, mipmapped: false)
          d.usage = .shaderRead
          return device.makeTexture(descriptor: d)!
        }
      }
      for (i, data) in [y, u, v].enumerated() {
        let t = planes[i]
        data.withUnsafeBytes { raw in
          t.replace(region: MTLRegionMake2D(0, 0, t.width, t.height), mipmapLevel: 0, withBytes: raw.baseAddress!, bytesPerRow: t.width)
        }
      }
      videoWidth = w; videoHeight = h; rotation = turn
    }
  }

  /// Width over height of the picture as it should be seen, upright.
  private var uprightAspect: Float {
    if videoWidth == 0 { return 16.0 / 9.0 }
    return rotation % 180 == 0 ? Float(videoWidth) / Float(videoHeight) : Float(videoHeight) / Float(videoWidth)
  }

  func drawEye(_ encoder: MTLRenderCommandEncoder, _ basis: [Float], _ x: Float, _ y: Float, _ z: Float, _ tanHalfY: Float, _ viewAspect: Float, _ vr: Bool) {
    guard hasVideo, planes.count == 3 else { return }
    let D = LiveScreen.DISTANCE
    let a = uprightAspect
    // As big as each eye allows with the whole frame in view (LiveScreen.kt).
    let fitHeight = D * tanHalfY * (vr ? 0.96 : 1)
    let halfHeight = min(fitHeight, fitHeight * viewAspect / a)
    let halfWidth = halfHeight * a
    Mat4.perspective(&projection, tanHalfY, viewAspect, 0.05, 50)
    Mat4.view(&view, basis, x, y, z)
    Mat4.multiply(&viewProjection, projection, view)
    let m = viewProjection
    let matrix = simd_float4x4(SIMD4(m[0], m[1], m[2], m[3]), SIMD4(m[4], m[5], m[6], m[7]),
                               SIMD4(m[8], m[9], m[10], m[11]), SIMD4(m[12], m[13], m[14], m[15]))
    // Metal clip space has z from 0 to 1; the shared math produces OpenGL's -1 to 1.
    let toMetal = simd_float4x4(SIMD4(1, 0, 0, 0), SIMD4(0, 1, 0, 0), SIMD4(0, 0, 0.5, 0), SIMD4(0, 0, 0.5, 1))

    var corners = [Float](repeating: 0, count: 8)
    let dx: [Float] = [0, 1, 1, 0], dy: [Float] = [1, 1, 0, 0]
    for c in 0..<4 {
      let px = dx[c], py = dy[c]
      switch rotation {
      case 90: corners[c * 2] = py; corners[c * 2 + 1] = 1 - px
      case 180: corners[c * 2] = 1 - px; corners[c * 2 + 1] = 1 - py
      case 270: corners[c * 2] = 1 - py; corners[c * 2 + 1] = px
      default: corners[c * 2] = px; corners[c * 2 + 1] = py
      }
    }
    encoder.setRenderPipelineState(pipeline)
    encoder.setDepthStencilState(depthOff)
    encoder.setCullMode(.none)
    var frame = Params(matrix: toMetal * matrix, rect: SIMD4(halfWidth + 0.04, halfHeight + 0.04, D + 0.01, 0),
                       uvLow: .zero, uvHigh: .zero, solid: SIMD4(0, 0, 0, 1))
    encoder.setVertexBytes(&frame, length: MemoryLayout<Params>.stride, index: 0)
    encoder.setFragmentBytes(&frame, length: MemoryLayout<Params>.stride, index: 0)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
    var picture = Params(matrix: toMetal * matrix, rect: SIMD4(halfWidth, halfHeight, D, 0),
                         uvLow: SIMD4(corners[0], corners[1], corners[2], corners[3]),
                         uvHigh: SIMD4(corners[4], corners[5], corners[6], corners[7]), solid: .zero)
    encoder.setVertexBytes(&picture, length: MemoryLayout<Params>.stride, index: 0)
    encoder.setFragmentBytes(&picture, length: MemoryLayout<Params>.stride, index: 0)
    for i in 0..<3 { encoder.setFragmentTexture(planes[i], index: i) }
    encoder.setFragmentSamplerState(sampler, index: 0)
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 6)
  }
}
