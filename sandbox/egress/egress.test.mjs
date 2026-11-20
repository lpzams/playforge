// Run: node --test sandbox/egress/
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { once } from 'node:events';
import { matches, createEgress } from './egress.mjs';

const closers = [];
after(() => closers.forEach((c) => c()));

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  closers.push(() => server.close());
  return server.address().port;
}

/** A TCP server echoing whatever it receives; counts connections. */
async function echoServer() {
  const s = net.createServer((sock) => { s.connections++; sock.on('error', () => {}); sock.pipe(sock); });
  s.connections = 0;
  const sockets = new Set();
  s.on('connection', (c) => { sockets.add(c); c.on('close', () => sockets.delete(c)); });
  closers.push(() => sockets.forEach((c) => c.destroy()));
  return { server: s, port: await listen(s) };
}

async function gateway(opts) {
  const log = [];
  const server = createEgress({ log: (e) => log.push(e), ...opts });
  const sockets = new Set();
  server.on('connection', (c) => { sockets.add(c); c.on('close', () => sockets.delete(c)); });
  closers.push(() => sockets.forEach((c) => c.destroy()));
  return { server, log, port: await listen(server) };
}

/** Sends raw text to the gateway; resolves with the status code and the socket (still open). */
async function rawRequest(port, text) {
  const sock = net.connect(port, '127.0.0.1');
  closers.push(() => sock.destroy());
  await once(sock, 'connect');
  sock.write(text);
  let buf = '';
  while (!buf.includes('\r\n\r\n')) {
    const [chunk] = await once(sock, 'data');
    buf += chunk.toString('latin1');
  }
  return { status: Number(buf.split(' ')[1]), sock };
}

const connect = (port, target) => rawRequest(port, `CONNECT ${target} HTTP/1.1\r\nHost: ${target}\r\n\r\n`);

async function echoes(sock) {
  sock.write('ping');
  const [chunk] = await once(sock, 'data');
  return chunk.toString();
}

test('matches: exact hosts, case and trailing dot ignored', () => {
  assert.equal(matches('api.anthropic.com', ['api.anthropic.com']), true);
  assert.equal(matches('API.Anthropic.com.', ['api.anthropic.com']), true);
  assert.equal(matches('evil.com', ['api.anthropic.com']), false);
  assert.equal(matches('api.anthropic.com.evil.com', ['api.anthropic.com']), false);
  assert.equal(matches('evil.com.api.anthropic.com', ['api.anthropic.com']), false);
});

test('matches: *.domain means strict subdomains', () => {
  assert.equal(matches('x.npmmirror.com', ['*.npmmirror.com']), true);
  assert.equal(matches('a.b.npmmirror.com', ['*.npmmirror.com']), true);
  assert.equal(matches('npmmirror.com', ['*.npmmirror.com']), false);
  assert.equal(matches('evilnpmmirror.com', ['*.npmmirror.com']), false);
});

test('CONNECT to an allowed host tunnels bytes and is logged', async () => {
  const echo = await echoServer();
  const g = await gateway({ allow: ['127.0.0.1'], ports: [echo.port] });
  const { status, sock } = await connect(g.port, `127.0.0.1:${echo.port}`);
  assert.equal(status, 200);
  assert.equal(await echoes(sock), 'ping');
  assert.equal(g.log.length, 1);
  assert.equal(g.log[0].host, '127.0.0.1');
  assert.equal(g.log[0].port, echo.port);
  assert.equal(g.log[0].allowed, true);
  assert.equal(typeof g.log[0].client, 'string');
  assert.ok(!Number.isNaN(Date.parse(g.log[0].ts)));
});

test('CONNECT to a host not on the list is refused and logged', async () => {
  const echo = await echoServer();
  const g = await gateway({ allow: ['example.org'], ports: [echo.port] });
  const { status } = await connect(g.port, `127.0.0.1:${echo.port}`);
  assert.equal(status, 403);
  assert.equal(echo.server.connections, 0);
  assert.deepEqual(g.log.map((e) => [e.host, e.allowed]), [['127.0.0.1', false]]);
});

test('CONNECT to an allowed host on another port is refused', async () => {
  const echo = await echoServer();
  const g = await gateway({ allow: ['127.0.0.1'] }); // default ports: 443 only
  const { status } = await connect(g.port, `127.0.0.1:${echo.port}`);
  assert.equal(status, 403);
  assert.equal(echo.server.connections, 0);
  assert.equal(g.log[0].allowed, false);
});

test('plain HTTP requests are refused and logged', async () => {
  const g = await gateway({ allow: ['example.org'] });
  const { status } = await rawRequest(g.port, 'GET http://example.org/x HTTP/1.1\r\nHost: example.org\r\n\r\n');
  assert.equal(status, 403);
  assert.deepEqual(g.log.map((e) => [e.host, e.port, e.allowed]), [['example.org', 80, false]]);
});

test('an upstream proxy carries allowed tunnels', async () => {
  const echo = await echoServer();
  const b = await gateway({ allow: ['127.0.0.1'], ports: [echo.port] });
  const a = await gateway({ allow: ['127.0.0.1'], ports: [echo.port], upstream: `http://127.0.0.1:${b.port}` });
  const { status, sock } = await connect(a.port, `127.0.0.1:${echo.port}`);
  assert.equal(status, 200);
  assert.equal(await echoes(sock), 'ping');
  assert.equal(b.log.length, 1);
  assert.equal(b.log[0].allowed, true);
});

test('an upstream that never answers gives 502 within the timeout', async () => {
  const silent = net.createServer((sock) => sock.on('error', () => {}));
  const silentPort = await listen(silent);
  const sockets = new Set();
  silent.on('connection', (c) => sockets.add(c));
  closers.push(() => sockets.forEach((c) => c.destroy()));
  const g = await gateway({ allow: ['127.0.0.1'], ports: [9], upstream: `http://127.0.0.1:${silentPort}`, upstreamTimeoutMs: 300 });
  const t0 = Date.now();
  const { status } = await connect(g.port, '127.0.0.1:9');
  assert.equal(status, 502);
  assert.ok(Date.now() - t0 < 2000);
});
