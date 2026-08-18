# V1.1.4 上线前修复清单（code review 2026-08-18）

> 范围：`hex/v1.1.4` 相对 `main` 的 29 个提交（社区管控 Story 1.1~4.1 + analytics 收尾，139 文件 / +23k 行）。
> 8 角度扫描 + 逐条对抗验证，10 条 CONFIRMED 入榜 + 3 条次要 + 1 条待产品拍板。
> ⚠️ 功能测试已过不代表安全：本清单多数问题在 MockMvc / 单测路径上全绿，只在真实浏览器或特定登录路径暴露。
>
> 状态标记：每条修完把 `[ ]` 改 `[x]` 并附提交号。
>
> **2026-08-18 修复批**：#1–#10 与 P3「批量超 50 条」已全部修复（#10 按拍板执行 A 路线：PostHog 专项豁免收编进文档与代码注释）。L0 全绿（后端 test-compile + 37 相关单测；前端 analyze 0 issues + social/content 130 测试）。**待 L1**：AdminPagesRenderSmokeTest 等集成测试需本地 scratch 库；模板改动（#2/#3/#6）仍需真浏览器点一遍验收。未提交。

## P0 —— 安全/功能性失效，不修不能上线

### 1. [x] 封号对 Apple 登录账号完全失效（安全）

- **位置**：`petgo-backend/.../auth/service/AuthService.java:124`（`loginWithApple`）
- **问题**：缺少 `isActiveStatus` 状态闸。`loginWithGoogle`（89-91 行）与 `rotateRefresh`（204-206 行）都有的检查，Apple 路径没有。
- **后果**：运营封号（Story 3.2，置 `status=DEACTIVATED` + 吊销 refresh handle）后，用户重走一次 Apple Sign-In 即拿到全新 access+refresh token，且新 handle 永久可用。请求层无兜底（JWT 无状态、`BannedVetFilter` 只查兽医、`/api/v1/auth/**` permitAll）。
- **修法**：`findByAppleSub` 命中后、签发 token 前，加与 Google 路径完全相同的状态检查。
- **验收**：集成测试——封禁用户走 Apple 登录应被拒（与 Google 路径同款断言）。

### 2. [x] 工单页首个行内处置按钮失效——嵌套 form 解析怪癖（已在 stag 实测复现 400）

- **位置**：`petgo-backend/src/main/resources/templates/admin/tickets.html:164`（171、179 行同）
- **问题**：行内「警告 / 封号 / 无需处置」三个 form 嵌套在批量 form（67→192 行）内部。HTML 禁止 form 嵌套：浏览器丢弃**每次渲染遇到的第一个**内层 `<form>` 标签（首个待处理行的「警告」）；但其 `</form>` 会把解析器 form 指针清空（外层 form 隔着 `<table>` 不在作用域），后续所有内层 form 反而正常建立。
- **后果**：每次渲染只有「第一个待处理账号举报行的警告按钮」是坏的——它绑到外层批量 form，点击提交 `POST /admin/tickets/batch` 缺必填 `action` 参数 → 400（`MissingServletRequestParameterException`），确认弹窗也不出；每次处置后 redirect 重排，「新的第一行」又是坏的。**2026-08-18 stag 实测复现**：5 条处置 4+1 成功、首行警告 400。比「全灭」更险：功能测试大概率全绿，线上偶发。
- **修法**：内层改为 form 外置 + `form` 属性关联，或按钮改 `formaction`/HTMX `hx-post`，或缩小批量 form 的包裹范围只含 checkbox。
- **验收**：真实浏览器对**列表第一行**点「警告」应弹确认并成功提交到 `/admin/tickets/warn`（专门验第一行——其它行本来就是好的）。

### 3. [x] 内容举报工单在所有 UI 都无法处置，会永久堆积

- **位置**：`tickets.html:162`（操作列 gate 在 `type==ACCOUNT_REPORT`）+ `AdminWebController.java`（`GET /admin/reports` → `redirect:/admin/tickets`）
- **问题**：统一队列只给账号举报渲染处置按钮；批量提交被 `parseAccountReportIds` 整体拒绝；旧内容举报页已改纯 redirect，但下架/驳回端点（AdminWebController:117-146）还在、只是没有任何入口可达。另：CTE 里 `sub_type` 固定 NULL、detail 对非 ACCOUNT_REPORT 返回空 entries → 举报原因（INFRINGEMENT/SPAM 等）在任何界面都看不到。
- **后果**：内容举报工单只能「展开」（还是空的），下架、驳回、REPORT_REVIEWED 回告全部不可达，PENDING 堆积。
- **修法**：统一队列为 CONTENT_REPORT 渲染下架/驳回操作（接回既有端点），CTE/detail 补 `sub_type` 与举报原因 entries。
- **验收**：真浏览器对一条内容举报工单完成下架与驳回全流程；detail 能看到 reasonType。

### 4. [x] 权限断档：存量审核员部署后被锁在工单队列外

- **位置**：`AdminWebController.java:113`（`content.view_tickets` gate）+ `layout.html:28`；迁移 V101-V104 只建表
- **问题**：队列入口换新权限点 `content.view_tickets`，但没有迁移为存量 `content.view_reports` 持有者回填。
- **后果**：部署当天所有非 SUPER_ADMIN 审核员：侧栏看不到队列，旧书签 `/admin/reports` → redirect → 403。人工重授前无人能处理举报。
- **修法**：新增 Flyway 迁移（**序号顺延分配，勿撞号**——决策 E2），把持有 `content.view_reports` 的账号批量授予 `content.view_tickets`。
- **验收**：scratch 库跑迁移后，原 STAFF 审核员能进 `/admin/tickets`。

## P1 —— 明显缺陷，强烈建议同批修

### 5. [x] 封号/警告推送兜底是硬编码中文（印尼用户收到中文封号通知）

- **位置**：`AccountDisposalService.java:158-159 / 195-196`；三份 `messages_*.properties` 均缺键
- **问题**：`notify.ACCOUNT_WARNED.*` / `notify.ACCOUNT_SUSPENDED.*` 键全部漏加，`NotificationService.pushText` 查不到 → 回落到中文兜底串。被停用用户进不了 App（refresh 已吊销），推送是唯一通道，C-101 法务定稿的印尼语文案永远展示不出来。ARB 键只覆盖 App 内展示。
- **修法**：三份 properties 补齐两组键（文案用 C-101 定稿）。
- **验收**：单测断言 messageSource 三语均能解析这两组键。

### 6. [x] 工单「展开详情」渲染在表格最底部（第二个 th:each 并列循环）

- **位置**：`tickets.html:187-188`（占位行）+ 154 行（hx-target）
- **问题**：详情占位行是与主行并列的第二个循环，所有占位统一堆在表尾。点第 1 行「展开」→ HTMX 把详情装进屏外表底，管理员感知「点了没反应」。
- **修法**：合并为单循环，`th:block` 内逐工单输出主行 + 详情行两个 `<tr>`。
- **验收**：真浏览器点任意行「展开」，详情出现在该行正下方。

### 7. [x] 评论区拉黑/举报后，首页 feed 不移除该作者内容

- **位置**：`petgo_app/lib/features/content/presentation/comment_section.dart:248`（116-131 行回调接线）
- **问题**：评论区迷你卡的 `onBlocked`/`onReported` 都接成 `_reload`（只重拉本帖评论），没走 home/detail 页的 `onAuthorHidden`（`feedProvider.removeByAuthor` + `maybePop`）。
- **后果**：返回首页后被拉黑者卡片仍在（直到手动下拉刷新）——正是本版要消灭的「我明明处理了，他的东西还在」；若被拉黑者是帖主，用户还停在服务端已 404 的详情页。
- **修法**：评论区入口接上与其它入口一致的 `onAuthorHidden` 语义（含 feed 失效与必要的 pop）。
- **验收**：widget 测试 + 模拟器实测：评论区拉黑后回首页，该作者卡片消失。

### 8. [x] 处置通知先于工单/审计提交，后续回滚不撤回推送

- **位置**：`AccountDisposalService.java:195`（warn 同）
- **问题**：`NotificationService.send` 是 REQUIRES_NEW（立即提交并推送），排在 `resolveTicket` 与审计落链（advisory 锁 + 哈希链，现实可失败）之前。后两步抛异常 → 外层回滚处置与停用，但「账号已停用」通知/推送/角标已发出且不撤回；与类 javadoc「全程同一事务」（26 行）矛盾。
- **修法**：把 send 挪到 resolveTicket + audit 之后（仍在方法末尾），或改 AFTER_COMMIT 语义（注意历史旧坑：同步 AFTER_COMMIT 监听器里 send 默认 REQUIRED 会静默不提交，监听器内需 REQUIRES_NEW——见 2026 年 6 月通知事务吞写事故）。
- **验收**：集成测试——audit 抛异常时断言无 ACCOUNT_SUSPENDED 通知落库。

### 9. [x] 工单检索纯数字超 19 位 → 500

- **位置**：`admin/moderation/service/UnifiedTicketQueryService.java:209`
- **问题**：关键字全数字则直接 `Long.parseLong`，粘贴超长号码（如去 `+` 的手机号 22 位）溢出抛 NumberFormatException，无 catch → 整页 500（HTMX 下 `#results` 被错误片段填充）。
- **修法**：try/catch 后按昵称 LIKE 或空结果处理。
- **验收**：单测输入 22 位纯数字返回空结果而非异常。

## P2 —— 待产品/Dai 拍板（不是纯代码修复）

### 10. [x] PostHog 明文 `internal_user_id` 与护栏冲突（commit a2c3d0f4）

- **位置**：`petgo_app/lib/core/analytics/analytics.dart:84`
- **冲突点**：根 CLAUDE.md「对外暴露标识一律不可枚举 token，不用自增 id 直接外露」；`docs/analytics-posthog-tracking.md:79`「不传任何 userProperties」；D4 决议只豁免无盐哈希，并点名合规替代（backend 下发 analytics_token / HMAC）。文件头注释（14-15 行）仍称「只传哈希」，与代码自相矛盾。
- **提交时的论证**：无盐哈希本可被反推回自增 id，明文附带不增加实际暴露面（运营需按数字 id 定位用户）。
- **两条路二选一**：
  - A. 维持现状 → 更新 `analytics-posthog-tracking.md` 与文件头注释，把豁免正式写进 D4；
  - B. 撤明文 → 改 HMAC(密钥, id) 或 backend 下发 analytics_token，运营侧建映射查询。

## P3 —— 次要（已验证存在，可延后）

- [x] **批量处置超 50 条**：上限异常未接 flash 处理，整页报错而非提示（`UnifiedTicketController.batch`）。
- [ ] **⋯菜单浮层遗留**：`mini_profile_sheet` 的 OverlayEntry 无 route 感知，Android 系统返回键 pop 掉弹层后菜单+scrim 仍浮在页面上（`_openCardMenu`，215-225 行；有 `mounted` 兜底不至崩溃）。
- [ ] cleanup 候选：`AccountDisposalService` 两个死方法、新表 FK 级联缺索引、settings 页 autoDispose 每次进入全量拉黑名单、工单 CTE 双重物化。

## 修复纪律

- 全部在 `hex/v1.1.4` 分支修；后端改动跑 L0（`mvn -B test-compile` + 相关测试）；**改了迁移记得 test-compile 重拷资源**（surefire 旧资源坑）。
- 模板类问题（#2/#3/#6）必须真浏览器验收，MockMvc 绿灯不算数。
- #1/#4/#8 补集成测试后在本地 scratch 库过 L1。
- 前端 #7 修完模拟器（Android）实测拉黑链路。
