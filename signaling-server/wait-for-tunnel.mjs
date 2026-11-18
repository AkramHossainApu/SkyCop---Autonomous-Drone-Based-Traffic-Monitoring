import { WebSocket } from 'ws';

const [address, origin] = process.argv.slice(2);
const deadline = Date.now() + 45000;

async function checkConnection() {
  return new Promise((resolve) => {
    const socket = new WebSocket(address, { origin, handshakeTimeout: 5000 });
    const timer = setTimeout(() => finish(false), 5000);
    let finished = false;

    function finish(ready) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      socket.terminate();
      resolve(ready);
    }

    socket.on('open', () => socket.send(JSON.stringify({ type: 'create' })));
    socket.on('message', (data) => {
      try {
        finish(JSON.parse(data.toString()).type === 'created');
      } catch {
        finish(false);
      }
    });
    socket.on('error', () => finish(false));
    socket.on('close', () => finish(false));
  });
}

while (Date.now() < deadline) {
  if (await checkConnection()) process.exit(0);
  await new Promise((resolve) => setTimeout(resolve, 1000));
}

console.error('The tunnel could not reach the signaling host. Check the Cloudflare output and restart the host.');
process.exit(1);