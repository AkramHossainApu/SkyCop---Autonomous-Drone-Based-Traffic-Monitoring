// The fallback for when the camera and the phone cannot reach each other directly: one on Wi-Fi and
// the other on mobile data, behind carrier NAT. Nothing of ours runs anywhere and no account is
// needed: the video goes, encrypted, through the same free public MQTT brokers that carry the
// pairing messages.
//
// Those brokers only pass about 30-50 kB/s per connection, at their own whim, and silently drop what
// does not fit, so the video is real H.264 (WebCodecs) at about 240 kb/s and 15 frames a second, sent
// as raw bytes: a tenth of what the same picture costs as JPEG frames. Each encrypted frame is cut in
// two halves plus their XOR, one piece per broker: any two pieces rebuild it, so one broker that
// stalls or drops costs nothing, and each carries only half the video. Browsers without an H.264
// encoder fall back to a few small JPEG frames a second.
//
// Wire format, one MQTT message per piece:
//   v3.<seq>.<ms>.<piece>.<length>.<bytes>  piece 0 and 1 are the halves of the sealed frame, piece 2
//       their XOR; <length> is the sealed frame's length. The frame is an H.264 access unit in Annex B
//       form (SPS and PPS ahead of every key frame); <ms> is the capture time, so the phone can play
//       the frames at the pace they were filmed instead of the pace they arrived.
//   f1.<seq>.<sealed base64>  JPEG frame (fallback)

import { MqttLink, VIDEO_BROKERS } from './mqtt';

/** Most pixels per frame: 640x360 landscape, 480x480 square. */
const VIDEO_PIXELS = 640 * 360;
const VIDEO_FPS = 15;
const VIDEO_BITRATE = 240_000;
/**
 * A key frame every 1.2 s, so a frame lost on every broker freezes the picture no longer than that
 * (the phone also asks for one straight away when it notices a loss).
 */
const KEY_EVERY = 18;
/** Constrained baseline: no B-frames, which every phone decoder takes with no delay. */
const CODECS = ['avc1.42E01F', 'avc1.42001F', 'avc1.42E01E'];

const JPEG_WIDTH = 480;
const JPEG_QUALITY = 0.5;
const JPEG_MS = 250;

/** Bytes that may wait in a socket before frames are skipped, so the picture never falls behind. */
const MAX_BACKLOG = 40_000;

export type RelayStats = { width: number; height: number; fps: number; kbps: number };

function latin1(prefix: string, bytes: Uint8Array): string {
  let out = prefix;
  for (let i = 0; i < bytes.length; i += 8192) out += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + 8192)));
  return out;
}

function toBase64(bytes: Uint8Array) {
  return btoa(latin1('', bytes));
}

/** SPS and PPS from an avcC record, plus the size of the length fields in front of each NAL unit. */
function parseAvcC(avcc: Uint8Array): { lengthSize: number; params: Uint8Array[] } {
  const params: Uint8Array[] = [];
  const lengthSize = (avcc[4] & 3) + 1;
  let at = 6;
  const sps = avcc[5] & 0x1f;
  for (let i = 0; i < sps && at + 2 <= avcc.length; i++) {
    const n = (avcc[at] << 8) | avcc[at + 1];
    params.push(avcc.subarray(at + 2, at + 2 + n));
    at += 2 + n;
  }
  const pps = avcc[at++] ?? 0;
  for (let i = 0; i < pps && at + 2 <= avcc.length; i++) {
    const n = (avcc[at] << 8) | avcc[at + 1];
    params.push(avcc.subarray(at + 2, at + 2 + n));
    at += 2 + n;
  }
  return { lengthSize, params };
}

/** Length-prefixed NAL units to start-code form, with the parameter sets first on key frames. */
function annexB(data: Uint8Array, lengthSize: number, params: Uint8Array[] | null): Uint8Array {
  const units: Uint8Array[] = params ? [...params] : [];
  let at = 0;
  while (at + lengthSize <= data.length) {
    let n = 0;
    for (let i = 0; i < lengthSize; i++) n = n * 256 + data[at + i];
    at += lengthSize;
    units.push(data.subarray(at, Math.min(data.length, at + n)));
    at += n;
  }
  const out = new Uint8Array(units.reduce((sum, unit) => sum + 4 + unit.length, 0));
  let o = 0;
  for (const unit of units) {
    out[o + 3] = 1;
    out.set(unit, o + 4);
    o += 4 + unit.length;
  }
  return out;
}

/** The first H.264 configuration this browser can encode at this size, or null. */
async function videoConfig(width: number, height: number): Promise<VideoEncoderConfig | null> {
  if (typeof VideoEncoder === 'undefined' || typeof VideoFrame === 'undefined') return null;
  for (const codec of CODECS) {
    const config: VideoEncoderConfig = {
      codec, width, height, bitrate: VIDEO_BITRATE, framerate: VIDEO_FPS,
      latencyMode: 'realtime', bitrateMode: 'variable', avc: { format: 'avc' },
    };
    try {
      if ((await VideoEncoder.isConfigSupported(config)).supported) return config;
    } catch { /* try the next one */ }
  }
  return null;
}

export class FrameSender {
  private readonly links: MqttLink[];
  private readonly video = document.createElement('video');
  private readonly canvas = document.createElement('canvas');
  private timer = 0;
  private stopped = false;
  private busy = false;
  private seq = 0;
  private sentFrames = 0;
  private sentBytes = 0;
  private windowStart = performance.now();
  /** Publishing waits on encryption; this keeps the frames in order. */
  private chain: Promise<void> = Promise.resolve();
  private encoder: VideoEncoder | null = null;
  private configured = '';
  private avc: { lengthSize: number; params: Uint8Array[] } | null = null;
  private sinceKey = KEY_EVERY;
  private mode: 'probe' | 'video' | 'jpeg' = 'probe';
  stats: RelayStats = { width: 0, height: 0, fps: 0, kbps: 0 };

  constructor(
    topic: string,
    private readonly sealRaw: (bytes: Uint8Array) => Promise<Uint8Array>,
    source: MediaStream,
  ) {
    this.links = VIDEO_BROKERS.map((url) => new MqttLink(url, topic, () => undefined, () => undefined, false));
    this.video.muted = true;
    this.video.playsInline = true;
    this.video.srcObject = source;
  }

  start() {
    void this.video.play().catch(() => undefined);
    this.links.forEach((link) => link.open());
    this.timer = window.setInterval(() => void this.tick(), 1000 / VIDEO_FPS);
  }

  /** Frame size for the current camera picture: same shape, at most VIDEO_PIXELS, even sides. */
  private size(maxPixels: number, maxWidth = Infinity) {
    const width = this.video.videoWidth; const height = this.video.videoHeight;
    const scale = Math.min(1, Math.sqrt(maxPixels / (width * height)), maxWidth / width);
    return { w: Math.max(2, Math.round((width * scale) / 2) * 2), h: Math.max(2, Math.round((height * scale) / 2) * 2) };
  }

  private draw(w: number, h: number) {
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
    this.canvas.getContext('2d', { alpha: false })?.drawImage(this.video, 0, 0, w, h);
  }

  private async tick() {
    if (this.busy || this.stopped || this.video.videoWidth === 0 || this.video.videoHeight === 0) return;
    // Every frame goes through every broker that keeps up; the phone takes whichever copy comes first.
    if (!this.links.some((link) => link.ready && link.backlog < MAX_BACKLOG)) return;
    this.busy = true;
    try {
      if (this.mode === 'probe') {
        const { w, h } = this.size(VIDEO_PIXELS);
        this.mode = (await videoConfig(w, h)) ? 'video' : 'jpeg';
      }
      if (this.mode === 'video') await this.encodeVideo();
      else await this.sendJpeg();
    } catch {
      // A broken encoder: fall back to JPEG for the rest of this session.
      this.encoder?.close();
      this.encoder = null;
      this.mode = 'jpeg';
    } finally {
      this.busy = false;
    }
  }

  private async encodeVideo() {
    const { w, h } = this.size(VIDEO_PIXELS);
    const shape = `${w}x${h}`;
    if (!this.encoder || this.encoder.state === 'closed') {
      this.encoder = new VideoEncoder({
        output: (chunk, metadata) => this.onChunk(chunk, metadata),
        error: () => { this.mode = 'jpeg'; },
      });
      this.configured = '';
    }
    if (this.configured !== shape) {
      // The phone was turned: a new size, and the next frame is a key frame with new parameter sets.
      const config = await videoConfig(w, h);
      if (!config) throw new Error('no encoder for this size');
      this.encoder.configure(config);
      this.configured = shape;
      this.avc = null;
      this.sinceKey = KEY_EVERY;
    }
    if (this.encoder.encodeQueueSize > 1) return;
    this.draw(w, h);
    const frame = new VideoFrame(this.canvas, { timestamp: Math.round(performance.now() * 1000) });
    const keyFrame = this.sinceKey >= KEY_EVERY;
    this.sinceKey = keyFrame ? 1 : this.sinceKey + 1;
    try {
      this.encoder.encode(frame, { keyFrame });
    } finally {
      frame.close();
    }
    this.stats = { ...this.stats, width: w, height: h };
  }

  private onChunk(chunk: EncodedVideoChunk, metadata?: EncodedVideoChunkMetadata) {
    const description = metadata?.decoderConfig?.description;
    if (description) {
      const bytes = description instanceof ArrayBuffer
        ? new Uint8Array(description.slice(0))
        : new Uint8Array(description.buffer.slice(description.byteOffset, description.byteOffset + description.byteLength));
      this.avc = parseAvcC(bytes);
    }
    const data = new Uint8Array(chunk.byteLength);
    chunk.copyTo(data);
    const key = chunk.type === 'key';
    // Without an avcC record the encoder already wrote start codes (some browsers ignore format: 'avc').
    const unit = this.avc ? annexB(data, this.avc.lengthSize, key ? this.avc.params : null) : data;
    const seq = ++this.seq;
    const ms = Math.round(chunk.timestamp / 1000);
    this.chain = this.chain.then(async () => {
      const sealed = await this.sealRaw(unit);
      this.publishPieces(seq, ms, sealed);
    }).catch(() => undefined);
  }

  private lastJpeg = 0;

  private async sendJpeg() {
    const now = performance.now();
    if (now - this.lastJpeg < JPEG_MS) return;
    this.lastJpeg = now;
    const { w, h } = this.size(JPEG_WIDTH * JPEG_WIDTH, JPEG_WIDTH);
    this.draw(w, h);
    const blob = await new Promise<Blob | null>((resolve) => this.canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY));
    if (!blob) return;
    const sealed = toBase64(await this.sealRaw(new Uint8Array(await blob.arrayBuffer())));
    this.stats = { ...this.stats, width: w, height: h };
    this.publish(`f1.${++this.seq}.${sealed}`, sealed.length);
  }

  /** Two halves and their XOR, one per broker; a broker that is down or behind hands its piece on. */
  private publishPieces(seq: number, ms: number, sealed: Uint8Array) {
    if (this.stopped) return;
    const half = Math.ceil(sealed.length / 2);
    const a = sealed.subarray(0, half);
    const b = new Uint8Array(half);
    b.set(sealed.subarray(half));
    const x = new Uint8Array(half);
    for (let i = 0; i < half; i++) x[i] = a[i] ^ b[i];
    const usable = this.links.filter((link) => link.ready && link.backlog < MAX_BACKLOG);
    if (usable.length === 0) return;
    [a, b, x].forEach((piece, i) => {
      const own = this.links[i];
      const link = usable.includes(own) ? own : usable[i % usable.length];
      link.publish(latin1(`v3.${seq}.${ms}.${i}.${sealed.length}.`, piece));
    });
    this.count(sealed.length);
  }

  private publish(payload: string, bytes: number) {
    if (this.stopped) return;
    let sent = false;
    for (const link of this.links) {
      if (link.ready && link.backlog < MAX_BACKLOG && link.publish(payload)) sent = true;
    }
    if (sent) this.count(bytes);
  }

  private count(bytes: number) {
    this.sentFrames++;
    this.sentBytes += bytes;
    const now = performance.now();
    if (now - this.windowStart >= 1000) {
      const seconds = (now - this.windowStart) / 1000;
      this.stats = { ...this.stats, fps: Math.round(this.sentFrames / seconds), kbps: Math.round((this.sentBytes * 8) / 1000 / seconds) };
      this.sentFrames = 0; this.sentBytes = 0; this.windowStart = now;
    }
  }

  /** The next frame is a key frame, so a phone that just (re)joined has a picture at once. */
  keyFrame() {
    this.sinceKey = KEY_EVERY;
  }

  stop() {
    this.stopped = true;
    window.clearInterval(this.timer);
    try { this.encoder?.close(); } catch { /* already closed */ }
    this.encoder = null;
    this.links.forEach((link) => link.close());
    this.video.srcObject = null;
  }
}
