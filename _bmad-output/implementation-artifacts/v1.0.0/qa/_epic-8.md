# Epic 8：宠物第一次里程碑系统（FR-42）— 人工测试用例集

> 生成日期：2026-06-10  
> 覆盖范围：Story 8.1–8.6（数据基建与清单分配 / 列表页与徽章展示 / 系统自动完成事件订阅 / 用户打卡两路径 / 三级庆祝动效 / L 级推送与分享卡）  
> 权威决策：CROSS-STORY-DECISIONS.md F16（FR-42 全量 V1）、F6（pet_type 不可改）、F5（定时推送 @Scheduled 范式）

---

## 范围与页面/路由清单

| 页面/组件 | 路由 | 文件 | 首次登场 |
|---|---|---|---|
| 里程碑列表页 | `/profile/milestones` | `milestone_list_page.dart` | 8.2 |
| 里程碑徽章弹层（系统类/打卡类） | 同上（BottomSheet） | `milestone_list_page.dart` | 8.2 |
| 已打卡内容关联选择器（Picker） | 同上（BottomSheet） | `milestone_list_page.dart` `_CheckinPickerSheet` | 8.4 |
| 去发布→成长日历 | `/publish?preset=growth-calendar&milestoneCode=<code>` | `publish_compose_page.dart` | 8.4 |
| 三级庆祝动效弹层 | 全局 GeneralDialog | `milestone_celebration.dart` | 8.5 |
| L 级分享卡（系统分享面板） | 系统原生面板 | `share_service.dart`（2-6 复用） | 8.6 |
| 成长档案统计栏（里程碑进度条） | `/profile` | `growth_archive_page.dart` | 8.2（连带 2-4） |
| 铃铛通知中心（MILESTONE_NODE 条目） | `/notifications` | 6-6 | 8.6 |
| 后端端点 | `GET /api/v1/pet-profiles/me/milestones` | `MilestoneController` | 8.1 |
| 后端端点 | `GET /api/v1/pet-profiles/me/milestones/checkin-candidates` | `MilestoneController` | 8.4 |
| 后端端点 | `POST /api/v1/pet-profiles/me/milestones/{code}/check-in` | `MilestoneController` | 8.4 |
| 后端端点 | `POST /api/v1/pet-profiles/me/card-shares` | `MilestoneController` | 8.3（C-S3 信号） |

### 清单分配规则（V1 固定常量 `MilestoneCatalog`）

| pet_type | 总数 | S 级 | M 级 | L 级 | 健康组合依赖 |
|---|---|---|---|---|---|
| CAT（C-xx） | 30 | 15 | 10 | 5 | C-L4 = C-M3+C-M4+C-M5 全完成自动解锁 |
| DOG（D-xx） | 30 | 15 | 10 | 5 | D-L4 = D-M3+D-M4+D-M5 全完成自动解锁 |
| OTHER（G-xx） | 15 | 8 | 4 | 3 | 无健康组合依赖 |

触发方式：`SYSTEM_AUTO`（系统自动）/ `USER_CHECKIN`（用户打卡）/ `PUSH_PUBLISH`（推送+当天发布）  
对外标识：一律用 `code`（C-S1 等），响应不含自增 DB id（C5 契约护栏）

---

## 8.1 里程碑数据基建与清单分配

### TC-8.1 CAT 建档后分配 30 条里程碑 roster

- **关联**：Story 8.1 · FR-42 · AC1
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：用户已注册登录；宠物档案 pet_type=CAT 刚建档；Docker pg+redis 运行中
- **步骤**：
  1. 以有效 JWT 调用 `GET /api/v1/pet-profiles/me/milestones`
  2. 检查 HTTP 状态码
  3. 检查响应体 `totalCount`
  4. 检查响应体 `groups` 数组中 L/M/S 三分区的 `totalCount`
  5. 检查 S 区 `items` 中第一项 `code = "C-S1"` 且 `triggerType = "SYSTEM_AUTO"`
- **预期**：
  - HTTP 200
  - `totalCount = 30`
  - groups: L 区 `totalCount=5`、M 区 `totalCount=10`、S 区 `totalCount=15`
  - 所有 items 的 `completed = false`、`completedAt = null`
  - 响应体无任何 `id`（数字型自增主键）字段，`code` 字段存在且格式 C-S1/C-M1/C-L1 等
- **层级**：L1（需 Docker pg+redis）

---

### TC-8.2 DOG 建档后分配 30 条里程碑 roster

- **关联**：Story 8.1 · FR-42 · AC1
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：用户已注册登录；宠物档案 pet_type=DOG 刚建档；Docker pg+redis 运行中
- **步骤**：
  1. 以有效 JWT 调用 `GET /api/v1/pet-profiles/me/milestones`
  2. 统计响应体 `totalCount` 及 L/M/S 各分区 `totalCount`
- **预期**：
  - HTTP 200，`totalCount = 30`
  - groups: L 区 5 条（D-L1~D-L5）、M 区 10 条（D-M1~D-M10）、S 区 15 条（D-S1~D-S15）
  - 所有 items `completed = false`
- **层级**：L1

---

### TC-8.3 OTHER 建档后分配 15 条里程碑 roster

- **关联**：Story 8.1 · FR-42 · AC1
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：用户已注册登录；宠物档案 pet_type=OTHER；Docker pg+redis 运行中
- **步骤**：
  1. 以有效 JWT 调用 `GET /api/v1/pet-profiles/me/milestones`
  2. 统计 `totalCount` 及各分区
- **预期**：
  - HTTP 200，`totalCount = 15`
  - groups: L 区 3 条（G-L1~G-L3）、M 区 4 条（G-M1~G-M4）、S 区 8 条（G-S1~G-S8）
  - 无 `C-xx` 或 `D-xx` code 出现在响应中
- **层级**：L1

---

### TC-8.4 无宠物档案时请求里程碑端点返回 404

- **关联**：Story 8.1 · FR-42 · AC1
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：用户已注册登录；**未创建宠物档案**；Docker pg+redis 运行中
- **步骤**：
  1. 以有效 JWT 调用 `GET /api/v1/pet-profiles/me/milestones`
- **预期**：
  - HTTP 404
  - 响应体为 RFC 9457 ProblemDetail（含 `type`/`title`/`status`/`detail`/`instance`）
  - 响应体不含堆栈 trace
- **层级**：L1

---

### TC-8.5 未登录请求里程碑端点返回 401

- **关联**：Story 8.1 · FR-42 · AC1
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：无有效 JWT
- **步骤**：
  1. 不带 Authorization 头调用 `GET /api/v1/pet-profiles/me/milestones`
- **预期**：
  - HTTP 401，ProblemDetail 格式
- **层级**：L1

---

### TC-8.6 新建档时 milestone_completions 无预插行（初始进度全 0）

- **关联**：Story 8.1 · FR-42 · AC2
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones` · `MilestoneController`
- **前置**：用户已注册登录；宠物档案 CAT 刚建档（无任何操作）；Docker pg+redis 运行中
- **步骤**：
  1. 以有效 JWT 调用 `GET /api/v1/pet-profiles/me/milestones`
  2. 检查 `completedCount`、各 group 的 `completedCount`
  3. 直接查 DB：`SELECT COUNT(*) FROM milestone_completions mc JOIN pet_milestones pm ON mc.pet_milestone_id=pm.id WHERE pm.pet_profile_id=<id>`
- **预期**：
  - API 响应中 `completedCount = 0`，所有 group 的 `completedCount = 0`
  - DB 查询返回 0 行（不预插完成行）
  - `pet_milestones` 表有 30 条 roster 行
- **层级**：L1

---

### TC-8.7 注销后里程碑数据级联删除

- **关联**：Story 8.1 · FR-42 · AC3
- **页面/入口**：`DELETE /api/v1/me` · `ProfileDeletionService`
- **前置**：用户有 CAT 档案且至少完成 3 个里程碑；Docker pg+redis 运行中
- **步骤**：
  1. 记录注销前 `pet_milestones` 和 `milestone_completions` 的行数
  2. 调用 `DELETE /api/v1/me`（或走注销流程）
  3. 直接查 DB 验证
- **预期**：
  - `milestone_completions` 表对应用户宠物的完成行全部删除
  - `pet_milestones` 表对应用户宠物的 roster 行全部删除
  - `pet_profiles` 表对应行删除
  - 删除顺序：completions → roster → 档案（符合 D1/D2 家族）
- **层级**：L1

---

### TC-8.8 响应体不含自增 DB id（C5 契约金标）

- **关联**：Story 8.1 · FR-42 · AC3（C5）
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones`
- **前置**：CAT 档案已建；Docker pg+redis 运行中
- **步骤**：
  1. 调用 `GET /api/v1/pet-profiles/me/milestones`
  2. 检查响应 JSON 全文
- **预期**：
  - 响应中无名为 `id`、`milestoneId`、`petMilestoneId` 等数字型主键字段
  - 每个 item 含 `code`（字符串，格式 C-S1/D-M3/G-L1 等）
  - `triggerType` 字段值为 UPPER_SNAKE 格式（`SYSTEM_AUTO` / `USER_CHECKIN` / `PUSH_PUBLISH`）
- **层级**：L1（L0 金标单测已覆盖，此处做人工确认）

---

## 8.2 里程碑列表页与徽章展示

### TC-8.9 列表页顶部：宠物头像+名字+总进度条

- **关联**：Story 8.2 · FR-42 · AC1
- **页面/入口**：`/profile/milestones` · `milestone_list_page.dart` `_Header`
- **前置**：mock 模式（`PETGO_MOCK=true`，默认）；CAT 宠物档案；已完成若干里程碑
- **步骤**：
  1. 进入成长档案页，点击里程碑进度条区域进入 `/profile/milestones`
  2. 观察页面顶部
- **预期**：
  - AppBar 标题显示「Milestones」（en）或「Tonggak」（id）
  - 顶部卡片含宠物头像（或默认 pets 图标，mintTint 背景）
  - 宠物名字字体 fontWeight w800，fontSize 17
  - 进度文案格式「X / N milestones done」（en）或「X / N tonggak tercapai」（id）
  - LinearProgressIndicator 显示对应比例，颜色为 mint（`#7FD1AE`）
- **层级**：L2（真机/模拟器视觉）

---

### TC-8.10 列表页 L/M/S 三级分区标题与进度

- **关联**：Story 8.2 · FR-42 · AC1
- **页面/入口**：`/profile/milestones` · `milestone_list_page.dart` `_GroupSection`
- **前置**：mock 模式；CAT 档案含若干已完成里程碑
- **步骤**：
  1. 进入里程碑列表页
  2. 观察三个分区标题和右侧 `X/N` 计数
- **预期**：
  - 分区顺序：L 区在上（「L · Major milestones」/ 「L · Tonggak besar」）、M 区、S 区在下
  - 每区右侧显示该级 `completedCount/totalCount`，字体 ink2、w700
  - CAT 清单：L 区总数 5、M 区 10、S 区 15
- **层级**：L2

---

### TC-8.11 已完成徽章显示彩色（mint 描边 + 奖杯图标）

- **关联**：Story 8.2 · FR-42 · AC2
- **页面/入口**：`/profile/milestones` · `_Badge`
- **前置**：mock 模式；C-S1（Profile created）已完成
- **步骤**：
  1. 进入里程碑列表页，找到 S 分区第一枚徽章（C-S1）
  2. 观察徽章样式
- **预期**：
  - 徽章圆形，背景 mintTint（浅薄荷绿），边框 mint（#7FD1AE），宽度 2
  - 图标 `emoji_events_rounded`（奖杯），颜色 mint700
  - 徽章下方文案「Profile created」（en）或「Profil dibuat」（id），颜色 ink，fontWeight w700
  - 文案来自 `kMilestoneTitles["C-S1"]`，**不显示后端中文「宠物档案创建完成」**
- **层级**：L2

---

### TC-8.12 未完成徽章显示灰色锁定轮廓

- **关联**：Story 8.2 · FR-42 · AC2
- **页面/入口**：`/profile/milestones` · `_Badge`
- **前置**：mock 模式；C-S6（First bath）未完成
- **步骤**：
  1. 进入里程碑列表页，找到 S 区中 C-S6 徽章
  2. 观察样式
- **预期**：
  - 圆形背景 line2（灰色），边框 line（灰）
  - 图标 `lock_outline_rounded`，颜色 muted
  - 徽章下方文案「First bath」（en）或「Mandi pertama」（id），颜色 muted，fontWeight w500
  - 无奖杯图标，无 mint 颜色
- **层级**：L2

---

### TC-8.13 系统自动类未完成徽章点击弹层——仅显示说明文案

- **关联**：Story 8.2 · FR-42 · AC3
- **页面/入口**：`/profile/milestones` · `_showBadgeSheet`
- **前置**：mock 模式；C-S2（First growth photo）未完成，triggerType=SYSTEM_AUTO
- **步骤**：
  1. 在 S 分区找到 C-S2 徽章（灰锁）
  2. 点击该徽章
  3. 观察弹出的 BottomSheet
- **预期**：
  - 弹层顶部：`flag_outlined` 图标（muted 色）+ 文案「First growth photo」（en）
  - 说明文案：「Lights up automatically once you do this.」（en）/ 「Otomatis menyala setelah kamu melakukannya.」（id）
  - **无**「I did it」和「Post now」按钮
  - **无**已完成日期行（未完成）
- **层级**：L2

---

### TC-8.14 已完成系统自动类徽章点击弹层——显示完成日期

- **关联**：Story 8.2 · FR-42 · AC3
- **页面/入口**：`/profile/milestones` · `_showBadgeSheet`
- **前置**：mock 模式；C-S1（Profile created）已完成，有 completedAt
- **步骤**：
  1. 在 S 分区找到 C-S1 彩色徽章
  2. 点击该徽章
- **预期**：
  - 弹层顶部：`emoji_events_rounded` 图标（mint700 色）+ 「Profile created」
  - 完成日期行：「Completed on YYYY-MM-DD」（en）/ 「Selesai pada YYYY-MM-DD」（id），颜色 mint700
  - 说明文案：「Lights up automatically once you do this.」
  - **无**「I did it」/ 「Post now」按钮（已完成不出打卡入口）
- **层级**：L2

---

### TC-8.15 用户打卡类未完成徽章点击弹层——出现两入口按钮

- **关联**：Story 8.2 · FR-42 · AC3
- **页面/入口**：`/profile/milestones` · `_showBadgeSheet`
- **前置**：mock 模式；C-S6（First bath / USER_CHECKIN）未完成
- **步骤**：
  1. 在 S 分区找到 C-S6 灰锁徽章
  2. 点击该徽章
  3. 观察弹层内容
- **预期**：
  - 标题：「First bath」（en）/ 「Mandi pertama」（id）
  - 说明文案：「Already did this? Tap "I did it" to link a moment, or "Post now" to add one.」（en）/ 「Sudah pernah? Ketuk "Sudah dilakukan" untuk menautkan momen, atau "Unggah sekarang".」（id）
  - OutlinedButton 文案：「I did it」（en）/ 「Sudah dilakukan」（id），ValueKey = `milestoneCheckedIn`
  - FilledButton 文案：「Post now」（en）/ 「Unggah sekarang」（id），ValueKey = `milestoneGoPublish`
- **层级**：L2

---

### TC-8.16 用户打卡类已完成徽章点击弹层——仅只读说明无按钮

- **关联**：Story 8.2 · FR-42 · AC3
- **页面/入口**：`/profile/milestones` · `_showBadgeSheet`
- **前置**：mock 模式；C-S6 已完成（mock 中标为 completed=true）
- **步骤**：
  1. 找到 C-S6 彩色徽章，点击
- **预期**：
  - 显示完成日期行
  - **无**「I did it」/ 「Post now」按钮（已完成不出打卡入口）
- **层级**：L2

---

### TC-8.17 PUSH_PUBLISH 类里程碑徽章点击弹层——显示对应说明文案

- **关联**：Story 8.2 · FR-42 · AC3
- **页面/入口**：`/profile/milestones` · `_showBadgeSheet`
- **前置**：mock 模式；C-L1（First birthday，PUSH_PUBLISH）未完成
- **步骤**：
  1. 在 L 分区找到 C-L1 徽章，点击
- **预期**：
  - 说明文案：「We'll remind you on the day — post a growth-calendar moment to light it up.」（en）/ 「Kami ingatkan di harinya — unggah momen kalender pertumbuhan untuk menyalakannya.」（id）
  - **无**「I did it」/ 「Post now」打卡入口（非 USER_CHECKIN 类）
- **层级**：L2

---

### TC-8.18 列表页加载失败显示 F13 统一失败态并可重试

- **关联**：Story 8.2 · FR-42 · AC4（F13）
- **页面/入口**：`/profile/milestones` · `_MilestoneError`
- **前置**：mock 模式，临时修改 mock 使 `/pet-profiles/me/milestones` 返回 500 或断网模拟
- **步骤**：
  1. 在网络不可用或服务异常状态进入 `/profile/milestones`
  2. 等待加载完成
  3. 观察错误态 UI
  4. 点击重试按钮
- **预期**：
  - 显示 `cloud_off_rounded` 图标（muted 色）
  - 文案：「Failed to load milestones, tap to retry」（en）/ 「Gagal memuat tonggak, ketuk untuk coba lagi」（id）
  - TextButton 文案：「Retry」（en）/ 「Coba lagi」（id），ValueKey = `milestoneRetry`
  - 点击重试后重新发起请求（列表 refresh）
- **层级**：L2

---

### TC-8.19 成长档案统计栏「已完成 X/N」真供数（连带 AC5）

- **关联**：Story 8.2 · FR-42 · AC5（C5）
- **页面/入口**：`/profile` · `growth_archive_page.dart`（统计栏进度条）
- **前置**：mock 模式；mock 中 `archive-stats` 的 `milestoneCompleted`/`milestoneTotal` 同源里程碑列表数据
- **步骤**：
  1. 进入成长档案页
  2. 找到里程碑进度条区域
  3. 对比「X / N milestones done」与 `/me/milestones` 实际 completedCount/totalCount
- **预期**：
  - 进度条显示与里程碑列表同源数据，`milestoneCompleted` 与 `milestoneTotal` 一致
  - 点击进度条跳转至 `/profile/milestones`
- **层级**：L2（mock）/ L1（真后端联调一致性）

---

### TC-8.20 i18n 切换：en→id 所有里程碑文案切换正确

- **关联**：Story 8.2 · FR-42 · AC1~AC3；i18n 债
- **页面/入口**：`/profile/milestones` · `milestone_list_page.dart`
- **前置**：mock 模式；设备语言切换至印尼语（id）
- **步骤**：
  1. 将设备/App 语言切换为 id
  2. 进入里程碑列表页
  3. 逐一检查页面文案
- **预期**：
  - AppBar：「Tonggak」
  - 分区标题：「L · Tonggak besar」/「M · Tonggak penting」/「S · Tonggak harian」
  - 进度文案：「X / N tonggak tercapai」
  - C-S1 徽章下方：「Profil dibuat」（**不显示中文「宠物档案创建完成」**）
  - C-S6 弹层按钮：「Sudah dilakukan」/「Unggah sekarang」
  - 系统自动提示：「Otomatis menyala setelah kamu melakukannya.」
  - 重试按钮：「Coba lagi」
- **层级**：L2
- **注意**：印尼语翻译为工程初稿，`kMilestoneTitles` 中术语（如 `steril`、`perawatan`）需印尼母语者复核（已知盲区）

---

## 8.3 里程碑系统自动完成（领域事件订阅）

### TC-8.21 建档自动完成 C-S1（ProfileCreatedEvent）

- **关联**：Story 8.3 · FR-42 · AC1
- **页面/入口**：建档流程 → 后端 `ProfileService.create` 发布 `ProfileCreatedEvent` → `MilestoneAutoCompleteListener` → `milestone_completions` 落库
- **前置**：用户已注册登录；**尚未创建宠物档案**；Docker pg+redis 运行中
- **步骤**：
  1. 完成创建宠物档案（pet_type=CAT）
  2. 等待后端异步处理（@Async @TransactionalEventListener，约 1-2 秒）
  3. 调用 `GET /api/v1/pet-profiles/me/milestones`
  4. 查找 code=C-S1 的 item
- **预期**：
  - C-S1 `completed = true`，`completedAt` 不为 null（UTC 时间戳）
  - `completedCount` ≥ 1
  - DB：`milestone_completions` 中存在对应行，`source = "SYSTEM_AUTO"`，`linked_content_id = null`
- **层级**：L1

---

### TC-8.22 首张成长日历照片自动完成 C-S2（ContentPublishedEvent，成长日历计数≥1）

- **关联**：Story 8.3 · FR-42 · AC1
- **页面/入口**：发布成长日历 → `ContentService.publish` → `ContentPublishedEvent` → `MilestoneAutoCompleteListener`
- **前置**：CAT 档案；C-S1 已完成；C-S2 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 发布第一张成长日历类型内容
  2. 等待后端异步处理
  3. 调用 `GET /api/v1/pet-profiles/me/milestones`，查找 C-S2
- **预期**：
  - C-S2 `completed = true`
  - DB：`source = "SYSTEM_AUTO"`
- **层级**：L1

---

### TC-8.23 分享名片信号触发 C-S3（CardSharedEvent）

- **关联**：Story 8.3 · FR-42 · AC1
- **页面/入口**：名片分享 FAB → `POST /api/v1/pet-profiles/me/card-shares`（fire-and-forget）→ `ProfileService.recordCardShared` → `CardSharedEvent` → C-S3
- **前置**：CAT 档案；C-S3 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 在名片页点击分享按钮（FAB），触发 `POST /api/v1/pet-profiles/me/card-shares`
  2. 确认 204 响应
  3. 等待后端异步处理
  4. 调用 `GET /api/v1/pet-profiles/me/milestones`，查找 C-S3
- **预期**：
  - `POST /api/v1/pet-profiles/me/card-shares` 返回 HTTP 204
  - C-S3 `completed = true`，`source = "SYSTEM_AUTO"`
- **层级**：L1

---

### TC-8.24 重复分享信号幂等——C-S3 不重复完成

- **关联**：Story 8.3 · FR-42 · AC3（幂等）
- **页面/入口**：`POST /api/v1/pet-profiles/me/card-shares`
- **前置**：CAT 档案；C-S3 已完成（第一次已触发）；Docker pg+redis 运行中
- **步骤**：
  1. 再次调用 `POST /api/v1/pet-profiles/me/card-shares`（第二次）
  2. 等待后端处理
  3. 查 DB `milestone_completions`
- **预期**：
  - 第二次 POST 返回 204（幂等不报错）
  - DB 中该宠物 C-S3 completion 仍只有一行（`pet_milestone_id` 唯一约束生效）
- **层级**：L1

---

### TC-8.25 首次保存兽医问诊结论自动完成 C-S4（HealthArchivedEvent）

- **关联**：Story 8.3 · FR-42 · AC1
- **页面/入口**：健康问诊 → `HealthEventService.recordDecision(ARCHIVED)` → `HealthArchivedEvent` → C-S4
- **前置**：CAT 档案；C-S4 未完成；完成一次问诊并保存结论；Docker pg+redis 运行中
- **步骤**：
  1. 完成一次兽医问诊，状态到达 ARCHIVED
  2. 等待后端处理
  3. 调用 `GET /api/v1/pet-profiles/me/milestones`，查找 C-S4
- **预期**：
  - C-S4 `completed = true`，`source = "SYSTEM_AUTO"`
- **层级**：L1

---

### TC-8.26 首次发布日常分享自动完成 C-S5

- **关联**：Story 8.3 · FR-42 · AC1
- **页面/入口**：发布日常分享（非成长日历）→ `ContentService.publish` → `ContentPublishedEvent` → C-S5
- **前置**：CAT 档案；C-S5 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 发布一条日常分享类型内容
  2. 等待后端处理
  3. 查询 C-S5 完成状态
- **预期**：
  - C-S5 `completed = true`
- **层级**：L1

---

### TC-8.27 成长日历满 10 条自动完成 C-M10（计数阈值）

- **关联**：Story 8.3 · FR-42 · AC2
- **页面/入口**：`ContentService.publish` → `ContentPublishedEvent`（携成长日历总数）→ `MilestoneCompletionService.onGrowthMomentCount`
- **前置**：CAT 档案；已有 9 条成长日历内容；C-M10 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 发布第 10 条成长日历
  2. 等待后端处理
  3. 查询 C-M10 完成状态
- **预期**：
  - C-M10 `completed = true`，`source = "SYSTEM_AUTO"`
  - 第 9 条发布后 C-M10 仍未完成（计数不足）
- **层级**：L1

---

### TC-8.28 成长日历满 30 条自动完成 C-L5（L 级计数阈值）

- **关联**：Story 8.3 · FR-42 · AC2
- **页面/入口**：同 TC-8.27
- **前置**：CAT 档案；已有 29 条成长日历；C-L5 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 发布第 30 条成长日历
  2. 等待后端处理
  3. 查询 C-L5 完成状态
- **预期**：
  - C-L5 `completed = true`，`source = "SYSTEM_AUTO"`
  - 因 C-L5 是 L 级，同时验证 `milestone_completions` 落库且后续通知触发（见 TC-8.52）
- **层级**：L1

---

### TC-8.29 健康组合依赖自动解锁 C-L4（C-M3+C-M4+C-M5 全完成）

- **关联**：Story 8.3 · FR-42 · AC2（HEALTH_COMBO：C-L4 = C-M3+C-M4+C-M5）
- **页面/入口**：`MilestoneCompletionService.maybeUnlockHealthCombo`
- **前置**：CAT 档案；C-M3（First vaccination）和 C-M4（First deworming）已完成；C-M5（First vet visit）未完成；C-L4 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 触发 C-M5 完成（打卡或自动，视 trigger_type 而定，C-M5=USER_CHECKIN）
  2. 等待后端处理
  3. 查询 C-L4 完成状态
- **预期**：
  - C-L4 `completed = true`，`source = "SYSTEM_AUTO"`（组合依赖自动解锁）
  - 仅 C-M5 触发后才解锁（M3+M4 已完成但 M5 未完成时 C-L4 不解锁）
- **层级**：L1

---

### TC-8.30 陪伴满 30 天定时扫描完成 C-M8（@Scheduled 01:10 UTC）

- **关联**：Story 8.3 · FR-42 · AC2（`MilestoneScheduledCompleter`）
- **页面/入口**：`MilestoneScheduledCompleter`（01:10 UTC 每日扫描）
- **前置**：CAT 档案建档日期恰好 30 天前；C-M8 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 确认宠物档案 `created_at` 距今 ≥ 30 天
  2. 手动触发或等待 @Scheduled（01:10 UTC）执行
  3. 查询 C-M8 完成状态
- **预期**：
  - C-M8 `completed = true`，`source = "SYSTEM_AUTO"`
  - 29 天时不触发
- **层级**：L1

---

### TC-8.31 并发重复事件幂等——唯一约束兜底

- **关联**：Story 8.3 · FR-42 · AC3
- **页面/入口**：`MilestoneCompletionService.completeForOwner`（`milestone_completions` 唯一约束）
- **前置**：CAT 档案；C-S2 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 并发触发两次 `ContentPublishedEvent`（或直接并发调用 completeForOwner）
  2. 检查 DB
- **预期**：
  - `milestone_completions` 表中 C-S2 仅有一行（`uq_milestone_completions_milestone` 唯一约束生效）
  - 没有异常/500 抛出（DataIntegrityViolationException 被安全处理）
- **层级**：L1

---

## 8.4 里程碑用户打卡两路径

### TC-8.32 「I did it」打开内容关联选择器 Picker

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`/profile/milestones` → 徽章弹层 → `milestoneCheckedIn` → `_CheckinPickerSheet`
- **前置**：mock 模式；C-S6（First bath / USER_CHECKIN）未完成；mock 中有 ≥1 条成长日历候选
- **步骤**：
  1. 点击 C-S6 徽章，徽章弹层出现
  2. 点击「I did it」按钮（ValueKey `milestoneCheckedIn`）
- **预期**：
  - 徽章弹层关闭
  - 新弹层（Picker）出现，标题「Link a growth-calendar moment」（en）/ 「Tautkan momen kalender pertumbuhan」（id）
  - 列表中显示本人成长日历内容（缩略图/文案/事件日期）
- **层级**：L2

---

### TC-8.33 Picker 内容列表：已关联内容置灰不可选

- **关联**：Story 8.4 · FR-42 · AC2
- **页面/入口**：`_CheckinPickerSheet` · `_CandidateTile`
- **前置**：mock 模式；mock 中至少一条候选内容 `linked=true`（已关联其它里程碑）
- **步骤**：
  1. 打开 Picker（如 TC-8.32 步骤）
  2. 观察已关联内容行
- **预期**：
  - 已关联内容行 `Opacity = 0.4`（置灰）
  - 右侧显示「Linked」（en）/ 「Tertaut」（id）标签
  - 点击置灰行无反应（`onTap = null`）
  - 未关联内容行右侧显示 `chevron_right_rounded` 箭头，可点击
- **层级**：L2

---

### TC-8.34 Picker 选择一条内容完成打卡——里程碑标记完成

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`_CandidateTile._confirm` → `POST /api/v1/pet-profiles/me/milestones/{code}/check-in`
- **前置**：mock 模式；C-S6 未完成；有可选内容（linked=false）
- **步骤**：
  1. 打开 C-S6 的 Picker
  2. 选择一条未关联的成长日历内容（点击行）
  3. 观察 Picker 关闭后的反应
- **预期**：
  - Picker 关闭
  - 里程碑列表刷新（`milestoneListProvider` invalidated）
  - C-S6 徽章变为彩色（已完成）
  - 庆祝动效触发（S 级：半屏弹层，见 TC-8.44）
- **层级**：L2

---

### TC-8.35 Picker 为空时显示空态提示

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`_CheckinPickerSheet`
- **前置**：mock 模式；mock 中成长日历候选列表为空
- **步骤**：
  1. 点击 USER_CHECKIN 类里程碑「I did it」
  2. Picker 打开后内容区
- **预期**：
  - 显示空态文案：「No growth-calendar moments yet. Tap "Post now" to add one.」（en）/ 「Belum ada momen kalender pertumbuhan. Ketuk "Unggah sekarang".」（id）
  - 无列表内容
- **层级**：L2

---

### TC-8.36 Picker 加载失败显示重试入口

- **关联**：Story 8.4 · FR-42 · AC1（F13）
- **页面/入口**：`_CheckinPickerSheet`（error 态）
- **前置**：mock 模式，临时令候选 API 返回 500
- **步骤**：
  1. 打开 Picker
  2. 等待加载错误
- **预期**：
  - 显示重试 TextButton（ValueKey `milestoneCheckinRetry`）
  - 点击重试后重新请求候选列表
- **层级**：L2

---

### TC-8.37 打卡一内容一里程碑——后端拒绝重复关联（409）

- **关联**：Story 8.4 · FR-42 · AC2
- **页面/入口**：`POST /api/v1/pet-profiles/me/milestones/{code}/check-in`
- **前置**：CAT 档案；内容 A 已关联 C-S6；Docker pg+redis 运行中
- **步骤**：
  1. 尝试将同一内容 A 关联到另一个 USER_CHECKIN 里程碑（如 C-S7）
  2. 调用 `POST /api/v1/pet-profiles/me/milestones/C-S7/check-in`，body `{contentId: A_id}`
- **预期**：
  - HTTP 409 Conflict
  - ProblemDetail `detail` 描述「该内容已关联其它里程碑」
- **层级**：L1

---

### TC-8.38 打卡非本人内容——后端返回 422

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`POST /api/v1/pet-profiles/me/milestones/{code}/check-in`
- **前置**：CAT 档案；内容 B 属于另一个用户；Docker pg+redis 运行中
- **步骤**：
  1. 调用 `POST .../C-S6/check-in`，body `{contentId: B_id}`
- **预期**：
  - HTTP 422 Unprocessable Entity
  - `detail`：「只能关联本人成长日历内容」
- **层级**：L1

---

### TC-8.39 对非 USER_CHECKIN 类里程碑打卡——后端返回 422

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`POST /api/v1/pet-profiles/me/milestones/C-S1/check-in`（C-S1 是 SYSTEM_AUTO）
- **前置**：CAT 档案；Docker pg+redis 运行中
- **步骤**：
  1. 调用 `POST .../C-S1/check-in`，body `{contentId: 任意}`
- **预期**：
  - HTTP 422
  - `detail`：「该里程碑非用户打卡类，不支持手动关联」
- **层级**：L1

---

### TC-8.40 对已完成里程碑打卡——后端返回 409

- **关联**：Story 8.4 · FR-42 · AC1
- **页面/入口**：`POST /api/v1/pet-profiles/me/milestones/{code}/check-in`
- **前置**：CAT 档案；C-S6 已完成；Docker pg+redis 运行中
- **步骤**：
  1. 调用 `POST .../C-S6/check-in`，body `{contentId: 任意本人成长日历 id}`
- **预期**：
  - HTTP 409
  - `detail`：「该里程碑已完成」
- **层级**：L1

---

### TC-8.41 「Post now」路径——跳转发布页预选成长日历携 milestoneCode

- **关联**：Story 8.4 · FR-42 · AC3
- **页面/入口**：徽章弹层 → `milestoneGoPublish` → `/publish?preset=growth-calendar&milestoneCode=C-S6`
- **前置**：mock 模式；C-S6 未完成
- **步骤**：
  1. 点击 C-S6 徽章弹层中「Post now」按钮
- **预期**：
  - 徽章弹层关闭
  - 跳转至发布页，URL/路由参数含 `preset=growth-calendar&milestoneCode=C-S6`
  - 发布页预选成长日历类型（仅跳转，不预锁定内容）
- **层级**：L2

---

### TC-8.42 「Post now」路径——发布成长日历成功后自动打卡回填

- **关联**：Story 8.4 · FR-42 · AC3
- **页面/入口**：`PublishComposePage`（milestoneCode 线程）→ `checkIn` 回填
- **前置**：mock 模式；C-S6 未完成；跳转至发布页携 `milestoneCode=C-S6`
- **步骤**：
  1. 在发布页选好成长日历内容并发布成功
  2. 观察发布成功后是否自动触发 C-S6 打卡回填
  3. 返回里程碑列表
- **预期**：
  - 发布成功后 App 自动调用 check-in（best-effort，失败可回页手动已打卡）
  - C-S6 变为已完成（若 check-in 成功），庆祝动效触发
  - 若 check-in 失败，用户看到提示并可手动从 Picker 补录
- **层级**：L2

---

### TC-8.43 「Post now」路径——发布非成长日历类型不自动回填

- **关联**：Story 8.4 · FR-42 · AC3
- **页面/入口**：`PublishComposePage`
- **前置**：mock 模式；携 `milestoneCode=C-S6` 跳转；在发布页切换为「日常分享」类型并发布
- **步骤**：
  1. 从里程碑「Post now」进入发布页
  2. 切换为日常分享类型
  3. 发布成功
  4. 返回里程碑列表，查看 C-S6 状态
- **预期**：
  - C-S6 仍为未完成（编辑切其它类型并发布 → 不自动完成）
- **层级**：L2

---

## 8.5 里程碑三级庆祝动效

### TC-8.44 S 级打卡完成——半屏庆祝弹层 1.5s 自动消失

- **关联**：Story 8.5 · FR-42 · AC1
- **页面/入口**：`_CandidateTile._confirm` → `showMilestoneCelebration(context, item)` → `_half`
- **前置**：mock 模式；C-S6（S 级，USER_CHECKIN）未完成；Picker 中有可选内容
- **步骤**：
  1. 在 Picker 中选择一条内容完成 C-S6 打卡
  2. 观察 Picker 关闭后出现的动效弹层
  3. 观察弹层自动消失
- **预期**：
  - 弹层对齐底部（Align.bottomCenter），ValueKey = `milestoneCelebrationS`
  - 弹层内容：`emoji_events_rounded` 奖杯图标 + 「Milestone unlocked!」（en）/ 「Tonggak terbuka!」（id）+ 里程碑标题「First bath」（en）
  - 弹层**不阻断**主界面（`barrierDismissible = true`）
  - 约 1.5 秒后弹层**自动消失**
  - 进入动效时 `elasticOut` 缩放曲线（弹性弹出效果）
- **层级**：L2

---

### TC-8.45 M 级打卡完成——全屏 3s 动效

- **关联**：Story 8.5 · FR-42 · AC2
- **页面/入口**：`showMilestoneCelebration` → `_full`
- **前置**：mock 模式；C-M1（First outdoor adventure，M 级，USER_CHECKIN）未完成；可打卡内容存在
- **步骤**：
  1. 完成 C-M1 打卡
  2. 观察全屏动效
- **预期**：
  - 全屏半透明蒙层（barrierColor alpha 0.55）
  - 中心：大徽章（120px，mintTint 圆形 + mint 边框 + mint700 奖杯）随 `elasticOut` 缩放
  - 白色文案「Milestone unlocked!」+ 「First outdoor adventure」（en）
  - `FadeTransition` 渐入效果（`_controller`）
  - ValueKey = `milestoneCelebrationM`
  - 约 3 秒后自动消失
- **层级**：L2

---

### TC-8.46 L 级打卡完成——Duolingo 开宝箱交互

- **关联**：Story 8.5 · FR-42 · AC3
- **页面/入口**：`showMilestoneCelebration` → `_chest`
- **前置**：mock 模式；C-L4（All health milestones，L 级）可触发（通过 mock 完成前置）
- **步骤**：
  1. 完成触发 C-L4（或 mock 直接返回 L 级完成）
  2. 观察 L 级庆祝弹层
  3. 点击宝箱图标
  4. 观察开箱后动效
  5. 等待动效结束（分享弹出）
- **预期**：
  - 初始状态：全屏蒙层 + 中心 `card_giftcard_rounded` 图标（gold 色，140px），ValueKey = `milestoneChestTap`
  - 底部提示文案：「Tap to open」（en）/ 「Ketuk untuk buka」（id）
  - 点击宝箱后：宝箱消失，大徽章（140px）以 `elasticOut` 弹出，文案切换为「Milestone unlocked!」
  - 约 4 秒后：`onShare` 被调用（系统分享面板弹出），随后弹层消失
- **层级**：L2

---

### TC-8.47 S/M 级动效不调用 onShare

- **关联**：Story 8.5 · FR-42 · AC1/AC2
- **页面/入口**：`showMilestoneCelebration`
- **前置**：mock 模式；S 级或 M 级完成
- **步骤**：
  1. 完成 S 或 M 级里程碑打卡
  2. 观察动效结束后是否有分享面板
- **预期**：
  - S/M 级动效结束后**不弹出**系统分享面板
  - `onShare = null`（仅 L 级注入分享回调）
- **层级**：L2

---

### TC-8.48 动效文案本地化正确（en/id）

- **关联**：Story 8.5 · FR-42 · AC1~AC3
- **页面/入口**：`milestone_celebration.dart`
- **前置**：mock 模式；设备分别设置 en 和 id；准备可触发 S/M/L 级完成的 mock 数据
- **步骤**：
  1. en 模式：完成 S/M/L 级，逐级观察文案
  2. id 模式：同上
- **预期**：
  - en：「Milestone unlocked!」/「Tap to open」，里程碑标题为英文（来自 `kMilestoneTitles`）
  - id：「Tonggak terbuka!」/「Ketuk untuk buka」，里程碑标题为印尼语
  - 任何情况下**不显示中文**
- **层级**：L2

---

## 8.6 L 级里程碑达成推送与分享卡

### TC-8.49 L 级达成（用户打卡路径）→ MilestoneCompletedEvent → MILESTONE_NODE 通知入库

- **关联**：Story 8.6 · FR-42 · AC1
- **页面/入口**：后端 `MilestoneNotifyListener.onMilestoneCompleted` → `NotificationService`
- **前置**：CAT 档案；C-L4 将在此步完成（前置 C-M3+C-M4 已完成，完成 C-M5 触发组合自动解锁）；Docker pg+redis 运行中
- **步骤**：
  1. 完成 C-M5 打卡，触发 C-L4 组合自动解锁（L 级）
  2. 等待 `MilestoneNotifyListener`（@Async @TransactionalEventListener）处理
  3. 查询 DB：`SELECT * FROM notifications WHERE user_id=<id> AND type='MILESTONE_NODE' ORDER BY created_at DESC LIMIT 1`
- **预期**：
  - `notifications` 表中有新行，`type = 'MILESTONE_NODE'`，`target_ref = 'C-L4'`
  - `deep_link` 指向 `/profile/milestones`（或等效路由）
- **层级**：L1

---

### TC-8.50 S/M 级达成——不触发 MILESTONE_NODE 推送

- **关联**：Story 8.6 · FR-42 · AC1（L0 金标逻辑，人工确认）
- **页面/入口**：`MilestoneNotifyListener`
- **前置**：CAT 档案；完成 S 级（C-S6）或 M 级（C-M1）里程碑；Docker pg+redis 运行中
- **步骤**：
  1. 完成 C-S6 或 C-M1 打卡
  2. 等待后端处理
  3. 查询 DB `notifications` 表
- **预期**：
  - **不产生**类型为 `MILESTONE_NODE` 的通知行（仅 L 级才推）
- **层级**：L1

---

### TC-8.51 MILESTONE_NODE 通知在铃铛通知中心显示（6-6 连带）

- **关联**：Story 8.6 · FR-42 · AC1（连带 6-6）
- **页面/入口**：`/notifications` · 铃铛图标
- **前置**：mock 模式；mock 中含一条 `MILESTONE_NODE` 类型通知（`milestoneShareText` 相关标题）
- **步骤**：
  1. 打开铃铛通知中心
  2. 查找 MILESTONE_NODE 类型通知条目
- **预期**：
  - 通知条目显示对应图标/标签
  - 点击该通知跳转至 `/profile/milestones`（深链路由）
- **层级**：L2

---

### TC-8.52 生日 L1（C-L1）当天发布成长日历——自动完成（PUSH_PUBLISH 路径）

- **关联**：Story 8.6 · FR-42 · AC3（`completeDateGatedLNodesOnPublish`）
- **页面/入口**：`ContentService.publish` → `ContentPublishedEvent` → L1 日期门判断
- **前置**：CAT 档案；宠物 `birthday` = 今日；C-L1 未完成；发布一条成长日历；Docker pg+redis 运行中
- **步骤**：
  1. 当天（宠物生日当天）发布一条成长日历
  2. 等待后端处理
  3. 查询 C-L1 完成状态
- **预期**：
  - C-L1 `completed = true`，`source = "PUBLISH"`
  - 同时触发 MILESTONE_NODE 通知（L 级）
- **层级**：L1

---

### TC-8.53 满 100 天当天发布成长日历——自动完成 C-L2

- **关联**：Story 8.6 · FR-42 · AC3
- **页面/入口**：`ContentService.publish` → L2 日期门（`created_at + 100d`）
- **前置**：CAT 档案；档案 `created_at` 距今恰好 100 天；C-L2 未完成；Docker pg+redis 运行中
- **步骤**：
  1. 第 100 天发布一条成长日历
  2. 查询 C-L2 完成状态
- **预期**：
  - C-L2 `completed = true`，`source = "PUBLISH"`
- **层级**：L1

---

### TC-8.54 生日当天**未发布**成长日历——C-L1 不自动完成

- **关联**：Story 8.6 · FR-42 · AC3
- **页面/入口**：`completeDateGatedLNodesOnPublish`
- **前置**：CAT 档案；今天是宠物生日；**不发布**任何内容；Docker pg+redis 运行中
- **步骤**：
  1. 等过 01:10 UTC（@Scheduled 扫描后）不发布任何成长日历
  2. 查询 C-L1 状态
- **预期**：
  - C-L1 仍未完成（PUSH_PUBLISH 类需当天发布成长日历才触发）
- **层级**：L1

---

### TC-8.55 L 级庆祝动效结束——系统分享面板自动弹出（WhatsApp/Instagram 可见）

- **关联**：Story 8.6 · FR-42 · AC2
- **页面/入口**：`_chest._openChest` → `onShare` → `shareServiceProvider`（2-6 通道）
- **前置**：mock 模式；L 级里程碑（如 C-L4）触发庆祝动效；设备已安装 WhatsApp/Instagram
- **步骤**：
  1. 触发 L 级完成庆祝（mock 返回 L 级完成）
  2. 点击宝箱图标开箱
  3. 等待约 4 秒后观察
- **预期**：
  - 系统原生分享面板弹出
  - 分享文案（en）：「My pet just unlocked the "{title}" milestone on TailTopia! 🎉」，标题为该里程碑英文名
  - 分享文案（id）：「Peliharaanku baru membuka tonggak "{title}" di TailTopia! 🎉」
  - 面板中可见 WhatsApp、Instagram 等分享目标
  - 标题来自 `localizedMilestoneTitle(code, locale)`，**不含中文**
- **层级**：L2

---

### TC-8.56 L 级分享文案本地化（en/id 对应里程碑标题）

- **关联**：Story 8.6 · FR-42 · AC2
- **页面/入口**：`_CandidateTile._confirm`（milestoneShareText 组装）
- **前置**：mock 模式；分别设置 en/id locale；触发 L 级完成
- **步骤**：
  1. en 模式：完成 C-L4，观察分享文案
  2. id 模式：同上
- **预期**：
  - en 分享文案：「My pet just unlocked the "All health milestones" milestone on TailTopia! 🎉」
  - id 分享文案：「Peliharaanku baru membuka tonggak "Semua tonggak kesehatan" di TailTopia! 🎉」
  - **任何情况下分享文案不含中文**
- **层级**：L2

---

### TC-8.57 真机系统推送接收 MILESTONE_NODE 推送并点击深链跳里程碑页

- **关联**：Story 8.6 · FR-42 · AC1（FR-34/FR-38）
- **页面/入口**：系统推送 → App 深链 `/profile/milestones`
- **前置**：真机（Android/iOS）；推送权限已授权；L 级里程碑达成触发后端推送；真后端（非 mock）
- **步骤**：
  1. L 级里程碑达成（如通过 API 触发或 TC-8.49 操作）
  2. 后台/锁屏状态收到推送通知
  3. 点击推送通知
- **预期**：
  - 推送通知出现，含里程碑名（按 locale 本地化）
  - 点击后 App 打开并导航至 `/profile/milestones`
  - 里程碑列表显示该 L 级已完成（彩色徽章）
- **层级**：L2（需真机 + 真后端）

---

## 横切验证组（跨 Story）

### TC-8.58 pet_type 创建后不可修改（F6）——里程碑清单不因编辑档案而改变

- **关联**：F6（pet_type 不可改）
- **页面/入口**：`/profile/edit` · `pet_profile_edit_page.dart`
- **前置**：CAT 档案已建，已有 30 条 roster；编辑页打开
- **步骤**：
  1. 进入宠物档案编辑页
  2. 查找 pet_type 字段（应置灰不可点）
- **预期**：
  - pet_type 字段置灰（disabled），无法修改
  - `pet_milestones` 表 roster 不变（仍 30 条 CAT 清单）
  - 后端 PATCH 档案 API 若携带 `petType` 字段，服务端忽略或拒绝修改
- **层级**：L1

---

### TC-8.59 OTHER 类型宠物无 HEALTH_COMBO 组合依赖

- **关联**：Story 8.3 · FR-42 · AC2；MilestoneCatalog `HEALTH_COMBO` 仅含 C-L4/D-L4
- **页面/入口**：`GET /api/v1/pet-profiles/me/milestones`（OTHER 档案）
- **前置**：OTHER 档案；G-M1（First vet visit）已完成；G-M2（First health check）已完成；Docker pg+redis 运行中
- **步骤**：
  1. 完成 G-M1 和 G-M2
  2. 查询里程碑列表，检查 G-L1/G-L2/G-L3
- **预期**：
  - G-L1/G-L2/G-L3 仍为未完成（OTHER 无组合依赖自动解锁逻辑）
  - 不存在「G-Lx HEALTH_COMBO」自动触发
- **层级**：L1

---

### TC-8.60 未知 code 里程碑标题兜底返回 code 本身（不显示中文）

- **关联**：i18n 债；`localizedMilestoneTitle` 兜底逻辑
- **页面/入口**：`milestone_titles.dart`
- **前置**：mock 模式；mock 中插入一条 `code = "X-UNKNOWN"` 的里程碑 item（假设后端新增了 App 未知的 code）
- **步骤**：
  1. 进入里程碑列表页
  2. 观察 `X-UNKNOWN` 徽章下方文案
- **预期**：
  - 显示「X-UNKNOWN」（code 本身）
  - **不显示**任何中文（如后端 titleZh 字段值）
- **层级**：L2

---

### TC-8.61 里程碑列表下拉刷新

- **关联**：Story 8.2 · FR-42 · AC1
- **页面/入口**：`/profile/milestones` · `RefreshIndicator`
- **前置**：mock 模式；已在里程碑列表页
- **步骤**：
  1. 在列表页向下拉动触发下拉刷新
- **预期**：
  - `milestoneListProvider` 重新请求（`ref.invalidate`）
  - 刷新指示器出现后消失
  - 列表内容更新（如后台完成了新里程碑，刷新后可见）
- **层级**：L2

---

### TC-8.62 mock 模式——所有里程碑字段与后端契约对齐（C5）

- **关联**：Story 8.2 · FR-42 · AC5（C5 四处同步：后端 record + App DTO + App mock + 契约 test）
- **页面/入口**：mock `/pet-profiles/me/milestones`
- **前置**：mock 模式（`PETGO_MOCK=true`）；flutter test 环境
- **步骤**：
  1. 查看 `mock_backend.dart` 中 `/pet-profiles/me/milestones` 的 mock 响应结构
  2. 对比 `MilestoneListResponse.java` 的字段集
  3. 对比 App `MilestoneList.fromJson` 的字段映射
- **预期**：
  - mock 响应、后端 DTO、App domain 三者字段名/类型/枚举值（UPPER_SNAKE）完全一致
  - 无 `id` 数字字段外露
  - `triggerType` 取值范围：`SYSTEM_AUTO` / `USER_CHECKIN` / `PUSH_PUBLISH`
  - `level` 取值：`S` / `M` / `L`
- **层级**：L0（flutter test 金标）/ L1（真后端对齐）

---

### TC-8.63 成长档案统计栏完成数来自真实里程碑数据（8.2 连带 2-4）

- **关联**：Story 8.2 · FR-42 · AC5；`TimelineService.getStats` 真计数
- **页面/入口**：`/profile` · `growth_archive_page.dart` 统计栏
- **前置**：L1 环境；CAT 档案；已完成 7 个里程碑
- **步骤**：
  1. 调用后端 `GET /api/v1/pet-profiles/me/archive-stats`（或对应统计接口）
  2. 检查 `milestoneCompleted` 和 `milestoneTotal` 字段
  3. 进入成长档案页，观察进度条
- **预期**：
  - `milestoneCompleted = 7`，`milestoneTotal = 30`（CAT）
  - 成长档案统计栏显示「7 / 30 milestones done」进度条
  - 与直接调用 `GET /me/milestones` 的 `completedCount/totalCount` 数据一致
- **层级**：L1

---

### TC-8.64 2-6 名片里程碑区块显示真实里程碑进度（C5 连带）

- **关联**：Story 8.2 · FR-42 · AC5；`CardPageController` 真供数
- **页面/入口**：H5 名片页 里程碑区块
- **前置**：L1 环境；CAT 档案；已完成若干里程碑
- **步骤**：
  1. 访问本人 H5 名片（`/card/<token>`）
  2. 找到里程碑区块
- **预期**：
  - 里程碑区块显示「已完成 X/N」真实数据（`milestoneCompleted`/`hasMilestones` 来自 `TimelineService.getStats`）
  - 不显示零态占位（如有里程碑数据则显示真实进度）
- **层级**：L1

---

## 本章遗留/盲区

### 盲区 1：印尼语翻译待母语者复核（已知债）

`petgo_app/lib/features/profile/domain/milestone_titles.dart` 中 `kMilestoneTitles` 的 `id` 翻译为工程初稿，注释标注「建议印尼母语者复核用词（尤其 `perawatan`/`steril` 等术语）」。目前有 TC-8.20 和 TC-8.48 等用例验证了 id 串切换，但翻译语义准确性无法通过自动化确认。所有 id 翻译结果在 L2 测试阶段需安排母语审校。

### 盲区 2：L2 动效视觉/计时观感（云端 headless 验不了）

TC-8.44/45/46 的三级动效视觉效果（`elasticOut` 弹性手感、S 1.5s 计时、M 3s 全屏、L 宝箱手感）、mint 配色（`AppColors.mintTint/mint/mint700/gold`）只能在真机或模拟器本地验收（L2）。云端 L0 仅验证了构建通过和计时逻辑单测。

### 盲区 3：真机系统推送路径（L2，需真后端 + 推送凭证）

TC-8.57 需要真机（Android/iOS）、FCM/APNs 凭证、以及已授权推送权限的账号。MILESTONE_NODE 推送内容（title/body 国际化）、点击深链 `/profile/milestones` 的端到端路径、以及锁屏/后台状态下的通知展示，均需本地真机验收。

### 盲区 4：过生日「已过生日补录」路径（Deferred）

Story 8.6 Completion Notes 中标注：C-L1 是 PUSH_PUBLISH 类，8.4 打卡 `checkIn` 当前仅放行 `USER_CHECKIN` 类——**已过生日的补录（让 L1 接受 USER_CHECKIN）为已知 deferred 小改**。目前测试用例仅覆盖「生日当天发布成长日历自动完成」路径（TC-8.52），「生日已过、手动补录 C-L1」路径暂无测试入口，待 deferred 修复后补充 TC。

### 盲区 5：@Scheduled 陪伴时长真扫描时机与时区边界

TC-8.30（C-M8 满 30 天定时扫描）依赖 `MilestoneScheduledCompleter` 01:10 UTC 触发，在测试时如需缩短等待可通过反射或测试 profile 手动调用。但时区边界（宠物档案 `created_at` 精度到毫秒，「满 30 天」以 UTC 日历日还是精确小时计）、以及跨零点触发的数据一致性，属于 L1 边界测试盲区，当前用例仅验证了主路径。
