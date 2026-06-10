# TailTopia 云端开发循环 Runbook（claude.ai/code 过夜批跑）

> 目标：本地负责**规划 + 视觉/集成验收**，云端 headless VM 负责**过夜批量实现 + 命令行验证（L0）**。Mac 合盖/关机不影响云端 session。

## 原理一句话

`claude --remote "..."` 把任务跑在 Anthropic 托管的云 VM 上，clone 本仓库执行，关浏览器仍继续，手机 App 能盯进度。每次 `--remote` = 全新隔离 VM（天然 `/clear`）。

## 角色边界（本项目是双产物全栈，非纯前端原型）

| 工作 | 在哪做 | 原因 |
|---|---|---|
| 规划 / 改 story / 拍决策 | **本地** | 需要人参与 |
| 视觉验收（Flutter UI / Tab Bar / 动效） | **本地** | 云端 headless 无模拟器（L2 视觉） |
| L1 集成（Docker postgres+redis + `/actuator/health`） | **本地**（默认） | 云沙箱不保证有 Docker daemon |
| L0 命令行验证（`flutter analyze/test`、`mvn -B package`） | **云端** | headless 即可，适合过夜批跑 |

---

## Stage A — 本地环境就绪（做 story 1.1 的前置）

story 1.1 的 Dev Notes 已探测：本机 **Flutter 未装、JDK 是 17（需 25）、Docker daemon 常没起**。先补齐：

```bash
# 1) Flutter 3.44.x（本机没有）—— 按官方 install 装 stable，确认 flutter --version 是 3.44.x
# 2) JDK 21（pom 写死 21）—— 本机若无：jenv/sdkman 装 21；本仓库已 jenv local 21
jenv local 21    # 或 sdk install java 21-tem
# 3) Docker daemon（L1 验收用）
open -a Docker                                      # 等就绪
```

## Stage B — 本地做 story 1.1（脚手架地基）

1.1 从零生成 `petgo-backend/` + `petgo_app/`，是后续所有云端 session 的地基，**必须本地先做掉并 push**（视觉骨架要人确认能跑）：

```
（在 Claude Code 里）dev this story _bmad-output/implementation-artifacts/1-1-双产物脚手架与本地可运行骨架.md
```

验收（L0+L1+视觉）通过后：

```bash
git add -A && git commit -m "feat(scaffold): story 1.1 双产物脚手架" && git push
```

> 1.1 push 后，`petgo_app/pubspec.yaml` 存在 → 本仓库的 SessionStart hook 才会真正开始 `flutter pub get`（之前是静默跳过）。

## Stage C — 仓库侧云端插桩（✅ 已就位，本 PR 已建）

- `CLAUDE.md`（根）：双产物结构 / 版本基线 / 命名 / 护栏 / 云端 L0 边界 —— 云端 clone 后自动加载
- `.claude/settings.json` + `.claude/hooks/session-start.sh`：SessionStart 自动 `flutter pub get`（容错，无脚手架时跳过）
- `.gitignore`：放行 `.claude/settings.json` 与 `.claude/hooks/`（其余 `.claude/` 仍忽略）—— 云端只读 commit 的 hook
- `scripts/cloud-setup.sh`：Web UI setup script 的入库底稿

> 把本 Stage 的文件 commit + push，云端才看得到。

## Stage D — 账号 / Web UI 侧（只有你能做，一次性）

1. **接 GitHub（二选一）**
   - 终端：在 Claude Code 里跑 `/web-setup`（同步本地 `gh` token + 建默认云环境），或
   - 网页：首次 onboarding 时授权 **Claude GitHub App**（要 Auto-fix 选这个）
2. **配 setup script**：claude.ai/code → Environment → Setup script，**粘贴 `scripts/cloud-setup.sh` 的内容**。
3. **网络**：选 **Trusted**（含 pub.dev / storage.googleapis.com）；若 JDK/Flutter 首跑下载被挡，切 **Full** 或加 Custom 域名。
4. **首跑验证**：开一个测试 session，确认 setup 末尾 `flutter --version && java -version && mvn -version` 三个都过；过了就被缓存，后续不重装。
   - 改了 setup script 会重建缓存——前 1-2 次按 `VERIFY` 标记迭代是正常的。

> 套餐要求：Pro / Max / Team / Enterprise（云端为 research preview，无单独算力计费，走现有额度）。

## Stage E — 跑 story 循环（过夜批跑）

**顺序铁律**（`sprint-status.yaml`）：严格 **Epic 1→7、story 编号升序**；Flyway 序号按执行顺序**单调分配**，**不能 46 个全并发**（迁移号会撞 → 决策 E2）。串行或小批：

```bash
claude --remote "按 bmad-dev-story 流程执行 _bmad-output/implementation-artifacts/1-3-google-登录与-jwt-签发.md。\
云端 headless：只做到 L0（mvn -B package + flutter analyze + flutter test）绿灯；\
L1/L2 在 Completion Notes 标注「待本地验收」。完成后提交到本 story 分支并开 PR。"
```

每条 `--remote` = 独立云 session = 全新上下文（无需 `/clear`）。

### Stage E-batch — 按 Epic 批量执行（连续推进 + Epic 末检查点）

一条云会话做完**一个 Epic 内的连续 story**，Epic 末停下等本地验收+合并，再放下一个 Epic。把下面模板里的 `<EPIC_N>` 与 story 范围替换后，`claude --remote "<模板>"` 发起：

```
你是 TailTopia 的 dev agent（云端 headless）。任务：按 bmad-dev-story 流程，依次实现 <EPIC_N> 中所有 ready-for-dev 的 story（按 sprint-status.yaml 的 story 编号升序），一个做完再做下一个，直到该 Epic 全部完成。

每个 story：
1. 用 bmad-dev-story 流程执行对应 _bmad-output/implementation-artifacts/<story>.md（让 bmad-dev-story 自动挑下一个 ready-for-dev 的）。
2. 云端只做 L0 绿灯：前端 flutter analyze + flutter test；涉后端 cd petgo-backend && ./mvnw -B -DskipTests package。
   L1(Docker)/L2(凭证/视觉) 不做——在该 story 的 Completion Notes 明确标「待本地验收」并写清需要什么（哪类凭证 / 需肉眼确认哪个界面）。
3. 提交到分支 <EPIC_N>-batch（从 main 切），每个 story 一个 commit；更新 sprint-status 该 story 为 review。

停止铁律（命中就停下、写清卡点，禁止硬闯）：
- 下一个 ready story 已不属于 <EPIC_N>（即本 Epic 做完）→ 停。
- 需真实凭证的 story（如 1.3 Google OAuth / 2.1 阿里 OSS / 4.1 Gemini / 5.x 腾讯 IM）：实现代码 + 用 mock/stub 把 L0 跑绿即可，禁止伪造凭证或跳过，Completion Notes 标清 L2 待本地。
- 安全攸关 story（4.2 安全规则层 / 4.5 红色强提醒 / 7.3 注销级联删除）→ 立即停，交人工，不在无监管下做。
- 任一 story 的 L0 连续修 3 次仍不过 → 停。
- Flyway 迁移序号按执行顺序单调分配（决策 E2），勿照搬 architecture 示例号。

全 Epic 完成后：推 <EPIC_N>-batch 开一个 PR，标题「<EPIC_N> 批量实现（L0 绿）」，PR 描述逐 story 列「L0 状态 + 待本地验收项」。然后停下，不要继续下一个 Epic。
```

**Epic 末你要做的**：拉分支本地补 L1/L2（起 Docker 验集成、填凭证验登录/AI/IM、`flutter run` 验视觉），通过后合并；安全节点（4.2/4.5/7.3）本地实现或重点 review。合并后再发下一个 Epic 的批量命令。

**留本地、别过夜批跑的安全攸关节点**（AC 含「只升不降/不可绕过/级联删除」硬约束）：
- `4-2` 确定性安全规则层（高危强制升红）
- `4-5` 红色半屏强提醒
- `7-3` 退出登录与账号注销级联删除

## Stage F — 盯进度 + 回收

- **进度**：终端 `/tasks`（按 `t` teleport 进某 session）；手机 Claude App → Code tab；网页侧边栏 diff review。
- **视觉验收**：需要看 Flutter UI 的 story，teleport 回本地或拉分支本地 `flutter run`。
- **集成验收**：L1 在本地起 `docker compose up` + `mvn spring-boot:run` 验 `/actuator/health=UP`。
- **合并**：云端只到 L0；**本地补 L1/L2 验收通过后再合主干**，别让无监管流程把安全节点降级。

---

## 常见坑速查

| 现象 | 原因 / 处置 |
|---|---|
| 云端 session 看不到 CLAUDE.md / hook | 没 commit。云端只读仓库里 commit 的东西。 |
| hook 没跑 `flutter pub get` | 脚手架（1.1）还没 push，或云端 setup 没装成 flutter → hook 设计为静默跳过。 |
| setup script 首跑下载失败 | 域名白名单：网络切 Full / 加 Custom 域名。后端用云端自带 openjdk-21（无需下 JDK），避开了证书/下载坑。 |
| Flyway 迁移号冲突 | 按执行顺序单调分配，勿照搬 architecture 示例号（决策 E2）。 |
| 云端要看模拟器 UI | 做不到（headless）。teleport 回本地。 |
