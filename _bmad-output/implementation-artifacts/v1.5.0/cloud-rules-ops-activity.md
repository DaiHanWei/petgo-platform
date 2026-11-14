# 云端执行须知 · V1.5.0 会话 ⑥ ops-activity（运营活动规则引擎）

> 适用分支 `feat/1.5.0-ops-activity`。本文件优先于通用 dev-story 步骤；与 story 冲突时以本文件为准，并在 Completion Notes 记一句。

- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-ops-activity && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-ops-activity` 同步。
  3. `git push --dry-run origin HEAD:feat/1.5.0-ops-activity` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  5. **依赖契约必须在树里**（gift-pack 4-1）：`petgo-backend/src/main/java/com/tailtopia/entitlement/service/EntitlementCreditService.java`、`GrantCommand.java`（含静态工厂 `activity(...)`）、`entitlement/domain/CreditType.java`（含 `KTP_HD_ANY`）、迁移 `V20261115_0220__create_entitlement_credits.sql` 全部存在；任一缺失 → 停，报告「gift-pack 4-1 尚未合进 dev_1.5.0，本分支切早了」。
  - 五项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 L0 命令（覆盖通用步骤）**：后端 `cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑 story「验证层级」里列的 L0 测试类：`./mvnw -B test -Dtest='<类名，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`；这些照样要写好、能编译，Completion Notes 记「L1 待本地验收」。App：`flutter analyze` + story 列出的 `flutter test` 目录。
- **🔴 story 里 `[L1]` / `[L2]` 的 AC 在云端不验**，只验 `[L0]`；照写测试，不标完成。Story 状态最多推到 `review`。
- **🔴 执行顺序**：严格按 `sprint-status-v1.5.0-ops-activity.yaml` 头注释：1-1 → 1-6 → 2-1 → 2-3 → 3-1 → 3-2，**到 3-2 为止**；**跳过 4-1**（本地联调）。只按 yaml 里的完整 key 取 story，不按 `<epic>-<n>` 前缀通配（同目录有其他五条线的同号 story，如 `1-1-*`、`4-1-*`）。
- **🔴 不部署、不合并**：不跑任何部署脚本，不碰 stag / prod，不开 PR、不合 dev。
- **口径优先级**：`CROSS-STORY-DECISIONS.md` > `planning-artifacts/v1.5.0/decision-log.md` > PRD > `architecture-v1.5.0-ops-activity-delta.md`（OA-1 ~ OA-22）> UI 稿。story 里写死的值照用，不自行发明。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story 引用的名字；有出入 → 先改本 story 文件（Completion Notes 记一句），再写代码。

## 依赖契约（gift-pack 4-1，冻结，本线只调用）

- 只用 `GrantCommand.activity(userId, type, quantity, activityId, idemKey, nameId, nameEn)` + `EntitlementCreditService.grant(...)`；**不调** `consume` / `summary`，**不直写** `entitlement_credits` / `entitlement_credit_uses`（4-1 守卫测试会拦）。
- **不改** `com.tailtopia.entitlement.*` 任何文件。契约不够用 → 不改契约，按架构 §8 的绕法做；绕不过去 → 停下报告。
- 「任意款」→ `CreditType.KTP_HD_ANY`；次数数量 1–100（契约上限）。
- 实际签名与规划不一致（字段顺序、方法名）→ 以树里代码为准，改本线 story 文件并在 Completion Notes 记录，不改契约代码。

## 地基所有、本线禁改（违反即返工）

- `ck_notifications_type`（`V20261114_1000`）——**本线一律不改**；`NotificationType.OPS_ACTIVITY_REWARDED` / `OPS_ACTIVITY_RANK_WON` 已在，只用（App 稿写的 `ACTIVITY_REWARD` / `ACTIVITY_RANK_WON` 一律按地基名落地）。
- `NotificationType` 的 27 个新值、`PushCategory` 归类：不改名、不增删、不改顺序。
- App `kPushDirectTypes`：本线**只加** `OPS_ACTIVITY_RANK_WON`，不删别人的值（① / ③ 也会加，合并取并集）。
- 🔴 **不得**新增 `notify.OPS_ACTIVITY_REWARDED.*` / `notify.OPS_ACTIVITY_RANK_WON.*` 消息键（`NotificationService.send` 会用静态键覆盖动态文案）；本线文案键前缀 `notify.opsActivity.*`。
- 功能分支与 `dev_1.5.0` 冲突时**不反向合并 dev 进功能分支**，停下报告，由本地从 dev 切对齐分支处理。

## 本线负责重建的共享 CHECK（只有本线会改）

- `ck_user_coupons_source`（1-2，`V20261115_0610`）：照抄**当前树里最后一条定义它的迁移**全集（规划时只有 `V20261105_1615` 的 5 值）+ `ACTIVITY` = 6 值。开工当天 grep 全部迁移 + `git fetch` 检查远端各 `feat/1.5.0-*` 分支，若有更晚的重建以它为准。DROP + ADD，永远不要只加一行。
- **迁移版本号已预分配，不得另取**：`V20261115_0600 / 0605 / 0610`；备用 `0615 ~ 0659`（用了回写 sprint-status 与架构 §7）。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。

## 跨线约束

- **⑤ likes**：会改 `GrowthActivityListener`（`onLiked` 开头加自动点赞跳过）并给 `ContentLikedEvent` 加 `automated`。本线**不改** `GrowthActivityListener`，活动判定放独立监听 `OpsActivityContentListener`。`ContentLikedEvent` 有 `automated()` 才写显式跳过，没有就靠资格判定（虚拟号不参与）并在 Completion Notes 记「⑤ 合入后补一行」。不要为了编译自己给事件加字段。
- **② gift-pack 6-3**：会改 `pawcoin_page.dart` 结构与 `notification_center_page.dart` 读后端文案那一行。本线 3-2 只改 `_txnLabel` 函数与 `PawCoinTxnItem` 模型；3-1 在同一行追加两个类型。免费次数记录的「Event · {label}」（B2）归 6-3，本线不做。
- **③ onboarding-recall**：`NotificationService.sendRendered` 是 ③ 的，本线不用（用现有 `send` / `sendWithoutPush`，`PushExitStructureTest` 白名单不需改）。

## 资金与数据护栏

- 发奖必须「先写待发 → 一个事务发完整份 → 成功改已发 / 出错回滚后改发放失败」（1-2）；同一份奖励各项用同一幂等键（`GrowthIdemKeys.activity*`，唯一生成处）；重发用同一键。
- 总份数只经 `UPDATE … WHERE granted_count < total_limit` 条件扣减；每人上限在 advisory 锁内判。不得读后写。
- 活动 EXP 只经新方法 `GrowthLedgerService.accrueActivity`（不占日常额度）；不走 `adjust()`。
- 扣回只扣 EXP、只扣参与即奖（`trigger_kind = 'POST' / 'LIKE'`）；`ACTRANK:` 永不扣；PawCoin / 券 / 次数无任何扣减代码路径。
- PawCoin 只经 `PawCoinWalletService.credit(…, BONUS, "ACTIVITY", activityId, key)`；券只经 `ActivityCouponIssuer.tryIssue`（库存 / 限领 / 失效只跳过券，不抛错）。
- 涉及钱 / 次数的口径拿不准就**停下报告**；其他与代码现状矛盾且 story 未给处置的，按架构能推出的最保守做法实现，Completion Notes「待确认」小节写清，不停下问。
- 有写库动作的事件监听一律 `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`，写库在被调方的 `REQUIRES_NEW` 事务里；不引 MQ / 缓存 / 新中间件；定时任务用 `@Scheduled` + DB 条件更新 / `FOR UPDATE SKIP LOCKED`。
- 日志只记 id 与原因码；不记活动名、券名、昵称、帖子 / 评论正文。

## 后台与 App 规矩

- **文案逐字照抄**：后台取 PRD 附录 A.2（中 / EN / ID，默认 `messages.properties` = EN）；通知文案取 PRD 附录 A.1（ID / EN，进后端 `messages_*`）。**不自己改写或翻译**。A.2 没有的条目按 story 给的三语建议写，Completion Notes 标「待产品复核」。
- 后台只用 `admin.css` 现有类；确认弹层全走 `confirm-modal.html`；422 写法照 `drawer-growth-rule`。
- App 不新增 `.arb` 词条；改 ARB（如确需）后跑 `flutter gen-l10n`；**不要对整个文件跑 `dart format`**；不写 golden（结构断言）。
- `kApiBaseUrl` 默认线上，非指令不改。

## 交付

- 每条 story：L0 绿 → 更新 story 文件（任务勾选、Completion Notes、File List、Status → review）→ 更新 sprint-status → commit（提交信息按仓库约定，中文）→ push。
- 云端做到 3-2 为止；4-1 留本地。不部署、不开 PR。
