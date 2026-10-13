- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.3.4-follow-notify && git status -sb` 必须在该分支且与 `origin/feat/1.3.4-follow-notify` 同步。
  3. `git push --dry-run origin HEAD:feat/1.3.4-follow-notify` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类（含 `*EndpointTest` 里继承它的）、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译（`clean package -DskipTests` 会编译测试源码），Completion Notes 记「L1 待本地验收」。
  2. **story 文件里「`mvn -B clean package` 绿」「L1 … 全绿」一类 AC 在云端不验**，只验 L0；照写测试，不标完成。
- **口径优先级**：`决策日志-follow.md`（D-1~D-11 产品拍板、T-1~T-43 技术裁定）> `PRD-v1.3.4-follow.md` > `architecture-v1.3.4-follow-delta.md`（AD-1~AD-16）> `ui-v1.3.4-follow.html`。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.3.4-follow.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同目录还有 app 主题的 23 条 story（不同分支、但编号同为 `1-1-*` 等），v1.3.0 / v1.3.2 等目录下也有同号 story，别拿错。
- **执行顺序**：Epic 1 → Epic 2 → Epic 3 串行（sprint-status 说 2 / 3 可并行，云端单会话一律串行）。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号**：取创建时刻（`V<yyyyMMdd_HHmm>__<snake>.sql`），**避开 app 主题已预分配的 `V20261012_1101` ~ `V20261012_1108`**；同一条 story 有两个迁移时分钟号错开。提交前照常跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **🔴 `ck_notifications_type`（Story 3.1）**：开工当天按 story 3.1「开工第一步」跑跨全部远端分支并集核查；目标全集 34 值 = 现有 31 + `OPS_ANNOUNCEMENT` + `USER_FOLLOWED` + `PLACE_COMMENTED`（D-1，与 app 主题 `V20261012_1108` 同集合）。并集与预期不符 → 以实际为准并在迁移注释与 Completion Notes 写清。改约束必须 DROP + ADD 全量重建；`NotificationType` 末尾按 `OPS_ANNOUNCEMENT`、`USER_FOLLOWED`、`PLACE_COMMENTED` 顺序追加，与 app 主题逐字相同。`MentionNotifyTest` 是纯单元测试，L0 必跑。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同，基准图本地必红）：用结构断言证明行为，golden 比对标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化，会带出几百行无关 diff）；手工对齐缩进，只跑 `flutter analyze`。
- **埋点**：按 D-6 用过去式命名；新埋点按 App 埋点守卫测试（`test/analytics/*_events_test.dart`）的规则登记，不删守卫、不放宽正则。
- **已知存量 flaky**：`PinnedSlotIntegrationTest.pinnedContentYieldsOnFirstPageOnly`、`HealthRecordEndpointIntegrationTest.profileDeletionCascadeHardDeletesRecords`。都是 L1，云端不跑；**不要改断言迁就**。
- 会被按设计打破的既有测试（各 story「必须更新的测试」）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用三类停机条件的：按架构 delta 与决策日志能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
