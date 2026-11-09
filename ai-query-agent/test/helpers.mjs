import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createStore } from '../src/store.mjs';
import { createJobManager } from '../src/jobs.mjs';
import { createServer } from '../src/server.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
export const FAKE_CLAUDE = path.join(here, 'fake-claude.mjs');
export const SECRET = 'test-secret-0123456789abcdef';

fs.chmodSync(FAKE_CLAUDE, 0o755);

export function tmpDir(prefix = 'aiq-') {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

export function testConfig(over = {}) {
  return {
    secret: SECRET,
    port: 0,
    host: '127.0.0.1',
    dataDir: tmpDir('aiq-data-'),
    workDir: tmpDir('aiq-work-'),
    claudeBin: FAKE_CLAUDE,
    jobTimeoutMs: 60_000,
    killGraceMs: 300,
    concurrency: 2,
    maxQueue: 20,
    ...over,
  };
}

/** 启动一个 agent 实例，返回 { base, call, logs, close, config, jobs }。 */
export async function startAgent({ config = testConfig(), parentEnv, routes, extra } = {}) {
  const home = tmpDir('aiq-home-');
  const env = parentEnv ?? { PATH: process.env.PATH, HOME: home, CLAUDE_CODE_OAUTH_TOKEN: 'tok-test' };
  const logs = [];
  const log = (event, fields = {}) => logs.push(JSON.stringify({ event, ...fields }));
  const store = createStore(config.dataDir);
  const jobs = createJobManager({ config, store, log, parentEnv: env });
  const server = createServer({ config, jobs, log, routes: routes?.({ config, store, jobs, log, env }), ...extra });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  async function call(method, p, body, { secret = SECRET, headers = {} } = {}) {
    const h = { ...headers };
    if (secret !== null) h['x-internal-secret'] = secret;
    if (body !== undefined) h['content-type'] = 'application/json';
    const res = await fetch(base + p, {
      method,
      headers: h,
      body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
    });
    const text = await res.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* 非 JSON */ }
    return { status: res.status, json, text, headers: res.headers };
  }
  async function close({ drain = true } = {}) {
    jobs.close();
    if (drain) await jobs.drain();
    await new Promise((r) => server.close(r));
  }
  return { base, call, logs, close, config, jobs, store, home, env };
}

export function job(over = {}) {
  const n = Math.random().toString(36).slice(2, 12);
  return {
    jobToken: `job_${n}`,
    conversationToken: `conv_${n}`,
    question: '本月注册数 SUCCESS',
    lang: 'zh',
    ...over,
  };
}

export async function waitFor(fn, { timeout = 10_000, interval = 25 } = {}) {
  const end = Date.now() + timeout;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) throw new Error('waitFor 超时');
    await new Promise((r) => setTimeout(r, interval));
  }
}

export async function waitDone(agent, token) {
  return waitFor(async () => {
    const r = await agent.call('GET', `/jobs/${token}`);
    return r.status === 200 && (r.json.status === 'DONE' || r.json.status === 'FAILED') ? r.json : null;
  });
}

export function records(home) {
  const dir = path.join(home, 'fake-claude-records');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).map((f) => JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')));
}

export function alive(pid) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}
