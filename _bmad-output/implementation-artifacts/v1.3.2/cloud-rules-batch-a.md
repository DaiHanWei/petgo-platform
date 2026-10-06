- **🔴 第 0 步（开工前必做，不过就立刻停下报告，一行代码都不许写）**：V1.3.0 两条线出过「云端做完推不上 GitHub」，干一整天产出为零。先自检：
  1. `git remote -v` 必须有 `origin` 指向 `github.com/DaiHanWei/petgo-platform`；没有 remote → 停。
  2. `git fetch origin feat/1.3.2-batch-a-app-feature && git status -sb` 必须在该分支且与 `origin/feat/1.3.2-batch-a-app-feature` 同步。
  3. `git push --dry-run origin HEAD:feat/1.3.2-batch-a-app-feature` 必须成功；失败 → 停。
  4. 环境必须是 `tailtopia-l0`：`flutter --version`、`java -version`（21）、`cd petgo-backend && ./mvnw -v` 三个都能跑；任一缺失 → 停。
  - 四项全过后，把自检输出贴在第一条回复里再开工。之后**每完成一条 story 立即 push**，push 失败立刻停下报告，不许攒本地提交继续做。
- **口径优先级**：`决策日志-batch-a.md`（D-1~D-20、C-1~C-13）> PRD（`PRD-v1.3.2-batch-a.md` / `PRD-v1.3.2-batch-a-admin.md`）> `tailsonality-内容设计.md` > UI 稿。技术口径以 `architecture-v1.3.2-batch-a-delta.md`（AD-1~AD-20）为准，代码现状以 `代码核对报告-batch-a.md` 为准。PRD / 内容设计里已被决策日志推翻的条目（硬编码价格、允许 0 元、单色章 + 圆形安全区、长按菜单、保存进度、配型付费等）**一律按决策日志**。
- **只按 `sprint-status-v1.3.2-batch-a.yaml` 里的完整 key 取 story**，不按 `<epic>-<n>` 前缀通配（V1.3.0 目录下也有同号 story，别拿错版本目录）。
- **🔴 story 文件是一次性提前写好的，引用的是「计划中的」类名 / 接口 / 字段**。每条 story 开工前，先对照**前面 story 实际提交的代码**核对本 story Dev Notes 里引用的名字；有出入 → **先改本 story 文件**（在 Completion Notes 记一句改了什么），再写代码。不得为了迁就 story 文字去改前面 story 已提交的对外契约。
- **🔴 设计素材一律不入库、不复制**（D-12 更新，2026-09-29）：story 里凡写「从 `~/Downloads/设计图/` 复制到 `assets/...`」的步骤**全部改为占位**——云端看不到本机，且设计图不是最终版。做法：
  1. 在 story 约定的素材路径与文件名（如 `assets/tailsonality/role_ENTJ.webp`、`assets/tailsonality/match_tier1.webp`、`assets/milestone/*`、默认章 / 默认场所图、图片题图）**只在代码里引用，不提交任何二进制占位图**；
  2. 渲染一律带回落：`Image.asset(..., errorBuilder: …)` 或先查映射，文件缺失时画一个**代码绘制的占位**（浅色底 + 描边 + 类型 / 代号文字或图标），尺寸比例与最终素材一致；
  3. 需要在 `pubspec.yaml` 声明的素材目录，放一个 `README.md` 写明「放什么文件、命名规则、比例 / 尺寸」，让目录存在、`flutter analyze` 不报缺失；
  4. 目标：设计交付后**只往目录里放文件、不改一行代码**就生效（NFR-9）。有测试钉「文件缺失时不崩、显示占位」。
- **EN / ID 翻译由你完成**（D-8）：story 2-2 等要求的全部新文案，按内容设计中文版翻成印尼语（主）+ 英语，口吻照内容设计已给出的 EN / ID 样例。待定文案（D-18 配型总评 / 总结句 / 卡面标语、D-19 能量段正文）先落占位并加 `// PENDING D-18` / `// PENDING D-19` 注释，不自行扩写。
- **不修线上 KTP 问题**：决策日志末尾记录的「KTP 高清 QRIS→PawCoin 共用幂等键」属线上版本，**本分支不碰** `IdCardHdService.purchaseCard` 的既有逻辑；3-1 只保证新 SKU 的幂等键按渠道加后缀。
- **3-1 开工当天重跑** `ck_payment_intents_purpose` 跨分支并集核查（story 3-1 里有命令），并集变了以实际为准写迁移，PR / commit 描述列出全集。
- **按块跑**：本会话只做**未被跳过的 Epic**；该 Epic 的 story 全部置 review 后**立刻结束会话**并输出汇总，不要开始被跳过的 Epic，也不要把被跳过 Epic 的 story 改状态。
- 会被按设计打破的既有测试（代码核对报告 §0 及各 story「必须更新的测试」）：**更新断言表达新规则**，不删测试、不 `skip`。
- 碰到 story 与代码现状矛盾且 story 未给处置、又不属于上面三类停机条件的：按架构 delta 与决策日志能推出的最保守做法实现，并在 Completion Notes「待确认」小节写清，**不停下问**。
