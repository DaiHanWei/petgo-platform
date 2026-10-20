- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.4.0-multi-pet && git status -sb` 必须在该分支且与 `origin/feat/1.4.0-multi-pet` 同步。
  3. `git push --dry-run origin HEAD:feat/1.4.0-multi-pet` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里「`mvn -B clean package` 绿」「L1 … 全绿」一类 AC 在云端不验**，只验 L0；照写测试，不标完成。
- **口径优先级**：`planning-artifacts/v1.4.0/decision-log.md`（② 节）> `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md`（AD-1~AD-17、P-1~P-4、V-1/V-2）> UI 稿。跨分支契约以 `CROSS-STORY-DECISIONS.md`「2026-10-18 V1.4.0 四条线共用地基」节为准。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.4.0-multi-pet.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同目录还有地基 `0-1-*` 以及等级 / 送礼 / 推送收藏三条线的同号 story，别拿错。
- **执行顺序**：Epic 1 → 2 → 3 → 4 串行，epic 内按编号串行。
- **🔴 地基契约不许动**：`ck_notifications_type` / `ck_payment_intents_purpose` 两条约束不改；`GrowthQueryService` / `GrowthLevelChangedEvent` / `GiftSentEvent` / `ContentCollectedEvent` / `ContentUncollectedEvent` 只引用、不改、不重定义；`PET_SLOT_RENT` 与 `PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` 直接使用；后台抽屉页签头与 `data-utab` 名不改。确需改 → 停下在 Completion Notes「待确认」写清，不擅改。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**：1.4 `V20261018_1603`（删除唯一约束，必须在 1.1 / 1.2 的源码扫描守卫通过之后）· 2.1 `V20261018_1604` · 2.2 `V20261018_1601` + `V20261018_1602` · 3.1 `V20261018_1600`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **单一入口**：取宠物只经 `PetResolver`、可写校验只经 `PetWriteGuard`、宠物状态只经 `PetSlotStateService.reconcile`、名额只经 `PetSlotService.quotaOf`（架构 §5）。不引 MQ / 缓存 / 新依赖。**本线不写任何成长值逻辑**（月租返成长值由 ① 自动处理）。
- **🔴 单宠物回归是本线最大风险**：每条后端 story 完成前，单宠物账号相关既有测试必须全绿；不删、不 skip、不为迁就改断言（除非 story 明确写了「按设计打破」）。
- **🔴 4.4 云端一律跳过**：它要改 ① 的 `/popups` 与升级庆祝页，而本分支不会反向合入 dev（拿不到 ① 的代码）。sprint-status 里 4.4 已标 `backlog`，云端不执行、不自造 ① 的代码；由本地在 ①② 均合入 `dev_1.4.0` 后从 dev 切对齐分支补做。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言证明行为，golden 与模拟器视觉标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。
- **埋点**：7 个事件名照 PRD §4（`pet_added` / `pet_switched` / `pet_slot_full_shown` / `pet_slot_rent_started` / `pet_slot_rent_paid` / `pet_readonly_banner_shown` / `pet_readonly_renew_tapped`，以及 `diary_published.pet_count`），按 App 埋点守卫测试规则登记，不删守卫、不放宽正则。
- **文案**：App 不渲染后端显示串（名额到期两类通知例外，读后端 title / body）；新增 arb 取 `多宠物/1-4-0多宠物-新增文案对照表.md` 与 story 列出的键，EN / ID 两份都加；后台四份 messages 都加。
- 会被按设计打破的既有测试：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按架构增量与决策日志能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
