---
baseline_commit: 5a9ccb7
---
# Story 3.6: 兽医端队列、进行中会话与 Toast

Status: review

> V1.1 **Epic 3 第六个** story，**后端小补一个只读 GET + 前端为主**。承接 3-2（`consult_requests` QUEUEING 入队 + 广播在线兽医）+ 3-3（`POST /vet/consultations/{token}/accept` CAS 接单 + 开 1.5min 支付窗 + `goBusy`）+ 3-4（支付/暂停/取消/超时回退）+ 3-5（用户端下单三屏）。本 story 补**兽医端计费队列读端点 `GET /vet/consultations/queue`** + **改造工作台「待接单」Tab 走计费队列**（当前 Inbox 只驱动 V1.0 免费直连流，计费流广播来的请求**无处落地**）+ **FR-53A「等待用户支付（剩余 X 秒）」倒计时中间态** + **FR-53B 取消/超时/未支付 3 秒 Toast**（补 `p-vet-active` 死链、核 5 tab 无死链）。
> 源：`epics-v1.1.md` Story 3.6（`GET /vet/queue` + `GET /vet/active`；队列与进行中会话均有落地页、5 tab 无死链 L0/L1；取消/超时/未支付 3 秒 Toast L0；真机双端 L2）· FR-53A（接单队列「等待支付」倒计时中间态）· FR-53B（取消/超时/未支付 3s Toast 通知兽医）· UX-DR12（`p-vet-active` 补死链）· 架构 `architecture-v1.1-delta.md`（`/vet/queue`·`/vet/active`·`/vet/income` 端点表 line 236；服务端权威计时 `p-vet-queue-pay` 中间态 line 187）。
> **⚠️ 现状（已核实，务必理解 brownfield）**：
> ① **计费队列在兽医端零 UI**：3-2 广播 `NEW_CONSULT_REQUEST` 给在线兽医，但兽医工作台**没有承接页**——现有 `VetInboxPage`（待接单 Tab）只调 V1.0 `GET /vet/consult-sessions/waiting`（免费直连流 `consult_sessions`，`sessionId` 寻址）。计费流请求（`consult_requests`，`requestToken` 寻址）广播出去后**无处可见、无法接单**。这是本 story 要补的核心缺口（3-3 [OPEN] 明记「兽医端队列读源留 3-6」）。
> ② **进行中会话「死链」在真机已解**：3-4 支付成功即建 `consult_sessions`(IN_PROGRESS, source=DIRECT, vet_id)，被**既有** `GET /vet/consult-sessions/in-progress` + `VetActivePage` 命中（`findByVetIdAndStatusInOrderByCreatedAtDesc`）。故「进行中会话落地页」**已存在**——`p-vet-active` 死链是原型 HTML 遗留，App 内已接线。本 story **复用既有 Active Tab，不新建 `/vet/active` 端点**（避免重复造轮子；见 Dev Notes「关键设计决策 D-2」）。
> ③ **`consult_requests` 不存病例**（3-1 表无 symptom/AI 等级列，见 3-2 [OPEN]）。计费队列卡**只能展示宠物身份 + 等待时长**，**无 AI 危险等级/症状摘要/照片**（与免费 `VetInboxPage` 的富卡不同）——dev 勿渲染不存在的字段。
> **范围边界**：本 story = `GET /vet/queue`（QUEUEING 池 + 本人 ACCEPTED_AWAIT_PAY 待支付项）+ 待接单 Tab 改造（计费队列 + 接单 CTA + FR-53A 倒计时中间态 + FR-53B Toast）+ 复用 Active Tab（进行中会话）+ 核 4 tab 无死链 + model/repo/api_paths/i18n。**不做**：兽医到手金额/月度收入（3-7，`/vet/income`）、Konsultasi UI 定稿 + 封禁挂起逃生（3-8）、既有 V1.0 免费直连流端点/`VetActivePage`/`VetHistoryPage`/`VetMePage` 改动、后端接单/支付/回退逻辑（3-3/3-4 已实现，本 story 只调既有 `POST /vet/consultations/{token}/accept`）、定向推送精确原因（本 story Toast 走前端轮询推断，见 D-4）。

## Story

As a 兽医,
I want 接单队列（计费流）、「等待用户支付」倒计时中间态、进行中会话页、取消/超时/未支付 Toast,
so that 我能看到并接计费流的问诊、知道接单是否会成、不空等（FR-53A/53B，补 p-vet-active 死链，UX-DR12）。

## Acceptance Criteria

1. **后端：兽医计费队列读端点 `GET /vet/consultations/queue`（补 3-2~3-3 兽医侧读缺口）（L0/L1）**
   **Given** 3-2 广播的 QUEUEING `consult_requests` + 3-3 本人接单后的 ACCEPTED_AWAIT_PAY 请求（`goBusy` 占用）
   **When** 在线兽医轮询队列
   **Then** 新增 `GET /api/v1/vet/consultations/queue`（`hasRole('VET')` 由 `/api/v1/vet/**` SecurityConfig 已门控，`vetId` 取自 JWT）→ `VetQueueResponse{awaitingPay: VetAwaitingPayItem?, available: List<VetQueueItem>}`：
   - `awaitingPay` = 本兽医当前 `ACCEPTED_AWAIT_PAY` 请求（`findFirstByVetIdAndState`），含 `{requestToken, petName?, payDeadlineAt(服务端权威 timestamptz), pausedAt?}`——供 FR-53A「等待支付」倒计时中间态；无则 `null`（Jackson NON_NULL 省略）
   - `available` = **仅当兽医不忙时**（`!presence.isBusy(vetId)`）返回未过期 QUEUEING 池（`state=QUEUEING`，按 `createdAt` 升序 FIFO），每项 `{requestToken, petName?, petSpecies?, petAgeMonths?, ownerHandle?, waitingSeconds, queueDeadlineAt}`；兽医忙（接单中/会话中）→ `available` 空（不能再接）
   （**L0** DTO 契约 / **L1** 端点/鉴权/富化/占用过滤）
   **And** 宠物身份富化**复用 `PetProfileQueryService.findIdentitiesByOwners(userIds)`**（批量按 `userId` 取 `PetIdentityView{name,species,ageMonths}`，避免 N+1；不直访 profile repo，保模块边界，照 `VetConsultService.waitingList` 范式）；机主昵称经 `AccountQueryService`（可选，同 waitingList）；**无病例字段**（`consult_requests` 不存 symptom/AI 等级——不下发这些字段）（**L1**）
   **And** `ConsultRequestService` 加只读 `vetQueue(vetId)`（`@Transactional(readOnly)`）；`ConsultRequestRepository` 加 `findFirstByVetIdAndState` + QUEUEING 池查询（未过期，`queueDeadlineAt >= now` 或直接取 QUEUEING 由前端/扫描器兜底过期）；无迁移（**L1**）

2. **前端：改造「待接单」Tab 走计费队列（GET /vet/queue）（L0/L2）**
   **Given** 计费流广播的 QUEUEING 请求
   **When** 兽医进工作台待接单 Tab
   **Then** `VetInboxPage` 数据源从 V1.0 `waitingList()`（免费流）**改为 `vetQueue()`（计费流）**：`available` 列表渲染计费队列卡（宠物身份块 + 等待时长 + **接单 CTA**）；点接单 → `POST /api/v1/vet/consultations/{requestToken}/accept`（3-3 既有）→ 200 拿 `payDeadlineAt` → 刷新队列（进入 FR-53A 中间态，AC3）；**409（被抢/已过期/占用）→ 3s Toast + 刷新**（照抢单先到先得，H-4）（**L0**）
   **And** 计费队列卡**仅展示身份 + 等待时长**（`consult_requests` 无 AI 等级/症状/照片）——**不复用**免费 `_InboxCard` 的等级色条/AI 摘要/危险徽章（那些字段计费流没有）；空态复用 `VetEmptyState`（`vetInboxEmpty`）；顶部统计卡（队列/完成/评分）保留，队列数 = `available.length`（**L0** / 模拟器 **L2**）

3. **前端：FR-53A「等待用户支付（剩余 X 秒）」倒计时中间态（L0/L2）**
   **Given** 本兽医接单成功（`awaitingPay != null`）
   **When** 停在待接单 Tab
   **Then** 待接单 Tab **顶部置顶「等待支付」卡**：显示宠物名 + 「等待用户支付 剩余 MM:SS」（`payDeadlineAt - now(client)`，**服务端权威 deadline**，纯显示，照 3-5 `vet_timed_pay_page` 倒计时范式）；此时 `available` 为空（兽医忙不能再接）→ 只显置顶卡；轮询（~3-5s）`vetQueue()` 驱动状态跃迁（**L0** / 模拟器 **L2**）
   **And** `pausedAt != null`（用户跳充值暂停中，A-4）→ 倒计时**暂停显示**（不继续走），文案「用户正在充值…」；恢复后 `payDeadlineAt` 已由服务端顺延，倒计时续（前端只读服务端权威值，不本地算，照 3-5 A-4 范式）（**L0**）

4. **前端：FR-53B 取消/超时/未支付 3 秒 Toast（前端轮询推断）（L0/L2）**
   **Given** 兽医处于「等待支付」中间态（`awaitingPay` 上一轮非空）
   **When** 轮询发现 `awaitingPay` 变为 `null`（请求已被处理：支付成功转单删 / 用户取消删 / 支付窗超时回退或彻底失败）
   **Then** 前端据轮询推断结果（**决策 D-4：前端轮询推断，不依赖后端定向推送**）：
   - **已支付**：拉 `GET /vet/consult-sessions/in-progress`，出现新 IN_PROGRESS 会话（兽医接单中恒仅 1 单，`goBusy` 互斥）→ 成功 Toast「用户已支付，会话已开始」+ 刷新/切到进行中 Tab（不强制导航，兽医自点卡进会话）
   - **未成交（取消/超时/未支付）**：无新会话 → **3 秒 Toast**「接单已取消或超时未支付」（合并原因，用户已选此粒度；`showAppToast` 3s）+ 回队列池态（`available` 重新可见，兽医可再接）（**L0** / 模拟器双端 **L2**）
   **And** Toast 走既有 `shared/widgets/app_toast.dart` `showAppToast`（3s）；轮询在 `dispose`/终态 `timer.cancel`；退后台停轮询、回前台立即拉一次（照既有 `VetInboxPage._pollInterval` 生命周期范式）（**L0**）

5. **前端：进行中会话复用 + 核 tab 无死链（补 p-vet-active，UX-DR12）（L0/L1）**
   **Given** 3-4 支付成功建的 `consult_sessions`(IN_PROGRESS, source=DIRECT, vet_id)
   **When** 兽医看进行中 Tab
   **Then** **复用既有 `VetActivePage` + `GET /vet/consult-sessions/in-progress`**（`findByVetIdAndStatusIn` 已命中付费会话，无需新 `/vet/active` 端点，D-2）；点卡进 `/vet/conversation/:id`（既有 IM 会话页）；**核对工作台 4 tab（待接单/进行中/历史/我的）均有落地页、无死链**（`p-vet-active` 原型死链在 App 内已由 `VetActivePage` 承接）（**L0** 路由核对 / **L1** 端点命中付费会话）

6. **前端：model + repository + api_paths + i18n（L0）**
   **Given** 队列改造
   **When** 接线
   **Then** `features/vet/domain/` 加 `VetQueue`(`awaitingPay: VetAwaitingPay?` + `available: List<VetQueueItem>`) + `VetQueueItem`(requestToken/petName/petSpecies/petAgeMonths/ownerHandle/waitingSeconds/queueDeadlineAt) + `VetAwaitingPay`(requestToken/petName/payDeadlineAt/pausedAt)，手写 `fromJson`（无 freezed，照 `vet_workbench_lists.dart`）；`VetRepository` 加 `vetQueue()` + `acceptConsultRequest(String requestToken)`；`ApiPaths` 加 `vetConsultationsQueue` + `vetConsultationsAccept(token)`；`app_id.arb`+`app_en.arb` 加计费队列/等待支付/Toast 文案（印尼语主）→ `flutter gen-l10n`（**L0**）

7. **前端：静态验收（L0）**
   **Given** 全部实现
   **When** 验证
   **Then** `flutter analyze` **零警告**、`flutter test` 全绿（含新 model fromJson + queue 页 widget 测试：available 渲染接单 CTA、awaitingPay 显倒计时、轮询 awaitingPay 消失→Toast 分支、409 接单失败 Toast；照 `triage_paywall_test`/既有 vet 页测试 fake repo + `DioException(409)` 范式）；`microcopy_rules_test` 通过（**L0**）
   **And** 后端 `mvn -B test` 绿（新 `VetQueueResponse` DTO L0 + `GET /vet/consultations/queue` L1）；模拟器/真机双端全链路（兽医接单→倒计时→用户支付建会话 / 用户取消→Toast）留本地（**L2**，云端 headless 只跑 L0）

## Tasks / Subtasks

> 后端 → 前端 → 联调。先补后端 `GET /vet/queue`（前端轮询依赖）→ 前端 model/repo/api_paths → 待接单 Tab 改造（队列 + 接单 + FR-53A 中间态 + FR-53B Toast）→ 核 Active Tab 复用/无死链 → i18n → analyze/test。**后端无迁移**。**先读**：后端 `consult/web/VetConsultRequestController`(3-3 accept)/`consult/service/ConsultRequestService`(statusOf/acceptRequest 范式)/`consult/repository/ConsultRequestRepository`/`consult/service/VetConsultService`(waitingList 富化范式 + PetProfileQueryService)/`consult/dto/{VetInboxItem,ConsultAcceptResponse}`；前端 `features/vet/presentation/{vet_workbench_shell,vet_inbox_page,vet_active_page}.dart`/`features/vet/data/vet_repository.dart`/`features/vet/domain/{vet_workbench_lists,vet_inbox_item}.dart`/`features/consult/presentation/vet_timed_pay_page.dart`(3-5 倒计时/A-4 范式)/`core/network/api_paths.dart`/`shared/widgets/app_toast.dart`/`l10n/app_id.arb`（Dev Notes 已附精确锚点）。

- [x] **T1 后端：repo 补方法**（AC1）
  - [x] `ConsultRequestRepository`：`Optional<ConsultRequest> findFirstByVetIdAndState(long vetId, ConsultRequestState state)`（取本人 ACCEPTED_AWAIT_PAY）；QUEUEING 池查询 `List<ConsultRequest> findByStateOrderByCreatedAtAsc(ConsultRequestState state)`（FIFO；不加时间谓词，依赖 3-2 scanner + 前端接单 409 兜底——D-3）。**L0/L1** ✅
- [x] **T2 后端：队列读 service + DTO**（AC1）
  - [x] `ConsultRequestService.vetQueue(long vetId)`（`@Transactional(readOnly)`）：`awaitingPay` = `findFirstByVetIdAndState` 富化；`available` = `isBusy(vetId) ? [] : findByStateOrderByCreatedAtAsc(QUEUEING)` 批量富化（注入 `PetProfileQueryService.findIdentitiesByOwners` + `AccountQueryService.findAuthorViews` 昵称）。**L1** ✅
  - [x] `consult/dto/VetQueueResponse{VetAwaitingPayItem awaitingPay, List<VetQueueItem> available}` + 嵌套 `VetAwaitingPayItem{requestToken, petName, payDeadlineAt, pausedAt}` + `VetQueueItem{requestToken, petName, petSpecies, petAgeMonths, ownerHandle, waitingSeconds, queueDeadlineAt}`（`@JsonInclude(NON_NULL)`；`waitingSeconds` = `now - createdAt` 照 `VetInboxItem.of`）。**L0** ✅ `VetQueueResponseTest` 4/4 绿
- [x] **T3 后端：兽医队列端点**（AC1）
  - [x] `VetConsultRequestController` 加 `@GetMapping("/queue")` → `GET /api/v1/vet/consultations/queue`（`currentVetId(jwt)`）→ `service.vetQueue(vetId)`。无 SecurityConfig 改动（`/vet/**` 已 hasRole VET）。**L1** ✅
  - [x] L1 `VetQueueIntegrationTest`（scratch 库 petgo_l1，@BeforeEach 清库隔离）：2 QUEUEING → GET queue → `available` 2 项 + 身份富化 + FIFO；accept 一单后 GET → `awaitingPay` 非空（payDeadline 存在）+ 本兽医 `available` 空（busy）+ 另一不忙兽医仍见剩余池；非 VET 403；缺 JWT 401。**L1** ✅ 3/3 绿
- [x] **T4 前端：model + api_paths + repository**（AC6）
  - [x] `features/vet/domain/vet_queue.dart`：`VetQueue`/`VetQueueItem`/`VetAwaitingPay` + `fromJson`（`payDeadlineAt`/`queueDeadlineAt` 解析 `DateTime.parse().toUtc()`；nullable 身份字段兜底；`VetAwaitingPay.isPaused`）。**L0** ✅
  - [x] `api_paths.dart` 加 `vetConsultationsQueue` + `vetConsultationsAccept(token)`。**L0** ✅
  - [x] `vet_repository.dart` 加 `Future<VetQueue> vetQueue()` + `Future<void> acceptConsultRequest(String requestToken)`（POST accept，409 由调用方 catch 映射 Toast）。**L0** ✅
- [x] **T5 前端：待接单 Tab 改造（队列 + 接单 + FR-53A + FR-53B）**（AC2/AC3/AC4）
  - [x] `vet_inbox_page.dart` 数据源改 `vetQueue()`；`available` 渲染新 `_QueueCard`（身份 + 等待时长 + 接单 CTA，**不复用免费流 `_InboxCard` 富卡**）；`awaitingPay != null` → 顶部置顶 `_AwaitingPayCard`（服务端权威 MM:SS 倒计时 + `pausedAt` 暂停显示）。**L0** ✅
  - [x] 接单：CTA → `acceptConsultRequest(token)` → 成功 `_reloadQueue`（进 awaitingPay 态）；`DioException(409)` → `showAppToast` 3s + 刷新。**L0** ✅
  - [x] FR-53B Toast：`_prevAwaitingToken` 记上一轮待支付项；`_reloadQueue` 后 `_detectAwaitingTransition`：prev 非空&now 空 → 拉 `activeSessions` 判新会话（有→`vetQueuePaidStarted` 成功 Toast；无→`vetQueueOrderFellThrough` 3s Toast + 回池态）。`Timer.periodic(4s)` 轮询 + `Timer.periodic(1s)` 倒计时显示；`dispose`/退后台 cancel。**L0** ✅
- [x] **T6 前端：核 Active Tab 复用 + 无死链**（AC5）
  - [x] 核对（后端已验）：3-4 支付成功建 `consult_sessions`(IN_PROGRESS, source=DIRECT, vet_id) 被既有 `findByVetIdAndStatusInOrderByCreatedAtDesc(vetId,[IN_PROGRESS])` 命中 → `VetActivePage` 承接付费会话。工作台 4 tab（`VetInboxPage`/`VetActivePage`/`VetHistoryPage`/`VetMePage`）均有落地页无死链；**未改** Active/History/Me/Shell。**L0/L1** ✅
- [x] **T7 前端：i18n**（AC6）
  - [x] `app_id.arb`+`app_en.arb` 加：`vetQueueAccept`/`vetQueueAcceptFailed`/`vetQueueAwaitingPayTitle`/`vetQueueAwaitingPaySubtitle`/`vetQueueAwaitingPaySubtitleNamed`/`vetQueueAwaitingPayRemaining`/`vetQueuePausedHint`/`vetQueuePaidStarted`/`vetQueueOrderFellThrough`（印尼语主）+ 复用 `vetQueueWaitJustNow`/`vetQueueWaitMinutesAgo`/`vetSpeciesCat|Dog`/`vetAgeMonths|Years`/`vetInboxEmpty`/`vetInboxDirect`/`vetDashboard*` → `flutter gen-l10n`。**L0** ✅
- [x] **T8 测试 + 静态**（AC7）
  - [x] `test/vet/vet_queue_model_test.dart`（fromJson + nullable + isPaused，5 例）+ 重写 `test/vet/vet_inbox_test.dart`（计费队列卡 + 接单 CTA/成功/409 Toast + FR-53A 倒计时/暂停 + FR-53B 成交/未成交 Toast，9 例）+ 改 `test/vet/vet_dashboard_test.dart`（fake 改 `vetQueue()`）。**L0** ✅
  - [x] `flutter analyze` **零警告** + `flutter test` 全绿（404 passed；唯一失败 `story_1_5_gating_test` 已 git stash 于 baseline 复现 → 既有失败，与本 story 无关）；后端 `mvn -B test`：L0 `VetQueueResponseTest` 4/4 + L1 `VetQueueIntegrationTest` 3/3 + consult/vet 模块回归绿。**L0** ✅
- [ ] **T9 模拟器/真机双端验收（L2，留本地）**
  - [ ] Android 模拟器逐屏视觉（队列卡 + 接单后「等待支付 MM:SS」置顶卡）+ 双端全链路（兽医接单→倒计时→用户端 3-5 支付建会话 / 用户取消→兽医 Toast）。**⚠️ 需本地起本分支后端**（`/vet/consultations/queue` 仅本分支、未上线 prod；App 默认连 api.tailtopia.id）+ 兽医号登录 + 用户端驱动。**L2 待本地联调**（云端 headless 只跑 L0，照根 CLAUDE.md 约定）

## Dev Notes

> 后端小补（一个只读 GET，富化复用 `PetProfileQueryService`）+ 前端为主（改造待接单 Tab）。**不重复造轮子**：倒计时照 3-5 `vet_timed_pay_page`、富化照 `VetConsultService.waitingList`、轮询生命周期照既有 `VetInboxPage`、Toast 照 `app_toast.dart`、进行中会话直接复用既有 `VetActivePage`。**接单动作直接调 3-3 既有端点**（不新建 accept）。

### 承接现状（已核实，照此复用/勿改）

- **3-3 接单端点（既有，直接调）**：`POST /api/v1/vet/consultations/{requestToken}/accept`（`VetConsultRequestController`）→ `ConsultAcceptResponse{requestToken, state=ACCEPTED_AWAIT_PAY, payDeadlineAt}`。CAS `tryAccept`（`WHERE state=QUEUEING`）+ `goBusy` 占用；被抢/过期/已 busy → **409**「该请求已被接单或已过期」/「您有进行中的接单」。本 story 前端接单 CTA 调此，409 映射 Toast。
- **3-3/3-4 支付窗超时/取消/支付（后端已实现，本 story 只观测）**：支付窗 90s 超时 → `revertExpiredAcceptances`（回 QUEUEING 重播 或达上限彻底失败删+`goAvailable`）；用户取消 → `cancelRequest`（物理删 + `goAvailable`）；支付成功 → 转 `consult_orders` + 建 `consult_sessions`(IN_PROGRESS, source=DIRECT) + 删 request。**这些都使 `awaitingPay` 消失**——前端据此推断 Toast（D-4）。
- **3-4 支付成功建会话（关键，进行中会话复用依据）**：`ConsultPayService` 支付成功 → `ConsultSession.startWaiting(userId, ConsultSource.DIRECT)` + `markInProgress(vetId)` + `attachImConversation` + save（`consult_sessions` IN_PROGRESS）+ `createOrder`。**该会话被既有 `VetConsultService.inProgressList(vetId)`（`findByVetIdAndStatusInOrderByCreatedAtDesc(vetId,[IN_PROGRESS])`）命中** → `VetActivePage` 自动显示。故进行中会话零新增前后端。
- **富化端口（复用，勿直访 repo）**：`PetProfileQueryService.findIdentitiesByOwners(Collection<Long> ownerIds)` → `Map<Long, PetIdentityView{name, species(petType), ageMonths}>`（批量按 `userId`）；`consult_requests` 有 `userId`+`petProfileId`，用 `userId` 批量取（一人一宠模型，`findIdentityByOwner` 同源）。机主昵称 `AccountQueryService`（可选，同 `VetConsultService.waitingList` 的 `ids.handle`）。**照 `VetConsultService.waitingList` 逐字范式**（`resolveIdentities` + `VetInboxItem.of`）。
- **`VetPresenceService`（既有）**：`isBusy(vetId)`（接单中/会话中）→ 队列读用它过滤 `available`（忙则空，不能再接）。在线态纯显式无 TTL（记忆 `vet-presence-explicit-only`）。
- **既有 vet 工作台（4 tab，改 1 处）**：`VetWorkbenchShell`（`IndexedStack` 4 tab：`VetInboxPage`/`VetActivePage`/`VetHistoryPage`/`VetMePage`）；`VetInboxPage`（当前调 `waitingList()` 免费流，有轮询 8s + 退后台停 + 顶部 3 统计卡 + `VetTopBar` 在线开关）。**本 story 只改 `VetInboxPage` 数据源与卡片**，Shell/其余 tab 不动。

### 关键设计决策（dev 照此）

- **D-1 队列语义（`GET /vet/queue` = 池 + 本人待支付项）**：`awaitingPay`（本人 ACCEPTED_AWAIT_PAY，FR-53A 中间态 `p-vet-queue-pay`）+ `available`（QUEUEING 池，忙则空）。二者互斥呈现：`awaitingPay != null` → 只显置顶等待支付卡（兽医忙）；否则显 `available` 抢单池。**不复用免费流 `_InboxCard` 富卡**——计费流无 AI 等级/症状/照片（`consult_requests` 不存，3-1 表 + 3-2 [OPEN]）。
- **D-2 进行中会话复用既有 Active Tab，不新建 `/vet/active` 端点**：架构端点表列 `/vet/active`，但 3-4 付费会话已落 `consult_sessions`(IN_PROGRESS)、被既有 `GET /vet/consult-sessions/in-progress` + `VetActivePage` 命中。**新建 `/vet/active` 是重复造轮子**（checklist「反重复」硬指标）。本 story 复用既有页/端点，AC5 只核「进行中会话有落地页 + 无死链」。`p-vet-active` 死链是原型 HTML 遗留，App 内早已接线（`vet_active_page.dart` Story 5.5 已建）。**偏离架构字面 `/vet/active` 有意为之，Dev Notes 记录理由。**
- **D-3 QUEUEING 池过期过滤**：3-2 scanner 每 30s 物理删过期 QUEUEING，故读端点通常已无过期行；稳妥起见读端点可加 `queueDeadlineAt >= now` 谓词（或不加，靠 scanner 兜底 + 前端 `queueDeadlineAt` 显示）。**dev 择简**：不加谓词、依赖 scanner（V1 规模个位数队列，30s 窗口内的过期项前端接单会 409 兜底，无害）。若加则 repo 用 `findByStateAndQueueDeadlineAtAfterOrderByCreatedAtAsc`。
- **D-4 FR-53B Toast = 前端轮询推断（不依赖后端定向推送）**：已定（用户选此）。兽医轮询 `vetQueue()`；`awaitingPay` 上一轮非空、本轮 null → 请求已被处理。区分：拉 `inProgressList`——有新会话（接单中恒仅 1 单，`goBusy` 互斥保证不歧义）→ 已支付（成功 Toast + 切进行中 Tab）；无 → 未成交（取消/超时/未支付合并，3s「接单已取消或超时未支付」Toast + 回池态）。**原因粒度=成交 vs 未成交**（不区分取消/超时/未支付——`consult_requests` 物理删无痕、后端不透传原因；用户已确认此粒度可接受）。**不动 3-4 service、不加通知类型**（定向推送精确原因是 [OPEN] 增强，留后续/9-x）。
- **D-5 服务端权威倒计时（照 3-5）**：`payDeadlineAt`/`queueDeadlineAt` 皆 DB timestamptz；前端倒计时纯**显示**（`deadline - now(client)`，MM:SS），**跃迁由轮询服务端驱动**，本地归零不自行删。`pausedAt != null`（A-4 跳充值暂停）→ 暂停显示；服务端 resume 顺延后 `payDeadlineAt` 已更新，前端下一轮读到新值续走（前端不本地算暂停顺延）。照 `vet_timed_pay_page.dart` 倒计时 + `_mmss` 范式。
- **D-6 轮询生命周期（照既有 `VetInboxPage`）**：`Timer.periodic(~3-5s)`（比现有 8s 更密，因 FR-53A 倒计时需实时；或本地 1s 显示 timer + 3-5s 拉取 timer 双 timer，照 3-5 `vet_waiting_page`）；退后台 `didChangeAppLifecycleState` 停轮询、回前台立即拉一次；`dispose` cancel。
- **无迁移**（后端仅加只读 GET + DTO + repo 查询方法）。

### Project Structure Notes

- **后端新增**：`consult/dto/VetQueueResponse.java`（含 `VetAwaitingPayItem`/`VetQueueItem` record，或拆文件）；测试 `VetQueueIntegrationTest`(L1) + `VetQueueResponseTest`(L0 DTO 契约)。
- **后端修改**：`consult/web/VetConsultRequestController.java`（+`GET /queue`）、`consult/service/ConsultRequestService.java`（+`vetQueue`；注入 `PetProfileQueryService`/`AccountQueryService`——若未注入则加）、`consult/repository/ConsultRequestRepository.java`（+`findFirstByVetIdAndState`/QUEUEING 池查询）。
- **前端新增**：`features/vet/domain/vet_queue.dart`、`features/vet/presentation/` 计费队列卡 widget（可内联 `vet_inbox_page.dart` 或抽 `widgets/vet_queue_card.dart`）、`test/vet/{vet_queue_model_test,vet_inbox_page_test}.dart`。
- **前端修改**：`features/vet/presentation/vet_inbox_page.dart`（数据源 + 卡片 + FR-53A/53B）、`features/vet/data/vet_repository.dart`（+`vetQueue`/`acceptConsultRequest`）、`core/network/api_paths.dart`（+vetConsultations*）、`l10n/app_id.arb`+`app_en.arb`（+计费队列 keys）→ gen-l10n。
- **不改**：3-3/3-4 后端接单/支付/回退逻辑、V1.0 `VetConsultController`/`VetConsultService.waitingList`（免费流保留、不删——两流并存过渡期，仅兽医入口切计费）、`VetWorkbenchShell`/`VetActivePage`/`VetHistoryPage`/`VetMePage`、`vet_inbox_item.dart`（免费流 model 保留）、`consult_requests` 实体/CAS。

### 前端复用范式（精确锚点）

- **倒计时 + A-4 暂停**：`features/consult/presentation/vet_timed_pay_page.dart`（3-5）——服务端权威 `payDeadlineAt` MM:SS 显示、`pausedAt` 暂停、双 timer（1s 显示 + 3s 轮询）。**FR-53A 置顶卡照此。**
- **轮询生命周期**：`features/vet/presentation/vet_inbox_page.dart:28-79`（`WidgetsBindingObserver` + `_startPoll`/`didChangeAppLifecycleState` 退后台停 + `_reload` 静默保留旧数据不闪）。
- **进行中列表 + IM 未读合并**：`features/vet/presentation/vet_active_page.dart`（既有，复用不改；付费会话自动进此列表）。
- **Toast**：`shared/widgets/app_toast.dart` `showAppToast(context, msg)`（3s 默认）。
- **model fromJson**：`features/vet/domain/vet_workbench_lists.dart`（`VetActiveItem`/`VetHistoryEntry` 手写 `fromJson`，nullable 兜底）；时间戳 `DateTime.parse(...).toUtc()`。
- **repository**：`features/vet/data/vet_repository.dart`（Dio + `ApiPaths`，方法返 model；POST 例 `accept`）。
- **主题**：vet 子树注入 `AppTheme.vet`（薄荷，`_vetScoped`）；主色 `AppColors.vetPrimary`/`AppColors.mint`(#845EC9)；**禁硬编码 hex**。
- **i18n**：印尼语主，两份 ARB 同步，`microcopy_rules_test` 契约守卫；可复用 `vetTabInbox`/`vetInboxEmpty`/`vetDashboardStatQueue`/`vetQueueWaitJustNow`/`vetQueueWaitMinutesAgo`/`vetSpeciesCat`/`vetSpeciesDog`/`vetAgeMonths`/`vetAgeYears`；**新增等待支付/接单/Toast keys**。
- **测试**：`ProviderContainer(overrides:)` + `UncontrolledProviderScope` + `MaterialApp`(挂 AppLocalizations delegates)；fake repo `overrideWithValue`；`DioException(statusCode:409)` 造接单失败；`tester.platformDispatcher.localesTestValue=[Locale('id')]`。⚠️ 前端包名 `tailtopia`（import `package:tailtopia/`）。

### 后端复用范式（精确锚点）

- **富化**：`consult/service/VetConsultService.java`（`waitingList` 的 `resolveIdentities` 批量 + `VetInboxItem.of`；`PetProfileQueryService`/`AccountQueryService` 注入）。
- **兽医端点鉴权**：`consult/web/VetConsultRequestController.java`（`currentVetId(jwt)` 私有静态 + `/api/v1/vet/**` SecurityConfig hasRole VET）。
- **只读 service**：`consult/service/ConsultRequestService.java:102 statusOf`（`@Transactional(readOnly)` + 防枚举范式）。
- **DTO 契约测试**：`consult/dto/ConsultAcceptResponseTest.java`（3-3 L0 record 契约范式）。
- **L1 净库**：scratch 库 `petgo_l1`（`DROP/CREATE DATABASE` + flush Redis DB0 + `DB_NAME=petgo_l1 mvn -B test`——记忆 `shared-dev-db-and-parallel-flyway-collision` 坑 1+3）。

### References

- [Source: epics-v1.1.md#Story 3.6]（`GET /vet/queue`+`GET /vet/active`；队列与进行中会话均有落地页、5 tab 无死链 L0/L1；取消/超时/未支付 3s Toast L0；真机双端 L2）· [#FR-53A]（等待支付倒计时中间态）· [#FR-53B]（取消/超时/未支付 3s Toast）· [#UX-DR12]（p-vet-active 补死链）
- [Source: architecture-v1.1-delta.md#端点表 line 236]（`/vet/queue`·`/vet/active`·`/vet/income`）· [line 187]（服务端权威计时 p-vet-queue-pay 中间态）· [line 285]（P0 p-vet-active 死链：补进行中会话接口+页、核 5 tab）
- [Source: 3-2/3-3/3-4/3-5 story]（QUEUEING 入队+广播 / 接单 CAS+支付窗+`goBusy` / 支付回退取消 / 用户端下单三屏倒计时范式）
- 后端代码：`consult/web/VetConsultRequestController.java`、`consult/service/{ConsultRequestService,VetConsultService}.java`、`consult/repository/ConsultRequestRepository.java`、`consult/dto/{ConsultAcceptResponse,VetInboxItem}.java`、`profile/service/PetProfileQueryService.java`、`vet/service/VetPresenceService.java`。
- 前端代码：`features/vet/presentation/{vet_workbench_shell,vet_inbox_page,vet_active_page}.dart`、`features/vet/data/vet_repository.dart`、`features/vet/domain/vet_workbench_lists.dart`、`features/consult/presentation/vet_timed_pay_page.dart`、`core/network/api_paths.dart`、`shared/widgets/app_toast.dart`、`l10n/app_id.arb`、`test/triage/triage_paywall_test.dart`。

### 待澄清（不阻塞）

- **[OPEN-1] Toast 精确原因**：本 story 前端轮询推断合并「取消/超时/未支付」为「未成交」（用户已定粒度）。若产品要精确区分，后续加后端定向推送（新 `NotificationType` + 在 3-4 `cancelRequest`/`revertExpiredAcceptances` 向接单兽医 `sendToVet`）→ 前端据类型显精确 Toast。留 9-x/后续。
- **[OPEN-2] 免费直连流去留**：本 story 兽医待接单入口切计费队列；V1.0 免费 `waitingList()`/`VetInboxPage._InboxCard` 富卡代码保留不删（两流并存过渡期）。用户端免费直连入口下线属 3-8 Konsultasi UI 定稿；届时免费流卡片可清理。本 story 不删旧代码（降回归风险）。
- **[OPEN-3] 红色分诊免费直达**：红色评级恒免费永不锁（2-2 红线）——红色用户如何免付进兽医会话（跳过 1.5min 支付窗）属计费流特例，本 story 不处理（队列/接单通用逻辑）；留 3-8 或计费流特判定义。
- **[OPEN-4] `available` 是否含忙时**：本 story 忙则 `available` 空（不能接）。若产品要「忙时仍可浏览池但接单 409」，改后端不按 busy 过滤、前端接单 CTA 置灰。默认取「忙则空」最简。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（Claude Code / bmad-create-story）

### Debug Log References

- 后端 L1 `VetQueueIntegrationTest`：连本地 docker postgres+redis，用 scratch 库 `petgo_l1`（`DROP/CREATE DATABASE` + flush Redis DB0，记忆 `shared-dev-db-and-parallel-flyway-collision` 坑 1+3）→ `DB_NAME=petgo_l1 mvn -B test`，Flyway validate 54 迁移通过。首跑 1 例失败（`available` 期望 2 得 3）——**跨用例遗留 QUEUEING 行污染**（`ApiIntegrationTest` 不清 consult_requests）→ 加 `@BeforeEach requests.deleteAll()` 隔离，3/3 绿。
- 后端 `postgres` 角色不存在 → 容器 `POSTGRES_USER=petgo`，改用 `psql -U petgo -d petgo` 建 scratch 库。
- 后端回归 `DB_NAME=petgo_l1 mvn -B test -Dtest='Consult*,Vet*'`：245 跑，1 例 `VetAuthControllerEndpointTest.vetLogin` 500——**大 glob 跨类数据污染**（另一类遗留）；隔离/成对（+我方新类）跑均 6/6、9/9 绿，且我方仅动 consult（vet login 零依赖）→ 既有 batch flake，非本次回归。
- 前端 widget 测试踩 `Timer still pending`：① Inbox 周期轮询 Timer + `awaitingPay` 倒计时 setState → **禁 `pumpAndSettle`**（永不 settle），改 `pump()`+`pump(100ms)`（照 `vet_workbench_test`）；② 3s toast 静态计时器 → 断言后 `pump(4s)` 烧完清浮层（`_drainToast`）。
- 前端 `_accept` 曾乐观预置 `_prevAwaitingToken=token` → reload 前误判「未成交」误报 Toast（测试暴露）→ **删除**，跃迁基线改由 `_reloadQueue` 侦测器据服务端 awaitingPay 建立（更正确）。
- 前端全量 `flutter test`：404 passed，唯一失败 `story_1_5_gating_test.dart:AC2`——**git stash 我方 lib/test 改动后 baseline 复现同一失败**（且孤立跑也失败）→ 既有 flake，与本 story 无关（本 story 未碰 auth Tab 门控）。
- ⚠️ 前端包名 `tailtopia`（非 petgo_app，2026-06-10 rebrand）；测试 import `package:tailtopia/`。

### Completion Notes List

- ✅ **AC1 后端队列读端点**：`GET /api/v1/vet/consultations/queue`（`VetConsultRequestController` +`@GetMapping("/queue")`）→ `VetQueueResponse{awaitingPay, available}`。`ConsultRequestService.vetQueue(vetId)`（只读）：`awaitingPay`=本人 `ACCEPTED_AWAIT_PAY`（`findFirstByVetIdAndState`，FR-53A）；`available`=`!isBusy(vetId)` 时的 QUEUEING 池（`findByStateOrderByCreatedAtAsc` FIFO），忙则空。身份富化注入 `PetProfileQueryService.findIdentitiesByOwners`+`AccountQueryService.findAuthorViews`（不直访 profile/auth repo）。**无病例字段**（`consult_requests` 不存）。`/vet/**` 已 hasRole VET，无 SecurityConfig 改动。无迁移。
- ✅ **AC2 待接单 Tab 改造走计费队列**：`VetInboxPage` 数据源 `waitingList()`→`vetQueue()`；`available` 渲染新 `_QueueCard`（种类 emoji + 宠物名 + meta「种类·年龄·@主人」+ 等待时长 + 接单 CTA），**不复用免费流 `_InboxCard`** 的 AI 等级色条/摘要/危险徽章（计费流无这些字段）。接单 CTA → `POST /vet/consultations/{token}/accept`（3-3 既有）→ 409 → `showAppToast` 3s + 刷新。
- ✅ **AC3 FR-53A 等待支付倒计时中间态**：`awaitingPay != null` → 顶部 `_AwaitingPayCard`（沙漏图标 + 「等待用户支付」+ 宠物名 + 服务端权威 `payDeadlineAt - now` MM:SS 倒计时，1s 显示 timer）；`pausedAt != null`（A-4 跳充值）→ 改显「用户正在充值…」不走倒计时（前端只读服务端权威值，不本地算暂停顺延）。忙时 `available` 空、不显空态占位。
- ✅ **AC4 FR-53B 取消/超时/未支付 3s Toast（前端轮询推断，D-4）**：`_prevAwaitingToken` 记上一轮；`_reloadQueue`→`_detectAwaitingTransition`：prev 非空&本轮 `awaitingPay==null` → 拉 `activeSessions` 判——有新会话（`goBusy` 互斥恒仅 1 单）→ 成功 Toast「用户已支付，会话已开始」；无 → 3s Toast「接单已取消或超时未支付」（合并取消/超时/未支付，用户已定粒度）+ 回池态。轮询 `Timer.periodic(4s)`，退后台停/回前台即拉/`dispose` cancel。
- ✅ **AC5 进行中会话复用 + 无死链（D-2）**：**不新建 `/vet/active` 端点**——3-4 付费会话（`consult_sessions` IN_PROGRESS, source=DIRECT）已被既有 `GET /vet/consult-sessions/in-progress`+`VetActivePage` 命中（后端 `findByVetIdAndStatusInOrderByCreatedAtDesc` 已核）。工作台 4 tab 均有落地页无死链（`p-vet-active` 原型死链 App 内早由 `VetActivePage` 承接）。未改 Active/History/Me/Shell。
- ✅ **AC6/AC7 model/repo/api_paths/i18n/静态**：手写 model fromJson（照 `vet_workbench_lists`）；repo +2 方法；api_paths +2；ARB 双份（印尼语主）+ gen-l10n。`flutter analyze` 零警告 + `flutter test` 404 绿（本 story 新/改 vet 测试 19 例绿 + 无回归）；后端 L0 4/4 + L1 3/3 + consult/vet 回归绿。
- **两流并存过渡**：V1.0 免费直连流 `waitingList()`/`VetConsultController`/`_InboxCard` 代码**保留不删**（[OPEN-2]，用户端免费入口下线属 3-8）；`/vet/request/:id` 免费流详情路由保留但计费队列不再导向（休眠）。
- **无迁移**（后端仅加只读 GET + DTO + repo 查询 + service 注入 2 端口）。
- **L2 待本地**：`/vet/consultations/queue` 仅本分支、未上线 prod，模拟器/真机双端全链路需本地起本分支后端 + 兽医号 + 用户端驱动（照根 CLAUDE.md「L2 留本地」）。

### File List

**后端**
- 新增 `petgo-backend/src/main/java/com/tailtopia/consult/dto/VetQueueResponse.java`（含嵌套 `VetAwaitingPayItem`/`VetQueueItem`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/repository/ConsultRequestRepository.java`（+`findByStateOrderByCreatedAtAsc`/`findFirstByVetIdAndState`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/service/ConsultRequestService.java`（+`vetQueue`/`petIdentityOf`/`handleOf`；注入 `PetProfileQueryService`/`AccountQueryService`）
- 修改 `petgo-backend/src/main/java/com/tailtopia/consult/web/VetConsultRequestController.java`（+`GET /queue`）
- 新增 `petgo-backend/src/test/java/com/tailtopia/consult/VetQueueIntegrationTest.java`（L1，3 例）
- 新增 `petgo-backend/src/test/java/com/tailtopia/consult/dto/VetQueueResponseTest.java`（L0，4 例）

**前端（petgo_app，包名 tailtopia）**
- 新增 `lib/features/vet/domain/vet_queue.dart`
- 重写 `lib/features/vet/presentation/vet_inbox_page.dart`（计费队列 + FR-53A 倒计时 + FR-53B Toast；新 `_QueueCard`/`_AwaitingPayCard`）
- 修改 `lib/features/vet/data/vet_repository.dart`（+`vetQueue`/`acceptConsultRequest`）
- 修改 `lib/core/network/api_paths.dart`（+`vetConsultationsQueue`/`vetConsultationsAccept`）
- 修改 `lib/l10n/app_id.arb`、`lib/l10n/app_en.arb`（+计费队列/等待支付/Toast keys）→ gen-l10n 产物
- 新增 `test/vet/vet_queue_model_test.dart`（5 例）；重写 `test/vet/vet_inbox_test.dart`（9 例）；修改 `test/vet/vet_dashboard_test.dart`（fake 改 vetQueue）
- 修改 `_bmad-output/implementation-artifacts/sprint-status-v1.1.yaml`（3-6 → review）

### Change Log

- 2026-07-13：dev-story 实现 Story 3.6。后端补只读 `GET /vet/consultations/queue`→`VetQueueResponse{awaitingPay(本人 ACCEPTED_AWAIT_PAY 待支付倒计时项)+available(QUEUEING 池·忙则空·FIFO)}`，身份富化复用 `PetProfileQueryService`/`AccountQueryService`，无病例字段，无迁移（L0 4/4 + L1 3/3 绿）。前端改造待接单 Tab 走计费队列：`_QueueCard` 接单 CTA（调 3-3 accept，409 Toast）+ FR-53A `_AwaitingPayCard` 服务端权威 MM:SS 倒计时（`pausedAt` 暂停显示 A-4）+ FR-53B 前端轮询推断 3s Toast（成交跳会话/未成交合并取消·超时·未支付，D-4）；进行中会话复用既有 `VetActivePage`+`/vet/consult-sessions/in-progress`（不新建 `/vet/active`，D-2）；4 tab 无死链。model/repo/api_paths/i18n（印尼语主）。`flutter analyze` 零警告 + test 404 绿（新/改 vet 19 例；唯一失败 `story_1_5_gating` 为 baseline 既有 flake）。免费直连流代码保留（过渡期，[OPEN-2] 下线属 3-8）。L2 双端留本地（端点仅本分支）。Status → review。
- 2026-07-13：create-story 定稿 Story 3.6（兽医端队列、进行中会话与 Toast）。后端补只读 `GET /api/v1/vet/consultations/queue` → `VetQueueResponse{awaitingPay(本人 ACCEPTED_AWAIT_PAY 待支付倒计时项), available(QUEUEING 池,忙则空)}`，富化复用 `PetProfileQueryService`；前端改造待接单 Tab 走计费队列（接单 CTA 调 3-3 既有 accept + FR-53A 等待支付倒计时中间态 + FR-53B 前端轮询推断 3s Toast[成交 vs 未成交]）；进行中会话复用既有 `VetActivePage`+`/vet/consult-sessions/in-progress`（付费会话已命中，不新建 `/vet/active`，D-2）；核 4 tab 无死链（p-vet-active 原型死链 App 内已接线）。无迁移。用户已定：待接单 Tab 走计费队列（改造，非新增/合并）+ Toast 前端轮询推断。L0 analyze/test + mvn test；L2 双端留本地。Status → ready-for-dev。
