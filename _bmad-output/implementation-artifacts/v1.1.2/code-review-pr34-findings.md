# PR #34 代码评审findings（hex/v1.1.2 → main）

> 评审对象：https://github.com/DaiHanWei/petgo-platform/pull/34 · 68 commits / 426 files / +31716 −2499
> 评审日期：2026-08-07 · 方式：三路独立 agent（盲审对抗 / 边界枚举 / 逐行审计）交叉去重
> 共 **15 条**（1 critical · 11 major · 3 minor）+ 3 条未计入上限的次要项
>
> **状态标注**：修复后请在对应条目前打勾并注明 commit。
>
> **2026-08-07 核实 + 修复状态**（三路 agent 对抗式核实 + 全量修复，均在 hex/v1.1.2 未提交）：
> - ✅ 已修复：#1 #2 #3 #4 #5 #6 #7 #8 #9 #10 #11 #12 #13 #14 #15 + 次要项 2/3。
>   L0 全绿（后端 test-compile + 相关单测 58 绿；`flutter analyze` 零问题 + 全量 754 测试绿）。
>   ⚠️ 后端 L1（RefundDecisionIntegrationTest 等）待本地 scratch 库跑。
> - ❌ 次要项 1 **核实为不成立**（REFUTED）：`login_guide_controller.dart` 182/195 行在
>   `isNewUser` 时已上报 `af_complete_registration`（Google/Apple 双路径），144 行是并存的
>   `signup_succeeded` 事件，不存在漏报——不修。
> - 核实修正：#1 的「落到埋点池被静默丢弃」不成立（确定性回退 SimpleAsyncTaskExecutor，
>   任务不丢只是无池化），「约 60 个」实为 22 处，critical 宜降 High；#4 订单无
>   PENDING/CANCELLED 态（真实坏输入是 IN_PROGRESS），卡死的是退款单与用户端入口、工单本身
>   可正常结案；#5 实际敞口更大——不依赖 relink，首次关联任意 COMPLETED 订单同样可批；
>   #6 仅新用户首登触发，宜降 Medium；#9 文件实际在 `features/onboarding/`。

---

## 优先级建议

| 批次 | 条目 | 理由 |
|---|---|---|
| **合并前必修** | #1 | critical，影响全仓 `@Async`，最坏静默丢任务 |
| **合并前建议修** | #8 #9 #10 #14 | 本次推送/ATT 改动引入，白屏风险 / 用户可感 / 隐私相关 / 审核风险 |
| **本迭代内修** | #2 #4 #5 #11 #12 | 数据正确性、资金安全、发布流程可用性 |
| **可排期** | #3 #6 #7 #13 #15 | 边界场景、性能、埋点准确性 |

---

## 🔴 Critical

### #1 新增的 analyticsExecutor 顶掉了默认业务线程池，全仓 `@Async` 失去线程池

**位置**：`petgo-backend/src/main/java/com/tailtopia/shared/async/AsyncConfig.java:33`

**问题**：`@Bean("analyticsExecutor") TaskExecutor` 是本应用唯一用户自定义的 `Executor` 实现（`TaskExecutor extends java.util.concurrent.Executor`）。Spring Boot 4 的 `TaskExecutorConfigurations.TaskExecutorConfiguration` 由 `OnExecutorCondition` → `@ConditionalOnMissingBean(Executor.class)` 把关（已反编译 `spring-boot-autoconfigure-4.0.6.jar` 确认），且项目未配置任何 `spring.task.execution.*`，因此 **`applicationTaskExecutor` 及其 `AsyncConfigurer` 根本没被创建**。

约 60 个无限定符的 `@Async` 方法（通知投递 `NotificationPusher`、定时推送 `ScheduledPushDispatcher`、昵称/头像/评论审核、里程碑自动完成、注销级联等）因此失去专属池，退化为：
- `SimpleAsyncTaskExecutor` —— 每次调用新建线程、**无上限**；或
- 落到埋点池 —— 其拒绝策略是 `DiscardPolicy`，队列满时**任务被静默丢弃、无任何日志**

**实证**：2026-08-07 部署 staging 时的启动日志已出现该警告：
```
More than one TaskExecutor bean found within the context, and none is named 'taskExecutor'.
Mark one of them as primary or name it 'taskExecutor' in order to use it for async processing:
[analyticsExecutor, taskScheduler]
```

**讽刺点**：该 bean 的 javadoc 本意是「隔离埋点池，避免拖垮业务异步」，实际效果是把业务池整个消灭了。

**修改建议**（二选一）：
1. `application.yml` 加 `spring.task.execution.mode: force` —— 强制创建 `applicationTaskExecutor`，一行解决；
2. 显式声明业务池并标 `@Primary`：
```java
@Bean("applicationTaskExecutor")
@Primary
public TaskExecutor applicationTaskExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setThreadNamePrefix("app-async-");
    e.setCorePoolSize(4);
    e.setMaxPoolSize(16);
    e.setQueueCapacity(500);   // 有界，避免无限堆积
    e.initialize();
    return e;
}
```
**验证方式**：启动日志中上述 WARN 消失；`@Async` 方法内打印 `Thread.currentThread().getName()` 应为业务池前缀而非 `SimpleAsyncTaskExecutor-*`。

---

## 🟠 Major

### #2 人工审核通过后，PRIVATE 日记变成 PUBLIC

**位置**：`petgo-backend/src/main/java/com/tailtopia/content/service/ContentService.java:338`

**问题**：人工审核分支用 `ContentPost.pendingReview(...)` 建对象，**从未调用 `setVisibility(req.visibilityOrPublic())`**，落库为默认 `PUBLIC`。用户关掉「同步到社区」发的日记，一旦命中 `DEGRADED`/`RISKY` 进人工审核，审核通过后 `approveReview` 会把它放进 Feed 并以 `PUBLIC` 发出 `ContentPublishedEvent`（连带错误发放 S5 里程碑）。

**影响**：用户明确选择私密的内容被公开——隐私事故，且用户无感知。

**修改建议**：`pendingReview(...)` 落库前补 `visibility` 赋值，与正常发布分支同源；补一条单测覆盖「PRIVATE + 人工审核 → 审核通过后仍为 PRIVATE」。

### #3 补录日记触发的里程碑会从时间线彻底消失

**位置**：`petgo-backend/src/main/java/com/tailtopia/profile/service/TimelineClassifier.java:101`

**问题**：`linkedContentId != null` 的里程碑被无条件跳过，前提假设是「关联内容在同一批次里、由内容项代为呈现」。但打卡候选是按 `eventDate` 排序的：今天给一张**旧日期**的照片打卡，里程碑与内容会落在不同分页——里程碑在今天这页被抑制，在旧日期那页又不在本次 fetch 范围内。

**影响**：用户完成了里程碑，时间线上却什么都看不到。

**修改建议**：改为「仅当关联内容**确实存在于当前批次**时才跳过」——即以本批内容 id 集合做判定，而非仅看字段非空。

### #4 退款审批不校验订单状态，静默 CAS 空转导致工单永久卡死

**位置**：`petgo-backend/src/main/java/com/tailtopia/admin/support/service/AdminTicketRefundService.java:58`

**问题**：`approveRefundNeed` 从不检查订单状态，而 `markRefundingFromCompleted` 是一个**静默 CAS**（条件不满足就什么也不做、也不报错）。对 `PENDING`/`CANCELLED`/已退款订单执行审批，界面提示成功、`need_decision` 置为 `APPROVED`，但订单纹丝未动 → 该退款条目此后恒 409，工单无法收尾。

**修改建议**：审批前显式校验订单状态；CAS 返回 0 行时抛业务异常（`AppException.conflict`），让后台看到真实原因而不是假成功。

### #5 重新关联订单可绕出第二笔已批准退款

**位置**：`petgo-backend/src/main/java/com/tailtopia/admin/support/service/AdminTicketRefundService.java:44`

**问题**：视图的状态是从**当前关联的订单**推导的。对一张已有已批准退款的工单执行 `relinkOrder`，审批区块会重新出现，从而对一笔无争议订单批出第二笔退款——且可立即经 `refundToPawCoin` 自助到账。

**影响**：资金安全，可被内部滥用。

**修改建议**：`relinkOrder` 前置校验「本工单不存在已批准/进行中的退款」，否则拒绝重关联；或重关联时一并作废旧退款决策并留审计。

### #6 登录事务内同步调腾讯 IM REST，最长占用连接 10 秒

**位置**：`petgo-backend/src/main/java/com/tailtopia/auth/service/AuthService.java:107`

**问题**：`registerImAccount` → `imClient.ensureAccount` 是同步 REST 调用（连接 5s + 读取 5s 超时），且处于 `@Transactional` 的登录方法内。每个新用户注册会把一条 DB 连接钉住最长 10 秒；新加坡 IM 侧一旦劣化，Hikari 连接池会被迅速耗尽。

**修改建议**：移出事务边界（`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`，与既有通知投递同范式）；IM 建号本就是幂等且非阻断的。

### #7 工单列表无分页且每条 N+1 查询

**位置**：`petgo-backend/src/main/java/com/tailtopia/admin/support/service/AdminSupportTicketQueryService.java:49`

**问题**：`list()` 无分页；`toView` 对每条工单额外做 `orders.findById` + `refunds.findByOrderId`（合计 4 查询 × N），而这些数据**列表页并不渲染**。

**修改建议**：列表视图裁掉订单/退款查询（详情页再查）；补分页。

### #8 push/go 的判定发生在 redirect 之前，仍可能 push 到 shell 根路由 → release 白屏

**位置**：`petgo_app/lib/core/push/push_service.dart:261`（孪生问题在 `app_router.dart:188`）

**问题**：`_navigate` 用**重定向前**的 location 决定用 `push` 还是 `go`。游客态或会话未恢复时，指向受控路由的深链会被 router redirect 到 `/home`——而 `/home` 是 shell tab 根，此时已经走了 `push` 分支 → 第二次构建 `StatefulShellRoute` → GlobalKey 撞车 → **release 包白屏，且该 Tab 此后永久失效**（bug 20260729 同款）。PR 之前这条路径用的是 `go`。

**修改建议**：改为「无法证明安全就用 `go`」——例如仅对已知的非 shell 详情路由用 `push`，其余一律 `go`；或在 redirect 之后再判定（监听路由落定后的实际 location）。

### #9 ATT / 通知权限弹窗导致启动屏动画从头重播

**位置**：`petgo_app/lib/main.dart:96`（配合 `splash_page.dart:421`）

**问题**：两个系统弹窗在首帧回调里弹出时 `SplashPage` 仍挂载。弹窗夺焦使 App 转 `inactive`，而 splash 的生命周期处理器把「非 resumed」一律当作进入后台，回前台时**从第 0 帧重播入场动画**——iOS 上因为有两个弹窗会重播两次。

**影响**：冷启动观感明显异常（与本迭代刚做的启动耗时优化直接冲突）。

**修改建议**：splash 的生命周期判定只认 `paused`（真正进后台），忽略 `inactive`（系统弹窗、控制中心下拉等临时失焦）。

### #10 toGuest 延迟执行的 im.logout() 可能清掉下一个账号的 IM 会话

**位置**：`petgo_app/lib/features/auth/domain/auth_state.dart:137`

**问题**：为保证「先反注册推送、再登出 IM」的顺序，`im.logout()` 被压在 `push.unregister().timeout(5s)` 之后。若在这 5 秒窗口内重新登录（Google 账号已缓存的秒登、或 401 强制登录弹窗那条路），**迟到的 `logout()` 会落在账号 B 登录之后，清掉 B 的 IM 凭证**。

同一 bug 在 `app.dart:145` 的换账号分支已正确串行化，`toGuest()` 这条没有——修了一半。

**修改建议**：与 `PushService` 的代际号（epoch）同思路——`toGuest` 记录一个登出代际，`logout()` 执行前比对，代际已变（说明期间发生了新登录）则放弃执行。

### #11 私密日记的成功页却引导「去社区看看」

**位置**：`petgo_app/lib/features/content/presentation/publish_result_page.dart:128`

**问题**：`PublishResultArgs` 不携带可见性标记，`_publish` 恒跳 `/publish/done`。保存一条 `PRIVATE` 日记后，用户看到「发布成功！🎉」「社区已经准备好支持你」，主 CTA 是 **「去社区看看」→ `context.go('/home')`**——而这条内容按定义永远不会出现在该 Feed 里。用户翻找无果，只会认为保存失败。这与上一屏刚展示的「仅自己可见」提示直接矛盾。

**修改建议**：`PublishResultArgs` 增加 `isPrivate` 标记；私密时改文案（「已保存到成长日记」）并把主 CTA 换成「查看日记」→ `/profile`。

### #12 hasPetProfile 过期，发布页默认到一个无法发布的类型

**位置**：`petgo_app/lib/features/content/presentation/publish_compose_page.dart:126`

**问题**：新的「无 preset 时的默认类型」逻辑读 `authControllerProvider.profile.hasPetProfile`。该字段**只在登录响应里写入一次**（`login_response.dart:75`），删除宠物档案时从不重置（`pet_profile_edit_page._deleteProfile` 只 invalidate 了 provider，没碰 auth 状态；`growth_archive_page.dart:104` 甚至已注释说明该标记不可信）。

复现：删除宠物档案 → 不重启 App → 从「健康」或「我的」点「＋」（preset 为 null）→ 发布页默认选中 Diary 且 Diary chip 可用 → 用户写完点分享 → `_ensurePetLoaded()` 返回 null → **发布被拒**。`main` 上无 preset 时默认是 `daily`，本 PR 把一个潜在边界问题提升到了默认路径。

**修改建议**：默认类型改为查询实时的宠物档案 provider（与页面其它地方同源），或删除档案时同步重置 auth 中的该标记。

---

## 🟡 Minor

### #13 只读权限可看到工单 PII

**位置**：`petgo-backend/src/main/java/com/tailtopia/admin/support/web/AdminSupportTicketController.java:45`

**问题**：权限从 `support.handle` 放宽到 `support.view`，使只读授权者可看到原始 `contactValue`（实体 javadoc 标注为 PII）、完整正文与已签名附件 URL。而 service 层 javadoc 仍写着以 `support.handle` 作为正当性依据，文档与实现已不一致。

**修改建议**：明确该放宽是否为有意决策——若是，更新 javadoc 并对 `contactValue` 做脱敏展示；若否，恢复 `support.handle`。

### #14 ATT 轮询 15 秒后放弃返回，破坏「返回即已落定」的契约

**位置**：`petgo_app/lib/core/analytics/att_gate.dart:98`

**问题**：`_waitUntilDetermined` 超时后**正常返回**，此时状态仍可能是 `notDetermined`，而 `main.dart:96` 正是依赖「AttGate 返回即 ATT 已落定」才敢接着请求通知权限。作答慢的用户会命中：通知弹窗盖在还开着的 ATT 弹窗上——**正是这段代码本来要防的 Guideline 2.1 碰撞**。

**修改建议**：让 `requestIfNeeded` 返回是否真正落定；`main.dart` 仅在落定时才请求通知权限，未落定则把通知权限推迟到下一次冷启动（宁可晚一轮，也不能再撞 ATT）。

### #15 AppsFlyer 初始化后移引入竞态，CUID 可能整个会话未设置

**位置**：`petgo_app/lib/main.dart:88`

**问题**：`AppsFlyerClient.init()` 移到 `runApp` 之后，与 auth 监听里的 `Analytics.identifyUser` 形成竞态。`setUserId` 在 `!_initialized` 时**直接 early-return 且不缓冲**，而它只在用户 id **发生变化**时才会重试——一旦抢跑失败，该会话的 CUID 就一直是空的（归因数据缺失）。

**修改建议**：`setUserId` 增加待发缓冲——未初始化时暂存，`init()` 完成后补发。

---

## 未计入上限的次要项

1. **`login_guide_controller.dart:144`**：软登录浮层/弹窗这条路径没有上报 `af_complete_registration`，而本次发版后它已成为主要注册入口 → AppsFlyer 注册数会系统性少计。
2. **`bottom_tab_bar.dart:424`**：`AnimatedScale(scale: 1)` 挂在每次新建的 widget 上，永远不会产生动画——文档描述的「Tab 点击回弹」实际是个空操作。
3. **`diary_guest_page.dart:46`**：曝光事件从 `initState` 触发，而该页是 `indexedStack` 分支根（会被 keep alive），因此每个会话最多只报一次、`session_first` 恒为 `true`。

---

## 评审确认无问题的部分

ARB 键与占位符前后端对齐 · 新增资源路径全部存在 · 6 个 `TimelineItemResponse` 工厂的 16 字段顺序正确 · `TimelineItemType` 前后端字面量一致 · V98/V99/V100 迁移与 schema 兼容 · `findRecentGrowthMomentsByEventDate` / `findFeed` 签名变更的所有调用方已同步 · `AnalyticsDistinctId` 与前端 `analytics.dart:179` 算法一致 · `splash_page.dart` 的 timer/生命周期处理与「落地矩阵 vs 深链」竞态 · 成长日历周日起始偏移 · 成长档案分页 generation 守卫 · `flutter analyze` 零问题
