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

- 写入账号 `rolconnlimit=5`、`rolconfig={statement_timeout=120s}`；只读账号 `rolconnlimit=3`、`rolconfig={statement_timeout=30s,idle_in_transaction_session_timeout=60s,default_transaction_read_only=on}`（Story 4.8 补；1.1 时已建过的账号重跑一次脚本即可补上）。
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

- 每个指标族（`activity`、`install`、`dau_reconcile`、`retention`、`install_profile`、`placement`、`push`、`toko`、`cart`、`product`、`ratio`；指标族与汇总表、口径条目的对应见 `analytics/metric-definitions.yaml` 各条目的 `tables` / `sql_path`）按 `agg_progress.last_done_date` 续算到 `asOf`，**每次至少重算最近 3 天**（晚到事件，D-6）。首次运行只算最近 3 天，更早的历史用下面的重跑回补。
- 例外：`retention`（`agg_cohort_retention`，按组日期 X 一行）每次重算最近 **16** 天——W1 要看到 X+13，再留 3 天晚到；未满 N 天的列为空（`X+N > asOf`）。重跑旧组日期时成熟度仍按当前的 `asOf` 判断，已有值不会变回空。
- `install_profile`（`agg_monthly_install_profile`）没有日表，按 WIB 自然月写：与重算窗口相交的月份都重算，月中是「截至昨天」的值（`is_complete = false`），月末次日起写终值；月初早于保留期下限的月份重跑时跳过（明细已清理）。
- `placement`（Story 4.4，`agg_daily/weekly/monthly_placement`）每次重算最近 **10** 天（7 天带单窗口 + 3 天晚到）；`push`（`agg_daily/weekly/monthly_push`）重算 3 天。两者的周表 / 月表与重算窗口相交的周期都重算（周期内重新去重，不是日表相加），`is_complete = false` 为「截至昨天」的值。每个单元先在埋点事务**之外**读业务库（运营位配置、已付款订单、运营推送活动，窄查询只取 token 与计数列），再开埋点事务写汇总；业务库读失败按该单元失败处理（告警、下次补）。
- `toko`（Story 4.5，`agg_daily/weekly/monthly_toko`）与 `cart`（Story 4.6，`agg_daily_cart`）每次重算最近 **8** 天（7 天漏斗 / 观察窗 + 当天），`is_final = false` 的行窗口未关闭、之后随回补刷新；`toko` 的周表 / 月表只在周期结束后计算。`product`（Story 4.6，`agg_daily_product`）重算 3 天，库存列是该行首次写入时的快照、回补不覆盖。`ratio` 见 §5.3。三者都先在埋点事务之外窄查业务库（已付款订单、订单行、商品 / SKU token 与库存）。
- `push` 的运营推送累计打开人数只计发送后 `ANALYTICS_ROLLUP_PUSH_CAMPAIGN_DAYS`（默认 14）天内的打开；该 env 写错不影响启动，只停用累计打开人数（列写空）并在启动就绪时 TECH 告警「【埋点配置非法】」，改对后重建容器即恢复。
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

## 5. GA4 快照与日活对账（Story 4.2）

### 5.1 GA4 快照（自动）

`Ga4DailySnapshotJob` 每天 WIB 02:30（早于 03:00 的汇总）用日报同一套 GA4 凭证（`GA4_PROPERTY_ID` / `GA4_SA_KEY_B64`，不加新 env）拉 GA4 全版本日活与 V1.5.0+ 日活，写 `ga4_daily_snapshot`：

- 每次回拉 D−1、D−2、D−3 覆盖旧值（GA4 有处理延迟），并补拉最近 14 天内缺行的日期。
- GA4 未配置 → 跳过、只记 INFO；某日拉取失败 → 旧行不动，TECH 群收到「【GA4 日活快照失败】」，下次运行自动补拉。
- 版本阈值 `analytics.reconcile.min-app-version`（默认 `1.5.0`）；GA4 属性时区必须是雅加达。
- 配置值写错（如 `ANALYTICS_GA4_SNAPSHOT_ENABLED=flase`、补拉天数不在 3–92、版本阈值格式不对、`ANALYTICS_DAU_SOURCE` 不是 `GA4` / `SELF`）**不会让后端起不来**：快照任务停用（日活来源回退 `GA4`），启动日志打 WARN，TECH 群收到「【埋点配置非法】」。改正 env 后重建容器即可。

🔴 **stag**：GA4 属性只有生产包的数据，stag 对出来没有意义 —— `~/.env.petgo-stag` 加 `ANALYTICS_GA4_SNAPSHOT_ENABLED=false`（改 env 后重建容器，见 §1.3）。stag 的 `agg_daily_dau_reconcile` 只有自建列，状态全部为 `NO_GA4`。

### 5.2 日活对账（自动，只存库不推飞书）

指标族 `dau_reconcile`（依赖 `activity`）每天写 `agg_daily_dau_reconcile`，并整表重算观察期与状态：

```sql
SELECT stat_date, self_reconcile_dau, ga4_new_version_dau, diff_pct, coverage, status, observation_start
FROM agg_daily_dau_reconcile ORDER BY stat_date DESC LIMIT 35;
```

- 观察期 = 覆盖率首次 ≥ 90% 的那天起 4 周；期内 |差值| ≤ 10% 为 `NORMAL`，否则 `ABNORMAL`；之前 `NOT_STARTED`、之后 `ENDED`；GA4 没拉到为 `NO_GA4`。
- 改用自建日活由 Dai 决定：设 `ANALYTICS_DAU_SOURCE=SELF`（默认 `GA4`）并重建容器；同一次发版在 `analytics/metric-definitions.yaml` 的 S1 条目填 `dau_source_switch: "YYYY-MM-DD"`（WIB 切换日）并在 changelog 写原因（Story 4.7）。`SELF` 而该字段为空时启动打 WARN、TECH 群收到「【埋点口径缺切换日期】」（不阻断启动，同一 WIB 日只发一次）。

### 5.3 以日活为分母的比率（Story 4.7，自动）

指标族 `ratio`（依赖 `activity`，每次重算最近 3 天）每天写 `agg_daily_ratio`：分子（S4 Toko 付款人、产品 #3 非 Toko 付费解锁人、内容 #1 新用户，另存送礼 / 充值参照列）由业务库窄查询得出，分母按 `ANALYTICS_DAU_SOURCE` 取 GA4 全版本日活或自建日活，`dau_source` 列记每行实际来源。

```sql
SELECT stat_date, dau, dau_source, toko_buyers, s4_ratio, nontoko_payers, p3_ratio, new_users, returning_users
FROM agg_daily_ratio ORDER BY stat_date DESC LIMIT 35;
```

- 分母缺失（GA4 当天没拉到、stag 无 GA4）时 `dau` / 两个比率 / `returning_users` 为空、分子照写；GA4 补拉成功后 3 天回补窗口内自动填上。更早缺的日子按 §4.2 插 `rollup_rerun_requests`（`families` = `{ratio}`，只能在明细保留期内）手动重跑。
- 月比率 = 当月每日比率的算术平均（跳过分母为空的日子），由日表现算，不建月表。
- 超过 14 天仍缺的 GA4 日期不再自动补拉：临时设 env `ANALYTICS_GA4SNAPSHOT_BACKFILLDAYS=<天数>`（Spring 宽松绑定，3–92）放大补拉窗口、重建容器等一次 02:30 运行后撤掉。对账表的 GA4 数会在下一次 03:00 汇总时随整表重算刷新，无需手动重跑。

## 6. 上线检查清单（首次在某环境启用埋点管道）

部署含埋点管道的后端之前 / 之后逐项打勾（生产、stag 各一遍）：

1. **建库建号**：§1.1 已执行；回显核对通过（只读账号含 Story 4.8 的三项会话默认值）。
2. **后端 env**：§1.2 各项已写，改完重建容器（§1.3）；启动日志确认管道已启用（§1.4）。
3. 🔴 **原始层最早数据早于明细初始分区时，明细游标会卡住**（Story 1.6 复审 R2）：明细层首条迁移只建「迁移当天（WIB）前后各 7 天」的分区，`pipeline_progress` 初始游标是 1970-01-01。若该环境的 `events_raw` 在明细层上线前已积累了更早的数据（原始层保留 35 天），DetailBuilder 会从最早那行开始处理、找不到对应日分区，整片回滚并每天告警一次，进度永久不动。**上线前**用写入账号核对：
   ```sql
   SELECT min(received_at), (min(received_at) AT TIME ZONE 'Asia/Jakarta')::date AS min_wib_date FROM events_raw;
   SELECT min(c.relname) AS earliest_detail_partition
   FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = 'events_detail'::regclass;
   ```
   若 `min_wib_date` 早于最早的 `events_detail_pYYYYMMDD`：二选一 ——（a）按告警里的日期手工补建明细分区（例：`CREATE TABLE events_detail_p20261101 PARTITION OF events_detail FOR VALUES FROM ('2026-11-01') TO ('2026-11-02');`，每天一个，用写入账号；超过明细保留期 3 个月的日期不要补）；（b）放弃更早的原始数据，把游标设到初始分区起点：`UPDATE pipeline_progress SET last_received_at = '<最早明细分区日期> 00:00:00+07' WHERE pipeline = 'detail';`。两者都属运维动作，先问 Dai。
4. **首日汇总**：次日 WIB 03:00 后 `SELECT family, last_done_date, last_error FROM agg_progress;`，11 个指标族都推进到昨天、`last_error` 为空；更早的历史按 §4.2 重跑。
5. **口径同步**：`SELECT count(*) FROM metric_definitions;` 等于 `analytics/metric-definitions.yaml` 的条目数（含 `_rules`），`team_ref` / `tables` 非空（Story 4.8）。
6. **AI 查数接入**（Story 4.8，等 Dai 指令）：按 `runbook-ai-query-agent.md` §2 第 6 步 / §8.1 给 agent 配埋点库只读账号，`verify_analytics_reader.sql` 全部通过，§4 第 8、9 项检查通过。

## 7. AI 查数只读账号（Story 4.8，FR-27）

- 账号：生产 `analytics_reader` / stag `analytics_reader_stag`，`NOINHERIT`、`CONNECTION LIMIT 3`、会话默认 `statement_timeout=30s`、`idle_in_transaction_session_timeout=60s`、`default_transaction_read_only=on`；另一环境的埋点库连不上（埋点库已 `REVOKE ALL ... FROM PUBLIC`，只给本环境只读账号 CONNECT），本库的 TEMP / CREATE 也随之收回。⚠️ 业务库 `petgo` / `petgo_stag` 仍保留 PostgreSQL 默认的 PUBLIC CONNECT（收回属共享库变更，须 Dai 另行确认，同 `claude_bot_stag.sql` 末尾说明），所以只读账号**能连上业务库但没有任何表权限**，读不到业务数据。
- 表权限：迁移里按白名单逐表 `GRANT SELECT ON <表> TO ${readerRole}`（明细 `events_detail`、`identity_links`、`v_events_person`、全部 `agg_*`、`agg_progress`、`ga4_daily_snapshot`、`event_catalog`、`metric_definitions`），**不用** `ALTER DEFAULT PRIVILEGES`。`events_raw` 及其分区、`pipeline_progress`、`detail_recompute_requests`、`storage_watermarks`、`rollup_rerun_requests` 不授权；擦除登记在业务库 `analytics_erasure_outbox`，埋点库只读账号在业务库无表权限、读不到；注意 AI 的业务库账号 `claude_bot` / `claude_bot_stag` 按其「全表 SELECT + 默认授权」能读到该表（只有内部 user_id 与哈希 user_key，属业务库账号既有范围）。新增表必须在迁移里决定是否授权（CI：`AnalyticsReaderGrantsTextTest`）。
- 核验：`ai-query-agent/deploy/sql/verify_analytics_reader.sql`（见 `runbook-ai-query-agent.md` §2 第 6 步）。
- 口径唯一来源：`petgo-backend/src/main/resources/analytics/metric-definitions.yaml`，启动时同步进 `metric_definitions`；新增汇总表必须有条目在 `tables` 里引用它（CI：`MetricDefinitionsCoverageTest`）。

## 8. 管道加固：写库线程、请求超时、配置非法（Story 4.9）

### 8.1 告警键表（本节相关，TECH 渠道）

| 去重键 | 触发 | 处置 |
|---|---|---|
| `analytics-ingest-flusher-error:<UTC 日>` | 写库线程某一轮抛出任何异常（含 `OutOfMemoryError` 等 Error）。线程不退出，退避 1 秒继续；若发生在写库时，当前这批丢弃（不重试） | 看后端日志 `analytics ingest flusher round failed`（只有异常类名和消息）；OOM 先查堆大小与 `analytics.ingest.queue-max-bytes` |
| `analytics-ingest-flusher-restarted:<UTC 日>` | 看门狗发现写库线程不在了（不在停机过程中），已重启 | 查重启前后的 ERROR 日志，确认终止原因 |
| `analytics-ingest-flusher-stalled:<UTC 日>` | 线程在，但超过 `stall-threshold`（默认 5 分钟）没完成一轮。**不重启** | 多半卡在埋点库：查 `pg_stat_activity` 里 analytics 池的会话（锁等待 / 连接耗尽 / 网络假死）；队列满 64 MB 后接收端 429 |
| `analytics-config-invalid:<UTC 日>` | 应用就绪时发现埋点配置非法（汇总、存储水位、接收、擦除超时、数据源池参数、请求查询超时、调度 cron / 时长） | 文案列出键名与影响；改正 env 后**重建容器**（§1.3） |
| `analytics-ga4-config-invalid` / `analytics-rollup-push-config-invalid` | 4.2 / 4.4 既有：GA4 快照（含 Story 4.9 起的 `analytics.ga4-snapshot.cron`）/ 推送天数配置非法 | 同上 |

看门狗每 `analytics.ingest.watchdog-interval`（默认 60 秒）只做内存检查，不碰库、不占连接；管道停用时跳过。`/actuator/health` 不受埋点影响（`db` 健康项只看业务库，有意为之）。

### 8.2 请求线程查询超时

`POST /api/v1/analytics/batch` 在请求线程上只有一处查埋点库：身份关联表查找（`IdentityLinkRepository.findUserId`）。它走单独的 `analyticsRequestJdbcTemplate`（同一个池），客户端超时 `analytics.datasource.request-query-timeout`（默认 2s）。超时 / 埋点库不可用 → 该批需要查表的事件剥离身份并标记 `identity_unverified`，接口照常 202。**请求线程最坏等待 = 5s 拿连接 + 2s 查询**。
后台 job（汇总、擦除、明细、分区、水位）与写库线程仍用不设客户端超时的模板，靠写入账号的 `statement_timeout`（120s，汇总 / 擦除事务内放宽到 10 / 30 分钟）兜底。
⚠️ 残余风险：JDBC 查询超时靠向 PG 发取消请求实现；若到埋点库的网络完全假死（取消请求也到不了），读操作仍会卡到 TCP 超时。URL 未加 `socketTimeout`（会误杀长事务的汇总 / 擦除），如遇到再按连接拆池处理。

### 8.3 配置表：值非法时的行为

所有键写错（格式非法、负数、`flase`、非法 cron）都**不阻断后端启动**；WARN 日志与就绪告警只含键名、不含值。定时任务由 `AnalyticsSchedulingConfig` 编程式注册，`"-"` 表示不注册。

| 键（`analytics.` 下） | env | 默认 | 值非法时 |
|---|---|---|---|
| `rollup.enabled` / `cron` / `statement-timeout` / `install-lookback-days` / `retention-days` / `rerun-fixed-delay-ms` / `rerun-initial-delay-ms` | — | `true` / `0 0 3 * * *` / `600s` / 92 / 92 / 60000 / 60000 | **停用汇总**：每日汇总与手动重跑都不注册、手工调用也跳过；`analytics-config-invalid` 告警 |
| `rollup.push-campaign-days` | `ANALYTICS_ROLLUP_PUSH_CAMPAIGN_DAYS` | 14 | 只停用推送累计打开人数（4.4 既有） |
| `ga4-snapshot.enabled` / `cron` / `backfill-days`、`reconcile.min-app-version` | `ANALYTICS_GA4_SNAPSHOT_ENABLED` | `true` / `0 30 2 * * *` / 14 / `1.5.0` | 停用 GA4 快照（cron 非法时不注册）；4.2 既有告警 |
| `dau-source` | `ANALYTICS_DAU_SOURCE` | `GA4` | 回退 GA4（4.2 既有） |
| `storage.alert-bytes` / `stop-bytes` / `month-events-hint` / `hint-months` / `breaker-check-ms` / `breaker-initial-delay-ms` / `breaker-retry-after-seconds` / `watermark-cron` | — | 40GB / 50GB / 6000 万 / 3 / 300000 / 0 / 300 / `0 30 4 * * *` | 该键回退默认；其中 `stop-bytes` / `breaker-*` 非法时**只停用「按水位停收」**（熔断不再打开，接收照常），其余键非法不影响熔断；水位记录与水位告警照常 |
| `ingest.*`（请求 / 单条 / 批大小、队列上限、Retry-After、写库批量 / 间隔、停机等待、`rate-limit.*`） | — | 见 application.yml | 该键回退默认，接收照常 |
| `ingest.watchdog-interval` / `stall-threshold` | `ANALYTICS_INGEST_WATCHDOG_INTERVAL` / `ANALYTICS_INGEST_STALL_THRESHOLD` | `60s` / `5m`（纯数字按秒） | 回退默认 |
| `datasource.request-query-timeout` | `ANALYTICS_REQUEST_QUERY_TIMEOUT` | `2s`（纯数字按秒） | 回退 2s |
| `datasource.hikari.*`（`maximum-pool-size` 等） | `ANALYTICS_DB_POOL_SIZE` | 池 3、`connection-timeout` 5000、`initialization-fail-timeout` -1 | 整组按默认值建池 |
| `erasure.statement-timeout` | — | `30min` | 回退 30min（擦除是合规承诺，不停用） |
| `partition.cron` / `unknown-event-alert.cron` / `erasure.cron` | — | `0 5 0 * * *`（UTC）/ `0 30 1 * * *`（UTC）/ `0 0 2 * * *`（WIB） | 按默认调度照常注册 |
| `detail.fixed-delay-ms` / `initial-delay-ms`、`recompute.fixed-delay-ms` / `initial-delay-ms` | — | 60000 | 按默认 60000 毫秒照常注册 |
| `datasource.url` / `username` / `password` / `reader-role`、`app-env`、`catalog.*` | 见 §1.2 | — | 字符串，不存在格式问题；库连不上 / 清单无效由 1.1 / 1.2 的停用逻辑处理 |
