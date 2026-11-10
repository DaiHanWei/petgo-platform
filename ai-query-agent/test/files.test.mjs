// Story 2.1：导出目录按对话 / 任务隔离、清单只含本任务顶层普通文件、下载只允许清单内文件
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { startAgent, testConfig, job, waitDone } from './helpers.mjs';
import { isSafeFileName } from '../src/files.mjs';

async function download(a, jobToken, name, opts) {
  return a.call('GET', `/jobs/${jobToken}/files/${encodeURIComponent(name)}`, undefined, opts);
}

test('任务导出目录按对话隔离，清单只含本任务新生成的普通文件', async () => {
  const a = await startAgent();
  try {
    const j1 = job({ question: 'q WRITE_FILE' });
    await a.call('POST', '/jobs', j1);
    const d1 = await waitDone(a, j1.jobToken);
    assert.deepEqual(d1.files, [{ name: 'report.csv', size: 18 }]);
    assert.ok(!('conversationToken' in d1), '内部字段不对外');
    const dir = path.join(a.config.exportDir, j1.conversationToken, j1.jobToken);
    assert.ok(fs.existsSync(path.join(dir, 'report.csv')));
    // 对话目录不可列举；任务结束后收回写权限
    assert.equal(fs.statSync(path.join(a.config.exportDir, j1.conversationToken)).mode & 0o777, 0o711);
    assert.equal(fs.statSync(dir).mode & 0o777, 0o700);

    // 同一对话的追问：历史文件不会被重发
    const j2 = job({ conversationToken: j1.conversationToken });
    await a.call('POST', '/jobs', j2);
    assert.deepEqual((await waitDone(a, j2.jobToken)).files, []);
  } finally { await a.close(); }
});

test('下载：清单内文件返回内容；符号链接、隐藏文件、清单外、他人任务、路径穿越都拒绝', async () => {
  const a = await startAgent();
  try {
    const j = job({ question: 'q WRITE_FILE' });
    await a.call('POST', '/jobs', j);
    await waitDone(a, j.jobToken);
    const ok = await download(a, j.jobToken, 'report.csv');
    assert.equal(ok.status, 200);
    assert.equal(ok.text, 'week,count\n1,1612\n');
    assert.equal(ok.headers.get('content-type'), 'application/octet-stream');
    assert.equal(ok.headers.get('x-content-type-options'), 'nosniff');

    assert.equal((await download(a, j.jobToken, 'leak.csv')).status, 404, '符号链接不在清单里');
    assert.equal((await download(a, j.jobToken, '.hidden')).status, 400);
    assert.equal((await download(a, j.jobToken, 'nope.csv')).status, 404);
    assert.equal((await a.call('GET', `/jobs/${j.jobToken}/files/..%2F..%2Fresult.json`)).status, 400);
    assert.equal((await a.call('GET', `/jobs/${j.jobToken}/files/%2Fetc%2Fpasswd`)).status, 400);
    assert.equal((await a.call('GET', `/jobs/${j.jobToken}/files/a%00b`)).status, 400);
    assert.equal((await download(a, 'other_job_token_x', 'report.csv')).status, 404);
    assert.equal((await download(a, j.jobToken, 'report.csv', { secret: 'wrong' })).status, 401);

    // 清单内文件事后被换成符号链接：打开时 O_NOFOLLOW 拒绝
    const dir = path.join(a.config.exportDir, j.conversationToken, j.jobToken);
    fs.rmSync(path.join(dir, 'report.csv'));
    fs.symlinkSync('/etc/passwd', path.join(dir, 'report.csv'));
    const swapped = await download(a, j.jobToken, 'report.csv');
    assert.equal(swapped.status, 404);
    assert.ok(!swapped.text.includes('root:'));
  } finally { await a.close(); }
});

test('agent 重启后已完成任务的文件仍可下载', async () => {
  const config = testConfig();
  const a = await startAgent({ config });
  const j = job({ question: 'q WRITE_FILE' });
  await a.call('POST', '/jobs', j);
  await waitDone(a, j.jobToken);
  await a.close();
  const b = await startAgent({ config });
  try {
    assert.equal((await download(b, j.jobToken, 'report.csv')).status, 200);
  } finally { await b.close(); }
});

test('文件名规则', () => {
  for (const ok of ['a.csv', '报表 11月.xlsx', 'KTP高清解锁明细-11月.xlsx']) assert.ok(isSafeFileName(ok), ok);
  for (const bad of ['', '.env', '..', '../x', 'a/b', 'a\\b', 'a\0b', 'x'.repeat(201), '增长率%.csv', 'a;b.csv', 'a?.csv', 'a#.csv']) {
    assert.ok(!isSafeFileName(bad), bad);
  }
});
