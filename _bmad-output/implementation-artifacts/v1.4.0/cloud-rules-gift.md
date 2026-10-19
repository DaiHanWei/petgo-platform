- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.4.0-gift && git status -sb` 必须在该分支且与 `origin/feat/1.4.0-gift` 同步。
  3. `git push --dry-run origin HEAD:feat/1.4.0-gift` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里「`mvn -B clean package` 绿」「L1 … 全绿」一类 AC 在云端不验**，只验 L0；照写测试，不标完成。
- **口径优先级**：`planning-artifacts/v1.4.0/decision-log.md`（③ 节）> `送礼/1-4-0送礼prd.md` > `architecture-v1.4.0-gift-delta.md`（AD-1~AD-13、P-1~P-3）> UI 稿。跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.4.0-gift.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同目录还有地基 `0-1-*` 以及等级 / 多宠物 / 推送收藏三条线的同号 story，别拿错。
- **执行顺序**：Epic 1 → 2 → 3 → 4 串行，epic 内按编号串行。
- **🔴 地基契约不许动**：`ck_notifications_type` / `ck_payment_intents_purpose` 两条约束不改；`GrowthQueryService` / `GrowthLevelChangedEvent` / `GiftSentEvent` / `ContentCollectedEvent` / `ContentUncollectedEvent` 只引用、不改、不重定义；`PaymentPurpose.GIFT` 与 `PET_GIFT_RECEIVED` / `GIFT_UNDELIVERED_REFUND` 直接使用，`GiftSentEvent` 字段不改；后台抽屉页签头与 `data-utab` 名不改。确需改 → 停下在 Completion Notes「待确认」写清，不擅改。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**：1.1 `V20261018_1700` + `V20261018_1701`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **🔴 钱的纪律**：`PawCoinWalletService` **零改动**；送礼只对送礼人 `debit(SPEND)`，收礼方不入任何币；送礼服务内标识符与注释避开 `transfer` 字样；`ConsultShopBoundaryTest` 必须全绿。幂等键只经 `GiftIdemKeys`；订单状态只经交付 / 退回服务改变；汇总表只在交付同事务维护。不引 MQ / 缓存 / 新依赖（送出动效用 CustomPainter）。**本线不写成长值逻辑**（只发 `GiftSentEvent`）。
- **🔴 与 ①② 并行**：取宠物只经 `GiftPetDirectory`（单宠过渡实现）；**不得引用** ② 的 `ContentPetQuery` / `PetQueryService` 或 ① 的等级页路由（本分支不存在），也不得自造它们。
- **🔴 4.1 云端一律跳过**：对齐 story，sprint-status 已标 `backlog`；①②③ 均合入 `dev_1.4.0` 后在对齐分支本地补做。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言证明行为，golden 与模拟器视觉标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。
- **埋点**：7 个事件，名称以 story 为准（为过命名守卫，`gift_paid` → `gift_payment_succeeded`、`gift_qris_blocked_low_amount` → `gift_qris_low_amount_shown`，其余照 PRD §4），按 App 埋点守卫测试规则登记，不删守卫、不放宽正则。
- **文案**：App 不渲染后端显示串（礼物名按语言取订单 / 目录的 EN / ID 字段）；新增 arb 取 story 列出的键，EN / ID 两份都加；后台四份 messages 都加。
- 会被按设计打破的既有测试：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按架构增量与决策日志能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
