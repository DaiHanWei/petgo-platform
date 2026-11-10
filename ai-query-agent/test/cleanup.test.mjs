// Story 2.2：DELETE /conversations/{token} 删除导出目录、任务结果与 Claude 会话记录，重复调用保持成功
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { startAgent, job, waitDone } from './helpers.mjs';

test('删除对话：导出目录、任务结果、会话记录全部清除，其他对话不受影响，幂等', async () => {
  const a = await startAgent();
  try {
    const conv = 'conv_cleanup_target_01';
    const other = 'conv_cleanup_other_002';
    const j1 = job({ conversationToken: conv, question: 'q WRITE_FILE' });
    const j2 = job({ conversationToken: other, question: 'q WRITE_FILE' });
    for (const j of [j1, j2]) await a.call('POST', '/jobs', j);
    const d1 = await waitDone(a, j1.jobToken);
    const d2 = await waitDone(a, j2.jobToken);

    // 模拟 Claude 会话记录（真实位置：$HOME/.claude/projects/<目录名>/<sessionId>.jsonl）
    const projects = path.join(a.env.HOME, '.claude', 'projects', '-app-work');
    fs.mkdirSync(projects, { recursive: true });
    const f1 = path.join(projects, `${d1.sessionId}.jsonl`);
    fs.writeFileSync(f1, '{"q":"机密"}\n');
    fs.mkdirSync(path.join(projects, d1.sessionId));
    const otherSession = '99999999-8888-7777-6666-555555555555';
    const f2 = path.join(projects, `${otherSession}.jsonl`);
    fs.writeFileSync(f2, '{}\n');

    const r = await a.call('DELETE', `/conversations/${conv}`);
    assert.equal(r.status, 200);
    assert.ok(!fs.existsSync(path.join(a.config.exportDir, conv)));
    assert.equal((await a.call('GET', `/jobs/${j1.jobToken}`)).status, 404);
    assert.ok(!fs.existsSync(f1));
    assert.ok(!fs.existsSync(path.join(projects, d1.sessionId)));
    // 其他对话 / 其他会话不受影响
    assert.ok(fs.existsSync(f2));
    assert.ok(fs.existsSync(path.join(a.config.exportDir, other, j2.jobToken, 'report.csv')));
    assert.equal((await a.call('GET', `/jobs/${j2.jobToken}`)).status, 200);
    assert.ok(d2.sessionId);

    // 重复调用、从未存在过的对话：都成功
    assert.equal((await a.call('DELETE', `/conversations/${conv}`)).status, 200);
    assert.equal((await a.call('DELETE', '/conversations/conv_never_existed_x')).status, 200);
    // 非法 token、缺密钥
    assert.equal((await a.call('DELETE', '/conversations/..%2F..')).status, 400);
    assert.equal((await a.call('DELETE', `/conversations/${other}`, undefined, { secret: null })).status, 401);
    assert.ok(a.logs.join('\n').includes('conversation.deleted'));
    assert.ok(!fs.existsSync(path.join(a.config.dataDir, 'conversations', `${conv}.json`)), '索引一并删除');
    assert.ok(!a.logs.join('\n').includes('机密'));
  } finally { await a.close(); }
});

test('超时 / 中断的任务（结果里没有会话 id）的会话记录也会被清除；有运行中任务时拒绝删除', async () => {
  const { testConfig } = await import('./helpers.mjs');
  const a = await startAgent({ config: testConfig({ jobTimeoutMs: 1000, killGraceMs: 100 }) });
  try {
    const conv = 'conv_timeout_target_01';
    const j = job({ conversationToken: conv, question: 'q HANG' });
    await a.call('POST', '/jobs', j);
    // 运行中：拒绝
    await new Promise((r) => setTimeout(r, 200));
    assert.equal((await a.call('DELETE', `/conversations/${conv}`)).status, 409);
    const d = await waitDone(a, j.jobToken);
    assert.equal(d.errorCode, 'AGENT_TIMEOUT');
    const { records } = await import('./helpers.mjs');
    const rec = records(a.home).find((x) => x.args.includes('--session-id'));
    const sid = rec.args[rec.args.indexOf('--session-id') + 1];
    const projects = path.join(a.env.HOME, '.claude', 'projects', '-app-work');
    fs.mkdirSync(projects, { recursive: true });
    fs.writeFileSync(path.join(projects, `${sid}.jsonl`), '{"q":"超时任务的问题"}\n');
    assert.equal((await a.call('DELETE', `/conversations/${conv}`)).status, 200);
    assert.ok(!fs.existsSync(path.join(projects, `${sid}.jsonl`)));
  } finally { await a.close(); }
});
