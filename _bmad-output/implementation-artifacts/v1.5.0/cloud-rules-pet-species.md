- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-pet-species && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-pet-species` 同步；地基提交（Story 0.1，`9680309cf` 或其后）必须已在本分支历史里。
  3. `git push --dry-run origin HEAD:feat/1.5.0-pet-species` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于通用步骤**，三处覆盖：
  1. **后端 L0**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文**的单元测试：`./mvnw -B test -Dtest='<本 story「测试」L0 列的测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、`ApiIntegrationTest` 子类、`@SpringBootTest`。被排除的测试照样写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. story 里标 `[L1]` / `[L2]` 的 AC 云端不验，照写测试，不标完成。
  3. **云端只做 `1-1` ~ `3-5`**（共 10 条）。`4-1`（本地联调）与 `4-2`（合并后对齐，要等另外三条线合入 dev）**云端不做**，做完 3-5 即停下报告。
- **口径优先级**：`implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」> `planning-artifacts/v1.5.0/decision-log.md`（2026-11-13 宠物物种扩展各条）> `planning-artifacts/v1.5.0/宠物物种扩展/1-5-0宠物物种扩展prd.md` > `planning-artifacts/v1.5.0/architecture-v1.5.0-pet-species-delta.md`（D-1 ~ D-20、G-1 ~ G-6）> UI 稿（`拆页-App/`、`拆页-后台/`，页头文案表为准）。story 里写死的值（物种值、清单、文案、日期规则）照用，不自行发明。
- **只按 `sprint-status-v1.5.0-pet-species.yaml` 的完整 key 取 story**——同目录有其他五个会话的同号 story（`1-1-*`、`2-1-*` …），本线文件名都含「物种」，别拿错。执行顺序 Epic 1 → 3 串行，epic 内按编号。
- **story 文件提前写好，引用的是计划中的类名 / 接口 / 字段**。每条开工前先对照前面 story 实际提交的代码；有出入 → 先改本 story 文件（Completion Notes 记一句改了什么），再写代码；不得为迁就 story 文字去改前面已提交的对外契约。
- **迁移号已预分配，不得另取**：`V20261115_0400`（1.1）、`_0410`、`_0420`（1.2）、`_0430`（1.3）；备用 `_0440` ~ `_0459`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。打包一律 `clean`。
- **🔴 地基所有、本线禁改**：`ck_notifications_type` 与 `NotificationType`（本线不新增通知类型，任何迁移都不得 DROP / ADD 它）；后端 `OpsAppPage` 枚举（地基已加 `PET_SPECIES`，不改顺序）；后台 `admin.opsPopups.appPage.*` 标签（地基已加）。App `ops_target.dart` 的 `petSpecies` 值与跳转、后台跳转说明文案归本线。
- **🔴 大类与细分类型三条铁律**（架构 G-2）：
  1. 大类只由 `PetSpecies.group()` 推出；`pet_type` 只经 `PetProfile.applySpecies` 改（`PetSpeciesWriteGuardTest` 守）。
  2. 里程碑 / 新手任务 code 只按 `milestoneTrack` 算（`MilestoneTrackUsageGuardTest` 守）；迁移脚本不改 `milestone_track`。
  3. App 类型名 / emoji 只走 `petSpeciesView`（旧函数保留签名转调）；业务逻辑（健康提醒、送礼物种排序、首页推荐分池、运营人群、礼物适用物种、性格测试、卡号物种码、商城推荐、年龄卡）继续读 `pet_type` / 大类，**一律不改**。
- **🔴 本线最后合并，只追加**（架构 G-1）：`PetProfile`、各返回体、`PetProfileCreateRequest` / `PetProfileUpdateRequest` / `CreateIdCardRequest` 新字段一律加在末尾，便捷构造器保留；不重排、不改别人的注释。送礼面板、推荐卡、建档 / 编辑页里只改类型显示那一行和类型 / 品种两个字段，不碰周围布局。别的线（健康 ①、送礼 ②、首日 ③）正在改同一批文件。
- **🔴 写库监听纪律**：有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)` + 另一个 bean 上的 `@Transactional(REQUIRES_NEW)`；本线的 `pet_type_changed` 埋点监听**不写库**。
- **🔴 里程碑切换不发事件**：`MilestoneTrackSwitcher` 补记完成一律 `MilestoneCompletion.ofCelebrated(...)` 直接存，**不得**发 `MilestoneCompletedEvent` / `AccountMilestoneCompletedEvent`（否则重复发成长值与通知）；退役行与完成记录不删。
- **对外标识**：沿用现状（宠物档案已授权接口带数字 `id`、对外名片用 `cardToken`）；不新增可枚举的对外标识。后台代改审计 summary **不写修改原因**（可能含工单号）；日志不记修改原因、不记宠物名。
- **不引新依赖**：去变音符号自写映射表（不加 `diacritic` 等包）；后台不引前端构建链。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言（Key、文本、顺序、条数）证明行为；golden、模拟器视觉、还原度打分标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。改 ARB 后跑 `flutter gen-l10n`，不手改生成产物。
- **埋点**：事件名写字面量（`pet_type_selected`、`pet_breed_selected`、`pet_breed_search_no_result`、`pet_type_changed`），App 进 `analytics.dart` 白名单；`pet_` 前缀已在 `test/analytics/v112_events_test.dart` 登记，不删守卫、不放宽正则。既有 `pet_profile_create_submitted` 的 `species` 属性保持大类不改（AppsFlyer 归因口径）。
- **文案**：App 新文案 EN / ID 两份 arb 都加（UI 稿页头文案表逐字）；后台三语写 `messages_{zh_CN,en,id}.properties` + 默认 `messages.properties`（含 `{0}` 的词条撇号写 `''`）。
- **运维脚本（1.4）**：只交付与测试，**不连 stag / 生产执行**；脚本里不写任何凭证。
- 会被按设计打破的既有测试（例如「编辑页类型恒锁」「品种 Lainnya 弹窗」「PetType 创建后不可改」）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按决策日志与架构增量能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。epics 末尾「待拍板」Q-1 ~ Q-10 已按推荐默认写进 story，照做。
