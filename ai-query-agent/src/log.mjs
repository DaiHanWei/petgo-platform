// agent 日志：JSON 行，只允许白名单字段。
// 问题、答案、claude 的 stdout/stderr 一律不进日志（spec §4.2、NFR4）。

const ALLOWED = new Set([
  'event', 'jobToken', 'conversationToken', 'status', 'secs', 'errorCode',
  'exitCode', 'signal', 'queued', 'running', 'port', 'reason', 'count',
]);

export function createLogger(write = (line) => process.stdout.write(line + '\n')) {
  return function log(event, fields = {}) {
    const rec = { ts: new Date().toISOString(), event };
    for (const [k, v] of Object.entries(fields)) {
      if (ALLOWED.has(k)) rec[k] = v;
    }
    write(JSON.stringify(rec));
  };
}
