import Foundation
import WebRTC

/// Receives the live camera's decoded frames from react-native-webrtc (LiveFeed.kt) and keeps the
/// latest one as tightly packed I420 planes for the Metal live screen.
final class LiveFeed: NSObject, RTCVideoRenderer {
  static let shared = LiveFeed()

  private let lock = NSLock()
  private var track: RTCVideoTrack?
  private var queue: DispatchQueue?
  private(set) var tag: String?
  private var y = [UInt8](), u = [UInt8](), v = [UInt8]()
  private var width = 0, height = 0, rotation = 0
  private var serial = 0

  var available: Bool { tag != nil }

  /// `module` is react-native-webrtc's WebRTCModule; the lookup runs on its worker queue, as RTCView does.
  func follow(_ module: AnyObject?, _ newTag: String?) {
    if newTag == tag { return }
    tag = newTag
    if let old = track, let queue { queue.async { old.remove(self) } }
    track = nil
    lock.lock(); width = 0; serial += 1; lock.unlock()
    guard let newTag, let module, let worker = module.value(forKey: "workerQueue") as? DispatchQueue else { return }
    queue = worker
    worker.async { [weak self] in
      guard let self, self.tag == newTag else { return }
      let selector = NSSelectorFromString("streamForReactTag:")
      guard module.responds(to: selector),
            let stream = module.perform(selector, with: newTag)?.takeUnretainedValue() as? RTCMediaStream,
            let video = stream.videoTracks.first else { return }
      video.add(self)
      self.track = video
    }
  }

  func setSize(_ size: CGSize) {}

  func renderFrame(_ frame: RTCVideoFrame?) {
    guard let frame else { return }
    let i420 = frame.buffer.toI420()
    let w = Int(i420.width), h = Int(i420.height)
    let cw = Int(i420.chromaWidth), ch = Int(i420.chromaHeight)
    lock.lock()
    defer { lock.unlock() }
    Self.pack(i420.dataY, Int(i420.strideY), w, h, &y)
    Self.pack(i420.dataU, Int(i420.strideU), cw, ch, &u)
    Self.pack(i420.dataV, Int(i420.strideV), cw, ch, &v)
    width = w; height = h
    switch frame.rotation {
    case ._90: rotation = 90
    case ._180: rotation = 180
    case ._270: rotation = 270
    default: rotation = 0
    }
    serial += 1
  }

  private static func pack(_ source: UnsafePointer<UInt8>, _ stride: Int, _ w: Int, _ h: Int, _ out: inout [UInt8]) {
    if out.count != w * h { out = [UInt8](repeating: 0, count: w * h) }
    out.withUnsafeMutableBufferPointer { target in
      for row in 0..<h { memcpy(target.baseAddress! + row * w, source + row * stride, w) }
    }
  }

  /// Hands the newest frame to `use` if it is newer than `since`; returns its serial.
  func latest(since: Int, _ use: (Int, Int, Int, [UInt8], [UInt8], [UInt8]) -> Void) -> Int {
    lock.lock()
    defer { lock.unlock() }
    if serial == since { return since }
    use(width, height, rotation, y, u, v)
    return serial
  }
}
