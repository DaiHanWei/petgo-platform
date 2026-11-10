// 失败分类：集中在此，便于本地实测后调整。
//
// 依据（2026-11-09 查阅官方文档，不凭记忆写文案）：
// - https://code.claude.com/docs/en/headless：运行内失败（如未认证）时，Claude Code 把失败作为
//   result 打印到 stdout，退出码非 0；stream-json 的 api_retry 事件 error 类别含
//   `authentication_failed`、`rate_limit`、`billing_error`。
// - https://code.claude.com/docs/en/errors 错误文案表中的片段：
//   `Not logged in`、`Invalid API key`、`API Error: 401`、
//   `You've hit your session limit`、`Server is temporarily limiting requests`、`Credit balance is too low`。
// - Anthropic API 错误类型：`rate_limit_error`（429）、`authentication_error`（401）。
//
// 【待本地实测确认】claude -p 在订阅额度用尽 / token 失效时的实际输出字段和完整文案。
// 确认后把真实输出固定到 test/fixtures/ 并在此补充规则。无法识别的失败一律归为 AGENT_ERROR。

const AUTH_PATTERNS = [
  /Not logged in/i,
  /Invalid API key/i,
  /API Error: 401\b/i,
  /\bauthentication_failed\b/i,
  /\bauthentication_error\b/i,
];

// 额度用尽（含订阅会话额度、API 限流、余额不足）
const RATE_LIMIT_PATTERNS = [
  /You've hit your session limit/i,
  /Server is temporarily limiting requests/i,
  /Credit balance is too low/i,
  /API Error: 429\b/i,
  /\brate_limit(_error)?\b/i,
  /\bbilling_error\b/i,
];

/** 只从结构化字段和错误输出里取文本做匹配，不写日志。 */
function collectText(parsed, stderr) {
  const parts = [stderr || ''];
  if (parsed && typeof parsed === 'object') {
    // 只在 CLI 声明出错时取 result（文档：失败时把失败信息作为 result 输出），
    // 避免把模型答案里恰好出现的关键词当成错误
    if (parsed.is_error === true && typeof parsed.result === 'string') parts.push(parsed.result);
    if (typeof parsed.error === 'string') parts.push(parsed.error);
    if (parsed.error && typeof parsed.error === 'object') parts.push(JSON.stringify(parsed.error));
  }
  return parts.join('\n');
}

export function classifyFailure({ parsed, stderr, spawnError }) {
  if (spawnError) return 'AGENT_ERROR';
  const text = collectText(parsed, stderr);
  if (AUTH_PATTERNS.some((re) => re.test(text))) return 'AGENT_AUTH_FAILED';
  if (RATE_LIMIT_PATTERNS.some((re) => re.test(text))) return 'AGENT_RATE_LIMITED';
  return 'AGENT_ERROR';
}
