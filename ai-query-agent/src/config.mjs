// agent 配置：全部来自 env，凭证不落盘、不入库。
// 定时值可注入，便于测试不真实等待 10 分钟。

import path from 'node:path';

const TOKEN_RE = /^[A-Za-z0-9_-]{8,128}$/;

const FORBIDDEN_ENV_RE = /^(LARK|FEISHU)_/i;

export const LANGS = Object.freeze(['zh', 'en', 'id']);

function boolEnv(env, name, def) {
  const raw = env[name];
  if (raw === undefined || raw === '') return def;
  if (raw === '1') return true;
  if (raw === '0') return false;
  throw new Error(`配置 ${name} 只能是 1 或 0`);
}

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
  // agent 不持有任何 Lark 凭证（spec §3、NFR12、S1）
  const lark = Object.keys(env).filter((k) => FORBIDDEN_ENV_RE.test(k));
  if (lark.length > 0) {
    throw new Error(`环境中存在 Lark 变量（${lark.join(', ')}），拒绝启动`);
  }
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
  // claude 子进程的独立 uid / gid / HOME（容器内配置；不配置时与 agent 同用户，仅用于本地测试）
  const claudeUid = env.CLAUDE_UID ? intEnv(env, 'CLAUDE_UID', 0, 1, 2 ** 31 - 1) : undefined;
  const claudeGid = env.CLAUDE_GID ? intEnv(env, 'CLAUDE_GID', 0, 1, 2 ** 31 - 1) : claudeUid;
  // 导出文件根目录（Story 2.1）：与任务数据目录分开，claude 只能写自己任务的子目录
  const exportDir = path.resolve(env.EXPORT_DIR || './exports');
  if (exportDir === dataDir || exportDir.startsWith(dataDir + path.sep)) {
    throw new Error('EXPORT_DIR 不得位于 DATA_DIR 内');
  }
  return {
    secret,
    exportDir,
    claudeUid,
    claudeGid,
    claudeHome: env.CLAUDE_HOME || undefined,
    // 从 Bash 子进程剥离凭证（CLAUDE_CODE_SUBPROCESS_ENV_SCRUB）。依赖 bubblewrap 沙箱，
    // 宿主内核不允许容器内创建命名空间时 claude 无法启动，只能置 0。
    // 2026-11-10 Dai 确认：当前服务器关闭剥离并接受 Bash 可读订阅 token 的风险（spec §11）。
    subprocessEnvScrub: boolEnv(env, 'SUBPROCESS_ENV_SCRUB', true),
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
