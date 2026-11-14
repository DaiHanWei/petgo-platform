- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-onboarding-recall && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-onboarding-recall` 同步（分支从 `dev_1.5.0` 切出，已含地基 Story 0.1 `9680309cf`）。
  3. `git push --dry-run origin HEAD:feat/1.5.0-onboarding-recall` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于通用步骤**，两处覆盖：
  1. **后端 L0 命令**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里标 `[L1]` / `[L2]` 的 AC 在云端不验**，只验 `[L0]`；照写测试，不标完成。`4-1`、`8-1` 的 L1 / L2 部分只写清单，留本地。
- **口径优先级**：`planning-artifacts/v1.5.0/decision-log.md` > `implementation-artifacts/CROSS-STORY-DECISIONS.md`「2026-11-14 V1.5.0 六会话共用地基」> 两份 PRD（`首日与新手任务/1-5-0首日与新手任务prd.md`、`推送与召回/1-5-0推送与召回prd.md`）> `planning-artifacts/v1.5.0/architecture-v1.5.0-onboarding-recall-delta.md`（OR-1 ~ OR-26）> UI 稿（整合稿页头执行说明 + `拆页-App/`）。story 里写死的值照用，不自行发明。`epics-v1.5.0-onboarding-recall.md` 末尾「待拍板」已按推荐默认写进 story，照做即可。
- **只按 `sprint-status-v1.5.0-onboarding-recall.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同目录有健康提醒、点赞、送礼、物种等五条线的同号 story，别拿错。
- **执行顺序**：Epic 1 → 8 串行，epic 内按编号串行。Epic 1 ~ 4 是首日与新手任务，Epic 5 ~ 8 是推送与召回。
- **🔴 首日检查点**：`4-1` 的 L0 全绿后，在 Completion Notes 写「首日检查点：<commit sha>」并 push，然后**继续**做 Epic 5。是否把本分支合入 `dev_1.5.0` 由操作者决定；云端**不合并、不开 PR、不把 dev 反向合进本分支**。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**（见 sprint-status 头注释）：2-1 `V20261115_0300`；2-2 `V20261115_0310`、`V20261115_0315`；2-4 `V20261115_0320`；5-1 `V20261115_0330`；备用 `_0340 ~ _0359`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`；打包一律 `clean`。
- **🔴 地基归属（违反即返工）**：
  - `ck_notifications_type` **一律不改**（地基 `V20261114_1000` 已含 `USER_POST_PUBLISHED`）；`NotificationType` / `PushCategory` 只用不改（`USER_POST_PUBLISHED` → `PET_DIARY` 已归类）。不新增推送分类、不动 `ck_user_push_mutes_category`。
  - 版本门槛（FR-185：关注更新、D1 / D3 / D7 召回只发 ≥ 1.5.0）**只走** `notify.service.AppVersionGate`（批量用 `usersOnV150OrLater`），不另写版本解析。
  - `notify.lifecycle.RetentionPushCalendar`：**本线替换 `anniversaryPushPets` 方法体**（5-2）；`recallNodeUsers` 节点规则不变、方法体不改；**签名 / 包名都不动**（健康提醒线在调用并 mock）。
  - `ScheduledPushPlanner.plan(LocalDate, List<PetProfileSnapshot>, Set<String>)` 签名保留（健康线调它判生日占用）；`PetProfileSnapshot` 加字段时保留原 5 参构造器。
  - App `kPushDirectTypes`（`core/push/push_service.dart`）：**只追加本线 6 个值** `USER_POST_PUBLISHED`、`PET_DIARY_POSTED`、`COMPANION_ANNIVERSARY`、`LIFECYCLE_D1`、`LIFECYCLE_D3`、`LIFECYCLE_D7`；健康提醒、运营活动线也会加，不删不改别人的值。`PushExitStructureTest.PUSHER_CALLER_METHODS` 同理取并集。
  - 推送设置「Hewan yang kamu ikuti」那一行改名**归本线**（arb `petFollowPushTitle / petFollowPushDesc` 改值，key 不改）；健康线只改 `pushSettingsPetReminderDesc`，别碰它。
- **🔴 跨线约定**：
  - **Feed 双击点赞（会话 ⑤）** 在本线首日部分之后合并，会改造 `LikeButton` 并作用到本线推荐卡 / 游客区：本线**只用 `LikeButton` 的公开参数**，不 fork、不复制状态机；需要 `entrySource` 就给 `LikeButton` 加一个可选参数（默认值保持现状），别的不动。
  - **自动点赞（会话 ⑤）**：`content_likes.is_automated` / `ContentLikedEvent.automated` 由 ⑤ 线加；自动点赞**计入**新手任务 ②——本线任何监听、补算 SQL **都不得**按 automated 过滤。
  - **成长值 `NEWBIE_GRADUATED`** 用本线独立迁移插一行（`V20261115_0315`），不改已有规则行；运营活动线往 `growth_rules` 插它自己的行，互不冲突。
  - **物种扩展（会话 ④，最后合）**：推荐「同物种」匹配只收口在 `PetRecommendationService.speciesKeyOf` + SQL 过滤列一处，本线按现有 `pet_type` 实现，不预做细分类型；`PetType` 枚举不改。
  - `ck_user_milestone_flags_key`、`ck_user_newbie_marks_key` 是本线自有约束，DROP + ADD 重列全集，值取当前树最后一条定义它的迁移原文。
- **🔴 写库监听纪律**：有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)`，写库放在**另一个 bean** 的 `@Transactional(REQUIRES_NEW)` 方法里（否则写入静默不提交，线上出过事故）；扇出再加 `@Async`，`@Async` 线程里不开外层事务，禁止 `this.xxx()` 自调用事务方法。
- **🔴 护栏**：不引 MQ / 通用缓存 / 新依赖（游客推荐 10 分钟快照是进程内单值，见 OR-5，别引 Caffeine / Redis 缓存）；调度用原生 `@Scheduled`；`ddl-auto=validate`；凭证与开关全走 env，`.env.example`（`petgo-backend/.env.example`）只放占位；日志不记昵称、宠物名、文案、评论原文，只记 id / 计数 / 异常类名。
- **旧版 App（< 1.5.0）兼容**按架构 §7 做：新手任务接口、里程碑列表按 `X-App-Version` 分流；关注更新旧版只写通知中心不推；召回旧版不发、不写 mark。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言证明行为；golden、模拟器视觉与还原度打分（≥ 95%）标 L2 留本地（4-1 / 8-1）。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。改 ARB 后跑 `flutter gen-l10n`，不手改生成产物。
- **埋点**：事件名 / 属性写字面量（不用常量），新事件登记到 `petgo_app/test/analytics/` 下现有事件清单守卫测试，不删守卫、不放宽正则；登录来源属性名是 `entry_source`（新值 `guest_real_pets`）；后端 `newbie_task_completed` 走 `AnalyticsClient.capture`（同步，不加 `@Async`）。
- **文案**：App 新文案 ID / EN 两份 arb 逐字照两份整合稿「新增文案」表 / PRD 附录 A，不改写不翻译；后端推送文案写四个 `messages*.properties`（`_id`、`_en`、默认同 EN、`_zh_CN` 中文意思），含 `{0}` 的词条撇号写 `''`。
- 会被按设计打破的既有测试（`NewbieTaskServiceTest`、`MilestoneCompletionServiceTest`、`ScheduledPushPlannerTest`、`LifecyclePushPlannerTest`、`LifecyclePushCopyTest` 等）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按决策日志与架构增量能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
- 🔴 **不部署任何环境**（stag 也不行）、不改服务器 env；`8-1` 的上线清单只写文档。
