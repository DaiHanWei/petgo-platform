- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 → 停。
  2. `git fetch origin feat/1.4.2-shop-admin && git status -sb` 必须在该分支且与远端同步。
  3. `git push --dry-run origin HEAD:feat/1.4.2-shop-admin` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 都能跑；缺失 → 停。
  5. `git merge-base --is-ancestor origin/feat/1.4.2-shop HEAD` 必须成功（电商线已在本分支历史内）；失败 → 停。
  - 全过后把自检输出贴在第一条回复再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告。
- **🔴 本文件优先于通用步骤**：
  1. 后端 L0：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑不需要数据库 / Redis / Spring 上下文的单元测试：`./mvnw -B test -Dtest='<story 列的 L0 测试类>' -Dsurefire.failIfNoSpecifiedTests=false`。不跑 `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`；这些照写、能编译，Completion Notes 记「L1 待本地验收」。
  2. 标 〔L1〕〔L2〕 的 AC 云端不验，照写测试，不标完成。
- **🔴 Epic 9 不在云端跑**，云端做到 8-1 为止。
- **口径优先级**：`决策日志-shop-admin.md` > `决策日志-shop.md` > 后台 PRD（文末变更记录最晚一条为准）> 架构增量 > UI 稿。
- **只按 `sprint-status-v1.4.2-shop-admin.yaml` 的完整 key 取 story**，不按编号通配。
- **story 已按合并后的代码核过一轮**（2026-11-06，基线 `fd94b3ffa`，每条 story 的「代码核对」节和标「（核对补）」的 Tasks）。开工前仍要对照实际代码，有出入先改 story 并在 Completion Notes 记一句。不得为迁就 story 改已提交的对外契约。
- **迁移号已预分配，不得另取**（见 sprint-status 头注释）；提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **🔴 金额单点**：应付等只在 `ShopPricingCalculator`，退款只在 `ShopRefundCalculator`；试算与落库同一计算。券状态只经 `CouponLedgerService`。
- **🔴 三个易错口径**：`shop_orders.shipping_discount` 存负数或 0，用户实付运费 = `shipping_fee + shipping_discount`；`shipments.insurance_amount` 是申报保价额，不得算钱，用 `insurance_cost`；用户原因退回的库存只在按行确认入库时加一次。
- **🔴 资金类拍板不可擅改**（S-1 ~ S-9、O-1、O-14 ~ O-17、O-25 ~ O-27）；涉及钱的计算口径拿不准就停下报告。
- **🔴 外部调用**：Biteship / GemPay 调用不在持有行锁的事务内；云端 `BITESHIP_MODE=stub`；日志不记 key、地址、手机号、收款账户、兑换码明文。
- **🔴 不开外层事务**：`BiteshipShipmentService`、`ShopLogisticsExceptionService` 自带 `REQUIRES_NEW` 短事务，调用方（含 `@Transactional` 的 `AdminShopOrderExceptionService` 方法）不得包在事务里调用。
- **🔴 回补后重读**：`movements.receiveReturn` 会清一级缓存，之后必须重读订单与订单行，照 `ShopOrderRefundService.reloadOrder`（PR#49 修过的乐观锁异常）。
- **🔴 写库监听**：`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`。
- **文案**：只取 `shop-admin-文案三语表.md`，四份 properties 同步；标「不可复用」的新建 key。无稿的两处（确认退回入库、注销用户线下处理）自拟三语并在 Completion Notes 标「待设计确认」。
- **样式**：取现有 `admin.css` 类，新样式放 `admin.css`，不量截图像素。还原度打分标 L2 留本地。
- **App 只有一处改动**（6.2 确认收货 409 提示）：EN / ID 两份 arb，跑 `flutter gen-l10n` 与 `flutter analyze`，不整文件 `dart format`。
- 被按设计打破的既有测试：更新断言，不删测试、不 `skip`。
- 不引 MQ / 缓存层 / 新依赖。story 与代码矛盾且未给处置时按决策日志推最保守做法，写入 Completion Notes「待确认」，不停下问；钱的口径例外。
