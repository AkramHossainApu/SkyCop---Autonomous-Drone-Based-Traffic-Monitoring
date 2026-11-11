import { createHmac } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';

const port = Number(process.env.PORT ?? 8787);
const rooms = new Map();
const server = new WebSocketServer({ host: '0.0.0.0', port, maxPayload: 64 * 1024 });
const peerRoles = new Set(['sender', 'viewer']);
const signalTypes = new Set(['offer', 'answer', 'candidate']);
const stunUrls = (process.env.STUN_URLS ?? 'stun:stun.l.google.com:19302').split(',').map((url) => url.trim()).filter(Boolean);
const turnUrls = (process.env.TURN_URLS ?? '').split(',').map((url) => url.trim()).filter(Boolean);
const turnSecret = process.env.TURN_SECRET;
const turnTtlSeconds = Math.max(60, Number(process.env.TURN_TTL_SECONDS ?? 3600));
const allowedOrigins = new Set((process.env.ALLOWED_ORIGINS ?? '').split(',').map((origin) => origin.trim()).filter(Boolean));

function getIceServers(room) {
  const iceServers = stunUrls.length ? [{ urls: stunUrls }] : [];
  if (turnUrls.length && turnSecret) {
    const username = `${Math.floor(Date.now() / 1000) + turnTtlSeconds}:${room}`;
    const credential = createHmac('sha1', turnSecret).update(username).digest('base64');
    iceServers.push({ urls: turnUrls, username, credential });
  }
  return iceServers;
}

function send(socket, message) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function leaveRoom(socket) {
  if (!socket.room || !socket.role) return;
  const members = rooms.get(socket.room);
  if (!members) return;

  const isCurrentMember = members.get(socket.role) === socket;
  if (isCurrentMember) {
    members.delete(socket.role);
    const otherRole = socket.role === 'sender' ? 'viewer' : 'sender';
    send(members.get(otherRole), { type: 'peer-left' });
  }
  if (members.size === 0) rooms.delete(socket.room);
  socket.room = undefined;
  socket.role = undefined;
}

server.on('connection', (socket, request) => {
  const origin = request.headers.origin;
  if (origin && allowedOrigins.size > 0 && !allowedOrigins.has(origin)) {
    socket.close(1008, 'Origin is not allowed.');
    return;
  }

  socket.on('message', (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      send(socket, { type: 'error', message: 'Invalid message.' });
      return;
    }

    if (message.type === 'join') {
      if (typeof message.room !== 'string' || !/^[a-f0-9]{32}$/.test(message.room) || !peerRoles.has(message.role)) {
        send(socket, { type: 'error', message: 'A valid 128-bit pairing code and peer role are required.' });
        return;
      }

      leaveRoom(socket);
      const members = rooms.get(message.room) ?? new Map();
      const otherRole = message.role === 'sender' ? 'viewer' : 'sender';
      const otherPeer = members.get(otherRole);
      const previousPeer = members.get(message.role);

      if (previousPeer && previousPeer !== socket) {
        send(previousPeer, { type: 'peer-left' });
        previousPeer.close(1000, 'Replaced by a newer peer');
      }

      socket.room = message.room;
      socket.role = message.role;
      members.set(message.role, socket);
      rooms.set(message.room, members);
      send(socket, { type: 'joined', role: message.role, iceServers: getIceServers(message.room) });

      if (otherPeer && otherPeer.readyState === WebSocket.OPEN) {
        send(otherPeer, { type: 'peer-joined', role: message.role });
        send(socket, { type: 'peer-joined', role: otherRole });
      }
      return;
    }

    if (signalTypes.has(message.type) && socket.room && socket.role) {
      const members = rooms.get(socket.room);
      const recipientRole = socket.role === 'sender' ? 'viewer' : 'sender';
      send(members?.get(recipientRole), { type: message.type, payload: message.payload });
      return;
    }

    send(socket, { type: 'error', message: 'Join a room before sending a signal.' });
  });

  socket.on('close', () => leaveRoom(socket));
  socket.on('error', () => leaveRoom(socket));
});

console.log(`SkyCop development signaling server listening on ws://0.0.0.0:${port}`);
if (turnUrls.length && !turnSecret) console.warn('TURN_URLS is set without TURN_SECRET; clients will only receive STUN servers.');
console.log('For public hosting, terminate TLS at the hosting proxy and use an authenticated, rate-limited service.');