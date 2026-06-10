# Epic 6 人工测试流程文档 — 推送通知、深链与通知中心

> **文档版本**：基于 PRD V1.0.0（2026-06-08 含 F2/F5/F7/F15/F16 决策）  
> **Story 范围**：6.1 推送基建与深链路由表 · 6.2 兽医回复与兽医端新请求推送 · 6.3 内容互动推送 · 6.4 推送权限申请时机与拒绝处理 · 6.5 App 版本更新提醒 · 6.6 全局通知中心（铃铛）· 6.7 定时类系统推送（生日 / 纪念日 / 里程碑节点）  
> **QA 执行对象**：测试工程师、兼职手工测试人员  
> **验证分层**：每条用例末尾标注 **L0**（静态/单测，无需 DB）/ **L1**（需 Docker pg+redis，`mvn spring-boot:run` actuator=UP）/ **L2**（需真实 FCM/APNs 凭证 + 真机 + IM 凭证）

---

## 范围说明与页面清单

### 涉及页面/文件

| 文件 | 路由 | 所属 Story |
|---|---|---|
| `notification_center_page.dart` | `/notifications` | 6.6 |
| `notification_bell.dart` | 首页顶栏（嵌入） | 6.6 |
| `push_enable_guide.dart` | 「我的」页嵌入组件 | 6.4 |
| `app_update_dialog.dart` | 冷启动弹窗（模态） | 6.5 |
| `push_permission_gate.dart`（domain） | — | 6.4 |
| `app_version_check.dart`（domain） | — | 6.5 |
| `deep_link_routes.dart`（core/router） | — | 6.1 |
| `notification_deep_link.dart`（domain） | — | 6.6 |
| `push_suppression.dart`（domain） | — | 6.2 |
| `milestone_list_page.dart`（壳） | `/profile/milestones` | 6.1 |
| `publish_landing_page.dart` | `/publish?preset=growth-calendar` | 6.1 |

### 深链路由表（FR-38，7 类映射）

| 推送类型 | 落地页路由 | 依赖 token | 来源 Story |
|---|---|---|---|
| `VET_REPLY` | `/consult/conversation/{token}` | 是 | 6.2 |
| `CONSULT_CLOSED` | `/consult/conversation/{token}` | 是 | 6.2 |
| `CONTENT_LIKED` | `/content/{token}` | 是 | 6.3 |
| `CONTENT_COMMENTED` | `/content/{token}?focus=comments` | 是 | 6.3 |
| `NEW_CONSULT_REQUEST` | `/vet/workbench` | 否（固定） | 6.2 |
| `PET_BIRTHDAY` | `/publish?preset=growth-calendar` | 否（固定） | 6.1 / 6.7 |
| `COMPANION_ANNIVERSARY` | `/profile` | 否（固定） | 6.1 / 6.7 |
| `MILESTONE_NODE` | `/profile/milestones` | 否（固定） | 6.1 / 6.7 |
| 未知/空 type | `/notifications`（兜底） | — | 6.1 |

---

## 6.1 推送基建与深链路由表

> **核心验证点**：推送出口写 `notifications` 表 + Redis 角标自增、深链路由七类映射、三态（冷启动/后台/前台）点击直达、目标失效多态兜底。

### 深链路由映射（L0 纯函数）

#### TC-6.1.1 深链映射 — VET_REPLY / CONSULT_CLOSED → 问诊会话

- **关联**：Story 6.1 · FR-38 · AC2
- **页面/入口**：`core/router/deep_link_routes.dart` · `pushPayloadToLocation`
- **前置**：无 DB 依赖（L0 纯函数）
- **步骤**：
  1. 以 `type='VET_REPLY'`, `token='abc123'` 调用 `DeepLinkRoutes.pushPayloadToLocation`
  2. 以 `type='CONSULT_CLOSED'`, `token='xyz789'` 重复调用
- **预期**：分别返回 `/consult/conversation/abc123` 和 `/consult/conversation/xyz789`
- **层级**：L0

#### TC-6.1.2 深链映射 — CONTENT_LIKED → 内容详情

- **关联**：Story 6.1 · FR-38 · AC2
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='CONTENT_LIKED'`, `token='post_tok1'` 调用映射函数
- **预期**：返回 `/content/post_tok1`；无评论区锚点
- **层级**：L0

#### TC-6.1.3 深链映射 — CONTENT_COMMENTED → 内容详情 + 评论区锚点

- **关联**：Story 6.1 · FR-38 · AC2
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='CONTENT_COMMENTED'`, `token='post_tok2'`, `commentAnchor=true` 调用
- **预期**：返回 `/content/post_tok2?focus=comments`；缺少 `commentAnchor` 参数时返回 `/content/post_tok2`（无锚点）
- **层级**：L0

#### TC-6.1.4 深链映射 — NEW_CONSULT_REQUEST → 兽医工作台（固定目标，不依赖 token）

- **关联**：Story 6.1 · FR-38 · AC2
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='NEW_CONSULT_REQUEST'`, `token=null` 调用；再用任意 token 调用
- **预期**：均返回 `/vet/workbench`
- **层级**：L0

#### TC-6.1.5 深链映射 — PET_BIRTHDAY → 发布预选成长日历（固定目标）

- **关联**：Story 6.1 · FR-38 · AC2 · 决策 F2/F5
- **页面/入路**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='PET_BIRTHDAY'`, `token=null` 调用
- **预期**：返回 `/publish?preset=growth-calendar`；即使传入任意 token 结果不变（固定目标类不依赖 token）
- **层级**：L0

#### TC-6.1.6 深链映射 — COMPANION_ANNIVERSARY → 成长档案 Tab（固定目标）

- **关联**：Story 6.1 · FR-38 · AC2 · 决策 F2/F5
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='COMPANION_ANNIVERSARY'`, `token=null` 调用
- **预期**：返回 `/profile`
- **层级**：L0

#### TC-6.1.7 深链映射 — MILESTONE_NODE → 里程碑列表页壳（固定目标）

- **关联**：Story 6.1 · FR-38 · AC2 · 决策 F2/F5
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：以 `type='MILESTONE_NODE'`, `token=null` 调用
- **预期**：返回 `/profile/milestones`
- **层级**：L0

#### TC-6.1.8 深链映射 — 未知 type 兜底落通知中心

- **关联**：Story 6.1 · FR-38 · AC2
- **页面/入口**：`deep_link_routes.dart`
- **前置**：无
- **步骤**：
  1. 以 `type='UNKNOWN_TYPE'`, `token='xyz'` 调用
  2. 以 `type=null`, `token=null` 调用
  3. 以 `type='VET_REPLY'`, `token=null`（token 空）调用
- **预期**：① 返回 `/notifications`；② 返回 `/notifications`；③ 因缺 token 返回 `/notifications`
- **层级**：L0

#### TC-6.1.9 推送 payload 不含顺序 id — 后端统一推送出口结构审查

- **关联**：Story 6.1 · AC1 · 安全护栏
- **页面/入口**：`notify/dto/PushPayload.java`（后端）
- **前置**：后端 `mvn compile` 通过
- **步骤**：审查 `PushPayload` record 字段：仅允许 `type`, `deepLinkToken`, `title`, `body`
- **预期**：无 `id`、无 `targetRef`、无健康数据明文字段；`deepLinkToken` 为 32 位 base62 随机串（非数字自增）
- **层级**：L0

### 后端推送链路（L1）

#### TC-6.1.10 dev 测试推送端点 — notifications 落库 + Redis 角标自增

- **关联**：Story 6.1 · AC1 · B4
- **页面/入口**：`POST /api/v1/notify/_test-push`（`@Profile("dev")`）
- **前置**：Docker pg+redis 运行；`mvn spring-boot:run -Dspring-boot.run.profiles=dev`；已有登录用户 token
- **步骤**：
  1. 以有效 JWT 请求 `POST /api/v1/notify/_test-push`，body 含 `recipientUserId`
  2. 查询 DB：`SELECT * FROM notifications WHERE recipient_user_id = {uid} ORDER BY created_at DESC LIMIT 1`
  3. 查询 Redis：`GET notify:unread:{uid}`
- **预期**：
  - `notifications` 新增一行；`deep_link_token` 为非顺序随机串；`target_ref` 不在响应中外泄
  - Redis 计数比操作前 +1
  - IM 离线投递调用出现在后端日志（可用 stub 或日志断言）
- **层级**：L1

#### TC-6.1.11 生产 profile 不暴露 dev 推送端点

- **关联**：Story 6.1 · AC1 · B4 · 安全护栏
- **页面/入口**：`POST /api/v1/notify/_test-push`
- **前置**：后端以 `prod` profile 启动
- **步骤**：请求 `POST /api/v1/notify/_test-push`
- **预期**：返回 404（路由不注册）；日志无报错；不泄露端点存在信息
- **层级**：L1

### 深链三态真机（L2）

#### TC-6.1.12 冷启动深链 — 点击推送从未启动状态直达目标页

- **关联**：Story 6.1 · AC2 · J2
- **页面/入口**：真机系统通知 → `/consult/conversation/{token}`
- **前置**：真机已授权推送权限；后端 dev 环境 + IM 凭证；App 完全未启动（进程已杀）
- **步骤**：
  1. 从后端触发 `VET_REPLY` 类型测试推送
  2. 真机不开 App，直接从系统通知栏点击推送
- **预期**：App 冷启动后**直接**落到问诊会话页（`/consult/conversation/{token}`），不经过首页；会话页内容正常加载
- **层级**：L2

#### TC-6.1.13 后台切前台深链 — App 在后台时点击推送直达目标页

- **关联**：Story 6.1 · AC2 · J2
- **页面/入口**：真机系统通知 → 目标页
- **前置**：真机已授权推送；App 已启动但在后台
- **步骤**：
  1. 将 App 切到后台（Home 键）
  2. 从系统通知栏点击推送（VET_REPLY 类型）
- **预期**：App 恢复前台并**直接导航**到会话页，不回首页；已有的 App 状态保留
- **层级**：L2

#### TC-6.1.14 前台深链 — App 前台收到推送，Banner 点击直达

- **关联**：Story 6.1 · AC2 · J2
- **页面/入口**：App 内 Banner 通知 → 目标页
- **前置**：真机已授权推送；App 在前台运行（非会话页）
- **步骤**：
  1. 触发 `CONTENT_LIKED` 类型推送
  2. 等待 App 内 Banner 出现，点击 Banner
- **预期**：落到 `/content/{token}` 内容详情页；不白屏
- **层级**：L2

### 目标失效多态兜底（L1 造数据 + L2 验真机）

#### TC-6.1.15 深链目标已删除 — 落详情页 not-found 态，不停首页不白屏

- **关联**：Story 6.1 · AC3 · J3
- **页面/入口**：目标内容详情页 / 问诊会话页（not-found 态）
- **前置**：Docker pg+redis；在 DB 中将 `CONTENT_LIKED` 的目标内容软删除（`deleted_at` 填值）
- **步骤**：
  1. 构造指向已删除内容的推送通知
  2. 点击推送（冷启动或后台）
- **预期**：落到内容详情页，展示 not-found / 已删除态 UI（如「内容已删除」提示）；不停留首页；不白屏；不崩溃
- **层级**：L1（造数据） + L2（真机点击）

#### TC-6.1.16 深链目标会话已关闭 — 落会话页已关闭态

- **关联**：Story 6.1 · AC3 · J3
- **页面/入口**：问诊会话页（已关闭态）
- **前置**：Docker pg；将目标 `consult_sessions.status = 'CLOSED'`
- **步骤**：构造指向该会话 token 的 `VET_REPLY` 推送，点击
- **预期**：落到会话页展示「问诊已结束」态；无白屏；不回首页
- **层级**：L1 + L2

---

## 6.2 兽医回复与兽医端新请求推送

> **核心验证点**：兽医回复→用户推送（含正在查看抑制）、新请求→在线兽医筛选推送（离线不收）、深链落点。

### 抑制逻辑（L0）

#### TC-6.2.1 正在查看抑制 — 当前激活会话与推送目标相同时不弹 Banner

- **关联**：Story 6.2 · AC1 · F1
- **页面/入口**：`push_suppression.dart` · `PushSuppression.shouldSuppressInApp`
- **前置**：无 DB 依赖（L0 纯函数）
- **步骤**：
  1. 设 `activeSessionId = 'session_tok1'`，推送 `type=VET_REPLY`, `token='session_tok1'`
  2. 设 `activeSessionId = 'session_tok1'`，推送 `type=VET_REPLY`, `token='session_tok2'`（不同会话）
  3. 设 `activeSessionId = null`（未在任何会话页）
- **预期**：① 应抑制（`shouldSuppressInApp = true`）；② 不抑制；③ 不抑制
- **层级**：L0

#### TC-6.2.2 CONSULT_CLOSED 推送对当前激活会话也抑制 Banner

- **关联**：Story 6.2 · AC1 · F1
- **页面/入口**：`push_suppression.dart`
- **前置**：无
- **步骤**：设 `activeSessionId = 'session_tok1'`，推送 `type=CONSULT_CLOSED`, `token='session_tok1'`
- **预期**：`shouldSuppressInApp = true`
- **层级**：L0

#### TC-6.2.3 在线兽医筛选 — 离线兽医不加入推送名单（L0 单测）

- **关联**：Story 6.2 · AC2 · B2
- **页面/入口**：`ConsultNotifyListener.onConsultRequestQueued`（后端）
- **前置**：`mvn test` 通过
- **步骤**：审查 `ConsultNotifyListenerTest`：构造 2 在线 + 1 离线兽医，触发 `ConsultRequestQueuedEvent`
- **预期**：`NotificationService.send` 仅被调用 2 次（在线兽医）；离线兽医 ID 不出现在 send 调用参数中
- **层级**：L0

### 推送链路（L1）

#### TC-6.2.4 兽医回复后用户侧生成 notification 行 + 角标自增

- **关联**：Story 6.2 · AC1 · B1
- **页面/入口**：`POST /api/v1/vet/consult-sessions/{id}/notify-reply`（dev 触发端点）
- **前置**：Docker pg+redis；已有进行中 `consult_sessions`；兽医 JWT + 用户 JWT
- **步骤**：
  1. 兽医 JWT 请求 `POST /api/v1/vet/consult-sessions/{id}/notify-reply`
  2. 查询 `notifications` 表：`WHERE type = 'VET_REPLY' AND recipient_user_id = {userId}`
  3. 查询 Redis `notify:unread:{userId}`
- **预期**：`notifications` 新增一行，`type=VET_REPLY`，`deep_link_token` 非顺序 id；Redis 角标 +1；IM 离线投递日志出现
- **层级**：L1

#### TC-6.2.5 新请求推送 — 在线兽医收到，离线兽医不收

- **关联**：Story 6.2 · AC2 · B2 · J2
- **页面/入口**：consult 提交接口 → `notify` 模块
- **前置**：Docker pg+redis；Redis 中造 2 在线兽医（`vet:presence:{vetId} = online`）+ 1 离线兽医（键不存在或 `offline`）
- **步骤**：
  1. 用户提交咨询请求（进入 WAITING 状态）
  2. 查询 `notifications` 表：`WHERE type = 'NEW_CONSULT_REQUEST'`
  3. 查询 Redis 各兽医角标
- **预期**：2 个在线兽医各生成 1 条 `notifications` 行（类型 `NEW_CONSULT_REQUEST`）；离线兽医无对应行；角标仅在线兽医自增
- **层级**：L1

### 真机端到端（L2）

#### TC-6.2.6 兽医回复 — 用户真机收到推送，点击直达会话页

- **关联**：Story 6.2 · AC1 · J1
- **页面/入口**：真机系统通知 → `/consult/conversation/{token}`
- **前置**：真机已授权推送；App 在后台；IM 凭证 live 模式
- **步骤**：
  1. 兽医在会话中回复（通过 IM 或 dev 端点触发）
  2. 用户真机后台接收推送
  3. 点击推送通知
- **预期**：落到对应问诊会话页；显示兽医最新消息；无重复推送
- **层级**：L2

#### TC-6.2.7 用户正在查看会话时兽医回复 — 不出现重复推送 Banner

- **关联**：Story 6.2 · AC1 · J1（抑制路径）
- **页面/入口**：问诊会话页（前台激活）
- **前置**：真机；用户在会话页前台；兽医发送回复
- **步骤**：用户打开对应会话页，兽医在该会话发送消息
- **预期**：App 内**不弹** Banner 通知；IM 实时消息正常显示在聊天界面；不打扰用户
- **层级**：L2

#### TC-6.2.8 在线兽医真机 — 收到新请求推送，点击跳工作台待接单

- **关联**：Story 6.2 · AC2 · J2
- **页面/入口**：真机 → 兽医工作台 `/vet/workbench`（待接单 Tab）
- **前置**：兽医账号真机已授权推送；兽医 Redis 在线态有效
- **步骤**：用户提交咨询请求；兽医真机点击「新的问诊请求，点击查看」推送
- **预期**：落到兽医工作台，聚焦待接单列表；该请求出现在列表中
- **层级**：L2

---

## 6.3 内容互动推送

> **核心验证点**：被赞/被评推送（含评论区定位）、自互动不推、逐条不合并。

### 自互动过滤 + 逐条（L0 / L1）

#### TC-6.3.1 自互动过滤 — 作者对自己内容点赞/评论不触发推送（L0）

- **关联**：Story 6.3 · AC2 · B3
- **页面/入口**：`ContentNotifyListenerTest`（后端单测）
- **前置**：`mvn test` 通过
- **步骤**：审查 `ContentNotifyListenerTest`：`ContentLikedEvent{likerId == authorId}` 触发；`ContentCommentedEvent{commenterId == contentAuthorId}` 触发
- **预期**：两种情况下 `NotificationService.send` 均**不被调用**
- **层级**：L0

#### TC-6.3.2 逐条不合并 — 同内容连续 3 次他人点赞产生 3 条独立通知（L1）

- **关联**：Story 6.3 · AC2 · B4
- **页面/入口**：`notifications` 表
- **前置**：Docker pg+redis；3 个不同用户对同一内容连续点赞
- **步骤**：
  1. 用户 A/B/C 依次对作者内容点赞
  2. 查询 `notifications` 表：`WHERE type='CONTENT_LIKED' AND recipient_user_id = {authorId}`
- **预期**：查询到 3 条独立行，`created_at` 不同；无合并/聚合
- **层级**：L1

#### TC-6.3.3 被点赞推送 — notifications 落库 + 角标自增（L1）

- **关联**：Story 6.3 · AC1 · B1
- **页面/入口**：内容点赞 API → notify 模块
- **前置**：Docker pg+redis；他人对作者内容点赞（用户 B 点赞用户 A 的内容）
- **步骤**：
  1. 用户 B 调用点赞接口（或直接向 DB 插入 `ContentLikedEvent` 并触发）
  2. 查询 `notifications` 表
  3. 查询 Redis `notify:unread:{authorId}`
- **预期**：`type=CONTENT_LIKED`，`recipient_user_id=authorId`；`deep_link_token` 为非顺序 token；角标 +1；日志无 PII
- **层级**：L1

#### TC-6.3.4 被评论 — notifications 落库，deep_link_token 映射到评论区（L1）

- **关联**：Story 6.3 · AC1 · B2
- **页面/入口**：评论提交接口 → notify 模块
- **前置**：Docker pg+redis；用户 B 评论用户 A 的内容
- **步骤**：
  1. 用户 B 发表评论
  2. 查询 `notifications` 表：`WHERE type='CONTENT_COMMENTED'`
- **预期**：`type=CONTENT_COMMENTED`，落库一行；`deep_link_token` 非顺序；前端点击后应走 `CONTENT_COMMENTED→/content/{token}?focus=comments` 映射
- **层级**：L1

### 真机端到端（L2）

#### TC-6.3.5 被点赞真机推送 — 落内容详情页（L2）

- **关联**：Story 6.3 · AC1 · J1
- **页面/入口**：系统通知 → `/content/{token}`
- **前置**：真机已授权推送；目标内容存在
- **步骤**：他人点赞作者内容；作者真机收到「有人赞了你的内容」推送；点击
- **预期**：落到内容详情页（`/content/{token}`）；内容正常展示；推送文案为 en/id 双语正确文案（**不含中文**）
- **层级**：L2

#### TC-6.3.6 被评论真机推送 — 落内容详情页并滚动定位到评论区（L2）

- **关联**：Story 6.3 · AC1 · J1
- **页面/入口**：系统通知 → `/content/{token}?focus=comments`
- **前置**：真机；目标内容已有评论
- **步骤**：他人评论；作者真机点击「有人评论了你的内容，点击查看」推送
- **预期**：落到详情页，**页面自动滚动**到评论区并聚焦；无需手动滚动
- **层级**：L2

#### TC-6.3.7 失效内容兜底 — 已软删除内容的推送点击，展示 not-found 态

- **关联**：Story 6.3 · AC1 · F2
- **页面/入口**：内容详情页（not-found 态）
- **前置**：目标内容 `deleted_at` 已填（软删）
- **步骤**：点击指向已删内容的 `CONTENT_LIKED` 推送
- **预期**：落详情页展示「内容已删除」或 not-found 态；不白屏；不回首页
- **层级**：L1（造数据） + L2（真机点击）

---

## 6.4 推送权限申请时机与拒绝处理

> **核心验证点**：双时机取最早（首次问诊后 OR 建档后且从未问诊）、仅弹一次、建档时机在庆祝页后触发（F15）、拒绝后被动引导。

### 时机门控逻辑（L0）

#### TC-6.4.1 首启不弹推送权限弹窗

- **关联**：Story 6.4 · AC1 · F1 · FR-22D
- **页面/入口**：`PushPermissionGate.shouldRequest`
- **前置**：无
- **步骤**：调用 `shouldRequest(alreadyAsked=false, firstConsultDone=false, profileCreated=false, neverConsulted=false)`
- **预期**：返回 `false`（绝不首启请求）
- **层级**：L0

#### TC-6.4.2 首次问诊完成 → 应触发推送权限申请

- **关联**：Story 6.4 · AC1 · F1 · 决策 F7
- **页面/入口**：`PushPermissionGate.shouldRequest`
- **前置**：无
- **步骤**：`shouldRequest(alreadyAsked=false, firstConsultDone=true)`
- **预期**：返回 `true`
- **层级**：L0

#### TC-6.4.3 建档完成且从未问诊 → 应触发推送权限申请

- **关联**：Story 6.4 · AC1 · F1 · 决策 F7
- **页面/入口**：`PushPermissionGate.shouldRequest`
- **前置**：无
- **步骤**：`shouldRequest(alreadyAsked=false, profileCreated=true, neverConsulted=true)`
- **预期**：返回 `true`
- **层级**：L0

#### TC-6.4.4 已问诊用户建档完成 → 不重复触发（仅问诊时机负责）

- **关联**：Story 6.4 · AC1 · F1 · 决策 F7
- **页面/入口**：`PushPermissionGate.shouldRequest`
- **前置**：无
- **步骤**：`shouldRequest(alreadyAsked=false, profileCreated=true, neverConsulted=false)`（已问诊用户）
- **预期**：返回 `false`（`neverConsulted=false` 限定，避免重复触发）
- **层级**：L0

#### TC-6.4.5 已问过权限（任一时机弹过）→ 不再触发

- **关联**：Story 6.4 · AC1 · F1
- **页面/入口**：`PushPermissionGate.shouldRequest`
- **前置**：无
- **步骤**：
  1. `shouldRequest(alreadyAsked=true, firstConsultDone=true)` 调用
  2. `shouldRequest(alreadyAsked=true, profileCreated=true, neverConsulted=true)` 调用
- **预期**：两次均返回 `false`（取最早仅一次，`alreadyAsked` 保证全局唯一）
- **层级**：L0

#### TC-6.4.6 拒绝后状态持久化 — `pushPermissionAsked=true` 写入 prefs，后续不主动弹

- **关联**：Story 6.4 · AC1 · F2
- **页面/入口**：`PushPermissionGate._maybeRequest` → `AppPrefs.pushPermissionAsked`
- **前置**：无
- **步骤**：注入 fake `requestSystemPermission` 返回 `false`（用户拒绝），调用 `maybeRequestAfterFirstConsult(firstConsultDone:true)`；之后再调 `shouldRequest(...)`
- **预期**：`pushPermissionAsked` 被置 `true`；后续 `shouldRequest` 返回 `false`（不再主动弹）
- **层级**：L0

### 建档时机弹出位置对齐庆祝页（F15）

#### TC-6.4.7 建档时机弹出位置 — 庆祝页主 CTA「开始探索」后触发，而非建档保存瞬间

- **关联**：Story 6.4 · AC3 · F1b · 决策 F15
- **页面/入口**：`app_router.dart` `/profile/created` 路由 `onStartExplore` 闭包；`push_build_timing_test.dart`
- **前置**：无（L0 widget 测）
- **步骤**：
  1. 用新建档用户（`neverConsulted=true`）进入庆祝页（`/profile/created`）
  2. 点击主 CTA「Start exploring」（en）/「Mulai jelajah」（id）
- **预期**：先调用 `gate.maybeRequestAfterProfileCreated`（触发权限申请），**之后**执行 `router.go('/home')`；权限申请在跳首页之前完成（`await` 顺序保证）
- **层级**：L0（时序锁定测试）/ L2（真机视觉时序）

#### TC-6.4.8 已问过权限时建档庆祝页「开始探索」不再弹，直接进首页

- **关联**：Story 6.4 · AC3 · F1b
- **页面/入口**：庆祝页 `onStartExplore`
- **前置**：`prefs.pushPermissionAsked = true`（已问过）
- **步骤**：点击庆祝页「开始探索」CTA
- **预期**：直接进首页（`router.go('/home')`）；不弹任何权限弹窗；`requestSystemPermission` **不被调用**
- **层级**：L0

#### TC-6.4.9 首次问诊完成时机 — 在问诊结果页关闭后立即弹（不经庆祝页）

- **关联**：Story 6.4 · AC3 · F1b
- **页面/入口**：问诊结果页关闭逻辑 + `maybeRequestAfterFirstConsult`
- **前置**：用户首次完成问诊且 `pushPermissionAsked=false`
- **步骤**：关闭问诊结果页（或关闭评分弹窗）
- **预期**：立即弹推送软引导，文案为「Turn on notifications so we can alert you the moment a vet replies」（en）/「Aktifkan notifikasi agar kami bisa memberi tahu begitu dokter hewan membalas」（id）；不经庆祝页
- **层级**：L0（门控逻辑）/ L2（真机弹窗验证）

### 拒绝后引导（L2）

#### TC-6.4.10 真机拒绝推送权限后「我的」页显示被动引导组件

- **关联**：Story 6.4 · AC1 · F3
- **页面/入口**：「我的」页 → `PushEnableGuide`（`push_enable_guide.dart`）
- **前置**：真机；已拒绝推送权限；App 未授权推送
- **步骤**：进入「我的」页
- **预期**：显示「Enable notifications」（en）/「Aktifkan notifikasi」（id）引导卡片；显示「去设置」按钮（`mediaOpenSettings` i18n key）
- **层级**：L2

#### TC-6.4.11 被动引导「去设置」按钮跳转系统通知设置页

- **关联**：Story 6.4 · AC1 · F3
- **页面/入口**：`PushEnableGuide` → `openPushSettings`
- **前置**：真机
- **步骤**：在「我的」页点击推送引导的「去设置」按钮
- **预期**：跳转到系统**通知设置**页（iOS：「设置 > 通知 > TailTopia」；Android：App 通知管理）；App 不崩溃
- **层级**：L2

#### TC-6.4.12 相机/相册被拒后再上传 — 弹统一引导

- **关联**：Story 6.4 · AC2 · F4 · 决策 F4
- **页面/入口**：AI 问诊上传照片 / 档案头像上传 / 成长日历上传
- **前置**：真机；相册权限已拒
- **步骤**：再次触发上传（三类场景任一）
- **预期**：弹「需要相册权限才能上传，请前往设置开启」（对齐 Story 2.1 样式）+ 「去设置」跳系统相册权限页；**无**「兽医咨询发送图片/视频」场景（V1.0.0 已删除该触发场景）
- **层级**：L2

---

## 6.5 App 版本更新提醒

> **核心验证点**：冷启动三态判定（无更新/推荐/强制）、强制不可跳过、跳平台商店、不走推送/不需推送权限。

### 版本比对逻辑（L0）

#### TC-6.5.1 版本三态判定 — 强制更新（当前 < minSupported）

- **关联**：Story 6.5 · AC1 · F1 · `AppVersionCheck.decide`
- **页面/入口**：`app_version_check.dart`
- **前置**：无
- **步骤**：`AppVersionCheck.decide(current='1.0.0', latest='2.0.0', minSupported='1.5.0')`
- **预期**：返回 `UpdateDecision.forced`（`1.0.0 < 1.5.0`）
- **层级**：L0

#### TC-6.5.2 版本三态判定 — 推荐更新（minSupported ≤ 当前 < latest）

- **关联**：Story 6.5 · AC1 · F1
- **页面/入口**：`app_version_check.dart`
- **前置**：无
- **步骤**：`AppVersionCheck.decide(current='1.5.0', latest='2.0.0', minSupported='1.5.0')`
- **预期**：返回 `UpdateDecision.recommended`（`1.5.0 >= 1.5.0`，`1.5.0 < 2.0.0`）
- **层级**：L0

#### TC-6.5.3 版本三态判定 — 无更新（当前 ≥ latest）

- **关联**：Story 6.5 · AC1 · F1
- **页面/入口**：`app_version_check.dart`
- **前置**：无
- **步骤**：`AppVersionCheck.decide(current='2.0.0', latest='2.0.0', minSupported='1.5.0')`
- **预期**：返回 `UpdateDecision.none`；无弹窗
- **层级**：L0

#### TC-6.5.4 语义版本比较 — 补丁号边界（1.0.9 vs 1.0.10）

- **关联**：Story 6.5 · AC1 · F1 · `AppVersionCheck.compareSemver`
- **页面/入口**：`app_version_check.dart`
- **前置**：无
- **步骤**：`AppVersionCheck.compareSemver('1.0.9', '1.0.10')`
- **预期**：返回 `-1`（`1.0.9 < 1.0.10`，避免字典序误判）
- **层级**：L0

#### TC-6.5.5 版本弹窗 — 不调用任何推送权限 API

- **关联**：Story 6.5 · AC2 · F4
- **页面/入口**：`app_update_dialog.dart` 代码审查
- **前置**：无
- **步骤**：审查 `AppUpdateDialog` 文件，搜索 `permission_handler` / `requestPermission` / `pushPermission` 关键字
- **预期**：无任何推送权限相关调用；弹窗纯为 App 内 UI + `url_launcher` 跳商店，与推送链路完全独立
- **层级**：L0

### 弹窗 UI 与商店跳转（L1/L2）

#### TC-6.5.6 后端版本端点公开可读 — 游客无需 JWT

- **关联**：Story 6.5 · AC1 · B1 · J1
- **页面/入口**：`GET /api/v1/app-version`
- **前置**：Docker pg；后端运行；不携带 JWT
- **步骤**：`curl -s http://localhost:8080/api/v1/app-version`
- **预期**：返回 HTTP 200；JSON 包含 `latestVersion`, `minSupportedVersion`, `iosStoreUrl`, `androidStoreUrl`；HTTP 401/403 不出现
- **层级**：L1

#### TC-6.5.7 版本端点超时时客户端默认放行启动，不阻断

- **关联**：Story 6.5 · AC1 · F1 · 安全护栏
- **页面/入口**：`AppVersionRepository.fetch` 失败路径
- **前置**：版本端点不可达（断网 / mock 返回 500）
- **步骤**：在 mock 或本地测试中让 `fetch` 抛出异常
- **预期**：返回 `null`；冷启动继续进入主功能，不弹弹窗，不阻断；无崩溃
- **层级**：L0（单测 mock）/ L1（真实超时）

#### TC-6.5.8 推荐更新弹窗 — 可「稍后」，下次冷启动继续提示

- **关联**：Story 6.5 · AC1 · F2
- **页面/入口**：`AppUpdateDialog`（`recommended` 态）
- **前置**：版本端点返回 `recommended` 态（`current < latest, current >= minSupported`）；真机或集成测试
- **步骤**：
  1. 冷启动触发推荐更新弹窗，出现「A new version is available」（en）/「Versi baru tersedia」（id）
  2. 点击「Later」（en）/「Nanti」（id）
  3. 关闭 App，再次冷启动
- **预期**：① 弹窗正常展示，含「Later」和「Update now」按钮；② 点「Later」弹窗关闭，App 可正常使用；③ 下次冷启动**继续**提示（不持久化忽略）
- **层级**：L1（本地版本端点） + L2（真机冷启动两次）

#### TC-6.5.9 强制更新弹窗 — 不可跳过，拦截返回键/点遮罩

- **关联**：Story 6.5 · AC1 · F3
- **页面/入口**：`AppUpdateDialog`（`forced` 态，`key='appUpdateForced'`）
- **前置**：版本端点返回 `forced` 态（`current < minSupported`）；真机/集成
- **步骤**：
  1. 冷启动触发强制更新弹窗，出现「Update required to continue」（en）/「Perlu pembaruan untuk lanjut」（id）
  2. 点击弹窗背景遮罩（尝试关闭）
  3. 按返回键/后退手势
  4. 观察是否出现「Later」按钮
- **预期**：① 弹窗出现，仅含「Update now」按钮，**无「Later」按钮**；② 点遮罩无反应（`barrierDismissible=false`）；③ 返回键/手势无反应（`PopScope canPop=false`）；App 功能**阻断**（无法进入主内容）
- **层级**：L1 + L2（真机验物理键）

#### TC-6.5.10 跳商店 — iOS 跳 App Store，Android 跳 Google Play

- **关联**：Story 6.5 · AC2 · F4 · J3
- **页面/入口**：`AppUpdateDialog` 「Update now」按钮 → `url_launcher`
- **前置**：iOS 真机 / Android 真机；弹窗出现
- **步骤**：点击「Update now」/「Perbarui sekarang」
- **预期**：iOS 真机跳 App Store（对应 App 页）；Android 跳 Google Play；全程无推送权限弹窗
- **层级**：L2

---

## 6.6 全局通知中心（铃铛）

> **核心验证点**：铃铛角标、通知中心列表（倒序/多类/空态/已读）、越权保护、里程碑零态降级、推送直跳已读同步。

### 铃铛角标（L0 / L1）

#### TC-6.6.1 铃铛角标 — 零未读时隐藏角标

- **关联**：Story 6.6 · AC1 · FR-34
- **页面/入口**：首页顶栏 `NotificationBell`（`notification_bell.dart`）
- **前置**：`unreadCount = 0`（L0 widget test）
- **步骤**：渲染 `NotificationBell` 时注入 `unreadCount=0`
- **预期**：不渲染 `notificationBadge`（key 不存在于 widget tree）
- **层级**：L0

#### TC-6.6.2 铃铛角标 — 有未读时显示数字角标

- **关联**：Story 6.6 · AC1 · FR-34
- **页面/入口**：`NotificationBell`
- **前置**：`unreadCount = 5`
- **步骤**：渲染时注入 `unreadCount=5`
- **预期**：`notificationBadge` 存在，文字为「5」，背景色为 `AppColors.danger`（红色）
- **层级**：L0

#### TC-6.6.3 铃铛角标 — 超 99 显示「99+」

- **关联**：Story 6.6 · AC1 · FR-34
- **页面/入口**：`NotificationBell`
- **前置**：`unreadCount = 150`
- **步骤**：渲染时注入 `unreadCount=150`
- **预期**：角标文字为「99+」
- **层级**：L0

#### TC-6.6.4 铃铛角标与问诊 Tab 红点并存不互斥（L1）

- **关联**：Story 6.6 · AC1 · FR-19
- **页面/入口**：首页（顶栏铃铛 + 底部 Tab Bar 问诊红点）
- **前置**：Docker pg+redis；有未读通知 + 有问诊 Tab 未处理请求
- **步骤**：制造有未读通知 + 问诊 Tab 有红点状态，进入首页
- **预期**：铃铛角标和问诊 Tab 红点**同时**出现；两者独立，互不影响
- **层级**：L1

#### TC-6.6.5 标记已读后角标递减，清空后角标隐藏（L1）

- **关联**：Story 6.6 · AC2 · B4
- **页面/入口**：`POST /api/v1/notifications/{token}/read` → Redis `notify:unread:{userId}`
- **前置**：Docker pg+redis；3 条未读通知
- **步骤**：
  1. 标记 1 条已读，查 Redis 计数
  2. 标记全部已读（`POST /notifications/read-all`），查 Redis 计数
- **预期**：① 角标从 3 变 2；② 角标变 0，铃铛不显示角标；DECR 不低于 0（夹 0 保护）
- **层级**：L1

### 通知中心列表（L0 / L1）

#### TC-6.6.6 通知中心 — 空账号显示空态，不白屏

- **关联**：Story 6.6 · AC2 · F2
- **页面/入口**：`NotificationCenterPage`（`/notifications`）
- **前置**：账号无任何通知
- **步骤**：进入通知中心页
- **预期**：展示空态（`key='notificationEmpty'`，图标 `notifications_none`，文字「No notifications yet」(en) / 「Belum ada notifikasi」(id)）；不白屏；无报错
- **层级**：L0（widget test）/ L1（真实空账号）

#### TC-6.6.7 通知中心列表 — 六类通知各自显示正确图标与文案标签

- **关联**：Story 6.6 · AC2 · F2
- **页面/入口**：`NotificationCenterPage` → `_NotificationTile`
- **前置**：Docker pg；造六类通知各一条（VET_REPLY / CONTENT_LIKED / CONTENT_COMMENTED / NEW_CONSULT_REQUEST / PET_BIRTHDAY / COMPANION_ANNIVERSARY）
- **步骤**：进入通知中心，检查各条目
- **预期**：
  - `VET_REPLY` → `medical_services_outlined`，「Vet replied」(en)/「Dokter hewan membalas」(id)
  - `CONTENT_LIKED` → `favorite_border`，「New like」(en)/「Suka baru」(id)
  - `CONTENT_COMMENTED` → `mode_comment_outlined`，「New comment」(en)/「Komentar baru」(id)
  - `NEW_CONSULT_REQUEST` → `inbox_outlined`，「New consultation request」(en)/「Permintaan konsultasi baru」(id)
  - `PET_BIRTHDAY` → `cake_outlined`，「Pet birthday」(en)/「Ulang tahun hewan」(id)
  - `COMPANION_ANNIVERSARY` → `celebration_outlined`，「Companion anniversary」(en)/「Hari jadi kebersamaan」(id)
  - 所有文案**不含中文字符**
- **层级**：L0（widget test）/ L1（真实数据）

#### TC-6.6.8 通知中心列表 — 按 created_at 倒序排列

- **关联**：Story 6.6 · AC2 · B1
- **页面/入口**：`GET /api/v1/notifications`
- **前置**：Docker pg；造 3 条通知，`created_at` 间隔 1 分钟
- **步骤**：请求列表接口，检查返回顺序
- **预期**：最新通知排第一；`created_at` 严格倒序；`target_ref`、顺序 `id` 不出现在响应 JSON
- **层级**：L1

#### TC-6.6.9 未读条目高亮 — 未读加粗 + 红色圆点，已读无圆点

- **关联**：Story 6.6 · AC2 · F2
- **页面/入口**：`_NotificationTile`（`notification_center_page.dart`）
- **前置**：有至少 1 条未读 + 1 条已读通知
- **步骤**：渲染通知列表
- **预期**：未读项：标题加粗（`fontWeight: FontWeight.w700`）+ 右侧红色圆点（8px 红色 `BoxDecoration`）；已读项：常规字重，无圆点
- **层级**：L0（widget test）/ L1

#### TC-6.6.10 点击条目 — 标记已读 + 深链跳目标页

- **关联**：Story 6.6 · AC2 · F3
- **页面/入口**：`NotificationCenterPage._onTap` → `NotificationDeepLink.open` → 目标页
- **前置**：Docker pg+redis；有未读 `VET_REPLY` 通知；问诊会话存在
- **步骤**：点击通知列表中「Vet replied」条目
- **预期**：调用 `POST /notifications/{token}/read`；`is_read=true`；角标 -1；路由跳至 `/consult/conversation/{token}`；该条目更新为已读态（无红点）
- **层级**：L1 + L2（真机导航）

#### TC-6.6.11 点击「Companion anniversary」条目 → 成长档案 Tab（固定目标）

- **关联**：Story 6.6 · AC2 · F3 · 决策 F2
- **页面/入口**：`NotificationCenterPage` → `/profile`
- **前置**：有 `COMPANION_ANNIVERSARY` 通知条目
- **步骤**：点击「Companion anniversary」条目
- **预期**：跳转到 `/profile`（成长档案 Tab）；`is_read` 更新；角标 -1；不依赖 deepLinkToken（固定目标类）
- **层级**：L1 + L2

#### TC-6.6.12 点击「Pet birthday」条目 → 发布页预选成长日历（固定目标）

- **关联**：Story 6.6 · AC2 · F3 · 决策 F2
- **页面/入口**：`NotificationCenterPage` → `/publish?preset=growth-calendar`
- **前置**：有 `PET_BIRTHDAY` 通知条目；已登录（`/publish` 属受控路由）
- **步骤**：点击「Pet birthday」/「Ulang tahun hewan」条目
- **预期**：跳转到发布页并预选成长日历类型；`is_read` 更新；不依赖 token
- **层级**：L1 + L2

### L级里程碑条目零态降级（L0）

#### TC-6.6.13 里程碑零态 — 无 MILESTONE_NODE 数据时不渲染旗帜图标/空壳

- **关联**：Story 6.6 · AC3 · F2b · 决策 F16
- **页面/入口**：`NotificationCenterPage`
- **前置**：通知列表中无 `MILESTONE_NODE` 类型条目（6.7 暂不写该类型）
- **步骤**：渲染通知列表（含其他类型条目）
- **预期**：列表中**不出现** `flag_outlined` 图标或空壳占位；其他类型正常渲染；无报错
- **层级**：L0（widget test 锁定）

#### TC-6.6.14 里程碑有数据时显示旗帜图标和「Milestone reached」文案

- **关联**：Story 6.6 · AC3 · F2b
- **页面/入口**：`_NotificationTile`（type='MILESTONE_NODE'）
- **前置**：造一条 `MILESTONE_NODE` 通知（后续 F16 里程碑 mini-epic 产生）
- **步骤**：渲染含 `MILESTONE_NODE` 的通知列表
- **预期**：`flag_outlined` 图标；「Milestone reached」(en)/「Tonggak tercapai」(id) 标签；点击跳 `/profile/milestones`
- **层级**：L0（mock 数据）/ L1（真实 Epic 8 数据）

### 推送直跳已读同步（L0 / L2）

#### TC-6.6.15 系统推送直跳目标页 — 通知中心对应条目同步标记已读，角标重算

- **关联**：Story 6.6 · AC3 · F2b · `NotificationDeepLink.open`
- **页面/入口**：`notification_deep_link.dart` → `markRead` + `unreadCountProvider.invalidate`
- **前置**：有 3 条未读通知；其中 1 条为 `PET_BIRTHDAY`（token=`tok_bday`）
- **步骤**：
  1. 模拟系统推送直跳：调用 `NotificationDeepLink.open(ref, type='PET_BIRTHDAY', token='tok_bday')`（不经通知列表点击）
  2. 查看 `unreadCountProvider` 值
  3. 进入通知中心，查看对应条目状态
- **预期**：`markRead('tok_bday')` 被调用；`unreadCountProvider` invalidate 后重算为 2；通知中心对应条目为已读态（无红点）；返回位置 `/publish?preset=growth-calendar`
- **层级**：L0（provider 测试）/ L2（真机回通知中心验已读）

### 安全越权保护（L1）

#### TC-6.6.16 越权标记已读 — 只能标记自己的通知，他人通知返回 404

- **关联**：Story 6.6 · AC2 · B3
- **页面/入口**：`POST /api/v1/notifications/{token}/read`
- **前置**：Docker pg；用户 A 的通知 token `tok_A`；用户 B 的 JWT
- **步骤**：用户 B 的 JWT 请求 `POST /api/v1/notifications/tok_A/read`
- **预期**：返回 HTTP 404（防枚举，不泄露是否存在）；`is_read` 不变；无 403 但不返回 200
- **层级**：L1

#### TC-6.6.17 Redis 角标键缺失时按库回算 unread-count

- **关联**：Story 6.6 · AC1 · B2
- **页面/入口**：`GET /api/v1/notifications/unread-count`
- **前置**：Docker pg+redis；删除 Redis `notify:unread:{userId}` 键；DB 中有 2 条 `is_read=false`
- **步骤**：请求 `GET /api/v1/notifications/unread-count`
- **预期**：返回 `{"unreadCount":2}`（按库 `is_read=false` 回算）；Redis 回填后再次请求仍返回 2
- **层级**：L1

---

## 6.7 定时类系统推送（生日 / 纪念日 / 里程碑节点）

> **核心验证点**：`@Scheduled` 每日扫描三类节点、DB 去重标记（唯一约束防重）、2/29 生日兜底、节点边界精准、范围守护（不写里程碑本体），F5 硬约束遵守。

### 扫描逻辑（L0 金标单测）

#### TC-6.7.1 生日扫描 — 明天是生日，本年未推 → 命中投递

- **关联**：Story 6.7 · AC1 · B3 · `ScheduledPushPlannerTest`
- **页面/入口**：`ScheduledPushPlanner.plan`（后端）
- **前置**：`mvn test` 通过（`ScheduledPushPlannerTest` 11 绿）
- **步骤**：注入 `today=2026-06-09`，档案 `birthday=2000-06-10`，`existingKeys` 不含该宠物该年度
- **预期**：Planner 返回 1 条 `PlannedPush{type=PET_BIRTHDAY, nodeKey='2026'}`
- **层级**：L0

#### TC-6.7.2 生日扫描 — 生日字段为空，不触发

- **关联**：Story 6.7 · AC1 · B3
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：注入档案 `birthday=null`
- **预期**：Planner 返回空集合（不触发）
- **层级**：L0

#### TC-6.7.3 生日扫描 — 今天非生日前一天，不触发

- **关联**：Story 6.7 · AC1 · B3
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：注入 `today=2026-06-08`，`birthday=2000-06-10`（明天是 9 号，不是 10 号）
- **预期**：Planner 返回空集合
- **层级**：L0

#### TC-6.7.4 生日扫描 — 本年已推过，不重复推送（DB 去重）

- **关联**：Story 6.7 · AC1 · B3
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：`existingKeys` 含该宠物 `(petProfileId, PET_BIRTHDAY, '2026')`
- **步骤**：注入命中条件，但去重集合已包含该键
- **预期**：Planner 返回空集合（跳过，不重复）
- **层级**：L0

#### TC-6.7.5 生日扫描 — 2月29日生日，平年兜底到 2月28日

- **关联**：Story 6.7 · AC1 · B3（2/29→平年 2/28 兜底）
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：注入 `today=2026-02-27`（平年），`birthday=2000-02-29`（闰年生日）
- **预期**：Planner 命中（平年时 2/29→2/28，前一天=2/27 匹配），返回 `PlannedPush{type=PET_BIRTHDAY}`
- **层级**：L0

#### TC-6.7.6 纪念日扫描 — 建档满 30 天当天触发，边界验证（29/31 天不触发）

- **关联**：Story 6.7 · AC2 · B4
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：
  1. `today=2026-07-10`，`created_at=2026-06-10`（恰好 30 天），`existingKeys` 空
  2. `today=2026-07-09`（29 天），同上档案
  3. `today=2026-07-11`（31 天），同上档案
- **预期**：① 触发 `COMPANION_ANNIVERSARY{nodeKey='30'}`；② 不触发；③ 不触发
- **层级**：L0

#### TC-6.7.7 纪念日扫描 — 100/365 天节点各自仅触发一次

- **关联**：Story 6.7 · AC2 · B4
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：
  1. 构造距 `created_at` 整 100 天，`existingKeys` 空 → 检查 `nodeKey='100'` 触发
  2. 构造距 365 天，`existingKeys` 空 → 检查 `nodeKey='365'` 触发
  3. 365 天节点已推过（`existingKeys` 含 `nodeKey='365'`）→ 不再触发
- **预期**：① 触发；② 触发；③ 不触发；365 天后无新节点（V1 仅 30/100/365）
- **层级**：L0

#### TC-6.7.8 第一个生日（L级里程碑节点）— age=1 时同时触发 MILESTONE_NODE

- **关联**：Story 6.7 · AC3 · B5
- **页面/入口**：`ScheduledPushPlanner.plan`
- **前置**：无
- **步骤**：注入 `today=2026-06-09`，`birthday=2025-06-10`（明天满 1 岁）；`existingKeys` 空
- **预期**：返回 2 条：`PET_BIRTHDAY{nodeKey='2026'}` + `MILESTONE_NODE{nodeKey='FIRST_BIRTHDAY'}`
- **层级**：L0

#### TC-6.7.9 里程碑节点范围守护 — Planner 绝不产出 pet_milestones 写入意图

- **关联**：Story 6.7 · AC3 · B5 · 决策 F2 范围守护
- **页面/入口**：`ScheduledPushPlannerTest`（范围守护测试）
- **前置**：无
- **步骤**：审查 `ScheduledPushPlannerTest` 中「范围守护」测试：Planner 输出集合只包含 `PET_BIRTHDAY`, `COMPANION_ANNIVERSARY`, `MILESTONE_NODE` 三种 type
- **预期**：无任何 `pet_milestones` / 完成态 / 徽章 / 动效数据出现在 Planner 输出；测试断言通过
- **层级**：L0

#### TC-6.7.10 @Scheduled 不引入 Quartz 或 MQ — 依赖审查

- **关联**：Story 6.7 · B2 · 决策 F5 护栏
- **页面/入口**：`petgo-backend/pom.xml` + `ScheduledPushJob.java`
- **前置**：无
- **步骤**：
  1. 查 `pom.xml`：搜索 `quartz`, `kafka`, `rabbitmq`, `spring-batch` 依赖
  2. 查 `ScheduledPushJob.java`：只有 `@Scheduled` 注解，无 `JobDetail`/`Trigger`/`JobBuilder`
- **预期**：pom.xml 无以上中间件依赖；Dispatcher 使用 `@Async`，无队列/消息发送调用
- **层级**：L0

#### TC-6.7.11 DB 去重唯一约束 — 并发扫描第二次插入去重标记被拒，不重复推送

- **关联**：Story 6.7 · AC1,2,3 · B1
- **页面/入口**：`V22__init_scheduled_push_dedup.sql` + `ScheduledPushMarkRepository`
- **前置**：Docker pg
- **步骤**：
  1. 手动向 `scheduled_push_marks` 插入 `(petProfileId=1, pushKind='PET_BIRTHDAY', nodeKey='2026')`
  2. 再次插入相同记录
- **预期**：第二次插入触发唯一约束违反（`duplicate key value violates unique constraint`）；`notifications` 中不产生第二条同类通知
- **层级**：L1

### 真实扫描与投递（L1）

#### TC-6.7.12 生日扫描 L1 — 造"明天生日"档案，扫描后落 notifications + scheduled_push_marks

- **关联**：Story 6.7 · AC1 · J1
- **页面/入口**：`ScheduledPushJob`（可通过 dev 端点手动触发或修改 cron 触发）
- **前置**：Docker pg+redis；档案 `birthday` 设为明天；无去重标记
- **步骤**：
  1. 触发每日扫描（修改 cron 或 dev 触发接口）
  2. 查 `notifications` 表：`WHERE type='PET_BIRTHDAY'`
  3. 查 `scheduled_push_marks` 表
  4. 查 Redis `notify:unread:{userId}`
- **预期**：`notifications` 新增 1 行（`PET_BIRTHDAY`）；`scheduled_push_marks` 新增去重标记；Redis 角标 +1；IM 投递调用出现在日志
- **层级**：L1

#### TC-6.7.13 重扫不重复 — 第二次扫描同一档案，不产生新通知

- **关联**：Story 6.7 · AC1 · J1
- **页面/入口**：`ScheduledPushJob`
- **前置**：TC-6.7.12 执行后（去重标记已存在）
- **步骤**：再次触发扫描
- **预期**：`notifications` 中不新增同类行；`scheduled_push_marks` 不重复写入；Redis 角标不变
- **层级**：L1

#### TC-6.7.14 纪念日 30/100/365 天扫描 — 各节点独立落库

- **关联**：Story 6.7 · AC2 · J2
- **页面/入口**：`ScheduledPushJob`
- **前置**：Docker pg+redis；造 `created_at` 分别距今 30/100/365 天的档案；去重标记为空
- **步骤**：触发扫描；查 `notifications` 和 `scheduled_push_marks`
- **预期**：各档案对应节点各生成 1 条 `COMPANION_ANNIVERSARY` 通知 + 1 条去重标记（`nodeKey='30'/'100'/'365'`）；边界档案（29/31/99/101 天）无通知生成
- **层级**：L1

#### TC-6.7.15 未授权推送用户 — 仅落通知中心，不阻塞主流程

- **关联**：Story 6.7 · AC1 · B6
- **页面/入口**：`NotificationService.send` → IM 离线投递失败路径
- **前置**：Docker pg+redis；用户未授权系统推送（IM 投递失败）
- **步骤**：触发生日扫描，目标用户为未授权设备
- **预期**：`notifications` 正常落库（通知中心仍可见）；角标自增；IM 投递失败仅记日志；主扫描流程不中断；**无** PII/令牌出现在日志
- **层级**：L1

### 真机端到端（L2）

#### TC-6.7.16 生日提醒真机 — 收到推送，点击跳「+发布」预选成长日历

- **关联**：Story 6.7 · AC1 · J4
- **页面/入口**：真机系统通知 → `/publish?preset=growth-calendar`
- **前置**：真机已授权推送；IM live 模式；`today-1` 是宠物生日
- **步骤**：触发生日扫描；真机收到「{宠物名} 明天就 X 岁啦！记录一条特别的快乐时刻吧 🎂」推送；点击
- **预期**：落到发布页，预选「成长日历」类型；推送文案**不含中文**（en/id）；`PublishLandingPage` 正常渲染
- **层级**：L2

#### TC-6.7.17 纪念日提醒真机 — 收到推送，点击跳成长档案 Tab

- **关联**：Story 6.7 · AC2 · J4
- **页面/入口**：真机系统通知 → `/profile`
- **前置**：真机；档案满 30 天
- **步骤**：触发纪念日扫描；真机收到「你和 {宠物名} 在一起 30 天了 🎉…」推送；点击
- **预期**：落到 `/profile`（成长档案 Tab）；推送文案**不含中文**；档案页正常展示
- **层级**：L2

---

## 双语 i18n 横切检查

#### TC-6.X.1 所有推送相关文案 — 切换语言至 English，无中文残留

- **关联**：所有 Story · 双语 i18n · 系统记忆「App 绝不渲染中文」
- **页面/入口**：全部通知相关页面（通知中心、推送弹窗、版本弹窗、推送权限引导）
- **前置**：App 语言设为 English（`en`）
- **步骤**：逐一浏览通知中心、推送软引导、版本更新弹窗、`PushEnableGuide`
- **预期**：所有 UI 文案为英文（en）；**零中文字符**出现；空态「No notifications yet」；铃铛无中文
- **层级**：L2

#### TC-6.X.2 所有推送相关文案 — 切换语言至 Bahasa Indonesia，正确显示 id 文案

- **关联**：所有 Story · 双语 i18n
- **页面/入口**：同上
- **前置**：App 语言设为 Bahasa Indonesia（`id`）
- **步骤**：同 TC-6.X.1，语言为 id
- **预期**：文案为印尼语（id）；「Notifikasi」、「Belum ada notifikasi」、「Perbarui sekarang」、「Aktifkan notifikasi」等 id 文案正确；零中文
- **层级**：L2

---

## 安全与日志横切检查

#### TC-6.X.3 推送 payload / 日志不含 PII 或健康数据

- **关联**：所有 Story · 安全护栏
- **页面/入口**：后端日志文件 / IM 推送 payload
- **前置**：L1 环境运行，触发各类推送
- **步骤**：
  1. 检查后端 JSON 日志：搜索 `phone`, `email`, `password`, `token=`, `Bearer`, `health` 等 PII 关键字
  2. 检查 IM 离线推送 payload（stub 日志）：确认只含 `type`, `deepLinkToken`, `title`, `body`
- **预期**：日志中无 PII/健康数据/JWT 令牌；`deepLinkToken` 为随机串，不可由外部推算到顺序 id；日志无原始 `target_ref` 值
- **层级**：L1

#### TC-6.X.4 深链 token 不可枚举 — 不暴露顺序 id

- **关联**：Story 6.1 · AC1 · 安全护栏
- **页面/入口**：`notifications` 表 + `PushPayload`
- **前置**：Docker pg；触发若干推送
- **步骤**：查询 `notifications.deep_link_token` 列，检查若干行的 token 格式
- **预期**：token 为随机 base62 字符串（约 32 字符）；相邻行的 token 无顺序规律；不等于行 `id`
- **层级**：L1

---

## 本章遗留 / 盲区

以下方面在当前文档无法完全通过 L0/L1 验证，需留意或补充测试计划：

1. **IM 离线通道真实投递（L2 全链）**：所有涉及「真实 APNs/FCM 经腾讯 IM 下发」的场景（TC-6.1.12~16、TC-6.2.6~8、TC-6.3.5~7、TC-6.7.16~17）依赖 IM live 凭证 + 真机。当前 L0/L1 仅验证 stub 调用日志，真实到达率与延迟无法在 CI 中验证。**建议**：开设「推送功能上线前」真机冒烟检查清单，至少覆盖 VET_REPLY、PET_BIRTHDAY 两类。

2. **冷启动深链三态区分度**：TC-6.1.12~14 区分「冷/后台/前台」三态较难在 L1 自动化，需人工操作真机。尤其冷启动（进程已杀）在 iOS 上受「后台刷新」开关影响，结果可能不一致。**盲区**：iOS 低功耗模式下冷启动推送点击行为未覆盖。

3. **推送权限真机弹窗时序（F15）**：TC-6.4.7 庆祝页「开始探索」→ 系统推送弹窗 → 进首页的**视觉时序**（弹窗阻断感、过渡动画）只能 L2 真机主观验证，不可单测自动化。**盲区**：Android 12+ 的「精确通知权限」流程与 iOS 不同，需单独测试 Android 路径。

4. **定时推送 cron 时区边界**：TC-6.7.x 扫描时间为 UTC 01:00，印尼用户（WIB = UTC+7，WITA = UTC+8）收到提醒时间为本地 08:00~09:00，验收为 L1 可验逻辑，但在实际运营中当日「明天生日」判定与用户本地日期可能因时区偏差造成体验偏差。**建议**：后续 V1.1 评估按用户时区扫描。

5. **角标计数极端并发场景**：多台设备同时触发推送（如大促同时给 500 用户发纪念日）时，Redis `INCR` 的顺序与 `@Async` 线程池饱和未在测试用例中覆盖。**盲区**：仅覆盖单用户单设备路径；压测超出 TC 范围。
