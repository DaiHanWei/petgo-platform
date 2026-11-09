// agent 配置：全部来自 env，凭证不落盘、不入库。
// 定时值可注入，便于测试不真实等待 10 分钟。

import path from 'node:path';

const TOKEN_RE = /^[A-Za-z0-9_-]{8,128}$/;

export const LANGS = Object.freeze(['zh', 'en', 'id']);

function intEnv(env, name, def, min, max = Number.MAX_SAFE_INTEGER) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) {
    throw new Error(`配置 ${name} 必须是 ${min}～${max} 之间的整数`);
  }
  return n;
}

/**
 * 从 env 读取配置。AGENT_SECRET 缺失时拒绝启动。
 * @param {Record<string,string|undefined>} env
 */
export function loadConfig(env = process.env) {
  const secret = env.AGENT_SECRET;
  if (!secret || secret.length < 16) {
    throw new Error('AGENT_SECRET 未配置或过短（至少 16 个字符），拒绝启动');
  }
  const dataDir = path.resolve(env.DATA_DIR || './data');
  // claude 的工作目录：放查数说明书，与任务数据目录分开，避免把 data/ 当成当前目录暴露给模型
  const workDir = path.resolve(env.WORK_DIR || './work');
  if (workDir === dataDir || workDir.startsWith(dataDir + path.sep)) {
    throw new Error('WORK_DIR 不得位于 DATA_DIR 内');
  }
  return {
    secret,
    // 容器内监听全部接口：只接入 Docker internal 网络、不映射端口（spec §4）
    port: intEnv(env, 'PORT', 8790, 1, 65535),
    host: env.HOST || '0.0.0.0',
    dataDir,
    workDir,
    claudeBin: env.CLAUDE_BIN || 'claude',
    jobTimeoutMs: intEnv(env, 'JOB_TIMEOUT_MS', 600_000, 1_000),
    killGraceMs: intEnv(env, 'KILL_GRACE_MS', 5_000, 100),
    concurrency: 2,
    maxQueue: 20,
  };
}

export function isValidToken(t) {
  return typeof t === 'string' && TOKEN_RE.test(t);
}
