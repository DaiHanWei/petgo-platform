// HTTP 层：node:http，只给 ops 内网调用。
// 所有接口先校验 X-Internal-Secret（先于任何其他校验）。

import http from 'node:http';
import { pipeline } from 'node:stream';
import crypto from 'node:crypto';
import { isValidToken, LANGS } from './config.mjs';
import { isSafeFileName, jobDir, openJobFile } from './files.mjs';

export const MAX_BODY = 64 * 1024;
export const MAX_QUESTION = 2000;
// Claude 会话 ID 为 UUID；严格校验，防止以 - 开头的值被 CLI 当成参数（参数注入）
const SESSION_ID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function digest(s) {
  return crypto.createHash('sha256').update(String(s), 'utf8').digest();
}

export function secretMatches(given, expected) {
  if (typeof given !== 'string' || given.length === 0) return false;
  return crypto.timingSafeEqual(digest(given), digest(expected));
}

function send(res, status, body) {
  const payload = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(payload);
}

class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > MAX_BODY) {
      reject(new HttpError(413, 'BODY_TOO_LARGE'));
      req.resume();
      return;
    }
    const chunks = [];
    let len = 0;
    let rejected = false;
    req.on('data', (c) => {
      if (rejected) return;
      len += c.length;
      if (len > MAX_BODY) {
        rejected = true;
        reject(new HttpError(413, 'BODY_TOO_LARGE'));
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => { if (!rejected) resolve(Buffer.concat(chunks).toString('utf8')); });
    req.on('error', (e) => { if (!rejected) reject(e); });
  });
}

/** 校验并规整 POST /jobs 请求体，非法时抛 400。 */
export function parseJobRequest(raw) {
  let b;
  try { b = JSON.parse(raw); } catch { throw new HttpError(400, 'BAD_JSON'); }
  if (!b || typeof b !== 'object' || Array.isArray(b)) throw new HttpError(400, 'BAD_JSON');
  const { jobToken, conversationToken, question, lang, resumeSessionId } = b;
  if (!isValidToken(jobToken)) throw new HttpError(400, 'BAD_JOB_TOKEN');
  if (!isValidToken(conversationToken)) throw new HttpError(400, 'BAD_CONVERSATION_TOKEN');
  if (typeof question !== 'string' || question.trim() === '' || [...question].length > MAX_QUESTION) {
    throw new HttpError(400, 'BAD_QUESTION');
  }
  if (!LANGS.includes(lang)) throw new HttpError(400, 'BAD_LANG');
  if (resumeSessionId !== undefined && resumeSessionId !== null
      && !(typeof resumeSessionId === 'string' && SESSION_ID_RE.test(resumeSessionId))) {
    throw new HttpError(400, 'BAD_RESUME_SESSION_ID');
  }
  return { jobToken, conversationToken, question, lang, resumeSessionId: resumeSessionId || null };
}

/**
 * @param {{config:object, jobs:object, log:Function, routes?:Function}} deps
 *   routes：扩展路由（Epic 2 的文件与删除接口），签名 (req, res, url, ctx) => boolean|Promise<boolean>
 */
export function createServer({ config, jobs, log, routes }) {
  return http.createServer(async (req, res) => {
    try {
      if (!secretMatches(req.headers['x-internal-secret'], config.secret)) {
        req.resume();
        return send(res, 401, { error: 'UNAUTHORIZED' });
      }
      const url = new URL(req.url, 'http://agent');
      const parts = url.pathname.split('/').filter(Boolean);

      if (req.method === 'POST' && url.pathname === '/jobs') {
        const job = parseJobRequest(await readBody(req));
        const r = await jobs.submit(job);
        if (r === 'full') return send(res, 429, { error: 'QUEUE_FULL' });
        return send(res, 202, { jobToken: job.jobToken });
      }

      if (req.method === 'GET' && parts.length === 2 && parts[0] === 'jobs') {
        const token = parts[1];
        if (!isValidToken(token)) return send(res, 404, { error: 'NOT_FOUND' });
        const r = await jobs.get(token);
        if (!r) return send(res, 404, { error: 'NOT_FOUND' });
        const { conversationToken: _internal, ...pub } = r;
        return send(res, 200, pub);
      }

      // 下载导出文件（Story 2.1）：name 只允许匹配该任务清单里的文件，拒绝路径穿越与符号链接
      if (req.method === 'GET' && parts.length === 4 && parts[0] === 'jobs' && parts[2] === 'files') {
        req.resume();
        const token = parts[1];
        let name;
        try { name = decodeURIComponent(parts[3]); } catch { return send(res, 400, { error: 'BAD_NAME' }); }
        if (!isSafeFileName(name)) return send(res, 400, { error: 'BAD_NAME' });
        if (!isValidToken(token)) return send(res, 404, { error: 'NOT_FOUND' });
        const r = await jobs.get(token);
        if (!r || r.status !== 'DONE' || !r.conversationToken || !config.exportDir
            || !(r.files || []).some((f) => f.name === name)) {
          return send(res, 404, { error: 'NOT_FOUND' });
        }
        const file = await openJobFile(jobDir(config.exportDir, r.conversationToken, token), name);
        if (!file) return send(res, 404, { error: 'GONE' });
        log('file.download', { jobToken: token });
        res.writeHead(200, {
          'content-type': 'application/octet-stream',
          'content-length': String(file.size),
          'cache-control': 'no-store',
          'x-content-type-options': 'nosniff',
        });
        // pipeline：客户端中途断开时也会关闭文件流，不泄漏文件描述符
        pipeline(file.stream, res, () => {});
        return;
      }

      if (routes && await routes(req, res, url, { send, HttpError })) return;

      req.resume();
      return send(res, 404, { error: 'NOT_FOUND' });
    } catch (e) {
      if (e instanceof HttpError) {
        req.resume();
        return send(res, e.status, { error: e.code });
      }
      log('http.error', { reason: e?.code || 'INTERNAL' });
      if (!res.headersSent) send(res, 500, { error: 'INTERNAL' });
      else res.end();
    }
  });
}
