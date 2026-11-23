import AVFoundation

/// Synthesized rotor sound, the same four detuned motors as RotorAudio.kt.
final class RotorAudio {
  private let engine = AVAudioEngine()
  private var node: AVAudioSourceNode?
  private let state = State()

  final class State {
    var rotor: Float = 0, enabled = false
    var speed: Float = 0, volume: Float = 0, rush: Float = 0
    var noise: UInt32 = 22222
    var phases: [Float] = [0, 0, 0, 0]
    let table: [Float] = (0..<2048).map {
      let t = Double($0) / 2048 * 2 * .pi
      return Float(sin(t) + 0.5 * sin(2 * t) + 0.22 * sin(3 * t))
    }
  }

  func set(_ rotor: Float, _ on: Bool) { state.rotor = rotor; state.enabled = on }

  func start() {
    guard node == nil else { return }
    try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
    try? AVAudioSession.sharedInstance().setActive(true)
    let rate = 22050.0
    let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
    let s = state
    let detune: [Float] = [1, 1.013, 0.988, 1.024]
    let source = AVAudioSourceNode(format: format) { _, _, frameCount, bufferList in
      let buffers = UnsafeMutableAudioBufferListPointer(bufferList)
      guard let out = buffers[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
      let targetSpeed = s.rotor
      let targetVolume: Float = s.enabled ? (targetSpeed * 0.8).clamped(0, 1) : 0
      for i in 0..<Int(frameCount) {
        s.speed += (targetSpeed - s.speed) * 0.0007
        s.volume += (targetVolume - s.volume) * 0.0007
        let step = (45 + 135 * s.speed) / Float(rate) * 2048
        var tone: Float = 0
        for m in 0..<4 {
          var p = s.phases[m] + step * detune[m]
          if p >= 2048 { p -= 2048 }
          s.phases[m] = p
          tone += s.table[Int(p) & 2047]
        }
        s.noise = s.noise &* 1664525 &+ 1013904223
        s.rush += (Float(Int32(bitPattern: s.noise) >> 16) / 32768 - s.rush) * 0.18
        out[i] = (tone * 0.035 + s.rush * 0.12) * s.volume
      }
      return noErr
    }
    node = source
    engine.attach(source)
    engine.connect(source, to: engine.mainMixerNode, format: format)
    try? engine.start()
  }

  func stop() {
    engine.stop()
    if let node { engine.detach(node) }
    node = nil
  }
}
