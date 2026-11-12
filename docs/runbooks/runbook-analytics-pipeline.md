# Runbook：自建埋点管道（analytics-pipeline）

> 规划：`_bmad-output/planning-artifacts/analytics-pipeline/architecture.md`（A8 资源隔离、启动隔离）；
> 实现：`_bmad-output/implementation-artifacts/analytics-pipeline/`。
> **所有建库 / 部署都等明确指令再执行。** 本文件只写操作步骤，不含任何凭证。后续 story 往本文件追加章节。

## 1. 部署前先建库（Story 1.1）

埋点数据落在与业务库**同一个 `petgo-postgres` 实例**上的独立库里，用独立账号、独立小连接池、独立 Flyway 历史：

| 项 | 生产 | staging |
|---|---|---|
| 埋点库 | `petgo_analytics` | `petgo_analytics_stag` |
| 写入账号（后端用，库属主） | `analytics_writer`（CONNECTION LIMIT 5，statement_timeout 120s） | `analytics_writer_stag`（同左） |
| 只读账号（AI 查数用） | `analytics_reader`（CONNECTION LIMIT 3，只有 CONNECT；表权限由迁移逐表授予） | `analytics_reader_stag`（同左） |
| 后端埋点连接池 | 3 | 2 |
| env 文件 | `~/.env.petgo` | `~/.env.petgo-stag` |

🔴 **必须在首次部署含埋点管道的后端之前完成。** 没建库也不会让后端起不来——埋点管道会停用并发 TECH 告警（见 §1.4），但那段时间的埋点就没了。

### 1.1 建库（生产 / stag 各执行一次）

脚本 `scripts/ops/create-analytics-db.sql` 可重复执行：已存在的角色会被重设密码与限额，已存在的库只校正属主与库级权限。

```bash
# 在服务器上，仓库根目录（或把脚本 scp 上去）。密码只在当前 shell 里，别写进文件、别进 shell history。
read -rs W; echo   # 写入账号密码，建议 openssl rand -hex 24
read -rs R; echo   # 只读账号密码（与上面不同）

# 生产
docker exec -i petgo-postgres psql -v ON_ERROR_STOP=1 -v env=prod -v writer_pw="$W" -v reader_pw="$R" \
  -U petgo -d postgres < scripts/ops/create-analytics-db.sql

# staging（另生成一组密码）
docker exec -i petgo-postgres psql -v ON_ERROR_STOP=1 -v env=stag -v writer_pw="$W" -v reader_pw="$R" \
  -U petgo -d postgres < scripts/ops/create-analytics-db.sql

unset W R
```

执行完脚本会回显两个角色的 `rolconnlimit` / `rolconfig` 和库的属主 / ACL，核对：

- 写入账号 `rolconnlimit=5`、`rolconfig={statement_timeout=120s}`；只读账号 `rolconnlimit=3`。
- 库属主是写入账号；`datacl` 里**没有** `=c/`（PUBLIC）项，只有写入账号和只读账号。

> ⚠️ 若实例开了 `log_statement=all|ddl`，`ALTER ROLE ... PASSWORD` 会进 postgres 日志，执行前确认未开。
> 只读账号密码交给 AI 查数侧配置（FR-27），不要放进后端 env。

### 1.2 后端 env

生产 `~/.env.petgo` 追加：

```bash
ANALYTICS_DB_NAME=petgo_analytics
ANALYTICS_DB_USER=analytics_writer
ANALYTICS_DB_PASSWORD=<写入账号密码>
# 以下两项生产用默认值，可不写
# ANALYTICS_DB_READER_ROLE=analytics_reader
# ANALYTICS_DB_POOL_SIZE=3
```

staging `~/.env.petgo-stag` 追加（**四项都要写**，否则会回落到生产的库名 / 角色名）：

```bash
ANALYTICS_DB_NAME=petgo_analytics_stag
ANALYTICS_DB_USER=analytics_writer_stag
ANALYTICS_DB_PASSWORD=<stag 写入账号密码>
ANALYTICS_DB_READER_ROLE=analytics_reader_stag
ANALYTICS_DB_POOL_SIZE=2
```

- 主机 / 端口复用 `DB_HOST` / `DB_PORT`，不新增变量。
- 库已对 PUBLIC 收回 CONNECT：stag 配错成生产库名时，stag 账号连不进去，表现为管道停用 + 告警，不会写进生产库。

### 1.3 改 env 后必须重建容器

`docker restart` **不重读 env 文件**。改完 env 走正常部署脚本（`scripts/deploy-backend.sh` / `scripts/deploy-backend-stag.sh`，会 `docker rm` 后重新 `docker run --env-file`），或手动 `docker rm -f` 后按脚本里的 `docker run` 重建。

### 1.4 确认管道已启用

启动日志（容器日志或 `~/petgo-server*/logs/petgo.log`）里找 `com.tailtopia.analytics.config.AnalyticsBootstrap`：

- 正常：`analytics migrations: N executed, target version ...` 和 `analytics pipeline enabled: analytics migrations applied`。首次启动 N≥1，之后为 0。
- 停用：WARN `analytics pipeline disabled: reason=DB_UNAVAILABLE|MIGRATION_FAILED cause=<异常类名链>, SQLState <码>`，同时 TECH 飞书群收到「【埋点管道已停用】」（同一 UTC 日只发一次）。
  - `DB_UNAVAILABLE`：取不到连接。SQLState `3D000` = 库不存在（没跑 §1.1 或库名配错）；`28P01` = 密码错；`53300` = 连接数超限。
  - `MIGRATION_FAILED`：埋点 Flyway 失败。查埋点库 `flyway_schema_history` 里 `success=false` 的行；`ANALYTICS_DB_READER_ROLE` 不是合法角色名也会落到这里；cause 是 `FlywaySqlUnableToConnectToDbException` 时多半是 `ANALYTICS_DB_POOL_SIZE` 被设成了 1（埋点 Flyway 同时要 2 条连接，池不得小于 2）。
- 停用期间：业务完全不受影响，`/actuator/health` 仍 UP（`db` 健康项只看业务库）；埋点接收返回 503、埋点定时任务跳过。修好后重建容器即恢复（状态只在启动时判定）。

核对迁移历史在埋点库里：

```bash
docker exec petgo-postgres psql -U petgo -d petgo_analytics \
  -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank'
```

业务库 `petgo` / `petgo_stag` 里不应出现 `event_catalog`、`metric_definitions`。

## 2. 过期分区删除与重算明细（Story 1.7）

### 2.1 过期分区删除（自动，无需操作）

`PartitionMaintenanceJob`（每天 UTC 00:05 + 启动后一次）在预建之后删过期分区：

- 明细层 `events_detail_pYYYYMMDD`（WIB 日）：早于「WIB 今天 − 3 个自然月」的删掉。
- 原始层 `events_raw_pYYYYMMDD`（UTC 日）：早于「UTC 今天 − 35 天」**且**明细进度游标 `pipeline_progress.detail` 已越过那一天的才删；游标没越过的保留，TECH 群收到「【埋点原始层过期分区未删：明细进度落后】」——先查明细转换为什么卡住，修好后下一次维护自动删。`events_raw_pdefault` 永不删。
- 每个分区一个事务，`DROP` 等表锁最多 5 秒；拿不到（AI 长查询 / 写入在途）就跳过并告警「【埋点过期分区删除失败】」，第二天自动重试，不影响接收。
- 有重算请求处于 `RUNNING` 时，原始层当天不删（重算正在读它），第二天再删。

### 2.2 重算明细

口径修正（如属性白名单调整）、补登记了之前未登记的事件、或怀疑某几天明细漏转时，从原始层重算这几天的明细。**只能重算原始层保留期（35 天）内的日期**；更早的请求会被拒绝（`REJECTED` / `RAW_RETENTION_EXCEEDED`）并告警。

用**写入账号**连埋点库（密码交互输入，不进 shell history），插一行请求（日期是 **WIB 日期，含两端**）：

```bash
# 生产；staging 换成 analytics_writer_stag / petgo_analytics_stag
docker exec -it petgo-postgres psql -h localhost -U analytics_writer -d petgo_analytics
```

```sql
INSERT INTO detail_recompute_requests(from_date, to_date) VALUES ('2026-11-01','2026-11-07');

-- 看进度：PENDING → RUNNING → DONE / FAILED / REJECTED
SELECT id, from_date, to_date, status, error_code, requested_at, started_at, done_at
FROM detail_recompute_requests ORDER BY id DESC LIMIT 5;
```

- `DetailRecomputeJob` 每分钟看一次，一次处理一条（按 id 顺序）。**建议单次 ≤ 7 天**；更长的拆成几条。
- 🔴 **重算期间明细层暂停更新**：`RUNNING` 时实时转换（`DetailBuilder`）整轮跳过，重算完自动追上（数据在原始层，不丢）。
- 重算按**当前**事件清单判定事件是否已登记、保留哪些属性键；原始层的 `unknown_event` 标记不看（补登记的事件会进明细）。
- 重算先清空区间内的明细行再从原始层重写，结果与首次处理一致；极少数行的 `event_date` 可能因「当时 / 现在已建分区不同」而不同（规则本身的结果）。
- `FAILED`（`error_code` 为异常类名）不自动重试：查后端日志 `analytics detail recompute failed`，修好后
  `UPDATE detail_recompute_requests SET status = 'PENDING' WHERE id = <id>;` 再跑。该区间明细在重跑完成前可能不完整。
- 后端在 `RUNNING` 时重启：重启后从头重跑这条请求（幂等）。

## 3. 存储水位与停写熔断（Story 1.10）

### 3.1 后端两道线（自动，无需操作）

| 机制 | 频率 | 阈值（`analytics.storage.*`） | 动作 |
|---|---|---|---|
| `StorageWatermarkJob` | 每天 WIB 04:30 | `alert-bytes` 40GB；`month-events-hint` 6,000 万；`hint-months` 3 | 写 `storage_watermarks` 一行（同日重跑覆盖）；超 40 GB → TECH「【埋点库容量超线】」；月事件量达标或按月增速外推 3 个月内到 40 GB → TECH「【埋点库容量提示】」，文案含「评估切回第三方」 |
| `IngestCircuitBreaker` | 每 5 分钟 + 启动即查 | `stop-bytes` 50GB | 超过 → `POST /api/v1/analytics/batch` 返回 429 + `Retry-After: 300`，服务端事件丢弃计数；降回 50 GB 及以下 → 自动恢复。熔断、恢复各告警一次 |

- 两者都只看**本环境连接的埋点库**（`pg_database_size(current_database())`）：生产后端看 `petgo_analytics`，stag 后端看 `petgo_analytics_stag`。同实例上业务库、另一环境埋点库的增长**不在**这两道线里 —— 由 §3.3 的主机整盘检查兜底。
- 熔断只拦新写入：内存队列里已有的事件照常写完；明细转换、分区维护、注销擦除照常运行（删分区正是降水位的手段）。
- 看水位（用**写入账号**；只读账号未被授予此表，架构 A8 白名单未列）：

```sql
SELECT snapshot_date, pg_size_pretty(db_bytes) AS db, pg_size_pretty(raw_bytes) AS raw,
       pg_size_pretty(detail_bytes) AS detail, events_on_day, month_events,
       pg_size_pretty(month_growth_bytes) AS growth_30d
FROM storage_watermarks ORDER BY snapshot_date DESC LIMIT 14;
```

- 口径：`snapshot_date` 是 WIB 运行日；`events_on_day` 计的是运行时刻已完整结束的前一个 UTC 日（WIB 04:30 运行 = `snapshot_date − 2` 那个 UTC 日的原始层分区）；`month_events` = 本 WIB 月各行 `events_on_day` 之和（某天任务失败没写行，那天的量不进累计）。

### 3.2 熔断后的处置

1. 查 `storage_watermarks`（上面的 SQL）看增长来源：原始层（`raw`）还是明细层（`detail`）涨得快、`events_on_day` 是否异常飙升（App 埋点 bug 刷量？）。
2. 释放空间：
   - 🔴 **`DELETE` 不会让 `pg_database_size` 下降**，只有 `DROP` 分区（或 `VACUUM FULL`，会长时间锁表，不推荐）才释放磁盘。
   - 手工删过期分区：先确认分区日期早于保留期（原始层 35 天且明细游标已越过、明细层 3 个月），用写入账号
     `ALTER TABLE events_raw DETACH PARTITION events_raw_pYYYYMMDD; DROP TABLE events_raw_pYYYYMMDD;`（明细层同理）。
     自动删除逻辑见 §2.1，手工删的规则一致。
   - 或缩短保留期（改代码 / 配置后重新部署，需另行拍板）。
3. 占用降回 50 GB 及以下后，**5 分钟内**下一次检查自动恢复接收，TECH 群收到「【埋点停写恢复】」。无需重启。
4. App 端在熔断期间按 `Retry-After` 保留重发，恢复后自动补传；服务端事件在熔断期间丢弃（无法重试），后端日志 `server analytics dropped` 有按原因的计数（熔断计在 `QUEUE_FULL` 下）。

### 3.3 主机整盘余量检查（运维项，不在后端实现）

后端容器看不到主机整盘余量；`petgo-postgres` 所在主机上业务库、两个环境的埋点库、日志、镜像共用同一块盘。**这是与后端 40 / 50 GB 水位相互独立的第二道防线**：后端水位只管「埋点库别涨太大」，整盘检查管「整台机器别写满」（不管是谁写的）。

- **安装位置**：`petgo-postgres` 所在主机（生产服务器）的 root crontab（`sudo crontab -e`），脚本放 `/usr/local/bin/petgo-disk-check.sh`（`chmod 700`，root 所有）。
- **检查对象**：PG 数据目录所在挂载点。数据卷是 docker volume `petgo_pgdata`，先查它在主机上的路径：
  `docker volume inspect petgo_pgdata --format '{{ .Mountpoint }}'`（一般在 `/var/lib/docker/volumes/...`，即根分区或 docker 数据盘）。
- **阈值**：可用空间 **< 15 GB** 告警。理由：埋点库到 50 GB 才停写，15 GB 余量给业务库、日志、镜像拉取和同机其他服务留出缓冲；如磁盘总量变化，按「总量 − 业务库预期 − 50 GB − 安全余量」重算。
- **频率**：每 30 分钟。
- **告警渠道**：飞书技术群（与后端 TECH 渠道同一个群）。webhook URL / 签名密钥放主机上 root-only 文件（如 `/root/.petgo-disk-alert.env`，`chmod 600`），**不进仓库**。
- **示例**（按实际挂载点改 `MOUNT`；若技术群机器人开了签名校验，需按飞书文档补 `timestamp` / `sign` 字段）：

```bash
#!/usr/bin/env bash
# /usr/local/bin/petgo-disk-check.sh —— 主机整盘余量检查（runbook-analytics-pipeline §3.3）
set -euo pipefail
source /root/.petgo-disk-alert.env          # 提供 LARK_TECH_WEBHOOK
MOUNT=/var/lib/docker                        # PG 数据目录所在挂载点
THRESHOLD_GB=15
avail_kb=$(df -P "$MOUNT" | awk 'NR==2 {print $4}')
avail_gb=$(( avail_kb / 1024 / 1024 ))
if [ "$avail_gb" -lt "$THRESHOLD_GB" ]; then
  curl -sS -X POST -H 'Content-Type: application/json' \
    -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"【主机磁盘余量不足】$(hostname) ${MOUNT} 可用 ${avail_gb} GB < ${THRESHOLD_GB} GB。查 docker system df、PG 各库大小（SELECT datname, pg_size_pretty(pg_database_size(datname)) FROM pg_database）、日志。\"}}" \
    "$LARK_TECH_WEBHOOK" >/dev/null
fi
```

```cron
*/30 * * * * /usr/local/bin/petgo-disk-check.sh >/dev/null 2>&1
```

- **验证方法**：①手工执行一次脚本确认无报错；②把 `THRESHOLD_GB` 临时改成大于当前可用值（如 99999）再跑一次，确认技术群收到告警，然后改回；③`sudo crontab -l` 确认条目存在；④一天后看 `grep CRON /var/log/syslog`（或 `journalctl -u cron`）有执行记录。
- 本检查不去重：余量持续不足时每 30 分钟提醒一次，处理完自动停止。

## 4. 指标汇总与重跑汇总（Story 4.1）

### 4.1 每日汇总（自动，无需操作）

`RollupJob` 每天 WIB 03:00（晚于 02:00 的注销擦除）运行，`asOf` = WIB 昨天：

- 每个指标族（`activity`、`install`，后续 story 追加）按 `agg_progress.last_done_date` 续算到 `asOf`，**每次至少重算最近 3 天**（晚到事件，D-6）。首次运行只算最近 3 天，更早的历史用下面的重跑回补。
- 每个（指标族, WIB 日）一个短事务：写汇总表与推进 `agg_progress` 同一事务提交。
- 某指标族某日失败：该指标族本轮停止、进度不推进，`agg_progress.last_error` 记异常类名，TECH 群收到「【埋点汇总失败】」；下一次运行自动从缺的日期补。其他指标族照常。
- 进度落后超过明细保留期（92 天）时只从保留期下限续算，TECH 群收到「【埋点汇总进度落后超过明细保留期】」（被跳过的日期明细已清理，无法再算）。
- 数据截至哪天：`SELECT family, last_done_date, last_run_at, last_error FROM agg_progress;`

### 4.2 重跑汇总

口径修正、明细重算（§2.2）之后、或回补首次上线前的历史时，按 WIB 日期区间重跑汇总。**只能重跑明细保留期内**（`from ≥ max(昨天 − 92 天, 今天 − 3 个月)`——后者是明细层实际保留的最早分区；`to ≤ 昨天`），否则 `REJECTED` / `OUT_OF_RANGE`；指标族名写错为 `UNKNOWN_FAMILY`。被拒都会告警。

用**写入账号**连埋点库（同 §2.2），插一行请求（日期是 **WIB 日期，含两端**；`families` 不写 = 全部指标族）：

```sql
INSERT INTO rollup_rerun_requests (from_date, to_date) VALUES ('2026-11-01', '2026-11-07');
INSERT INTO rollup_rerun_requests (from_date, to_date, families) VALUES ('2026-11-01', '2026-11-07', '{install}');

-- 看进度：PENDING → RUNNING → DONE / FAILED / REJECTED
SELECT id, from_date, to_date, families, status, error_code, requested_at, started_at, done_at
FROM rollup_rerun_requests ORDER BY id DESC LIMIT 5;
```

- `RollupRerunJob` 每分钟看一次，一次处理一条；每日汇总正在跑时等它跑完。
- 重跑是幂等的（先删当天再写），`agg_progress.last_done_date` 只前进不倒退。
- `FAILED`（`error_code` 列出失败的指标族）不自动重试：查后端日志 `analytics rollup unit failed`，修好后
  `UPDATE rollup_rerun_requests SET status = 'PENDING' WHERE id = <id>;` 再跑。
