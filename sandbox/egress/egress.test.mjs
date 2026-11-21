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

test('a CONNECT target that is not a host name is logged as invalid, never verbatim', async () => {
  const g = await gateway({ allow: ['example.org'] });
  for (const target of ['SECRET-Token_123', 'sk-ant/abc:443', 'a"b.com:443', `${'x'.repeat(300)}.com:443`]) {
    const { status } = await connect(g.port, target);
    assert.ok(status === 403 || status === 400, `refused: ${status}`); // Node's parser answers 400 to some
  }
  assert.ok(g.log.length >= 3);
  assert.ok(g.log.every((e) => e.host === '(invalid)' && !e.allowed));
  assert.ok(!JSON.stringify(g.log).includes('SECRET') && !JSON.stringify(g.log).includes('xxxx'));
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

/** Resolves true if the event fires within ms, false otherwise. */
const within = (emitter, event, ms) => new Promise((resolve) => {
  const timer = setTimeout(() => resolve(false), ms);
  emitter.once(event, () => { clearTimeout(timer); resolve(true); });
});

test('a client that leaves while the gateway is still connecting does not leak the upstream socket', async () => {
  // An upstream proxy that answers 200 only after 300 ms; hands us its side of the socket.
  const slow = net.createServer((sock) => {
    sock.on('error', () => {});
    sock.once('data', () => setTimeout(() => sock.writable && sock.write('HTTP/1.1 200 OK\r\n\r\n'), 300));
  });
  const slowPort = await listen(slow);
  const gotConn = once(slow, 'connection');
  const g = await gateway({ allow: ['127.0.0.1'], ports: [9], upstream: `http://127.0.0.1:${slowPort}` });
  const client = net.connect(g.port, '127.0.0.1');
  client.on('error', () => {});
  await once(client, 'connect');
  client.write('CONNECT 127.0.0.1:9 HTTP/1.1\r\nHost: 127.0.0.1:9\r\n\r\n');
  const [upstreamSide] = await gotConn;
  closers.push(() => upstreamSide.destroy());
  client.resetAndDestroy(); // RST, not FIN: a clean end would be propagated by pipe() and hide the leak
  assert.equal(await within(upstreamSide, 'close', 1500), true, 'upstream socket closed after the client left');
});

test('an error on the remote side after the tunnel is up closes the client without crashing', async () => {
  let remoteSide;
  const rst = net.createServer((sock) => { sock.on('error', () => {}); remoteSide = sock; });
  const rstPort = await listen(rst);
  const g = await gateway({ allow: ['127.0.0.1'], ports: [rstPort] });
  const { status, sock } = await connect(g.port, `127.0.0.1:${rstPort}`);
  assert.equal(status, 200);
  sock.on('error', () => {});
  remoteSide.resetAndDestroy(); // ECONNRESET on the gateway's remote socket
  assert.equal(await within(sock, 'close', 1500), true);
  // The gateway is still serving.
  const again = await connect(g.port, 'example.org:443');
  assert.equal(again.status, 403);
});

test('connections beyond maxConnections are refused', async () => {
  const echo = await echoServer();
  const g = await gateway({ allow: ['127.0.0.1'], ports: [echo.port], maxConnections: 2 });
  assert.equal(g.server.maxConnections, 2);
  const t1 = await connect(g.port, `127.0.0.1:${echo.port}`);
  const t2 = await connect(g.port, `127.0.0.1:${echo.port}`);
  assert.equal(t1.status, 200);
  assert.equal(t2.status, 200);
  const third = net.connect(g.port, '127.0.0.1');
  closers.push(() => third.destroy());
  third.on('error', () => {});
  let got = '';
  third.on('data', (c) => { got += c.toString('latin1'); });
  third.on('connect', () => third.write(`CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\nHost: x\r\n\r\n`));
  assert.equal(await within(third, 'close', 1500), true, 'third connection closed');
  assert.ok(!got.includes(' 200 '), 'third connection never got a tunnel');
  // The first tunnels still work.
  assert.equal(await echoes(t1.sock), 'ping');
});

test('maxConnections defaults to 256', async () => {
  const g = await gateway({ allow: [] });
  assert.equal(g.server.maxConnections, 256);
});
