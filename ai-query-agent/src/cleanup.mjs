// 对话清理（Story 2.2，spec §5.7、S4、S5）：删除该对话的导出目录、任务结果、Claude 会话记录。幂等。
//
// Claude 会话记录在 <claude HOME>/.claude/projects/<工作目录转义名>/<sessionId>.jsonl（及同名目录），
// 归 claude 的 uid 所有；agent 容器加回了 CAP_DAC_OVERRIDE（切换到 claude uid 时能力会被清空，不影响隔离），
// 可以直接删除 claude 创建的文件与子目录。

import fsp from 'node:fs/promises';
import path from 'node:path';
import { isValidToken } from './config.mjs';
import { removeConversationExports } from './files.mjs';

const SESSION_ID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** 会话记录文件 / 目录的完整路径（只接受 UUID 形式的 session id）。 */
async function sessionPaths(claudeHome, sessionIds) {
  const projects = path.join(claudeHome, '.claude', 'projects');
  let dirs;
  try {
    dirs = await fsp.readdir(projects, { withFileTypes: true });
  } catch (e) {
    if (e.code === 'ENOENT') return [];
    throw e;
  }
  const out = [];
  for (const d of dirs) {
    if (!d.isDirectory()) continue;
    for (const sid of sessionIds) {
      out.push(path.join(projects, d.name, `${sid}.jsonl`));
      out.push(path.join(projects, d.name, sid));
    }
  }
  return out;
}

async function removeAll(paths) {
  for (const p of paths) await fsp.rm(p, { recursive: true, force: true });
}

/**
 * 删除对话的全部痕迹（按对话索引，不扫描全部任务）。调用方须先确认该对话没有排队 / 运行中的任务。
 * @returns {Promise<{jobs: string[]}>}
 */
export async function deleteConversation({ config, store, conversationToken, claudeHome }) {
  const idx = await store.readIndex(conversationToken);
  const sessionIds = [...new Set(idx.sessions.filter((s) => typeof s === 'string' && SESSION_ID_RE.test(s)))];
  if (claudeHome && sessionIds.length > 0) {
    await removeAll(await sessionPaths(claudeHome, sessionIds));
  }
  if (config.exportDir) {
    await removeConversationExports(config.exportDir, conversationToken);
  }
  const jobs = idx.jobs.filter((t) => isValidToken(t));
  for (const t of jobs) {
    await fsp.rm(store.jobDir(t), { recursive: true, force: true });
  }
  await store.removeIndex(conversationToken);
  return { jobs };
}
