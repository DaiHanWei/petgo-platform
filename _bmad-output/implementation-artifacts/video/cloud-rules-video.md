## 专题线 video 的追加规则（覆盖上面模板里与之冲突的部分）

### 版本与路径
- 这是**专题线 video**，不挂版本号：规划在 `_bmad-output/planning-artifacts/video/`，story 与排期在 `_bmad-output/implementation-artifacts/video/`，排期文件是 `sprint-status-video.yaml`（没有主题后缀）。调用任何 BMAD 技能时，提示词里写明「专题线 video」。
- 权威顺序：`决策日志-video.md` > `PRD-video.md` > `architecture-video-delta.md` > `epics-video.md` > `addendum-video.md`（只有 A / B 两节有效）。

### 第 0 步：自检（不过就停下报告，不要硬跑）
- `git status` 干净、在 `feat/video`、与 `origin/feat/video` 一致；`git push --dry-run origin feat/video` 能成功（推不上 = 环境没装 Claude GitHub App，停）。
- `cd petgo-backend && ./mvnw -B -q -o -DskipTests compile` 或在线编译通过；`cd petgo_app && flutter pub get && flutter analyze` 零问题。

### 第 1 步：先复审已完成的 Epic 1
- 1-1 是 done；**1-2 ~ 1-6 是 review，但还没做过代码评审**。依次对 1-2、1-3、1-4、1-5、1-6 跑 `bmad-code-review`（对照各自 story 文件），CONFIRMED 的修掉、L0 跑绿、每条一个 commit（`fix(video): <story key> code review`）并推送。评审完把该 story 置 **done 以外的状态不变**（仍为 review，等本地 L1/L2 验收后再由人标 done）。

### 第 2 步起：Epic 2 → Epic 3（覆盖模板第 1 步）
- 本线 Epic 2、Epic 3 的 story 在排期里是 **backlog**，没有 story 文件。循环规则改为：按 `sprint-status-video.yaml` 从上到下取第一条 **backlog** 的 story（跳过 `epic-*` 与 `*-retrospective` 键）→ 先跑 `bmad-create-story`（生成 story 文件、置 ready-for-dev）→ 再按模板第 2–5 步 dev / code-review / 提交推送 / 置 review。
- 循环终止条件改为：排期里 story 键没有 backlog 也没有 ready-for-dev。
- 顺序：Epic 2（2-1 → 2-6）→ Epic 3（3-1 → 3-4）。Epic 1 的 story 不重做。

### 本线已经踩过的坑（必须遵守）
- **埋点命名**：仓库规范测试 `test/analytics/v112_events_test.dart` 要求事件名「模块前缀 + 对象 + 动作」且**动作在词尾**、属性名 **snake_case**。`video_` 前缀已登记。架构 AD-10 表里的名字不合规，Epic 2 按同一规则改名，例如：`video_card_impression`→`video_card_shown`、`video_play_start`→`video_play_started`、`video_progress`→`video_progress_reported`、`video_complete`→`video_play_completed`、`video_buffering`→`video_buffering_started`、`video_play_error`→`video_play_failed`、`video_watch_time`→`video_watch_time_reported`；属性 `ttffMs`→`ttff_ms`、`postId`→`post_id` 等。需要的动作后缀不在允许表里时，按该测试里已有的写法加注释登记，不要放宽规则。Epic 1 已用名：`video_spec_check_completed`、`video_upload_failed`、`video_publish_succeeded`。
- **后端测试**：Spring Boot 4 用 Jackson 3，测试里 `ObjectNode` 是 `tools.jackson.databind.node.ObjectNode`；造虚拟账号的 sub 必须以 `virtual:` 开头（否则 `SyntheticAccountParityTest` 红）；发帖接口限流 20 次 / 分钟 / 人，同一测试里批量打接口要换用户；`ApiIntegrationTest` 已用 `media.video.acl-reconcile-cron=-` 关掉对账定时任务，对账逻辑由测试直接调用。
- **云端无 Docker**：L1 集成测试（继承 `ApiIntegrationTest`）跑不了 —— 照常写好，`./mvnw -B clean package` 若因无库失败，改跑 `./mvnw -B clean package -DskipTests` + 单独跑纯单元测试类（不继承 `ApiIntegrationTest` 的），并在 Completion Notes 写清「L1 未跑，待本地」。不许为了让云端变绿而删 / 跳过 L1 测试。
- **App**：测试放 `test/features/content/...`；不要整文件 `dart format`；改 ARB 后 `flutter gen-l10n`；只有 en / id 两份 ARB；`microcopy_rules_test` 会拦英印同形词（同形且确实不需翻译时登记进 `_sameInBothLocales` 并写理由）。
- **已有可复用的件**：`VideoUrlResolver`（公开 / 签名地址，2.1、3.3 用）、`MediaAclReconciler.reconcileOne`（3.1 的下架 / 恢复入口在事务外调用）、`ContentPost.desiredOf` 与实体内重算（3.1 只需补注销批量 SQL 与 L1 覆盖）、`CdnCacheRefresher`（3.4 只做真实接入与验证）、`VideoSpecChecker` / `VideoDraft`、`video_post_repository.dart`。
- 后端 1.1–1.4 尚未部署到任何环境；不要部署，不要连 stag / 生产。
