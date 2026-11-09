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

  return { jobsDir, jobDir, writeResult, readResult };
}
