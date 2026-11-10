// 导出文件（Story 2.1，spec §4.2、S4）：按「对话 / 任务」隔离目录，只返回本任务新生成的文件，不会重发历史文件。
//
// 目录：<EXPORT_DIR>/<conversationToken>/<jobToken>/
//   - 根目录与对话目录 0711：可穿过、不可列举——别的任务不知道不可枚举的 token 就找不到别人的目录
//   - 任务目录运行中 0733（claude 以独立 uid 运行，可写入、不可列举）；结束后 0700，claude 不能再改写已导出的文件
//   - 列清单与下载只认任务目录顶层的普通文件：拒绝符号链接（防止 claude 用链接把 /data 等目录的文件带出来）、
//     子目录、点开头的隐藏文件；打开时再用 O_NOFOLLOW + fstat 复核，避免检查与打开之间被替换。

import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { isValidToken } from './config.mjs';

export const MAX_FILES = 20;
// 另排除 % ; ? #：后台 Spring Security 防火墙会拒绝路径里编码后的这些字符，列出来也下载不了
const SAFE_NAME = /^[^/\\\0\r\n%;?#]{1,200}$/;

export function isSafeFileName(name) {
  return typeof name === 'string' && SAFE_NAME.test(name) && !name.startsWith('.') && name !== '..';
}

export function conversationDir(exportDir, conversationToken) {
  if (!isValidToken(conversationToken)) throw new Error('非法 token');
  return path.join(exportDir, conversationToken);
}

export function jobDir(exportDir, conversationToken, jobToken) {
  if (!isValidToken(jobToken)) throw new Error('非法 token');
  return path.join(conversationDir(exportDir, conversationToken), jobToken);
}

/** 建好任务导出目录并返回路径。 */
export async function prepareJobDir(exportDir, conversationToken, jobToken) {
  const conv = conversationDir(exportDir, conversationToken);
  await fsp.mkdir(conv, { recursive: true });
  await fsp.chmod(conv, 0o711);
  const dir = jobDir(exportDir, conversationToken, jobToken);
  await fsp.mkdir(dir, { recursive: true });
  await fsp.chmod(dir, 0o733);
  return dir;
}

/** 任务结束：收回写权限（0700），已导出的文件不再能被后续任务改写。 */
export async function sealJobDir(dir) {
  await fsp.chmod(dir, 0o700).catch(() => {});
}

/** 任务目录顶层的普通文件清单 [{name, size}]（按名称排序，最多 MAX_FILES 个）。 */
export async function listJobFiles(dir) {
  let entries;
  try {
    entries = await fsp.readdir(dir, { withFileTypes: true });
  } catch (e) {
    if (e.code === 'ENOENT') return [];
    throw e;
  }
  const out = [];
  for (const ent of entries) {
    if (!isSafeFileName(ent.name)) continue;
    const st = await fsp.lstat(path.join(dir, ent.name)).catch(() => null);
    if (!st || !st.isFile()) continue; // 符号链接、目录、设备文件一律忽略
    out.push({ name: ent.name, size: st.size });
  }
  out.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  return out.slice(0, MAX_FILES);
}

/**
 * 打开任务目录里的一个文件用于下载。不是普通文件（含符号链接）返回 null。
 * @returns {Promise<{stream: fs.ReadStream, size: number} | null>}
 */
export async function openJobFile(dir, name) {
  if (!isSafeFileName(name)) return null;
  const full = path.join(dir, name);
  if (path.dirname(full) !== dir) return null;
  let fh;
  try {
    fh = await fsp.open(full, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  } catch {
    return null; // 不存在 / 是符号链接（ELOOP）/ 无权限
  }
  try {
    const st = await fh.stat();
    if (!st.isFile()) {
      await fh.close();
      return null;
    }
    return { stream: fh.createReadStream(), size: st.size };
  } catch {
    await fh.close().catch(() => {});
    return null;
  }
}

/** 删除整个对话的导出目录（Story 2.2）。不存在视为成功。 */
export async function removeConversationExports(exportDir, conversationToken) {
  await fsp.rm(conversationDir(exportDir, conversationToken), { recursive: true, force: true });
}
