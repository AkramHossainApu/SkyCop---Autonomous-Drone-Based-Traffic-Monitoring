import ExpoModulesCore
import QuartzCore
import UIKit

/// The app's own on-screen preview of relay video (RelayPreviewView.kt): draws every picture LiveFeed
/// gets, the same ones the VR view shows, natively, so the preview moves as smoothly as VR without
/// going through JavaScript. The colour conversion runs off the main thread.
final class RelayPreviewView: ExpoView {
  private static let converter = DispatchQueue(label: "SkyCopRelayPreview")

  private let picture = UIImageView()
  private var link: CADisplayLink?
  private var serial = -1
  private var busy = false

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = true
    picture.contentMode = .scaleAspectFit
    picture.frame = bounds
    picture.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    addSubview(picture)
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    link?.invalidate()
    link = nil
    guard window != nil else { return }
    let next = CADisplayLink(target: Weak(self), selector: #selector(Weak.tick))
    next.add(to: .main, forMode: .common)
    link = next
  }

  fileprivate func tick() {
    if busy { return }
    var w = 0, h = 0
    var planes: ([UInt8], [UInt8], [UInt8])?
    serial = LiveFeed.shared.latest(since: serial) { fw, fh, _, y, u, v in
      guard fw > 0, y.count >= fw * fh, u.count >= fw * fh / 4, v.count >= fw * fh / 4 else { return }
      w = fw; h = fh
      planes = (y, u, v) // Copy-on-write arrays: LiveFeed replaces or copies before writing.
    }
    guard let (py, pu, pv) = planes else { return }
    busy = true
    Self.converter.async { [weak self] in
      let image = Self.image(w, h, py, pu, pv)
      DispatchQueue.main.async {
        guard let self else { return }
        if let image { self.picture.image = image }
        self.busy = false
      }
    }
  }

  /// BT.601 video range, as the decoders give it.
  private static func image(_ w: Int, _ h: Int, _ py: [UInt8], _ pu: [UInt8], _ pv: [UInt8]) -> UIImage? {
    var bgra = [UInt8](repeating: 255, count: w * h * 4)
    let cw = w / 2
    bgra.withUnsafeMutableBufferPointer { out in
      py.withUnsafeBufferPointer { ys in
        pu.withUnsafeBufferPointer { us in
          pv.withUnsafeBufferPointer { vs in
            for row in 0..<h {
              let line = row * w, chroma = (row / 2) * cw
              for column in 0..<w {
                let c = chroma + column / 2
                let y = max(0, Int(ys[line + column]) - 16) * 1192
                let u = Int(us[c]) - 128, v = Int(vs[c]) - 128
                let o = (line + column) * 4
                out[o] = UInt8(clamping: (y + 2066 * u) >> 10)
                out[o + 1] = UInt8(clamping: (y - 833 * v - 400 * u) >> 10)
                out[o + 2] = UInt8(clamping: (y + 1634 * v) >> 10)
              }
            }
          }
        }
      }
    }
    guard let provider = CGDataProvider(data: Data(bgra) as CFData),
          let cg = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4,
                           space: CGColorSpaceCreateDeviceRGB(),
                           bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue),
                           provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) else { return nil }
    return UIImage(cgImage: cg)
  }

  /// CADisplayLink holds its target strongly; this keeps it from holding the view.
  private final class Weak: NSObject {
    weak var view: RelayPreviewView?
    init(_ view: RelayPreviewView) { self.view = view }
    @objc func tick() { view?.tick() }
  }
}
