// 执行器：用 claude -p 跑一个问题。
// - 不使用 shell，问题经 stdin 传入
// - detached 建立独立进程组，超时时整组结束
// - 不加 --bare（该模式不读 CLAUDE_CODE_OAUTH_TOKEN，spec §2 D1）
// - 子进程 env 只走 buildChildEnv() 白名单，不展开 process.env

import { spawn } from 'node:child_process';
import { classifyFailure } from './classify.mjs';
import { buildSystemPrompt } from './prompt.mjs';

export const ALLOWED_TOOLS = 'Bash,Read,Grep,Glob';

/**
 * 子进程 env 白名单（spec §6 S1）。只按名单逐个复制，禁止展开 process.env。
 * - 数据库密码不走 env：用 PGPASSFILE 指向只读挂载的 .pgpass
 * - 代理变量透传，为白名单代理（Story 3.1）预留
 * - 埋点库（analytics-pipeline Story 4.8，FR-27）：只多两个非机密变量（库名、账号名），主机 / 端口 / PGPASSFILE 复用，
 *   密码写在同一个 .pgpass 的第二行
 */
export const CHILD_ENV_PASSTHROUGH = Object.freeze([
  'PATH', 'HOME', 'LANG', 'TZ',
  'CLAUDE_CODE_OAUTH_TOKEN',
  'PGHOST', 'PGPORT', 'PGUSER', 'PGDATABASE', 'PGPASSFILE',
  'AIQ_ANALYTICS_PGDATABASE', 'AIQ_ANALYTICS_PGUSER',
  'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY',
]);

/** 固定写入的 env（官方文档 env-vars 页，2026-11-09 查阅）。 */
export const CHILD_ENV_FIXED = Object.freeze({
  // 从 Bash、hooks 等子进程环境中剥离凭证；claude 自身仍用 token 调 API。
  // 默认开启；只能由 agent 配置 SUBPROCESS_ENV_SCRUB=0 关闭，父进程 env 无法覆盖
  CLAUDE_CODE_SUBPROCESS_ENV_SCRUB: '1',
  // 关闭自动更新、遥测、错误上报等非必要出网
  CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
  DISABLE_AUTOUPDATER: '1',
});

export function buildChildEnv(parentEnv, { claudeHome, subprocessEnvScrub = true } = {}) {
  const env = {};
  for (const k of CHILD_ENV_PASSTHROUGH) {
    if (parentEnv[k] !== undefined) env[k] = parentEnv[k];
  }
  if (claudeHome) env.HOME = claudeHome;
  return { ...env, ...CHILD_ENV_FIXED, CLAUDE_CODE_SUBPROCESS_ENV_SCRUB: subprocessEnvScrub ? '1' : '0' };
}

export function buildArgs({ resumeSessionId, sessionId, lang, exportDir }) {
  const args = [
    '-p', '--output-format', 'json', '--allowedTools', ALLOWED_TOOLS,
    '--append-system-prompt', buildSystemPrompt(lang, exportDir),
    // 只加载工作目录（root 所有、只读）里的项目设置，忽略 claude 可写的 ~/.claude/settings.json，
    // 防止某次被注入的任务写入 hooks / 权限后影响以后所有任务
    '--setting-sources', 'project',
  ];
  if (resumeSessionId) {
    args.push('--resume', resumeSessionId);
  } else if (sessionId) {
    // 预分配的会话 id（agent 生成的 UUID），保证即使任务超时 / 中断，清理时也知道会话记录在哪
    args.push('--session-id', sessionId);
  }
  return args;
}

function killGroup(child, signal) {
  try {
    process.kill(-child.pid, signal);
  } catch {
    try { child.kill(signal); } catch { /* 已退出 */ }
  }
}

/**
 * 执行一次查询。永不 reject，结果统一为
 * { ok, answerMd, sessionId, turns, costUsd, errorCode, exitCode, signal }
 */
export function runClaude(job, config, parentEnv = process.env, abortSignal = undefined) {
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(config.claudeBin, buildArgs(job), {
        env: { ...buildChildEnv(parentEnv, config), ...(job.exportDir ? { AIQ_EXPORT_DIR: job.exportDir } : {}) },
        cwd: config.workDir,
        detached: true,
        shell: false,
        stdio: ['pipe', 'pipe', 'pipe'],
        // claude 以独立 uid 运行：读不到 agent 进程的 /proc/<pid>/environ（AGENT_SECRET）和 /data 下其他任务的结果
        ...(config.claudeUid !== undefined ? { uid: config.claudeUid, gid: config.claudeGid } : {}),
      });
    } catch (e) {
      resolve({ ok: false, answerMd: null, sessionId: null, turns: null, costUsd: null,
        errorCode: 'AGENT_ERROR', exitCode: null, signal: null, spawnError: e });
      return;
    }

    const out = [];
    const err = [];
    let outLen = 0;
    const MAX_OUT = 16 * 1024 * 1024;
    let timedOut = false;
    let killTimer = null;
    let spawnError = null;

    child.stdout.on('data', (b) => {
      if (outLen < MAX_OUT) { out.push(b); outLen += b.length; }
    });
    child.stderr.on('data', (b) => {
      if (err.length < 256) err.push(b);
    });
    child.on('error', (e) => { spawnError = e; });
    child.stdin.on('error', () => { /* 子进程提前退出 */ });
    child.stdin.end(job.question);

    // agent 停止时整组结束，避免 detached 子进程成为孤儿
    const onAbort = () => killGroup(child, 'SIGKILL');
    abortSignal?.addEventListener('abort', onAbort, { once: true });

    const timer = setTimeout(() => {
      timedOut = true;
      killGroup(child, 'SIGTERM');
      killTimer = setTimeout(() => killGroup(child, 'SIGKILL'), config.killGraceMs);
    }, config.jobTimeoutMs);

    let finished = false;
    let closeFallback = null;
    function finish(exitCode, signal) {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      if (killTimer) clearTimeout(killTimer);
      if (closeFallback) clearTimeout(closeFallback);
      abortSignal?.removeEventListener('abort', onAbort);
      const stdout = Buffer.concat(out).toString('utf8');
      const stderr = Buffer.concat(err).toString('utf8');
      resolve(interpret({ stdout, stderr, exitCode, signal, timedOut, spawnError }));
    }

    // 主进程退出后：进程组里残留的孙进程（如 Bash 工具放到后台的命令）一并结束，
    // 否则它们会继续占着 stdout 管道，close 迟迟不来，名额被白占到超时。
    child.on('exit', (exitCode, signal) => {
      killGroup(child, 'SIGKILL');
      closeFallback = setTimeout(() => {
        // 脱离进程组的残留仍占着管道：不再等待，直接用已收到的输出收尾
        child.stdout.destroy();
        child.stderr.destroy();
        finish(exitCode, signal);
      }, config.killGraceMs);
    });

    // 正常情况下 close 之后才释放名额：此时 stdio 已关闭、主进程已退出
    child.on('close', (exitCode, signal) => finish(exitCode, signal));
  });
}

function parseJson(stdout) {
  const s = stdout.trim();
  if (!s) return null;
  try {
    return JSON.parse(s);
  } catch {
    // 兼容尾部附带的非 JSON 行：取最后一行可解析的对象
    const lines = s.split('\n').reverse();
    for (const line of lines) {
      try { return JSON.parse(line); } catch { /* 继续 */ }
    }
    return null;
  }
}

export function interpret({ stdout, stderr, exitCode, signal, timedOut, spawnError }) {
  const parsed = parseJson(stdout || '');
  const base = {
    answerMd: null,
    sessionId: parsed?.session_id ?? null,
    turns: Number.isInteger(parsed?.num_turns) ? parsed.num_turns : null,
    costUsd: typeof parsed?.total_cost_usd === 'number' ? parsed.total_cost_usd : null,
    exitCode: exitCode ?? null,
    signal: signal ?? null,
  };
  if (timedOut) return { ...base, ok: false, errorCode: 'AGENT_TIMEOUT' };
  const success = !spawnError && exitCode === 0 && parsed
    && parsed.is_error !== true && typeof parsed.result === 'string';
  if (success) return { ...base, ok: true, answerMd: parsed.result, errorCode: null };
  return {
    ...base,
    ok: false,
    errorCode: classifyFailure({ parsed, stderr: stderr || '', exitCode, spawnError }),
  };
}
