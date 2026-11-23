// The fallback for when the camera and the phone cannot reach each other directly: one on Wi-Fi and
// the other on mobile data, behind carrier NAT. Nothing of ours runs anywhere and no account is
// needed: the video goes as encrypted JPEG frames through the same free public MQTT brokers that
// carry the pairing messages. It is lower quality than the direct link (about ten 640-pixel frames
// a second, half a second behind), and the phone uses roughly 250 KB/s of data while watching.

import { MQTT_BROKERS, MqttLink } from './mqtt';

const WIDTH = 640;
const QUALITY = 0.55;
const FRAME_MS = 100;
/** Bytes that may wait in a socket before frames are skipped, so the picture never falls behind. */
const MAX_BACKLOG = 120_000;

export type RelayStats = { width: number; height: number; fps: number; kbps: number };

export class FrameSender {
  private readonly links: MqttLink[];
  private readonly video = document.createElement('video');
  private readonly canvas = document.createElement('canvas');
  private timer = 0;
  private busy = false;
  private seq = 0;
  private sentFrames = 0;
  private sentBytes = 0;
  private windowStart = performance.now();
  stats: RelayStats = { width: 0, height: 0, fps: 0, kbps: 0 };

  constructor(topic: string, private readonly seal: (bytes: Uint8Array) => Promise<string>, source: MediaStream) {
    this.links = MQTT_BROKERS.map((url) => new MqttLink(url, topic, () => undefined, () => undefined, false));
    this.video.muted = true;
    this.video.playsInline = true;
    this.video.srcObject = source;
  }

  start() {
    void this.video.play().catch(() => undefined);
    this.links.forEach((link) => link.open());
    this.timer = window.setInterval(() => void this.tick(), FRAME_MS);
  }

  private async tick() {
    if (this.busy) return;
    // One broker at a time; the phone listens on all of them.
    const link = this.links.find((candidate) => candidate.ready && candidate.backlog < MAX_BACKLOG);
    const width = this.video.videoWidth; const height = this.video.videoHeight;
    if (!link || width === 0 || height === 0) return;
    this.busy = true;
    try {
      const w = Math.min(WIDTH, width);
      const h = Math.round((w * height) / width / 2) * 2;
      if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
      this.canvas.getContext('2d')?.drawImage(this.video, 0, 0, w, h);
      const blob = await new Promise<Blob | null>((resolve) => this.canvas.toBlob(resolve, 'image/jpeg', QUALITY));
      if (!blob) return;
      const sealed = await this.seal(new Uint8Array(await blob.arrayBuffer()));
      if (link.publish(`f1.${++this.seq}.${sealed}`)) {
        this.sentFrames++;
        this.sentBytes += sealed.length;
      }
      const now = performance.now();
      if (now - this.windowStart >= 1000) {
        const seconds = (now - this.windowStart) / 1000;
        this.stats = { width: w, height: h, fps: Math.round(this.sentFrames / seconds), kbps: Math.round((this.sentBytes * 8) / 1000 / seconds) };
        this.sentFrames = 0; this.sentBytes = 0; this.windowStart = now;
      }
    } finally {
      this.busy = false;
    }
  }

  stop() {
    window.clearInterval(this.timer);
    this.links.forEach((link) => link.close());
    this.video.srcObject = null;
  }
}
