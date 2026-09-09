---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
workflowType: 'architecture'
lastStep: 8
completedAt: '2026-09-09'
docType: 'architecture-delta'
project_name: 'TailTopia V1.3.0 后台'
user_name: 'Dai'
date: '2026-09-09'
status: 'complete'
baseline:
  - _bmad-output/planning-artifacts/architecture.md                        # V1.0 冻结基线
  - _bmad-output/planning-artifacts/admin-backend/architecture.md          # 运营后台专题架构（Lark 登录 / 审计链 / admin 切片）
  - _bmad-output/planning-artifacts/v1.1.0/architecture-v1.1-delta.md
  - _bmad-output/planning-artifacts/v1.1.2/architecture-v1.1.2-delta.md
  - _bmad-output/planning-artifacts/v1.1.4/architecture-v1.1.4-delta.md
  - _bmad-output/planning-artifacts/v1.1.6/architecture-v1.1.6-delta.md
  - _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-delta.md   # 并行电商线（商城组 17 页的现状依据）
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.0/PRD-v1.3.0-admin.md
  - _bmad-output/planning-artifacts/v1.3.0/后台重构逐页规格.md
  - _bmad-output/planning-artifacts/v1.3.0/ui-v1.3.0-admin.html
  - _bmad-output/planning-artifacts/v1.3.0/决策日志.md
  - _bmad-output/planning-artifacts/v1.3.0/validation-2026-09-09/review-adversarial-general.md
  - _bmad-output/planning-artifacts/v1.3.0/内容运营所需数据-20260831.sql
  - _bmad-output/planning-artifacts/v1.3.0/README.md
  - docs/reference/db-schema-reference.md
  - docs/reference/admin-permission-codes.md
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md
binds: [AB-15A, AB-16A, AB-17A, AB-18A, AB-19A, AB-20A, AB-21A, AB-22A]
flywayConvention: 'V<yyyyMMdd_HHmm>__<snake_case>.sql 时间戳制（决策 E7），out-of-order 常开'
---

# TailTopia V1.3.0 后台 架构决策文档 —— Delta

_本文件通过逐步协作构建；各节随决策依次追加。未提及处一律继承基线。_

## Project Context Analysis

### Requirements Overview

**功能需求（8 条 AB，性质分三类）：**

| 类 | AB | 架构含义 |
|---|---|---|
| **新功能，需新表/新端点** | AB-15A 数据看板、AB-17A 场所管理、AB-20A 暖贴、AB-21A 角色配置、AB-22A 充值档位新建 | 5 张以上新表（预聚合、场所及其照片/评论/打卡/举报、暖贴跟进队列、角色-权限；档位新建无新表）；约 15 个新端点；1 个新日跑批 |
| **既有功能增强，改服务层** | AB-16A 账号增强（改名/换绑/self 护栏/踢重登）、AB-18A 定价加列 | 账号表加变更版本号列 + 守门过滤器改造；`pricing_config` 加两列 |
| **纯前端重构，零后端功能改动** | AB-19A 49 页五模板重构 | 64 个模板重写 + 128 个写端点从整页 PRG 改 htmx 局部更新；11 条微调例外中有 4 条要动服务层（警告 reason、并入抽屉的 GET 聚合、旧详情路由删除、标签分配起止时间列） |

**非功能需求：**

- **数据一致性**：看板任一天数值必须与口径表逐项相等（D-26），WIB 切日，回填到 2026-07-17（D-15），9 项帖子指标双口径（D-29）→ 预聚合表设计是本版最重的数据决策。
- **安全**：账号信息变更即踢重登（D-1）；权限重登生效并提示（D-2）；换绑触发超管告警；所有写操作走既有审计哈希链；角色表与 `SUPER_ADMIN` 硬编码边界不可越（D-9）。
- **审核一致性**：暖评走完整审核链（D-4），后台不能有绕过 UGC 审核的写入口。
- **三语**：全部新增文案走 message key，zh/en/id 齐备才可合入。
- **无新中间件**：图表库须 vendored（Chart.js 或自绘 SVG，OQ-B1），地图选点须 vendored 底图；禁 CDN 外链。
- **可用性**：模板 A 处置动线 htmx 局部更新、处置后自动下一条；窄屏降级。

### Scale & Complexity

- **Primary domain**：后端单体内的服务端渲染管理后台（Spring Boot 4 + Thymeleaf + htmx），无独立前端工程。
- **Complexity level**：**中高**。单点技术难度不高，但面广：一次触碰全部 64 页模板与 128 个写端点，同时并行的 v1.4.0 电商线还在往商城组 17 页里加东西。
- **Estimated architectural components**：新增约 6 个 admin 子模块（dashboard 重写、places、warmreply、roles、tiers 扩展、account-guard），改造 1 个横切件（会话守门过滤器），1 个新 `@Scheduled` 跑批 + 1 个一次性回填脚本，5 套模板片段（fragments）。

### Technical Constraints & Dependencies

- **基线继承**：V1.0 架构 + 运营后台专题架构（Lark OAuth、审计哈希链、`admin_accounts` 与 App 用户隔离）+ 四份 delta；Flyway 时间戳版本号，`ddl-auto=validate`。
- **既有事实（评审核实）**：会话为容器内存 HttpSession，权限登录时固化；`pricing_config` 为固定列单例表；账本 `amount > 0` 约束；评论创建链 = L1 黑名单 → UNDER_REVIEW → 异步机审 → 人工复核；举报模型只认帖子与账号；`AdminPermissions` 72 个常量，`AdminRole` 6 个非超管角色，只有 CUSTOM 落表。
- **并行线依赖**：商城组 17 页的现状由 v1.4.0 分支决定，AB-19A 对这 17 页的重构必须排在电商线合入 `dev_1.3.0` 之后。
- **本分支边界**：只做后台。AB-17A 场所的用户端 FR-112、AB-18A 护照样式 FR-120 由 App 分支实现；本 delta 只定后台侧数据模型与端点，App 端读接口契约列为「需与 App 分支对齐」。
- **不做**：0 元限免（D-7）、旧详情路由跳转（D-23）、暖评硬上限（D-20）、独立部署拆分（已搁置）。

### Cross-Cutting Concerns Identified

1. **账号变更版本号 + 每请求比对**（D-1/D-2）——同时服务 AB-16A 与 AB-21A，横切件，须先定。
2. **WIB 日切**——看板预聚合、回填、跑批时机、审计页展示（D-22）四处共用同一时区约定。
3. **审计动作码扩充**——ACCOUNT_RENAMED / ACCOUNT_EMAIL_REBOUND / PLACE_* / COMMENT_VIRTUAL_POST / ROLE_* / TIER_CREATED，全部进 `AuditActions` 常量表。
4. **权限码扩充与角色表迁移**——`place.manage`、`comment.virtual_post` 两个新码；四个预置角色的权限从枚举迁入表；权限矩阵按 35 个页面维度重排。
5. **htmx 局部更新范式**——模板 A/B 的处置与抽屉都要求不整页刷新，需统一的 fragment 返回约定与错误呈现约定，替代现状 PRG。
6. **三语 message key** 与 **导出分列**（规则 12）贯穿所有页面。
7. **虚拟账号判定**——看板「真实用户」口径、暖评身份池、暖贴入队三处都依赖同一个「是否虚拟/种子账号」判定，须单一出口。

## Starter Template Evaluation

### Primary Technology Domain

服务端渲染管理后台，brownfield 增量：Spring Boot 4 / Java 21 单体内的 `com.tailtopia.admin` 切片，Thymeleaf + htmx，无独立前端工程、无构建链。

### 既有基线（不重建）

| 件 | 现状 | 本版用法 |
|---|---|---|
| 渲染 | Thymeleaf，`templates/admin/layout.html` 单一 `page(content)` fragment + `fragments/`，64 个模板 | 五套模板以 fragment 落进 `fragments/`，页面只填内容 |
| 交互 | `static/admin/vendor/htmx.min.js` = **1.9.12**（D-31 维持不升级），全站 45 处 `hx-*`；`admin.js` 664 行（data-confirm / data-autosubmit / toast） | 模板 A「一次响应刷左栏行 + 右栏」用 `hx-swap-oob`；角标刷新用 `HX-Trigger` 响应头，1.x 均已具备 |
| 样式 | `admin.css` 480 行 + `admin-shop.css`，无 CSS 框架 | UI 稿 `:root` 令牌照抄自 admin.css，直接沿用 |
| 认证/会话 | 独立 `adminFilterChain`、Lark OAuth + 超管紧急账密、内存 HttpSession、`AdminSessionGuardFilter` | D-1 的版本号比对在此过滤器落 |
| 审计 | `AdminAuditService.record` 哈希链 + `AuditActions` 常量 | 新动作码全部追加此处 |
| i18n | `messages_{zh_CN,en,id}.properties` 各 2141 key，会话级切换 | 新 key 三语同批 |
| 测试 | MockMvc 切片，`src/test/.../admin` 26 个测试类 | 新页面沿用 |

### 本版新增前端件

- **图表库（AB-15A，OQ-B1 → D-30）：Chart.js 4.5.x**，MIT，单文件 UMD 约 200 KB，零依赖，vendored 到 `static/admin/vendor/chart.umd.min.js`，只在看板页引入。柱状 / 折线 / 双轴开箱覆盖五张卡；缺数据日以 null 值 + `spanGaps:false` 呈现断点。否决自绘 SVG（五卡 × 双口径 × 7/30 天切换的交互量不值得自研）。
- **地图（AB-17A → D-32）：不引入。** 后台不做地图选点、不引 Leaflet 等库、不请求外部瓦片；场所页只展示文字地址，坐标只读显示（底图瓦片按视口动态请求、无法 vendored；自建瓦片服务违反无新基建）。
- **htmx（D-31）：维持 1.9.12。** 运营后台专题架构写的「HTMX 2.0.x」属文档漂移，回写文档为 1.9.12；不借重构搭车升级。

### 不新增的

无新 Maven 依赖（Chart.js 为静态文件）；无新数据库、无新中间件、部署形态不变；不引入前端构建链、CSS 框架、前端状态库。

### Initialization

brownfield，无 init 命令。Chart.js 静态资源落位与五套 fragment 骨架作为 **G0 全局壳 story**，是 AB-19A 的第一个实现 story。

> 待定（进入 §Core Decisions 处理）：预置场所坐标来源与后台「新建场所」按钮去留（决策日志末行）。

## Core Architectural Decisions

### Decision Priority Analysis

**Critical（不定就没法开工）**：账号变更版本号横切件（AD-1）；看板预聚合表与自愈跑批（AD-2/3）；角色-权限表与解析顺序（AD-4）；场所数据模型与后台边界（AD-5）；暖评审核链与跟进队列入队规则（AD-6）；htmx 局部更新约定（AD-9）。
**Important（塑形）**：虚拟账号判定单一出口（AD-8）；导出规范（AD-10）；前端 fragment / JS 拆分（AD-11）；商城组重构排在电商线合入之后（AD-12）。
**Deferred（已拍板不做）**：0 元限免（D-7）、旧路由跳转（D-23）、暖评硬上限（D-20）、独立部署（搁置）、htmx 升级（D-31）、地图组件（D-32）、二级暖评识别（D-35 预留后补）。

### Data Architecture

**AD-1 账号变更版本号（D-1/D-2）**
`admin_accounts` 加 `security_version INT NOT NULL DEFAULT 0`。改邮箱、停用、改角色、改账号级权限、所在角色模板改权限 → 该账号版本号 +1（角色模板改权限时批量 +1 该角色下全部账号）。登录时把版本号写进会话属性；`AdminSessionGuardFilter` 现有的每请求查库顺手比对，不等则 `session.invalidate()` 跳 `/admin/login?relogin`（三语提示「账号信息已变更，请重新登录」）。零新查询成本。权限本身不在过滤器重算（D-2）。

**AD-2 看板预聚合：长表，一行一个（日, 指标, 口径）**
新表 `ops_daily_metrics(id, report_date DATE, metric_key VARCHAR(48), scope VARCHAR(8), value NUMERIC(18,4), computed_at TIMESTAMPTZ, UNIQUE(report_date, metric_key, scope))`。`scope` ∈ `ALL` / `REAL`：D-29 的 9 项帖子类指标写两行，其余 9 项只写 `ALL`。18 项口径实现为 Java 端原生 SQL（与 PRD §1 ①-a 口径表逐行对应，每项一个方法，方法名 = metric_key），切日一律 `(ts AT TIME ZONE 'Asia/Jakarta')::date`，复用 `ScheduleWindow.WIB`（OQ-B3 闭合）。选长表不选 27 列宽表：加指标不改 schema、双口径天然表达、Chart.js 取数按 key 过滤即可。

**AD-3 自愈跑批 = 回填 + 补偿一体**
一个 `@Scheduled(cron = "0 0 1 * * *", zone = "Asia/Jakarta")` 任务：每次运行计算 `[2026-07-17, 昨天]` 内表中缺失的日期并逐日物化，单日一个事务。首次上线跑一次即完成 D-15 全量回填；某天失败次日自动补齐（OQ-B2 闭合）；已物化行不重算（口径保证同一天数字不变）。不提供手工回填入口；提供 `/actuator`-外的只读健康指标「最近物化日期」供 stag 验收。

**AD-4 角色-权限表（D-9 方案 A）**
新表 `admin_roles(id, code VARCHAR(32) UNIQUE, name VARCHAR(60), name_key VARCHAR(80) NULL, role_type VARCHAR(8) SYSTEM|CUSTOM, created_by, created_at, updated_at)` 与 `admin_role_permissions(role_id FK, permission_code VARCHAR(64), UNIQUE(role_id, permission_code))`。`admin_accounts` 加 `role_id BIGINT NULL REFERENCES admin_roles ON DELETE RESTRICT`。
**解析顺序**（登录时一次，`AdminUserDetailsService.resolvePermissions`）：`accountType=SUPER_ADMIN` → 隐式全权；`role=OPS_MANAGER` → 仍读枚举；`role=CUSTOM` → 读 `admin_account_permissions`（现状不动）；其余 → 读 `role_id` 指向的 `admin_role_permissions`。
一次性迁移（Flyway 数据迁移）：为 OPERATIONS / FULFILLMENT / SUPPORT / FINANCE 各建一行 SYSTEM 角色，`name_key` 指向三语 message key，权限码从枚举灌入，现有账号按 `role` 值回填 `role_id`。迁移后 `AdminRole` 枚举中这四个值保留为「标记」（用于 `role` 列兼容），权限列表清空并注明「以表为准」。自定义角色：`code` 自动生成 `role-<id>`，`name` 运营自填单语。删除自定义角色 = 服务层先校验无账号引用，DB FK RESTRICT 兜底。

**AD-5 场所模型（后台侧，D-33）**
新表 `places(id, public_token, name, place_type, tags JSONB, description, address_text, lat NUMERIC(9,6), lng NUMERIC(9,6), marked_by_user_id, status ACTIVE|DELISTED|MERGED, merged_into_id NULL self-FK, photo_count, comment_count, checkin_count, recommend_count, not_recommend_count, created_at, updated_at, deleted_at)`；`place_photos(id, place_id, object_key, uploader_user_id, created_at, deleted_at)`；`place_comments(id, place_id, author_user_id, body, attitude RECOMMEND|NOT_RECOMMEND, created_at, deleted_at)`；`place_checkins(id, place_id, user_id, checked_at)`；`place_reports(id, place_id, reporter_user_id, reason_type, status PENDING|DISMISSED|ACTIONED, handled_by, handled_at, created_at)`。场所举报进 A1 统一队列：`TicketType` 增 `PLACE_REPORT`，页签内处置调用 `AdminPlaceService.delist / removePhoto / removeComment / dismissReport` 同一批方法。
后台「新建场所」保留：坐标由运营从 Google Maps 复制手填，服务层校验纬度 [-90,90]、经度 [-180,180]、小数位 ≤6；落在雅加达都会区包围盒外只给黄条警告不拦截。软删（`deleted_at`）；下架 = `status=DELISTED`；合并 = 被合并方 `status=MERGED` + `merged_into_id` + 三张子表 `place_id` 改指保留方 + 保留方计数重算，单事务。**护照章合并语义**（两边都盖过 → 并一枚、次数相加）依赖 App 分支的护照章表：本 delta 只约定合并时发布 `PlaceMergedEvent(keepId, mergedId)`，归并逻辑由 App 分支监听实现（跨分支契约 X-1）。计数列为反规范化缓存，由服务层同事务维护，合并/删除时重算。

**AD-6 暖评与跟进队列（D-4/D-19/D-35）**
暖评写入：`CommentService` 同一入口，新增显式参数「以指定虚拟账号身份发布」的服务层方法（校验该账号 ∈ 虚拟池且启用），走完整审核链，后台不新开绕审核的写路径。本版只发一级评论。
新表 `warm_reply_followups(id, virtual_comment_id UNIQUE-when-PENDING, post_id, virtual_user_id, pending_reply_count INT, last_reply_id, last_reply_at, status PENDING|HANDLED, handled_by, handled_at, handled_action REPLIED|READ)`；部分唯一索引 `UNIQUE(virtual_comment_id) WHERE status='PENDING'` 保证合并为一项。入队：监听既有 `ContentCommentedEvent`，条件 `parentAuthorId` 为虚拟账号（AD-8）且 `commenterId` 非虚拟。出队：监听评论删除/下架事件，`pending_reply_count` 减一，归零则删除待办。
**预留（D-35）**：`comments` 加 `reply_to_comment_id BIGINT NULL`，`POST /api/v1/comments/{parentId}/replies` 请求体可选 `replyToCommentId`；本版只落库不使用；App 分支合入后再做「通知发给被回复者 + 二级暖评识别」（跨分支契约 X-2）。

**AD-7 定价与档位（D-3/D-7/D-24/D-25）**
`pricing_config` 加 `passport_page_unlock_price`、`passport_boarding_unlock_price`（BIGINT NOT NULL，迁移默认值 = 当前 `id_hd_download_price`），CHECK `>= 1`；三行价格独立、不联动。档位新建走既有 `pawcoin_topup_tiers`：`tier_key = "t" + amount_idr`（`t25000`，与既有 UNIQUE 天然兼容），`sort_order` 按金额升序重排，「启用中 ≤4」在服务层校验并用 `pg_advisory_xact_lock` 防并发；列表查询默认 `enabled=true`，「查看已停用」为同页第二段查询。

**AD-8 虚拟/种子账号判定单一出口**
`User.isSyntheticAccount()`：`role=ADMIN` 或 `googleSub` 以 `virtual:` / `seed-tailtopia-` 开头（null 安全，Apple 用户 → false）。Java 端三处（看板 REAL 口径的用户/互动者过滤、暖评身份池、暖贴入队）只调它；SQL 侧同一判断封装为常量片段 `SyntheticAccountSql.EXCLUDE_WHERE`，与 Java 方法并列维护，单测断言两者对同一批样本结果一致。

### Authentication & Security

- 会话与权限：AD-1；权限重登生效（D-2），过滤器不重算权限，只做版本号比对。
- 新权限码：`place.manage`（读 + 全部处置 + 新建）、`comment.virtual_post`；两者**不预授予任何预置角色**，超管隐式拥有，运营需要时在角色配置页勾选。看板付费卡权限码 = **与支付记录页同码**（付费口径源自 `payment_intents`，D-17）。
- 换绑邮箱：唯一性只对 `status=ACTIVE` 校验（D-21）；触发 `AdminAlertService.alertSuperAdmins`；bootstrap 超管（env 邮箱）拒绝换绑；self 护栏 `actorAccountId == accountId` 拒绝停用/改角色。
- 审计动作码追加 `AuditActions`：`ACCOUNT_RENAMED` `ACCOUNT_EMAIL_REBOUND` `ACCOUNT_WARNED`（补 reason 进 detail）`PLACE_CREATED/EDITED/DELISTED/RESTORED/MERGED/PHOTO_REMOVED/COMMENT_REMOVED` `COMMENT_VIRTUAL_POST` `WARM_REPLY_READ` `ROLE_CREATED/UPDATED/DELETED` `TIER_CREATED`；定价复用 `PRICING_UPDATED`。审计 detail 禁写评论正文全文（只摘要 ≤50 字）。
- 暖评身份：服务层显式参数传虚拟账号 id，禁止伪造登录态或切换 SecurityContext。

### API & Communication Patterns

**AD-9 htmx 局部更新约定（模板 A/B）**
- 请求头含 `HX-Request` → Controller 返回 fragment（`templates/admin/fragments/**`），否则整页；同一 Controller 方法两条路径共用 Model，用 `HxRequest` 参数解析器判别。
- 处置成功：200 + 主 fragment（右栏）+ `hx-swap-oob` 带出左栏该行与页签计数；响应头 `HX-Trigger: {"admin:badge-refresh":{}}` 让侧导航角标自刷（角标 fragment 由 `GET /admin/nav/badges` 提供）。
- 校验/业务失败：**422 + 操作区 fragment**（行内 err）；权限不足 403 + 禁用态 fragment 注明所缺权限名；不可逆动作仍用 `data-confirm`。htmx 1.9 默认不渲染 4xx，由 `admin-core.js` 监听 `htmx:beforeSwap` 对 422/403 放行 swap（不用 `response-targets` 扩展）。
- 模板 D/E 与所有整页表单维持现状 PRG，不混用。
- CSRF：htmx 请求由 `admin-core.js` 统一注入 `X-CSRF-TOKEN`（现状机制沿用）。
- 抽屉：`GET /admin/<list>/{id}/drawer` 返回抽屉 fragment；列表页 `?open=<id>` 查询参数在页面加载后由 `admin-drawer.js` 自动请求该抽屉（页内深链，非旧路由兜底）。

**AD-10 导出（规则 12）**
新增 `AdminExportWriter`：xlsx 走既有 POI（`AdminPaymentExportService` 范式），CSV 走 RFC 4180 转义（引号、逗号、换行、前导 `=` 防公式注入），表头取当前会话 locale 的 message key，时间列 WIB 字样。各页导出只允许调它，禁止自拼字符串。

### Frontend Architecture

**AD-11 fragment 与 JS 拆分**
- 五套模板 fragment：`fragments/tpl-a-workbench.html`、`tpl-b-list.html`（含抽屉壳与摘要条）、`tpl-c-report.html`、`tpl-d-config-card.html`、`tpl-e-steps.html`；页面模板只填槽位（`th:replace` + 命名 fragment 参数）。侧导航自 `layout.html` 抽为 `fragments/nav.html`，按权限渲染、无权限项不渲染。
- JS 拆分：`admin-core.js`（data-confirm / autosubmit / toast / csrf，现状）+ `admin-workbench.js`（模板 A 自动下一条、窄屏两屏切换）+ `admin-drawer.js`（抽屉开合、`?open=` 同步、ESC 关闭）+ `admin-charts.js`（仅看板页）。无构建链，`<script defer>`。
- 图表取数：7/30 天切换由 htmx 替换图表区 fragment，fragment 内嵌 `<script type="application/json" data-chart="...">` 携带序列，`htmx:afterSwap` 初始化/销毁 Chart.js 实例；不另开 JSON API。缺数据日 = null + `spanGaps:false`。
- 三语：新增 key 前缀 `admin.v130.*`；CI 校验三包 key 集合相等（把本次评审用的 diff 脚本化进 `scripts/ci/`）。ID 文案占位适配按逐页规格三语约束。

### Infrastructure & Deployment

- 无新中间件、无新容器、无新 env；Chart.js 静态文件随 jar。
- 新增 1 个 `@Scheduled`（AD-3）+ 2 个事件监听（AD-6）+ 1 个 `PlaceMergedEvent` 发布点；与既有 4 个 admin 扫描器同容器。事件监听沿用 `@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`（既有通知事故的修法）。
- Flyway 时间戳迁移预计 8 支：`admin_accounts.security_version`、`ops_daily_metrics`、`admin_roles` + `admin_role_permissions` + 数据迁移、`places` 五表、`warm_reply_followups`、`comments.reply_to_comment_id`、`pricing_config` 两列。

**AD-12 商城组排序约束**
AB-19A 对商城组 17 页的重构 story 排在 v1.4.0 电商线合入 `dev_1.3.0` 之后；其余 32 页 + G0 可先行。epics 阶段按此切两批。

### 跨分支契约（与 App 分支对齐，本 delta 只定后台侧）

| # | 契约 | 后台侧承诺 | App 侧待做 |
|---|---|---|---|
| X-1 | 场所合并的护照章归并 | 合并时发布 `PlaceMergedEvent(keepId, mergedId)`，被合并场所 `merged_into_id` 可查 | 监听事件归并护照章（并一枚、次数相加）；直链跳转到保留场所 |
| X-2 | 二级回复目标 | `comments.reply_to_comment_id` 可空列 + 回复接口可选字段，本版不使用 | 传目标评论 id；通知改发给被回复者；后台再开二级暖评识别 |
| X-3 | 场所读接口 | `places*` 五表与 `status` 语义由本 delta 定 | App 端 FR-112 读写接口以此表为准 |
| X-4 | 护照样式定价 | `pricing_config` 两新列 + 现有 `/consult/pricing` 同类下发方式 | FR-120 解锁按新列取价 |

### Decision Impact Analysis

**实现顺序**：AD-1 版本号（横切，最先）→ G0 全局壳 + 五 fragment + JS 拆分（AD-9/11 前端底座）→ AD-4 角色表（B24/D6 依赖）→ AD-2/3 看板 → AD-8 判定出口 → AD-6 暖评 → AD-5 场所 → AD-7 定价档位 → AB-19A 逐组套模板（商城组最后，AD-12）。
**交叉依赖**：AD-8 被 AD-2、AD-6 共用；AD-1 被 AB-16A、AB-21A 共用；AD-9/11 是 49 页的共同前提；AD-5 与 AD-6 的事件监听共用 AFTER_COMMIT + REQUIRES_NEW 范式。

## Implementation Patterns & Consistency Rules

### Pattern Categories Defined

识别出 **11 类**不加约束就会各写各的的点：新模块目录、模板与 fragment 命名、htmx 局部响应形状、message key 前缀、权限码与审计码命名、指标 key、事件命名、错误呈现、WIB 时间格式、测试放置、导出。基线（V1.0 + 后台专题）已定的一律继承，此处只写增量。

### Naming Patterns

**数据库（继承 snake_case，增量约定）**
- 新表：`ops_daily_metrics`、`admin_roles`、`admin_role_permissions`、`places`、`place_photos`、`place_comments`、`place_checkins`、`place_reports`、`warm_reply_followups`。后台专属表前缀 `admin_` / `ops_`，业务域表不加前缀。
- 状态列一律 `status VARCHAR + CHECK(UPPER_SNAKE 全集)`，改 CHECK 必须 `DROP + ADD` 重列全集。
- 指标 key（`ops_daily_metrics.metric_key`）固定 18 值，枚举在 `DashboardMetric`，禁散字符串：`new_users` `cumulative_users` `posting_users` `new_posts` `new_pet_owners` `cumulative_pet_owners` `diary_pet_owners` `interacted_posts` `silent_posts` `engagement_score` `new_posts_score` `all_posts_avg_score` `interacted_posts_avg_score` `engagement_score_all_time` `paying_users_cash` `payments_cash` `paying_users_incl_pawcoin` `payments_incl_pawcoin`。

**后台路由**
- 页面 `GET /admin/<复数资源>`；抽屉 `GET /admin/<复数资源>/{id}/drawer`；动作 `POST /admin/<复数资源>/{id}/<动词>`；对外 token 参数名 `{token}`，内部 id 用 `{id}`。
- 新页面：`/admin/places`、`/admin/roles`、`/admin/warm-replies`、`/admin/comments/distribution`（评论页签二）；看板仍是 `/admin`；角标聚合 `GET /admin/nav/badges`。

**Java**
- 新子模块沿用四层 `admin/<module>/{domain,repository,service,web,dto}`：`dashboard`（重写）、`places`、`warmreply`、`roles`；档位扩展落既有 `admin/config`，账号增强落既有 `admin/account`。
- `Admin` 前缀只用于 Controller / Service；实体与仓储不加前缀（`Place`、`PlaceRepository`）。
- 权限码 `<资源>.<动词>` 小写下划线；审计码 `<对象>_<动作>` UPPER_SNAKE，同对象前缀一致；事件 `<对象><过去分词>Event` record 放对象所属包的 `event/`。

**模板与前端**
- 页面模板 kebab-case 与路由同名（`places.html`、`roles.html`、`warm-replies.html`）；抽屉 `fragments/drawer-<资源>.html`；模板壳 `fragments/tpl-<a|b|c|d|e>-*.html`。
- fragment 内部 id 前缀 = 资源名（`#places-rows`、`#places-drawer`、`#places-summary`）。
- message key：新增 `admin.v130.<页面>.<语义>`；错误 `admin.err.<模块>.<原因>`；flash `admin.flash.<模块>.<结果>`（后两者沿用）。
- JS 文件 `admin-<职责>.js`，全局挂 `window.Admin.<职责>`，不用 ES module。

### Structure Patterns

- 五套模板壳槽位固定：A = `tabs / filters / queue / detail / actions`；B = `filters / summary / table / drawer`；C = `range / cards / detail`；D = `cards[]`（每卡自带 form）；E = `steps / body / footer`。页面只填槽位不改壳。
- 看板：`admin/dashboard/metrics/` 下每指标一个 `*MetricQuery` 实现同一接口 `compute(LocalDate, Scope)`，跑批按枚举遍历；禁止单条大 SQL 算全部。
- 测试放 `test/.../admin/<module>/Admin<Module>IntegrationTest.java`；每个新 Controller 至少四条：整页 200、`HX-Request` 返 fragment、422 行内错、403 禁用态。
- 迁移一支一件事；DDL 与数据迁移分文件（角色表：DDL 后 `seed_admin_roles`）。

### Format Patterns

- htmx 响应：成功 200 主 fragment（+ `hx-swap-oob` 片段）；失败 422 操作区 fragment；403 禁用态 fragment；重定向只在整页 PRG。禁止向 htmx 返回 JSON。
- `HX-Trigger` 事件名 `admin:<对象>-<动作>`（`admin:badge-refresh`、`admin:drawer-close`）。
- 图表内嵌 JSON：`{"labels":[ISO 日期…], "series":[{"key":"new_users","scope":"REAL","values":[…|null]}]}`，缺数据日为 `null`。
- 时间：展示一律 WIB `yyyy-MM-dd HH:mm`（审计页亦然，D-22），格式化走 `AdminTime` 工具；入库 UTC。金额 IDR 整数千分位。
- 导出只经 `AdminExportWriter`；文件名 `<资源>-<yyyyMMdd>.<csv|xlsx>`。

### Communication Patterns

- 事件监听统一 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`，禁 `REQUIRED`。
- admin 新模块不得直接注入业务包 Repository，只经 service 接口与事件（存量 70 处历史债不新增）。
- 虚拟账号判定只调 `User.isSyntheticAccount()` / `SyntheticAccountSql.EXCLUDE_WHERE`。
- 应用日志只记 id 与动作码，禁记评论正文、邮箱、坐标；审计 detail 评论正文只留 ≤50 字摘要。

### Process Patterns

- 校验：服务层抛 `AdminBusinessException(errKey)` → Controller 统一映射 422 fragment；模板层只做即时提示，不复制业务规则。
- 权限：Controller `@PreAuthorize`；模板 `sec:authorize` 决定按钮禁用态并注明所缺权限名。
- 高危确认 `data-confirm="#{key}"` 复述对象与后果，键 `admin.v130.<页面>.confirm.<动作>`。
- 处置后自动下一条：成功 fragment 根元素带 `data-next-id`，`admin-workbench.js` 发起下一条 GET；服务端不做跳转。
- 加载态：`hx-indicator` 加 `.is-loading`；骨架屏只用于看板卡；失败在卡内/操作区内重试。
- 三语：新增 key 三包同批；CI 比对 key 集合，缺一即红。

### Enforcement Guidelines

**所有实现 story 必须**：新表列先查 `db-schema-reference.md` 防撞名；写操作三件套 `@PreAuthorize` + `AdminAuditService.record` + 三语 key；htmx 端点四条测试齐全才算 L0；触碰账号/角色/权限的写操作必须递增 `security_version`（漏加返工）。

**反例**：控制器直接拼 CSV；抽屉用独立页面路由替代 fragment；模板写死中文；散写 `LIKE 'admin:%'` 判定虚拟账号；一个 SQL 算 18 个指标；`REQUIRED` 事务的事件监听。

## Project Structure & Boundaries

### Complete Project Directory Structure（增量，全部在 `petgo-backend/` 内）

```
petgo-backend/
├── pom.xml                                    # 不变（无新依赖）
├── src/main/java/com/tailtopia/
│   ├── admin/
│   │   ├── account/                           # AB-16A（既有模块扩展）
│   │   │   ├── domain/AdminAccount.java       #   + securityVersion, roleId
│   │   │   ├── service/AdminAccountService.java  # + rename / rebindEmail / self 护栏 / bumpVersion
│   │   │   ├── web/AdminSessionGuardFilter.java  # + 版本号比对（AD-1）
│   │   │   └── web/AdminAccountAdminController.java # + 行内动作端点 + drawer
│   │   ├── roles/                             # AB-21A（新）
│   │   │   ├── domain/{AdminRoleEntity, AdminRolePermission, RoleType}.java
│   │   │   ├── repository/{AdminRoleRepository, AdminRolePermissionRepository}.java
│   │   │   ├── service/{AdminRoleService, PermissionMatrixBuilder}.java   # 矩阵按 35 页面维度分组
│   │   │   ├── web/AdminRoleController.java   #   /admin/roles*, /{id}/drawer
│   │   │   └── dto/{RoleRow, PermissionMatrixView}.java
│   │   ├── dashboard/                         # AB-15A（重写）
│   │   │   ├── domain/{OpsDailyMetric, DashboardMetric(enum), MetricScope(enum)}.java
│   │   │   ├── repository/OpsDailyMetricRepository.java
│   │   │   ├── metrics/                       #   18 个 *MetricQuery + MetricQuery 接口 + SyntheticAccountSql
│   │   │   ├── service/{DashboardMaterializer(@Scheduled 自愈), DashboardQueryService}.java
│   │   │   ├── web/AdminDashboardController.java  #   GET /admin, GET /admin/charts?range=7|30 (fragment)
│   │   │   └── dto/{ChartSeries, ChartCard, TotalsRow}.java
│   │   ├── places/                            # AB-17A（新）
│   │   │   ├── domain/{Place, PlacePhoto, PlaceComment, PlaceCheckin, PlaceReport, PlaceStatus, Attitude}.java
│   │   │   ├── repository/…（5 个）
│   │   │   ├── event/PlaceMergedEvent.java
│   │   │   ├── service/{AdminPlaceService, PlaceMergeService, PlaceCoordinateValidator}.java
│   │   │   ├── web/AdminPlaceController.java  #   /admin/places*, /{id}/drawer, /{id}/{edit|delist|restore|merge}, photos/{pid}/remove, comments/{cid}/remove
│   │   │   └── dto/{PlaceRow, PlaceDrawerView, PlaceForm}.java
│   │   ├── warmreply/                         # AB-20A（新）
│   │   │   ├── domain/{WarmReplyFollowup, FollowupStatus, HandledAction}.java
│   │   │   ├── repository/WarmReplyFollowupRepository.java
│   │   │   ├── service/{VirtualCommentService（调 CommentService 同链路）, WarmReplyQueueService, WarmReplyEnqueueListener}.java
│   │   │   ├── web/{AdminCommentDistributionController（/admin/comments/distribution*）, AdminWarmReplyController（/admin/warm-replies*）}.java
│   │   │   └── dto/{PostDistributionRow, DistributionSummary, FollowupRow}.java
│   │   ├── config/                            # AB-18A + AB-22A（既有模块扩展）
│   │   │   ├── service/AdminConfigService.java     # + passport 两价 diff；createTier（advisory lock，≤4）
│   │   │   └── web/AdminConfigController.java      # + POST /admin/config/tiers；GET …/tiers/disabled (fragment)
│   │   ├── audit/service/AuditActions.java    # + 全部新动作码
│   │   ├── account/domain/AdminPermissions.java # + place.manage, comment.virtual_post
│   │   ├── moderation/…                       # A1 + 场所举报页签（TicketType + PLACE_REPORT）
│   │   ├── shared/                            # （新，admin 内横切）
│   │   │   ├── web/{HxRequest 参数解析器, AdminFragmentResponses, AdminBusinessExceptionAdvice(422/403 fragment)}.java
│   │   │   ├── export/AdminExportWriter.java  # AD-10
│   │   │   └── time/AdminTime.java            # WIB 格式化单一出口
│   │   └── web/AdminNavController.java        # GET /admin/nav/badges（角标聚合）
│   ├── auth/domain/User.java                  # + isSyntheticAccount()（AD-8）
│   ├── content/
│   │   ├── domain/Comment.java                # + replyToCommentId（预留，D-35）
│   │   ├── dto/CommentCreateRequest.java      # + Optional replyToCommentId
│   │   └── service/CommentService.java        # + 以虚拟身份发布的显式入口（走同一审核链）
│   └── config/domain/PricingConfig.java       # + passportPageUnlockPrice, passportBoardingUnlockPrice
├── src/main/resources/
│   ├── db/migration/                          # 8 支时间戳迁移（见 §数据模型增量）
│   ├── i18n/messages_{zh_CN,en,id}.properties # + admin.v130.* 三包同批
│   ├── static/admin/
│   │   ├── vendor/chart.umd.min.js            # Chart.js 4.5.x（新）
│   │   ├── admin-core.js                      # 由 admin.js 拆出（现状能力）
│   │   ├── admin-workbench.js / admin-drawer.js / admin-charts.js   # 新
│   │   └── admin.css                          # + 五模板壳样式（令牌不变）
│   └── templates/admin/
│       ├── layout.html                        # 顶栏账号菜单 + STAG 角标；nav 抽出
│       ├── fragments/
│       │   ├── nav.html, badges.html          # 侧导航（按权限）与角标片段
│       │   ├── tpl-a-workbench.html, tpl-b-list.html, tpl-c-report.html, tpl-d-config-card.html, tpl-e-steps.html
│       │   ├── drawer-<资源>.html              # 每个 B 类页一个
│       │   └── dashboard-charts.html          # 图表区 fragment（内嵌 JSON）
│       ├── places.html, roles.html, warm-replies.html, comments.html(两页签)   # 新页
│       └── <其余 49 页>.html                  # 套模板重写；content-detail / user-detail / consult-order-detail / ai-order-detail / vet-edit / vet-qualification / vets-online / ratings 八个独立页删除
├── src/test/java/com/tailtopia/admin/
│   ├── roles/AdminRoleIntegrationTest.java, places/AdminPlaceIntegrationTest.java, warmreply/…, dashboard/{DashboardMaterializerTest, MetricQueryParityTest}
│   ├── account/AdminSessionGuardVersionTest.java
│   └── shared/{AdminExportWriterTest, SyntheticAccountParityTest}.java
└── scripts/ci/check-i18n-keys.sh              # 三包 key 集合相等（新）
```

### Architectural Boundaries

**API 边界**：后台全部在 `/admin/**`（会话 + CSRF 链），本版新增约 30 个端点全在此前缀下；`/api/v1` 只动一处——评论回复请求体多一个可选字段（X-2 预留），对现有 App 完全兼容。看板不开 JSON API，只有 fragment。

**组件边界**：`admin/shared/` 是 admin 内横切层，只被 admin 各模块依赖，不依赖任何具体模块；五套模板壳与三个 JS 是 49 页共同底座，任何页面不得复制壳代码；`admin/dashboard/metrics/` 只读业务表，写只发生在 `ops_daily_metrics`。

**服务边界**：暖评写入必须经 `content.service.CommentService`，`warmreply` 不得直接写 `comments` 表；场所合并只发事件，不触碰 App 侧护照章表（X-1）；角色权限解析只在 `AdminUserDetailsService.resolvePermissions` 一处。

**数据边界**：新表全部由本版迁移创建，`ddl-auto=validate`；`admin_*` / `ops_*` 表只允许 admin 包访问；`places*` 五表为双分支共用表——本分支定义 schema，App 分支只读写数据（X-3）。

### Requirements to Structure Mapping

| AB | 落点 |
|---|---|
| AB-15A 看板 | `admin/dashboard/**` + `fragments/dashboard-charts.html` + `admin-charts.js` + `vendor/chart.umd.min.js` |
| AB-16A 账号增强 | `admin/account/**`（扩展）+ `AdminSessionGuardFilter` |
| AB-17A 场所 | `admin/places/**` + `places.html` + `drawer-places.html` + `moderation` 场所举报页签 |
| AB-18A 定价 | `config/domain/PricingConfig` + `admin/config/**` + `config.html` D 卡 |
| AB-19A 重构 | `fragments/tpl-*` + `nav.html` + 三个 JS + 49 页模板 + `admin/shared/web/**` |
| AB-20A 暖贴 | `admin/warmreply/**` + `comments.html` 页签二 + `warm-replies.html` + `CommentService` 显式入口 |
| AB-21A 角色 | `admin/roles/**` + `roles.html` + `AdminUserDetailsService` 解析顺序 |
| AB-22A 档位 | `admin/config/**` createTier + `config.html` PawCoin 卡 |

**横切**：AD-1 版本号（`account` + `AdminSessionGuardFilter`）；AD-8 判定（`auth/domain/User` + `dashboard/metrics/SyntheticAccountSql`）；AD-10 导出（`admin/shared/export`）；WIB（`admin/shared/time/AdminTime` + `ScheduleWindow.WIB`）；三语 CI（`scripts/ci/check-i18n-keys.sh`）。

### Integration Points

- **内部**：Spring 事件（`ContentCommentedEvent` → 暖贴入队；评论删除事件 → 出队；`PlaceMergedEvent` → App 分支监听）；`HX-Trigger` → 前端角标刷新。
- **外部**：无新增第三方（Lark OAuth、OSS、审核服务均为既有）。
- **数据流**：业务表 → 每日 01:00 WIB 物化 → `ops_daily_metrics` → fragment 内嵌 JSON → Chart.js；运营处置 → Controller → Service（审计同事务）→ fragment + oob → 页面局部更新 + 角标刷新。

### File Organization Patterns

- 配置：无新 env；`application.yml` 加 `petgo.admin.dashboard.cron` 与 `petgo.admin.dashboard.backfill-from=2026-07-17` 两项带默认值。
- 源码：新模块四层目录；admin 横切归 `admin/shared`。
- 测试：与模块同名目录；看板另有「Java 实现 vs 参考 SQL」对照测试 `MetricQueryParityTest`（用 stag 快照数据验证 18 项逐日相等）。
- 资产：Chart.js 只在看板页引入；JS 拆分后 `layout.html` 引 `admin-core.js`，其余按页按需引。

### Development Workflow Integration

- 本地：`mvn -B clean package` L0；L1 走 scratch 库（先 flush Redis DB0）跑 admin 集成测试；模板重构页用 MockMvc 断言 fragment 结构 + 人工在 stag 走 UI 稿比对。
- 构建：无变化，静态资源随 jar；`clean` 必带。
- 部署：`deploy-backend-stag.sh` 不变；stag 顶栏 STAG 角标靠既有 profile 判断；首次部署后看板跑批在次日 01:00 WIB 自动回填，验收可用 `DashboardMaterializer` 的 stag 专用手动触发端点（仅 stag profile 渲染，与 B12 模拟回调同规则）。

## Architecture Validation Results

### Coherence Validation ✅

**Decision Compatibility**：Spring Boot 4 / Java 21 / PostgreSQL / Thymeleaf / htmx 1.9.12 / Chart.js 4.5.x 全为静态文件或既有依赖，无版本冲突；D-31 维持 htmx 1.9 与 AD-9 的 oob / HX-Trigger 兼容，4xx 渲染用 beforeSwap 钩子无需扩展。AD-1 版本号与 D-2「重登生效」互不矛盾：过滤器只比对不重算。AD-4 方案 A 与 B24 保留账号级权限面板一致。
**Pattern Consistency**：命名、四层目录、fragment 响应形状、AFTER_COMMIT + REQUIRES_NEW 均与既有代码风格对齐，无一处要求改写存量。
**Structure Alignment**：增量树每个新文件都映射到某条 AD；`admin/shared/` 承载全部横切件，无循环依赖。

### Requirements Coverage Validation ✅

**AB 覆盖**：AB-15A（AD-2/3/8/11）、AB-16A（AD-1 + 安全节）、AB-17A（AD-5 + 举报页签）、AB-18A（AD-7）、AB-19A（AD-9/11/12 + 例外清单 11 条逐条有落点：警告 reason 只进审计 detail 无 schema 变更；性别/生日与标签起止时间为纯展示；排期并入、标签入口收拢、C5/C6 退役均为模板层改动）、AB-20A（AD-6/8）、AB-21A（AD-4）、AB-22A（AD-7）——8 条全覆盖。
**决策覆盖**：D-1～D-35 全部落入 AD 或模式规则；OQ-B1～B5 全部闭合。
**NFR**：数据一致性（口径表逐项一个 Query + Parity 测试）、安全（版本号、审计码、权限码、不绕审核）、三语（CI 校验）、无新中间件、WIB 统一——均有机制。性能：数据规模 ≤500 DAU，预聚合避免实时 LATERAL；模板 B 摘要条单条 `COUNT(*) FILTER` 聚合；角标一条聚合查询；抽屉按需加载。

### Implementation Readiness Validation ✅

**Decision Completeness**：12 条 AD 均含表结构、端点、事务边界与理由；技术件版本已核实。
**Structure Completeness**：增量树到文件级；删除的 8 个独立页明示；测试文件命名给出。
**Pattern Completeness**：11 类冲突点各有规则与反例；htmx 端点四条测试为 L0 门槛。

### Gap Analysis Results

- **Critical**：无。
- **Important（本节已修正）**：htmx 4xx 渲染方式（AD-9）；`tier_key` 规则（AD-7）；数据模型增量清单（下节补）；场所举报数据流（AD-5）；新权限码默认授予范围（安全节）。
- **Nice-to-have**：`MetricQueryParityTest` 依赖 stag 库脱敏快照，首次跑需人工准备；AB-19A 商城组 17 页依赖 v1.4.0 合入时点，epics 阶段与电商线确认。

### 数据模型增量（Flyway 时间戳迁移，8 支）

| # | 迁移 | 内容 |
|---|---|---|
| 1 | `add_admin_accounts_security_version` | `admin_accounts.security_version INT NOT NULL DEFAULT 0` |
| 2 | `init_ops_daily_metrics` | AD-2 长表 + `UNIQUE(report_date, metric_key, scope)` |
| 3 | `init_admin_roles` | `admin_roles` + `admin_role_permissions` + `admin_accounts.role_id` FK RESTRICT |
| 4 | `seed_admin_roles` | 四个 SYSTEM 角色行 + 权限码灌入 + 存量账号 `role_id` 回填（数据迁移，单独文件） |
| 5 | `init_places` | `places` / `place_photos` / `place_comments` / `place_checkins` / `place_reports` 五表 + 索引 |
| 6 | `init_warm_reply_followups` | AD-6 表 + 部分唯一索引 `WHERE status='PENDING'` |
| 7 | `add_comments_reply_to_comment_id` | 可空列 + 索引（D-35 预留） |
| 8 | `add_pricing_config_passport_prices` | 两列 NOT NULL，默认值 = 当前 `id_hd_download_price`，CHECK `>= 1` |

顺序仅表依赖：3 → 4；其余独立。`out-of-order` 常开，与并行分支合并不重排号。文件名取创建时刻 `V<yyyyMMdd_HHmm>__<上表名>.sql`。

### Validation Issues Addressed

- AD-9：422/403 fragment 渲染由 `admin-core.js` 的 `htmx:beforeSwap` 放行，不用扩展。
- AD-7：`tier_key = "t" + amount_idr`。
- AD-5：`place_reports.status` 三态；`TicketType.PLACE_REPORT`；A1 页签处置复用 `AdminPlaceService`。
- 安全节：新权限码不预授予预置角色。

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status**：READY FOR IMPLEMENTATION
**Confidence Level**：high（后台侧）；跨分支契约 X-1～X-4 的 App 侧落地不在本分支控制范围，medium。
**Key Strengths**：横切件先于功能定死，49 页重构有统一底座；看板回填与补偿合一，无手工运维动作；全部决策可追溯到 D 编号。
**Areas for Future Enhancement**：admin 直查业务 Repository 的 70 处历史债；二级回复目标字段启用（X-2）；stag 专用件的 profile 门控统一为 `@StagOnly`。

### Implementation Handoff

**AI Agent Guidelines**：严格按 AD 与模式规则实现；每条 story 的 AC 引用对应 AD 编号；架构疑问以本文件为准，冲突序 本 delta > v1.4.0 delta > v1.1.6 delta > … > V1.0 基线。
**First Implementation Priority**：AD-1 账号变更版本号（迁移 #1 + 过滤器 + 测试）→ 紧接 G0 全局壳（五 fragment + nav + JS 拆分 + Chart.js 落位）。

## Deferred（本 delta 明确不决定）

- 0 元限免与零价短路（D-7）；旧详情路由跳转（D-23）；暖评每日硬上限与告警（D-20）；htmx 升级（D-31）；地图组件（D-32）；ops 独立部署（2026-09-09 搁置，重提优先同 jar 双容器 profile 分流）；二级暖评识别与「通知发给被回复者」（D-35，App 分支合入后）；admin 直查业务 Repository 的 70 处历史债（不在本版清理）。

## 对上游文档的反向影响（须回写）

| 文档 | 改动 | 依据 |
|---|---|---|
| `PRD-v1.3.0-admin.md` AB-17A ③ | 「地图选点」→ 运营手填经纬度 + 文字地址，后台校验范围 | D-32 / D-33 |
| `PRD-v1.3.0-admin.md` AB-20A ② | 补「本版只发一级评论」+ 预留 `reply_to_comment_id` | D-35 |
| `后台重构逐页规格.md` B6 / B2 | 同上两处 | D-33 / D-35 |
| `ui-v1.3.0-admin.html` 2-21 新建场所帧 | 地图区改为经纬度两个数字输入 | D-33（文字已改，画面待设计师同步） |
| `admin-backend/architecture.md` 增强项 3 | 「HTMX 2.0.x」→ 实际 1.9.12 | D-31 |
| `CROSS-STORY-DECISIONS.md` 表归属总表 | 追加 9 张新表归属 admin / places / content | AD-2/4/5/6 |

## 开放问题

- 无阻塞项。跨分支契约 X-1～X-4 的 App 侧落地时点由 App 分支排期；商城组 17 页重构的启动时点取决于 v1.4.0 合入 `dev_1.3.0` 的日期（AD-12）。
