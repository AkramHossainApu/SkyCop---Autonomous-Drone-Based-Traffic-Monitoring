// Pairing over a public relay, with nothing to run on your own machine.
//
// GitHub Pages is HTTPS, so the page cannot talk to a phone on the local network directly. The two
// sides exchange their WebRTC connection details once through free public relays: MQTT brokers over
// WebSocket, with ntfy.sh as a fallback. Every message is encrypted with AES-256-GCM using a key
// derived from the pairing code, so the relays only ever see ciphertext. The video itself flows directly between the
// devices. The phone app implements the same protocol in mobile/pairing.ts.

import { MQTT_BROKERS, MqttLink } from './mqtt';

export const RELAY = 'https://ntfy.sh';
const ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789';
// ntfy.sh rejects message bodies over 4096 bytes; ciphertext is split into parts below that.
const PART = 3000;
// Messages older than this belong to an earlier session and are ignored.
const MAX_AGE_SECONDS = 120;

export type Role = 'camera' | 'viewer';
export type Envelope = {
  v: 1;
  from: Role;
  session: string;
  at: number;
  type: 'ready' | 'hello' | 'offer' | 'answer' | 'bye' | 'relay';
  to?: string;
  sdp?: string;
};

export function newCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(9));
  const letters = Array.from(bytes, (byte) => ALPHABET[byte % ALPHABET.length]).join('');
  return `${letters.slice(0, 3)}-${letters.slice(3, 6)}-${letters.slice(6)}`;
}

export function isCode(value: string): boolean {
  return /^[a-hjkmnp-z2-9]{3}-[a-hjkmnp-z2-9]{3}-[a-hjkmnp-z2-9]{3}$/.test(value);
}

export function normalizeCode(value: string): string {
  const letters = value.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (letters.length !== 9) return value.trim().toLowerCase();
  return `${letters.slice(0, 3)}-${letters.slice(3, 6)}-${letters.slice(6)}`;
}

function hex(bytes: ArrayBuffer) {
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256(text: string) {
  return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
}

function toBase64(bytes: Uint8Array) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(binary);
}

function fromBase64(text: string) {
  return Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
}

export async function topicFor(code: string) {
  return `skycop-${hex(await sha256(`skycop/topic/${code}`)).slice(0, 32)}`;
}

async function keyFor(code: string) {
  return crypto.subtle.importKey('raw', await sha256(`skycop/key/${code}`), 'AES-GCM', false, ['encrypt', 'decrypt']);
}

/**
 * One end of the relay channel. Messages go out over the public MQTT brokers (no quota) and fall
 * back to an ntfy.sh POST only when no broker is reachable; they are received from all of them and
 * de-duplicated. Messages from the same role are ignored, so a page can never answer itself, and
 * chunks are reassembled before decryption.
 */
export class PairingChannel {
  private socket: WebSocket | null = null;
  private links: MqttLink[] = [];
  private key: CryptoKey | null = null;
  private topic = '';
  private closed = false;
  private retry = 0;
  private ntfyOpen = false;
  private wasOpen = false;
  private parts = new Map<string, string[]>();
  private done = new Set<string>();
  private openedAt = 0;
  private since = Math.floor(Date.now() / 1000) - 5;
  readonly session = Math.random().toString(36).slice(2, 10);

  /** Where video frames go when the devices cannot reach each other directly. */
  get frameTopic() {
    return `skycop/${this.topic}/f`;
  }

  /** Encrypts bytes with the pairing key: base64 of iv + ciphertext + tag, as the phone's native open() expects. */
  async sealBytes(bytes: Uint8Array): Promise<string> {
    if (!this.key) throw new Error('Not open');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, this.key, bytes);
    const sealed = new Uint8Array(12 + cipher.byteLength);
    sealed.set(iv); sealed.set(new Uint8Array(cipher), 12);
    return toBase64(sealed);
  }

  constructor(
    private readonly code: string,
    private readonly role: Role,
    private readonly onMessage: (message: Envelope) => void,
    private readonly onState: (open: boolean) => void,
  ) {}

  async open() {
    this.key = await keyFor(this.code);
    this.topic = await topicFor(this.code);
    this.openedAt = Date.now();
    this.links = MQTT_BROKERS.map((url) => new MqttLink(url, `skycop/${this.topic}`, (body) => void this.receive(body), (ready) => this.report(ready)));
    this.links.forEach((link) => link.open());
    this.connect();
  }

  /** Reports every transport that comes up (so the other side can be greeted again), and the moment all are down. */
  private report(cameUp: boolean) {
    if (this.closed) return;
    const open = this.ntfyOpen || this.links.some((link) => link.ready);
    if (cameUp) this.onState(true);
    else if (!open && this.wasOpen) this.onState(false);
    this.wasOpen = open;
  }

  private connect() {
    if (this.closed) return;
    const url = `${RELAY.replace(/^http/, 'ws')}/${this.topic}/ws?since=${this.since}`;
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.onmessage = (event) => {
      const data = JSON.parse(String(event.data)) as { event: string; message?: string; time?: number };
      if (data.event === 'open') {
        this.retry = 0;
        this.ntfyOpen = true;
        this.report(true);
      } else if (data.event === 'message' && data.message) {
        if (data.time) this.since = Math.max(this.since, data.time);
        void this.receive(data.message);
      }
    };
    socket.onclose = () => {
      if (this.socket !== socket || this.closed) return;
      this.ntfyOpen = false;
      this.report(false);
      const delay = Math.min(30000, 1000 * 2 ** this.retry++);
      window.setTimeout(() => this.connect(), delay);
    };
  }

  private async receive(body: string) {
    const match = /^s1\.([a-z0-9]+)\.(\d+)\.(\d+)\.(.+)$/.exec(body);
    if (!match || !this.key) return;
    const [, id, indexText, countText, part] = match;
    if (this.done.has(id)) return;
    const index = Number(indexText); const count = Number(countText);
    if (count < 1 || count > 20 || index >= count) return;
    const parts = this.parts.get(id) ?? new Array<string>(count).fill('');
    parts[index] = part;
    this.parts.set(id, parts);
    if (parts.some((p) => !p)) return;
    this.parts.delete(id);
    this.done.add(id);
    if (this.done.size > 500) this.done.clear();
    try {
      const sealed = fromBase64(parts.join(''));
      const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: sealed.slice(0, 12) }, this.key, sealed.slice(12));
      const message = JSON.parse(new TextDecoder().decode(plain)) as Envelope;
      if (message.v !== 1 || message.from === this.role) return;
      if (Math.abs(Date.now() / 1000 - message.at) > MAX_AGE_SECONDS) return;
      this.onMessage(message);
    } catch {
      // Not ours, or tampered with: ignore.
    }
  }

  async send(type: Envelope['type'], extra: Partial<Envelope> = {}) {
    if (!this.key) return;
    const message: Envelope = { v: 1, from: this.role, session: this.session, at: Math.floor(Date.now() / 1000), type, ...extra };
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, this.key, new TextEncoder().encode(JSON.stringify(message)));
    const sealed = new Uint8Array(12 + cipher.byteLength);
    sealed.set(iv); sealed.set(new Uint8Array(cipher), 12);
    const text = toBase64(sealed);
    const id = Math.random().toString(36).slice(2, 10);
    const count = Math.ceil(text.length / PART);
    const bodies = Array.from({ length: count }, (_, i) => `s1.${id}.${i}.${count}.${text.slice(i * PART, (i + 1) * PART)}`);
    // Right after opening, give the brokers a moment to connect before falling back.
    for (let waited = 0; waited < 5000 && !this.links.some((link) => link.ready) && Date.now() - this.openedAt < 8000; waited += 250) {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    let sent = false;
    for (const link of this.links) {
      if (bodies.every((body) => link.publish(body))) sent = true;
    }
    if (sent) return;
    // No broker reachable: ntfy.sh, which allows 250 messages per network every 12 hours.
    for (const body of bodies) {
      const response = await fetch(`${RELAY}/${this.topic}`, { method: 'POST', body });
      if (response.status === 429) throw new Error('The relay is busy for this network right now. Try again in a few minutes.');
      if (!response.ok) throw new Error(`The relay refused the message (${response.status}).`);
    }
  }

  close() {
    this.closed = true;
    this.links.forEach((link) => link.close());
    const socket = this.socket;
    this.socket = null;
    socket?.close();
  }
}

export const ICE_SERVERS: RTCIceServer[] = [{ urls: 'stun:stun.l.google.com:19302' }];

export function waitForIceGathering(peer: RTCPeerConnection, timeout = 8000): Promise<void> {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    // Host candidates arrive first; on a local network they are all that is needed.
    const timer = window.setTimeout(done, timeout);
    function done() {
      window.clearTimeout(timer);
      peer.removeEventListener('icegatheringstatechange', onChange);
      resolve();
    }
    function onChange() {
      if (peer.iceGatheringState === 'complete') done();
    }
    peer.addEventListener('icegatheringstatechange', onChange);
  });
}
