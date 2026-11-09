#!/usr/bin/env node
// 假 claude：测试用。记录收到的参数和 env，按 stdin 内容决定行为。
//   SUCCESS / 其他   → 输出 fixtures/success.json（session_id 与 resume 关联）
//   RATE_LIMIT       → fixtures/rate-limited.json，退出码 1
//   AUTH             → fixtures/auth-failed.json，退出码 1
//   CRASH            → stderr 输出随机内容，退出码 2
//   HANG             → 生成一个同组孙进程，然后永久挂起（可被 SIGTERM 结束）
//   HANG_STUBBORN    → 忽略 SIGTERM，只能被 SIGKILL 结束
//   BG_ORPHAN        → 留下占着 stdout 的后台孙进程后正常退出
//   SLOW:<ms>        → 等待后成功
//   ECHO_ENV:<name>  → 把指定 env 写入答案（用于验证 env 白名单）
//   WRITE_FILE       → 在 AIQ_EXPORT_DIR 写 report.csv（及一个隐藏文件、一个子目录、一个指向 /etc/passwd 的符号链接）
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = (n) => fs.readFileSync(path.join(here, 'fixtures', n), 'utf8');

const stdin = fs.readFileSync(0, 'utf8');
const args = process.argv.slice(2);
const recDir = path.join(process.env.HOME || '/tmp', 'fake-claude-records');
fs.mkdirSync(recDir, { recursive: true });
const rec = { pid: process.pid, args, env: { ...process.env }, stdin, cwd: process.cwd() };

function save() {
  fs.writeFileSync(path.join(recDir, `${process.pid}.json`), JSON.stringify(rec));
}

const resumeIdx = args.indexOf('--resume');
const resumed = resumeIdx >= 0 ? args[resumeIdx + 1] : null;
const marker = stdin.trim().split(/\s+/).pop() || '';

function success(answer) {
  const out = JSON.parse(fixture('success.json'));
  out.result = answer ?? out.result;
  out.session_id = resumed || out.session_id;
  process.stdout.write(JSON.stringify(out));
}

if (marker === 'HANG' || marker === 'HANG_STUBBORN') {
  const gc = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: 'ignore' });
  rec.grandchildPid = gc.pid;
  if (marker === 'HANG_STUBBORN') process.on('SIGTERM', () => {});
  save();
  setInterval(() => {}, 1000);
} else if (marker === 'BG_ORPHAN') {
  // 模拟 Bash 工具留下的后台进程：继承 stdout 管道，claude 本身正常退出
  const gc = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], { stdio: ['ignore', 'inherit', 'inherit'] });
  rec.grandchildPid = gc.pid;
  save();
  success();
  gc.unref();
  process.exit(0);
} else if (marker.startsWith('SLOW:')) {
  save();
  setTimeout(() => success(), Number(marker.slice(5)));
} else if (marker === 'RATE_LIMIT') {
  save();
  process.stdout.write(fixture('rate-limited.json'));
  process.exitCode = 1;
} else if (marker === 'AUTH') {
  save();
  process.stdout.write(fixture('auth-failed.json'));
  process.exitCode = 1;
} else if (marker === 'CRASH') {
  save();
  process.stderr.write('something unexpected SECRET_QUESTION_TEXT\n');
  process.exitCode = 2;
} else if (marker === 'WRITE_FILE') {
  const dir = process.env.AIQ_EXPORT_DIR;
  fs.writeFileSync(path.join(dir, 'report.csv'), 'week,count\n1,1612\n');
  fs.writeFileSync(path.join(dir, '.hidden'), 'x');
  fs.mkdirSync(path.join(dir, 'sub'));
  fs.symlinkSync('/etc/passwd', path.join(dir, 'leak.csv'));
  save();
  success('已导出 report.csv');
} else if (marker.startsWith('ECHO_ENV:')) {
  save();
  success(String(process.env[marker.slice(9)] ?? '<unset>'));
} else {
  save();
  success();
}
