import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import test from 'node:test';
import { WebSocket } from 'ws';

test('creates a code, pairs peers, and relays offer and answer', async (context) => {
  const reservation = createServer();
  await new Promise((resolve) => reservation.listen(0, '127.0.0.1', resolve));
  const { port } = reservation.address();
  await new Promise((resolve) => reservation.close(resolve));
  const process = spawn(processExec(), ['index.mjs'], {
    cwd: new URL('.', import.meta.url),
    env: { ...globalThis.process.env, PORT: String(port), ALLOWED_ORIGINS: 'https://example.test' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  context.after(() => process.kill());
  await once(process.stdout, 'data');

  const sender = new WebSocket(`ws://127.0.0.1:${port}`, { origin: 'https://example.test' });
  const viewer = new WebSocket(`ws://127.0.0.1:${port}`);
  context.after(() => { sender.terminate(); viewer.terminate(); });
  await Promise.all([once(sender, 'open'), once(viewer, 'open')]);

  const rejected = new WebSocket(`ws://127.0.0.1:${port}`, { origin: 'https://untrusted.test' });
  context.after(() => rejected.terminate());
  const [closeCode] = await once(rejected, 'close');
  assert.equal(closeCode, 1008);

  viewer.send(JSON.stringify({ type: 'join', code: 'aaa-aaa-aaa' }));
  assert.equal(JSON.parse((await once(viewer, 'message'))[0].toString()).type, 'error');

  sender.send(JSON.stringify({ type: 'create' }));
  const [createdRaw] = await once(sender, 'message');
  const created = JSON.parse(createdRaw.toString());
  assert.match(created.code, /^[a-hjkmnp-z2-9]{3}(-[a-hjkmnp-z2-9]{3}){2}$/);

  const viewerJoined = once(viewer, 'message');
  const senderNotified = once(sender, 'message');
  viewer.send(JSON.stringify({ type: 'join', code: created.code }));
  assert.equal(JSON.parse((await viewerJoined)[0].toString()).type, 'joined');
  assert.equal(JSON.parse((await senderNotified)[0].toString()).type, 'viewer-joined');

  const offer = once(viewer, 'message');
  sender.send(JSON.stringify({ type: 'offer', sdp: 'test-offer' }));
  assert.deepEqual(JSON.parse((await offer)[0].toString()), { type: 'offer', sdp: 'test-offer' });
  const answer = once(sender, 'message');
  viewer.send(JSON.stringify({ type: 'answer', sdp: 'test-answer' }));
  assert.deepEqual(JSON.parse((await answer)[0].toString()), { type: 'answer', sdp: 'test-answer' });
});

function processExec() {
  return globalThis.process.execPath;
}