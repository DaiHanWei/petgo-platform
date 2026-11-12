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
