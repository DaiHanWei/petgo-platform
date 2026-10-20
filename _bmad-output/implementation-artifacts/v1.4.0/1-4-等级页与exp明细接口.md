# Story 1.4: 等级页与 EXP 明细接口

Status: review

> 版本 v1.4.0 · 主题 tailrent-level · 分支 `feat/1.4.0-tailrent-level`
> 口径优先级：`decision-log.md` ① 节 > `Tailrent等级/1-4-0Tailrent等级prd.md` > `architecture-v1.4.0-tailrent-level-delta.md` > UI 稿 `Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html`（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。
> 前置：**Story 1.1**（五张表、规则 / 等级表 seed、`growth_daily_counters`、流水 `ref_type` 口径表、参与判定）、**Story 1.2**（`growth_accounts.level` 落库、`GrowthFrameResolver`）、**Story 1.3**（`SPEND_*` 流水 `ref_type = 支付用途`）。
> 范围层：**只后端**。无迁移。App 在 Story 1.5。

## Story

As a 用户,
I want 一个接口拿到等级页所需的全部信息、另一个接口分页看 EXP 明细,
so that App 能展示我的等级、进度、今天能涨多少和历史流水。

## Acceptance Criteria

**AC1 — `GET /api/v1/me/growth`（等级页首屏一次取齐）** `[L0]` `[L1]`（AD-16、PRD AC20）
1. 新建 `growth.web.MeGrowthController`（`@RequestMapping("/api/v1/me/growth")`），只作用于 JWT `sub` 本人，`currentUserId` 写法照 `PawCoinController`；`SecurityConfig` 显式加 `"/api/v1/me/growth", "/api/v1/me/growth/**"` → `hasRole("USER")`（不加会落到 `anyRequest().authenticated()`，兽医 token 能以同号用户名义读，见 SecurityConfig 既有注释）。
2. 聚合由 `growth.service.GrowthPageQuery` 完成（只读事务），响应 `GrowthPageResponse`（字段全 camelCase、只下发**代码与数字**，不下发显示串）：
   ```
   {
     participating: true,
     level: 7, tier: "BUDDY", lifetimeTotal: 1420,
     currentThreshold: 1250, nextLevel: 8, nextThreshold: 1800,      // 满级时 nextLevel / nextThreshold 为 null
     levels: [{level, threshold, tier}, …],                            // 完整等级表，App 据此算段位区间与每级颜色
     frames: { effective: "BASIC", manual: false,
               options: [{frame: "NONE", unlocked: true, unlockLevel: 1},
                         {frame: "BASIC", unlocked: true, unlockLevel: 5},
                         {frame: "UPGRADED", unlocked: false, unlockLevel: 10},
                         {frame: "ANIMATED", unlocked: false, unlockLevel: 15}] },
     rules: [{code, points, unitAmount, dailyCap, capGroup, todayCount, todayEarned, maxedToday}, …],
     passiveGroup: { dailyCap: 50, todayEarned: 18, maxedToday: false },
     tierBenefits: [{tier: "NEWBIE", fromLevel: 1, toLevel: 4, benefits: ["LEVEL_BADGE"]}, …],
     recentLedger: [ <与 AC3 同结构的行，最多 3 条> ]
   }
   ```
3. `frames`：经 Story 1.2 `GrowthFrameResolver` 推导；`unlockLevel` = 等级表中该档对应段位的最低等级（BASIC = BUDDY 首级、UPGRADED = GUARDIAN 首级、ANIMATED = STAR 首级），从 `growth_levels` 算，不写死 5 / 10 / 15。App 1.5 不渲染这一排（Epic 3 用），但字段本 story 就给齐。
4. `rules`：只列**启用**且用户可感知的规则——排除 `PASSIVE_GROUP`、`OPS_CAMPAIGN`、`ADMIN_ADJUST`、`LAUNCH_BACKFILL`；顺序固定：`DAILY_LOGIN, POST_PUBLISHED, COMMENT_POSTED, LIKE_GIVEN, PLACE_CHECKIN, LIKE_RECEIVED, COMMENT_RECEIVED, COLLECT_RECEIVED, MILESTONE_S, MILESTONE_M, MILESTONE_L, GIFT_SENT, GIFT_RECEIVED, SPEND_DIGITAL, SPEND_SHOP`（同 UI 稿 B2 自上而下）。
5. `tierBenefits`（AC20：**不含优惠券**）：权益代码由后端常量 `GrowthBenefit` 定义，按段位：NEWBIE `[LEVEL_BADGE]`；BUDDY `[PET_SLOT_PLUS_ONE, FRAME_BASIC, EXCLUSIVE_GIFTS_BATCH1]`；GUARDIAN `[PET_SLOT_PLUS_ONE, FRAME_UPGRADED]`；STAR `[FEED_BOOST, FRAME_ANIMATED, EXCLUSIVE_GIFTS_BATCH2]`。`fromLevel / toLevel` 由等级表算（STAR 的 `toLevel` 为 null = 「Lv15+」）。
6. Given 登录的真实用户，When 请求 `GET /api/v1/me/growth`，Then 一次返回：等级、段位、累计、下一级门槛（满级为 null）、完整等级表、头像框四档（可用 / 生效 / 是否手动）、全部启用规则（代码、每次分值、每日上限、今日已得、是否今日已满）、各段位权益代码（不含优惠券）、最近 3 条流水。
7. 无账户行的真实用户：`level = 1`、`lifetimeTotal = 0`、`frames.effective = NONE`、`recentLedger = []`，**不建账户行**（只读接口不写库）。

**AC2 — 「今日已得 / 今日已满」口径** `[L0]` `[L1]`（V-2、PRD AC17）
1. `todayEarned` = 当天（`wib_date = 今天(ScheduleWindow.WIB)`）该规则流水 **正数 delta 之和**（扣回不减，与「扣回不返还当日额度」一致）。
2. `todayCount` = 有 `daily_cap` 的主动规则读 `growth_daily_counters.used`（counter_key = 规则代码）；被动三项与无上限规则为 null。
3. `maxedToday`：主动规则 `todayCount >= dailyCap`；被动三项与 `passiveGroup` 看 `growth_daily_counters(PASSIVE).used >= PASSIVE_GROUP.daily_cap`；无上限规则恒 false。
4. `passiveGroup.todayEarned` = counter `PASSIVE` 的 `used`。
5. Given 用户今天已得 `LIKE_GIVEN` 3 次（+3），When 请求等级页，Then 该规则 `todayCount = 3`、`todayEarned = 3`、未满；达到 5 次后 `maxedToday = true`（AC17）。
6. 整页聚合的查询次数为常数（账户 1、等级表 1、规则 1、当日计数 1、当日流水汇总 1、最近 3 条 1 + 其明细补充 1），不随规则数增长。

**AC3 — `GET /api/v1/me/growth/ledger?cursor=`（EXP 明细分页）** `[L0]` `[L1]`（AD-16）
1. keyset 分页：`ORDER BY created_at DESC, id DESC`，每页 20；游标用 `shared.paging.KeysetCursor`（`encode()` / `decodeOrNull()`），非法游标 → 400 ProblemDetail。响应 `{ items: [...], nextCursor: string | null }`。
2. 行结构 `GrowthLedgerRow`：`{ ruleCode, delta, reason, note, detailCode, createdAt }`：
   - `reason`：扣回 / 调整原因代码（`REFUND / UNLIKE / UNCOLLECT / TAKEDOWN / ADMIN_ADJUST`），正向入账为 null；
   - `note`：运营填写的原因或活动名（`OPS_CAMPAIGN` / `ADMIN_ADJUST`），**原样下发**，其余为 null；
   - `detailCode`（UI 稿 B6「Layanan digital · KTP」「Posting Diary」需要）：`SPEND_*` → 流水 `ref_type`（支付用途代码，如 `ID_HD`）；`POST_PUBLISHED` → 该帖 `content_posts.type`（`GROWTH_MOMENT` / `DAILY` / `KNOWLEDGE`，**一页一次批量查询**，帖已物理删除则 null）；其余 null。
   - `createdAt` UTC ISO-8601，App 按 WIB 分组。
3. Given 用户有 45 条流水，When 请求首页与下一页，Then 每页 20 条、游标翻页无重复无遗漏（第 3 页 5 条、`nextCursor = null`）；每行含规则代码、增减值、原因代码、`note`、时间；扣回行带原因，运营调整与活动带 `note`。
4. **不下发** `ref_id`、`pet_ids`、`operator_admin_id`、`batch_no`、`idem_key`（自增 id 不外露；明细只给本人看）。

**AC4 — 不参与者与游客** `[L0]` `[L1]`
- Given 虚拟号或游客，When 请求上述接口，Then 游客 401（ProblemDetail）；虚拟号（实际无法登录，防御性）与其他不参与者（官方号、停用）返回 `200 { participating: false }`，其余字段省略，明细接口返回空列表——即「无等级页数据」。

**AC5 — `/me` 加 growth 摘要** `[L0]` `[L1]`（AD-14 的 `/me` 部分）
1. `UserProfileResponse` 末尾追加 `MeGrowthBrief growth`（可空）：`{ level, tier, frame, lifetimeTotal, nextThreshold }`，`frame` = 生效头像框（`GrowthFrameResolver`）、`nextThreshold` 满级为 null。
2. 由 `MeService.withStats`（GET 与 PATCH 共用）填充 —— **PATCH `/me` 的响应也必须带**，否则 App 用 PATCH 响应覆盖 profile 后胶囊会消失。登录响应（`AuthService` 两处 `UserProfileResponse.from(user, …)`）不查成长值，`growth = null`（全局 NON_NULL 省略，与 `postCount` 同一处理）。
3. 不参与者 `growth = null`。
4. Given `GET /api/v1/me`，When 真实用户请求，Then 响应含 `growth: {level, tier, frame, lifetimeTotal, nextThreshold}`。

**AC6 — 错误与隐私** `[L0]` `[L1]`
1. 错误一律 RFC 9457 ProblemDetail（经 `AppException` + `GlobalExceptionHandler`），不外泄堆栈。
2. 别人的明细**无任何接口可取**：两个端点都不接受 userId / token 参数；测试断言带 `?userId=` 也只返回本人数据。
3. 日志不记流水内容与 `note`。

**AC7 — 回归** `[L0]`：`MeControllerEndpointTest`、`MeServiceTest` 既有用例不变绿；`mvn -B clean package` 绿。

## Tasks / Subtasks

- [x] **T0 开工核对**：确认 1.1 ~ 1.3 已合入；核对 1.1 实际的 `ref_type` 值（`CONTENT_POST` 等）与 1.2 `GrowthFrameResolver` 方法名
- [x] **T1 DTO**：`GrowthPageResponse`、`GrowthLedgerRow`、`GrowthLedgerPage`、`MeGrowthBrief`（`growth.web.dto`）；`GrowthBenefit` 常量
- [x] **T2 `GrowthPageQuery`**（AC1 / AC2）+ 明细分页查询（AC3，含 `detailCode` 批量补充）
- [x] **T3 `MeGrowthController`** + `SecurityConfig` 规则（AC1 / AC3 / AC4）
- [x] **T4 `/me` 摘要**（AC5）：`UserProfileResponse` 追加字段、`MeService.withStats` 填充、`AuthService` 登录路径传 null
- [x] **T5 测试**：L0 全部；L1 写好能编译
- [x] **T6** `mvn -B clean package`；Completion Notes 标「L1 待本地验收」

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 81005da7）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/auth/web/MeController.java` | `GET /api/v1/me`（:35-42，先 `captureLocale` 再 `getMe`）、`PATCH`（:44-48）；`currentUserId`（:50-59） | 不改 | — |
| `…/auth/service/MeService.java` | `getMe`（:45-48）→ `withStats`（:50-55）；`updateMe`（:74）也走 `withStats` | `withStats` 加 growth 摘要；构造器注入 `GrowthQueryService` 或新 `GrowthBriefQuery` | 帖子统计口径 |
| `…/auth/dto/UserProfileResponse.java` | record 18 个字段（:12-57）；`from(u, hasPet)`（:60-62，登录用）、`from(u, hasPet, postCount, likeCount)`（:65-67）、`build`（:69-90） | 末尾追加 `MeGrowthBrief growth`；`from` 两个重载与 `build` 同步 | 字段顺序、既有工厂签名（可加重载） |
| `…/auth/service/AuthService.java` | 登录两处 `UserProfileResponse.from(user, …)`（:128、:160） | 不改调用（工厂内部传 null） | 登录不查统计（T-6 口径） |
| `petgo-backend/src/test/java/com/tailtopia/auth/service/MeServiceTest.java` | 用 `UserProfileResponse.from(freshUser(), false)`（:198） | 补 growth 用例、mock 新依赖 | — |
| `…/pay/web/PawCoinController.java` | `/api/v1/me/pawcoin` 分页端点范例：`DEFAULT_LIMIT = 20`、`currentUserId`（:31-58） | 照写 | — |
| `…/shared/paging/KeysetCursor.java` | `record KeysetCursor(Instant createdAt, long id)`（:37），`encode()` / `decodeOrNull()` | 复用 | — |
| `…/shared/security/SecurityConfig.java` | `/api/v1/me/follow-suggestions` / `following-strip` / `following-feed` 显式 `hasRole("USER")`（:223-227）；否则落 `anyRequest().authenticated()` | 加 `/api/v1/me/growth` 与 `/**` | 其余规则顺序 |
| `…/shared/schedule/ScheduleWindow.java` | `WIB`（:34） | 「今天」只用它 | — |
| `…/growth/service/GrowthFrameResolver.java` | **Story 1.2 新建** | 调用 | — |
| `…/growth/service/GrowthQueryService.java` | Story 1.1 后读落库等级；签名是 ①②③ 契约 | **不改签名**；`/me` 摘要另写查询或在本类加新方法（只加不改） | `getLevel(s)` 签名 |

### 实现提示

- 等级页聚合不需要锁、不写库；全部 SQL 用 `NamedParameterJdbcTemplate` 或仓储投影，禁止对规则逐条查当日计数（一条 `WHERE user_id = ? AND wib_date = ?` 取全部 counter）。
- 当日流水汇总：`SELECT rule_code, SUM(delta) FILTER (WHERE delta > 0) FROM growth_ledger WHERE user_id = ? AND wib_date = ? GROUP BY rule_code`。
- 段位区间：按 `levels` 里同一 tier 的最小 / 最大 level 计算，追加 Lv21 后自动生效。
- `currentThreshold` 供 App 画进度条起点（`(lifetime − current) / (next − current)`）。
- 显示串规则（AD-16）：规则名、原因、段位名、权益**全部**由 App 按代码本地化；只有 `note` 原样。后端不得放任何印尼语 / 英语文案进 DTO。

### 架构约束

- AD-16：`/api/v1/me/growth` 前缀、需登录 USER；明细 keyset 每页 20；不可用框 409 `frame-locked` 属 Story 3.3（本 story 不做 PUT）。
- AD-14：`/me` 加 `growth`；作者等级批量下发（`AuthorView` / 拍平 DTO）属 Story 3.1，本 story 不碰。
- V-2：今日已得 = 当天正数之和；今日已满读 counters。
- §5.5：权益判断用 `getLevel(s)`，展示用 badge——本人 `/me` 摘要按落库等级（真实用户二者一致）。
- 对外标识不可枚举：明细行不下发流水 id；游标是编码后的 `(createdAt, id)`，与既有 PawCoin 流水同一做法。
- NFR9：别人的成长值明细不对外。

### 与前后 story 的边界（本 story 不做）

- 不做 `GET /me/growth/popups` 与 ack（Story 2.1 / 2.2）、`PUT /me/growth/frame`（Story 3.3）。
- 不做作者等级下发（Story 3.1）、后台用户抽屉成长值页签（Story 4.2，可复用本 story 的明细查询但不复用 DTO）。
- 不改入账逻辑（1.1 ~ 1.3）；不做 App（1.5 消费本 story 的字段，字段名定稿后 1.5 照抄）。

### 测试

**验证层级**
- **L0（云端可跑，纯单元）**：
  - `growth/service/GrowthPageQueryTest`（mock JdbcTemplate / 仓储）：满级 `nextLevel / nextThreshold = null`；无账户行 → Lv1 / 0 / NONE / 空明细且不写库；`todayCount` / `todayEarned` / `maxedToday` 三类规则口径（主动、被动、无上限）；`LIKE_GIVEN` 3 次未满、5 次已满；`rules` 排除四个系统代码且顺序固定；`tierBenefits` 不含任何优惠券代码、STAR `toLevel = null`；`unlockLevel` 从等级表算。
  - `growth/service/GrowthLedgerPageQueryTest`：`detailCode` 映射（SPEND → ref_type、POST → 帖子类型批量一次、其余 null）；`reason` / `note` 透传；非法游标抛 400。
  - `growth/web/MeGrowthControllerTest`（`MockMvcBuilders.standaloneSetup`，先例 `shared/web/LegalPageControllerTest`）：无 JWT → 401 ProblemDetail；`?userId=` 被忽略；JSON 字段名与结构。
  - `auth/service/MeServiceTest` 补：`getMe` 与 `updateMe` 都带 growth；不参与者 growth 为 null；登录工厂 growth 为 null。
- **L1（需 Docker，云端不跑，待本地验收）**：
  - `growth/MeGrowthControllerEndpointTest`（`extends ApiIntegrationTest`）：真实用户造 45 条流水 → 三页 20 / 20 / 5、无重复无遗漏、`nextCursor` 末页 null；等级页 `LIKE_GIVEN` 3 → 5 次的未满 / 已满；被动 50 满额；兽医 token 访问 403；游客 401；虚拟号返回 `participating: false`。
  - `auth/web/MeControllerEndpointTest` 补：`GET /me` 与 `PATCH /me` 响应都含 `growth`。
- 云端只跑 L0；Completion Notes 标「L1 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-tailrent-level.md#Story 1.4]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md#AD-14（/me 部分）、AD-16、§5.5、§7 V-2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/1-4-0Tailrent等级prd.md#3.2 等级页 / 段位权益 / AC17 / AC20]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-1-4-0-tailrent-level-integrated-v1.html#B1 / B2 / B3 / B6]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/1-1-成长值账本与日常行为入账.md#关联对象口径]

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程手工执行）

### Debug Log References

- L0：`./mvnw -B clean package -DskipTests` 绿；`./mvnw -B test -Dtest=GrowthPageQueryTest,GrowthLedgerPageQueryTest,MeGrowthControllerTest,MeServiceTest` 全绿（32）。无迁移。

### Completion Notes List

- **🔴 L1/L2 待本地验收**：`growth/MeGrowthControllerEndpointTest`（45 条三页 20/20/5、`LIKE_GIVEN` 5 次已满、游客 401 / 兽医 403、非法游标 400）与 `auth/web/MeControllerEndpointTest#getAndPatchMe_carryGrowthBrief` 已写好能编译，云端未跑；AC7「`mvn -B clean package` 绿」云端不验。
- T0 核对：1.1 实际 ref_type 为 `CONTENT_POST` / `PLACE_CHECKIN` / `MILESTONE` / `GIFT_ORDER`，1.3 的 `SPEND_*` ref_type = 支付用途名；1.2 的方法为 `GrowthFrameResolver.availableFrames / effectiveFrame / isManual`（静态）。与 story 一致，未改 story 文字。
- `/me` 摘要：`MeService` 注入 `GrowthPageQuery`（`brief(userId)`），`UserProfileResponse` 末尾追加 `growth` 并新增 5 参工厂；登录两处仍走 2 参工厂 → `growth = null`。4 参旧工厂保留（签名不动），Javadoc 标明 `/me` 已改用 5 参。
- `GrowthPageQuery` 与 `GrowthLedgerPageQuery` 分开：明细分页供 1.5 / 4.2 复用查询（不复用 DTO）；最近 3 条走同一查询。无账户行的用户不查流水、不写库。
- 非法游标：story AC3.1 要求 400 ProblemDetail，而 `KeysetCursor.decodeOrNull` 的约定与其他列表是「坏游标当首页」。**照 story 写 400**（`AppException(BAD_REQUEST, VALIDATION)`；`AppException.validation` 是 422 不合用）。
- `detailCode`：`POST_PUBLISHED` 读 `content_posts.type`（含软删帖，只有物理删除才 null，与 story「帖已物理删除则 null」一致）。
- code-review：修了测试 Javadoc 错位与旧工厂文档；`/api/v1/me` 本身未限 `ROLE_USER`（兽医 token 撞号可读同号用户 profile）属存量问题、超出本 story，已另起任务建议，未在此改。

#### 待确认

- 非法游标 400 与仓内「坏游标降级首页」惯例相反（见上），按 story 实现；如需统一改为降级首页，只改 `GrowthLedgerPageQuery.page` 一处。

### File List

- `petgo-backend/src/main/java/com/tailtopia/growth/domain/GrowthBenefit.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/growth/service/{GrowthPageQuery,GrowthLedgerPageQuery}.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/growth/web/MeGrowthController.java`、`growth/web/dto/{GrowthPageResponse,GrowthLedgerRow,GrowthLedgerPage,MeGrowthBrief}.java`（新）
- `petgo-backend/src/main/java/com/tailtopia/auth/dto/UserProfileResponse.java`、`auth/service/MeService.java`、`shared/security/SecurityConfig.java`
- 测试（新）：`growth/service/{GrowthPageQueryTest,GrowthLedgerPageQueryTest}`、`growth/web/MeGrowthControllerTest`、`growth/MeGrowthControllerEndpointTest`（L1）
- 测试（改）：`auth/service/MeServiceTest`、`auth/web/MeControllerEndpointTest`（L1）

### Change Log

- 2026-10-18：实现 Story 1.4（等级页 / EXP 明细接口 + `/me` 摘要）；L0 绿，L1/L2 待本地验收；状态 → review。
