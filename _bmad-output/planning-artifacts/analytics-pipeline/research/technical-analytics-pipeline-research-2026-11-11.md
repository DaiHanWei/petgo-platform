---
stepsCompleted: [1]
inputDocuments: []
workflowType: 'research'
lastStep: 1
research_type: 'technical'
research_topic: '自建埋点数据管道（替代 PostHog）：接收、存储、分层与统计'
research_goals: '在不破坏主业务库的前提下，选出能承载当前量级并有 10 倍余量的埋点接收与存储分层方案，供后续 PRD / 架构使用'
user_name: 'Dai'
date: '2026-11-11'
web_research_enabled: true
source_verification: true
---

# Research Report: technical

**Date:** 2026-11-11
**Author:** Dai
**Research Type:** technical

---

## Research Overview

[Research overview and methodology will be appended here]

---

<!-- Content will be appended sequentially through research workflow steps -->

## 技术调研范围确认

**调研主题：** 自建埋点数据管道（替代 PostHog）：接收、存储、分层与统计
**调研目标：** 在不破坏主业务库的前提下，选出能承载当前量级并有 10 倍余量的埋点接收与存储分层方案，供后续 PRD / 架构使用

**调研范围：**

- 架构分析：原始 / 明细 / 汇总分层，保留期与冷热分离，数据放在哪（同库 / 独立库 / 独立实例 / 独立服务器）
- 存储选型：Postgres 原生时间分区、TimescaleDB 等 PG 扩展、ClickHouse 等专用分析库
- 接收端：批量、鉴权与防刷、限流、幂等去重、离线重放、时间口径
- 集成：App 从 PostHog SDK 切换、后端服务端事件、AI 查数衔接
- 性能：300 万 / 750 万 / 7,500 万条每月三个量级下的写入、存储与查询压力，对共用 Postgres 的影响

**调研方法：**

- 以当前公开资料核实，关键结论多源交叉
- 不确定的信息标注可信度
- 结合本项目约束（禁止新中间件、生产与 stag 同机共用 petgo-postgres）

**范围确认：** 2026-11-11


## 技术概况（技术栈分析）

### 0. 本项目现状与约束（2026-11-11 生产只读核实）

| 项 | 现状 | 对选型的含义 |
|---|---|---|
| 主机 | 8 核、23G 内存（约 15G 可用）、磁盘 193G（余 131G）；同机十余个容器（生产 / stag 后端、logistic、merchant、两套 MySQL 等） | 不宜再放吃内存的大组件；磁盘是硬上限 |
| 数据库 | `petgo-postgres` = 官方 `postgres:16-alpine`，**无任何扩展**（无 pg_partman / TimescaleDB / pg_cron），`shared_buffers=128MB`、`max_connections=100` 均为默认 | 用扩展须换镜像并重建生产与 stag 共用的库容器；纯原生方案零基建改动 |
| 业务库体量 | 生产 `petgo` 476MB，stag 31MB | 埋点几周内就会超过业务库，**必须与业务数据隔离（至少独立 database）并设保留期** |
| 现有埋点 | App 191 种事件经 PostHog SDK（批量 20），客户端已有 PII / 自由文本剥离；后端少量服务端事件；`user_active_days` 自算日活；GA4 管游客日活 | 脱敏规则可原样复用；日活口径已有，不依赖新系统 |
| 护栏 | 禁止 MQ / 通用缓存层 / 新中间件；异步只用 `@Async` + DB 状态机 | ClickHouse / Kafka 类方案需 Dai 明确破例 |
| 消费方 | ops AI 查数已上生产，直连生产 Postgres（只读账号 `claude_bot`） | 数据在 Postgres 内即可被 AI 直接问；放在别的引擎需另接 |

### 体量估算

- 现量：登录日活 700–1,200（峰值 1,595），按每人每日 50–150 条事件、游客另加 ~50%，约 **10–25 万条 / 天，300–750 万条 / 月**。
- 10 倍余量：**3,000–7,500 万条 / 月**。
- 单条原始事件（含 JSONB 属性、设备上下文）按 0.5–1KB 估（_可信度：中，需用 `pg_column_size` 实测样本校准_）→ 现量 **1.5–7.5GB / 月**，10 倍时 **15–75GB / 月**。磁盘余 131G：原始层**不能无限保留**，须分区 + 保留期（或压缩）。

### 存储与计算技术

| 方案 | 要点 | 与本项目的契合度 |
|---|---|---|
| **A. Postgres 原生声明式分区**（按天 / 按月 range 分区） | PG 16 内置，无需扩展；过期数据直接 `DETACH` / `DROP` 整个分区，删除零成本；时间列用 **BRIN 索引**体积极小（适合按时间顺序写入的表）；主键 / 唯一约束必须包含分区键且只在单分区内唯一 | **最高**：零新组件、零镜像改动；分区由后端 `@Scheduled` 预建 / 清理即可（不依赖 pg_partman / pg_cron） |
| A'. 原生分区 + pg_partman | pg_partman 5.x 完全基于原生分区，只是自动化建 / 删分区；需安装扩展（换镜像） | 中：省一点代码，但要动共用库容器；本项目用应用调度器即可替代 |
| **B. TimescaleDB**（PG 扩展：hypertable、列存压缩、连续聚合） | 自动分区、连续聚合（增量汇总）好用；**列存 / 压缩只在 Community Edition（TSL 许可）里**，Apache 版没有；需换成带扩展的镜像 | 中低：压缩收益大（宣传 80–97%，_可信度低，未见官方 2025 数字_），但要换掉生产与 stag 共用的库镜像，且 TSL 许可需法务确认（自用一般允许） |
| B'. 列存扩展：pg_duckdb / Citus columnar / Hydra | pg_duckdb 把 DuckDB 嵌进 PG 进程做快速分析，单机；Citus columnar 为 AGPL-3.0；Hydra 已转向 pg_duckdb | 低：都要换镜像；嵌入式分析引擎与业务库同进程，资源争用风险 |
| **C. ClickHouse**（专用列式分析库） | 分析性能与压缩最好；单机最低 4G 内存，实际工作负载建议 8G 起、持续写入 16–32G；与其他服务同机时内存与磁盘 IO 争用是常见瓶颈 | 低（现阶段）：**属新中间件需破例**；本机已十余容器，再放 8–16G 内存的组件风险高；当前量级用不上它的能力 |
| D. 现成自托管产品（PostHog 自托管 / Plausible / Umami / Jitsu 等） | PostHog 自托管 = ClickHouse + Kafka + Redis + PG，三十多个组件，官方不提供支持也无打标签发布；Plausible 同样基于 ClickHouse | 很低：与「去掉 PostHog、数据自有、少组件」的初衷相反 |

### 写入（摄取）技术

- **批量写入远快于逐条**：同一基准中，逐条 INSERT 约 983 行 / 秒，多值批量 INSERT 约 3.3 万行 / 秒，COPY 约 25.6 万行 / 秒（单次测试、硬件特定，_可信度：中_）。现量峰值折合每秒不到 10 条，**10 倍余量也只有每秒数十到上百条**，批量 INSERT 绰绰有余；接收端应「攒批写」而不是每条一个事务。
- **业界接收协议参考**（Segment Tracking API）：单事件 ≤32KB、批量请求 ≤500KB；每条带客户端生成的唯一 `messageId`；时间分 `timestamp`（事件发生）/ `sentAt`（客户端发送，用于校正时钟偏差）/ `receivedAt`（服务端收到）三种；超限回 400，限流回 `Retry-After`。本项目可直接沿用这套字段语义。

### 汇总（分层）计算技术

- PG 原生物化视图**只能全量刷新**，`REFRESH ... CONCURRENTLY` 还要求唯一索引；不适合随数据增长频繁刷新。
- 增量汇总的常见做法是 **rollup 表 + 定时任务按时间窗增量写入**（只算新到的时间段），难点是不重算、不漏算（用「已处理到的水位线」解决）。调度可用 pg_cron 或**应用侧调度器**——本项目已有 `@Scheduled` + DB 状态机 + advisory lock 的成熟用法，无需 pg_cron。
- 扩展 pg_ivm 可做物化视图增量维护（需装扩展，不推荐）。

> **不纳入的方案（2026-11-11 Dai 确认「暂时不用」）：Firebase / GA4 + BigQuery 导出。** 控制台报表免费但只有汇总；原始事件需 BigQuery 导出——每日批量导出免费但标准属性上限 100 万条 / 天（10 倍量级会超、超限暂停且不补），流式导出 $0.05/GB；BigQuery 沙盒存储 10GB；数据在 Google 侧，AI 查数无法直接查。来源：[GA4 BigQuery Export](https://support.google.com/analytics/answer/9358801?hl=en)、[GA4 事件采集限制](https://support.google.com/analytics/answer/9267744?hl=en)。

### 本步小结（供后续步骤验证）

1. **现量与 10 倍余量都在「单机 Postgres + 原生分区」能力范围内**，瓶颈不在写入吞吐，而在**磁盘增长**与**与业务库的资源隔离**。
2. 最贴合约束的路线是 **A：原生分区 + 应用侧调度建 / 删分区 + BRIN + rollup 表**；B（TimescaleDB）的唯一明显优势是压缩，代价是换共用库镜像；C（ClickHouse）留作「量级再涨一个数量级或需要复杂即席分析」时的升级路径。
3. 待后续步骤落实：数据放在**同实例独立 database / 独立 PG 实例**哪一种；原始层保留多久；JSONB 单行实测大小。

**来源：**
- [PostgreSQL 时间分区实践（OneUptime）](https://oneuptime.com/blog/post/2026-01-26-time-based-partitioning-postgresql/view)、[pg_partman 指南](https://alexandrubagu.github.io/blog/pg-partman-guide.html)、[Crunchy Data：pg_partman 时间分区](https://crunchydata.com/blog/time-partitioning-and-custom-time-intervals-in-postgres-with-pg_partman)
- [TimescaleDB 版本与许可（Tiger Data）](https://www.tigerdata.com/docs/about/latest/timescaledb-editions)、[Timescale License](https://www.tigerdata.com/legal/licenses)、[Zabbix：Apache 版不支持压缩](https://support.zabbix.com/browse/ZBX-20743)
- [Altinity：小服务器单机 ClickHouse](https://altinity.com/blog/deploying-single-node-clickhouse-on-small-servers)、[Altinity KB：硬件要求](https://kb.altinity.com/altinity-kb-setup-and-maintenance/cluster-production-configuration-guide/hardware-requirements/)
- [Postgres 分析扩展对比（ClickHouse 资料）](https://clickhouse.com/zh/resources/engineering/best-postgres-extensions-for-analytics-2026)、[pg_duckdb（Vonng）](https://blog.vonng.com/en/pg/pg-duckdb/)
- [PostHog 自托管文档](https://posthog.com/docs/self-host)
- [Postgres 写入：INSERT vs 批量 vs COPY（Tiger Data）](https://www.tigerdata.com/learn/testing-postgres-ingest-insert-vs-batch-insert-vs-copy)、[1M 行 COPY 基准](https://karan.is-a.dev/blog/postgres-copy-ingest/1m-rows-into-postgres-in-1-29-seconds)、[Crunchy Data：BRIN 索引](https://www.crunchydata.com/blog/postgresql-brin-indexes-big-data-performance-with-minimal-storage)
- [Segment HTTP API](https://www.twilio.com/docs/segment/connections/sources/catalog/libraries/server/http-api)、[Segment 通用字段规范](https://www.twilio.com/docs/segment/connections/spec/common)、[Segment 批量大小限制](https://support.twilio.com/hc/en-us/articles/53898043461531-Maximum-Batch-Size-Limits-for-Segment-HTTP-API-Calls)
- [Citus：物化视图 vs rollup 表](https://www.citusdata.com/blog/2018/10/31/materialized-views-vs-rollup-tables)、[PG 物化视图并发刷新](https://www.postgresql.org/about/featurematrix/detail/materialized-views-with-concurrent-refresh)
