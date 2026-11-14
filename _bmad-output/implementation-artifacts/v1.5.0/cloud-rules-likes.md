- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.5.0-likes && git status -sb` 必须在该分支且与 `origin/feat/1.5.0-likes` 同步；`git log --oneline -1 origin/dev_1.5.0` 的地基提交（`9680309cf` 或之后）必须是本分支的祖先。
  3. `git push --dry-run origin HEAD:feat/1.5.0-likes` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于通用步骤**，两处覆盖：
  1. **后端 L0 命令**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「测试」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里标 `[L1]` / `[L2]` 的 AC 在云端不验**，只验 `[L0]`；照写测试，不标完成。
- **口径优先级**：`planning-artifacts/v1.5.0/decision-log.md` > PRD（A：`自动点赞/1-5-0自动点赞prd.md`，英文；B：`Feed双击点赞/1-5-0Feed双击点赞prd.md`）> `architecture-v1.5.0-likes-delta.md`（L-1~L-14、F-1~F-9、G-1~G-6）> UI 稿。story 里写死的值照用，不自行发明。PRD A 的需求号是 **FR-212 ~ FR-223**（旧号 FR-193~204 已作废）。
- **只按 `sprint-status-v1.5.0-likes.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同目录有另外五条线的同号 story。
- **执行顺序**：Epic 1 → 2 → 3 → 4 串行，epic 内按编号。**会话做到 `4-2` 为止**；`4-3-feed双击点赞与首日推荐页合并回归` 不在云端做（要等 ③ 合进 dev，在对齐分支本地做），把它留在 ready-for-dev。
- **Epic 2 做完（2-4 提交 push 后）**：在 2-4 Completion Notes 写下该提交 sha，并在回复里单独一行报给主会话——后端可截到这一点先合（待拍板 Q-7）。不要自己合并、不要部署。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字与行号；有出入 → **先改本 story 文件**（Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**：1-1 `V20261115_0500`；2-1 `V20261115_0510`；2-2 `V20261115_0520`；预留 `_0530 ~ _0559`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。新表新列都写 `COMMENT ON`。
- **🔴 不碰地基**：不改 `ck_notifications_type`、不加 `NotificationType`、不改 `OpsAppPage` / `AppVersionGate` / `RetentionPushCalendar` / `users.last_app_version`。自动赞走现成的 `CONTENT_LIKED`。
- **🔴 多实例安全只用仓库已有的两种锁**：计划阶段 `pg_try_advisory_xact_lock(hashtext('auto_like_plan'))`（照 `TopicHeatJob`）；派发阶段 Redis `SET NX PX` + uuid 比对释放（照 `BiteshipJobLock`，本包内自写，不跨包引用包私有类）+ 行级条件 UPDATE 认领 + 唯一键。**不引 ShedLock / Quartz / MQ / 缓存层 / 任何新依赖。**
- **🔴 `@Scheduled` 的 cron 必须写 `zone = "Asia/Jakarta"`**（容器默认 UTC）。双构造器（注入 `Clock` / 随机源）主构造器必须标 `@Autowired`，否则启动即崩。
- **🔴 排除口径**：排序 / 推荐 / 热度 / 运营统计用 `countOrganicByPostIdIn` 或 SQL `AND NOT l.is_automated`；**展示赞数（Feed 卡、详情、点赞人、主页 Suka、后台列表默认模式）不排除**。不要给 `countByPostIdIn` 加过滤。
- **🔴 `ContentLikedEvent.automated = true` 只有成长值监听跳过**；里程碑、@ 候选、通知照常。不要在任何别的监听里加 `automated` 判断（③ 的新手任务 ② 也不得排除，A-AC42）。
- **🔴 开关默认值**：base `application.yml` `true`（stag / 生产部署即生效），`application-dev.yml` `false`。不要改反，不要往 `.env.example` 加。
- **日志纪律**：自动点赞相关日志只记数字、id 计数与异常类名，不记帖子正文、昵称、帖子 id 列表。
- **App 侧**：
  - 3-1 先做，`like_button` 现有测试**断言不改**全绿后才开始 4-1（PRD B 改造顺序）；只允许为新构造方式补 `ProviderScope` / override，并在 Completion Notes 逐个说明。
  - 单击 / 双击窗口只复用 `kLightboxSingleTapDelay`（220ms），不另定时长、不改该常量、不改大图页行为。
  - 颜色只用 `AppColors`；新增 `likeHeartLight` / `likeHeartDeep` 两个常量，不写死 hex。动效用 Flutter 原生，不用 Rive。
  - **视觉一致性不写 golden**（云端 Linux 与本机字体渲染不同）：用结构断言证明行为；模拟器视觉、帧率、震动、读屏、还原度打分标 L2 留本地。
  - **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。本主题无新文案，不改 ARB。
  - 埋点：`post_like_tapped` 事件名不改，只加属性 `trigger`（`button` / `double_tap`）；已赞 / 飞行中再双击不报；护栏测试不删、不放宽。
- 会被按设计打破的既有测试（如 `FeedRecommendationServiceTest` mock 的方法名、日报卡片多一行）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按决策日志与架构增量能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。待拍板 Q-1 ~ Q-10 已按推荐默认写进 story，照做。
- 提交信息按仓库约定；不合并、不部署任何环境（stag 也算），止步 push 功能分支。
