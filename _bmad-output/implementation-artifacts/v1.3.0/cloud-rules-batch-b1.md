- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：admin / batch-a 两条线都出过「云端做完推不上 GitHub」——会话若是从本地打包（bundle）起的，副本里没有可推的远端，干一整天产出为零。所以先自检：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.3.0-batch-b1-places-social && git status -sb` 必须在该分支且与 `origin/feat/1.3.0-batch-b1-places-social` 同步。
  3. `git push --dry-run origin HEAD:feat/1.3.0-batch-b1-places-social` 必须成功（验证有推送权限）；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失说明起错了环境 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败同样立刻停下报告，不许攒着本地提交继续往下做。
- 口径优先级：**决策日志-batch-b1.md（B1-D1~D13）> PRD > UI 稿**。PRD 已被推翻两处：地图选型（B1-D9：OSM → Google，且只做显示 + 打自己的点）、Diary 推荐覆盖人群（B1-D2：扩到「声明未养宠 / 计划养宠」态）。架构以 `architecture-v1.3.0-batch-b1-delta.md`（AD-1~AD-10）为准，代码现状以 `代码核对报告-batch-b1.md` 为准。
- 同一版本目录下 admin / batch-a / batch-b1 三个主题各自独立成域，Epic 号段会重复：**只按 `sprint-status-v1.3.0-batch-b1.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配，提交信息与 Completion Notes 里引用 story 一律带主题（如「batch-b1 的 1-1」）。
