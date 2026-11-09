// 入口：读配置 → 建存储、队列、HTTP 服务。
import fs from 'node:fs';
import { loadConfig } from './config.mjs';
import { createLogger } from './log.mjs';
import { createStore } from './store.mjs';
import { createJobManager } from './jobs.mjs';
import { createServer } from './server.mjs';

const log = createLogger();
let config;
try {
  config = loadConfig(process.env);
} catch (e) {
  log('agent.config_error', { reason: e.message });
  process.exit(1);
}

fs.mkdirSync(config.dataDir, { recursive: true, mode: 0o700 });
fs.mkdirSync(config.workDir, { recursive: true });
const store = createStore(config.dataDir);
const jobs = createJobManager({ config, store, log });
const server = createServer({ config, jobs, log });

server.listen(config.port, config.host, () => {
  log('agent.started', { port: config.port });
});

function shutdown(signal) {
  log('agent.stopping', { signal });
  jobs.close();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 2_000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
