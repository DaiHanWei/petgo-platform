- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.3.4-app-ux-revamp && git status -sb` 必须在该分支且与 `origin/feat/1.3.4-app-ux-revamp` 同步。
  3. `git push --dry-run origin HEAD:feat/1.3.4-app-ux-revamp` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再 `./mvnw -B test` 并**排除需要数据库 / Redis 的测试**（`*IntegrationTest`、继承 `ApiIntegrationTest` 的类；具体排除写法见各 story「验证层级」一节）。云端没有 Docker，跑全量必红、会死锁。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **迁移版本号覆盖「取创建时刻」**：8 个迁移号已预分配、写在各 story 里（`V20261012_1101` ~ `V20261012_1108`），**照用，不得另取**；提交前照常跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **口径优先级**：`决策日志-app.md`（D-1~D-25）> `PRD-v1.3.4-app.md` > `architecture-v1.3.4-app-delta.md`（AD-1~AD-20）> `ui-v1.3.4-app.html`。**D-15~D-25 是 2026-10-12 上云预检的拍板**（里程碑说明文案、`audience_spec` 结构、版本配置错误码、版本号预置 1.3.3、单人推送循环、批量 / 重试 / 扫描 / 超时参数、弹窗图片 5s 超时、弹窗延后 500ms 与二次检查、`ops_popups` 可空性、埋点用 `public_token`、`decision` 属性），**一律照 story 里写死的值，不自行发明**。
- **只按 `sprint-status-v1.3.4-app.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配（v1.3.0 / v1.3.2 等目录下有同号 story，别拿错版本目录）。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **两枚新徽章素材已预置入库**（与 V1.3.2「设计素材不入库」不同）：`petgo_app/assets/milestone/place_checkin.webp`、`tailsonality.webp` 与 `petgo-backend/src/main/resources/static/milestone/` 下同名文件同哈希。**不得改名、不得重新转码**（`MilestoneBadgeAssetSyncTest` 按 SHA-256 比对）。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同，基准图本地必红）：用结构断言（`Alignment` / `BoxFit` / 比例值）证明行为不变，golden 比对标 L2 留本地。
- **🔴 6.1 开工当天重跑 `ck_notifications_type` 跨全部远端分支并集核查**（命令在 story 6.1）：预检时现行并集 31 值 + `OPS_ANNOUNCEMENT` = 32；并集变了以实际为准写迁移，迁移注释与 commit 描述列出全集。改 CHECK 约束必须 DROP + ADD 全量重建。
- **本主题不碰 admin 页面与后台写接口**（D-2）：版本配置页、运营弹窗配置页、运营通知发送页都在 admin 主题；本主题只交付数据表、App 接口与核心服务方法，用迁移 / 测试预置数据自测。
- **埋点**：运营弹窗 / 通知的标识一律用 `public_token`；外部跳转链接只允许 `http` / `https`；新埋点按 App 埋点守卫测试（`test/analytics/*_events_test.dart`）的命名规则登记，不删守卫、不放宽正则。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化，会带出几百行无关 diff）；手工对齐缩进，只跑 `flutter analyze`。
- **已知存量 flaky**：`PinnedSlotIntegrationTest.pinnedContentYieldsOnFirstPageOnly`（推荐流首屏让位的顶置帖被 consumed 吃掉，随机种子决定红绿）、`HealthRecordEndpointIntegrationTest.profileDeletionCascadeHardDeletesRecords`（异步里程碑与删档竞速）。二者都是 L1，云端不跑；本地若红先认存量问题，**不要改断言迁就**。
- 会被按设计打破的既有测试（各 story「必须更新的测试」）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用三类停机条件的：按架构 delta 与决策日志能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
- **按 Epic 1→6 串行**：5.3 要回头给 3.2 强制更新弹窗、4.3 庆祝页补「功能弹窗」登记；6.1 / 6.3 复用 5.1 / 5.2 的统一跳转目标。不跳序、不并行。
