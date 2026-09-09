# V1.3.0 实现产物

> 集成分支 `dev_1.3.0`；story 由各功能分支 `feat/1.3.0-<主题>` 产出后合回本目录。
> 2026-09-09：admin 主题（`feat/1.3.0-ops-ui-refactor`）的 `sprint-status-v1.3.0.yaml` 已生成（11 Epic / 59 Story，全部 backlog）；59 条 story 文件已生成（2026-09-09，含 1-1 手写 + 58 条并行生成），全部 ready-for-dev；执行顺序见 sprint-status 头部注释。

- `sprint-status-v1.3.0.yaml` —— 全版本一份，`story_location` 指向本目录；各主题的 Epic 号段见 `../../planning-artifacts/v1.3.0/README.md` 登记表。
- `<epic>-<n>-<中文名>.md` —— story 文件。
- 主题内独立的评审/验收记录直接放本目录，文件名带主题，如 `L2-视觉验收报告-ops-ui.md`。

## 云端全量执行指令（2026-09-09 拍板：一口气跑完、不设检查点、本地统一验收）

> 复制下面整段作为云端 session 的首条提示词。前提：session 已 clone 并 checkout `feat/1.3.0-ops-ui-refactor`。

```
V1.3.0 后台线，分支 feat/1.3.0-ops-ui-refactor。先读 _bmad-output/project-context.md 与 _bmad-output/planning-artifacts/v1.3.0/README.md。

循环执行，直到 sprint-status-v1.3.0.yaml 里没有 ready-for-dev 的 story：
1. 按 sprint-status-v1.3.0.yaml 从上到下取第一条 ready-for-dev 的 story（Epic 10 的 6 条全部跳过，保持 ready-for-dev 不动——它们要等电商线合入后再做）。
2. 对该 story 跑 bmad-dev-story：只到 L0（mvn -B clean package；涉及前端资源的页面也只做模板与静态文件，不起容器）。L0 不绿不许进下一步。
3. 跑 bmad-code-review 复审本 story 的改动，CONFIRMED 的修掉。
4. 提交（一 story 一 commit，message 前缀 `feat(v1.3.0): <story key>`），推远端 feat/1.3.0-ops-ui-refactor。
5. sprint-status 把该 story 置 review；Completion Notes 必须写「L1/L2 待本地验收」。任何 story 不得标 done。
6. 取下一条。

硬规则：
- 不设人工检查点，中途不问我，除非遇到「无法解析版本」「迁移号撞车」「上一条 story 的改动导致本条无法编译」三类才停下报告。
- Flyway 迁移一律时间戳版本号 V<yyyyMMdd_HHmm>__<snake>.sql，取创建时刻；提交前跑 bash scripts/ci/check-flyway-versions.sh origin/main。
- 三语 message key 三包同批；Story 2.2 建好 scripts/ci/check-i18n-keys.sh 后每条 story 都跑它。
- 不部署任何环境、不 push 其它分支、不改 dev_1.3.0 / main。
- 严格按 story 文件的 Dev Notes「必须保留」列施工；架构疑问以 architecture-v1.3.0-delta.md 为准，决策以 决策日志.md 为准，不自行拍板。
- 上下文被压缩后继续按 sprint-status 当前状态接着跑，不重做已 review 的 story。

全部跑完后输出：完成的 story 列表、跳过的 Epic 10 列表、每条 Completion Notes 里标注的待本地验收项汇总、迁移文件清单。
```

**本地验收顺序（全量跑完后一次做）**
1. 拉代码 → flush Redis DB0 → 重建 scratch 库 → `mvn -B clean package` → 启动，先把迁移全部跑通（预期第一次会挂在某支迁移，逐支修）。
2. L1 全量集成测试。
3. `./scripts/deploy-backend-stag.sh` → 按 UI 稿 8 泳道逐页比对，填 `L2-视觉验收报告-v1.3.0.md`（Story 11-5 骨架）。
4. 看板 3 日 × 18 项数值核对；角标同源；切 ID 语言全站无截断。
5. 验收过的 story 由本地置 done。
