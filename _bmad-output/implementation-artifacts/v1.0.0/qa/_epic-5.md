# Epic 5：在线兽医咨询与兽医工作台 — 人工测试流程文档

> 版本：2026-06-10 · 覆盖 Story 5.1~5.8（含所有 R2 回改：F4/F11/F12）  
> 验证分层：**L0**（编译/lint/单测，无需 DB）· **L1**（需 Docker pg+redis，真实 Flyway+状态机）· **L2**（需真实腾讯 IM 凭证/真机双端）

---

## 范围说明

Epic 5 交付两套产物，测试必须覆盖**双端双视角**：

| 端 | 导航壳 | 关键路由 |
|---|---|---|
| 宠物主 App（role=user）| 5-Tab，问诊 Tab = `/triage` | `/consult` 入口 · `/consult/waiting/:id` · `/consult/conversation/:id` |
| 兽医工作台（role=vet）| 独立 4-Tab VetWorkbenchShell | `/vet/login` · `/vet/workbench`（待接单/进行中/历史/我的）· `/vet/request/:id` · `/vet/conversation/:id` |
| Admin 后台（role=ADMIN）| Thymeleaf `/admin/**` | `/admin/vets`（开户/重置/封禁）· `/admin/vets/{id}/ratings` |

### 会话状态机（全量）

```
                    ┌─────────────────────────────────────────┐
用户发起            │  WAITING  ──(1min 超时,不迁移)──▶ 弹层   │
POST /consult-sessions ──▶ WAITING ──(取消,退出 kill)──▶ CANCELLED
                                │
                      (兽医接单,5.5 原子写 @Version CAS)
                                │
                                ▼
                           IN_PROGRESS ──(兽医封禁,5.7)──▶ INTERRUPTED(终态)
                                │
                      (兽医点「结束会话」,5.6)
                                │
                                ▼
                          PENDING_CLOSE ──(兽医封禁,5.7)──▶ INTERRUPTED(终态)
                          (30min 保护窗口)
                            /        \
                 (用户评分)              (超时 30min 无评分)
                    /                        \
                CLOSED(RATED)           CLOSED(UNRATED)
                   终态                     终态，补弹一次
```

### 双端页面/路由清单

**宠物主端（features/consult + features/triage）**

| 路由 | 文件 | 功能 |
|---|---|---|
| `/consult`（问诊 Tab 入口）| `consult_entry_page.dart` | 在线/离线两态 + 已有进行中跳转 |
| `/consult/waiting/:id` | `consult_waiting_page.dart` | 等待界面 + 1min 超时弹层 + 取消 |
| `/consult/conversation/:id` | `consult_conversation_page.dart` | 进行中对话 + PENDING_CLOSE 评分门 + INTERRUPTED 中断态 |
| `ConsultRatingDialog`（弹窗）| `consult_rating_dialog.dart` | 1-5 星必填 + ≤100 字 |
| `ConsultAvailabilityIndicator` | `consult_availability_indicator.dart` | 在线/离线指示组件 |
| `ImChatPlaceholder` | `im_chat_placeholder.dart` | 对话区占位（L2 替换 IM SDK）|

**兽医端（features/vet）**

| 路由 | 文件 | 功能 |
|---|---|---|
| `/vet/login` | `vet_login_page.dart` | 账密登录，无忘记密码 |
| `/vet/workbench`（壳）| `vet_workbench_shell.dart` | 独立 4-Tab：待接单/进行中/历史/我的 |
| `/vet/workbench`（Tab 0）| `vet_inbox_page.dart` | 抢单模式，卡片列表 |
| `/vet/request/:id` | `vet_request_detail_page.dart` | 3min 预览倒计时 + 接单 + 三返回态 |
| `/vet/workbench`（Tab 1）| `vet_active_page.dart` | 进行中（5.5 后填充）|
| `/vet/workbench`（Tab 2）| `vet_history_page.dart` | 历史（5.8 后填充）|
| `/vet/workbench`（Tab 3）| `vet_me_page.dart` | 在线/离线开关 + 心跳 + 登出 |
| `/vet/conversation/:id` | `vet_conversation_page.dart` | AI 上下文卡 + 辅助面板 + IM 对话区 |
| `VetAiContextCard` | `vet_ai_context_card.dart` | AI 上下文卡（AI_UPGRADE 显示，DIRECT 不显示）|
| `VetEmptyState` | `vet_empty_state.dart` | 空态占位 |

---

## 5.1 兽医账号与登录（含 Admin 开户）

### TC-5.1.01 Admin 开户 — 正常路径

- **关联**：Story 5.1 · AC1 · FR-29
- **页面/入口**：`/admin/vets` · `templates/admin/vets.html` · Admin 侧
- **前置**：Docker pg+redis 运行；已有 ADMIN 账号（Story 3.1 种子）；locale=en
- **步骤**：
  1. 浏览器打开 `/admin/vets`，以 ADMIN 账号登录
  2. 点「创建兽医账号」，填写 username=`drh_sari`，displayName=`drh. Sari`，初始密码由表单生成或手填
  3. 点「创建」
  4. 在 DB 执行 `SELECT password_hash, status FROM vet_accounts WHERE username='drh_sari'`
- **预期**：
  - Admin 列表出现新条目，displayName=`drh. Sari`，status=`ACTIVE`
  - DB 中 `password_hash` 为 BCrypt hash（以 `$2a$` 或 `$2b$` 开头），明文密码**不出现**在任何响应/日志
  - 响应体**不含** `password_hash` 字段（若为 API 方式，初始密码只在创建响应中一次性回显）
- **层级**：L1

---

### TC-5.1.02 兽医账密登录 — 正常路径

- **关联**：Story 5.1 · AC2 · FR-29
- **页面/入口**：`/vet/login` · `vet_login_page.dart` · 兽医侧
- **前置**：TC-5.1.01 已建 `drh_sari` 账号；locale=en
- **步骤**：
  1. 打开 App 登录页，找到 Google 登录按钮**下方**的小字链接，确认文案存在（en/id 均不含中文）
  2. 点击「兽医登录」小字链接，进入 `/vet/login`
  3. 确认页面**无**「Forgot password」或「忘记密码」链接，仅有联系运营提示（`l10n.vetForgotHint`）
  4. 在 `vetUsernameField` 输入 `drh_sari`，在 `vetPasswordField` 输入正确密码
  5. 点 `vetLoginButton`
- **预期**：
  - 登录成功，App 导航至 `/vet/workbench`（兽医工作台壳）
  - 工作台显示 displayName=`drh. Sari`（`vetDisplayName` key）
  - 底部 Tab 为独立 4-Tab（待接单/进行中/历史/我的），**无**用户侧 Feed/档案/发布 FAB
  - JWT 中 `role=VET`（可通过 `/api/v1/vet/me` 响应 role 字段验证）
- **层级**：L2

---

### TC-5.1.03 兽医登录 — 密码错误防枚举

- **关联**：Story 5.1 · AC2 · NFR-12
- **页面/入口**：`/vet/login` · `vet_login_page.dart` · 兽医侧
- **前置**：`drh_sari` 账号已建；locale=en
- **步骤**：
  1. 在 `vetPasswordField` 输入**错误**密码，点 `vetLoginButton`
  2. 再用**不存在**的 username 尝试登录
- **预期**：
  - 两种情形均显示相同文案 `l10n.vetLoginFailed`（账号或密码错误），不区分「账号不存在」vs「密码错」
  - 响应均为 HTTP 401 ProblemDetail，**不**包含 BCrypt hash 或任何账号枚举信息
- **层级**：L1

---

### TC-5.1.04 登录限流（429）

- **关联**：Story 5.1 · AC2 · NFR-12
- **页面/入口**：`POST /api/v1/auth/vet/login` · 兽医侧
- **前置**：Redis 运行；locale=en
- **步骤**：
  1. 在 1 分钟内连续调用 `/api/v1/auth/vet/login` 超过 10 次（可用 curl 循环）
- **预期**：
  - 超限后返回 HTTP 429 ProblemDetail
  - App 侧显示文案 `l10n.vetLoginRateLimited`（「尝试过于频繁，请稍后再试」）
- **层级**：L1

---

### TC-5.1.05 Admin 重置密码

- **关联**：Story 5.1 · AC4 · FR-29
- **页面/入口**：`/admin/vets` · Admin 侧
- **前置**：`drh_sari` 账号已建；知晓当前密码
- **步骤**：
  1. Admin 在列表点「重置密码」，为 `drh_sari` 设置新密码 `NewPass!456`
  2. 用**旧密码**尝试登录
  3. 用**新密码**尝试登录
- **预期**：
  - 旧密码登录 → HTTP 401，显示「账号或密码错误」
  - 新密码登录 → 成功，跳工作台
- **层级**：L1

---

### TC-5.1.06 BANNED 账号不可登录

- **关联**：Story 5.1 · AC4（5.7 前置语义）
- **页面/入口**：`/admin/vets` → `POST /api/v1/auth/vet/login` · Admin/兽医侧
- **前置**：`drh_sari` 账号 ACTIVE；Docker pg+redis
- **步骤**：
  1. Admin 在列表点「封禁」，确认置 `status=BANNED`
  2. 用 `drh_sari` 账密尝试登录
- **预期**：HTTP 401 ProblemDetail，不透露封禁原因（防枚举，统一「账号或密码错误」）
- **层级**：L1

---

### TC-5.1.07 角色门控 — VET token 访问用户写端点被拒

- **关联**：Story 5.1 · AC3
- **页面/入口**：`/api/v1/` 用户写端点（如 `POST /api/v1/pets`）· 后端
- **前置**：已获取 VET JWT
- **步骤**：
  1. 用 VET JWT 调用 `POST /api/v1/pets`（用户专属端点）
- **预期**：HTTP 403 ProblemDetail（type/title/status/traceId，**无堆栈**）
- **层级**：L1

---

### TC-5.1.08 角色门控 — USER token 访问兽医端点被拒

- **关联**：Story 5.1 · AC3
- **页面/入口**：`GET /api/v1/vet/me` · 后端
- **前置**：已获取 USER JWT
- **步骤**：
  1. 用 USER JWT 调用 `GET /api/v1/vet/me`
- **预期**：HTTP 403 ProblemDetail
- **层级**：L1

---

### TC-5.1.09 前端 role 守卫 — VET 态深链用户侧路由被拦截

- **关联**：Story 5.1 · AC3 · 前端守卫
- **页面/入口**：`core/router/app_router.dart` · 兽医侧
- **前置**：以兽医身份登录，处于 `/vet/workbench`；locale=id
- **步骤**：
  1. 在地址栏直接输入或调用 `context.go('/triage')`（用户侧档案/问诊路由）
- **预期**：被守卫拦截，重定向回 `/vet/workbench`，**不**进入用户侧页面
- **层级**：L2

---

### TC-5.1.10 刷新 token — VET/USER 主体隔离（防 refresh 串签）

- **关联**：Story 5.1 Dev Notes（`subject_type` 修复）
- **页面/入口**：`POST /api/v1/auth/refresh` · 后端
- **前置**：分别持有 USER refresh token 和 VET refresh token
- **步骤**：
  1. 用 VET refresh token 调用 `/auth/refresh`，检查返回的新 access token 的 `role` claim
  2. 用 USER refresh token 调用同端点，检查返回 access token 的 `role`
- **预期**：
  - VET refresh → 新 access token `role=VET`，**不**签成 USER role
  - USER refresh → 新 access token `role=USER`，**不**签成 VET role
- **层级**：L1

---

## 5.2 兽医工作台框架与在线状态管理

### TC-5.2.01 兽医工作台独立 4-Tab 框架

- **关联**：Story 5.2 · AC1 · FR-30
- **页面/入口**：`/vet/workbench` · `vet_workbench_shell.dart` · 兽医侧
- **前置**：以 `role=VET` 登录成功
- **步骤**：
  1. 进入 `/vet/workbench`，确认底部导航有 4 个目标（`vetBottomNav` key）
  2. 依次点击：待接单（Tab 0）→ 进行中（Tab 1）→ 历史记录（Tab 2）→ 我的（Tab 3）
  3. 检查每个 Tab 的 AppBar 标题与 l10n key（`vetTabInbox` / `vetTabActive` / `vetTabHistory` / `vetTabMe`）
  4. 检查**无**用户侧元素：无凸起「+」FAB、无 Feed/动态、无宠物档案入口
- **预期**：
  - 4 Tab 均可正常切换，内容独立渲染
  - 无任何用户侧导航元素（Feed/档案/发布）泄漏至兽医壳
  - en/id 双语标题正确（无中文）
- **层级**：L0/L2

---

### TC-5.2.02 在线/离线开关 — 正常切换（Redis 写）

- **关联**：Story 5.2 · AC2 · FR-32
- **页面/入口**：`/vet/workbench`（Tab 3）· `vet_me_page.dart` · 兽医侧
- **前置**：VET 已登录；Docker pg+redis；初始状态为离线
- **步骤**：
  1. 进入「我的」Tab，确认 `vetOnlineSwitch` 为 OFF，状态文案为 `l10n.vetOfflineLabel`
  2. 拨动开关至 ON（乐观更新即刻翻转），等待 API 响应
  3. 在 Redis 执行 `ZRANGEBYSCORE vet:online -inf +inf WITHSCORES` 验证 vetId 在集合中
  4. 再次拨动至 OFF
  5. 验证 Redis 集合中 vetId 已被移除
- **预期**：
  - 开关 ON → 状态文案变为 `l10n.vetOnlineLabel`（「在线·可接单」）
  - 开关 OFF → 文案变为 `l10n.vetOfflineLabel`（「离线·不接单」）
  - Redis ZSET `vet:online` 成员与 score（epochMillis）正确写入/移除
  - 切换失败时乐观更新回滚，SnackBar 显示 `l10n.vetStatusUpdateFailed`
- **层级**：L1

---

### TC-5.2.03 在线态 TTL 兜底离线（防幽灵在线）

- **关联**：Story 5.2 · AC4 · Redis 收窄
- **页面/入口**：`VetMePage` 心跳 / Redis ZSET · 兽医侧
- **前置**：VET 在线（Redis 有记录）；TTL 窗口=3min；心跳间隔=60s
- **步骤**：
  1. 兽医置在线后，强制停止心跳（模拟杀 App / 退后台）
  2. 等待 3 分钟以上
  3. 调用 `GET /api/v1/consult/availability` 或执行 `ZRANGEBYSCORE vet:online <now-3min> +inf`
- **预期**：
  - 3 分钟后 `anyOnline()` 返回 false（score 超出窗口，惰性清理后不计入）
  - `vetOnline=false`（用户侧入口显示「当前暂无兽医在线」）
- **层级**：L1

---

### TC-5.2.04 登出即离线（goOffline 清 Redis）

- **关联**：Story 5.2 · AC4
- **页面/入口**：`VetMePage` `vetLogoutButton` · 兽医侧
- **前置**：VET 在线；Docker+redis
- **步骤**：
  1. VET 在线态下点 `vetLogoutButton`
  2. 立即查 Redis `vet:online`
- **预期**：Redis 中该 vetId 已被移除；心跳 Timer 停止；App 跳回 `/home`（用户侧首页）
- **层级**：L1

---

### TC-5.2.05 用户侧入口读在线态 — 有兽医在线

- **关联**：Story 5.2 · AC3 · 用户侧
- **页面/入口**：`/consult` · `consult_entry_page.dart` · 宠物主侧
- **前置**：至少一名 VET 在线（Redis 有记录）；USER 已登录
- **步骤**：
  1. 打开 `/consult` 入口页
- **预期**：
  - 显示 `_online` 态（`consultStartButton` 可见）
  - 文案 `l10n.consultProbabilisticOnline`（「工作日 8:00–23:00 通常有兽医在线」）
  - **不显示**在线人数（任何「X 位兽医在线」类文案均为缺陷）
- **层级**：L1（API）+ L2（渲染）

---

### TC-5.2.06 用户侧入口读在线态 — 全部兽医离线

- **关联**：Story 5.2 · AC3 · FR-4B 离线态
- **页面/入口**：`/consult` · `consult_entry_page.dart` · 宠物主侧
- **前置**：所有 VET 离线（Redis 空 / TTL 过期）；USER 已登录
- **步骤**：
  1. 打开 `/consult` 入口页
- **预期**：
  - 显示 `consultOfflineState`（`consultNoVetOnline` 文案：「当前暂无兽医在线」）
  - 显示 `consultOfflineWindow`（恢复时段提示，静态文案）
  - 显示 `consultOfflineUseAi` 按钮，**不强制**跳转（用户可留在本页不点）
  - `consultStartButton` **不可见**（不能发起咨询）
- **层级**：L1（API）+ L2（渲染）

---

### TC-5.2.07 待接单抢单列表 — 抢单模式卡片渲染

- **关联**：Story 5.2 · AC5 · F11 · 决策 F11
- **页面/入口**：`/vet/workbench`（Tab 0）· `vet_inbox_page.dart` · 兽医侧
- **前置**：VET 在线；Redis 待接单队列有 WAITING 请求（AI_UPGRADE 和 DIRECT 各 1 条）
- **步骤**：
  1. VET 进入待接单 Tab，等待列表加载
  2. 检查 AI_UPGRADE 类型卡片（`vetRequestCard_<id>`）：含评级标签 + 症状预览 + 图片张数
  3. 检查 DIRECT 类型卡片：显示 `l10n.vetInboxDirect` 文案
  4. 点 AI_UPGRADE 卡片（整卡可点），进入 `/vet/request/:id`
- **预期**：
  - 无列表内联接单按钮（R2 已去除）
  - 整卡点击进入预览详情页
  - AI_UPGRADE 卡片左侧彩色评级标签（黄=`triageYellow`，绿=`triageGreen`）
  - 列表空时显示 `VetEmptyState`（`l10n.vetInboxEmpty`）
- **层级**：L0（widget）

---

### TC-5.2.08 待接单列表 — locale=id 双语

- **关联**：Story 5.2 · AC5 · i18n
- **页面/入口**：`vet_inbox_page.dart` / `vet_request_detail_page.dart` · 兽医侧
- **前置**：设备 locale=id；VET 在线
- **步骤**：
  1. 检查待接单 Tab 文案、卡片标签、预览页文案全为印度尼西亚语
- **预期**：无中文，无硬编码印尼语（均走 `l10n.*`）
- **层级**：L0/L2

---

## 5.3 兽医咨询发起、排队与超时

### TC-5.3.01 发起咨询 → WAITING + Redis 队列

- **关联**：Story 5.3 · AC1 · FR-4B
- **页面/入口**：`/consult` → `/consult/waiting/:id` · `consult_entry_page.dart` / `consult_waiting_page.dart` · 宠物主侧
- **前置**：USER 已登录；至少 1 VET 在线；Docker pg+redis
- **步骤**：
  1. 打开 `/consult`，点 `consultStartButton`
  2. 等待跳转至 `/consult/waiting/:id`
  3. 在 DB 查 `SELECT id, status, source FROM consult_sessions ORDER BY created_at DESC LIMIT 1`
  4. 在 Redis 查 `ZRANGEBYSCORE consult:waiting -inf +inf`
- **预期**：
  - 等待界面显示 `consultMatching`（「正在为你匹配兽医…」）+ 加载圈 + `consultCancel` 按钮
  - DB：session status=`WAITING`，source=`DIRECT`
  - Redis ZSET `consult:waiting` 包含该 sessionId
- **层级**：L1

---

### TC-5.3.02 同时仅 1 个进行中约束

- **关联**：Story 5.3 · AC2
- **页面/入口**：`/consult` · `consult_entry_page.dart` · 宠物主侧
- **前置**：USER 已有 WAITING/IN_PROGRESS/PENDING_CLOSE 会话之一
- **步骤**：
  1. 打开 `/consult` 入口页
- **预期**：
  - 显示 `consultViewActive` 按钮（`l10n.consultViewActive`：「查看进行中的咨询 →」）
  - `consultStartButton` **不可见**（不能再发起）
  - 三种占用态（WAITING/IN_PROGRESS/PENDING_CLOSE）均触发该展示
- **层级**：L1（后端校验）+ L2（渲染）

---

### TC-5.3.03 1min 超时弹层 — 继续等待分支

- **关联**：Story 5.3 · AC3 · FR-4B
- **页面/入口**：`/consult/waiting/:id` · `consult_waiting_page.dart` · 宠物主侧
- **前置**：USER 在等待中；等待 > 1 分钟无兽医接单
- **步骤**：
  1. 等待 1 分钟后，底部弹层出现（`consultTimeoutTitle` 标题）
  2. 点 `consultContinueWaiting`（「继续等待」）
  3. 查 DB 该 session 的 `waiting_started_at` 是否重置
- **预期**：
  - 弹层显示 `consultTimeoutTitle` + `consultTimeoutBody`
  - 点「继续等待」：弹层关闭，回到等待界面（仍显示加载圈），`waiting_started_at` 重置
  - session **仍为 WAITING**（不迁移状态）
  - Redis 队列中 sessionId **仍存在**
- **层级**：L1（计时/重置）+ L2（弹层 UI）

---

### TC-5.3.04 1min 超时弹层 — 先用 AI 分诊分支

- **关联**：Story 5.3 · AC3 · FR-4B
- **页面/入口**：`/consult/waiting/:id` · 宠物主侧
- **前置**：与 TC-5.3.03 相同
- **步骤**：
  1. 超时弹层出现后，点 `consultUseAi`（「先用 AI 分诊」）
  2. App 跳转至 `/triage/upload`，显示 SnackBar `l10n.consultUseAiKeptHint`
  3. 查 DB 该 session 状态
  4. 查 Redis `consult:waiting`
- **预期**：
  - session **仍为 WAITING**（不删除，原请求保留）
  - Redis 队列中 sessionId **仍存在**
  - SnackBar 提示「兽医接单后会通知你」（`l10n.consultUseAiKeptHint`）
  - 兽医侧待接单 Tab 中该请求**仍可见**（可被接单）
- **层级**：L1（数据）+ L2（UI 跳转）

---

### TC-5.3.05 主动取消二次确认

- **关联**：Story 5.3 · AC4
- **页面/入口**：`/consult/waiting/:id` · `consult_waiting_page.dart` · 宠物主侧
- **前置**：USER 在等待中（WAITING）
- **步骤**：
  1. 点 `consultCancel`（「取消」）
  2. 弹出 AlertDialog，确认标题含 `l10n.consultCancelConfirmTitle`
  3. 先点 `consultCancelConfirmNo`（否），弹层关闭，验证 session 仍 WAITING
  4. 再次点「取消」，点 `consultCancelConfirmYes`（确认）
  5. 查 DB session 状态；查 Redis 队列
- **预期**：
  - 点「否」：弹层关闭，session 仍 WAITING，等待继续
  - 点「确认」：session 变 CANCELLED，Redis 队列中 sessionId 被移除，App 返回 `/triage`（问诊 Tab）
  - 取消后可再次在入口发起新咨询（不被「同时仅 1 个」拦截）
- **层级**：L1

---

### TC-5.3.06 等待期退出 kill — 自动取消（F12）

- **关联**：Story 5.3 · AC7 · 决策 F12
- **页面/入口**：`consult_waiting_page.dart` `WidgetsBindingObserver` · 宠物主侧
- **前置**：USER 在等待中（WAITING）；locale=en
- **步骤**：
  1. 停留在等待页，直接按 Home 再强制终止 App（或模拟 `AppLifecycleState.detached`）
  2. 稍后查 DB session 状态和 Redis 队列
- **预期**：
  - session 变 CANCELLED，Redis 队列中 sessionId 被移除
  - 兽医侧待接单 Tab 中该请求**不再可见**
  - 重新打开 App 可正常发起新咨询
- **层级**：L0（widget detached→cancel）+ L1（状态 CANCELLED）+ L2（真机 kill）

---

### TC-5.3.07 退出 kill — 已选「先用 AI」时不误取消

- **关联**：Story 5.3 · AC7 · `_exitCancelDisabled`
- **页面/入口**：`consult_waiting_page.dart` · 宠物主侧
- **前置**：USER 在等待中，已点「先用 AI」（`_exitCancelDisabled=true`）
- **步骤**：
  1. 在「先用 AI」后模拟 `AppLifecycleState.detached`
  2. 查 DB session 状态
- **预期**：session **仍为 WAITING**（`_exitCancelDisabled` 抑制了退出取消），原请求保留
- **层级**：L0

---

### TC-5.3.08 并发抢单原子写 — 先到先得（F11）

- **关联**：Story 5.3 · AC6 · 决策 F11 · 安全
- **页面/入口**：`POST /api/v1/vet/consult-sessions/{id}/accept`（后端原子写）· 双兽医并发
- **前置**：1 条 WAITING 请求；2 名 VET 同时在线；Docker pg
- **步骤**：
  1. 两名 VET 同时发送接单请求（模拟并发）
  2. 检查 DB：哪个 vet_id 写入、session status
  3. 检查接单失败方的响应
- **预期**：
  - 仅一人成功（`@Version` 乐观锁 CAS，影响 1 行）
  - 另一人得 HTTP 409 ProblemDetail（`l10n.vetInboxTaken`：「此请求已被其他兽医接单」）
  - **无双接单**：DB 中 vet_id 唯一写入，session 只有一个 IN_PROGRESS
- **层级**：L1

---

### TC-5.3.09 退单（release）— 正常路径（F11）

- **关联**：Story 5.3 · AC6 · 决策 F11
- **页面/入口**：`POST /api/v1/vet/consult-sessions/{id}/release` · 兽医侧
- **前置**：VET A 已接单（IN_PROGRESS），release_count=0
- **步骤**：
  1. VET A 调用 release 端点
  2. 查 DB：session status、vet_id、release_count
  3. 查 Redis 待接单队列、vet 在线态
- **预期**：
  - session status → WAITING，vet_id 解绑（null），release_count=1
  - Redis 待接单队列重入该 sessionId
  - VET A 的在线态从 BUSY 恢复 ONLINE（`goAvailable`）
  - 其他在线兽医待接单 Tab 中该请求重新可见
- **层级**：L1

---

### TC-5.3.10 退单超限（release_count>2）

- **关联**：Story 5.3 · AC6
- **页面/入口**：后端 `ConsultAcceptService.release` · 后端
- **前置**：某会话 release_count 已=2
- **步骤**：
  1. 对该会话再次调用 release（第 3 次退单）
- **预期**：
  - session 仍重新入队（不卡用户）
  - DB：`isAbnormalReleaseCount=true`（release_count=3，超过 2 的异常信号）
  - 运营可从 Admin 看到该信号（后续人工处理）
- **层级**：L1

---

### TC-5.3.11 离线态软引导 — 不强制跳转

- **关联**：Story 5.3 · AC5 · FR-4B
- **页面/入口**：`/consult` · `consult_entry_page.dart` · 宠物主侧
- **前置**：所有 VET 离线
- **步骤**：
  1. 打开 `/consult`，确认显示 `consultOfflineState`
  2. **不点**「先用 AI 分诊？」按钮，留在本页
  3. 检查页面状态
- **预期**：用户可留在本页不被强制跳转；「先用 AI 分诊？」按钮（`consultOfflineUseAi`）可选点，不是唯一出口
- **层级**：L2

---

## 5.4 AI 升级至兽医的上下文传递

### TC-5.4.01 绿/黄态 AI 结果页出现「咨询兽医」入口

- **关联**：Story 5.4 · AC1 · FR-4B
- **页面/入口**：`features/triage/presentation/triage_result_view.dart` · 宠物主侧
- **前置**：完成 AI 分诊，评级为绿（GREEN）；locale=en
- **步骤**：
  1. 查看 AI 分诊结果页
  2. 检查「咨询兽医」升级按钮是否存在
- **预期**：绿/黄态页面显示「咨询兽医」按钮；点击后进入等待流（source=AI_UPGRADE）
- **层级**：L0（widget）+ L2（真机）

---

### TC-5.4.02 红色态严禁出现「咨询兽医」入口（安全红线）

- **关联**：Story 5.4 · AC1 · 架构不可协商（Non-Negotiable）
- **页面/入口**：`features/triage/presentation/triage_result_view.dart` · 宠物主侧
- **前置**：AI 分诊评级为红（RED）
- **步骤**：
  1. 查看 AI 分诊红色结果页（`TriageRedResult`）
  2. 检查整个页面是否含有「咨询兽医」相关按钮/链接
- **预期**：
  - **绝无**「咨询兽医」入口（零兽医引流、零变现，架构不可协商）
  - 页面仅显示强制就医引导
- **层级**：L0（单测断言已验）

---

### TC-5.4.03 后端红色态兜底拒绝（双保险）

- **关联**：Story 5.4 · AC1 · 安全
- **页面/入口**：`POST /api/v1/consult-sessions`（source=AI_UPGRADE + RED triageTaskId）· 后端
- **前置**：构造一个 dangerLevel=RED 的 triageTaskId（可直接调 API 构造）
- **步骤**：
  1. 直接调 `POST /api/v1/consult-sessions`，body=`{source:"AI_UPGRADE", triageTaskId:<RED id>}`
- **预期**：HTTP 403 或 422 ProblemDetail，拒绝创建会话（不因前端拦截而绕过）
- **层级**：L1

---

### TC-5.4.04 AI_UPGRADE 会话 — 上下文快照落库

- **关联**：Story 5.4 · AC1
- **页面/入口**：`POST /api/v1/consult-sessions`（source=AI_UPGRADE）· 后端
- **前置**：有效绿/黄 triageTaskId；Docker pg
- **步骤**：
  1. 以 source=AI_UPGRADE + triageTaskId 发起咨询
  2. 查 DB `consult_sessions`：`ai_danger_level`、`ai_symptom_text`、`ai_image_refs`
- **预期**：
  - `ai_danger_level` = `GREEN` 或 `YELLOW`（绝无 RED）
  - `ai_symptom_text` 非空（快照当下描述）
  - `ai_image_refs` 为对象 key 列表 JSON（不含签名 URL）
  - `triage_task_id` 正确绑定
- **层级**：L1

---

### TC-5.4.05 兽医侧待接单卡片 — AI 上下文摘要

- **关联**：Story 5.4 · AC2
- **页面/入口**：`vet_inbox_page.dart` + `vet_request_detail_page.dart` · 兽医侧
- **前置**：Redis 待接单队列有 AI_UPGRADE 会话；VET 在线
- **步骤**：
  1. VET 进入待接单 Tab，查看 AI_UPGRADE 卡片：应有评级标签 + 症状摘要
  2. 点开卡片进预览页：评级标签（黄/绿色彩标）+ 完整症状描述 + 图片张数
  3. 检查 DIRECT 会话卡片：仅显示 `l10n.vetInboxDirect`，无 AI 上下文摘要
- **预期**：
  - AI_UPGRADE 卡：评级彩标 + 症状截断预览 + 图片数
  - DIRECT 卡：`vetInboxDirect` 文案，无评级/上下文
- **层级**：L0（widget）+ L1（数据）

---

### TC-5.4.06 接单后对话顶部 AI 上下文卡 — AI_UPGRADE 显示

- **关联**：Story 5.4 · AC2 · `VetAiContextCard`
- **页面/入口**：`vet_conversation_page.dart` → `VetAiContextCard` · 兽医侧
- **前置**：接单 AI_UPGRADE 会话（IN_PROGRESS）；有私密图（OSS 凭证）
- **步骤**：
  1. VET 进入对话页
  2. 检查顶部 `vetAiContextCard`（key）是否存在
  3. 检查评级标签颜色/文字（黄=`vetAiContextLevelYellow`，绿=`vetAiContextLevelGreen`）
  4. 检查症状文字和图片缩略图（签名 URL，可 load）
- **预期**：
  - `vetAiContextCard` 可见，左侧彩色竖线（`levelColor`）
  - 评级标签 + 症状文字显示
  - 图片缩略 72×72 可加载（签名 URL 有效，不含 URL 日志）
- **层级**：L1（数据）+ L2（图片签名 URL 需 OSS 凭证）

---

### TC-5.4.07 DIRECT 会话 — AI 上下文卡不渲染

- **关联**：Story 5.4 · AC2 · `VetAiContextCard`
- **页面/入口**：`vet_conversation_page.dart` · 兽医侧
- **前置**：接单 DIRECT（source=DIRECT）会话
- **步骤**：
  1. VET 进入 DIRECT 会话对话页
  2. 检查 `vetAiContextCard`
- **预期**：`VetAiContextCard` 不渲染（`hasAiContext=false`，返回 `SizedBox.shrink()`）
- **层级**：L0

---

### TC-5.4.08 前端升级不重传图片

- **关联**：Story 5.4 · AC1 · FR-4B（无需重新描述/上传）
- **页面/入口**：`consult_repository.dart` · `createFromUpgrade` 方法 · 宠物主侧
- **前置**：有效绿/黄 triageTaskId
- **步骤**：
  1. 抓包或日志确认升级发起请求 body 仅含 `{source:"AI_UPGRADE","triageTaskId":<id>}`
- **预期**：请求 body 中**无**评级/描述/图片 base64，后端经 TriageService 自行拉取（前端不重传）
- **层级**：L0/L1

---

## 5.5 腾讯 IM 会话与图文对话界面

### TC-5.5.01 兽医接单 → WAITING→IN_PROGRESS 状态迁移

- **关联**：Story 5.5 · AC1 · FR-30
- **页面/入口**：`vet_request_detail_page.dart` `vetRequestAccept` → 后端接单 · 兽医侧
- **前置**：WAITING 会话 + VET 在预览页（3min 内）；Docker pg+redis
- **步骤**：
  1. VET 在预览页点 `vetRequestAccept`（「接单」）
  2. 查 DB：session status、vet_id、im_conversation_id
  3. 查 Redis：vet:online 集合（BUSY）；`consult:waiting` 中该 sessionId
- **预期**：
  - session status=`IN_PROGRESS`，vet_id=接单兽医 id，im_conversation_id 非空
  - Redis：`vet:online` 中该 vet 标记 BUSY
  - `consult:waiting` 中该 sessionId **已出队**
  - App 跳至 `/vet/conversation/:id`
- **层级**：L1（状态迁移/Redis）+ L2（真实 IM 建会话）

---

### TC-5.5.02 接单预览 3 分钟计时 — 超时自动返回（状态 3）

- **关联**：Story 5.2 AC5 · Story 5.5 · F11
- **页面/入口**：`vet_request_detail_page.dart` `vetPreviewCountdown` · 兽医侧
- **前置**：VET 进入预览页（3min 计时已开始）
- **步骤**：
  1. 检查 AppBar 右侧 `vetPreviewCountdown`（MM:SS 倒计时，从 03:00 开始）
  2. 等待 3 分钟不操作
- **预期**：
  - 倒计时实时减少（每秒更新）
  - 3 分钟到 → SnackBar 显示 `l10n.vetRequestPreviewExpired`，自动返回待接单列表
  - 该请求**不被本兽医独占**，其他兽医待接单 Tab 中仍可见
- **层级**：L0（widget 测）

---

### TC-5.5.03 接单预览 — 用户取消后提示「已关闭」（状态 1）

- **关联**：Story 5.2 AC5 · F11
- **页面/入口**：`vet_request_detail_page.dart` 状态轮询 · 兽医侧
- **前置**：VET 处于预览页（正在倒计时）；宠物主侧同时取消该请求
- **步骤**：
  1. 宠物主侧将该会话取消（WAITING→CANCELLED）
  2. 等待 VET 侧下次轮询（最多 5s）
- **预期**：SnackBar 显示 `l10n.vetRequestClosed`（「此请求已关闭」），自动返回待接单列表
- **层级**：L0（widget mock 轮询 CANCELLED）

---

### TC-5.5.04 接单预览 — 他人抢先接单，接单 409 返回「已被抢」（状态 2）

- **关联**：Story 5.2 AC5 · F11 · 并发
- **页面/入口**：`vet_request_detail_page.dart` · 兽医侧
- **前置**：2 名 VET 同时在预览页；其中 VET B 先完成接单（session 变 IN_PROGRESS）
- **步骤（方式 A：轮询先察觉）**：VET A 轮询到 status 非 WAITING → 显示「已被接走」
- **步骤（方式 B：接单 409）**：VET A 点接单，后端原子写影响 0 行 → HTTP 409
- **预期**：
  - 两种方式均显示 `l10n.vetInboxTaken`（「此请求已被其他兽医接单」），返回待接单列表
  - `_resolved` 一次性闸确保不重复 pop
- **层级**：L0（widget mock）+ L1（真实并发）

---

### TC-5.5.05 进行中图文对话 — 文字发送接收

- **关联**：Story 5.5 · AC2 · F4（无视频）
- **页面/入口**：`consult_conversation_page.dart` / `vet_conversation_page.dart` + `ImChatPlaceholder` · 双端
- **前置**：会话 IN_PROGRESS；真实腾讯 IM 凭证 + 双端真机
- **步骤**：
  1. 宠物主侧输入文字消息，点发送
  2. 兽医侧确认收到消息（IM 实时推送）
  3. 兽医侧回复
  4. 宠物主侧确认收到
  5. 确认**无视频聊天入口**（F4 修订，V1.0.0 全程无视频）
- **预期**：
  - 文字双向收发 < 2 秒延迟
  - 无视频聊天按钮/功能（删除彻底）
  - 消息媒体由腾讯 IM 托管，后端**不中转**，日志中**无聊天内容/图片**
- **层级**：L2

---

### TC-5.5.06 进行中图文对话 — 图片发送（≤10MB）

- **关联**：Story 5.5 · AC2
- **页面/入口**：`ImChatPlaceholder` `ImInputBar` 相机按钮 · 双端
- **前置**：会话 IN_PROGRESS；真实 IM；真机
- **步骤**：
  1. 宠物主侧点相机按钮，选择一张图片上传
  2. 兽医侧查看收到的图片气泡
- **预期**：
  - 图片正常发送/展示（客户端压缩 ≤10MB）
  - 图片由腾讯 IM 托管（检查请求流，不走 OSS 上传路径、不经后端中转）
- **层级**：L2

---

### TC-5.5.07 切 Tab 后台保连消息连续（NFR-5）

- **关联**：Story 5.5 · AC2 · NFR-5
- **页面/入口**：`ConsultConversationPage` / 兽医 4-Tab · 双端
- **前置**：会话 IN_PROGRESS；真实 IM；真机
- **步骤**：
  1. 宠物主侧在对话中，切换至其他 Tab
  2. 兽医侧发送 2 条消息
  3. 宠物主侧切回对话页
- **预期**：
  - 宠物主侧消息连续不丢失，2 条消息均展示
  - IM 连接在后台期间未断开（不触发重连/丢消息）
- **层级**：L2

---

### TC-5.5.08 用户侧免责提示常驻（NFR-9）

- **关联**：Story 5.5 · AC3 · NFR-9
- **页面/入口**：`consult_conversation_page.dart` `consultDisclaimerBanner` · 宠物主侧
- **前置**：会话 IN_PROGRESS；locale=en 和 id 分别测
- **步骤**：
  1. 打开进行中会话页
  2. 检查 `consultDisclaimerBanner`
- **预期**：
  - 黄色背景 Banner 常驻顶部（`goldTint` 色）
  - 文案 `l10n.consultDisclaimer`（「本次建议为参考，最终决策权在您」）
  - en/id 均有，无中文
  - 用户无法关闭该 Banner
- **层级**：L0/L2

---

### TC-5.5.09 兽医 FR-5 辅助面板 — AI 参考回复（冷启动）

- **关联**：Story 5.5 · AC3 · FR-5 · G-2（冷启动）
- **页面/入口**：`vet_conversation_page.dart` `vetAssistPanel` · 兽医侧
- **前置**：会话 IN_PROGRESS；历史摘要库为空（G-2 冷启动）
- **步骤**：
  1. 兽医进入对话页，查看 `vetAssistPanel`（黄底面板）
  2. 检查 AI 参考回复文本
  3. 点 `vetAssistAdopt`（「采用」）
  4. 检查历史摘要区域
- **预期**：
  - AI 参考回复存在（模板化，确定性）
  - 点「采用」仅填充输入框（不自动发送，NFR-9）
  - 历史摘要区显示 `l10n.vetAssistHistoryEmpty`（冷启动空）
- **层级**：L1（数据）+ L2（渲染）

---

### TC-5.5.10 IM UserSig 安全 — SecretKey 不泄露

- **关联**：Story 5.5 · B2 · 安全
- **页面/入口**：`GET /api/v1/im/usersig` · 后端
- **前置**：已登录（USER 或 VET）；真实 IM 配置
- **步骤**：
  1. 调用 `GET /api/v1/im/usersig`，检查响应 body
  2. 抓包查看响应/日志中是否含 SecretKey
- **预期**：
  - 响应仅含 UserSig + 有效期（TTL=86400s）
  - SecretKey **绝不**出现在响应体/日志/MDC 中
  - UserSig 短时有效（非永久）
- **层级**：L2（真实凭证）

---

### TC-5.5.11 IM 回调签名校验

- **关联**：Story 5.5 · B2 · 安全
- **页面/入口**：`POST /im/callback` · 后端
- **前置**：真实腾讯 IM
- **步骤**：
  1. 发送无效签名的 callback 请求
  2. 发送伪造 body 的 callback（signature 正确但 body 篡改）
- **预期**：无效签名 → HTTP 401/403，不处理事件；合法回调正常分发
- **层级**：L2

---

## 5.6 会话结束机制与用户评分门

### TC-5.6.01 兽医结束会话 → PENDING_CLOSE

- **关联**：Story 5.6 · AC1 · FR-31
- **页面/入口**：`vet_conversation_page.dart` `vetEndSession` → `vetEndConfirmYes` · 兽医侧
- **前置**：会话 IN_PROGRESS
- **步骤**：
  1. 兽医点 AppBar `vetEndSession`（「结束会话」）
  2. 出现 AlertDialog（`vetEndConfirmTitle`）
  3. 先点 `vetEndConfirmNo`（「否」），验证会话仍 IN_PROGRESS
  4. 再点「结束会话」→ 点 `vetEndConfirmYes`（「确认」）
  5. 查 DB：session status、`pending_close_started_at`
- **预期**：
  - 点「否」：弹层关闭，会话仍 IN_PROGRESS
  - 点「确认」：session status=`PENDING_CLOSE`，`pending_close_started_at` 非空
  - 兽医 BUSY 状态解除 → Redis `vet:online` BUSY 态清除，恢复 ONLINE（可接新单）
  - 兽医侧 App 返回工作台（`/vet/workbench`）
- **层级**：L1（状态迁移）+ L2（IM 系统消息：「兽医已完成本次问诊，请为本次服务评分」）

---

### TC-5.6.02 30min 保护窗口 — 继续发消息（不关闭）

- **关联**：Story 5.6 · AC2 · FR-31
- **页面/入口**：`consult_conversation_page.dart` · 宠物主侧
- **前置**：会话 PENDING_CLOSE
- **步骤**：
  1. 宠物主侧进入对话（PENDING_CLOSE），检查 `consultRatePromptBanner` 是否出现
  2. 在 Banner 下方继续在输入框输入文字并发送
  3. 兽医侧确认收到推送/消息
- **预期**：
  - `consultRatePromptBanner` 显示（含「请为本次服务评分」+ `consultOpenRating` 按钮）
  - 输入框**未锁**（用户仍可继续发消息）
  - 消息正常发出/兽医收到
  - 30min 计时**不因继续发消息而重置**（从 `pending_close_started_at` 起算）
- **层级**：L2（继续发消息需真实 IM）

---

### TC-5.6.03 评分 → 立即关闭（CLOSED/RATED）

- **关联**：Story 5.6 · AC2 · FR-33
- **页面/入口**：`ConsultRatingDialog` · 宠物主侧
- **前置**：会话 PENDING_CLOSE；locale=en
- **步骤**：
  1. 点 `consultOpenRating`，弹出评分弹窗
  2. 点 `ratingStar_4`（4 星），在 `ratingComment` 填写≤100 字评论
  3. 点 `ratingSubmit`
  4. 查 DB：session status、`consult_ratings` 记录
- **预期**：
  - 提交成功，SnackBar 显示 `l10n.consultRateThanks`
  - session status=`CLOSED`，`closed_reason=RATED`
  - `consult_ratings` 有记录：stars=4、comment 非空、session_id 正确
  - 对话转只读（`consultTerminalLabel` 显示 `l10n.terminalClosed`，输入框消失）
  - 触发 `ConsultClosedEvent`（存档桥接 + 评分记录）
- **层级**：L1

---

### TC-5.6.04 评分校验 — 星必填（客户端预校验）

- **关联**：Story 5.6 · AC2 / AC3（校验）
- **页面/入口**：`ConsultRatingDialog` · 宠物主侧
- **前置**：评分弹窗已打开
- **步骤**：
  1. 不选星直接点 `ratingSubmit`
  2. 检查按钮状态和提示文案
- **预期**：
  - `ratingSubmit` 按钮不可点（`_stars == 0 → onPressed: null`）
  - 显示 `l10n.consultRateStarsRequired` 红色提示文案
- **层级**：L0

---

### TC-5.6.05 评分校验 — 字数超 100 字服务端拒绝

- **关联**：Story 5.6 · AC2（服务端权威）
- **页面/入口**：`POST /api/v1/consult-sessions/{id}/rating` · 后端
- **前置**：会话 PENDING_CLOSE
- **步骤**：
  1. 直调接口，comment 超过 100 字（绕过前端 `maxLength`）
- **预期**：HTTP 422 ProblemDetail，`detail` 含 comment 超长说明；无堆栈
- **层级**：L1

---

### TC-5.6.06 30min 超时自动关闭（CLOSED/UNRATED）

- **关联**：Story 5.6 · AC2 · `@Scheduled closeExpiredGates`
- **页面/入口**：后端定时扫描 / DB · 后端
- **前置**：会话 PENDING_CLOSE；`pending_close_started_at` = 31 分钟前（可直接改 DB）；Docker
- **步骤**：
  1. 等待下一次定时扫描（最多 60 秒，`@Scheduled` 间隔）
  2. 查 DB：session status、`closed_reason`、`rating_prompt_state`
- **预期**：
  - session status=`CLOSED`，`closed_reason=UNRATED`
  - `rating_prompt_state=PENDING`（等待补弹）
  - 触发 `ConsultClosedEvent`（存档桥接）
- **层级**：L1

---

### TC-5.6.07 超时未评分 — 下次进问诊 Tab 补弹一次

- **关联**：Story 5.6 · AC3 · FR-33
- **页面/入口**：`/consult`（问诊 Tab）· `ConsultEntryPage._maybePromptRating` · 宠物主侧
- **前置**：TC-5.6.06 产生的 UNRATED 会话（`rating_prompt_state=PENDING`）；USER 无活跃会话
- **步骤**：
  1. 进入 `/consult` 入口页
  2. 等待补弹弹窗（`ConsultRatingDialog` 出现）
  3. 提交评分（或跳过关闭）
  4. 再次进入 `/consult`
- **预期**：
  - 进页时自动弹出评分弹窗（`pendingRating` 返回待补弹会话）
  - 弹后（无论评分/跳过）即置 `rating_prompt_state=PROMPTED`，**不再弹**
  - 第二次进 `/consult`：**无**补弹弹窗
- **层级**：L1（标记流转）+ L2（弹窗 UI）

---

### TC-5.6.08 补评分推迟 — 有进行中会话时不补弹（F12）

- **关联**：Story 5.6 · AC5 · 决策 F12
- **页面/入口**：`ConsultEntryPage` / `ConsultCloseService.pendingRating` · 宠物主侧
- **前置**：USER 同时有 UNRATED CLOSED 会话（待补弹）+ 一个 IN_PROGRESS 活跃会话
- **步骤**：
  1. 进入 `/consult` 入口页
- **预期**：
  - 页面仅显示 `consultViewActive`（「查看进行中 →」），**不弹**评分弹窗
  - 等活跃会话结束（CLOSED/INTERRUPTED）后再进 `/consult`，才触发补弹
- **层级**：L0（widget）+ L1（后端 `pendingRating` 有活跃→空）

---

### TC-5.6.09 kill 断线保护 — IN_PROGRESS 可恢复（F12）

- **关联**：Story 5.6 · AC5 · 决策 F12
- **页面/入口**：`ConsultEntryPage` / `/consult/conversation/:id` · 宠物主侧
- **前置**：会话 IN_PROGRESS；真机
- **步骤**：
  1. 宠物主侧强制终止 App（kill 进程）
  2. 重新打开 App，进入 `/consult`
  3. 点 `consultViewActive`，进入 `/consult/conversation/:id`
- **预期**：
  - 会话**仍为 IN_PROGRESS**（不被 INTERRUPTED，非封禁中断）
  - 进入对话页后消息历史连续（IM 会话不重建）
  - 若 30 分钟内未恢复对话，触发既有 `@Scheduled closeExpiredGates` 自动关闭（CLOSED/UNRATED）
- **层级**：L2（真机）

---

### TC-5.6.10 IM→OSS 存档 — CLOSED(RATED) 触发媒体复制

- **关联**：Story 5.6 · AC2 / B3 · FR-16
- **页面/入口**：`ConsultArchiveListener` / 私密桶 · 后端
- **前置**：会话 PENDING_CLOSE；真实 IM 凭证 + OSS 凭证；Docker
- **步骤**：
  1. 用户评分 → session CLOSED(RATED) → 发 `ConsultClosedEvent`
  2. 稍后查私密桶：聊天媒体是否已复制至档案路径
  3. 查 DB：`archived` 标记是否更新
- **预期**：
  - 聊天图片从 IM 复制到私密桶档案路径（档案只含应用自有 URL，不含 IM 会过期 URL）
  - 存档失败**不阻断** CLOSED（异步 @Async，失败隔离）
  - 日志中**无**签名 URL / 健康图内容
- **层级**：L2（需真实 IM + OSS 凭证）

---

### TC-5.6.11 Admin 评分查看 — 仅运营可见

- **关联**：Story 5.6 · AC4 · FR-33 · G-1
- **页面/入口**：`/admin/vets/{id}/ratings` · `templates/admin/vet-ratings.html` · Admin 侧
- **前置**：有若干已评分的 CLOSED 会话；Docker pg
- **步骤**：
  1. Admin 进入 `/admin/vets/{id}/ratings`，查看历史评分列表 + 平均分
  2. 用 USER JWT 调用 `/api/v1/admin/vets/{id}/ratings` 或 `/api/v1/consult/ratings/{sessionId}`（若存在）
  3. 用 VET JWT 调用同端点
- **预期**：
  - Admin 页可见各兽医历史评分 + 平均分
  - USER/VET token 访问评分查看端点 → HTTP 403（评分不公开）
  - 用户/兽医侧 API 返回**不暴露**兽医公开均分
- **层级**：L1

---

## 5.7 兽医封禁与进行中会话中断处理

### TC-5.7.01 Admin 封禁兽医 — 立即生效（踢下线）

- **关联**：Story 5.7 · AC1 · FR-19
- **页面/入口**：`/admin/vets` → `AdminVetService.setBanned` · Admin/兽医侧
- **前置**：`drh_sari` 在线（Redis 有记录）；Docker pg+redis
- **步骤**：
  1. Admin 点封禁按钮（二次确认 `onsubmit confirm`），置 `status=BANNED`
  2. 立即查 DB：`vet_accounts.status`
  3. 立即查 Redis：`vet:online` 集合是否还有 `drh_sari`
  4. `drh_sari` 下次发送 API 请求（如心跳、`GET /vet/me`）
- **预期**：
  - DB：status=`BANNED`
  - Redis：`drh_sari` 从在线集合中移除
  - 被封兽医下次请求 → HTTP 401（`BannedVetFilter` 校验 isActive，type=`account-disabled`）
  - `drh_sari` App 被踢回登录页，显示 `l10n.vetBannedKicked`（「账号已被停用，请联系运营」）
- **层级**：L1（状态/Redis）+ L2（App 踢回）

---

### TC-5.7.02 封禁时进行中会话 → INTERRUPTED

- **关联**：Story 5.7 · AC2
- **页面/入口**：`ConsultInterruptService.interruptByVetBan` · 双端
- **前置**：`drh_sari` 有 2 个进行中会话（1 个 IN_PROGRESS + 1 个 PENDING_CLOSE）；Docker pg
- **步骤**：
  1. Admin 封禁 `drh_sari`
  2. 查 DB：两个会话 status
  3. 宠物主侧等待轮询（≤5s）
- **预期**：
  - 两个会话均迁移至 `INTERRUPTED`，`interrupted_reason=VET_BANNED`
  - 宠物主侧轮询到 INTERRUPTED → 对话转只读终态，`consultInterruptedState` 显示
  - 显示 `l10n.consultInterrupted`（「兽医已临时下线，本次问诊已中断」）
  - 显示 `consultReconsult` 按钮（「重新发起咨询」，跳 `/consult`）
  - 兽医侧 IM 系统消息（L2 真实 IM）：「兽医已临时下线，本次问诊已中断，请重新发起咨询」
- **层级**：L1（状态迁移）+ L2（IM 系统消息）

---

### TC-5.7.03 封禁只中断已接会话 — 未接 WAITING 不受影响

- **关联**：Story 5.7 · AC2 多态边界
- **页面/入口**：`ConsultInterruptService.interruptByVetBan` · 后端
- **前置**：`drh_sari` 有 IN_PROGRESS 会话；另有一个 WAITING 请求（未接，vet_id=null）
- **步骤**：
  1. 封禁 `drh_sari`
  2. 查 DB：WAITING 请求的 status
  3. 查 Redis：`consult:waiting` 中 WAITING 请求
- **预期**：
  - WAITING 请求 status 仍为 `WAITING`，vet_id 仍为 null
  - Redis 待接单队列中该 sessionId **仍在**
  - 其他在线兽医可正常接单
- **层级**：L1

---

### TC-5.7.04 中断后用户可立即重新发起（占用解除）

- **关联**：Story 5.7 · AC2 · 5.3 同时仅 1 个约束
- **页面/入口**：`/consult` → 发起咨询 · 宠物主侧
- **前置**：TC-5.7.02 中断后，原 INTERRUPTED 会话属于该用户
- **步骤**：
  1. 宠物主侧点 `consultReconsult` 按钮，跳 `/consult`
  2. 发起新咨询
- **预期**：
  - INTERRUPTED 会话不占用「同时仅 1 个进行中」约束
  - 新发起成功（创建新 WAITING 会话），**不**被 409 拒绝
- **层级**：L1

---

### TC-5.7.05 PENDING_CLOSE 会话也被中断（多态覆盖）

- **关联**：Story 5.7 · AC2 · spec-page-state-completeness
- **页面/入口**：`ConsultInterruptService.interruptByVetBan` · 后端
- **前置**：`drh_sari` 有 PENDING_CLOSE 会话
- **步骤**：
  1. 封禁 `drh_sari`
  2. 查 DB：该 PENDING_CLOSE 会话的 status
- **预期**：
  - PENDING_CLOSE → INTERRUPTED（封禁中断，非正常评分关闭）
  - `interrupted_reason=VET_BANNED`
  - 用户侧显示「已中断」终态，不显示评分弹窗（INTERRUPTED 不经 PENDING_CLOSE 评分门）
- **层级**：L1

---

### TC-5.7.06 封禁中断 vs kill 断线 — 状态/文案严格区分（AC3·F12）

- **关联**：Story 5.7 · AC3 · 决策 F12
- **页面/入口**：`consult_conversation_page.dart` 三态区分 · 宠物主侧
- **前置**：两个测试会话：① INTERRUPTED（封禁）② IN_PROGRESS（kill 断线，可恢复）
- **步骤（INTERRUPTED 验证）**：
  1. 进入 INTERRUPTED 会话页，检查 `consultInterruptedState`
  2. 确认有 `consultReconsult` 按钮，确认**无**活跃输入框
  3. 确认 `consultTerminalLabel` = `l10n.terminalInterrupted`
- **步骤（IN_PROGRESS 断线验证）**：
  1. 进入 IN_PROGRESS（kill 后重开）会话页
  2. 确认**无** `consultInterruptedState`，无「已中断」文案，有活跃输入框
- **预期**：两种情形文案/状态严格区分：
  - INTERRUPTED=终态（「已中断」+「重新发起」，无输入框）
  - IN_PROGRESS（断线）=可恢复（活跃对话，无终态标签、无重新发起）
  - PENDING_CLOSE=评分窗口（评分 Banner，无中断态）
- **层级**：L0（widget 锁定测试已验）+ L2（真机）

---

### TC-5.7.07 INTERRUPTED 会话 — 不触发存档桥接

- **关联**：Story 5.7 · Dev Notes · `InterruptedEvent` 不走 ConsultClosedEvent
- **页面/入口**：后端事件监听 · 后端
- **前置**：已封禁并中断会话；Docker
- **步骤**：
  1. 封禁操作后，检查后端日志中是否触发 `ImToOssArchiver` / `ConsultArchiveListener`
- **预期**：INTERRUPTED 会话**不触发** `ConsultClosedEvent`，不走存档桥接（不误存档中断会话）
- **层级**：L1

---

## 5.8 问诊 Tab 结构与问诊历史整合

### TC-5.8.01 问诊 Tab 三段结构 — 有进行中会话

- **关联**：Story 5.8 · AC1 · FR-35
- **页面/入口**：`/triage`（问诊 Tab，5-Tab 之一）· `triage_page.dart`（问诊 Tab 入口）· 宠物主侧
- **前置**：USER 有 IN_PROGRESS 会话；locale=en
- **步骤**：
  1. 进入问诊 Tab
  2. 从上至下检查三段结构：① 功能入口区（AI/兽医平级）② 进行中卡 ③ 历史列表
- **预期**：
  - ① 入口区：AI 问诊（跳 `/triage/upload`）+ 兽医咨询（跳 `/consult`）**平级**并列
  - ② 进行中卡可见（显示 IN_PROGRESS 会话信息），点击进对话页（`/consult/conversation/:id`）
  - ③ 历史列表加载（含 AI 和兽医两类条目）
  - en/id 双语，无中文
- **层级**：L1（数据）+ L2（渲染）

---

### TC-5.8.02 问诊 Tab — 无进行中会话（进行中卡不显示）

- **关联**：Story 5.8 · AC1 · spec-page-state-completeness
- **页面/入口**：`/triage`（问诊 Tab）· 宠物主侧
- **前置**：USER 无活跃会话（所有终态）
- **步骤**：
  1. 进入问诊 Tab
  2. 检查进行中卡区域
- **预期**：进行中卡**不显示**（无该区块）；功能入口区 + 历史列表正常显示
- **层级**：L2

---

### TC-5.8.03 问诊 Tab — 无历史记录空态

- **关联**：Story 5.8 · AC2 · spec-page-state-completeness
- **页面/入口**：`/triage`（问诊 Tab）· 宠物主侧
- **前置**：USER 无任何历史记录（全新账号）
- **步骤**：
  1. 进入问诊 Tab 历史列表区
- **预期**：历史列表区显示空态（无列表，无崩溃）；功能入口区正常
- **层级**：L2

---

### TC-5.8.04 历史列表 — 兽医条目内容

- **关联**：Story 5.8 · AC2
- **页面/入口**：`/triage`（问诊 Tab）历史列表 · 宠物主侧
- **前置**：有若干 CLOSED(RATED/UNRATED) + INTERRUPTED 会话
- **步骤**：
  1. 查看历史列表
  2. 检查 CLOSED(RATED) 条目：日期 + 「兽医」类型标 + 兽医昵称 + 会话摘要 + 星级评分 + 已存档标记
  3. 检查 CLOSED(UNRATED) 条目：显示「未评分」而非星级
  4. 检查 INTERRUPTED 条目：显示「已中断」终态标签
- **预期**：
  - 三类条目均正确渲染，评分展示逻辑互斥（有评分显星、无评分显「未评分」、中断显「已中断」）
  - 即使未存档（FR-16 未落），条目**仍在历史列表**（历史独立于存档）
- **层级**：L1（数据）+ L2（渲染）

---

### TC-5.8.05 历史列表 — AI 分诊条目内容

- **关联**：Story 5.8 · AC2
- **页面/入口**：`/triage`（问诊 Tab）历史列表 · 宠物主侧
- **前置**：有 AI 分诊历史（绿/黄/红三类评级）
- **步骤**：
  1. 检查 AI 类型条目：日期 + 「AI」类型标 + 评级标签（icon+text+color）+ 症状摘要
  2. 检查红色 AI 条目的评级标签颜色/文字（不依赖单一颜色，NFR-13）
- **预期**：评级标签用 icon+text+color 三重编码（无障碍 NFR-13）；症状摘要截断显示
- **层级**：L1（数据）+ L2（渲染）

---

### TC-5.8.06 历史独立于存档

- **关联**：Story 5.8 · AC2 · FR-35 vs FR-16
- **页面/入口**：`/triage`（问诊 Tab）历史列表 · 宠物主侧
- **前置**：有 CLOSED 会话，`archived=false`（未走 IM→OSS 存档）
- **步骤**：
  1. 查看历史列表，检查该 CLOSED 会话是否出现
  2. 检查条目上的「已存档」标记
- **预期**：
  - 未存档会话**仍在**历史列表（history 来自 triage/consult 自身记录，不依赖 FR-16）
  - 条目上 `archived=false`（无「已存档」标记，或显示标记为否）
- **层级**：L1

---

### TC-5.8.07 只读会话 — CLOSED(RATED)「已结束」

- **关联**：Story 5.8 · AC3 · UX-DR18④
- **页面/入口**：`/consult/conversation/:id`（终态只读）· 宠物主侧
- **前置**：会话 CLOSED(RATED)；locale=id
- **步骤**：
  1. 从历史列表点击该会话，进入 `/consult/conversation/:id`
  2. 检查 `consultTerminalLabel`
  3. 检查输入框是否存在
- **预期**：
  - AppBar 右侧显示 `l10n.terminalClosed`（「已结束」）
  - 输入框**不显示**（`!interrupted && !closed` 条件不满足，只读态）
  - 免责 Banner 仍显示
- **层级**：L2

---

### TC-5.8.08 只读会话 — CLOSED(UNRATED)「未评分」+ 补弹入口

- **关联**：Story 5.8 · AC3
- **页面/入口**：`/consult/conversation/:id`（终态只读）· 宠物主侧
- **前置**：会话 CLOSED(UNRATED)；`rating_prompt_state=PENDING`
- **步骤**：
  1. 进入该会话只读页
  2. 检查 `consultTerminalLabel`
  3. 检查 `consultRatePromptBanner` 是否出现（UNRATED 可补评）
- **预期**：
  - AppBar 右侧 `l10n.terminalUnrated`（「未评分」）
  - `consultRatePromptBanner` 出现，可点 `consultOpenRating` 补评
  - 补评后状态更新
- **层级**：L2

---

### TC-5.8.09 只读会话 — INTERRUPTED「已中断」+ 重新发起

- **关联**：Story 5.8 · AC3
- **页面/入口**：`/consult/conversation/:id`（INTERRUPTED）· 宠物主侧
- **前置**：会话 INTERRUPTED
- **步骤**：
  1. 进入该会话只读页
  2. 检查 `consultInterruptedState`（`consultInterrupted` 文案 + `consultReconsult` 按钮）
  3. 点「重新发起咨询」
- **预期**：
  - `consultTerminalLabel`=`l10n.terminalInterrupted`
  - 显示 `consultInterrupted` 文案 + cloud_off 图标
  - 点「重新发起」跳 `/consult`，可正常发起新咨询
  - 输入框不显示（终态只读）
- **层级**：L2

---

### TC-5.8.10 只读会话 — 会话不存在/越权（失效态 404/403）

- **关联**：Story 5.8 · AC3 · UX-DR18⑥
- **页面/入口**：`/consult/conversation/:id` · 宠物主侧
- **前置**：构造无效 id 或其他用户的 sessionId
- **步骤**：
  1. 访问 `/consult/conversation/999999`（不存在）
  2. 访问他人会话 id
- **预期**：
  - 404 → 友好不白屏（not-found 态，有返回按钮）
  - 403 → 无权限态（不停首页，有引导）
  - **绝不**白屏或 unhandled exception
- **层级**：L1（后端）+ L2（前端友好态渲染）

---

### TC-5.8.11 问诊 Tab 进入 — 补弹流程完整测试（含推迟逻辑）

- **关联**：Story 5.8 · B4 · 5.6 补弹整合
- **页面/入口**：`/triage`（问诊 Tab）· 宠物主侧
- **前置**：USER 有 UNRATED CLOSED 会话（`PENDING`）+ 无活跃会话
- **步骤**：
  1. 进入问诊 Tab
  2. 等待评分弹窗（`ConsultRatingDialog`）出现
  3. 提交评分（4 星）
  4. 重新进入问诊 Tab
- **预期**：
  - 进 Tab 时弹一次评分弹窗（`pendingRating` 返回待补弹）
  - 提交后 `rating_prompt_state=PROMPTED`，不再弹
  - 再次进 Tab：无弹窗
- **层级**：L1（后端）+ L2（弹窗 UI）

---

## 横切测试组

### TC-5.X.01 双语 i18n — 全 Epic 5 无中文渲染

- **关联**：全 Epic 5 · i18n · CLAUDE.md（App 绝不渲染中文）
- **页面/入口**：所有 Epic 5 前端页面 · 双端
- **前置**：locale=id（印度尼西亚语）
- **步骤**：
  1. 将设备/模拟器 locale 设为 `id`，遍历 Epic 5 全部页面（兽医登录/工作台/对话/评分/历史等）
  2. 逐一检查页面文案
- **预期**：
  - 零中文文字渲染（所有文案来自 `l10n.app_id.arb`）
  - 无硬编码印尼语（已知遗留债：`im_chat_placeholder.dart` 内聊天种子数据为印尼语种子对话，属占位，不算缺陷；但其他业务文案须走 l10n）
- **层级**：L0/L2

---

### TC-5.X.02 会话 token 不可枚举安全

- **关联**：架构 §Security · 全 Epic 5
- **页面/入口**：所有 `/api/v1/consult-sessions/**` / `/api/v1/vet/**` 端点
- **步骤**：
  1. 会话 id 使用连续枚举（如 id=1, 2, 3...），以其他 USER 的 token 访问不属于自己的会话
- **预期**：HTTP 403 ProblemDetail（非 404，防止「存在/不存在」信息泄露）
- **层级**：L1

---

### TC-5.X.03 注销时 IM 媒体删除（D2 合规）

- **关联**：架构 D2 注销 · F1末 · 5.5
- **页面/入口**：注销端点 + 存档清理 · 后端
- **前置**：USER 有历史 CLOSED 会话（已存档图片在私密桶）
- **步骤**：
  1. USER 发起注销（Story 7.3 流程）
  2. 检查私密桶：该用户的咨询图片/AI 上下文图是否已删除/匿名化
- **预期**：注销触发级联删除/匿名化，聊天媒体不保留（D2 / Story 7.3 合规要求）
- **层级**：L1/L2

---

### TC-5.X.04 后端日志无 PII / 无签名 URL / 无健康数据

- **关联**：架构 §日志 · 全 Epic 5
- **页面/入口**：后端日志 · 后端
- **步骤**：
  1. 执行 TC-5.5.01（接单）、TC-5.4.04（升级落库）、TC-5.6.03（评分）
  2. 查看日志输出（stdout JSON 格式）
- **预期**：日志中无：明文密码、BCrypt hash、JWT 明文、签名 URL、健康图内容、用户真实姓名/手机/邮箱
- **层级**：L1

---

### TC-5.X.05 并发在线兽医集合维护

- **关联**：Story 5.2 · AC4 · Redis 收窄
- **页面/入口**：`VetPresenceService` · 后端
- **前置**：3 名 VET 同时在线；Docker+redis
- **步骤**：
  1. 3 名 VET 同时在线，查 Redis `vet:online` 集合有 3 个成员
  2. VET A 主动离线，确认集合变 2
  3. VET B 未发离线请求，等 TTL 过期，确认集合变 1（TTL 兜底）
- **预期**：集合成员数与实际在线 VET 一致；无幽灵在线（惰性清理正确）
- **层级**：L1

---

### TC-5.X.06 禁中间件护栏验证

- **关联**：CLAUDE.md §Enforcement · 全 Epic 5
- **页面/入口**：`petgo-backend/pom.xml` · 后端依赖
- **步骤**：
  1. 检查 `pom.xml` 不含 `kafka`、`rabbitmq`、`redisson`、`caffeine` 相关依赖
  2. 检查 `application.yml` 中 `spring.jpa.hibernate.ddl-auto=validate`
- **预期**：
  - 无 MQ/通用缓存/分布式锁中间件
  - DDL 策略为 `validate`（schema 归 Flyway）
- **层级**：L0（静态检查）

---

## 本章遗留/盲区

1. **真实腾讯 IM L2 验收全待本地**：接单后 IM 建会话、UserSig 有效性、实时收发文字/图片、后台保连（NFR-5）、`/im/callback` 真实签名校验、IM 系统消息（「兽医已接受」/「请评分」/「已中断」），均需 SDKAppID+SecretKey+双端真机，当前 L0 用 stub IM 通过，L2 尚未验收。

2. **并发抢单 L1 真实 pg 验收待本地**：TC-5.3.08 需两进程/线程并发同时发送接单请求到真实 pg（`@Version` 乐观锁 CAS），单元测试已覆盖悲观路径，但真实并发下数据库层竞争未在 Docker pg 环境跑过。

3. **kill 断线 L2 真机覆盖不足**：TC-5.3.06（等待期 kill→CANCELLED）、TC-5.6.09（IN_PROGRESS kill→保护窗口恢复）均依赖真机 `AppLifecycleState.detached` 可靠触发。硬 SIGKILL（如 `adb shell kill -9`）在部分 Android 设备上不触发 detached，后端心跳兜底在 V1 从简未实现，该场景存在残余风险（stale WAITING 会话需运营监控）。

4. **IM→OSS 存档桥接 L2 全待本地**：TC-5.6.10 需真实腾讯 IM `ImMediaFetcher` + OSS 凭证，当前实现在无 fetcher 时为空操作（不存 IM URL），存档完整性需 L2 验。`archived` 标记目前暂 false（FR-16 档案展示待 Epic 2 profile 订阅 `ConsultClosedEvent`），TC-5.8.06 的「已存档标记」只能验 false 态，true 态待 Epic 2 接通。

5. **退单重广播多兽医收到更新待 L2 验**：TC-5.3.09 退单后 Redis 重入队 + `ConsultRequestQueuedEvent` 重广播已在 L0/L1 验，但其他在线兽医的待接单 Tab 实时刷新（需推送或前端轮询察觉）属 L2 联动场景，推送本身依赖 Epic 6/6.2 尚未实现。
