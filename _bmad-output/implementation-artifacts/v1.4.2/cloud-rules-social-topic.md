- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.4.2-social-topic && git status -sb` 必须在该分支且与 `origin/feat/1.4.2-social-topic` 同步。
  3. `git push --dry-run origin HEAD:feat/1.4.2-social-topic` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **🔴 本文件优先于上面的通用步骤**，两处覆盖：
  1. **后端 L0 命令覆盖第 2 步**：`cd petgo-backend && ./mvnw -B clean package -DskipTests`，再只跑**不需要数据库 / Redis / Spring 上下文的单元测试**：`./mvnw -B test -Dtest='<本 story「验证层级」里列的 L0 测试类，逗号分隔>' -Dsurefire.failIfNoSpecifiedTests=false`。**不跑** `*IntegrationTest`、继承 `ApiIntegrationTest` 的类、任何 `@SpringBootTest`。云端没有 Docker，跑全量必红、会卡死。被排除的测试照样要写好、能编译，Completion Notes 记「L1 待本地验收」。
  2. **story 里「L1 … 全绿」一类 AC 在云端不验**，只验 L0；照写测试，不标完成。
- **口径优先级**：`planning-artifacts/v1.4.2/决策日志-social-topic.md`（R-1~R-5）> `追更话题/1-4-2追更宠物·里程碑·话题prd.md` > `architecture-v1.4.2-social-topic-delta.md`（D-1~D-23、P-1~P-12、G-1~G-7）> UI 稿。story 里写死的值照用，不自行发明。
- **只按 `sprint-status-v1.4.2-social-topic.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配——同版本目录之后会有电商线的同号 story，别拿错。
- **执行顺序**：Epic 1 → 7 串行，epic 内按编号串行。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **迁移版本号已预分配，不得另取**（见 sprint-status 头注释）：1.1 `V20261104_2000`；1.2 `V20261104_2010`、`V20261104_2020`；2.1 `V20261104_2030`；2.2 `V20261104_2040`；4.1 `V20261104_2050`、`V20261104_2055`；7.1 `V20261104_2058`。提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- **🔴 `ck_notifications_type` 重建（1.2）**：DROP + ADD 重列全集，值必须取**当前树里最后一条重建它的迁移**的原文全集再加 `PET_FOLLOWED`、`PET_DIARY_POSTED`，不写死数量、不凭记忆抄；`PushCategoryTest`、`MentionNotifyTest` 同 story 内一起改。
- **🔴 写库监听纪律**：有写库动作的事件监听一律 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`（否则写入静默不提交，线上出过事故）；扇出再加 `@Async`。
- **🔴 话题名规则只有一份实现**：后端 `TopicNameRules`、App `shared/rich_text/body_tokens.dart`，两边测试读同一份 `petgo-backend/src/test/resources/fixtures/topic-parse-cases.json`；禁止在别处另写话题正则。
- **🔴 可见性过滤四件套**（举报过的帖、我隐藏的作者、作者已注销、作者非 ACTIVE）在新查询里与 `findFollowingFeed` 逐条一致；「作者拉黑了我」只在追更宠物一路加（R-1）。
- **🔴 对外标识**：话题对外用标准名、不暴露自增 id；埋点 `topic_id` = 标准名、`owner_id` = 主人公开 token（R-2）。
- 不引 MQ / 缓存 / 新依赖；输入框高亮自写 `TopicMentionTextController`，不引第三方富文本库；后台通知编辑区用原生 `contenteditable`，不引编辑器库。
- **视觉一致性不写 golden 截图**（云端 Linux 与本机 macOS 字体渲染不同）：用结构断言证明行为，golden、模拟器视觉与还原度打分标 L2 留本地。
- **不要对整个文件跑 `dart format`**（存量代码未统一格式化）；手工对齐缩进，只跑 `flutter analyze`。改 ARB 后跑 `flutter gen-l10n`，不手改生成产物。
- **埋点**：事件名写字面量（不用常量），新前缀 `topic_`、`home_topic_` 登记到 `test/analytics/v112_events_test.dart`，不删守卫、不放宽正则；属性严格按 PRD §4 与决策日志 R-2。
- **文案**：App 新文案 EN / ID 两份 arb 都加（PRD §7）；后台三语写 `messages_{zh_CN,en,id}.properties`（PRD §7 后台文案表的建议 key，标「复用」的引原 key；含 `{0}` 的词条撇号写 `''`）。
- 会被按设计打破的既有测试：**更新断言表达新规则**，不删测试、不 `skip`。
- 素材（新徽章 `first_friend` / `first_save`、里程碑卡花结 SVG）未到：按 story 用兜底样式，不阻塞，Completion Notes 记「待素材」。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于通用停机条件的：按决策日志与架构增量能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
