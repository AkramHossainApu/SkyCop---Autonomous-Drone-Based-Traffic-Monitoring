// Phone side of ../src/frameRelay.ts: video frames that come through the public MQTT brokers when
// the camera cannot reach this phone directly. Frames are decrypted and decoded natively, which also
// hands each one to the VR view; the JPEG comes back here for the preview.
import SkyCopSim from './modules/skycop-sim';
import { MQTT_BROKERS, MqttLink } from './mqtt';

export class FrameReceiver {
  private readonly links: MqttLink[];
  private last = 0;

  constructor(topic: string, private readonly keyHex: string, private readonly onFrame: (jpegBase64: string) => void) {
    // Listen on every broker; the camera publishes each frame on whichever one it can reach.
    this.links = MQTT_BROKERS.map((url) => new MqttLink(url, topic, (payload) => this.receive(payload), () => undefined));
  }

  open() {
    this.links.forEach((link) => link.open());
  }

  private receive(payload: string) {
    const dot = payload.indexOf('.', 3);
    if (!payload.startsWith('f1.') || dot < 0) return;
    const seq = Number(payload.slice(3, dot));
    // Older frames are dropped; a big step back means the camera started again.
    if (seq <= this.last && seq > this.last - 50) return;
    this.last = seq;
    const jpeg = SkyCopSim?.openFrame(this.keyHex, payload.slice(dot + 1));
    if (jpeg) this.onFrame(jpeg);
  }

  close() {
    this.links.forEach((link) => link.close());
  }
}
