---
title: "V1.3.4 app 主题 · 架构增量"
status: draft
created: 2026-10-12
inputs: PRD-v1.3.4-app.md · ui-v1.3.4-app.html · 决策日志-app.md · v1-3-4后台prd.md（只读 AB-22 / AB-30 / AB-31 中本主题要承接的数据面）
baseline: _bmad-output/planning-artifacts/architecture.md（基线不变的部分不重复）
code_baseline: feat/1.3.4-app-ux-revamp @ 28a7d30d（= main，含 V1.3.3）
---

# V1.3.4 app 主题 · 架构增量（AD-1 ~ AD-20）

> 本文件只写本版本**新增或改变**的技术决定。每条决定都对照了当前代码（文件:行号见各条「现状」）。
> **权威顺序**：`决策日志-app.md` > `PRD-v1.3.4-app.md` > 本文件 > UI 稿。本文件与 PRD 冲突时按 PRD，并回到操作者确认。

## 0. 范围与切分

| FR | 内容 | 后端 | App | 后台（admin 主题） |
|---|---|---|---|---|
| FR-122 | 教育类独立候选池 + 半衰期 720h | ✅ | — | — |
| FR-135 | 刷新抖动随机数打散 | ✅ | — | — |
| FR-125 | 原图上传 + `cropOffset` 展示裁切 | ✅ 字段 / DTO | ✅ 露出区域页 / 展示 | — |
| FR-139 | 发帖页点缩略图看大图 | — | ✅ | — |
| FR-124 | 版本更新提醒接线 | ✅ 配置表 + 读接口 | ✅ 横幅 / 弹窗 / 设置页 | AB-22 配置页 |
| FR-126 | 里程碑新增两项 | ✅ 清单 / 事件 / 回填 | ✅ 徽章 / 文案 / 当场庆祝 | — |
| FR-137 | 运营弹窗 | ✅ 表 + App 读接口 + 曝光记录 | ✅ 弹窗 / 跳转 | AB-30 配置页 |
| FR-138 | 运营系统通知 | ✅ 表 + 发送核心 + 通知类型 | ✅ 通知中心渲染 / 跳转 | AB-31 发送页 |
| — | 场所入口图标换「折叠地图 + 定位针」（UI 决策 2026-10-09） | — | ✅ | — |

**AD-1 · 后端按「谁用」切（决策日志 D-1 / D-2）。** 本主题交付：迁移（建表 / 加列 / 约束 / 预置数据）、实体与仓储、**App 调用的接口**、发送运营通知的**核心服务**（人群解析、分批推送、状态机、定时扫描）。admin 主题只做后台页面与其**写接口**（保存版本配置、弹窗增删改停用、新建 / 取消发送），写接口调用本主题提供的服务。本主题的 story 以迁移预置数据 + 集成测试自证，不等后台页面。

---

## 1. FR-122 教育类独立候选池

**现状：** 候选池 `ContentPostRepository.findRankCandidatePool`（:536-552）= 公开 + 已发布 + 未删 + 未被本人举报 + 作者未被本人屏蔽，`ORDER BY created_at DESC, id DESC`，上限 `petgo.feed.rank.candidate-pool-size:1000`。打分 `FeedRankScorer.freshness()` 把 24h 半衰写死在 :33。配置单行表 `feed_rank_config`（最新一次改表 `V20260901_1120`），`PlatformConfigService.feedRank()` 每次实时读、无缓存。

**AD-2 · 独立池 = 新查询 + 与主池按 id 合并，排序引擎不改。**
- 新增 `findEduCandidatePool(hasViewer, viewerId, since, pageable)`：WHERE 子句**逐字复用**主池的全部过滤（删 / 状态 / 可见性 / 举报 / 屏蔽），另加 `type = KNOWLEDGE AND created_at >= :since`（`since = now - 90 天`），上限 1000。
- `FeedRecommendationService.generate()` 取主池后再取教育池，**按 id 去重合并**成一个候选列表交给 `FeedRankEngine.rank()`。槽位挑选、5 级放宽、降级链、防扎堆**一行不改**——教育槽位自然从更大的 KNOWLEDGE 集合里挑（PRD AC1 / AC4）。
- 🔴 过滤**不得**另写一份：抽成共享的 JPQL 片段或在仓储层同一方法里参数化，测试钉住「被屏蔽作者的科普不出现在任何槽位」（PRD AC2）。
- 冷启动 P95 估算（`interaction_p95 <= 0` 时由池子现算）改用**合并后**的池——仅在 P95 未配置时生效，影响可忽略。
- **索引：** 新增部分索引 `idx_content_posts_knowledge_feed ON content_posts (created_at DESC, id DESC) WHERE type = 'KNOWLEDGE' AND deleted_at IS NULL`。教育类存量约 280~450 条，索引代价极小，避免查询退化为扫 `idx_content_posts_feed` 全部近 90 天行。

**AD-3 · 半衰期按内容类型取值，新增配置列。**
- 迁移：`feed_rank_config` 加 `edu_freshness_half_life_hours DOUBLE PRECISION NOT NULL DEFAULT 720`，CHECK `> 0`。
- `FeedRankScorer.freshness(createdAt, now, halfLifeHours)` 改为传参；KNOWLEDGE 用配置值，其余类型仍传 24（**FUN / LIFE 的 24 小时不变成配置项**，PRD 只要求教育类可调）。
- 只作用于 ALL Tab 推荐序（其余 Tab 走 `chronoFeed`，不打分）。
- **不在后台露出**（PRD OQ-1）。⚠️ 后台「算法参数」页保存走 `AdminConfigService.updateFeedRank`：须确认它是**读出实体再改字段**、不会把新列写回默认值；由集成测试钉住「后台保存其他参数后，半衰期列不变」。
- 改值即时生效：配置每个序列生成时实时读（无缓存），序列快照 30 分钟 TTL 过期后用新值（PRD AC3）。

## 2. FR-135 刷新抖动打散

**现状：** `FeedRankEngine.jitterFactor()`（:256-264）= `new Random(seed.hashCode() * 1_000_003L + id).nextDouble()`，唯一调用点 `rank()` :114。

**AD-4 · 用 SplitMix64 终混函数打散，保持确定性。**
- `long h = mix64(seed.hashCode() * 0x9E3779B97F4A7C15L ^ mix64(id))`，`rand = (mix64(h) >>> 11) * 0x1.0p-53`，`factor = 1 - strength * rand`。`mix64` 即 SplitMix64 的终混（三次 xor-shift-multiply）。
- 不用 `Random` / `Math.random` / `ThreadLocalRandom`；同一 `(seed, id)` 永远同一系数（翻页续算、Redis 失效时的重算依赖它）。
- `strength <= 0` 或 `seed == null` → 1.0（不变）。区间仍为 `(1 - strength, 1]`。
- 验收按 PRD AC1~AC4 写成单测：相邻 id 系数相关性 ≈ 0；1000 条 × 50 次刷新的「首条种数」与理想均匀随机同量级；同种子两次生成逐条一致；强度 0 时排序 = 纯分数排序。

## 3. FR-125 原图上传 + 展示裁切

**现状：**
- 图片存 `content_posts` 两列平行 JSONB：`image_urls`（≤9）、`image_sizes`（`[{w,h}|null]`，`V20260818_1344`）。发布请求 `ContentPostCreateRequest` 带 `imageUrls` / `imageSizes`；`ImageSizeResolver.normalize` 在长度不齐时整列丢弃。DTO：`FeedItemResponse` / `ContentDetailResponse` 返回 `imageUrls` + `imageSizes`。
- 上传：预签名 PUT 直传阿里云 OSS（`POST /api/v1/media/upload-url`），一图一个原件；**缩略图靠 OSS 实时处理**（`AppImage.ossResized` 追加 `x-oss-process=image/resize,w_N`，仅当域名以 `aliyuncs.com` 结尾）。2026-10-12 核实 stag / 生产 Feed 图片域名均为 `tailtopia.oss-ap-southeast-5.aliyuncs.com`，实时缩图生效。
- App：选图 `ImageProcessor`（剥 EXIF、≤10MB）→ `applyBatchCrop`（`image_crop.dart:188`，首张定档锁批次）→ `cropToAspect` 物理裁切重编码 → 上传。`CropPreset` 三档，埋点 `publish_image_crop_completed{target_ratio, is_batch_lock_source}`。

**AD-5 · `cropOffset` 存第三列平行数组。**
- 迁移：`content_posts` 加 `image_crop_offsets JSONB NULL`，元素为 `[0,1]` 的小数或 `null`，与 `image_urls` 同序同长——与 `image_sizes` 同一模式，不新建图片表（PRD「同一条路径上的增量」）。
- 请求：`ContentPostCreateRequest.imageCropOffsets`（≤9，可省略）。**校验严格**：给了就必须与 `imageUrls` 等长、非空元素在 `[0,1]`，否则 422（新客户端是唯一写入方，长度不齐说明有 bug，不能像 `imageSizes` 那样静默丢弃——丢了就退化为居中，用户选的位置无声消失）。
- 响应：`FeedItemResponse` / `ContentDetailResponse` 加 `imageCropOffsets`（缺省省略）。存量与区间内图片为 `null` → App 走现有居中（PRD AC4，无需回填）。
- 新发布的超界图一律带值（跳过 = 0.5，PRD AC8）；区间内的图恒为 `null`。

**AD-6 · 展示：比例逻辑不动，只把对齐方式从「居中」换成按 offset。**
- `resolveFeedImageAspect` / `resolveDetailImageAspect` 的比例计算**不改**（窗口比例仍 = `clampFeedRatio(原图比例)`，再受现有最大高度约束，PRD AC2）。
- 新增纯函数 `feedImageAlignment(size, cropOffset) → Alignment`：过宽图 `Alignment(2·offset − 1, 0)`，过竖图 `Alignment(0, 2·offset − 1)`；`offset == null` 或图在区间内 → `Alignment.center`。`BoxFit.cover` 的对齐语义恰好使 0 / 0.5 / 1 对应贴起始边 / 居中 / 贴结束边（PRD AC3）。
- Feed 卡（`masonry_card.dart` → `FeedImage`）、置顶卡、详情轮播同用该函数；灯箱**不改**，本来就 `contain` 展示整图（PRD AC5）。
- AC4（存量逐像素一致）以 golden 测试覆盖：同一张图 `offset == null` 时渲染结果与改动前的 golden 相同。

**AD-7 · 三处取档（闭合 PRD OQ-4）：沿用现状，不新增档位。**

| 位置 | 加载 | 依据 |
|---|---|---|
| Feed 卡 | OSS 实时缩图 `w_800` | `FeedImage.thumbWidth` 默认 800 |
| 详情轮播 | `w_1080` | `content_detail_page.dart` `thumbWidth: 1080` |
| 灯箱 | 先 `w_1080` 垫底，再原图淡入 | `image_lightbox.dart` |

原图只在灯箱里加载，Feed 列表绝不拉原图。⚠️ 该保证依赖「图片域名以 `aliyuncs.com` 结尾」；若将来换自定义 CDN 域名，`ossResized` 会静默不缩图——本版本加一条单测钉住 `ossResized` 对当前域名生效，域名白名单的改造不在本版本。

**AD-8 · 发布链路：取消物理裁切，裁剪页改为「露出区域页」。**
- 删除 `CropPreset`、`cropToAspect`、`computeCropRect`、`applyBatchCrop` 的锁档逻辑；`needsCrop()` 与 `kFeedRatioMin/Max` **保留不改**（PRD「不改容差」）。
- 上传的字节 = `ImageProcessor` 产物，之后不再解码 / 重编码（PRD AC1 哈希一致）。
- `ImageUploadItem` 增加 `cropOffset`（`double?`），随项走——拖拽重排、删除都带着它，发布时按最终顺序组装 `imageCropOffsets`。
- 露出区域页：一次接收本批**全部超界图**，同页左右切换（PageView + 缩略图条 + 页码），返回 `List<double>`（每张超界图一个 offset）或 `null`（✕ / 返回 = 取消本次全部选图，沿用现状）。默认 0.5。
- 发帖页缩略图仍方形取正中，不跟 offset（PRD 2026-10-09）。
- 埋点口径（PRD §4）：`publish_image_crop_completed` 每张超界图一条，属性改为 `target_ratio`（`0x75` / `1x78`）+ `adjusted`（布尔，是否动过默认居中）；删除 `is_batch_lock_source`。`publish_image_crop_shown` / `publish_image_crop_exit_tapped` 保留。发版说明须提示数据侧按发版日切分。

## 4. FR-139 发帖页看大图

**AD-9 · 只加点击入口，复用 `ImageLightbox.openMemory()`。**
- 现状：格子手势只有长按拖拽（`LongPressDraggable`）与右上 ✕；灯箱 `openMemory(images, initialIndex, heroTagPrefix, source)` 已存在，键盘弹起时自身就会跳过打开（bug 507 规则）。
- 缩略图格子加 `onTap` → `openMemory(images: 当前全部 item.bytes 按格子顺序, initialIndex: 点击下标, source: 'publish_compose')`。✕ 用独立的 `GestureDetector` 吃掉点击，保证点 ✕ 不冒泡到格子（PRD AC4）。上传中 / 失败的格子同样可点（看本地字节，PRD AC5）。
- 内存：`openMemory` 用 `PageView` 懒加载，不一次解码全部（PRD 影响面），本版本不改。

## 5. FR-124 版本更新提醒

**现状：** `GET /api/v1/app-version`（`AppVersionController`，无 service 层）直接读 `petgo.app-version.*` 环境变量，返回 `latestVersion / minSupportedVersion / iosStoreUrl / androidStoreUrl`。App 侧 `app_version_check.dart`（`kAppVersion` 取 dart-define，恒为 1.0.0）、`app_version_repository.dart`（失败返回 null）、`AppUpdateDialog`（Material AlertDialog）**全部零调用**。`package_info_plus` 已在设置页使用。

**AD-10 · 后端：版本配置搬进单行表，接口换字段。**
- 迁移建 `app_version_config`（单行，`id = 1` CHECK）：`ios_latest_version`、`android_latest_version`、`min_supported_version`、`ios_store_url`、`android_store_url`（均 `VARCHAR NOT NULL`）、`updated_at`、`updated_by`（可空，后台写入时填）。
- 预置值（决策日志 D-10）：两个标准商店链接；`min_supported_version = '1.0.0'`；**两端最新版本号写发版时商店的实际版本**——迁移文件里留明确注释，列入发版检查单，迁移由开发在合入前按当时商店版本填写（本主题开发时以 App Store 1.3.2 / Google Play 待查为准，不得写 1.0.0）。
- 接口返回改为 `iosLatestVersion, androidLatestVersion, minSupportedVersion, iosStoreUrl, androidStoreUrl`（游客可读不变）。旧字段 `latestVersion` 无任何线上消费者（App 从未接线），直接移除；`petgo.app-version.*` 配置属性与 env 一并删除。
- 校验（版本格式、最低版本 ≤ 两端最新、上调最低版本时两链接必填、`https://` 开头）写在本主题的 `AppVersionConfigService.update(...)` 里，供 admin 主题的保存接口调用（AD-1）。

**AD-11 · App：单一的更新检查控制器。**
- 版本号来源改为 `PackageInfo.fromPlatform().version`（删 `kAppVersion` 的 dart-define）。
- 新增 `appUpdateControllerProvider`（Riverpod Notifier，进程内单例）持有：判定结果、接口数据、上次检查时刻、本次运行是否已报横幅曝光。判定仍用现有 `AppVersionCheck.decide`，`latest` 按平台取 `iosLatestVersion` / `androidLatestVersion`。
- 触发：冷启动（启动协调器里，见 AD-13）、回前台且距上次 ≥ 6 小时（`WidgetsBindingObserver`，照 `att_gate.dart` `_ResumeObserver`）、设置页手动。
- 冷却：`AppPrefs` 新增 `petgo.update_skipped_version` / `petgo.update_skipped_at`；推荐态在「同一 latest 且 < 72h」时不出横幅；手动检查无视冷却。强制态不受冷却影响。
- 表现：推荐态 → 首页横幅（`_BerandaTop` 最上方、`PlaceEntryRow` 之前，随页面滚动）；强制态 → 重做后的 `AppUpdateDialog`（不可关）；设置页手动 → 同一弹窗的推荐态（带「Nanti」）或 toast。商店链接为空 → 横幅不出、弹窗无「前往更新」按钮（PRD AC6）。
- 埋点：`app_update_prompt_shown{decision, surface}`（横幅每次运行只报一次）、`app_update_go_store_tapped{decision, surface}`、`app_update_later_tapped{decision, surface}`。

## 6. 冷启动弹窗协调（FR-124 强制态 · FR-126 · FR-137）

**现状：** 没有统一的启动弹窗协调器。`main.dart` 首帧后的回调手写顺序：ATT → 推送权限 → 权限快照 → AppsFlyer / TikTok。新功能蒙层（`CoachmarkOverlay`）在 Diary 归档页、Know Your Pet 页各自触发；里程碑庆祝从不在冷启动出现。

**AD-12 · 新增进程内的「本次冷启动功能弹窗记录」。**
- `startupPopupLedger`（进程内单例，不持久化）：任一功能弹窗**真正出现**时登记——ATT（请求前状态为 `notDetermined` 且确实弹出，决策日志 D-6）、系统推送权限申请、强制更新弹窗、新功能蒙层（`CoachmarkOverlay` 插入时）、里程碑庆祝（`showMilestoneCelebration` 入口）。推荐更新横幅**不登记**（PRD）。
- 只登记、不排队：本版本不改造既有弹窗的触发方式，避免回归。

**AD-13 · 运营弹窗与强制更新挂在启动链末尾。**
- `main.dart` 首帧回调在现有链（ATT → 推送权限 → …）之后追加：①版本检查（强制态立即弹，登记 ledger）；②运营弹窗判定。
- 运营弹窗判定条件：本次冷启动尚未弹过运营弹窗 **且** ledger 为空 **且** 当前路由是落地页（Diary 或 Social 任一 Tab 根，决策日志 D-8）**且** 图片已加载成功。图片加载失败 → 本次不弹、不计频次（PRD AC7）。
- 冷启动来自推送点击 / 深链、落到 Diary / Social 以外的页面时，**当时不弹**；本次运行内用户之后**第一次进入 Diary 或 Social** 时再按同样条件判定（决策日志 D-11）。实现上判定挂在这两个 Tab 根页的「首次可见」上，而不是只在启动链末尾跑一次。

## 7. FR-137 运营弹窗

**AD-14 · 数据面（本主题建表，admin 主题写入）。**
- `ops_popups`：`id`、`public_token`（对外标识，不可枚举）、`name`（仅后台）、`status`（`ACTIVE` / `DISABLED`；「未开始 / 进行中 / 已结束」由时间窗现算，沿用 `ScheduleWindow` 的 WIB 口径）、`starts_at` / `ends_at`、`priority INT`、`audience`（`ALL` / `NEW` / `OLD`）、`new_user_days INT DEFAULT 7`、`frequency`（`ONCE` / `DAILY`）、`image_url_id` / `image_url_en`（en 可空 = 共用）、`title_id/en`、`body_id/en`（可空）、`button_text_id/en`、`target_type`（`NONE` / `PRODUCT` / `CATEGORY` / `APP_PAGE` / `EXTERNAL`）、`target_value VARCHAR(512)`、`created_at` / `updated_at`。
- `ops_popup_impressions`：`(popup_id, user_id, shown_at)`，登录用户的频次记录；游客频次只在设备本地。
- App 读接口 `GET /api/v1/ops-popups/candidates`（游客可读，带登录态则识别用户）：服务端过滤「ACTIVE + 在时间窗 + 人群匹配（游客只返回 `ALL`；登录用户按 `users.created_at` 与 `new_user_days` 判新 / 老）+ 跳转目标有效（商品须 `is_active`）+ 登录用户频次未满（ONCE 曾展示过 / DAILY 当天 WIB 已展示）」，按 `priority DESC, created_at DESC` 返回**有序候选列表**（客户端还要做本地过滤，所以不只返回一个）。
- 曝光上报 `POST /api/v1/ops-popups/{token}/impressions`（登录用户）——弹窗真正显示后才报。
- 品类是固定枚举（`ProductCategory`），不存在「下线」状态；后台保存时校验枚举值即可，App 不需要再判品类失效。
- 编辑不重置频次记录（后台 PRD 2026-10-08）——频次挂在 `popup_id` 上，编辑不换 id。

**AD-15 · App 侧频次与游客继承。**
- 本地记录（`AppPrefs`）：`{popupToken: lastShownWibDate | "once"}`，游客与登录用户都写。
- 选弹窗：取候选列表，**剔除本地记录已满频次的**（实现「游客期间弹过的每人一次，登录后不再弹」，PRD AC4），取第一个，预加载图片成功才弹。
- WIB 自然日：`DateTime.now().toUtc().add(7h)` 取日期，不用手机本地时区（PRD 补充细则）。
- 语言：按 App 当前语言取 id / en 字段；图片 en 为空用 id 图（PRD AC6）。

**AD-16 · 统一跳转目标（运营弹窗与运营通知共用）。**
- 新增 `OpsTarget { type, value }` 与 `openOpsTarget(context, ref, target)`：
  - `PRODUCT` → `/shop/products/{token}`；`CATEGORY` → `/shop?category={enum}`（`go`，Tab 根）
  - `APP_PAGE` 固定 5 个：`HOME` `/home` · `PUBLISH` 发帖弹层（`PublishComposePage.open`）· `TOKO` `/shop` · `KNOW_YOUR_PET` `/profile/pet-insights` · `HEALTH` `/profile/health`
  - `EXTERNAL` → `launchUrl(mode: LaunchMode.inAppBrowserView)`，失败退回 `externalApplication`；**系统默认配色**（决策日志 D-9）
  - `NONE` → 只关闭
- 需登录的页面（发帖、Know Your Pet、健康）对游客走 `requireLogin(..., pendingAction: RouteIntent(...))`，登录后继续跳转（PRD「游客点击后先进登录」）。现有路由守卫对游客是重定向回 `/home`，不能直接 `push`。
- 不扩展 `tailtopia://` 外部深链（`deepLinkToLocation` 与 manifest 不动），目标只在 App 内解析。

## 8. FR-138 运营系统通知

**现状：** `notifications` 表（`V19`）`title VARCHAR(120)` / `body VARCHAR(255)` / `deep_link_type` / `deep_link_token` / `target_ref VARCHAR(64)`；类型 CHECK 最新全量重建为 `V20260915_1614`（31 值）。`NotificationService.send/sendWithCopy` 单人写入 + Redis 未读角标 + `@Async` 推送；推送文案按 `users.locale`（`en` 否则 `id`）从 messageSource 取。腾讯 IM `pushOffline` 一次一人。通知中心 `_typeLabel` / `_typeBody` **只用本地文案**，从不读 `item.title/body`。

**AD-17 · 新类型 + 活动表 + 发送核心。**
- 类型 `OPS_ANNOUNCEMENT`（≤32 字符）。迁移按 `V20260915_1614` 写法 **DROP + ADD 全量 34 值** = 现有 31 + `OPS_ANNOUNCEMENT` + follow 主题的 `USER_FOLLOWED` + `PLACE_COMMENTED`（决策日志 D-26：两分支的重建都列全集，先后合并、先后执行都得到完整全集）；开工当天对全部远端分支并集核查（四次漏值事故，见 CLAUDE.md）。`NotificationType` 枚举两分支都在末尾按同一顺序追加三个值（逐字相同，合并不冲突，D-26）。
- `ops_notification_campaigns`：`id`、`public_token`、`title_id/en`、`body_id/en`、`target_type` / `target_value`（同 AD-14）、`audience_spec JSONB`（全部 / 条件 / 指定 ID 列表）、`scheduled_at`（可空 = 立即）、`status`（`PENDING` / `SENDING` / `DONE` / `PARTIAL_FAILED` / `CANCELED`）、`recipient_count`、`failed_count`、`created_by`、时间戳。指定 ID 列表大时存子表 `ops_notification_campaign_targets(campaign_id, user_id)`。
- 每个收件人一行 `notifications`：`type = OPS_ANNOUNCEMENT`，`title/body` **写入时按收件人 `users.locale` 选好语言**（推送与通知中心同一语言，PRD AC2），`deep_link_type = 'OPS_ANNOUNCEMENT'`，`target_ref = campaign public_token`。`NotificationItem` 对该类型附带 `opsTarget {type, value}`（查 campaign 得到），因为外链最长 512，塞不进 `target_ref`。
- 发送核心 `OpsNotificationDispatcher`：
  - 人群解析：基础过滤复用 `role = USER AND account_type = REAL AND deleted_at IS NULL AND enabled AND status = ACTIVE`（同 `findLifecyclePushCandidates`），排除封号 / 注销 / 虚拟号（PRD AC5）；条件筛选与 CSV 指定由 admin 主题组装成 `audience_spec`，解析在本服务（定时发送按实际发送时刻计算人群）。
  - 写通知中心：按批（如 500 人一批）批量插入，未读角标批量自增；「触达人数」= 成功写入数。
  - 推送：按语言分组后用腾讯 IM 批量接口（`/v4/timpush/batch` 一次最多 500）分批下发，批间限速；失败批次重试 3 次，仍失败计入 `failed_count`、状态置 `PARTIAL_FAILED`（PRD AC6，后台 PRD）。推送文案直接用该条的 title / body，不走 messageSource。
  - 定时：`@Scheduled` 扫描到期的 `PENDING` 活动；状态机变更用条件更新防重复执行（多实例安全）。
  - 异步只用 `@Async` + DB 状态机（架构护栏，不引入 MQ）。
- App：通知中心对 `OPS_ANNOUNCEMENT` 例外读 `item.title / item.body`；图标 `campaign_rounded`，紫色（mint / cream2）配色（UI 稿 E2）；点击 → `openOpsTarget`。推送点击仍先落通知中心并标已读（2026-08-07 规则不变）。埋点 `ops_notification_opened{campaign_id, source}`。

## 9. FR-126 里程碑新增两项

**现状：** `MilestoneCatalog` 三份清单（猫 / 狗 31、通用 16，「新手毕业」在 S 级末位且 sortOrder 最大）；`MilestoneAutoEvent` 17 种；`MilestoneAutoCompleteListener`（`@TransactionalEventListener` + `@Async`）消费业务事件，完成后发 `MilestoneCompletedEvent`（→ 通知 + 埋点）。每只宠物建档时生成 `pet_milestones` 名册；完成写 `milestone_completions`（含 `celebrated_at`）。打卡发 `PlaceCheckedInEvent`（无宠物 id，无里程碑接线）；完整解读解锁发 `KeepsakeUnlockedEvent(sku = TAILSONALITY)`。App 庆祝页 `showMilestoneCelebration`，「当场」庆祝在健康页用 500 / 800 / 1200ms 短轮询等后端异步完成。

**AD-18 · 后端。**
- 三份清单在**末尾**追加两条 S 级 `SYSTEM_AUTO`：猫 `C-S17` / `C-S18`、狗 `D-S17` / `D-S18`、通用 `G-S10` / `G-S11`。追加在「新手毕业」之后，**既有 code 与 sortOrder 一个都不变**。
- 新事件 `PLACE_CHECKED_IN`、`TAILSONALITY_UNLOCKED`，加进 `MilestoneAutoCompleteMap`。
- `PlaceCheckedInEvent` 增加 `petIds`，监听器对每只宠物各完成一次（PRD「逐宠达成」；本版本一人一宠，结构上按列表处理）。
- 监听 `KeepsakeUnlockedEvent`：**只认 `sku == TAILSONALITY`**（完整解读，含补差价升级）；`TS_MATCH` 不算（PRD 2026-10-12）。宠物取自 `tailsonality_results.pet_profile_id`。已完成则幂等跳过（重测再解锁不重复达成）。
- 回填迁移（一次性，沿用 `V99` / `V20260910_1108` 写法）：①给**所有存量宠物**按物种插入两条新 `pet_milestones` 名册行（名册在建档时生成，不补就没有行可完成）；②有打卡记录的宠物插入 `C/D/G` 打卡项完成行，`completed_at` = 首次打卡时间；③有完整解读解锁的宠物插入性格测试项完成行，`completed_at` = 最早 `unlocked_at`；④两类回填的 `celebrated_at = completed_at`（不补弹）；⑤**不发通知、不报完成事件**（直接写表，不经 `MilestoneCompletionService`）。
- 跨库一致性：`MilestoneCatalogI18nTest`（App 印尼文标题 = 后端 `titleId`）、`MilestoneBadgeMappingTest`（徽章 key 由 40 → 42）、`MilestoneBadgeAssetSyncTest`（App `assets/milestone` 与后端 `static/milestone` 同名同哈希）、`MilestoneCatalogTest`（数量 33 / 33 / 18）全部要同步更新。

**AD-19 · App。**
- `kMilestoneTitles`、`kMilestoneCelebrationCopy`、`kMilestoneBadgeKeys` 各加 6 个 code；新增两枚徽章 key（`place_checkin`、`tailsonality`），插画由交付的 PNG 转 webp，放 App `assets/milestone/` 与后端 `static/milestone/` 两处。
- 当场庆祝：打卡成功页 `initState` 后、完整解读解锁成功（结果页 `_startUnlock` 与配型页「买完整解读」两处）后，按健康页的短轮询范式查该宠物该 code 是否已完成且未庆祝 → `showMilestoneCelebration(path: instant)` 盖在当前页上，关闭回原页；上报 `celebrations`。轮询都没等到 → 不弹，交既有补庆祝兜底（PRD）。

---

## 10. 场所入口图标

**AD-20 · 用 UI 稿的 SVG。** `PlaceEntryRow` 的 `Icons.place_outlined` 换成 UI 稿 C1 中的「折叠地图 + 定位针」SVG（项目已有 `flutter_svg`），颜色仍取 `AppColors.mint`。唯一使用处是首页 `_BerandaTop`；`pet_recommendation_strip_test` 里对该行的断言按 key 不按图标，不受影响（开发时复核）。

---

## 11. 迁移清单（全部时间戳命名，`V2026101x_HHMM__*.sql`，取创建时刻）

| 迁移 | 内容 | AD |
|---|---|---|
| `…__feed_rank_edu_half_life.sql` | `feed_rank_config` 加 `edu_freshness_half_life_hours` | AD-3 |
| `…__content_posts_knowledge_index.sql` | 教育池部分索引 | AD-2 |
| `…__content_posts_add_image_crop_offsets.sql` | `image_crop_offsets JSONB` | AD-5 |
| `…__create_app_version_config.sql` | 版本配置单行表 + 预置值 | AD-10 |
| `…__create_ops_popups.sql` | `ops_popups` + `ops_popup_impressions` | AD-14 |
| `…__create_ops_notification_campaigns.sql` | 活动表 + 指定用户子表 | AD-17 |
| `…__rebuild_ck_notifications_type_with_ops.sql` | CHECK 全量 34 值（D-26） | AD-17 |
| `…__milestones_checkin_tailsonality.sql` | 名册补行 + 存量回填 | AD-18 |

## 12. 会被打破、需要同步改的既有测试（开发时以实际为准）

- `image_crop.dart` / `publish_crop_page.dart` 相关测试（档位、锁档）——随删除重写
- 里程碑跨库测试四件（AD-18）
- `AppVersionControllerEndpointTest`、`test/notify/app_version_check_test.dart`、`app_update_dialog_test.dart`——字段与样式变更
- 通知中心类型渲染测试——新增 `OPS_ANNOUNCEMENT` 分支
- 埋点事件清单测试（事件名字面量扫描）——新增 / 改属性的事件

## 13. 本版本发现、但不在本版本处理的事项（记录备查）

1. Feed 缩略图宽固定 800，不按屏幕像素密度缩放
2. `feed_rank_config` 每页被读 2~3 次（注释写「每序列一次」）
3. 发帖图片逐张串行上传
4. 上传失败的 ⚠ 图标与 ✕ 叠在同一角——本版不处理（决策日志 D-14）
5. `content_detail_page.dart:610` 注释仍写容差上限 1.34（实为 1.78）
