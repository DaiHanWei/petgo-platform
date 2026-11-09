// 执行器：用 claude -p 跑一个问题。
// - 不使用 shell，问题经 stdin 传入
// - detached 建立独立进程组，超时时整组结束
// - 不加 --bare（该模式不读 CLAUDE_CODE_OAUTH_TOKEN，spec §2 D1）
// - 子进程 env 只走 buildChildEnv() 白名单，不展开 process.env

import { spawn } from 'node:child_process';
import { classifyFailure } from './classify.mjs';

export const ALLOWED_TOOLS = 'Bash,Read,Grep,Glob';

/** 子进程 env 白名单（Story 1.2 在此收紧，禁止展开 process.env）。 */
export function buildChildEnv(parentEnv) {
  const env = {};
  for (const k of ['PATH', 'HOME', 'CLAUDE_CODE_OAUTH_TOKEN']) {
    if (parentEnv[k] !== undefined) env[k] = parentEnv[k];
  }
  return env;
}

export function buildArgs({ resumeSessionId }) {
  const args = ['-p', '--output-format', 'json', '--allowedTools', ALLOWED_TOOLS];
  if (resumeSessionId) args.push('--resume', resumeSessionId);
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
    const child = spawn(config.claudeBin, buildArgs(job), {
      env: buildChildEnv(parentEnv),
      cwd: config.workDir,
      detached: true,
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

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
