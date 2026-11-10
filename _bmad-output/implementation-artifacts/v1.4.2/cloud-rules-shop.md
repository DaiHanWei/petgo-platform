- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.4.2-shop && git status -sb` 必须在该分支且与 `origin/feat/1.4.2-shop` 同步。
  3. `git push --dry-run origin HEAD:feat/1.4.2-shop` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里「L1 … 全绿」一类 AC 在云端不验**，只验 L0；照写测试，不标完成。
- **🔴 Epic 10（联调）不在云端跑**：10-1、10-2 是 L1 / L2 联调，留本地；云端做到 9-4 为止。
- **口径优先级**：`planning-artifacts/v1.4.2/决策日志-shop.md`（S-0 ~ S-9）> `电商/1-4-2电商prd.md` > `architecture-v1.4.2-shop-delta.md`（D-1 ~ D-24、P-1 ~ P-12）> UI 稿。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.4.2-shop.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同版本目录有 social-topic 线的同号 story（如 `1-1-追更关系与接口.md`），别拿错。
- **执行顺序**：Epic 1 → 9 串行，epic 内按编号串行。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**（见 sprint-status 头注释）：1.1 `V20261105_1600` / `_1605` / `_1610`；2.1 `_1615`；2.3 `_1620`；3.2 `_1625`；3.3 `_1630`；4.1 `_1635`；5.1 `_1640`；6.1 `_1645` / `_1650`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **🔴 订单头金额沿用已有列**：`goods_subtotal`（商品金额）、`shipping_fee`、`shipping_discount`、`total_amount`（应付）、`coin_amount`、`cash_amount`、`refunded_total`、`refunded_coin`；行级部分取消复用 `refunded_qty`。**禁止**新增同义列。
- **🔴 金额单点**：应付 / 减免 / 保价 / 币与现金拆分只在 `ShopPricingCalculator`；退款金额只在 `ShopRefundCalculator`。App 只展示服务端给的数，不自算。
- **🔴 券状态只经 `CouponLedgerService`**；支付意图状态只经 `PaymentIntentService` / `PaymentReconciliationService` 的 `applyResult`。
- **🔴 资金类拍板不可擅改**：PawCoin 提交时扣（S-3）；关单判据「订单过期且该单所有意图码已失效」+ 关单前查单、查询失败不关单（S-1 / B1）；关单后到账意图照常转 PAID + `paid_after_close`，**不新增意图状态**（I1）；旧版 App 仍可下单（S-4）。
- **🔴 外部调用**：Biteship / GemPay 调用不在持有行锁的事务内；`BITESHIP_MODE=stub` 是云端与 L0 / L1 的默认，云端不配真 key；日志不记 key、地址、手机号、收款账户。
- **🔴 `ck_notifications_type` 重建（2.3）**：DROP + ADD 重列全集，值必须取**当前树里最后一条重建它的迁移**（现为 `V20261104_2010`）的原文全集再加 `COUPON_EXPIRING`、`SHOP_ORDER_REORDERED`，不写死数量、不凭记忆抄；`PushCategoryTest`、`MentionNotifyTest` 同 story 内一起改。
- **🔴 写库监听纪律**：有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`；扇出再加 `@Async`。
- **🔴 对外标识**：券、用户券、兑换码、报价、退款单、运单对外一律 `public_token`，不暴露自增 id（区域 id 为参考数据例外）。
- 不引 MQ / 缓存层 / 新依赖；Biteship 用现有 `RestClient` 直连；App 轮播用 `PageView`，不引轮播库。
- **共用支付面板只加可选参数**（默认值保持现状）；送礼 / AI 解锁 / 身份证高清 / 一次性解锁 / 月租调用点不改，测试证明不受影响。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言证明行为，golden、模拟器视觉与还原度打分标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。改 ARB 后跑 `flutter gen-l10n`，不手改生成产物。
- **埋点**：事件名写字面量（不用常量），沿用 `toko_` 前缀（已在 `test/analytics/v112_events_test.dart` 前缀表），不删守卫、不放宽正则；属性严格按 PRD §4 与决策日志。
- **文案**：App 新 / 改文案 EN / ID 两份 arb 都加，严格取 PRD §6 表；标「复用」的引原 key；PRD §6 与 UI 稿冲突以 PRD §6 为准。
- 会被按设计打破的既有测试：**更新断言表达新规则**，不删测试、不 `skip`（例：`CheckoutPreviewIntegrationTest`「不得出现券字段」断言随 3.1 删除）。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按决策日志与架构增量能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**；**涉及钱的计算口径例外——拿不准就停下报告**。
