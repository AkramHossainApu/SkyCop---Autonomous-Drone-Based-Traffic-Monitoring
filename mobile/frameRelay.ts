// Phone side of ../src/frameRelay.ts: video that comes through the public MQTT brokers when the
// camera cannot reach this phone directly. Frames are decrypted and decoded natively (H.264 on the
// hardware decoder, or the JPEG fallback), which also hands each picture to the VR view. Now and
// then a small JPEG of the newest picture comes back here for the on-screen preview.
import SkyCopSim from './modules/skycop-sim';
import { MQTT_BROKERS, MqttLink } from './mqtt';

export class FrameReceiver {
  private readonly links: MqttLink[];
  private last = 0;

  /** `onFrame` runs for every frame that arrives; `preview` is a base64 JPEG when a new one is ready. */
  constructor(topic: string, private readonly keyHex: string, private readonly onFrame: (preview: string | null) => void) {
    // Listen on every broker; the camera publishes each frame on whichever one it can reach.
    this.links = MQTT_BROKERS.map((url) => new MqttLink(url, topic, (payload) => this.receive(payload), () => undefined));
  }

  open() {
    this.links.forEach((link) => link.open());
  }

  private receive(payload: string) {
    const video = payload.startsWith('v1.');
    if (!video && !payload.startsWith('f1.')) return;
    const dot = payload.indexOf('.', 3);
    if (dot < 0) return;
    const seq = Number(payload.slice(3, dot));
    // Older frames are dropped; a big step back means the camera started again.
    if (seq <= this.last && seq > this.last - 300) return;
    const gap = seq !== this.last + 1;
    this.last = seq;
    if (video) {
      // Raw bytes on the wire (each character is one byte); the native side takes base64.
      // After a gap the decoder waits for the next key frame instead of showing a smeared picture.
      this.onFrame(SkyCopSim?.openVideo(this.keyHex, btoa(payload.slice(dot + 1)), gap) ?? null);
    } else {
      this.onFrame(SkyCopSim?.openFrame(this.keyHex, payload.slice(dot + 1)) ?? null);
    }
  }

  close() {
    this.links.forEach((link) => link.close());
    SkyCopSim?.closeVideo();
  }
}
