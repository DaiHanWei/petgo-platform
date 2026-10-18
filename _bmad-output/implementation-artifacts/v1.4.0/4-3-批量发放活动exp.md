# Story 4.3: 批量发放活动 EXP

Status: ready-for-dev

> 版本 v1.4.0 · 主题 tailrent-level · 分支 `feat/1.4.0-tailrent-level`
> 口径优先级：`decision-log.md` ① 节 > `Tailrent等级/1-4-0Tailrent等级prd.md`（§3.4 AB-33「运营活动发放 · 批量」）> `architecture-v1.4.0-tailrent-level-delta.md`（AD-2 / AD-5 / AD-6 / AD-17、§5.2、§5.4）> `Tailrent等级/ui-admin-1-4-0-tailrent-level-integrated-v1.html`（H1 ~ H5）（均在 `_bmad-output/planning-artifacts/v1.4.0/`）。
> 前置：**Story 4.1**（`/admin/growth` 页、`AdminConfirmModal.facts`、`admin.v140.growth.*` 基础文案）、**Story 4.2**（`GROWTH_USER_ADJUST`、`GrowthLedgerService.adjust` + `AdminAdjustCommand`、`GrowthIdemKeys.admin`、`OPS_CAMPAIGN` 停用拒绝口径），以及其依赖的 Story 1.1 / 1.2 / 2.1（`growth_ledger.batch_no` 列、`GrowthLevelChangeHandler` 连锁）。
> 范围：**后端 + 后台模板**。无迁移（`growth_ledger.batch_no` 由 Story 1.1 的 `V20261018_1500` 建）。App 零改动。

## Story

As a 运营,
I want 一次给一批用户发放同一个活动的 EXP,
so that 能做站内活动奖励而不用逐个调整。

## Acceptance Criteria

**AC1 — 入口与权限** `[L0]` `[L1]`（PRD §3.4「批量」/ AD-17 / UI G1 / G7）
1. 「成长体系」页标题行右侧「批量发放」按钮（`btn btn-sm`），`sec:authorize="hasRole('SUPER_ADMIN') or hasAuthority('growth.user_adjust')"`——**无权限直接不渲染**（PRD 明写「只对有权限的账号显示」，是 bug 20260901-475「置灰不隐藏」惯例的唯一例外，UI G7）。
2. 点击 = `data-drawer-open` 打开 600 宽抽屉 `GET /admin/growth/batch/drawer`（门 `GROWTH_ADJUST_AUTH`）；抽屉壳 `tpl-shared :: drawer('growthbatch', …)`，`admin.css` 加一行 `#growthbatch-drawer.drawer { width: 600px; }`（同用户抽屉 :939，只放宽这一个）。
3. **入口门扩展**（见 Dev Notes「OPS_MANAGER 进不了成长体系页」）：`/admin/growth` 的页面门与 `AdminPageCatalog` 的 `growth` 页 `navCodes` 同时加上 `GROWTH_USER_ADJUST`（逐字同集）。只持 `GROWTH_USER_ADJUST` 的账号进页后：三个页签按 Story 4.1 AC8 的只读视图渲染（输入禁用 + 卡底只读提示），能看到并使用「批量发放」。
4. 所有批量端点门 = `GROWTH_ADJUST_AUTH`（`hasRole('SUPER_ADMIN') or hasAuthority('growth.user_adjust')`，与 Story 4.2 同一常量）；无码 → 403。

**AC2 — ① 填写（H1）** `[L0]` `[L1]`
1. 抽屉顶部三步条「1 · 填写 / 2 · 预览 / 3 · 发放」，样式沿用 `.step-bar` / `.step--on` / `.step--done`（`batch-steps.html:10-20` 的类名；步骤不可点回，只靠按钮）。
2. 字段：活动名称 *（≤ 200 字，提示「写进每条流水，用户在 EXP 明细里看到。」）；每人 EXP *（正整数）；用户 ID *（textarea，提示「逗号、空格或换行分隔；或上传 CSV（一列 user_id）。」）；「下载模板」（`GET /admin/growth/batch/template.csv`，内容 = 表头 `user_id` + 一行示例）；「选择文件」（`accept=".csv,text/csv"`）。粘贴与文件**可同时提供，取并集**。
3. 「取消」关抽屉；「预览」= `POST /admin/growth/batch/preview`（`hx-encoding="multipart/form-data"`）。
4. 校验（422 留在第 ① 步，字段下红字）：活动名为空 / 超长；EXP 非正整数；一个 ID 都没有；去重后 ID 数超过上限（建议 **5,000**，常量 `MAX_IDS`，见 Dev Notes）；CSV 超过 1 MB；`OPS_CAMPAIGN` 规则已停用（Story 4.1 规则页可停用）→ `admin.err.growth.campaignDisabled`。

**AC3 — ② 预览（H2）：校验不过的行不阻塞整批** `[L0]` `[L1]`（PRD「预览（有效用户数、无效 ID、虚拟号自动排除）」「沿用种子批量的预览与『校验未过的行不阻塞整批』惯例」/ AD-17）
1. 解析：按 `[,，;\s]+` 切分（逗号 / 中文逗号 / 分号 / 空白 / 换行），去掉首尾引号与 UTF-8 BOM；CSV 只取每行第一列，首行若是表头（`user_id` / `uid` / `id`，不分大小写）忽略；**重复 ID 只算一次、不算无效**；非数字 token 计入无效（原因「格式不正确」）。
2. 归类（**一次批量查询**，口径与 Story 4.2 单个调整的参与判定一致——由 growth 模块提供同一个判定方法，不在后台另写一套）：
   - 有效：`account_type = REAL`、未注销、`status = ACTIVE`；
   - 无效：不存在 / 已注销 / 已停用 / 格式不正确 / 不参与成长值（`role ≠ USER`，见 Dev Notes「归类口径」）；
   - 虚拟号（`account_type = VIRTUAL`）：单列「虚拟号（已排除）」。
3. 展示（H2）：汇总三格「有效用户 / 无效 ID / 虚拟号（已排除）」（`.sum`）；活动名称、每人 EXP、**合计 = 有效人数 × 每人 EXP**；问题清单表「用户 ID / 结果」**只列有问题的**（无效 + 虚拟号），超过 200 行只显示前 200 行并注明「另有 N 条未显示」；底部说明「无效 ID 与虚拟号不会发放，不阻塞整批。已注销 / 已停用账号同样计入无效。」
4. **批次号在预览时生成**：`GB-{yyyyMMddHHmm(WIB)}-{6 位大写字母数字随机}`（AD-17 格式；`SecureRandom`；总长 ≤ 32 = `growth_ledger.batch_no` 列宽）。UI 稿示例 `GB-20261013-0001` 与 AD-17 不同，**以 AD-17 为准**。
5. 有效用户数为 0 →「确认发放」`disabled`。「← 上一步」= 回到第 ① 步且保留已填值（`POST /admin/growth/batch/form`，用隐藏字段回填；不保留已选文件，改为把解析出的 ID 回填进 textarea）。

**AC4 — ③ 二次确认弹窗（H3）** `[L0]` `[L1]`（PRD「确认用服务端渲染弹窗，显示人数 × 每人 EXP、合计与批次号」/ OQ-9）
1. 「确认发放」= `POST /admin/growth/batch/confirm`：服务端**按隐藏字段重新解析、重新归类**（不信任预览结果），回 `fragments/confirm-modal :: modal` 到 `#confirm-host`（`growth.html` 已有，Story 4.1）。
2. 弹层（`AdminConfirmModal` + `facts`）：标题「确认发放」；正文「确认给 {N} 位用户各发 {X} EXP（合计 {N×X} EXP）？」；facts「活动 {名称}」「批次号 {GB-…}」；note「同一批次重复提交不会重复发放。发放后可能触发用户升级（照常发升级通知）。」；hidden = `batchNo / campaign / expPerUser / userIds`（规范化后的全部 ID，逗号拼接）；按钮「取消 / 确认发放」。
3. 重新归类后有效数为 0 → 422 回抽屉，不弹层。

**AC5 — 执行与去重** `[L0]` `[L1]`（PRD「按（批次号, 用户）去重，重复提交不会重复发」/ AD-2 / AD-5 / AD-6 / §5.2 / §5.4）
1. 弹层内 POST `/admin/growth/batch`：再次重新解析 + 归类；对每个有效用户调用 `GrowthLedgerService.adjust`，命令为**批量形态**：`rule_code = OPS_CAMPAIGN`、`delta = expPerUser`、`note = 活动名`、`operator_admin_id`、**`batch_no = 批次号`**、幂等键 **`GrowthIdemKeys.batch(batchNo, userId)`**（= `BATCH:{batchNo}:{u}`，§5.2 禁止手拼）。若 Story 4.2 的 `AdminAdjustCommand` 只支持 `grantToken`，本 story 给它**追加**批量工厂方法 / 可选字段（不改 4.2 的既有调用）。
2. **逐人独立事务**（`REQUIRES_NEW`，经自身代理调用，照 `SeedBatchPublishService.publishOrScheduleRow` :260-261 与 `confirm` 循环 :122-166 的写法）：单人失败（异常）只计入「失败」，其余照发；整批不放在一个事务里（避免一人失败把已成功的全部回滚，见该类 :152-155 注释的事故）。
3. 每人入账后等级变化照常走 `GrowthLevelChangeHandler`（升级 → 待弹庆祝 + `GROWTH_LEVEL_UP` 通知；降级不会发生，因为批量只增不减）。
4. 幂等命中（该用户在该批次已入账）→ 计入「已发过」，不重复入账、不报错。
5. 统计：`granted`（本次新入账）、`alreadyGranted`、`invalid`、`virtual`、`failed`、合计 EXP = `granted × expPerUser`。

**AC6 — ④ 结果（H4）与失败（H5）** `[L0]` `[L1]`
1. 成功（H4）：弹层收起（`HX-Trigger-After-Swap: admin:confirm-close`）；抽屉体换成结果页：三步条第 3 步高亮；「已发放：批次 {批次号}，{granted} 位用户各 {X} EXP。」；明细「活动 / 入账人数 / 未发放 {invalid + virtual}（无效 {invalid} · 虚拟号 {virtual}） / 操作人 {显示名}」；`failed > 0` 时加一行「失败 {failed} 人，可用同一批次号重试，已入账的不会重复」+「重试」按钮；「完成」关抽屉；右下 toast「已发放 {合计} EXP（{granted} 位用户）」。
2. 同一批次重复提交且本次 `granted = 0`、`alreadyGranted > 0`：结果页显示「该批次已发放过，未重复发放」，toast 不显示「已发放」。
3. 失败（H5）：整次请求出现未预期异常（如数据库不可用）→ 抽屉内错误 banner「发放失败：服务暂时不可用，请重试。同一批次重试不会重复发放。」+ 批次号 + 有效用户数 +「← 上一步」/「重试」（重试 = 带同一批次号再次 POST `/admin/growth/batch`）。不吞异常到 200；日志只记批次号与异常类名。

**AC7 — 审计** `[L0]`（PRD「每批写审计日志」/ AD-17 / NFR6）
1. `AuditActions.GROWTH_BATCH_GRANTED`；每次执行且 `granted > 0` 或 `failed > 0` 时写**一条**：`record(adminId, GROWTH_BATCH_GRANTED, "growth_batch", batchNo, summary)`。纯重放（`granted = 0 && failed = 0`）不写。
2. `summary`：`活动=<名称>；每人=50；入账=5；合计=250；无效=1；虚拟号=1；已发过=0；失败=0`（不列具体用户 ID——数量大且无必要；需要时按 `growth_ledger.batch_no` 查）。
3. 审计在逐人循环之后、独立事务写（循环中的单人事务与审计互不回滚）。

**AC8 — 文案四份** `[L0]`
新键四份同加（`messages.properties` = en），见 Dev Notes「文案键」；`check-i18n-keys.sh`、`check-admin-hardcoded-text.sh` 本地绿。

**AC9 — 回归** `[L0]` `[L1]`
`mvn -B clean package` 全绿；`AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate`（入口门扩展后侧栏可见性与门一致）、`AdminNavPermissionMatrixTest`（`growth.user_adjust` 从「无侧栏页认领」变为有页认领，`theCodesNoNavPageGateClaimsStayAtTheKnownBaseline` 的 25 ~ 40 区间应仍满足）写好；云端标「L1 待本地验收」。

## Tasks / Subtasks

- [ ] **T0 开工核对**：Story 4.2 的 `AdminAdjustCommand` / `adjust` 实际签名与返回值；`GrowthIdemKeys` 是否已有 `batch(...)`；Story 1.1 的参与判定方法名（单个 + 批量）；`growth_ledger.batch_no` 列宽；Story 4.1 的页面门常量与只读渲染开关
- [ ] **T1 入口门扩展**（AC1.3）：`AdminGrowthConfigController` 页面门 + `AdminPageCatalog.growth.navCodes` 同改；只持 USER_ADJUST 时只读渲染
- [ ] **T2 解析 / 归类**：`admin.growth.service.GrowthBatchParser`（纯函数，可单测）+ growth 模块批量参与判定（若 1.1 没有批量版则在 growth 模块加，后台只调用）
- [ ] **T3 服务 `admin.growth.service.AdminGrowthBatchService`**：`preview` / `prepareConfirm` / `execute`（逐人 `REQUIRES_NEW`）/ 批次号生成 / 审计
- [ ] **T4 Controller `admin.growth.web.AdminGrowthBatchController`**：`GET /admin/growth/batch/drawer`、`GET /admin/growth/batch/template.csv`、`POST /admin/growth/batch/form`、`POST /admin/growth/batch/preview`、`POST /admin/growth/batch/confirm`、`POST /admin/growth/batch`
- [ ] **T5 模板**：`growth.html` 标题行加按钮 + 抽屉壳；`fragments/drawer-growth-batch.html`（`fill` / `preview` / `result` / `failed` 四个片段）；`admin.css` 一行宽度
- [ ] **T6 审计常量 + 文案四份**
- [ ] **T7 测试**

## Dev Notes

### 现状（改动前必读，dev_1.4.0 @ 81005da7 核实）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `petgo-backend/src/main/java/com/tailtopia/admin/seed/service/SeedBatchPublishService.java` | 「不阻塞整批」先例：`preview(batchId)` :76-79 只读；`confirm` :107-176 循环里失败行 `skippedByError++` 留草稿（:131-135）、已处理行计 `alreadyDone`（:124-130，bug 20260901-473「静默跳过会被读成计数坏了」）、逐行 `REQUIRES_NEW` 经 `self` 代理（:150-158，注释说明整批单事务会 `UnexpectedRollbackException`）、单行异常转 FAILED 继续（:159-166）；循环后 `audit.record(…"SEED_BATCH_CONFIRM"…)` :170 | 照抄结构，不改该类 | — |
| `…/admin/seed/web/AdminSeedBatchWorkspaceController.java` | 预览页 `GET …/{batchId}/preview` :440-452、确认 `POST …/{batchId}/confirm` :509-544；粘贴 `POST …/rows/paste` :267、Excel 导入 :336、下载模板 `GET …/template` :322-331（`ResponseEntity<byte[]>` + `Content-Disposition: attachment`） | 下载模板照 :322-331 写法（CSV，`text/csv; charset=UTF-8`） | — |
| `…/templates/admin/seed-batch-preview.html` | 汇总一行 :30-31；「校验未过的行不会阻塞整批」提示 :33-35；整页套 `tpl-e-steps :: steps` :26 | 预览片段照其信息结构（抽屉内，不整页） | — |
| `…/templates/admin/fragments/batch-steps.html` | 步骤条 `<ol class="step-bar">` + `li.step[.step--on/.step--done]` :10-20（链接回各步） | 复用类名，写自己的三步（不可点） | — |
| `…/admin/opsnotify/service/AdminOpsNotifyService.java` | AB-31「指定用户 ID」：`checkIds` :212-235（有效 + 按原因跳过、重复只算一次）、`parseIdLines` :245-275（**每行只取第一列**，`FIELD_SEP = [,;\t，]` :102、`ID = \d{1,18}` :100、表头识别 :242-243、`MAX_IDS = 50_000` :59）；CSV 大小上限 4 MB（`AdminOpsNotifyController.MAX_CSV_BYTES` :88、`readCsv` :290） | **不复用解析**（本页要求同一行内逗号 / 空格分隔多个 ID，`parseIdLines` 会丢掉第二个以后的）；表头、BOM、引号处理照抄 | — |
| `…/auth/service/OpsAudienceQuery.java` | `classify(ids)` :91-109 分块批量归类，SQL `OpsAudienceSql.classifyIds` :126-133：`role ≠ USER → NOT_FOUND`、注销 → DELETED、`status ≠ ACTIVE → BANNED`、非 REAL → VIRTUAL、`enabled` 非真 → NOT_FOUND | **不直接复用**：其「role ≠ USER 视为不存在」是运营通知的口径，成长值参与判定以 Story 1.1 / AD-5 为准（见下「归类口径」） | — |
| `…/admin/growth/web/AdminGrowthConfigController.java` + `…/templates/admin/growth.html` | Story 4.1 新建：页面门 `VIEW_AUTH`（VIEW 或 EDIT）、`#confirm-host`、`.wb-title-row` 标题行 | 门加 USER_ADJUST；标题行加按钮；加抽屉壳 | 4.1 的只读逻辑 |
| `…/admin/shared/AdminPageCatalog.java` | Story 4.1 后 `growth` 页 `navCodes = (GROWTH_CONFIG_VIEW, GROWTH_CONFIG_EDIT)`；Story 4.2 把 `GROWTH_USER_ADJUST` 记在本页 `otherCodes` | `navCodes` 加 `GROWTH_USER_ADJUST` | 归属不变 |
| `…/growth/service/GrowthLedgerService.java` | Story 4.2 加的 `adjust(AdminAdjustCommand)` | 追加批量形态（工厂方法 / 可选 `batchNo`） | 4.2 既有调用不变 |
| `…/templates/admin/fragments/tpl-shared.html` | `drawer(res, titleSlot)` :19-29 | 用 `res='growthbatch'` | — |
| `…/static/admin/admin.css` | `#user-drawer.drawer { width: 600px; }` :939（注释「只放宽这一个抽屉」） | 加 `#growthbatch-drawer.drawer` 一行，注释写 UI 稿 H1「600 宽抽屉（同用户抽屉宽度）」 | 其余抽屉 480 |
| `…/admin/audit/service/AuditActions.java` | 见 Story 4.1 | 加 `GROWTH_BATCH_GRANTED` | — |

测试参照：`petgo-backend/src/test/java/com/tailtopia/admin/seed/SeedBatchPublishIntegrationTest.java`（不阻塞整批的集成断言写法）、`…/admin/seed/SeedBatchValidatorTest.java`（纯函数校验单测）、`…/admin/opsnotify/` 下 ids 校验相关测试（解析 / 表头 / BOM 用例写法）、集成基类 `…/support/ApiIntegrationTest.java`。

### OPS_MANAGER 进不了成长体系页（与架构 / PRD 的隐含冲突，按推荐处理）

- P-5 只把 `GROWTH_USER_ADJUST` 预授给 OPS_MANAGER，`GROWTH_CONFIG_VIEW / EDIT` 不预授（Story 4.1 AC2）。而批量发放的入口在「成长体系」页上（PRD 定）。若页面门只认 `GROWTH_CONFIG_*`，**OPS_MANAGER 有发放权却打不开入口页**，P-5 的预授对批量发放等于没给。
- 推荐（本 story 实现）：页面门与 `navCodes` 加入 `GROWTH_USER_ADJUST`；只持该码时三个页签只读显示。理由：规则分值、上限、等级门槛 App 等级页本来就对用户公开，只读暴露给有发放权的运营无额外风险；不另开新页、不加新码。
- 若操作者更希望「只持 USER_ADJUST 的人只看见批量发放、看不到配置」，改为：页面门含 USER_ADJUST，但三个页签内容在无 `GROWTH_CONFIG_VIEW` 时换成一句提示「需要「查看 EXP 配置」权限」。二者都不影响其余 AC，开工前按操作者回复取其一（未回复按推荐）。

### 归类口径（与单个调整一致）

- 有效 = Story 1.1 的参与判定（与 `AccountQueryService.isActiveRegularUser` :72-80 同口径：`role = USER`、`account_type = REAL`、未注销、`status = ACTIVE`）——**批量与单个调整必须调用同一个判定**，不在后台复制条件。
- `role ≠ USER`（官方号 `ADMIN`、兽医等）在 1.1 口径下不参与：预览里归为无效，原因文案「不参与成长值」（键 `admin.v140.growth.batch.reason.NOT_PARTICIPATING`），不要显示成「用户不存在」。
- 判定的批量版放 growth 模块（如 `GrowthParticipation.classify(Collection<Long>) → Map<Long, Status>`，一次 SQL），`AdminGrowthBatchService` 只调用。

### 上限建议

- `MAX_IDS = 5,000`（去重后）、CSV ≤ 1 MB：V1 规模（≤ 500 DAU，架构 §0.1）下一批活动远小于此；上限的意义是防误粘贴整张表，以及确认弹层 hidden 字段与逐人事务的耗时可控。数字是建议值，操作者若给了别的数以操作者为准；AB-31 的 5 万是「通知触达」口径，不照搬。
- 每人 EXP 不设上限（PRD 未规定）；确认弹层已复述「人数 × 每人 = 合计」作为防手误的最后一道。

### 端点一览

| 方法 | 路径 | 门 | 返回 |
|---|---|---|---|
| GET | `/admin/growth/batch/drawer` | `GROWTH_ADJUST_AUTH` | 抽屉体 `fill`；非 htmx → `redirect:/admin/growth` |
| GET | `/admin/growth/batch/template.csv` | 同上 | CSV 附件 |
| POST | `/admin/growth/batch/form` | 同上 | `fill`（回填） |
| POST | `/admin/growth/batch/preview` | 同上 | `preview`；422 → `fill` 带字段错误 |
| POST | `/admin/growth/batch/confirm` | 同上 | `confirm-modal :: modal` → `#confirm-host`；422 → 抽屉错误槽 |
| POST | `/admin/growth/batch` | 同上 | `result`（+ toast oob + `admin:confirm-close`）；未预期异常 → `failed` |

- GET 路由以 `drawer` / `.csv` 结尾，`check-admin-permission-consistency.sh` 不当页面（:96）。
- 抽屉内各步互换用 `HX-Retarget #growthbatch-drawer-body` + `innerHTML`；错误槽 `#growthbatch-err` 必须带 id（htmx 才发 `HX-Target`）。

### 架构约束

- **AD-6**：`OPS_CAMPAIGN` 批量幂等键 `BATCH:{batchNo}:{u}`、虚拟号排除；**§5.2** 键只经 `GrowthIdemKeys`；**AD-2** 流水带 `batch_no`、`note`、`operator_admin_id`。
- **§5.4**：写成长值只经 `GrowthLedgerService.adjust`；等级连锁只在 `GrowthLevelChangeHandler`。
- **AD-17**：600 抽屉 + 三步条；照 `SeedBatchPublishService` 的预览 / 不阻塞整批；批次号 `GB-{yyyyMMddHHmm}-{rand}`。
- 护栏：不引 MQ / 缓存 / 任务队列（同步逐人执行；5,000 人量级在一次请求内完成）；不加迁移；不改共享 CHECK。
- 日志只记批次号、计数、异常类名；不逐个打印用户 ID。

### 文案键（四份；`messages.properties` = en）

| 键 | zh_CN | en | id |
|---|---|---|---|
| `admin.v140.growth.batch.open` | 批量发放 | Bulk grant | Pemberian massal |
| `admin.v140.growth.batch.title` | 批量发放 EXP | Bulk grant EXP | Pemberian EXP massal |
| `admin.v140.growth.batch.step.1` / `.step.2` / `.step.3` | 1 · 填写 / 2 · 预览 / 3 · 发放 | 1 · Fill in / 2 · Preview / 3 · Grant | 1 · Isi / 2 · Pratinjau / 3 · Berikan |
| `admin.v140.growth.batch.campaign` / `.campaignHint` | 活动名称 / 写进每条流水，用户在 EXP 明细里看到。 | Campaign name / Written into each record; users see it in their EXP history. | Nama kampanye / Ditulis di setiap catatan; pengguna melihatnya di riwayat EXP. |
| `admin.v140.growth.batch.exp` / `.expHint` | 每人 EXP / 正整数。 | EXP per user / Positive integer. | EXP per pengguna / Bilangan bulat positif. |
| `admin.v140.growth.batch.ids` / `.idsHint` | 用户 ID / 逗号、空格或换行分隔；或上传 CSV（一列 user_id）。 | User IDs / Separate with commas, spaces or new lines; or upload a CSV (one user_id column). | ID pengguna / Pisahkan dengan koma, spasi, atau baris baru; atau unggah CSV (satu kolom user_id). |
| `admin.v140.growth.batch.template` / `.chooseFile` / `.noFile` | 下载模板 / 选择文件 / 未选择文件 | Download template / Choose file / No file chosen | Unduh templat / Pilih file / Belum ada file |
| `admin.v140.growth.batch.preview` | 预览 | Preview | Pratinjau |
| `admin.v140.growth.batch.sum.valid` / `.sum.invalid` / `.sum.virtual` | 有效用户 / 无效 ID / 虚拟号（已排除） | Valid users / Invalid IDs / Virtual (excluded) | Pengguna valid / ID tidak valid / Virtual (dikecualikan) |
| `admin.v140.growth.batch.total` | 合计 | Total | Total |
| `admin.v140.growth.batch.col.id` / `.col.result` | 用户 ID / 结果 | User ID / Result | ID pengguna / Hasil |
| `admin.v140.growth.batch.reason.NOT_FOUND` / `.DELETED` / `.DEACTIVATED` / `.MALFORMED` / `.VIRTUAL` | 用户不存在 / 已注销 / 已停用 / 格式不正确 / 虚拟号，自动排除 | User not found / Deleted / Deactivated / Invalid format / Virtual account, excluded | Pengguna tidak ditemukan / Dihapus / Dinonaktifkan / Format salah / Akun virtual, dikecualikan |
| `admin.v140.growth.batch.reason.NOT_PARTICIPATING` | 不参与成长值 | Not eligible for EXP | Tidak ikut EXP |
| `admin.v140.growth.batch.moreRows` | 另有 {0} 条未显示 | {0} more not shown | {0} lainnya tidak ditampilkan |
| `admin.v140.growth.batch.previewNote` | 无效 ID 与虚拟号不会发放，不阻塞整批。已注销 / 已停用账号同样计入无效。 | Invalid IDs and virtual accounts are skipped without blocking the batch. Deleted or deactivated accounts also count as invalid. | ID tidak valid dan akun virtual dilewati tanpa menghentikan batch. Akun yang dihapus atau dinonaktifkan juga dihitung tidak valid. |
| `admin.v140.growth.batch.back` / `.confirmBtn` | ← 上一步 / 确认发放 | ← Back / Confirm grant | ← Kembali / Konfirmasi pemberian |
| `admin.v140.growth.batch.confirm.title` | 确认发放 | Confirm grant | Konfirmasi pemberian |
| `admin.v140.growth.batch.confirm.body` | 确认给 {0} 位用户各发 {1} EXP（合计 {2} EXP）？ | Give {1} EXP to each of {0} users ({2} EXP in total)? | Berikan {1} EXP ke masing-masing {0} pengguna (total {2} EXP)? |
| `admin.v140.growth.batch.confirm.campaign` / `.confirm.batchNo` | 活动 / 批次号 | Campaign / Batch no. | Kampanye / No. batch |
| `admin.v140.growth.batch.confirm.note` | 同一批次重复提交不会重复发放。发放后可能触发用户升级（照常发升级通知）。 | Resubmitting the same batch never grants twice. Grants may level users up (level-up notifications are sent as usual). | Mengirim ulang batch yang sama tidak memberi dua kali. Pemberian bisa menaikkan level pengguna (notifikasi naik level dikirim seperti biasa). |
| `admin.v140.growth.batch.done` | 已发放：批次 {0}，{1} 位用户各 {2} EXP。 | Granted: batch {0}, {2} EXP to each of {1} users. | Sudah diberikan: batch {0}, {2} EXP ke masing-masing {1} pengguna. |
| `admin.v140.growth.batch.result.granted` / `.skipped` / `.operator` | 入账人数 / 未发放 / 操作人 | Granted / Not granted / Operator | Diberikan / Tidak diberikan / Operator |
| `admin.v140.growth.batch.result.skippedDetail` | {0}（无效 {1} · 虚拟号 {2}） | {0} (invalid {1} · virtual {2}) | {0} (tidak valid {1} · virtual {2}) |
| `admin.v140.growth.batch.result.failed` | 失败 {0} 人，可用同一批次号重试，已入账的不会重复 | {0} failed; retry with the same batch no. — users already granted won't be granted again | {0} gagal; coba lagi dengan no. batch yang sama — yang sudah diberikan tidak akan diberikan lagi |
| `admin.v140.growth.batch.alreadyDone` | 该批次已发放过，未重复发放 | This batch was already granted; nothing was granted again | Batch ini sudah diberikan; tidak diberikan lagi |
| `admin.v140.growth.batch.finish` / `.retry` | 完成 / 重试 | Done / Retry | Selesai / Coba lagi |
| `admin.v140.growth.batch.failed` | 发放失败：服务暂时不可用，请重试。同一批次重试不会重复发放。 | Grant failed: the service is temporarily unavailable. Please retry — retrying the same batch never grants twice. | Pemberian gagal: layanan sedang tidak tersedia. Silakan coba lagi — mengulang batch yang sama tidak memberi dua kali. |
| `admin.flash.growth.batchGranted` | 已发放 {0} EXP（{1} 位用户） | Granted {0} EXP ({1} users) | {0} EXP diberikan ({1} pengguna) |
| `admin.err.growth.batch.campaignRequired` / `.campaignTooLong` | 请填写活动名称 / 活动名称不能超过 200 字 | Enter a campaign name / Campaign name can't exceed 200 characters | Isi nama kampanye / Nama kampanye maks. 200 karakter |
| `admin.err.growth.batch.expInvalid` | 每人 EXP 须为正整数 | EXP per user must be a positive integer | EXP per pengguna harus bilangan bulat positif |
| `admin.err.growth.batch.idsRequired` | 请填写或上传用户 ID | Enter or upload user IDs | Isi atau unggah ID pengguna |
| `admin.err.growth.batch.idsTooMany` | 用户 ID 不能超过 {0} 个 | No more than {0} user IDs | Maksimal {0} ID pengguna |
| `admin.err.growth.batch.fileTooLarge` | CSV 文件不能超过 1 MB | The CSV file can't exceed 1 MB | File CSV maks. 1 MB |
| `admin.err.growth.batch.noValid` | 没有可发放的有效用户 | No valid users to grant | Tidak ada pengguna valid untuk diberikan |

取消钮复用 Story 4.1 的 `admin.v140.growth.cancel`；`admin.err.growth.campaignDisabled` 复用 Story 4.2。

### 本 story 不做

- 批次管理页 / 批次撤销 / 按批次回滚（PRD 未要求；需要撤销时运营在用户抽屉逐个负向调整）。
- 活动规则引擎（PRD：随内容话题在 1.4.4）。
- 不建批次表、不加迁移；不做异步任务化（同步执行即可）。
- 不改 Story 4.2 的单个调整行为。

### 测试

- **L0（云端必须全绿）**
  - `admin/growth/service/GrowthBatchParserTest`：逗号 / 中文逗号 / 空格 / 换行混合；同行多个 ID；重复只算一次；非数字计「格式不正确」；BOM；引号；CSV 表头 `user_id` 忽略；超过 `MAX_IDS` 抛 422；空输入 422。
  - `admin/growth/service/AdminGrowthBatchServiceTest`（mock 参与判定与 `GrowthLedgerService`）：归类计数（有效 / 不存在 / 注销 / 停用 / 不参与 / 虚拟号）；合计 = 有效 × 每人；批次号格式 `^GB-\d{12}-[A-Z0-9]{6}$` 且 ≤ 32；执行时每个有效用户以 `BATCH:{batchNo}:{u}` 调用一次；某一人抛异常 → 其余照发、`failed = 1`；幂等命中计 `alreadyGranted`；纯重放不写审计、有新入账写一条且摘要字段齐；`OPS_CAMPAIGN` 停用 422。
  - 守卫：`AdminPageCatalogTest`（`growth` 页 `navCodes` 码均在册）、`AdminPermissionWiringTest`。
- **L1（写好能编译；云端不跑，标「L1 待本地验收」）**
  - `admin/growth/AdminGrowthBatchIntegrationTest`（`extends ApiIntegrationTest`）：无码用户在成长体系页**看不到**「批量发放」、调端点 403；只持 `growth.user_adjust` 的账号能进 `/admin/growth`（只读）且看到按钮；造 5 个真实 + 1 虚拟 + 1 不存在 + 1 停用 → 预览计数正确；确认 → `growth_ledger` 5 行（`rule_code = OPS_CAMPAIGN`、`batch_no`、`note`、`operator_admin_id`）、审计 1 行；同一批次再提交 → 0 新行、结果「已发放过」、审计仍 1 行；跨级升级者有 `GROWTH_LEVEL_UP` 通知行。
  - `AdminPagesRenderSmokeTest.everySidebarLinkMatchesItsPageGate`、`AdminNavPermissionMatrixTest`。
- **L2**：本地后台走 H1 ~ H5（H5 可停本地 postgres 复现），三语。

#### 验证层级

| 层级 | 内容 | 环境 |
|---|---|---|
| L0 | `mvn -B clean package`；`GrowthBatchParserTest`、`AdminGrowthBatchServiceTest`、`AdminPageCatalogTest`、`AdminPermissionWiringTest`；`check-i18n-keys.sh`、`check-admin-hardcoded-text.sh`、`check-admin-permission-consistency.sh` | 云端可跑 |
| L1 | `AdminGrowthBatchIntegrationTest`、`AdminPagesRenderSmokeTest`、`AdminNavPermissionMatrixTest` | 本地 Docker postgres + redis |
| L2 | 后台 H1 ~ H5 人工验收；被发放用户 App 等级页 / EXP 明细看到活动名（连 stag） | 本地浏览器 + Android 模拟器 |

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-tailrent-level.md#Story 4.3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-tailrent-level-delta.md#AD-2 / AD-5 / AD-6 / AD-17 / P-5 / §5.2 / §5.4]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/1-4-0Tailrent等级prd.md#3.4 运营活动发放 · 批量]
- [Source: _bmad-output/planning-artifacts/v1.4.0/Tailrent等级/ui-admin-1-4-0-tailrent-level-integrated-v1.html#H1 ~ H5 / G7]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#① OQ-9 / 建议 9/9]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/4-2-用户抽屉成长值页签与手动调整.md#AC1 / AC5]

### 拍板（2026-10-18，Dai；优先于本文件正文与「待确认」）

- 官方号与虚拟号一样列入预览排除（不参与成长值）。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log
