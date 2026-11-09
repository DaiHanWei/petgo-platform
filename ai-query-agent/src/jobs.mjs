// 任务队列：内存队列，并发固定 2，排队上限 20。
// - 同一 jobToken 在内存或磁盘已存在时直接视为已接收（幂等）
// - 运行中的任务不持久化：agent 重启后丢失，GET 返回 404，由 ops 转 FAILED（spec §4.2）

import { runClaude } from './runner.mjs';
import { prepareJobDir, listJobFiles, sealJobDir } from './files.mjs';

export function createJobManager({ config, store, log, run = runClaude, parentEnv = process.env }) {
  /** @type {Map<string, {status:string, job:object, startedAt?:number}>} */
  const active = new Map();
  const queue = [];
  let running = 0;
  let closed = false;
  const runningPromises = new Set();
  const abort = new AbortController();

  // 落盘失败的结果留在内存，仍可取回，也保证幂等不重跑
  const unpersisted = new Map();

  async function submit(job) {
    // 停止中不再接收：返回排队已满，ops 保持 PENDING 下轮重试
    if (closed) return 'full';
    if (active.has(job.jobToken) || unpersisted.has(job.jobToken)) return 'duplicate';
    if (await store.readResult(job.jobToken)) return 'duplicate';
    // 读盘期间可能有同 token 并发提交
    if (active.has(job.jobToken)) return 'duplicate';
    if (queue.length >= config.maxQueue) return 'full';
    active.set(job.jobToken, { status: 'QUEUED', job });
    queue.push(job.jobToken);
    log('job.queued', { jobToken: job.jobToken, queued: queue.length, running });
    pump();
    return 'accepted';
  }

  function pump() {
    while (!closed && running < config.concurrency && queue.length > 0) {
      const token = queue.shift();
      const entry = active.get(token);
      if (!entry) continue;
      running++;
      entry.status = 'RUNNING';
      entry.startedAt = Date.now();
      log('job.started', { jobToken: token, running });
      const p = execute(token, entry).finally(() => {
        running--;
        runningPromises.delete(p);
        pump();
      });
      runningPromises.add(p);
    }
  }

  async function execute(token, entry) {
    const started = entry.startedAt;
    let r;
    let exportDir = null;
    try {
      if (config.exportDir) {
        exportDir = await prepareJobDir(config.exportDir, entry.job.conversationToken, token);
      }
      r = await run({ ...entry.job, exportDir }, config, parentEnv, abort.signal);
    } catch {
      r = { ok: false, errorCode: 'AGENT_ERROR' };
    }
    if (abort.signal.aborted) {
      // agent 停止：运行中任务视为丢失，不落盘，重启后 GET 返回 404
      active.delete(token);
      log('job.lost', { jobToken: token });
      return;
    }
    const secs = Math.round((Date.now() - started) / 1000);
    let files = [];
    if (exportDir) {
      if (r.ok) {
        try { files = await listJobFiles(exportDir); } catch { files = []; }
      }
      await sealJobDir(exportDir);
    }
    const result = {
      status: r.ok ? 'DONE' : 'FAILED',
      answerMd: r.ok ? r.answerMd : null,
      sessionId: r.sessionId ?? null,
      files,
      // 内部字段：定位导出目录（下载 / 清理用），GET /jobs 不返回
      conversationToken: entry.job.conversationToken,
      secs,
      turns: r.turns ?? null,
      costUsd: r.costUsd ?? null,
      errorCode: r.ok ? null : (r.errorCode || 'AGENT_ERROR'),
    };
    try {
      await store.writeResult(token, result);
    } catch {
      unpersisted.set(token, result);
      log('job.persist_failed', { jobToken: token });
    }
    active.delete(token);
    log('job.finished', {
      jobToken: token, status: result.status, secs, errorCode: result.errorCode,
      exitCode: r.exitCode ?? null, signal: r.signal ?? null,
    });
  }

  async function get(token) {
    const entry = active.get(token);
    if (entry) {
      return {
        status: entry.status,
        answerMd: null,
        sessionId: null,
        files: [],
        secs: entry.startedAt ? Math.round((Date.now() - entry.startedAt) / 1000) : 0,
        turns: null,
        costUsd: null,
        errorCode: null,
      };
    }
    if (unpersisted.has(token)) return unpersisted.get(token);
    return store.readResult(token);
  }

  /** 关闭：不再派发，结束正在运行的 claude 进程组（运行中任务随之丢失，属预期）。 */
  function close() {
    closed = true;
    abort.abort();
  }

  async function drain() {
    await Promise.all([...runningPromises]);
  }

  return {
    submit, get, close, drain,
    stats: () => ({ running, queued: queue.length }),
  };
}
