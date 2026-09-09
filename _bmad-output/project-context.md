# 项目上下文（所有 BMAD 技能启动时自动加载）

## 版本解析规则（强制，2026-09-09 起）

本仓库的规划与实现产物**按版本目录**存放，不在根目录：
- 规划层：`_bmad-output/planning-artifacts/<ver>/`（PRD、架构 delta、epics、决策日志）
- 实现层：`_bmad-output/implementation-artifacts/<ver>/`（story 文件 + `sprint-status-<ver>.yaml`）
- `<ver>` 形如 `v1.3.0`、`v1.4.0`、`v1.1.6`；总入口 `_bmad-output/README.md`

任何技能（create-story / dev-story / code-review / sprint-planning / sprint-status / check-implementation-readiness 等）在定位文件前，**必须先解析版本**，顺序：
1. **用户提示词**里出现的版本号（`V1.3.0` / `1.3.0` / `v1.3.0` 均可）→ `<ver>=v1.3.0`
2. 提示词没有 → **当前 git 分支名**：`feat/1.3.0-*` / `dev_1.3.0` / `dev/dev_1.1.6` / `stag`（stag 分支不带版本，视为未解析）
3. 都解析不出 → **停下来问用户要版本号**。**禁止**回退到 `_bmad-output/planning-artifacts/` 或 `_bmad-output/implementation-artifacts/` 根目录默认路径，禁止猜「最新的那个」。

解析出 `<ver>` 后，把技能内的路径变量**重定向**：
- `{planning_artifacts}` → `_bmad-output/planning-artifacts/<ver>`
- `{implementation_artifacts}` → `_bmad-output/implementation-artifacts/<ver>`
- `sprint_status` → `_bmad-output/implementation-artifacts/<ver>/sprint-status-<ver>.yaml`（文件名带版本后缀，不是 `sprint-status.yaml`）
- `epics_file` → `…/planning-artifacts/<ver>/epics-<ver>.md`；`architecture_file` → `…/architecture-<ver>-delta.md`；`prd_file` → `…/PRD-<ver>*.md`（后台线为 `PRD-<ver>-admin.md`）
- 跨版本常设文件不重定向：`_bmad-output/planning-artifacts/architecture.md`（基线架构）、`_bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md`

同一版本可能有多个主题（如 v1.3.0 的后台 admin 与 App 端），主题分支 `feat/<ver>-<主题>`；同一版本目录内 story 编号由 `planning-artifacts/<ver>/README.md` 的主题登记表分号段，不会重复；**不同版本目录之间编号会重复**（v1.0.0 与 v1.1.0 都有 1-1），引用 story 必须带版本目录。

## 云端（headless）执行提醒

- 只跑到 L0（`mvn -B clean package` / `flutter analyze` / `flutter test`）；L1（Docker postgres+redis）与 L2（页面视觉、真机）留本地，Completion Notes 必须写「L1/L2 待本地验收」，story 不得标 done。
- 部署脚本（`scripts/deploy-backend*.sh`）需 SSH，云端不执行。
- 同一分支同一时间只跑一个 story；Flyway 迁移取创建时刻的时间戳版本号，提交前跑 `bash scripts/ci/check-flyway-versions.sh origin/main`。
- 其余纪律见根 `CLAUDE.md`。
