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

## 二轮评审（2026-08-18，全量重审 · 以代码为事实）修复批

一轮 10 条修复经直读代码逐条复核**全部真实生效**；二轮新入榜 10 条已全部修复：

| # | 问题 | 修法 |
|---|---|---|
| R2-1 [x] | `upsertTicket` catch 唯一约束异常=死代码（rollback-only），并发首报败方 500 丢数据 | 改 `INSERT … ON CONFLICT (target_user_id) DO NOTHING` + find；提交前校验目标用户存在（404） |
| R2-2 [x] | `insertIfAbsent` 同款假幂等，双击拉黑/拉黑不存在者 500 | 改 `ON CONFLICT (holder,target,source) DO NOTHING`；block 前校验目标存在（404） |
| R2-3 [x] | 举报自由文本 `detail` 原文进接口日志（PII 红线） | LogSanitizer 新增**仅请求体**脱敏键集 `{detail}`（`sanitizeRequest`），响应体 RFC 9457 的 detail 不受影响 |
| R2-4 [x] | 账号举报处置从不回告举报人（FR-51 缺失） | `resolveTicket`/`dismiss` 后对该工单**去重举报人**逐个发布 `ReportResolvedEvent`（复用内容举报同一监听器与模糊文案，AFTER_COMMIT） |
| R2-5 [x] | warn/suspend 不校验 reportId 归属，可警告 X 关掉 Y 的工单 | `requireDisposalTarget`：目标用户须存在 + `report.targetUserId` 必须匹配；controller 侧 AppException 走 flash 不再 500 |
| R2-6 [x] | dismiss-all 只 gate 查看权，只读审核员可批量抹举报 | gate 改 `content.takedown`（对齐旧批量驳回）；模板按钮同步 |
| R2-7 [x] | 内容举报批量能力回退 + 报错答非所问 | batch 端点按类型分派：内容批次支持 TAKEDOWN（新「批量下架」按钮）/DISMISS（=批量驳回，走 `batchByPost` 逐帖独立事务），警告/封号给指向性提示；账号/内容两支各自收权限口 |
| R2-8 [x] | 检索 ILIKE 元字符零转义 + 数字昵称搜不到 | `escapeLike`（%/_/\\ + ESCAPE）；纯数字改 `(target_user_id = ? OR nickname ILIKE ?)` 双命中 |
| R2-9 [x] | 拉黑评论作者后评论计数不刷新（拉黑泄底） | `_onCommentAuthorHidden` 改 bump `commentsRefreshProvider`（同时驱动列表与头部计数） |
| R2-10 [x] | 警告/无需处置/批量条缺 sec:authorize，view-only 人群点了必 403 | 行内动作与批量条全部按权限渲染（警告/无需处置=dispose；批量封号=+user.deactivate；下架/驳回/批量下架=takedown） |

L0：后端 test-compile + 44 单测绿（含契约更新的 `batchRejectsNonAccountTicketTypes`）；前端 analyze 0 + social/content 130 测试绿。
未入榜 backlog 仍开放：settings 拉黑计数、200 字 grapheme/UTF-16 计数、重复举报重复计数、注销者拉黑 403 toast、浮层遗留、cleanup 一批。

## 三轮评审（2026-08-19，全量重审 · 只信任代码）修复批

前两轮 20 条修复经直读当前代码逐条复核**全部真实生效、无假修复**。三轮新入榜 10 条已全部修复：

| # | 严重度 | 问题 | 修法 |
|---|---|---|---|
| R3-1 [x] | 安全 | 兽医 JWT 可越权操作任意用户拉黑/举报（新端点落 anyRequest().authenticated()，sub=vetId 与 users.id 碰撞） | SecurityConfig 给 /me/blocked-users 与 /account-reports 补 `hasRole('USER')`（对齐 consult/support）；MiniProfileController.viewerId 额外校验 role=USER（permitAll 端点的深度防御） |
| R3-2 [x] | 安全 | 单条驳回 /admin/reports/{id}/dismiss 仍 gate 查看权，绕过 R2-6 处置权闸 | gate 改 `content.takedown`（对齐 dismiss-all/批量驳回） |
| R3-3 [x] | 正确性 | warn/suspend/dismiss 无 PENDING 守卫，过期页/并发重放副作用 | requireDisposalTarget + dismiss 加 `requirePending`（工单非 PENDING 报错回列表） |
| R3-4 [x] | 正确性 | 评论计数缺「父被隐藏→整串回复不计数」传递条件，拉黑泄底 | countVisibleForViewer 对回复行加父评论自身可见性 EXISTS（覆盖父被隐藏/影子/软删） |
| R3-5 [x] | 并发（PLAUSIBLE） | 处置进行中提交的新举报静默落入终态工单丢失 | 举报提交与处置双路径改行级写锁（findByTargetUserIdForUpdate / findByIdForUpdate）串行化，单行同序无死锁 |
| R3-6 [x] | 正确性 | 批量按钮跨权限渲染，点了整页 403 | 控制器分支的权限不足从 throw AccessDeniedException 降级为红色 flash（不再 500/403） |
| R3-7 [x] | 正确性 | 回告用全量举报人，工单翻面重发历史周期幽灵通知 | notifyReporters 以上次 handled_at 为界，只回告本周期新举报人（findDistinctReporterIdsAfter） |
| R3-8 [x] | 正确性 | 举报成功态被点蒙层收起当成取消，Feed/标签不更新 | openAccountReport 改用 onSubmitted 回调记账，成功与关闭方式解耦（怎么关都算成功） |
| R3-9 [x] | 正确性 | 处置失败提示渲染成绿色成功横幅，运营读成成功 | 失败信息改走 error flash 键 + 模板加红色 err 横幅（对齐全后台约定） |
| R3-10 [x] | 轻微 | 黑名单日期显示 UTC 差一天 | blockedAt 解析后 .toLocal()（对齐 App 内其它绝对日期路径） |

L0：后端 test-compile + 相关单测绿（含契约更新的批量权限/flash 断言）；前端 analyze 0 + social/content 130 测试绿。
**待 L1**（需 DB/scratch 库真跑）：R3-3 状态守卫、R3-4 计数 SQL 传递条件、R3-5 行级锁串行化都只能在集成测试/真库验证——L0 编译绿不代表 SQL 语义与锁行为正确。
未入榜 backlog 仍开放：confirm_sheet 无 PopScope、takedown 重定向无 flash、解除拉黑 toast 遮挡，及 cleanup 一批。
