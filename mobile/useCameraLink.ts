import { useCallback, useEffect, useRef, useState } from 'react';
import { ICE_SERVERS, PairingChannel, type Envelope } from './pairing';
import { RTCPeerConnection, RTCSessionDescription, type MediaStream } from './webrtc';
import { FrameReceiver } from './frameRelay';
import SkyCopSim from './modules/skycop-sim';

export type LinkStatus = 'idle' | 'searching' | 'connecting' | 'live' | 'error';

function waitForIce(peer: RTCPeerConnection, timeout = 6000): Promise<void> {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(done, timeout);
    function done() {
      clearTimeout(timer);
      peer.onicegatheringstatechange = null;
      resolve();
    }
    peer.onicegatheringstatechange = () => {
      if (peer.iceGatheringState === 'complete') done();
    };
  });
}

/** Joins a camera station by pairing code and keeps the video coming back after drops. */
export function useCameraLink() {
  const channelRef = useRef<PairingChannel | null>(null);
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const cameraRef = useRef('');
  const helloTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** Listens for relay video from the moment the code is entered, so it shows up within seconds. */
  const relayRef = useRef<FrameReceiver | null>(null);
  /** Relay video is arriving (and no direct link has taken over). */
  const relayLive = useRef(false);
  const codeRef = useRef('');
  const lastFrameAt = useRef(0);
  const [status, setStatus] = useState<LinkStatus>('idle');
  const [stream, setStream] = useState<MediaStream | null>(null);
  /** A recent picture (base64 JPEG) while the video comes through the relay, else null. */
  const [relayFrame, setRelayFrame] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [code, setCode] = useState('');

  const closePeer = useCallback(() => {
    const peer = peerRef.current;
    peerRef.current = null;
    peer?.close();
    setStream(null);
  }, []);

  /** The relay picture is no longer wanted (a direct link took over, or the camera went away). */
  const dropRelay = useCallback(() => {
    if (relayLive.current) SkyCopSim?.closeVideo();
    relayLive.current = false;
    setRelayFrame(null);
  }, []);

  const stopRelay = useCallback(() => {
    relayRef.current?.close();
    relayRef.current = null;
    relayLive.current = false;
    setRelayFrame(null);
  }, []);

  // Says hello until a camera answers, so the order in which the two sides start does not matter.
  // The gap grows from 4 s to 30 s; the camera also prompts a hello whenever it comes online.
  const keepAsking = useCallback(() => {
    if (helloTimer.current) clearTimeout(helloTimer.current);
    let gap = 4000;
    const ask = () => {
      const channel = channelRef.current;
      if (!channel || peerRef.current || relayLive.current) return;
      channel.send('hello').then(() => {
        if (channelRef.current === channel) setError((current) => (current.startsWith('Can\'t reach') ? '' : current));
      }).catch((caught: unknown) => {
        if (channelRef.current !== channel) return;
        setError(`Can't reach the pairing relay (${caught instanceof Error ? caught.message : 'unknown error'}). Check this phone's internet connection.`);
      });
      helloTimer.current = setTimeout(ask, gap);
      gap = Math.min(30000, gap * 2);
    };
    ask();
  }, []);

  const answer = useCallback(async (message: Envelope) => {
    const channel = channelRef.current;
    if (!channel || !message.sdp) return;
    closePeer();
    cameraRef.current = message.session;
    const peer = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    peerRef.current = peer;
    if (!relayLive.current) setStatus('connecting');
    (peer as unknown as { ontrack: (event: { streams: MediaStream[] }) => void }).ontrack = (event) => {
      const [incoming] = event.streams;
      if (incoming && peerRef.current === peer) setStream(incoming);
    };
    (peer as unknown as { onconnectionstatechange: () => void }).onconnectionstatechange = () => {
      if (peerRef.current !== peer) return;
      const state = peer.connectionState;
      if (state === 'connected') {
        // The direct link is sharper than the relay; the camera stops the relay now.
        dropRelay();
        setStatus('live');
        setError('');
      } else if (state === 'failed') {
        // No direct route (different networks): the relay, already running, carries the video.
        closePeer();
        if (!relayLive.current) setStatus('connecting');
      } else if (state === 'disconnected') {
        setTimeout(() => {
          if (peerRef.current !== peer || peer.connectionState === 'connected') return;
          closePeer();
          if (relayLive.current) return;
          setStatus('searching');
          keepAsking();
        }, 4000);
      }
    };
    await peer.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp: message.sdp }));
    await peer.setLocalDescription(await peer.createAnswer());
    await waitForIce(peer);
    if (peerRef.current !== peer || !peer.localDescription?.sdp) return;
    await channel.send('answer', { to: message.session, sdp: peer.localDescription.sdp });
  }, [closePeer, dropRelay, keepAsking]);

  /** The camera cannot reach this phone directly: watch the frames it sends through the relay. */
  const watchRelay = useCallback((camera: string) => {
    cameraRef.current = camera;
    if (!relayLive.current && !peerRef.current) {
      lastFrameAt.current = Date.now();
      setStatus('connecting');
    }
  }, []);

  /** Opens the relay listener for this pairing; frames show up as soon as the camera sends them. */
  const listen = useCallback((channel: PairingChannel) => {
    let toldAt = 0;
    let askedAt = 0;
    const askForKey = () => {
      const now = Date.now();
      if (now - askedAt < 2000) return;
      askedAt = now;
      void channel.send('key').catch(() => undefined);
    };
    const receiver = new FrameReceiver(channel.frameTopic, channel.keyHex, (preview) => {
      if (relayRef.current !== receiver || channelRef.current !== channel) return;
      // A direct link that is up wins; stray relay frames from before it are ignored.
      if (peerRef.current?.connectionState === 'connected') return;
      const now = Date.now();
      lastFrameAt.current = now;
      relayLive.current = true;
      // Tell the camera every 3 s that this phone is still watching (the relay is one-way). This rides
      // on arriving frames, not a timer: Android pauses JS timers while the VR screen is in front.
      if (now - toldAt > 3000) {
        toldAt = now;
        void channel.send('watching').catch(() => undefined);
      }
      if (!preview) return;
      setRelayFrame(preview);
      setStatus('live');
      setError('');
    }, askForKey);
    relayRef.current = receiver;
    receiver.open();
  }, []);

  // Frames stopped coming: the camera went away, so look for it again.
  useEffect(() => {
    const timer = setInterval(() => {
      if (!relayLive.current || peerRef.current || Date.now() - lastFrameAt.current < 12000) return;
      dropRelay();
      setStatus('searching');
      keepAsking();
    }, 2000);
    return () => clearInterval(timer);
  }, [dropRelay, keepAsking]);

  const disconnect = useCallback(() => {
    if (helloTimer.current) clearTimeout(helloTimer.current);
    const channel = channelRef.current;
    channelRef.current = null;
    if (channel) void channel.send('bye').catch(() => undefined).finally(() => channel.close());
    closePeer();
    stopRelay();
    setStatus('idle');
    setError('');
  }, [closePeer, stopRelay]);

  const connect = useCallback((pairingCode: string) => {
    // Already on this code (the saved code and a scanned link can both ask at launch): nothing to do.
    if (channelRef.current && codeRef.current === pairingCode) return;
    codeRef.current = pairingCode;
    disconnect();
    setCode(pairingCode);
    setError('');
    setStatus('searching');
    try {
      const channel = new PairingChannel(pairingCode, 'viewer', (message) => {
        if (channelRef.current !== channel) return;
        if (message.type === 'ready' && !peerRef.current && !relayLive.current) void channel.send('hello').catch(() => undefined);
        else if (message.type === 'relay' && message.to === channel.session) watchRelay(message.session);
        else if (message.type === 'offer' && message.to === channel.session) {
          void answer(message).catch(() => {
            setError('The camera sent connection details this phone could not use.');
            closePeer();
            setStatus('searching');
          });
        } else if (message.type === 'bye' && message.session === cameraRef.current) {
          closePeer();
          dropRelay();
          setStatus('searching');
          setError('The camera stopped. It reconnects when the camera starts again.');
        }
      }, (open, detail) => {
        if (channelRef.current !== channel) return;
        if (open) {
          setError((current) => (current.startsWith('Can\'t reach') ? '' : current));
          keepAsking();
        } else {
          setError(`Can't reach the pairing relay (${detail ?? 'connection lost'}). Retrying…`);
        }
      });
      channelRef.current = channel;
      channel.open();
      listen(channel);
      // Hello goes out by plain HTTPS straight away; it does not need the listening connection.
      keepAsking();
    } catch (caught) {
      setStatus('error');
      setError(caught instanceof Error ? caught.message : 'Could not start pairing.');
    }
  }, [answer, closePeer, disconnect, dropRelay, keepAsking, listen, watchRelay]);

  useEffect(() => () => {
    if (helloTimer.current) clearTimeout(helloTimer.current);
    channelRef.current?.close();
    peerRef.current?.close();
    relayRef.current?.close();
  }, []);

  return { status, stream, relayFrame, error, code, connect, disconnect };
}
