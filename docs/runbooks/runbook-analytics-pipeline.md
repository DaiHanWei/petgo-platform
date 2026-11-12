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
