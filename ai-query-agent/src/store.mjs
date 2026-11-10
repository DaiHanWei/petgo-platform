// 结果存储：jobs/<token>/result.json，先写临时文件再 rename。
// result.json 不保存问题原文。

import fs from 'node:fs/promises';
import path from 'node:path';
import { isValidToken } from './config.mjs';

export function createStore(dataDir) {
  const jobsDir = path.join(dataDir, 'jobs');

  function jobDir(token) {
    if (!isValidToken(token)) throw new Error('非法 token');
    return path.join(jobsDir, token);
  }

  async function writeResult(token, result) {
    const dir = jobDir(token);
    await fs.mkdir(dir, { recursive: true });
    const tmp = path.join(dir, `result.json.${process.pid}.${Date.now()}.tmp`);
    await fs.writeFile(tmp, JSON.stringify(result), { mode: 0o600 });
    await fs.rename(tmp, path.join(dir, 'result.json'));
  }

  async function readResult(token) {
    if (!isValidToken(token)) return null;
    try {
      const raw = await fs.readFile(path.join(jobDir(token), 'result.json'), 'utf8');
      return JSON.parse(raw);
    } catch (e) {
      if (e.code === 'ENOENT') return null;
      throw e;
    }
  }

  // ---- 对话索引（Story 2.2）：conversations/<token>.json = { jobs: [...], sessions: [...] } ----
  // 任务开始时即登记任务 token 与（预分配的）Claude 会话 id，清理时按索引删除，
  // 超时 / 中断 / 结果未落盘的任务的会话记录也不会漏。
  const convDir = path.join(dataDir, 'conversations');
  const locks = new Map();

  function indexPath(conversationToken) {
    if (!isValidToken(conversationToken)) throw new Error('非法 token');
    return path.join(convDir, `${conversationToken}.json`);
  }

  async function readIndex(conversationToken) {
    try {
      const raw = await fs.readFile(indexPath(conversationToken), 'utf8');
      const v = JSON.parse(raw);
      return { jobs: Array.isArray(v.jobs) ? v.jobs : [], sessions: Array.isArray(v.sessions) ? v.sessions : [] };
    } catch (e) {
      if (e.code === 'ENOENT') return { jobs: [], sessions: [] };
      throw e;
    }
  }

  /** 追加登记（同一对话串行写，先写临时文件再 rename）。 */
  function addToIndex(conversationToken, { jobToken, sessionId }) {
    const prev = locks.get(conversationToken) || Promise.resolve();
    const next = prev.catch(() => {}).then(async () => {
      const idx = await readIndex(conversationToken);
      if (jobToken && !idx.jobs.includes(jobToken)) idx.jobs.push(jobToken);
      if (sessionId && !idx.sessions.includes(sessionId)) idx.sessions.push(sessionId);
      await fs.mkdir(convDir, { recursive: true, mode: 0o700 });
      const file = indexPath(conversationToken);
      const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
      await fs.writeFile(tmp, JSON.stringify(idx), { mode: 0o600 });
      await fs.rename(tmp, file);
    });
    locks.set(conversationToken, next);
    return next.finally(() => { if (locks.get(conversationToken) === next) locks.delete(conversationToken); });
  }

  async function removeIndex(conversationToken) {
    await fs.rm(indexPath(conversationToken), { force: true });
  }

  return { jobsDir, jobDir, writeResult, readResult, readIndex, addToIndex, removeIndex };
}
