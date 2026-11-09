import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {
  startAgent, testConfig, job, waitDone, waitFor, records, alive, SECRET,
} from './helpers.mjs';
import { loadConfig } from '../src/config.mjs';
import { buildArgs, buildChildEnv, interpret } from '../src/runner.mjs';
import { classifyFailure } from '../src/classify.mjs';

test('AC1 POST /jobs 返回 202 并以 claude -p 执行，不含 --bare', async () => {
  const a = await startAgent();
  try {
    const j = job();
    const r = await a.call('POST', '/jobs', j);
    assert.equal(r.status, 202);
    const done = await waitDone(a, j.jobToken);
    assert.equal(done.status, 'DONE');
    const [rec] = records(a.home);
    assert.ok(rec.args.includes('-p'));
    assert.ok(!rec.args.includes('--bare'));
    assert.deepEqual(rec.args.slice(0, 5), ['-p', '--output-format', 'json', '--allowedTools', 'Bash,Read,Grep,Glob']);
    assert.ok(rec.args.includes('--append-system-prompt'));
    // 问题经 stdin 传入，不出现在参数里
    assert.equal(rec.stdin, j.question);
    assert.ok(!rec.args.some((x) => x.includes('注册')));
  } finally { await a.close(); }
});

test('AC2 同一 jobToken 重复提交不产生第二个任务，已完成的任务不重跑', async () => {
  const a = await startAgent();
  try {
    const j = job({ question: 'q SLOW:200' });
    const [r1, r2] = await Promise.all([a.call('POST', '/jobs', j), a.call('POST', '/jobs', j)]);
    assert.equal(r1.status, 202);
    assert.equal(r2.status, 202);
    await waitDone(a, j.jobToken);
    assert.equal((await a.call('POST', '/jobs', j)).status, 202);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(records(a.home).length, 1);
  } finally { await a.close(); }
});

test('AC2 agent 重启后，已完成任务重复提交也不重跑', async () => {
  const config = testConfig();
  const a = await startAgent({ config });
  const j = job();
  await a.call('POST', '/jobs', j);
  await waitDone(a, j.jobToken);
  await a.close();
  const b = await startAgent({ config });
  try {
    assert.equal((await b.call('POST', '/jobs', j)).status, 202);
    await new Promise((r) => setTimeout(r, 100));
    assert.equal(records(b.home).length, 0);
  } finally { await b.close(); }
});

test('AC3 带 resumeSessionId 时以 --resume 沿用会话', async () => {
  const a = await startAgent();
  try {
    const sid = '11111111-2222-3333-4444-555555555555';
    const j = job({ resumeSessionId: sid });
    await a.call('POST', '/jobs', j);
    const done = await waitDone(a, j.jobToken);
    assert.equal(done.sessionId, sid);
    const [rec] = records(a.home);
    const i = rec.args.indexOf('--resume');
    assert.ok(i > 0);
    assert.equal(rec.args[i + 1], sid);
  } finally { await a.close(); }
});

test('AC3 非法 resumeSessionId 返回 400（防参数注入）', async () => {
  const a = await startAgent();
  try {
    for (const bad of ['--dangerous flag', '--dangerously-skip-permissions', '-p', 'abc', '../x']) {
      const r = await a.call('POST', '/jobs', job({ resumeSessionId: bad }));
      assert.equal(r.status, 400, bad);
    }
    assert.equal(records(a.home).length, 0);
  } finally { await a.close(); }
});

test('claude 正常退出但留下占管道的后台进程：仍按时完成并清理残留', async () => {
  const a = await startAgent({ config: testConfig({ jobTimeoutMs: 20_000, killGraceMs: 300 }) });
  try {
    const j = job({ question: 'q BG_ORPHAN' });
    await a.call('POST', '/jobs', j);
    const t0 = Date.now();
    const d = await waitDone(a, j.jobToken);
    assert.equal(d.status, 'DONE');
    assert.ok(Date.now() - t0 < 5_000);
    const [rec] = records(a.home);
    await waitFor(() => !alive(rec.grandchildPid), { timeout: 3000 });
  } finally { await a.close(); }
});

test('agent 停止中不再接收新任务（429，ops 下轮重试）', async () => {
  const a = await startAgent();
  a.jobs.close();
  try {
    assert.equal((await a.call('POST', '/jobs', job())).status, 429);
  } finally { await a.close(); }
});

test('AC4 缺少或错误密钥时任一接口返回 401，且先于其他校验', async () => {
  const a = await startAgent();
  try {
    for (const secret of [null, '', 'wrong', SECRET + 'x', SECRET.slice(0, -1)]) {
      assert.equal((await a.call('POST', '/jobs', job(), { secret })).status, 401);
      assert.equal((await a.call('POST', '/jobs', '{bad json', { secret })).status, 401);
      assert.equal((await a.call('GET', '/jobs/unknown_token_x', undefined, { secret })).status, 401);
      assert.equal((await a.call('GET', '/jobs/../../etc', undefined, { secret })).status, 401);
      assert.equal((await a.call('GET', '/jobs/abcdefgh/files/x.csv', undefined, { secret })).status, 401);
      assert.equal((await a.call('DELETE', '/conversations/abcdefgh', undefined, { secret })).status, 401);
      assert.equal((await a.call('GET', '/nope', undefined, { secret })).status, 401);
    }
    assert.equal(records(a.home).length, 0);
  } finally { await a.close(); }
});

test('参数校验：token 格式、lang、问题长度、请求体上限', async () => {
  const a = await startAgent();
  try {
    assert.equal((await a.call('POST', '/jobs', '{bad')).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ jobToken: '../../etc' }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ jobToken: 'short' }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ conversationToken: 'a/b/c/d/e/f' }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ lang: 'fr' }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ question: '   ' }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ question: '字'.repeat(2001) }))).status, 400);
    assert.equal((await a.call('POST', '/jobs', job({ question: '字'.repeat(2000) }))).status, 202);
    const big = JSON.stringify({ ...job(), pad: 'x'.repeat(70 * 1024) });
    assert.equal((await a.call('POST', '/jobs', big)).status, 413);
  } finally { await a.close(); }
});

test('AC5 最多 2 个并发；2 个运行且排队 20 个时返回 429', async () => {
  const a = await startAgent();
  try {
    const tokens = [];
    for (let i = 0; i < 22; i++) {
      const j = job({ question: 'q HANG' });
      tokens.push(j.jobToken);
      assert.equal((await a.call('POST', '/jobs', j)).status, 202, `第 ${i + 1} 个应接收`);
    }
    await waitFor(() => records(a.home).length === 2);
    assert.deepEqual(a.jobs.stats(), { running: 2, queued: 20 });
    assert.equal((await a.call('POST', '/jobs', job())).status, 429);
    // 已接收的重复提交仍是 202
    assert.equal((await a.call('POST', '/jobs', job({ jobToken: tokens[21] }))).status, 202);
    await new Promise((r) => setTimeout(r, 150));
    assert.equal(records(a.home).length, 2);
    const st = await a.call('GET', `/jobs/${tokens[0]}`);
    assert.equal(st.json.status, 'RUNNING');
    assert.equal((await a.call('GET', `/jobs/${tokens[5]}`)).json.status, 'QUEUED');
  } finally { await a.close(); }
});

test('AC6 结果落盘 result.json，agent 重启后仍可取回全部字段', async () => {
  const config = testConfig();
  const a = await startAgent({ config });
  const j = job();
  await a.call('POST', '/jobs', j);
  await waitDone(a, j.jobToken);
  await a.close();

  const file = path.join(config.dataDir, 'jobs', j.jobToken, 'result.json');
  const raw = fs.readFileSync(file, 'utf8');
  assert.ok(!raw.includes('本月注册数'), 'result.json 不保存问题原文');
  assert.equal((fs.statSync(file).mode & 0o777), 0o600);

  const b = await startAgent({ config });
  try {
    const r = await b.call('GET', `/jobs/${j.jobToken}`);
    assert.equal(r.status, 200);
    assert.deepEqual(Object.keys(r.json).sort(),
      ['answerMd', 'costUsd', 'errorCode', 'files', 'secs', 'sessionId', 'status', 'turns'].sort());
    assert.equal(r.json.status, 'DONE');
    assert.match(r.json.answerMd, /1,234/);
    assert.equal(r.json.sessionId, '3f6a1d2e-0b1c-4d5e-8f90-123456789abc');
    assert.equal(r.json.turns, 3);
    assert.equal(r.json.costUsd, 0.1234);
    assert.deepEqual(r.json.files, []);
    assert.equal(r.json.errorCode, null);
    assert.equal(typeof r.json.secs, 'number');
  } finally { await b.close(); }
});

test('AC7 未知任务返回 404；运行中任务因重启丢失后返回 404', async () => {
  const config = testConfig();
  const a = await startAgent({ config });
  assert.equal((await a.call('GET', '/jobs/never_seen_token')).status, 404);
  assert.equal((await a.call('GET', '/jobs/bad')).status, 404);
  const j = job({ question: 'q HANG' });
  await a.call('POST', '/jobs', j);
  await waitFor(() => records(a.home).length === 1);
  const [rec] = records(a.home);
  await a.close(); // 模拟 agent 停止：运行中任务被结束且不落盘
  await waitFor(() => !alive(rec.pid));
  await waitFor(() => !alive(rec.grandchildPid));

  const b = await startAgent({ config });
  try {
    assert.equal((await b.call('GET', `/jobs/${j.jobToken}`)).status, 404);
  } finally { await b.close(); }
});

test('AC8 超时结束 claude 及其子进程，释放名额，errorCode=AGENT_TIMEOUT', async () => {
  const a = await startAgent({ config: testConfig({ jobTimeoutMs: 300, killGraceMs: 200 }) });
  try {
    const j1 = job({ question: 'q HANG' });
    const j2 = job({ question: 'q HANG_STUBBORN' });
    const j3 = job();
    for (const j of [j1, j2, j3]) await a.call('POST', '/jobs', j);
    const d1 = await waitDone(a, j1.jobToken);
    const d2 = await waitDone(a, j2.jobToken);
    for (const d of [d1, d2]) {
      assert.equal(d.status, 'FAILED');
      assert.equal(d.errorCode, 'AGENT_TIMEOUT');
    }
    // 名额释放后第三个任务得以执行
    assert.equal((await waitDone(a, j3.jobToken)).status, 'DONE');
    for (const rec of records(a.home).filter((r) => r.grandchildPid)) {
      await waitFor(() => !alive(rec.pid), { timeout: 3000 });
      await waitFor(() => !alive(rec.grandchildPid), { timeout: 3000 });
    }
  } finally { await a.close(); }
});

test('AC9 日志不含问题、答案和 claude 的 stdout/stderr', async () => {
  const a = await startAgent();
  try {
    const qs = ['机密问题甲 SUCCESS', '机密问题乙 CRASH', '机密问题丙 RATE_LIMIT'];
    const tokens = [];
    for (const q of qs) {
      const j = job({ question: q });
      tokens.push(j.jobToken);
      await a.call('POST', '/jobs', j);
    }
    for (const t of tokens) await waitDone(a, t);
    const all = a.logs.join('\n');
    assert.ok(all.length > 0);
    for (const s of ['机密问题', '1,234', 'SECRET_QUESTION_TEXT', 'session limit', '结论']) {
      assert.ok(!all.includes(s), `日志不应包含 ${s}`);
    }
    assert.ok(all.includes(tokens[0]));
  } finally { await a.close(); }
});

test('AC10 额度用尽、授权失效、其他失败的 errorCode', async () => {
  const a = await startAgent();
  try {
    const cases = [['RATE_LIMIT', 'AGENT_RATE_LIMITED'], ['AUTH', 'AGENT_AUTH_FAILED'], ['CRASH', 'AGENT_ERROR']];
    for (const [m, code] of cases) {
      const j = job({ question: `q ${m}` });
      await a.call('POST', '/jobs', j);
      const d = await waitDone(a, j.jobToken);
      assert.equal(d.status, 'FAILED');
      assert.equal(d.errorCode, code, m);
      assert.equal(d.answerMd, null);
    }
  } finally { await a.close(); }
});

test('AC10 claude 可执行文件不存在时归为 AGENT_ERROR', async () => {
  const a = await startAgent({ config: testConfig({ claudeBin: '/nonexistent/claude' }) });
  try {
    const j = job();
    await a.call('POST', '/jobs', j);
    assert.equal((await waitDone(a, j.jobToken)).errorCode, 'AGENT_ERROR');
  } finally { await a.close(); }
});

test('classifyFailure：依据官方文档文案片段分类，未识别一律 AGENT_ERROR', () => {
  assert.equal(classifyFailure({ parsed: { is_error: true, result: 'API Error: 401 Invalid authentication credentials' }, stderr: '' }), 'AGENT_AUTH_FAILED');
  assert.equal(classifyFailure({ parsed: null, stderr: 'Invalid API key · Fix external API key' }), 'AGENT_AUTH_FAILED');
  assert.equal(classifyFailure({ parsed: { is_error: true, result: 'Credit balance is too low' }, stderr: '' }), 'AGENT_RATE_LIMITED');
  assert.equal(classifyFailure({ parsed: { is_error: true, result: 'Server is temporarily limiting requests' }, stderr: '' }), 'AGENT_RATE_LIMITED');
  assert.equal(classifyFailure({ parsed: { is_error: true, result: '查询失败：表不存在' }, stderr: '' }), 'AGENT_ERROR');
  assert.equal(classifyFailure({ parsed: null, stderr: '', spawnError: new Error('ENOENT') }), 'AGENT_ERROR');
});

test('interpret：is_error 为真或退出码非 0 都不算成功', () => {
  const ok = JSON.stringify({ result: 'a', session_id: 's', is_error: false });
  assert.equal(interpret({ stdout: ok, stderr: '', exitCode: 0 }).ok, true);
  assert.equal(interpret({ stdout: ok, stderr: '', exitCode: 1 }).ok, false);
  assert.equal(interpret({ stdout: JSON.stringify({ result: 'a', is_error: true }), stderr: '', exitCode: 0 }).ok, false);
  assert.equal(interpret({ stdout: 'not json', stderr: '', exitCode: 0 }).ok, false);
});

test('buildChildEnv 只传白名单，不展开父进程 env', () => {
  const env = buildChildEnv({ PATH: '/bin', HOME: '/h', CLAUDE_CODE_OAUTH_TOKEN: 't', AGENT_SECRET: 's', LARK_APP_SECRET: 'x', DB_PASSWORD: 'p' });
  assert.ok(!('AGENT_SECRET' in env));
  assert.ok(!('LARK_APP_SECRET' in env));
  assert.ok(!('DB_PASSWORD' in env));
  assert.equal(env.CLAUDE_CODE_OAUTH_TOKEN, 't');
  assert.ok(!buildArgs({ lang: 'zh' }).includes('--bare'));
  assert.ok(!buildArgs({ lang: 'zh' }).includes('--resume'));
});

test('子进程实际拿不到白名单外变量', async () => {
  const a = await startAgent({ parentEnv: undefined });
  const env = { ...a.env, AGENT_SECRET: SECRET, LARK_APP_SECRET: 'lark' };
  await a.close();
  const b = await startAgent({ parentEnv: env });
  try {
    for (const name of ['AGENT_SECRET', 'LARK_APP_SECRET']) {
      const j = job({ question: `q ECHO_ENV:${name}` });
      await b.call('POST', '/jobs', j);
      assert.equal((await waitDone(b, j.jobToken)).answerMd, '<unset>');
    }
  } finally { await b.close(); }
});

test('loadConfig：缺少 AGENT_SECRET 拒绝启动，默认超时 10 分钟', () => {
  assert.throws(() => loadConfig({}), /AGENT_SECRET/);
  assert.throws(() => loadConfig({ AGENT_SECRET: 'short' }), /AGENT_SECRET/);
  const c = loadConfig({ AGENT_SECRET: SECRET });
  assert.equal(c.jobTimeoutMs, 600_000);
  assert.equal(c.concurrency, 2);
  assert.equal(c.maxQueue, 20);
  assert.equal(c.claudeBin, 'claude');
  assert.throws(() => loadConfig({ AGENT_SECRET: SECRET, JOB_TIMEOUT_MS: '0' }), /JOB_TIMEOUT_MS/);
  assert.throws(() => loadConfig({ AGENT_SECRET: SECRET, PORT: '70000' }), /PORT/);
  assert.throws(() => loadConfig({ AGENT_SECRET: SECRET, DATA_DIR: '/x/data', WORK_DIR: '/x/data/w' }), /WORK_DIR/);
});
