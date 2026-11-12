本线专属规则（analytics-pipeline）：
- 提交信息前缀用 `feat(analytics-pipeline): <story key>`。
- **迁移版本号以 story 文件里预分配的为准**（埋点库 `V20261113_1000`–`1430`、业务库 `V20261113_1125`），不要改成创建时刻；story 标注「作废」的迁移不要建。埋点库迁移放 `petgo-backend/src/main/resources/db/analytics-migration/`。提交前仍跑 `bash scripts/ci/check-flyway-versions.sh origin/main`（1.1 之后它会同时检查两个目录）。
- story 文件末尾的「Dai 拍板修订」「汇总修订」「跨 story 对齐」小节**优先于**正文；与 epics / 架构冲突时以 `_bmad-output/planning-artifacts/analytics-pipeline/architecture.md` 为准。
- 前置 story 已实现的类名、表名、列名、占位符（如 `${readerRole}`、`AnalyticsPipelineState`）以代码为准，后面的 story 跟着代码走，不改前置 story 的实现。
- App 侧不要整文件 `dart format`（存量未格式化），只跑 `flutter analyze` 和 `flutter test`；不加新依赖。
- 后端 L0 用 `./mvnw -B clean package`；需要真实 postgres 的测试按项目惯例标记，云端不跑。
- Epic 4 属批次 ②，照常顺序做完，不跳过。
