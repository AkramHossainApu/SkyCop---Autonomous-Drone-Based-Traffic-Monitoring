import { useEffect, useRef, useState } from 'react';

export type BroadcastStatus = 'idle' | 'connecting' | 'waiting' | 'connected' | 'error';
type SignalMessage = { type: string; code?: string; sdp?: string; message?: string };

function waitForIceGathering(peer: RTCPeerConnection): Promise<void> {
  if (peer.iceGatheringState === 'complete') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => {
      peer.removeEventListener('icegatheringstatechange', onChange);
      reject(new Error('Network candidate gathering timed out.'));
    }, 15000);
    function onChange() {
      if (peer.iceGatheringState !== 'complete') return;
      window.clearTimeout(timer);
      peer.removeEventListener('icegatheringstatechange', onChange);
      resolve();
    }
    peer.addEventListener('icegatheringstatechange', onChange);
    onChange();
  });
}

export function useCameraBroadcast(stream: MediaStream | null, signalingAddress: string) {
  const peerRef = useRef<RTCPeerConnection | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<BroadcastStatus>('idle');
  const [error, setError] = useState('');
  const [meetingCode, setMeetingCode] = useState('');

  function closePeer() {
    const peer = peerRef.current;
    peerRef.current = null;
    peer?.close();
  }

  function stop() {
    const socket = socketRef.current;
    socketRef.current = null;
    socket?.close();
    closePeer();
    setStatus('idle');
    setError('');
    setMeetingCode('');
  }

  useEffect(() => () => {
    socketRef.current?.close();
    socketRef.current = null;
    closePeer();
  }, []);

  async function createOffer(socket: WebSocket) {
    if (!stream || socketRef.current !== socket) return;
    closePeer();
    const peer = new RTCPeerConnection({ iceServers: [] });
    peerRef.current = peer;
    stream.getTracks().forEach((track) => peer.addTrack(track, stream));
    peer.onconnectionstatechange = () => {
      if (peerRef.current !== peer) return;
      if (peer.connectionState === 'connected') setStatus('connected');
      if (peer.connectionState === 'failed' || peer.connectionState === 'disconnected') {
        setStatus('error');
        setError('Video connection lost. Check that the devices can reach each other.');
      }
    };
    await peer.setLocalDescription(await peer.createOffer());
    await waitForIceGathering(peer);
    if (peerRef.current !== peer || socketRef.current !== socket || socket.readyState !== WebSocket.OPEN) return;
    if (!peer.localDescription?.sdp) throw new Error('Could not create an offer.');
    socket.send(JSON.stringify({ type: 'offer', sdp: peer.localDescription.sdp }));
  }

  function start() {
    if (!stream || socketRef.current) return;
    setError('');
    setStatus('connecting');
    try {
      const signalingUrl = signalingAddress.trim();
      if (!signalingUrl) throw new Error('Enter the secure signaling address for your Mac host.');
      const url = new URL(signalingUrl);
      if (!['ws:', 'wss:'].includes(url.protocol) || (location.protocol === 'https:' && url.protocol !== 'wss:')) {
        throw new Error('GitHub Pages requires a secure wss:// signaling address.');
      }
      const socket = new WebSocket(url.toString());
      socketRef.current = socket;
      let opened = false;
      let serverError = '';
      socket.onopen = () => {
        opened = true;
        socket.send(JSON.stringify({ type: 'create' }));
      };
      socket.onmessage = (event) => {
        void (async () => {
          const message = JSON.parse(String(event.data)) as SignalMessage;
          if (message.type === 'created' && message.code) {
            setMeetingCode(message.code);
            setStatus('waiting');
          } else if (message.type === 'viewer-joined') {
            setStatus('connecting');
            await createOffer(socket);
          } else if (message.type === 'answer' && message.sdp && peerRef.current) {
            await peerRef.current.setRemoteDescription({ type: 'answer', sdp: message.sdp });
          } else if (message.type === 'peer-left') {
            closePeer();
            setStatus('waiting');
          } else if (message.type === 'error') {
            serverError = message.message ?? 'Signaling failed.';
            setError(serverError);
            setStatus('error');
          }
        })().catch(() => {
          setError('Could not negotiate a video connection.');
          setStatus('error');
        });
      };
      socket.onerror = () => {
        if (socketRef.current === socket) {
          setError('Cannot reach the signaling host. Check the address and server.');
          setStatus('error');
        }
      };
      socket.onclose = (event) => {
        if (socketRef.current !== socket) return;
        socketRef.current = null;
        closePeer();
        setMeetingCode('');
        setStatus('error');
        setError(serverError || (event.code === 1008
          ? 'The signaling host rejected this website origin. Check ALLOWED_ORIGINS on the Mac.'
          : opened
            ? 'The signaling host or tunnel stopped. Restart it and use its current address.'
            : 'Could not reach the signaling host. Check that the Mac is running and use the latest tunnel address.'));
      };
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Could not open the signaling connection.');
      setStatus('error');
    }
  }

  return { status, error, meetingCode, start, stop };
}