import { useCallback, useEffect, useRef, useState } from 'react';
import { ICE_SERVERS, PairingChannel, type Envelope } from './pairing';
import { RTCPeerConnection, RTCSessionDescription, type MediaStream } from './webrtc';
import { FrameReceiver } from './frameRelay';

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
  const relayRef = useRef<FrameReceiver | null>(null);
  const lastFrameAt = useRef(0);
  const [status, setStatus] = useState<LinkStatus>('idle');
  const [stream, setStream] = useState<MediaStream | null>(null);
  /** The newest frame (base64 JPEG) while the video comes through the relay, else null. */
  const [relayFrame, setRelayFrame] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [code, setCode] = useState('');

  const closePeer = useCallback(() => {
    const peer = peerRef.current;
    peerRef.current = null;
    peer?.close();
    setStream(null);
  }, []);

  const stopRelay = useCallback(() => {
    relayRef.current?.close();
    relayRef.current = null;
    setRelayFrame(null);
  }, []);

  // Says hello until a camera answers, so the order in which the two sides start does not matter.
  // The gap grows from 4 s to 30 s; the camera also prompts a hello whenever it comes online.
  const keepAsking = useCallback(() => {
    if (helloTimer.current) clearTimeout(helloTimer.current);
    let gap = 4000;
    const ask = () => {
      const channel = channelRef.current;
      if (!channel || peerRef.current || relayRef.current) return;
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
    stopRelay();
    cameraRef.current = message.session;
    const peer = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    peerRef.current = peer;
    setStatus('connecting');
    (peer as unknown as { ontrack: (event: { streams: MediaStream[] }) => void }).ontrack = (event) => {
      const [incoming] = event.streams;
      if (incoming && peerRef.current === peer) setStream(incoming);
    };
    (peer as unknown as { onconnectionstatechange: () => void }).onconnectionstatechange = () => {
      if (peerRef.current !== peer) return;
      const state = peer.connectionState;
      if (state === 'connected') {
        setStatus('live');
        setError('');
      } else if (state === 'failed') {
        // The camera switches to the relay when the direct link fails; just wait for it.
        closePeer();
        setStatus('connecting');
      } else if (state === 'disconnected') {
        setTimeout(() => {
          if (peerRef.current !== peer || peer.connectionState === 'connected') return;
          closePeer();
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
  }, [closePeer, keepAsking, stopRelay]);

  /** The camera cannot reach this phone directly: watch the frames it sends through the relay. */
  const watchRelay = useCallback((camera: string) => {
    const channel = channelRef.current;
    if (!channel || relayRef.current) return;
    closePeer();
    cameraRef.current = camera;
    lastFrameAt.current = Date.now();
    setStatus('connecting');
    const receiver = new FrameReceiver(channel.frameTopic, channel.keyHex, (jpeg) => {
      if (relayRef.current !== receiver) return;
      lastFrameAt.current = Date.now();
      setRelayFrame(jpeg);
      setStatus('live');
      setError('');
    });
    relayRef.current = receiver;
    receiver.open();
  }, [closePeer]);

  // Frames stopped coming: the camera went away, so look for it again.
  useEffect(() => {
    const timer = setInterval(() => {
      if (!relayRef.current || Date.now() - lastFrameAt.current < 12000) return;
      stopRelay();
      setStatus('searching');
      keepAsking();
    }, 2000);
    return () => clearInterval(timer);
  }, [keepAsking, stopRelay]);

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
    disconnect();
    setCode(pairingCode);
    setError('');
    setStatus('searching');
    try {
      const channel = new PairingChannel(pairingCode, 'viewer', (message) => {
        if (channelRef.current !== channel) return;
        if (message.type === 'ready' && !peerRef.current && !relayRef.current) void channel.send('hello').catch(() => undefined);
        else if (message.type === 'relay' && message.to === channel.session) watchRelay(message.session);
        else if (message.type === 'offer' && message.to === channel.session) {
          void answer(message).catch(() => {
            setError('The camera sent connection details this phone could not use.');
            closePeer();
            setStatus('searching');
          });
        } else if (message.type === 'bye' && message.session === cameraRef.current) {
          closePeer();
          stopRelay();
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
      // Hello goes out by plain HTTPS straight away; it does not need the listening connection.
      keepAsking();
    } catch (caught) {
      setStatus('error');
      setError(caught instanceof Error ? caught.message : 'Could not start pairing.');
    }
  }, [answer, closePeer, disconnect, keepAsking, stopRelay, watchRelay]);

  useEffect(() => () => {
    if (helloTimer.current) clearTimeout(helloTimer.current);
    channelRef.current?.close();
    peerRef.current?.close();
    relayRef.current?.close();
  }, []);

  return { status, stream, relayFrame, error, code, connect, disconnect };
}
