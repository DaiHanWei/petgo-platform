- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-health-reminder && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-health-reminder` 同步；`git log --oneline -1 origin/dev_1.5.0` 的地基提交（Story 0.1，`9680309cf` 或其后）必须已在本分支历史里。
  3. `git push --dry-run origin HEAD:feat/1.5.0-health-reminder` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告。
- **🔴 本文件优先于通用步骤**，两处覆盖：
  1. **后端 L0**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文**的单元测试：`./mvnw -B test -Dtest='<本 story「验证层级」L0 列的测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、`ApiIntegrationTest` 子类、`@SpringBootTest`。被排除的测试照样写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. story 里标 `[L1]` / `[L2]` 的 AC 云端不验，照写测试，不标完成。4.1 在云端只做 AC1。
- **口径优先级**：`implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」> `planning-artifacts/v1.5.0/architecture-v1.5.0-health-reminder-delta.md` > `planning-artifacts/v1.5.0/宠物健康记录提醒/1-5-0健康记录提醒prd.md`（英文，附录 A 文案逐字照用）> UI 稿。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.5.0-health-reminder.yaml` 的完整 key 取 story**——同目录有其他五个会话的同号 story（`1-1-*`、`2-1-*` …），别拿错。执行顺序 Epic 1 → 4 串行，epic 内按编号。
- **story 文件提前写好，引用的是计划中的类名 / 接口**。每条开工前先对照前面 story 实际提交的代码；有出入 → 先改本 story 文件（Completion Notes 记一句），再写代码；不得为迁就 story 文字去改前面已提交的对外契约。
- **迁移号已预分配，不得另取**：`V20261115_0100`、`0110`（1.1）、`0120`（2.1）、`0130`（2.2）；备用 `0140`、`0150`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。打包一律 `clean`。
- **🔴 地基所有、本线禁改**（CROSS-STORY-DECISIONS 地基段）：
  - `ck_notifications_type`（`V20261114_1000`）——**本线任何迁移都不得 DROP / ADD 它**；`NotificationType` 的 23 个 `HEALTH_*` 只用，不改名、不增删、不改顺序；`PushCategory.of` 已归 `PET_REMINDER`，不动。
  - `notify.service.AppVersionGate`：版本门槛**只走这里**（批量 `usersOnV150OrLater`，禁 N+1），不改签名、不另写一套。
  - `notify.lifecycle.RetentionPushCalendar`：只调用 `recallNodeUsers` / `anniversaryPushPets`，**不改方法体**（纪念日占位恒空是预期，③ 会替换）；测试里 mock。
  - `ScheduledPushPlanner` / `ScheduledPushJob` / `LifecyclePushJob`：只调用 `ScheduledPushPlanner.plan(...)` 判生日 / 第一个生日占用，不改这些类（③ 推送与召回会改生日时点）。
- **🔴 写库监听纪律**：有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)` + 另一个 bean 上的 `@Transactional(REQUIRES_NEW)`（可再加 `@Async`）；禁止在监听器里 `this.xxx()` 调自己的 `@Transactional` 方法。
- **🔴 推送出口**：只经新方法 `NotificationService#sendHealthReminder`（自带 `!pushAllowed(` 闸门），并把它加进 `PushExitStructureTest.PUSHER_CALLER_METHODS`；不另开推送出口、不改 `TencentImClient` / `NotificationPusher`、不调高限速。
- **🔴 物种**：只认 `CAT` / `DOG`，其余一律按 OTHER 规则（④ 宠物物种扩展会加 `PetType` 新值并在本线**之后**合并）；后端禁止对 `PetType` 写无 default 的穷尽 switch，App 遇未知物种走 OTHER 分支。
- **🔴 规则只有一份**：Next date 预填、显示状态、覆盖组、安全提示全部在后端 `com.tailtopia.profile.health`，App 只调 `/health-records/preview` 与读列表字段，**禁止在 Dart 里复制任何日期规则**。
- **🔴 日期一律 WIB**：`ScheduleWindow.WIB`；日跑 cron `0 0 3 * * *` UTC；不要照抄 `ScheduledPushJob` 的 `ZoneOffset.UTC` 取日期。
- **旧版兼容**：所有请求按 `X-App-Version`（`ClientAppVersion.isV150OrLater`）分流；旧版：记录列表 / 问诊历史不返回 `VET_VISIT`、通知列表不返回 `HEALTH_*`、写入不读新字段且不清空已存的新字段。
- **🔴 合并时注意（与其他会话的交界）**：
  - App `kPushDirectTypes`（`core/push/push_service.dart`）由 ① ③ ⑥ 各自往同一集合加值——**只加本线 23 个 `HEALTH_*`**，不删不改别人的值；合并冲突取并集。
  - `PushExitStructureTest.PUSHER_CALLER_METHODS` 同理，③ 可能也加方法，取并集。
  - 推送设置页「Hewan yang kamu ikuti」那一行的改名归 ③ 推送与召回；本线只改 `pushSettingsPetReminderDesc` 两份 arb。
  - ④ 宠物物种扩展在本线之后合并，会改档案编辑页与各处物种显示：本线在 `pet_profile_edit_page.dart` 只插入独立的 `PetHealthStatusSection` widget，不重排其他段落。
  - 功能分支与 `dev_1.5.0` 冲突时**不反向合并 dev 进本分支**，留给本地从 dev 切对齐分支处理。
- **埋点**：后端经 `AnalyticsClient` + `AnalyticsDistinctId`，事件名常量与属性键登记 `AnalyticsEventGuard` 白名单（漏登记 = 静默丢）；App 用 `Analytics.capture` 字面量、`health_` 前缀；属性禁宠物名 / 备注 / 疫苗原文 / 明文 user_id；枚举值小写蛇形。
- **文案**：App 新文案 ID / EN 两份 arb 都加（PRD FR-169 表原文），跑 `flutter gen-l10n`、不手改生成产物；后端推送文案写 `messages_id` / `messages_en` / `messages.properties`，含参数的串单引号写 `''`，EN 计数用 `choice` 处理单复数。
- **日志**：健康数据 = PII，日志只记 id + 动作 + 计数，禁记宠物名、文案、记录字段。
- **视觉一致性不写 golden 截图**；用结构断言，模拟器视觉与还原度（≥95%）标 L2 留本地。
- **不要对整个文件跑 `dart format`**；手工对齐缩进，只跑 `flutter analyze`。
- 会被按设计打破的既有测试（如健康页 AppBar 按钮、`triage_category_jump_tapped`、`Siklus Berahi` 文案）：**更新断言表达新规则**，不删测试、不 `skip`。
- 不引 MQ / 缓存 / 新依赖；不加后台页面。
- 碰到 story 与代码现状矛盾且 story 未给处置的：按架构增量 §0 漂移表与 PRD 能推出的最保守做法实现，在 Completion Notes「待确认」小节写清，**不停下问**。
