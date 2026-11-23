import Foundation
import UIKit
import WebRTC

/// Receives the live camera's decoded frames from react-native-webrtc (LiveFeed.kt) and keeps the
/// latest one as tightly packed I420 planes for the Metal live screen.
final class LiveFeed: NSObject, RTCVideoRenderer {
  static let shared = LiveFeed()
  /// The tag for video that comes as JPEG frames through the relay instead of over WebRTC.
  static let RELAY = "relay"
  private let decoder = DispatchQueue(label: "SkyCopRelayFrames")
  private var decoding = false

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
    guard let newTag, newTag != LiveFeed.RELAY, let module, let worker = module.value(forKey: "workerQueue") as? DispatchQueue else { return }
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

  /// A JPEG frame from the relay, decoded off the caller's thread into I420 planes (see LiveFeed.kt).
  func pushJpeg(_ jpeg: Data) {
    lock.lock()
    let busy = decoding || tag != LiveFeed.RELAY
    if !busy { decoding = true }
    lock.unlock()
    if busy { return }
    decoder.async { [weak self] in
      guard let self else { return }
      defer { self.lock.lock(); self.decoding = false; self.lock.unlock() }
      guard let image = UIImage(data: jpeg)?.cgImage else { return }
      let w = image.width & ~1, h = image.height & ~1
      if w < 2 || h < 2 { return }
      var rgba = [UInt8](repeating: 0, count: w * h * 4)
      let drawn = rgba.withUnsafeMutableBytes { raw -> Bool in
        guard let context = CGContext(data: raw.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return true
      }
      if !drawn { return }
      let cw = w / 2, ch = h / 2
      var py = [UInt8](repeating: 0, count: w * h), pu = [UInt8](repeating: 0, count: cw * ch), pv = [UInt8](repeating: 0, count: cw * ch)
      // BT.601 video range, as WebRTC delivers it.
      for row in 0..<h {
        for column in 0..<w {
          let i = (row * w + column) * 4
          let r = Int(rgba[i]), g = Int(rgba[i + 1]), b = Int(rgba[i + 2])
          py[row * w + column] = UInt8(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16)
          if row & 1 == 0 && column & 1 == 0 {
            let c = (row / 2) * cw + column / 2
            pu[c] = UInt8(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128)
            pv[c] = UInt8(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128)
          }
        }
      }
      self.lock.lock()
      if self.tag == LiveFeed.RELAY {
        self.y = py; self.u = pu; self.v = pv
        self.width = w; self.height = h; self.rotation = 0
        self.serial += 1
      }
      self.lock.unlock()
    }
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
