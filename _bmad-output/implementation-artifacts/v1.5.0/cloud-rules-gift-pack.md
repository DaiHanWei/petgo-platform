# 云端执行须知 · V1.5.0 会话 ② gift-pack（送礼优化 → 超值礼包）

> 适用分支 `feat/1.5.0-gift-pack`。本文件优先于通用 dev-story 步骤；与 story 冲突时以本文件为准，并在 Completion Notes 记一句。

- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-gift-pack && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-gift-pack` 同步。
  3. `git push --dry-run origin HEAD:feat/1.5.0-gift-pack` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 L0 命令（覆盖通用步骤）**：后端 `cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑 story「验证层级」里列的 L0 测试类：`./mvnw -B test -Dtest='<类名，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`；这些照样要写好、能编译，Completion Notes 记「L1 待本地验收」。App：`flutter analyze` + story 列出的 `flutter test` 目录。
- **🔴 story 里 `[L1]` / `[L2]` 的 AC 在云端不验**，只验 `[L0]`；照写测试，不标完成。Story 状态最多推到 `review`。
- **🔴 执行顺序**：严格按 `sprint-status-v1.5.0-gift-pack.yaml` 头注释——**先 4-1**，再 1-1 ~ 2-6，**跳过 3-1**，再 4-2 ~ 6-5，**跳过 7-1**。只按 yaml 里的完整 key 取 story，不按 `<epic>-<n>` 前缀通配（同目录有其他五条线的同号 story，如 `1-1-*`）。
- **🔴 4-1 是会话 ⑥ 的前置契约**：架构 §5 列出的包名 `com.tailtopia.entitlement.*`、类名、方法签名、record 字段顺序、幂等语义（`(idemKey, type)` 唯一、同键重放返回原批）**冻结**；后续 story 只能加方法，不能改。4-1 push 后在第一条汇报里写明「4-1 已完成，可单独合 dev」。
- **口径优先级**：`CROSS-STORY-DECISIONS.md` > `planning-artifacts/v1.5.0/decision-log.md` > 两份 PRD > `architecture-v1.5.0-gift-pack-delta.md`（D-1 ~ D-31）> UI 稿。story 里写死的值照用，不自行发明。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story 引用的名字；有出入 → 先改本 story 文件（Completion Notes 记一句），再写代码。不得为迁就 story 文字去改前面 story 已提交的对外契约（尤其 4-1 的 §5 契约）。

## 地基所有、本线禁改（违反即返工）

- `ck_notifications_type`（`V20261114_1000`）——**本线一律不改**；`NotificationType.PACK_CREDIT_EXPIRING` 已在，只用。
- `NotificationType` 的 27 个新值：不改名、不增删、不改顺序。
- `OpsAppPage.STARTER_PACK`（后端枚举 + `admin.opsPopups.appPage.*` 四语标签）——**不改枚举顺序、不重复加标签**。后台稿写的 `VALUE_PACK` 一律按 `STARTER_PACK` 落地。本线只做 App `ops_target.dart` 的值与跳转、后台 targetHint 文案。
- `users.last_app_version`、`AppVersionGate`、`RetentionPushCalendar`——只读 / 不碰。
- App `kPushDirectTypes`：本线不加任何值（礼包到期提醒不推送）。
- 功能分支与 `dev_1.5.0` 冲突时**不反向合并 dev 进功能分支**，停下报告，由本地从 dev 切对齐分支处理。

## 本线负责重建的共享 CHECK（只有本线会改）

- `ck_payment_intents_purpose`（4-2，`V20261115_0225`）：照抄**当前树里最后一条重建它的迁移**全集（规划时为 `V20261024_2133` 的 12 值）+ `STARTER_PACK`。开工当天 grep 全部迁移 + `git fetch` 检查远端分支，若有更晚的重建以它为准。DROP + ADD，永远不要只加一行。
- `ck_pet_slot_purchases_kind`、`ck_pet_slot_rentals_source`（4-2，`_0230`）、`ck_gift_orders_channel`、`ck_gift_orders_total`、`ck_id_card_hd_purchases_channel`（4-3，`_0235`）：同样先 grep 确认最后定义处再重列全集。
- **迁移版本号已预分配，不得另取**：`V20261115_0200 / 0205 / 0210 / 0220 / 0225 / 0230 / 0235`；备用 `0240 ~ 0259`（用了回写 sprint-status 与架构 §7）。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。

## 跨线约束

- **④ 物种扩展在本线之后合**，会改送礼面板的物种显示 / 排序与后台跳转提示，并给 `GiftCatalogResponse.Recipient`、`PetGiftShowcaseResponse.PetView` 末尾加物种字段（见 ④ 的 `1-3-物种显示字段下发与AI提示词.md`）。本线：新逐笔列表接口的宠物信息**复用** `PetGiftShowcaseResponse.PetView`，不新建同义类型；`Recipient` / `PetView` 不改已有字段顺序；**不改** `GiftCatalogService.orderFor`、`GiftCatalogItem.suits` / `isUniversal` 的签名与语义；App 不改 Diary 页头的物种 / 品种拼法；后台 targetHint 按 PRD B §8 原文写 6 个页面，不替 ④ 预写「宠物类型」。
- **⑥ 运营活动在本线之后起**：只调 4-1 的 `EntitlementCreditService.grant`。本线不实现活动、不实现 PawCoin 流水「Event · 活动名」（⑥ 的事）；合并页免费次数记录里的活动来源显示「Event · {label}」由 6-3 实现。
- 共用支付组件：`PayMethodCards`（2-2 建）是送礼包装页与 `PayChannelPicker`（6-1）共用的唯一两卡实现，不各写一套。`PayChannelPicker` 对外构造参数只加不删。

## 资金与数据护栏

- `PayChannel` / `PaymentPurpose` / `OrderType` / `PetSlotPurchaseKind` / `PetSlotRentalSource` 一律**末尾追加**。
- `PayChannel.PACK_CREDIT` 永不进 `payment_intents`；所有接受客户端 `PayChannel` 的非送礼入口对它显式 422（4-3 AC2），不得落到「非 QRIS 即扣币」分支。
- 权益次数表只经 `EntitlementCreditService` 写（4-1 AC6 守卫）。扣次数与解锁 / 送礼同一事务，按目标幂等；PawCoin 礼包整笔同事务；QRIS 礼包到账走「PAID → AFTER_COMMIT 发放 → 重试任务兜底」状态机（D-24），不引 MQ / 新中间件。
- 性格测试用次数解锁**不写** `keepsake_purchases`（会进我的订单）；拆礼物**不入账**。
- 涉及钱 / 次数的计算口径拿不准就**停下报告**；其他与代码现状矛盾且 story 未给处置的，按架构能推出的最保守做法实现，Completion Notes「待确认」小节写清，不停下问。
- 有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`；支付到账处理器照 `GiftPaidHandler`（`@EventListener` + `MANDATORY`、异常不外抛）。
- 对外标识用 `public_token`；分类 `categoryId` 是参考数据例外（D-4）。日志不记祝福语正文、昵称、宠物名。

## App 规矩

- **文案逐字照抄**：送礼取 App 稿页头「新文案表」（ID / EN 两列），礼包取 PRD B §7（ID / EN），后台取送礼后台稿文案表与 PRD B §8（中 / EN / ID）。**不自己改写或翻译**；标「复用」的引原 key。UI 稿有而表里没有的条目，ID 逐字取 UI 稿，EN 按 story 给的建议写，并在 Completion Notes 标「EN 待产品复核」。
- 改 ARB 后跑 `flutter gen-l10n`，不手改生成产物；**不要对整个文件跑 `dart format`**（存量未统一格式化），手工对齐缩进。
- 不写 golden 截图（云端 Linux 与本机字体渲染不同）：用结构断言（key + 文本）。Rive 不在测试里渲染，用可注入替身。
- 新增用户维度 provider（礼包、权益汇总）登记进 `app.dart` 的 `resetUserScopedCaches`。
- 埋点事件名写字面量、登记白名单测试，属性只按 PRD B §4 / 架构 D-29；送礼优化不新增埋点。
- 素材：`send_gift.riv` / `receive_gift.riv` 从 `planning-artifacts/v1.5.0/ui-gift-optimization/` 复制到 `petgo_app/assets/rive/`；礼盒临时图转存 `assets/gift/`（2-3）。设计师待补的事件 / 图片属性缺失属预期，走兜底时间，不阻塞。
- `kApiBaseUrl` 默认线上，非指令不改。

## 交付

- 每条 story：L0 绿 → 更新 story 文件（任务勾选、Completion Notes、File List、Status → review）→ 更新 sprint-status → commit（提交信息按仓库约定，中文）→ push。
- 云端做到 6-5 为止；3-1、7-1 留本地。
