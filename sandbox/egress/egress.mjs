// Egress gateway for the sandbox network: the only way out of the internal Docker network.
// HTTPS only (CONNECT), to hosts on an allow list and allowed ports; everything else is refused and logged.
// Node standard library only: it runs from a read-only mount in the sandbox image.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const norm = (host) => String(host).trim().toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');

/** True when host is on the list: exact names, or "*.example.com" for strict subdomains. */
export function matches(host, rules) {
  const h = norm(host);
  return rules.some((rule) => {
    const r = norm(rule);
    return r.startsWith('*.') ? h.endsWith(r.slice(1)) : h === r;
  });
}

/** Splits "host:port" (IPv6 in brackets allowed). */
function target(authority) {
  const m = /^(\[[^\]]+\]|[^:]+):(\d+)$/.exec(authority ?? '');
  return m ? { host: norm(m[1]), port: Number(m[2]) } : null;
}

/** Opens a tunnel to host:port through an HTTP proxy; resolves with the socket after its 200. */
function viaUpstream(upstream, host, port, timeoutMs) {
  const u = new URL(upstream);
  return new Promise((resolve, reject) => {
    const sock = net.connect(Number(u.port || 80), u.hostname);
    const timer = setTimeout(() => fail(new Error('upstream timeout')), timeoutMs);
    let buf = Buffer.alloc(0);
    const fail = (e) => { clearTimeout(timer); sock.destroy(); reject(e); };
    sock.on('error', fail);
    sock.on('connect', () => sock.write(`CONNECT ${host}:${port} HTTP/1.1\r\nHost: ${host}:${port}\r\n\r\n`));
    sock.on('data', function onData(chunk) {
      buf = Buffer.concat([buf, chunk]);
      const end = buf.indexOf('\r\n\r\n');
      if (end < 0) return;
      sock.off('data', onData);
      clearTimeout(timer);
      const status = Number(buf.subarray(0, end).toString('latin1').split(' ')[1]);
      if (status !== 200) return fail(new Error(`upstream answered ${status}`));
      sock.off('error', fail);
      const rest = buf.subarray(end + 4);
      if (rest.length) sock.unshift(rest);
      resolve(sock);
    });
  });
}

function direct(host, port, timeoutMs) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(port, host);
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('connect timeout')); }, timeoutMs);
    sock.once('connect', () => { clearTimeout(timer); sock.off('error', reject); resolve(sock); });
    sock.once('error', (e) => { clearTimeout(timer); reject(e); });
  });
}

/**
 * @param {{allow: string[], ports?: number[], upstream?: string, log?: (e: object) => void, upstreamTimeoutMs?: number}} opts
 */
export function createEgress({ allow, ports = [443], upstream, log = () => {}, upstreamTimeoutMs = 10_000 }) {
  const record = (client, host, port, allowed) =>
    log({ ts: new Date().toISOString(), client: client ?? '', host, port, allowed });

  const server = http.createServer((req, res) => {
    let host = '';
    let port = 80;
    try {
      const u = new URL(req.url);
      host = norm(u.hostname);
      port = Number(u.port) || (u.protocol === 'https:' ? 443 : 80);
    } catch {
      host = norm((req.headers.host ?? '').split(':')[0]);
    }
    record(req.socket.remoteAddress, host, port, false);
    res.writeHead(403, { 'Content-Type': 'text/plain' }).end('egress: only HTTPS (CONNECT) to allowed hosts\n');
  });

  server.on('connect', async (req, client, head) => {
    client.on('error', () => {});
    const t = target(req.url);
    const ok = !!t && ports.includes(t.port) && matches(t.host, allow);
    record(client.remoteAddress, t?.host ?? String(req.url), t?.port ?? 0, ok);
    if (!ok) {
      client.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    let remote;
    try {
      remote = upstream
        ? await viaUpstream(upstream, t.host, t.port, upstreamTimeoutMs)
        : await direct(t.host, t.port, upstreamTimeoutMs);
    } catch {
      client.end('HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    remote.on('error', () => client.destroy());
    client.on('close', () => remote.destroy());
    remote.on('close', () => client.destroy());
    client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head?.length) remote.write(head);
    client.pipe(remote);
    remote.pipe(client);
  });

  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const list = (v) => (v ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const file = process.env.EGRESS_LOG || '/tmp/egress.jsonl';
  const server = createEgress({
    allow: list(process.env.EGRESS_ALLOW),
    ports: list(process.env.EGRESS_PORTS || '443').map(Number),
    upstream: process.env.EGRESS_UPSTREAM || undefined,
    log: (e) => {
      const line = JSON.stringify(e);
      console.log(line);
      fs.appendFile(file, line + '\n', () => {});
    },
  });
  server.listen(Number(process.env.EGRESS_PORT || 3128), '0.0.0.0', () => console.log('egress listening'));
}
