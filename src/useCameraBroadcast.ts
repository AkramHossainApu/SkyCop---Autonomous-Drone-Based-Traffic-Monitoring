import { useCallback, useEffect, useRef, useState } from 'react';
import { ICE_SERVERS, PairingChannel, waitForIceGathering, type Envelope } from './pairing';
import { FrameSender } from './frameRelay';

/** How long a direct link gets to come up before the video goes through the relay instead. */
const DIRECT_TIMEOUT_MS = 12000;
/** `?relay` in the page address skips the direct link, to try the relay on one network. */
const FORCE_RELAY = new URLSearchParams(window.location.search).has('relay');

export type BroadcastStatus = 'idle' | 'opening' | 'waiting' | 'connecting' | 'connected' | 'error';
export type LogEntry = { at: Date; text: string };
export type LinkStats = { width: number; height: number; fps: number; kbps: number; rttMs: number | null; route: string };

/** Shares one camera stream with one phone at a time, paired by code. */
export function useCameraBroadcast(stream: MediaStream | null, code: string) {
  const channelRef = useRef<PairingChannel | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const viewerRef = useRef('');
  const offeredAtRef = useRef(0);
  const refusedRef = useRef(new Set<string>());
  const relayRef = useRef<FrameSender | null>(null);
  const relayViewerRef = useRef('');
  const streamRef = useRef(stream);
  streamRef.current = stream;
  const [status, setStatus] = useState<BroadcastStatus>('idle');
  const [error, setError] = useState('');
  const [log, setLog] = useState<LogEntry[]>([]);
  const [stats, setStats] = useState<LinkStats | null>(null);

  const note = useCallback((text: string) => {
    setLog((entries) => [{ at: new Date(), text }, ...entries].slice(0, 30));
  }, []);

  const closePeer = useCallback(() => {
    const peer = peerRef.current;
    peerRef.current = null;
    peer?.close();
    setStats(null);
  }, []);

  const stopRelay = useCallback(() => {
    relayRef.current?.stop();
    relayRef.current = null;
    relayViewerRef.current = '';
  }, []);

  /** The devices cannot reach each other directly: send the video through the public brokers. */
  const startRelay = useCallback((viewer: string) => {
    const channel = channelRef.current;
    const media = streamRef.current;
    if (!channel || !media) return;
    closePeer();
    stopRelay();
    relayViewerRef.current = viewer;
    viewerRef.current = viewer;
    void channel.send('relay', { to: viewer }).catch(() => undefined);
    const sender = new FrameSender(channel.frameTopic, (bytes) => channel.sealBytes(bytes), media);
    relayRef.current = sender;
    sender.start();
    setError('');
    setStatus('connected');
    note('No direct route to the phone: video goes through the relay');
  }, [closePeer, note, stopRelay]);

  const offerTo = useCallback(async (viewer: string) => {
    const channel = channelRef.current;
    const media = streamRef.current;
    if (!channel || !media) return;
    closePeer();
    stopRelay();
    if (FORCE_RELAY) { startRelay(viewer); return; }
    viewerRef.current = viewer;
    offeredAtRef.current = Date.now();
    const peer = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    peerRef.current = peer;
    media.getTracks().forEach((track) => peer.addTrack(track, media));
    peer.onconnectionstatechange = () => {
      if (peerRef.current !== peer) return;
      if (peer.connectionState === 'connected') {
        setStatus('connected');
        note('Video is live on the phone');
      } else if (peer.connectionState === 'failed') {
        note('Direct connection failed');
        startRelay(viewer);
      } else if (peer.connectionState === 'disconnected') {
        // Wi-Fi hiccups recover by themselves; only give up if it stays down.
        window.setTimeout(() => {
          if (peerRef.current !== peer || peer.connectionState === 'connected') return;
          setStatus('waiting');
          note('Phone disconnected');
          closePeer();
        }, 4000);
      }
    };
    setStatus('connecting');
    await peer.setLocalDescription(await peer.createOffer());
    await waitForIceGathering(peer);
    if (peerRef.current !== peer || !peer.localDescription) return;
    await channel.send('offer', { to: viewer, sdp: peer.localDescription.sdp });
    note('Sent connection details to the phone');
    // Different networks (Wi-Fi and mobile data) often cannot connect directly at all.
    window.setTimeout(() => {
      if (peerRef.current === peer && peer.connectionState !== 'connected') startRelay(viewer);
    }, DIRECT_TIMEOUT_MS);
  }, [closePeer, note, startRelay, stopRelay]);

  const handle = useCallback((message: Envelope) => {
    const channel = channelRef.current;
    if (!channel) return;
    if (message.type === 'hello') {
      if (relayRef.current) {
        // The phone that is watching through the relay missed the news: tell it again.
        if (message.session === relayViewerRef.current) void channel.send('relay', { to: message.session }).catch(() => undefined);
        else if (!refusedRef.current.has(message.session)) {
          refusedRef.current.add(message.session);
          note('Another phone tried to join; it can connect when this one leaves');
        }
        return;
      }
      // A phone may say hello twice while one offer is in flight; answering both would race.
      const peer = peerRef.current;
      const busy = peer && message.session === viewerRef.current &&
        (peer.connectionState === 'connected' || Date.now() - offeredAtRef.current < 8000);
      if (busy) return;
      // One phone at a time: a second phone waits until the first leaves or drops, instead of the
      // two taking the stream from each other in turn.
      if (peer && peer.connectionState === 'connected' && message.session !== viewerRef.current) {
        if (!refusedRef.current.has(message.session)) {
          refusedRef.current.add(message.session);
          note('Another phone tried to join; it can connect when this one leaves');
        }
        return;
      }
      note('Phone asked to join');
      setError('');
      void offerTo(message.session).catch(() => {
        setError('Could not prepare the video connection. Try again.');
        setStatus('waiting');
      });
    } else if (message.type === 'answer' && message.to === channel.session && message.session === viewerRef.current && message.sdp) {
      void peerRef.current?.setRemoteDescription({ type: 'answer', sdp: message.sdp }).catch(() => {
        setError('The phone sent connection details this browser could not use.');
      });
    } else if (message.type === 'bye' && message.session === viewerRef.current) {
      note('Phone left');
      closePeer();
      stopRelay();
      setStatus('waiting');
    }
  }, [closePeer, note, offerTo, stopRelay]);

  const stop = useCallback(() => {
    const channel = channelRef.current;
    channelRef.current = null;
    if (channel) {
      void channel.send('bye').catch(() => undefined).finally(() => channel.close());
    }
    closePeer();
    stopRelay();
    setStatus('idle');
    setError('');
  }, [closePeer, stopRelay]);

  const start = useCallback(async () => {
    if (channelRef.current || !streamRef.current) return;
    setError('');
    setStatus('opening');
    const channel = new PairingChannel(code, 'camera', (message) => handle(message), (open) => {
      if (channelRef.current !== channel) return;
      if (open) {
        // Phones that were already waiting re-introduce themselves when they see this.
        void channel.send('ready').catch(() => undefined);
        setStatus((current) => (current === 'opening' ? 'waiting' : current));
      } else {
        note('Relay connection dropped, retrying');
      }
    });
    channelRef.current = channel;
    try {
      await channel.open();
      note(`Waiting for a phone on code ${code}`);
    } catch {
      channelRef.current = null;
      setStatus('error');
      setError('Could not reach the pairing relay. Check the internet connection.');
    }
  }, [code, handle, note]);

  // A new code means a new channel.
  useEffect(() => {
    if (!channelRef.current) return;
    stop();
    void start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code]);

  useEffect(() => () => {
    channelRef.current?.close();
    peerRef.current?.close();
    relayRef.current?.stop();
  }, []);

  useEffect(() => {
    if (!stream) stop();
  }, [stream, stop]);

  // Live link figures, read from the browser's own WebRTC statistics.
  useEffect(() => {
    if (status !== 'connected') return;
    let lastBytes = 0; let lastTime = 0;
    const timer = window.setInterval(async () => {
      const relay = relayRef.current;
      if (relay) {
        setStats({ ...relay.stats, rttMs: null, route: 'through the relay' });
        return;
      }
      const peer = peerRef.current;
      if (!peer) return;
      const report = await peer.getStats();
      let next: LinkStats = { width: 0, height: 0, fps: 0, kbps: 0, rttMs: null, route: 'local network' };
      report.forEach((entry) => {
        if (entry.type === 'outbound-rtp' && entry.kind === 'video') {
          next = { ...next, width: entry.frameWidth ?? 0, height: entry.frameHeight ?? 0, fps: Math.round(entry.framesPerSecond ?? 0) };
          if (lastTime) next.kbps = Math.round(((entry.bytesSent - lastBytes) * 8) / (entry.timestamp - lastTime));
          lastBytes = entry.bytesSent; lastTime = entry.timestamp;
        }
        if (entry.type === 'candidate-pair' && entry.nominated && entry.state === 'succeeded') {
          if (typeof entry.currentRoundTripTime === 'number') next.rttMs = Math.round(entry.currentRoundTripTime * 1000);
          const local = report.get(entry.localCandidateId);
          if (local && local.candidateType !== 'host') next.route = local.candidateType === 'relay' ? 'relayed' : 'via router';
        }
      });
      setStats(next);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [status]);

  return { status, error, log, stats, start, stop, note };
}
