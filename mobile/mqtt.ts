// A tiny MQTT 3.1.1 client over WebSocket: connect, subscribe to one topic, publish at QoS 0.
// It is the pairing relay's main transport because public MQTT brokers have no per-IP message quota
// (ntfy.sh allows 250 messages per 12 hours per network). Payloads are ASCII (base64 ciphertext), so
// no text encoders are needed. mobile/mqtt.ts is an identical copy for the phone app; keep them in step.

export const MQTT_BROKERS = ['wss://broker.hivemq.com:8884/mqtt', 'wss://test.mosquitto.org:8081/mqtt'];

function ascii(text: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < text.length; i++) out.push(text.charCodeAt(i) & 0xff);
  return out;
}

function str(text: string): number[] {
  const bytes = ascii(text);
  return [bytes.length >> 8, bytes.length & 0xff, ...bytes];
}

function packet(type: number, body: number[]): ArrayBuffer {
  const length: number[] = [];
  let n = body.length;
  do {
    let digit = n % 128;
    n = Math.floor(n / 128);
    if (n > 0) digit |= 0x80;
    length.push(digit);
  } while (n > 0);
  return new Uint8Array([type, ...length, ...body]).buffer;
}

export class MqttLink {
  private socket: WebSocket | null = null;
  private buffer: number[] = [];
  private ping: ReturnType<typeof setInterval> | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private retry = 0;
  private closed = false;
  ready = false;

  constructor(
    private readonly url: string,
    private readonly topic: string,
    private readonly onMessage: (payload: string) => void,
    private readonly onState: (ready: boolean, detail?: string) => void,
  ) {}

  open() {
    if (this.closed) return;
    let socket: WebSocket;
    try {
      socket = new WebSocket(this.url, ['mqtt']);
    } catch {
      this.scheduleRetry();
      return;
    }
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    this.buffer = [];
    socket.onopen = () => {
      const clientId = `skycop${Math.random().toString(36).slice(2, 12)}`;
      // Protocol "MQTT" level 4, clean session, 60 s keep-alive.
      socket.send(packet(0x10, [...str('MQTT'), 4, 0x02, 0, 60, ...str(clientId)]));
    };
    socket.onmessage = (event) => {
      if (this.socket !== socket || !(event.data instanceof ArrayBuffer)) return;
      const bytes = new Uint8Array(event.data);
      for (let i = 0; i < bytes.length; i++) this.buffer.push(bytes[i]);
      this.drain(socket);
    };
    let failure = '';
    socket.onerror = (event) => {
      failure = (event as unknown as { message?: string }).message ?? 'connection error';
    };
    socket.onclose = (event) => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.stopPing();
      if (this.ready) {
        this.ready = false;
        this.onState(false, failure || `closed (${event.code})`);
      }
      this.scheduleRetry();
    };
  }

  private scheduleRetry() {
    if (this.closed) return;
    this.timer = setTimeout(() => this.open(), Math.min(20000, 1000 * 2 ** this.retry++));
  }

  private drain(socket: WebSocket) {
    for (;;) {
      if (this.buffer.length < 2) return;
      let length = 0; let multiplier = 1; let index = 1; let byte: number;
      do {
        if (index >= this.buffer.length) return;
        byte = this.buffer[index++];
        length += (byte & 0x7f) * multiplier;
        multiplier *= 128;
      } while (byte & 0x80);
      if (this.buffer.length < index + length) return;
      const type = this.buffer[0] >> 4;
      const body = this.buffer.slice(index, index + length);
      this.buffer = this.buffer.slice(index + length);
      if (type === 2) {
        // CONNACK: subscribe once the broker accepts us.
        if (body[1] !== 0) { socket.close(); return; }
        socket.send(packet(0x82, [0, 1, ...str(this.topic), 0]));
      } else if (type === 9) {
        // SUBACK
        this.retry = 0;
        this.ready = true;
        this.startPing(socket);
        this.onState(true);
      } else if (type === 3) {
        // PUBLISH at QoS 0: topic, then payload (no packet id).
        const topicLength = (body[0] << 8) | body[1];
        let payload = '';
        for (let i = 2 + topicLength; i < body.length; i++) payload += String.fromCharCode(body[i]);
        this.onMessage(payload);
      }
    }
  }

  private startPing(socket: WebSocket) {
    this.stopPing();
    this.ping = setInterval(() => {
      if (socket.readyState === 1) socket.send(new Uint8Array([0xc0, 0]).buffer);
    }, 30000);
  }

  private stopPing() {
    if (this.ping) clearInterval(this.ping);
    this.ping = null;
  }

  /** Publishes at QoS 0. Returns false when the link is not up. */
  publish(payload: string): boolean {
    const socket = this.socket;
    if (!socket || !this.ready || socket.readyState !== 1) return false;
    socket.send(packet(0x30, [...str(this.topic), ...ascii(payload)]));
    return true;
  }

  close() {
    this.closed = true;
    this.ready = false;
    this.stopPing();
    if (this.timer) clearTimeout(this.timer);
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState === 1) socket.send(new Uint8Array([0xe0, 0]).buffer);
    socket?.close();
  }
}
