---
baseline_commit: 4301227a
branch: feat/1.3.0-ops-ui-refactor
epic: 11
story: 11.1
ad: [AD-12]
decisions: [D-8, D-23, D-27]
depends_on: [2-1, 全部 Epic 1~10]
---

# Story 11.1: 写操作清单 diff 与端点零丢失核对

Status: review

> 自包含 story，纯 L0（脚本 + 文档），可云端执行。与用户沟通用中文。
> 这是 AB-19A「不动任何功能、逐操作核对不丢一项」的机器证据；前置是 Story 2.1 已产出基线清单与脚本 `scripts/ci/list-admin-write-ops.sh`。

## Story

As a QA，
I want 用 Story 2.1 同一个脚本对重构后代码再生成一份清单并与基线 diff，
so that 128 个写操作一个不丢有机器证据，而不是靰人肉对表。

## Acceptance Criteria

**AC1 · 重跑脚本**
**Given** `dev_1.3.0`（或本分支）重构后代码，Epic 1～10 全部合入
**When** 运行 `bash scripts/ci/list-admin-write-ops.sh > implementation-artifacts/v1.3.0/后台写操作清单-<yyyyMMdd>-after.md`
**Then** 输出格式与基线（Story 2.1 产出的 `后台写操作清单-<yyyyMMdd>.md`）逐列一致：路径 / HTTP 方法 / Controller#method / `@PreAuthorize` 表达式 / 所属页面（`AdminPageCatalog`）`[L0]`
**And** 同批输出 GET 页面路由列表（去参数路由 / 导出 / drawer / 片段）`[L0]`

**AC2 · diff 判定规则（写端点）**
**When** `diff` 基线与 after
**Then** **允许新增**的写端点仅限本版 PRD 定义：`POST /admin/accounts/{id}/rename`、`…/rebind-email`（AB-16A）；`/admin/roles/**` 写端点（AB-21A）；`POST /admin/places/**`（AB-17A）；`POST /admin/comments/virtual`、`/admin/warm-replies/**` 写端点（AB-20A）；`POST /admin/config/tiers`（AB-22A）；`POST /admin/dashboard/materialize`（@StagOnly，AD-3）；`POST /admin/tickets/warn` 若因加 `reason` 参数导致方法签名变化，路径不变不算新增 `[L0]`
**And** **允许删除**的写端点仅限 Story 7.6 注明的旧批量入口 `POST /admin/seed-batch`、`POST /admin/seed-batch/import`（且必须在 7.6 Completion Notes 有「仅服务旧入口」的核实记录）`[L0]`
**And** 其余基线 128 条写端点：路径、方法、`@PreAuthorize` 表达式**逐字一致**；任何一条差异 = 本 story 失败，回对应页面 story 修 `[L0]`

**AC3 · diff 判定规则（GET 路由）**
**Then** **允许删除**的 GET 路由仅限退役清单（见 Story 11.3 精确列表：8 个独立详情页 + `reports` + `content-schedules` + `vets/online` + `ratings` + `vets/{id}/ratings` + `vets/{id}/edit` + `vets/{id}/qualification` + `tickets/detail`）`[L0]`
**And** **允许新增**的 GET 仅限：`/**/{id}/drawer` 抽屉片段、`/admin/nav/badges`、`/admin/charts`、`/admin/places*`、`/admin/roles*`、`/admin/warm-replies*`、`/admin/comments/distribution*`、`/admin/config/tiers/disabled`、`/admin/algo-params/changes/drawer` `[L0]`

**AC4 · 产出核对报告**
**Then** 写 `implementation-artifacts/v1.3.0/后台写操作清单-核对-<yyyyMMdd>.md`：三段（写端点 diff / GET diff / 结论），每条新增 / 删除标注依据 story 号；结论行「128/128 基线写端点一致 ✅」或列出偏差 `[L0]`
**And** 脚本加 `--baseline <file>` 参数可直接输出上述报告骨架（可选，若 2.1 未做则本 story 补）`[L0]`

**AC5 · CI 钉住**
**Then** `.github/workflows` 加一步：在 `dev_1.3.0` 与 `feat/1.3.0-*` 分支 PR 上运行脚本并与 `后台写操作清单-<基线日期>.md` diff，非白名单差异即红 `[L0]`

---

## Tasks / Subtasks

- [x] **T1 · 重跑与对齐格式**（AC1）
  - [x] 确认脚本扫描范围 = `petgo-backend/src/main/java/com/tailtopia/admin/**` + `namemoderation/web`（`NameModerationAdminController` 在 admin 包外但挂 `/admin/**`）+ 任何 `@RequestMapping("/admin")` 前缀类；基线若漏了包外 Controller，先补脚本再重跑基线（写明）
  - [x] 输出按路径排序，稳定可 diff

- [x] **T2 · 白名单文件**（AC2 / AC3）
  - [x] 新建 `scripts/ci/admin-write-ops-allowlist.txt`：两段 `+`（允许新增）`-`（允许删除），每行带 story 号注释
  - [x] 脚本 `--baseline` 模式：读白名单，输出「未解释的差异」

- [x] **T3 · 核对报告**（AC4）
  - [x] 按模板写报告；把 Epic 7.6 的旧端点删除依据链接进来

- [x] **T4 · CI**（AC5）
  - [x] 参考既有 `flyway-guard`（`scripts/ci/check-flyway-versions.sh`）的接法加 job；失败信息打印未解释差异

- [x] **T5 · 云端执行须知**：全 L0，云端可跑完

---

## Dev Notes

### 基线口径

- 2026-09-09 核实：`admin/**` 内 `@Post|Put|DeleteMapping` **128** 条，`@GetMapping` **79** 条（含参数路由 / 导出 / 片段）。这两个数字是 PRD §5 ③ 与 UX 对齐 #1 的依据。
- 「页面数」以 GET 路由列表为分母（约 45 个页面级路由），**不是** UI 稿 49 帧（就绪度报告 UX 对齐 #1，D-8 措辞已改）。

### 🔴 三类容易误判为「丢了」的变化

1. **PRG → htmx 分支**：同一个 POST 方法内加 `HxRequest` 判断返 fragment，路径不变，**不是**变更。
2. **`warn` 加 `reason` 参数**：路径不变，方法签名变，**不是**变更；但 `@PreAuthorize` 必须不变。
3. **Controller 搬家**：如 `AdminRatingController.overview` 删除但排序逻辑并入 `vets`（9.1b），GET 路由 `/admin/ratings` 在退役白名单内，写端点无涉。

### 🔴 唯一主动删写端点的地方

Story 7.6 种子发布页删旧批量入口时，`POST /admin/seed-batch` / `/seed-batch/import` 若仅服务该入口则一并删。本 story 的允许删除白名单**只有这两条**；其他任何写端点消失都算事故。

### 与 Story 2.1 的关系

2.1 产脚本 + 基线；本 story 复用脚本产 after + diff + 报告 + CI。脚本若需改（补包外 Controller），先在 11.1 改并**重跑基线**、注明「基线 v2」，不要用 v1 基线对 v2 脚本输出。

### References

- [Source: epics-v1.3.0-admin.md#Story 11.1 / Story 2.1 / Story 7.6] · [Source: architecture-v1.3.0-admin-delta.md#Enforcement / AD-12] · [Source: 决策日志-admin.md D-8 D-23 D-27] · [Source: implementation-readiness-report-2026-09-09-v1.3.0-admin.md#UX 对齐 #1]
- [Source: scripts/ci/check-flyway-versions.sh（CI 接法范式）]

## Dev Agent Record

### Agent Model Used

Claude Code（云端 headless session，纯 L0：脚本 + 文档，无 Java 改动）

### Debug Log References

- 生成 after：`bash scripts/ci/list-admin-write-ops.sh --out …-20260910-after.md` → 写端点 **150**、GET 映射 **117**、页面路由 ≈ 65；逐文件自检「全树解析 452 行 ≥ 映射注解 426 条，无漏抓」；写端点缺 `@PreAuthorize` **0 条**。
- diff：`… --baseline …-20260909-基线v2.md` → **`write-ops-guard: OK（与基线零未解释差异）`，exit 0**。
- 负向自检（三项，全部实跑）：① 从白名单删掉 `- GET /admin/reports` → **exit 1**；② 在 **admin 包外**新建 `com/tailtopia/zztmp/web/ZzTmpAdminController` 加 `POST /admin/users/{userId}/nuke` → 报「未解释的新增端点」、**exit 1**；③ 把某处置端点的授权由 `hasRole('SUPER_ADMIN') || hasAuthority('content.takedown')` 放宽成 `|| hasAuthority('ANYTHING_GOES')` → 报「@PreAuthorize 漂移」、**exit 1**。（②③ 在修复前都是**全绿**。临时文件已全部还原。）
- 幂等自检：同一提交连跑两次输出**逐字节一致**；入库的 after 与重跑结果一致。
- 未跑 `mvn`：本 story 不动 Java / 模板 / i18n。

### Completion Notes

**结论：133/133 留存写端点的路径 / 方法 / `@PreAuthorize` 逐字一致；`@PreAuthorize` 漂移 0 处；未解释的新增 / 删除 0 处。** 报告见 `后台写操作清单-核对-20260910.md`。

**🔴 这个 story 的产物是「验收证据」本身，所以最该怀疑的是「守门看起来绿、其实抓不到东西」。实际抓出 5 处 —— 前两处让基线数字都不对，中间两处让守门可以被静默绕过：**

1. **生成器漏扫 admin 包外的 `/admin` Controller**：`namemoderation/web/NameModerationAdminController` 的 `POST /admin/name-moderation/{recordId}/decide`（统一复核工作台以 htmx 调它）。漏得很安静 —— 行数自检 grep 的是同一个目录，少一行两边同时少，自检恒等。
2. **常量拼接路径只抓到后半截**：`@PostMapping(ROUTE + "/{id:\\d+}/edit")`（`AdminPlaceController` 8 条 + `AdminWarmReplyController` 2 条）解析出 `/{id:\d+}/edit`，不以 `/admin` 开头 → **整条端点被丢掉，一次少 10 行**。修法是路径也走 `@PreAuthorize` 那套常量展开，并把 Java 字面量的 `\\d+` 还原成 `\d+`。

3. **退役标记按路径匹配、不看方法**：`POST /admin/vets/{id}/qualification`（11.3 AC1「勿误删」名单里的端点）在底账里带着「⛔ 本版退役」备注 —— 自相矛盾的口径。改为**退役标记只贴 GET**；另把 `GET /admin/vets/{id}/qualification`、`GET .../ratings` 的备注从 ⛔ 改成 🔄「整页退役、路径保留（改为抽屉页签片段）」——写成 ⛔ 会让 11.3 照着去删，删了抽屉页签就空了。（不影响行数，影响可读性。）

4. 🔴 **扫描根按包名写死 → admin 包外新增的 `/admin` 端点完全隐形，CI 恒绿**。第 1 条的修法只是把一个目录加进手写名单，没有把「按路径判定而不是按目录判定」做掉。改为**扫全树、按 `^/admin` 过滤**；自检也从「全局比总数」改成**逐文件**「解析行数 ≥ 映射注解条数」—— 全局比总数时，「这里多两行、那里漏两行」会互相抵消，漏抓照样看不见。
5. 🔴 **`@PreAuthorize` 里出现 `|` 时，权限漂移检测被静默绕过**。生成表格时把 `|` 转义成 `\|` 保护 markdown，但 `--baseline` 回读按 `|` 切列，`\|` 照样当分隔符 → 权限表达式在第一个 `|` 处被**截断**，后半截永不参与比对。SpEL 的 `||` 与 `or` 等价，随时会有人写；而权限漂移恰恰是 AC2 里唯一不受白名单豁免的硬约束。改为写表时转 `&#124;`、回读时还原。

顺带修的三处（都来自复审）：`@PatchMapping` 原来完全不在口径内（团队在 `/api/v1` 侧已在用，admin 侧迟早会有）；**写端点缺 `@PreAuthorize` 现在直接报错**（新增端点没有基线可比，漂移检测看不到它们）；`@PreAuthorize` 的**跨类常量**（`OtherController.MANAGE_AUTH`）现在也展开成 SpEL 原文 —— 之前那一行留的是 Java 表达式，「原文逐字一致」对它并不成立，且只被跨类引用的常量改了值不会产生任何 diff。顺手删掉 `retired_note` 里重复且不可达的 `/admin/vets/online` 分支。

修完脚本后**用 v2 脚本重跑基线提交 `7a160d0`**产出基线 v2 并入库（v1 保留留痕、不再用于 diff）：写端点 134 → **135**。清单里所有权限列现在都是 SpEL 原文，**零行残留 Java 常量引用**。

**口径澄清**：story / PRD 里的「128 条」是人肉数，与生成器口径从来不等（多路径注解、包外 Controller、stag-only 端点）。本 story 一律以生成器为准：基线 v2 **135** → after **150**；`135 + 17 − 2 = 150` ✅，GET `84 + 50 − 17 = 117` ✅。

**新增/删除全部有依据**（逐条带 story 号写进 `scripts/ci/admin-write-ops-allowlist.txt`）：

- 允许新增写端点 **17**（places 8 · warm-replies 2 · comments/virtual · config/tiers · config/ktp-pricing · 🧪dashboard/materialize · 🧪payments simulate ×3）。其中 **🧪 4 条是 `@StagOnly`，生产不注册 bean、路由不存在** → **生产实际新增 13 条**。
- 允许删除写端点 **2**（`POST /admin/seed-batch`、`/seed-batch/import`，Story 7.6 唯一允许项）。**其余写端点零删除。**
- 允许新增 GET **50**（抽屉片段 27 · 模板 A `/queue`+`/{id}/detail` 12 · 本版新页面/片段 11）。
- 允许删除 GET **17** = **在 11.3 AC1 退役表里的 13 条** + **4 条不在表里、逐条记账的**：`GET /admin/seed-batch` 与 `/seed-batch/template`（7.6 旧批量入口，随那两条 POST 一并退役）、`GET /admin/publish-identities/{userId}/remove` 与 `GET /admin/virtual-accounts/{userId}/disable`（8.3 把确认页改成确认弹层片段，路径加 `/confirm` 后缀，**对应的 POST 一个没动**，属改名不属丢失）。
- **反方向补齐**：11.3 AC1 表里有 4 条至今仍在服役（`/admin/shop/orders/{token}`、`/admin/shop/returns/{token}` 因 Epic 10 未执行；`/admin/vets/{id}/qualification`、`/admin/vets/{id}/ratings` 因 9.1b 改成抽屉页签片段、路径保留）。它们**已作为预授权写进白名单**（当前不命中）—— 11.3 Dev Notes 要求「11.1 的 GET 白名单与 11.3 AC1 表一致」，漏了的话，将来一次**有授权的删除**会被 guard 报成事故、PR 无端变红。
- ⚠️ **一条 AC 偏差待拍板**：`POST /admin/config/ktp-pricing`（Story 6.1 / AB-18A）**不在 AC2 的「允许新增仅限」枚举里，也不在 9-9 拍板回写的补充名单里**。端点真实、依据充分，但按 AC2 字面口径它是未授权的新增，已在报告 §1.2 单独记账，**请补一句授权**。

**守门口径（AC2 的硬边界写进脚本）**：比对键 = **方法 + 路径 + `@PreAuthorize` 原文**；`Controller#方法` **不进键**（Controller 搬家是本版预期变化）；`@PreAuthorize` 漂移**不受白名单豁免** —— 端点在不在白名单里都一样红。参数级变化（`warn`/`reject` +reason、`payout` +proof）清单本来就不列参数，不产生 diff，人工登记在核对报告 §1.4。

**观察项（不阻断本 story，转 Story 11.4 权限矩阵收口）**：新增的 `GET /admin/charts`、`GET /admin/nav/badges` 没有 `@PreAuthorize`，只靠 `/admin/**` 链级 `ROLE_ADMIN`（与基线里的 `/admin`、`/admin/dashboard` 同姿态，不算漂移）—— 11.4 应显式确认这是有意为之。另：11.3 AC1 的退役清单里有 `GET /admin/vets/{id}/qualification` 与 `GET .../ratings`，但 9.1b 实际是把它们改成抽屉页签片段、**路径保留**；按「未删」记账，**11.3 不要去删**。

**✅ 覆盖边界已闭合（2026-09-11 补跑）**：上一轮写的是「**Epic 10（商城组）本轮未执行**（前置是 v1.4.0 电商线合入，AD-12）。`/admin/shop/**` 因此没被重构，本次 diff 中 shop 组零差异 —— 那是「没动过」，**不是「已核对通过」**。Epic 10 落地后必须重跑本流程、刷新白名单与核对报告。」

Epic 10 六条 story 已于 2026-09-11 全部落地（`aafefd12` → `71049c85`），**本流程已重跑**：

- `--out …-20260911-after.md` → 写端点 **150**、GET **117** —— 与 Epic 10 之前的 `…-20260910-after.md` **完全相同**。
- 两份 after **逐字节 diff 唯一差异是 `baseline_commit` 一行**（`954b2ae9` → `71049c85`）。
- `--baseline …-基线v2.md` → 仍是 `write-ops-guard: OK（与基线零未解释差异）`，**白名单一条都不用改**。

这不是巧合：Epic 10 六条 story 各自受「零后端功能改动、零新端点」约束，需要的抽屉 / 卡区取数**一律复用既有 mapping 的 `HX-Request` 分支**（AD-9 的 `GET …/{id}/drawer` 惯例让位于零新端点，D-43），所以路由表纹丝不动。逐条对照见 `后台写操作清单-核对-20260911-epic10闭合.md`。

**顺带闭合一条口径差**：11.3 AC1 的退役表把 `GET /admin/shop/orders/{token}`、`/admin/shop/returns/{token}` 列为「删除」，而 10.1 / 10.2 的实际处置是**保留 mapping**（htmx 下返片段，非 htmx 抛 404，code `admin.err.common.pageRetired`）—— 那条路径同时是抽屉的取数入口，删了抽屉就是空的。**这两条不要去删**；白名单里的预授权项保留。同形态的还有 9.1b 的 `GET /admin/vets/{id}/qualification` 与 `.../ratings`。四条合起来一句话：**退役表里有 4 条是「整页退役、路径保留」，不是「路由删除」。**

**⏳ CI（AC5）文件已写好但「待安装」**：本 session 的 OAuth 令牌**没有 `workflow` scope**，GitHub 直接拒绝推送任何 `.github/workflows/*`（`refusing to allow an OAuth App to create or update workflow ... without workflow scope`）。这是凭证权限问题，不是文件问题 —— 与其把 CI 条款降级成「本地跑跑」，不如按最终形态写好、放在 `scripts/ci/github-workflows/`，等本地一次 `git mv` 落位（步骤见该目录 README）。**AC5 在文件移进 `.github/workflows/` 之前不算完成。**

**CI 内容**：`write-ops-guard.yml` 的 `paths` **不能只监听 admin 包**（同 C1：包外新增的 `/admin` Controller 会让 job 根本不起），已放宽到整个 java 源码树；基线文件的日期戳路径原来硬编码在 `push.paths` / `pull_request.paths` / `env` **三处**，将来换基线只要漏改 paths 里的两份，触发条件就**静默失效（不报错，只是不跑）** —— 现在 paths 只写目录、真值只在 `env.BASELINE` 一处。

**L1 / L2 待本地验收**：无。本 story 全 L0，云端已跑完。唯一留待验证的是 **AC5 的 CI job 在真实 GitHub runner 上首跑绿灯**（本地已用同一条命令验证 exit 0/1 两态，YAML 已解析校验）。

### File List

**新增**

- `scripts/ci/admin-write-ops-allowlist.txt`（90 行白名单，每行带 story 号；含 4 条「已授权未删」的预授权项）
- `scripts/ci/github-workflows/write-ops-guard.yml`（AC5；⏳ **待本地 `git mv` 进 `.github/workflows/`**，见同目录 README）
- `scripts/ci/github-workflows/README.md`（为什么在这儿 + 一次性安装步骤）
- `_bmad-output/implementation-artifacts/v1.3.0/后台写操作清单-20260909-基线v2.md`（同一基线提交 `7a160d0`，v2 脚本重跑）
- `_bmad-output/implementation-artifacts/v1.3.0/后台写操作清单-20260910-after.md`（`4e15b5fe`）
- `_bmad-output/implementation-artifacts/v1.3.0/后台写操作清单-核对-20260910.md`（AC4 三段报告）

**改**

- `scripts/ci/list-admin-write-ops.sh`（全树扫 + 按路径过滤 · 逐文件漏抓自检 · 路径常量展开 · 跨类 `@PreAuthorize` 常量展开 · `|` 转 HTML 实体 · `@PatchMapping` · 写端点缺 `@PreAuthorize` 报错 · 退役标记只贴 GET · `script_version` header · `--baseline` / `--allowlist` diff 模式 · `/admin/charts`、`/admin/name-moderation` 归组）

**迁移**：无。

## 拍板回写（2026-09-09）

- **D-36 / D-41 白名单追加**：允许的写端点变更 = `warn`(+reason) · `refund-reject`(+reason) · `refunds/{token}/reject`(+reason) · `payout`(+proof) · B12 三个 stag-only 模拟回调端点（仅 stag profile 注册，生产 diff 中不出现）。
