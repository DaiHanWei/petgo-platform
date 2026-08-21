---
baseline_commit: 9a4520e7
---

# Story 11.1: 顶置管理（Feed 坑位）

Status: review

## Story

As a 运营，
I want 在后台配置首页 Feed 的顶置坑位（顶一篇已发布内容，或直接配一张推广卡片），
so that FR-68 的顶置能力不再是个没有开关的空壳。

---

> **本 story 是「已交付功能的开关」。** App 侧 FR-68 已交付（Epic 4，story 4-1~4-3），
> 表 `content_pins` 已建（V108）。**后台没有任何页面能往里写** —— 现在线上顶置坑位恒为空。
>
> ✅ **表已按"坑位可扩展"建好**（AD-8 Rule 5）：`slot` 是一个 VARCHAR 列、**刻意不加 CHECK 约束**、
> 也刻意不建独立坑位表。后台 PRD A-3 的当期约束**已满足**，本 story 不需要为可扩展性做任何额外设计 ——
> 界面按"坑位是一个下拉"实现即可，本版本该下拉只有 `HOME_FEED` 一个选项。

---

## Acceptance Criteria

### AC1 — 顶置配置列表（L1）

**Then** 按坑位分类展示当前及排期中的配置，含**生效状态**（排期中 / 生效中 / 已结束）`[L1]`
**And** 🛡 生效状态一律**查询时**按当前时刻是否落在 `[starts_at, COALESCE(terminated_at, ends_at))` 内算出 `[L0]`
**And** 🛡 **不得新增 status 列、不得建定时扫描器** `[L0]`（AD-9 Rule 2 —— 表就是照这个建的）

> `content_pins` 已有 `terminated_at` 单列记录"提前结束"，**刻意不覆盖 `ends_at`**：
> 覆盖了运营就只看到"这条 14:32 结束了"，无从知道是排期到点还是被下架带走的。
> SQL 侧直接用 `COALESCE(terminated_at, ends_at)` 当结束时刻。

### AC2 — 新建顶置（L1）

**Then** 流程：选坑位 → 选类型 → 填内容 → 设开始/结束时间 → 保存 `[L1]`
**And** 两种类型**互斥** `[L1]`：
- **CONTENT**：搜索/选择一篇已发布内容，写 `content_id`
- **PROMO**：填图片 + 标题 + 跳转目标，`content_id` 为空

> 表上已有 CHECK 约束保证"该有的有、不该有的没有"，应用层漏判会被库挡下 —— 但**不要依赖它做用户提示**。

### AC3 — 🔴 同坑位时间重叠必须拦截（L1）

**Then** 🛡 同一坑位内时间窗重叠的配置**保存时校验拦截** `[L1]`（FR-68）

> 🔴 **订正（2026-08-21 实读）：这条校验其实已经存在**，在 `ContentPinService.schedule()` 里
> （`requireNoOverlap`，且首尾相接不算重叠）。拆 story 时我只看了「表上无约束 + App 侧读取只取一条」
> 就断言"全系统不存在"，**没去看写入服务** —— 判断错了。
>
> **真实情况**：机制在，但**没有任何入口能调到它**（Story 4.1 的类注释原话：
> 「本 story 没有对外接口 —— 后台配置界面 AB-10A 不在本轮范围…交付的是机制，供后台接上来之后直接调用」）。
> 所以本 story 的活是**接上来 + 钉住它真的走到了**，而不是从零实现。
> 若哪天有人绕过 service 直接写库，重叠仍会发生（表上确实没有约束）—— 这一点不变。
>
> ⚠️ 判定要用**半开区间** `[starts_at, ends_at)`：`A.starts < B.ends AND B.starts < A.ends`。
> 用闭区间会把"上一条 10:00 结束、下一条 10:00 开始"误判成重叠 —— 那是运营最常见的排法。

### AC4 — 🔴 只允许顶置公开内容（L1）

**Then** 🛡 内容选择器**只返回公开内容** `[L1]`：`DAILY` / `KNOWLEDGE`，以及发布时已同步为公开的 `GROWTH_MOMENT`
**And** 🛡 全量内容管理列表里对**未同步的 Diary** 不提供顶置操作入口 `[L1]`

> 把作者主动设为私密的内容顶到 Feed，直接违背 FR-83 给作者的可见范围选择权。
>
> 🔴 **枚举名别照 PRD 字面抄**：PRD 写的 `MOMENT` 在代码里不存在，产品口语的
> "Moment" = 代码 `DAILY`，"Diary" = 代码 `GROWTH_MOMENT`。以代码为准。
>
> ⚠️ **候选集接近全量内容**：FR-83 的同步开关已于 2026-07-30 反转为默认开启，
> 被排除的只是"作者主动关掉开关"的少数。选择器须按大结果集设计
> （关键词搜索 + 分页 + 筛选），**不可假设候选量小到能平铺**。

### AC5 — 生效时校验内容有效性（L1）

**Then** 排期中的配置到达开始时间时，校验对应内容仍然有效（未下架 / 未删除 / 作者未封禁）`[L1]`
**And** 校验不通过 → 该条**不生效**，列表标记「内容失效未生效」，坑位按 FR-68 回退为普通内容填充 `[L1]`
**And** PROMO 类型不涉及此校验（不对应真实帖子）`[L0]`

> ⚠️ 「到达开始时间时校验」不等于"要建扫描器"——**在读取生效配置的那个查询里连带判定即可**
> （AD-9 Rule 2）。为这条建扫描器就是给自己加一个状态机。

### AC6 — 编辑 / 提前下线 / 历史（L1）

**Then** 排期中与生效中的配置均可编辑，或手动提前结束（写 `terminated_at`）`[L1]`
**And** 已结束的配置保留可查，供运营复盘 `[L1]`

### AC7 — 推广卡片图的比例规格（L1 / L2）

**Then** 推广卡片图**须落在 0.75~1.34 区间**内 `[L1]`
**And** 超出区间**给出带具体裁切百分比的警告，但不阻止上传** `[L1]`

> 推广卡片在 Feed 里渲染的就是一张普通内容卡，同样受 FR-71 的 clamp + cover 裁切约束。
> 原 PRD 只写"填写图片+标题+跳转目标"、没给规格 —— 运营按常见的 16:9 banner 出图，
> 进 Feed 会被左右各裁掉约 25%，而他不会知道。
> 警告文案须写清**裁切方向与百分比**，不可笼统写"会被裁切"。

### AC8 — 时区（L1）

**Then** 开始/结束时间按 **WIB（`Asia/Jakarta`）** 解释，入库转 UTC `[L1]`
**And** 🛡 **时间输入控件旁必须明示「WIB」字样** `[L1]`

### AC9 — 权限与导航（L0 / L1）

**Then** 🛡 **双权限码**：查看一个、编辑（新建/编辑/提前下线）一个 `[L0]`
**And** 🛡 **入口门与侧栏 `sec:authorize` 条件逐字一致** `[L0]`
**And** 权限码进 `AdminPermissions.java` 常量 + 勾选清单 + i18n 显示名 `[L0]`
**And** 🛡 改导航名时**四处同源**：侧栏名 / 页内标题 / 页面说明 / 权限显示名 `[L0]`

> ⚠️ 权限码一旦落地即**冻结**（改名会切断已授予关系），只有显示名可改。
> 新功能**不做回填迁移** —— 不给存量账号自动授予新权限才是对的。

---

## Tasks / Subtasks

- [x] **T1 · 服务层**（AC1、AC3、AC5）
  - [x] 生效判定：`[starts_at, COALESCE(terminated_at, ends_at))`，🛡 无状态列、无扫描器
  - [x] 🔴 同坑位重叠校验，**半开区间**判定
  - [x] 生效时的内容有效性校验，连带在读取查询里做
- [x] **T2 · 列表 / 新建 / 编辑 / 提前下线页面**（AC1、AC2、AC6、AC8）
  - [x] 坑位下拉（本版本仅 `HOME_FEED`），按"以后会有多个"实现，不写死
  - [x] 时间输入旁标「WIB」
- [x] **T3 · 内容选择器**（AC4）
  - [x] 🛡 只返回公开内容；🔴 用代码枚举 `DAILY`/`KNOWLEDGE`/已同步 `GROWTH_MOMENT`
  - [x] 按大结果集设计：搜索 + 分页
- [x] **T4 · 推广卡片图比例警告**（AC7）
- [x] **T5 · 权限与导航**（AC9）
- [x] **T6 · 测试**
  - [x] L1：🔴 同坑位重叠被拦（含"首尾相接不算重叠"的边界）
  - [x] L1：🛡 未同步的 Diary 不出现在选择器结果里
  - [x] L1：内容被下架 → 该配置不生效且标注
  - [x] L1：提前结束写 `terminated_at`、不动 `ends_at`
  - [x] L0：侧栏条件与入口门条件逐字一致（可参照既有渲染烟测的写法）

---

## Dev Notes

### 已经具备的，别重做

| 要用的 | 已有 | 位置 |
|---|---|---|
| 顶置表（含 slot 可扩展、promo 三列、terminated_at） | ✅ | `V108__create_content_pins.sql` |
| 生效判定口径（查询时算，无状态列） | ✅ 已定 | AD-9 Rule 2 + V108 注释 |
| App 侧读取与渲染 | ✅ 已交付 | Epic 4（story 4-1~4-3） |

### 🔴 本 story 最容易漏的一条

**同坑位时间重叠校验在整个系统里不存在。** 它不在表上（没约束）、不在 App 侧（读取只取一条）。
后台是唯一的写入方，也就是唯一能挡住它的地方。漏了不会报错 —— 只会让 Feed 顶置变成随机的。

### References

- [Source: V1.1.6/v1-1-6后台prd.md#AB-10A]
- [Source: petgo-backend/src/main/resources/db/migration/V108__create_content_pins.sql] 表设计与全部既定取舍
- [Source: _bmad-output/planning-artifacts/architecture-v1.1.6-delta.md] AD-8（坑位是字段）/ AD-9（无状态列无扫描器、时区）

---

## Dev Agent Record

### Context Reference

story 自带上下文 + 实读 Story 4.1 已交付的 `ContentPinService` / `ContentPinRepository` / `FeedService` 坑位取数。

### Agent Model Used

claude-opus-5[1m]

### Debug Log References

- 本 story 用例：`AdminContentPinIntegrationTest` **12 passed**
- 渲染烟测：`AdminPagesRenderSmokeTest` **5 passed**（新页已纳入双 locale 渲染 + 标题与导航名一致）
- 后端全量：**1921 passed / 0 failed**（首轮 4 红：1 条是权限计数守门需更新——见下；另 3 条通知未读数属既有偶发，已定位并证明与本改动无关）
- ⚠️ 后端测试须 `DB_NAME=petgo_rebased`（本分支专用库）

### Completion Notes List

**🔴 先更正一处我在拆 story 时写错的判断。**
story 的 AC3 原文写「同坑位时间重叠校验**在整个系统里不存在**」。**这是错的。**
它早在 Story 4.1 就写好了 —— `ContentPinService.schedule()` → `requireNoOverlap()`，
而且首尾相接不算重叠的半开区间语义也在，`findOverlapping` 连 `excludeId` 参数都预留了。
我当时只看了「表上无约束 + App 侧读取只取一条」就下了结论，**没去看写入服务**。

**所以本 story 的实际性质是"接上来"，不是"从零实现"。** Story 4.1 的类注释把这件事说得很清楚：
「本 story 没有对外接口 —— 后台配置界面（AB-10A）不在本轮范围，运营还无处配置。
本类交付的是**机制**，供后台接上来之后直接调用」。本 story 就是那个「接上来」。
测试的重点因此从"验证机制"改成了**"后台这条路径真的走到了那套既有机制"**。

**服务层只补了三个缺口**（其余全部复用）：
- `update()` —— 编辑时重叠校验**排除自身**（否则"把自己的窗口往后挪一小时"会被自己拦住）
- `terminateNow()` —— 手动提前结束，委托领域对象既有的 `terminateAt()`（自带幂等 + 保证 `terminated_at <= ends_at`）
- `listBySlot()` —— 后台列表要含已结束的历史

**🔴 抽出了一份唯一的「内容还能不能对外展示」判定**（`ContentDisplayability`）。
它原本是 `FeedService` 里的私有 `isDisplayable`。顶置有两个读它的地方：App 的 Feed（决定坑位渲染什么）
与后台列表（决定要不要标「内容失效未生效」）。**各写一遍的表现最难查 ——
后台显示「生效中」而 App 上那个坑位是空的**，运营会以为是缓存或故障，日志里什么都没有。
判定口径一字未改，只是搬了位置 + 让 FeedService 委托它。

**⚠️ 一处口径分歧，我按"跟随现状"处理并留档待产品定。**
本 story 的 AC5 原文写内容有效性含「**作者账号未被封禁**」，但 **Feed 侧今天并不按作者封号过滤内容**
（`isDisplayable` 只看 已删 / 非 PUBLISHED / 非 PUBLIC）。
若只在后台加这一条，就会造出反方向的谎：**后台说失效、App 上照样在展示**。
所以 `ContentDisplayability` 严格跟随 Feed 的现有口径。
🔴 **「Feed 是否也该排除封号作者的内容」是一个独立问题，需要产品定，定了两处一起改。**
（注销已被覆盖 —— 那会把状态翻成 `AUTHOR_DEACTIVATED`；举报预处置也被覆盖 —— 翻成 `UNDER_REVIEW`。
真正没盖住的只有"账号被封号但内容状态没动"这一种。）

**导航另起了一个「内容运营」分组，没有塞进既有的「内容」分组。**
理由是那个分组的次序是产品 2026-08-20 逐项指定过的（种子内容发布→内容管理→评论管理→人工复核→用户→被举报用户），
往中间插一项会打乱它，而且渲染烟测 `contentNavKeepsTheProductSpecifiedOrder` 正盯着那个顺序。
新分组对应后台 PRD 的模块 15，Story 11.2（装饰标签）会继续往里加项。

**推广卡片的比例警告在浏览器里量，不需要上传也不需要服务端抓图。**
把 URL 塞进一个隐藏 `<img>`，`load` 后读 `naturalWidth/naturalHeight` 算比例。
超出 0.75~1.34 时给**带方向与百分比**的警告（16:9 → 「左右各裁切约 25%」），🛡 只警告、不阻止提交。
（本 story 的推广图仍是 URL 字段；上传控件是 12-2 的范围。）

**踩到并修掉的两处（都是我测试写错）**
1. **POST 全部 403，GET 正常。** 我先前只看到 `csrf.disable()` 就以为不用带令牌 ——
   那行在**API 链**上，`/admin/**` 那条链**保留了 CSRF**。少了 `.with(csrf())` 拿到的是 403，
   **极易被误读成「权限门没放行」**，而权限完全正确。已在测试里写明这一点。
2. 第一轮补 CSRF 时用正则匹配 `post("/admin/content-pins...")`，**漏掉了两条用字符串拼接 URL 的**
   （`"/admin/content-pins/" + id + "/edit"`），于是那两条还红着。

**零 Flyway 迁移**（表和列都在，权限码是代码常量、刻意不做回填）。CI `flyway-guard` 无需触发。

**权限计数守门要一起改。** `AdminPermissionsTest.listStableSize` 钉着权限码总数（45 → 47），
首轮全量因此红了一条。**这条红是对的** —— 它守的是「新增权限码是件需要被看见的事」：
权限码一旦落地即冻结（改名会切断已授予关系），所以每加一个都应当在那份账里留一行，
而不是让数字悄悄变大。已按格式补了一行说明。

**另外 3 条通知未读数的红，查清了不是本改动造成的。** 证据链（不是"应该不是我的"）：
- 基线（暂存我的全部改动后）单跑该类：**19/19 通过**
- 带我的改动单跑该类：**19/19 通过**
- 首轮全量：挂了 3 条；与另一个类同跑：挂的是**第 4 条不同的用例**
- 第二轮全量：**0 失败**

**同一份代码不同运行挂不同用例 ⇒ 是不确定性，而确定性的代码改动不会产生这种表现。**
根因看得见：那几条断言的是**绝对未读数**（如「新建用户 → 未读必须为 0」），
而日志里能看到 `milestone completed: code=C-S1 source=SYSTEM_AUTO` 这类**异步**里程碑完成会写通知行 ——
时序凑巧对上就会多出一条。
⚠️ **这是那个测试类自身的既有隐患，不在本 story 范围**：修法是把绝对计数改成相对差值、
或在断言前等异步落定。此前 Story 7.2 的完成记录里也记过一次同类现象，
当时未能定位（surefire 报告被后续运行覆盖），**这次定位到了**。

**留 L2 的**：真机/浏览器上看比例警告的实际观感、HTMX 选择器的交互手感、`data-confirm` 弹窗。

### File List

**新增**
- `petgo-backend/src/main/java/com/tailtopia/content/service/ContentDisplayability.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/pin/dto/PinRow.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/pin/dto/PinnableContentRow.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/pin/service/AdminContentPinService.java`
- `petgo-backend/src/main/java/com/tailtopia/admin/pin/web/AdminContentPinController.java`
- `petgo-backend/src/main/resources/templates/admin/content-pins.html`
- `petgo-backend/src/test/java/com/tailtopia/admin/pin/AdminContentPinIntegrationTest.java`

**修改**
- `petgo-backend/src/main/java/com/tailtopia/content/domain/ContentPin.java`（`reschedule` / `retarget`）
- `petgo-backend/src/main/java/com/tailtopia/content/service/ContentPinService.java`（`update` / `terminateNow` / `listBySlot`）
- `petgo-backend/src/main/java/com/tailtopia/content/service/FeedService.java`（`isDisplayable` 改为委托唯一判定）
- `petgo-backend/src/main/java/com/tailtopia/content/repository/ContentPinRepository.java`（列表查询）
- `petgo-backend/src/main/java/com/tailtopia/content/repository/ContentPostRepository.java`（`searchPinnable`）
- `petgo-backend/src/main/java/com/tailtopia/admin/account/domain/AdminPermissions.java`（两个权限码 + 分组）
- `petgo-backend/src/main/resources/templates/admin/layout.html`（「内容运营」分组 + 顶置入口）
- `petgo-backend/src/main/resources/i18n/messages_{zh_CN,en,id}.properties`（各 34 条）
- `petgo-backend/src/test/java/com/tailtopia/admin/web/AdminPagesRenderSmokeTest.java`（纳入新页）
- `_bmad-output/implementation-artifacts/sprint-status-v1.1.6.yaml`

---

## Change Log

| 日期 | 变更 |
|---|---|
| 2026-08-21 | 由后台 PRD 校验后拆出，status = ready-for-dev |
| 2026-08-21 | 实现完成：后台顶置管理页接上 Story 4.1 已交付的机制；12 条测试；更正 AC3 一处错误判断；status = review |
