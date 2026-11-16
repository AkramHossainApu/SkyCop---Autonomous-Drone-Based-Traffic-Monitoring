import { randomInt } from 'node:crypto';
import { createServer } from 'node:http';
import { WebSocket, WebSocketServer } from 'ws';

const port = Number(process.env.PORT ?? 8787);
const allowedOrigins = new Set((process.env.ALLOWED_ORIGINS ?? 'http://localhost:5173,http://127.0.0.1:5173').split(',').map((origin) => origin.trim()).filter(Boolean));
const alphabet = 'abcdefghjkmnpqrstuvwxyz23456789';
const rooms = new Map();
const attempts = new Map();
const server = createServer((_request, response) => {
  response.writeHead(200, { 'Content-Type': 'text/plain' });
  response.end('SkyCop signaling is running.');
});
const sockets = new WebSocketServer({ server, maxPayload: 65536 });

function send(socket, message) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function generateCode() {
  let code = '';
  for (let index = 0; index < 9; index++) code += alphabet[randomInt(alphabet.length)];
  return `${code.slice(0, 3)}-${code.slice(3, 6)}-${code.slice(6)}`;
}

function leave(socket) {
  const room = rooms.get(socket.code);
  if (!room) return;
  if (room.sender === socket) {
    rooms.delete(socket.code);
    send(room.viewer, { type: 'peer-left' });
    room.viewer?.close(1000, 'Sender disconnected');
  } else if (room.viewer === socket) {
    room.viewer = null;
    send(room.sender, { type: 'peer-left' });
  }
  socket.code = undefined;
  socket.role = undefined;
}

sockets.on('connection', (socket, request) => {
  const origin = request.headers.origin;
  const nativeOrigin = !origin || origin === 'null' || origin === `http://${request.headers.host}` || origin === `https://${request.headers.host}`;
  if (!nativeOrigin && !allowedOrigins.has(origin)) {
    socket.close(1008, 'Origin not allowed');
    return;
  }
  socket.on('message', (raw) => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { socket.close(1003, 'Invalid message'); return; }
    if (!message || typeof message !== 'object') { socket.close(1003, 'Invalid message'); return; }

    if (message.type === 'create' && !socket.code) {
      if (rooms.size >= 100) { send(socket, { type: 'error', message: 'Host is busy. Try again later.' }); return; }
      let code;
      do { code = generateCode(); } while (rooms.has(code));
      socket.code = code;
      socket.role = 'sender';
      rooms.set(code, { sender: socket, viewer: null, created: Date.now() });
      send(socket, { type: 'created', code });
      return;
    }

    if (message.type === 'join' && !socket.code) {
      const address = request.socket.remoteAddress ?? 'unknown';
      const now = Date.now();
      const recent = (attempts.get(address) ?? []).filter((time) => now - time < 60000);
      if (recent.length >= 20) { socket.close(1008, 'Too many attempts'); return; }
      recent.push(now);
      attempts.set(address, recent);
      const code = typeof message.code === 'string' ? message.code.trim().toLowerCase() : '';
      const room = rooms.get(code);
      if (!room || now - room.created > 3600000 || room.viewer) {
        send(socket, { type: 'error', message: 'Code not found or already in use.' });
        return;
      }
      socket.code = code;
      socket.role = 'viewer';
      room.viewer = socket;
      send(socket, { type: 'joined' });
      send(room.sender, { type: 'viewer-joined' });
      return;
    }

    const room = rooms.get(socket.code);
    if (room && typeof message.sdp === 'string' && message.sdp.length <= 60000 &&
        ((socket.role === 'sender' && message.type === 'offer') || (socket.role === 'viewer' && message.type === 'answer'))) {
      send(socket.role === 'sender' ? room.viewer : room.sender, { type: message.type, sdp: message.sdp });
      return;
    }
    send(socket, { type: 'error', message: 'Invalid signaling message.' });
  });
  socket.on('close', () => leave(socket));
  socket.on('error', () => leave(socket));
});

const cleanup = setInterval(() => {
  const now = Date.now();
  for (const [address, times] of attempts) {
    if (times.every((time) => now - time >= 60000)) attempts.delete(address);
  }
  for (const [code, room] of rooms) {
    if (now - room.created > 3600000) {
      send(room.sender, { type: 'error', message: 'Session expired. Start a new session.' });
      room.sender.close(1000, 'Session expired');
      room.viewer?.close(1000, 'Session expired');
      rooms.delete(code);
    }
  }
}, 60000);
cleanup.unref();

server.listen(port, '0.0.0.0', () => console.log(`SkyCop signaling host listening on port ${port}`));