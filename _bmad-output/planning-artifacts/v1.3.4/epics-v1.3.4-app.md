---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - _bmad-output/planning-artifacts/v1.3.4/PRD-v1.3.4-app.md
  - _bmad-output/planning-artifacts/v1.3.4/architecture-v1.3.4-app-delta.md
  - _bmad-output/planning-artifacts/v1.3.4/决策日志-app.md
  - _bmad-output/planning-artifacts/v1.3.4/ui-v1.3.4-app.html
  - _bmad-output/planning-artifacts/v1.3.4/UI决策记录-app.md
  - _bmad-output/planning-artifacts/architecture.md                      # 跨版本基线（不重定向）
  - _bmad-output/implementation-artifacts/CROSS-STORY-DECISIONS.md       # 跨 story 契约（不重定向）
  - 外部只读：MY-PROJECT/Pet Project/TailTopia/V1.3.4/v1-3-4后台prd.md（仅取 AB-22 / AB-30 / AB-31 中本主题承接的数据面，见 AD-1）
version: v1.3.4
theme: app
status: draft
updated: '2026-10-12'
---

# petgo-platform V1.3.4 app - Epic Breakdown

## Overview

本文件把 V1.3.4 App PRD（FR-122 / 124 / 125 / 126 / 135 / 137 / 138 / 139）、UI 稿与架构增量拆成可实现的 story。

**范围**：App + 后端（决策日志 D-1）。后台配置页 AB-22 / AB-30 / AB-31 另开 admin 主题；后端按「谁用」切（D-2 / AD-1）——表、App 调用的接口、发送核心归本主题，后台页面与其写接口归 admin 主题。

> 🔴 **口径优先级**：`决策日志-app.md`（D-1 ~ D-14）> `PRD-v1.3.4-app.md` > `architecture-v1.3.4-app-delta.md`（AD-1 ~ AD-20）> UI 稿（`ui-v1.3.4-app.html`，UI 阶段决策见 `UI决策记录-app.md`）。跨 story 契约冲突以 `CROSS-STORY-DECISIONS.md` 为准。

## Requirements Inventory

### Functional Requirements

沿用 PRD 的 FR 编号，子项用小数位细分。

**FR-122 教育类槽位候选池独立（纯后端）**

- **FR-122.1** 教育类槽位使用独立候选池：近 90 天全部 `type='KNOWLEDGE'` 公开内容，按发布时间倒序上限 1000 条；不受主候选池「最近 1000 条」截断
- **FR-122.2** 独立池上可见性（仅 PUBLIC）、软删除、拉黑作者、举报者隐藏四项过滤全部生效，任何池子、任何降级态都不可绕过
- **FR-122.3** 所有 KNOWLEDGE 内容在推荐打分时新鲜度半衰期为 720 小时（按内容类型生效，不论落在哪个槽位）；FUN / LIFE 仍 24 小时；仅作用于 ALL Tab
- **FR-122.4** 教育类半衰期为配置项（`feed_rank_config` 新增字段，默认 720），改后无需发版即生效，**不在后台露出**
- **FR-122.5** 教育类池不足以填满槽位时，降级行为与现状一致（降级链级别 1 补位，不空槽、不报错）
- **FR-122.6** 不改属性配比、物种配比、刷新抖动幅度、属性归属规则

**FR-135 刷新抖动随机数打散（纯后端）**

- **FR-135.1** 生成抖动系数前把「刷新种子 + 内容 id」充分打散，id 相邻内容的系数互不相关
- **FR-135.2** 保持确定性：同一（种子, id）永远同一系数；翻页续算与重算依赖它
- **FR-135.3** 抖动幅度取值与区间 `(1 - 幅度, 1]` 不变；幅度为 0 或种子为空 → 不抖动
- **FR-135.4** 顶置内容、作者本人待审帖固定在上方属既有设计，不改

**FR-125 发布图片改为原图上传 + 展示裁切**

- **FR-125.1** 发布端不再物理裁剪；上传文件 = `ImageProcessor`（剥 EXIF 含 GPS、≤10MB）处理后的产物，之后不再裁切或重编码；选图处理保留不变
- **FR-125.2** 超界图（`ratio < 0.75` 或 `> 1.78`）选完后自动进入「露出区域页」；容差区间内不进
- **FR-125.3** 取消三档比例；展示窗口比例由越界方向唯一决定：过竖 0.75、过宽 1.78
- **FR-125.4** 每张超界图存一个 `cropOffset ∈ [0,1]`（0 贴起始边 / 0.5 居中 / 1 贴结束边）；默认居中，不调整直接点「Lanjut」存 0.5；新发布超界图一律写值
- **FR-125.5** 多张超界图在同一页左右切换逐张调整，每张独立、无跨图锁定；只调几张也可以，其余居中
- **FR-125.6** 点 ✕ / 系统返回 = 取消本次选的全部图，回发帖页一张不加
- **FR-125.7** 发帖页缩略图仍为方形取正中，不跟随露出区域
- **FR-125.8** Feed 卡片与详情页轮播：有 `cropOffset` 按其开窗；无该值（存量、区间内）沿用居中 cover，逐像素不变；详情与 Feed 同比例同位置
- **FR-125.9** 灯箱展示完整原图，组件不改；多图滑动位置与轮播同步（回归）
- **FR-125.10** 存量内容不回溯、不补偿；不改容差取值与 `needsCrop()`；不改灯箱；Feed 卡不新增全屏入口
- **FR-125.11** 埋点口径变更：裁剪完成事件改记展示窗口比例（`0x75` / `1x78`）+ 是否调整过露出区域（布尔）；与旧口径按发版日切分

**FR-139 发帖页点击图片查看大图（仅 App）**

- **FR-139.1** 点任一缩略图打开灯箱定位到该张，左右滑看本次全部已选图，顺序 = 格子顺序（含拖拽重排后）
- **FR-139.2** 灯箱显示完整原图，不套 Feed 窗口裁切
- **FR-139.3** 只看不改：无删除 / 重排 / 裁剪 / 设封面；关闭回发帖页，已选图与填写内容全部保留
- **FR-139.4** 点击 = 看大图、长按 = 拖拽重排、点 ✕ = 删除，互不影响；点 ✕ 不打开灯箱
- **FR-139.5** 上传中 / 上传失败的图同样可点开（看本地图）
- **FR-139.6** 键盘弹起时第一次点只收键盘（沿用灯箱 bug 507 规则）
- **FR-139.7** 关闭：单击图片或黑边、下滑、✕、系统返回键；双击为缩放不关闭（均为灯箱既有能力）
- **FR-139.8** 埋点复用 `lightbox_opened` / `lightbox_dismissed`，`source = publish_compose`

**FR-124 版本更新提醒接线**

- **FR-124.1** 客户端判定所用版本号 = 真机包版本（`package_info_plus`），不再依赖 `--dart-define`；必须真机包验证
- **FR-124.2** 后端版本配置从环境变量搬到配置表；`GET /api/v1/app-version` 返回 iOS 最新版本号、Android 最新版本号、最低支持版本、两个商店链接；游客可读
- **FR-124.3** App 按自身平台取对应最新版本号做三态判定（强制 / 推荐 / 不提示）；只调高一端时另一端无任何提示
- **FR-124.4** 触发时机：冷启动每次必查；回到前台距上次检查 ≥ 6 小时才发请求；设置页手动检查。不做登录后 / 关键流程前触发
- **FR-124.5** 推荐态 = 首页（Social）顶部一行横幅（标题 `updateRecommendTitle` + 胶囊按钮 `updateGoStore` + ✕），随页面滚动、不悬浮、不阻断；本次运行内每次回首页都在，直到点 ✕ 或「前往更新」
- **FR-124.6** 横幅点 ✕ 或「前往更新」= 进入 72 小时冷却（同一 `latestVersion`）；`latestVersion` 上升立即恢复；点「前往更新」跳对应平台商店并当下收起
- **FR-124.7** 强制态 = 重做后的更新弹窗，不可跳过（返回键、遮罩、无「稍后」三者同时成立），不受冷却影响
- **FR-124.8** 更新弹窗样式重做为品牌卡片（白卡圆角 24、顶部紫色图标块、标题正文居中、版本标签「Versi {version}」、整行主按钮）；推荐态（设置页手动检查）主按钮下加文字按钮「Nanti」
- **FR-124.9** 设置页新增分组「TENTANG APLIKASI」（在「ZONA BAHAYA」之前）+ 一行「Periksa pembaruan」（右侧显示当前版本号）；检查中右侧转圈且不可再点；底部版本号小字保留
- **FR-124.10** 手动检查无视冷却：有新版本弹推荐态弹窗（点「Nanti」进入冷却）；已是最新 → toast「已是最新版本」；失败 → toast「检查失败，请稍后再试」。自动检查失败静默放行
- **FR-124.11** 商店链接为空 → 横幅不出现、弹窗不渲染「前往更新」按钮
- **FR-124.12** 端点不可达 / 超时 / 异常 → 不提示、不阻断启动
- **FR-124.13** 埋点：`app_update_prompt_shown{decision, surface}`（横幅每次运行只报一次）、`app_update_go_store_tapped{surface}`、`app_update_later_tapped{surface}`（横幅 ✕ 记为 later）
- **FR-124.14** `minSupportedVersion` 默认 `1.0.0` 永不上调；版本配置初始值见决策日志 D-10

**FR-126 里程碑新增「场所打卡」「性格测试」两项**

- **FR-126.1** 猫 / 狗 / 通用三份清单各新增两项 S 级、系统自动成就：猫 `C-S17` / `C-S18`、狗 `D-S17` / `D-S18`、通用 `G-S10` / `G-S11`；与既有里程碑并存，不改任何现有条目；不纳入新手任务
- **FR-126.2** 打卡达成：打卡成功即达成，按本次打卡选中的宠物逐宠达成
- **FR-126.3** 性格测试达成：该宠物的**完整解读**解锁成功即达成（QRIS / PawCoin / 配型后补差价升级均算）；只单独买配型不算；只答题未解锁不算；重测再解锁不重复达成（决策日志 D-4 删除「0 元解锁」口径）
- **FR-126.4** 达成当场弹庆祝页：盖在打卡成功页 / 测试结果页之上，关闭回原页；补庆祝机制保留作兜底
- **FR-126.5** 存量回填：V1.3.2 上线后已打卡 / 已解锁完整解读的宠物一次性回填为已完成，完成时间取首次打卡 / 首次解锁时间，`celebrated_at` 同时回填、不补弹
- **FR-126.6** 清单总数变为 33 / 33 / 18，进度条分母随之变化
- **FR-126.7** 文案三处同步：App en / id、后端中文标题、H5 分享页印尼语标题；徽章插画两枚（已交付 PNG）
- **FR-126.8** 埋点不新增，既有里程碑事件按 code 自动覆盖

**FR-137 运营弹窗**

- **FR-137.1** 居中弹窗：一张 4:5 图（可选标题、正文）+ 一个按钮 + 压在图片右上角的 ✕
- **FR-137.2** 点图片或按钮按配置跳转：商品 / 品类 / App 内页面（固定 5 个：首页 · 发帖页 · Toko 首页 · Know Your Pet · 健康页）/ 外部链接 / 无跳转；「无跳转」时按钮 = 关闭、点图无反应；需登录页面游客先进登录
- **FR-137.3** 外部链接在 App 内浏览器打开（`url_launcher` App 内模式，系统默认配色，D-9），关闭回原页；设备不支持退回系统浏览器
- **FR-137.4** 出现时机：冷启动后进入落地页（Diary 或 Social）时（D-8）；冷启动未落在这两页的，本次运行内之后首次进入这两页时再判定（D-11）
- **FR-137.5** 频次：每人一次 / 每天最多一次（WIB 自然日）；同一冷启动最多一个，多个生效按优先级取最高（相同时后创建优先）
- **FR-137.6** 当次冷启动已出现强制更新、推送权限申请、新功能引导蒙层、里程碑庆祝、**ATT 授权框**（D-6）任一功能弹窗 → 运营弹窗本次不出、顺延下次冷启动；推荐更新横幅不挡
- **FR-137.7** 人群：全部 / 新用户（注册 N 天内，N 每个弹窗单独设、默认 7）/ 老用户；游客只看「全部用户」，频次按设备计；游客期间弹过的「每人一次」登录后继承、不再弹
- **FR-137.8** 语言按 App 当前语言取 EN / ID 文案与图片；只传一张图则共用
- **FR-137.9** 等图片加载完成才弹；加载失败本次不弹、不计频次
- **FR-137.10** 跳转目标失效（商品下架）、弹窗停用、过生效时间 → 不再出现
- **FR-137.11** 埋点 `ops_popup_shown` / `ops_popup_tapped` / `ops_popup_closed`（`popup_id`）

**FR-138 运营系统通知**

- **FR-138.1** 运营发送后写入目标用户通知中心，同时发系统推送；未授权推送只进通知中心
- **FR-138.2** 显示语言按**账号语言**（`users.locale`：en → EN，其余 → ID）；推送与通知中心同一语言
- **FR-138.3** 通知中心与其他系统通知同列；该类型显示后端下发的标题与正文（不是本地固定文案）
- **FR-138.4** 点系统推送先落通知中心并标已读；在通知中心点该条按配置跳转（规则同 FR-137.2 / 137.3）
- **FR-138.5** 只发已注册用户；排除已封号、已注销、虚拟号
- **FR-138.6** 大批量发送推送分批下发，避免腾讯 IM 限流导致整批失败；失败批次重试，仍失败记部分失败（后台 PRD AB-31，核心逻辑归本主题）
- **FR-138.7** 新通知类型须重建 `ck_notifications_type` 全量取值
- **FR-138.8** 埋点 `ops_notification_opened{campaign_id, source}`（推送或通知中心打开均报）

**UI 稿附带的既有组件改动**

- **FR-UI.1** 首页场所入口行 `PlaceEntryRow` 图标由普通定位针改为「折叠地图 + 定位针」（UI 决策 2026-10-09）

### NonFunctional Requirements

- **NFR1 隐私**：选图处理（剥 EXIF 含 GPS、≤10MB）在任何情况下不得移除；发布后的图片不含 EXIF / GPS（FR-125 AC1b）
- **NFR2 流量**：Feed 列表绝不加载原图（印尼市场流量敏感）；三处取档按 AD-7 写死
- **NFR3 安全规则层**：内容可见性、软删除、拉黑、举报者隐藏过滤在任何候选池、任何降级态下不可绕过（FR-122）
- **NFR4 确定性**：推荐序列在同一刷新种子下可重放（翻页不重不漏）
- **NFR5 失败放行**：版本检查自动失败不阻断启动、不提示；运营弹窗任何异常不阻断启动
- **NFR6 防锁死**：强制更新弹窗必须有可点的「前往更新」；版本号来源必须先于强制更新能力正确（真机包验证）
- **NFR7 向后兼容**：无 `cropOffset` 的内容渲染与改动前逐像素一致；无任何回填
- **NFR8 推送限流**：大批量推送分批、限速，失败重试，不因限流整批失败
- **NFR9 多语言**：新增文案 EN / ID 两语同步；里程碑标题 App / 后端 / H5 三处同步（跨库测试钉住）
- **NFR10 无新中间件**：异步只用 `@Async` + DB 状态机；不新增 Flutter 依赖（D-9）
- **NFR11 数据库约束**：`ck_notifications_type` 重建须列全集；Flyway 迁移一律时间戳命名
- **NFR12 埋点口径**：裁剪埋点取值变更需通知数据侧按发版日切分；横幅曝光每次运行只报一次

### Additional Requirements

（来自 `architecture-v1.3.4-app-delta.md`，无 starter template）

- **AR1（AD-1）** 后端按「谁用」切：本主题交付迁移、实体、App 读接口、运营通知发送核心；admin 主题调用本主题服务；本主题用迁移预置数据自测
- **AR2（AD-2）** 教育池 = 新仓储查询（与主池共用同一份过滤）+ 按 id 合并后交给未改动的排序引擎；新增部分索引 `idx_content_posts_knowledge_feed`
- **AR3（AD-3）** `feed_rank_config` 新增 `edu_freshness_half_life_hours`（默认 720，>0）；`FeedRankScorer.freshness` 改为传半衰期参数；后台保存算法参数不得覆盖该列
- **AR4（AD-4）** `jitterFactor` 改用 SplitMix64 终混；不用 `Random` / `Math.random`
- **AR5（AD-5）** `content_posts.image_crop_offsets JSONB`（与 `image_urls` 同序同长）；请求 `imageCropOffsets` 严格校验（长度不齐或越界 422）；Feed / 详情 DTO 返回
- **AR6（AD-6）** 新纯函数 `feedImageAlignment(size, cropOffset)`，比例逻辑不动；存量逐像素一致以 golden 测试覆盖
- **AR7（AD-7）** 取档：Feed `w_800` / 详情 `w_1080` / 灯箱 1080 垫底 + 原图；单测钉住 `ossResized` 对当前 OSS 域名生效
- **AR8（AD-8）** 删除 `CropPreset` / `cropToAspect` / 锁档；`ImageUploadItem.cropOffset` 随项走（重排 / 删除）；露出区域页一次处理整批超界图
- **AR9（AD-9）** 发帖页缩略图 `onTap` → `ImageLightbox.openMemory(source: 'publish_compose')`；✕ 独立吃掉点击
- **AR10（AD-10）** `app_version_config` 单行表 + 预置值（D-10）；接口字段改为双端最新版本号；校验服务 `AppVersionConfigService.update` 供 admin 主题调用；删除 `petgo.app-version.*` env
- **AR11（AD-11）** `appUpdateControllerProvider` 统一持有判定 / 冷却 / 曝光状态；`AppPrefs` 新增两个冷却键
- **AR12（AD-12）** 进程内「本次冷启动功能弹窗记录」：ATT（真正弹出）、推送权限、强制更新、新功能蒙层、里程碑庆祝出现时登记
- **AR13（AD-13）** 启动链末尾追加版本检查与运营弹窗判定；运营弹窗判定挂在 Diary / Social Tab 根页的「首次可见」上
- **AR14（AD-14）** `ops_popups` + `ops_popup_impressions` 两表；App 读接口 `GET /api/v1/ops-popups/candidates`（服务端过滤后返回有序候选）+ 曝光上报 `POST /api/v1/ops-popups/{token}/impressions`
- **AR15（AD-15）** 本地频次记录（游客 + 登录用户都写），用于游客继承；WIB 日期按 UTC+7 计算
- **AR16（AD-16）** 共享 `OpsTarget` + `openOpsTarget`：5 个固定 App 页面映射、需登录走 `requireLogin(RouteIntent)`、外链 `inAppBrowserView` 失败退回系统浏览器；不扩展 `tailtopia://`
- **AR17（AD-17）** 通知类型 `OPS_ANNOUNCEMENT` + CHECK 全量重建（32 值）；`ops_notification_campaigns`（+ 指定用户子表）；写入时按收件人语言选文案；`NotificationItem` 对该类型附 `opsTarget`；`OpsNotificationDispatcher`（人群解析、批量写入、按语言分组批量推送、重试 3 次、定时扫描、条件更新防重）
- **AR18（AD-18）** 里程碑清单末尾追加、既有 code / sortOrder 不变；新事件 `PLACE_CHECKED_IN` / `TAILSONALITY_UNLOCKED`；`PlaceCheckedInEvent` 加 `petIds`；只认 `sku == TAILSONALITY`；回填迁移先补名册行再补完成行、不发通知；四个跨库测试同步更新
- **AR19（AD-19）** App 端三张表（标题 / 庆祝文案 / 徽章 key）各加 6 code；两枚插画 PNG 转 webp 放 App 与后端两处；当场庆祝用短轮询范式
- **AR20（AD-20）** `PlaceEntryRow` 图标用 UI 稿 SVG（`flutter_svg` 已有）
- **AR21** 迁移清单 8 个（AD §11），时间戳命名，提交前跑 `check-flyway-versions.sh origin/main`
- **AR22** 既有测试需同步改动清单见 AD §12

### UX Design Requirements

（来自 `ui-v1.3.4-app.html`，31 屏；文案取 app_id.arb 真值或 D-12 审定值）

- **UX-DR1（A1–A3）露出区域页**：由现有裁剪页改造——黑底、左上白色 ✕、标题「Sesuaikan foto」；整图 + 白框取景（白框 = 展示窗口），框外压暗 55%，框四角白色加粗角标，框中央「‹ Geser ›」半透明深底胶囊（宽图左右箭头 / 竖图上下）；图片正下方提示「Bagian di dalam bingkai yang tampil di Sosial」；底部全宽紫色主按钮「Lanjut」。删除比例 chips 与锁定提示。单指拖动，到边即停
- **UX-DR2（A4）多张超界图**：右上页码「2 / 3」；取景区可左右滑切图；下方缩略图条（只列超界图，48 尺寸、圆角 10，当前张紫框）；点一次「Lanjut」完成整批
- **UX-DR3（A6 / A7）展示**：Feed 卡与详情轮播结构不变，仅图片区按 `cropOffset` 开窗
- **UX-DR4（B1–B5）发帖页看大图**：格子可点、被点格子不加标记；灯箱外观同帖子详情（纯黑、左上 ✕、顶部页码胶囊）；上传失败格子的 ⚠ / ✕ 保持现状不改（决策日志 D-14）
- **UX-DR5（C1）首页更新横幅**：压成一行、高 52，位于滚动头部最上方、场所入口行之前，左右 16；白底小方块内紫色小图标 · 标题「Versi baru tersedia」（不截断）· 紫色胶囊「Perbarui sekarang」· ✕（热区 44）；浅紫底（同通知中心「开启推送」引导条配色）
- **UX-DR6（C2 / C5）更新弹窗重做**：白卡圆角 24；顶部 64 紫色渐变图标块（手机 + 下载箭头）；标题 / 正文居中；版本标签「Versi {version}」（取对应平台最新版本号）；整行主按钮「Perbarui sekarang」；推荐态主按钮下方文字按钮「Nanti」；强制态无「Nanti」
- **UX-DR7（C3–C7）设置页**：「TENTANG APLIKASI」分组 + 「Periksa pembaruan」行（右值当前版本，tabular 数字）；检查中右侧紫色转圈、行不可点；已是最新 / 失败用统一 toast `showAppToast`
- **UX-DR8（D1 / D2）运营弹窗**：居中、圆角 24、柔和投影；4:5 图片 1px 8% 黑色内描边；标题 + 正文（可选，pretty 换行）+ 一个按钮；✕ 压在图片右上角、热区 44；背后压暗；无标题正文时图片即主体
- **UX-DR9（D3 / D3b）App 内浏览器**：iOS SFSafariViewController / Android Chrome Custom Tabs，**系统默认配色**（D-9 作废 UI 稿的品牌紫着色）
- **UX-DR10（E2 / E3）通知中心运营通知**：同列不分栏；左 40×40 图标块紫色配色、图标 `campaign_rounded`；标题 / 正文显示运营填写内容；未读紫点与 `#F8F2FF` 底，已读白底
- **UX-DR11（F1 / F2）庆祝页**：沿用既有庆祝页结构不改；新增两枚徽章插画、标题 / 正文（D-12）
- **UX-DR12（F3 / F4）里程碑列表**：S 组末尾新增两枚徽章（未达成显示全局锁定图）；总数 33 / 33 / 18
- **UX-DR13（C1 附）场所入口图标**：「折叠地图 + 定位针」

### 新增文案（决策日志 D-12 审定）

| key（拟） | 印尼文 | 英文 | 中文（后端） |
|---|---|---|---|
| 露出区域页提示 | Bagian di dalam bingkai yang tampil di Sosial | The part inside the frame shows in Social | — |
| 拖动提示 | Geser | Drag | — |
| 露出区域页按钮 | Lanjut（复用 `onboardNext`） | Next | — |
| 设置分组 | TENTANG APLIKASI | ABOUT THE APP | — |
| 设置入口 | Periksa pembaruan | Check for updates | — |
| 已是最新 | Kamu sudah memakai versi terbaru | You're on the latest version | — |
| 检查失败 | Gagal memeriksa pembaruan. Coba lagi nanti. | Couldn't check for updates. Try again later. | — |
| 版本标签 | Versi {version} | Version {version} | — |
| 徽章名·打卡 | Check-in pertama | First check-in | 第一次场所打卡 |
| 徽章名·性格测试 | Kenal kepribadian | Personality revealed | 解锁性格测试结果 |
| 庆祝标题·打卡 | Check-in pertama {name}! 📍 | {name}'s first check-in! 📍 | — |
| 庆祝正文·打卡 | {name} resmi jadi anabul jalan-jalan. | {name} is officially a pet on the go. | — |
| 庆祝标题·性格测试 | {name} sudah kenal dirinya! 🔮 | {name} knows who they are now! 🔮 | — |
| 庆祝正文·性格测试 | Kepribadian {name} sudah terbuka. | {name}'s personality is unlocked. | — |

### FR Coverage Map

| FR | Epic | 说明 |
|---|---|---|
| FR-122.1 ~ 122.6 | Epic 1 | 教育类独立池 + 半衰期配置 |
| FR-135.1 ~ 135.4 | Epic 1 | 刷新抖动打散 |
| FR-125.1 ~ 125.11 | Epic 2 | 原图上传 + 露出区域 + 展示开窗 + 埋点口径 |
| FR-139.1 ~ 139.8 | Epic 2 | 发帖页看大图 |
| FR-124.1 ~ 124.14 | Epic 3 | 版本更新：配置表 + 横幅 + 弹窗 + 设置页 |
| FR-UI.1 | Epic 3 | 首页场所入口图标（与横幅同在首页头部） |
| FR-126.1 ~ 126.8 | Epic 4 | 两项新成就 + 回填 + 当场庆祝 |
| FR-137.1 ~ 137.11 | Epic 5 | 运营弹窗 + 启动弹窗记录 + 统一跳转 |
| FR-138.1 ~ 138.8 | Epic 6 | 运营系统通知（复用 Epic 5 的统一跳转） |

## Epic List

### Epic 1: 首页推荐更「新」——科普不再循环、下拉真的换一批
用户在首页 ALL 推荐里不再反复刷到同一批科普；下拉刷新后第一条和整体顺序会明显变化。纯后端，App 与后台无改动。
**FRs covered:** FR-122.1 ~ 122.6、FR-135.1 ~ 135.4
**依赖：** 无。改动集中在 `FeedRankEngine` / `FeedRankScorer` / `FeedRecommendationService` / `ContentPostRepository`，两个 FR 同一组文件，合为一个 epic。

### Epic 2: 发图不丢画面——原图上传、选露出位置、发前看大图
用户发超宽 / 超长图时不再被永久裁掉画面，可以选首页露出哪一段；发布前在发帖页能点开看完整大图。看帖的人点开大图看到完整原图。
**FRs covered:** FR-125.1 ~ 125.11、FR-139.1 ~ 139.8
**依赖：** 无。两个 FR 都改发帖页 `publish_compose_page.dart`，合为一个 epic；先后端字段、再展示、再发布链路，最后发帖页看大图。

### Epic 3: 用户能及时更新到新版本
有新版本时首页顶部出现一行提醒（可关、72 小时冷却），必须更新时弹不可跳过的弹窗，设置页可以手动检查更新；版本号由运营配置，iOS / Android 分开。顺带把首页场所入口图标换成「折叠地图 + 定位针」（与横幅同在首页头部）。
**FRs covered:** FR-124.1 ~ 124.14、FR-UI.1
**依赖：** 无。版本配置的后台页面（AB-22）在 admin 主题；本 epic 用迁移预置值即可完整工作。

### Epic 4: 打卡与性格测试也能解锁里程碑
宠物第一次场所打卡、第一次解锁完整性格解读时，当场弹出庆祝页并点亮新徽章；老用户已做过的自动补上。
**FRs covered:** FR-126.1 ~ 126.8
**依赖：** 无（V1.3.2 打卡与 Tailsonality、V1.3.3 配型付费均已在 main）。

### Epic 5: 运营弹窗——打开 App 就能看到活动
运营配置的活动弹窗在用户冷启动进入 Diary / Social 时弹出，按人群、频次、优先级只弹一个，不和功能弹窗抢；点击可跳商品、品类、5 个 App 页面或外部链接。
**FRs covered:** FR-137.1 ~ 137.11
**依赖：** Epic 3（强制更新弹窗要登记进本 epic 新建的「本次冷启动功能弹窗记录」——若 Epic 3 未完成，本 epic 先登记其余功能弹窗，强制更新的登记随 Epic 3 补）。配置页面（AB-30）在 admin 主题；本 epic 用迁移 / 测试预置数据完整工作。

### Epic 6: 运营系统通知——运营能给用户发消息
运营选定的用户在通知中心收到运营写的消息（按账号语言），同时收到推送；点开按配置跳转。发送核心（人群、分批推送、重试、定时）在本 epic。
**FRs covered:** FR-138.1 ~ 138.8
**依赖：** Epic 5（复用其「统一跳转目标」）。发送页面（AB-31）在 admin 主题，调用本 epic 的发送服务。

---

## Epic 1: 首页推荐更「新」——科普不再循环、下拉真的换一批

用户在首页 ALL 推荐里不再反复刷到同一批科普；下拉刷新后第一条和整体顺序会明显变化。纯后端，App 与后台无改动。（FR-122、FR-135；AD-2 ~ AD-4）

### Story 1.1: 教育类槽位改用近 90 天独立候选池

As a 刷首页的用户,
I want 推荐里的科普内容来自近 90 天的全部科普、而不只是最近 10 天的 50 来条,
So that 我不会一两天就把科普刷完、之后反复看到同样的内容。

**Acceptance Criteria:**

**Given** 库中有 91 天前、60 天前、5 天前发布的公开 KNOWLEDGE 帖，且主候选池（最近 1000 条）只覆盖近 10 天
**When** 为某用户生成 ALL Tab 推荐序列
**Then** 60 天前与 5 天前的科普都进入候选；91 天前的不进入
**And** 教育池查询按发布时间倒序上限 1000 条，与主池按 id 去重合并后交给 `FeedRankEngine.rank()`，引擎的槽位挑选、5 级放宽、防扎堆逻辑代码不改（AD-2）

**Given** 一条 60 天前的科普，作者被当前用户屏蔽 / 帖子被当前用户举报 / 帖子已软删除 / 帖子为非公开 / 帖子状态非 PUBLISHED（含被举报下架为 UNDER_REVIEW）
**When** 为该用户生成推荐序列
**Then** 该帖不出现在任何槽位
**And** 教育池与主池的过滤条件为同一份代码（共享片段或同一参数化方法），不另写一份（PRD AC2，NFR3）

**Given** 教育池为空或条数不足以填满教育槽位
**When** 生成推荐序列
**Then** 行为与改动前一致：按降级链级别 1 用其他属性最高分内容补位，不空槽、不报错（PRD AC4）

**Given** 迁移执行后
**When** 查看 `content_posts` 索引
**Then** 存在部分索引 `idx_content_posts_knowledge_feed (created_at DESC, id DESC) WHERE type = 'KNOWLEDGE' AND deleted_at IS NULL`，迁移为时间戳命名

**Given** 本 story 完成
**When** 检查改动范围
**Then** 属性配比、物种配比、刷新抖动幅度、内容属性归属规则（按 `content_posts.type` 映射）均未改动（FR-122.6）

**Given** 游客（无 viewerId）
**When** 请求 ALL Tab
**Then** 教育池同样生效，举报 / 屏蔽两项过滤按游客口径跳过（与主池一致）

### Story 1.2: 科普内容新鲜度改为 30 天半衰、可配置

As a 刷首页的用户,
I want 发布较久的优质科普仍能凭互动质量排上来,
So that 进了候选池的老科普不会因为「不新」而永远垫底。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `feed_rank_config`
**Then** 新增列 `edu_freshness_half_life_hours DOUBLE PRECISION NOT NULL DEFAULT 720`，CHECK `> 0`，存量单行取值 720（AD-3）

**Given** 一条 90 天前的 KNOWLEDGE 帖与一条 90 天前的 DAILY 帖
**When** 计算推荐分
**Then** KNOWLEDGE 的新鲜度按 `1 / (1 + 小时数 / 720)`（≈ 0.25），DAILY 仍按 `1 / (1 + 小时数 / 24)`（≈ 0.011）
**And** 按内容类型生效：KNOWLEDGE 内容不论落在教育槽位还是经降级补到其他槽位，一律用配置的半衰期（PRD 2026-10-07）

**Given** 工程直接在数据库把该列改为 360
**When** 旧序列快照过期（≤ 30 分钟）后生成新序列
**Then** 新序列按 360 计算，无需发版或重启（PRD AC3）

**Given** 运营在后台「算法参数」页修改并保存任意其他参数
**When** 保存完成
**Then** `edu_freshness_half_life_hours` 保持原值不变（集成测试钉住；该字段不在后台露出，PRD OQ-1）

**Given** 非 ALL Tab（按类型的时间倒序 Tab）
**When** 请求列表
**Then** 排序不受影响（不打分）

### Story 1.3: 下拉刷新真正「换一批」——抖动系数打散

As a 刷首页的用户,
I want 每次下拉刷新后首页顺序明显不同、第一条会换,
So that 「刷新换一批」名副其实。

**Acceptance Criteria:**

**Given** 同一刷新种子、id 连续的 3 条内容
**When** 生成抖动系数
**Then** 相邻 id 的系数不再集中在 0.001 以内；单测对连续 id 序列做统计断言，相邻系数相关性接近 0（PRD AC1）
**And** 实现为 SplitMix64 终混打散种子与 id 后生成 `[0,1)` 随机数；不使用 `java.util.Random` / `Math.random` / `ThreadLocalRandom`（AD-4）

**Given** 1000 条内容、连续刷新 50 次（剔除顶置与作者本人待审帖），用与线上同口径的离线模拟
**When** 统计第一条内容的不同种数
**Then** 与「理想均匀随机」模拟处于同一量级（参照 15 种），不再出现同一条占 40 次（PRD AC2）

**Given** 同一刷新种子
**When** 重复生成序列，或按游标翻页续算
**Then** 结果逐条一致，翻页不重复、不漏出（PRD AC3，NFR4）

**Given** 后台刷新抖动幅度为 0，或种子为空
**When** 生成序列
**Then** 系数恒为 1，排序与纯分数排序完全一致（PRD AC4）

**Given** 抖动幅度为 0.4
**When** 生成系数
**Then** 系数取值区间仍为 `(0.6, 1]`

---

## Epic 2: 发图不丢画面——原图上传、选露出位置、发前看大图

用户发超宽 / 超长图时不再被永久裁掉画面，可以选首页露出哪一段；发布前在发帖页能点开看完整大图；看帖的人点开大图看到完整原图。（FR-125、FR-139；AD-5 ~ AD-9）

### Story 2.1: 帖子保存与下发每张图的露出位置

As a 发帖用户,
I want 我为每张图选的露出位置随帖子一起保存、并在看帖时下发,
So that 别人刷到我的帖子时看到的是我选的那一段。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `content_posts`
**Then** 新增可空列 `image_crop_offsets JSONB`，元素为 `[0,1]` 小数或 `null`，与 `image_urls` 同序同长；存量行为 `NULL`，不回填（AD-5，NFR7）

**Given** 发帖请求带 `imageCropOffsets`，长度与 `imageUrls` 相等、非空元素都在 `[0,1]`
**When** `POST /api/v1/content-posts`
**Then** 原样落库，帖子创建成功

**Given** `imageCropOffsets` 长度与 `imageUrls` 不等，或任一非空元素 < 0 或 > 1
**When** 发帖
**Then** 返回 422（ProblemDetail），帖子不创建（严格校验，不静默丢弃，AD-5）

**Given** 发帖请求不带 `imageCropOffsets`（旧客户端 / 无超界图）
**When** 发帖
**Then** 正常创建，该列为 `NULL`

**Given** 有 / 无露出位置的帖子
**When** 请求 Feed 列表与帖子详情
**Then** `FeedItemResponse` 与 `ContentDetailResponse` 带 `imageCropOffsets`（与 `imageUrls` 同序）；为 `NULL` 时按全局 non_null 省略

### Story 2.2: Feed 卡与详情页按露出位置开窗

As a 刷首页的用户,
I want 超宽 / 超长图在卡片和详情页里露出发帖人选的那一段,
So that 我看到的是发帖人想展示的画面，而不总是正中间。

**Acceptance Criteria:**

**Given** 一张 3:1 宽图（`cropOffset` 为 0 / 0.5 / 1）与一张 1:2 竖图（同三值）
**When** 在 Feed 卡与详情轮播中渲染
**Then** 窗口分别贴起始边 / 居中 / 贴结束边（宽图为左右，竖图为上下）（PRD AC3）
**And** 新增纯函数 `feedImageAlignment(size, cropOffset)`：宽图 `Alignment(2·offset − 1, 0)`、竖图 `Alignment(0, 2·offset − 1)`，单测覆盖三值（AD-6）

**Given** 原图比例 `< 0.75` 或 `> 1.78`
**When** 计算展示窗口
**Then** 窗口比例等于 `clampFeedRatio(原图比例)` 的返回值（0.75 / 1.78），以算术断言覆盖（PRD AC2）；`resolveFeedImageAspect` / `resolveDetailImageAspect` 的比例逻辑不改

**Given** 无 `cropOffset` 的帖子（存量、容差区间内的图）
**When** 渲染 Feed 卡与详情轮播
**Then** 渲染结果与改动前逐像素一致，以改动前后 golden 截图比对覆盖（PRD AC4，NFR7）

**Given** 同一帖子
**When** 依次查看 Feed 卡、详情轮播
**Then** 两处比例与露出位置一致（V1.3.0「详情与 Feed 一致」不变）

**Given** 详情页点击图片
**When** 打开灯箱
**Then** 展示完整原图、不套用任何裁切；多图时灯箱滑动位置与轮播同步（PRD AC5 回归）；灯箱组件代码不改

**Given** 各处加载图片
**When** 检查请求的图片尺寸
**Then** Feed 卡 `w_800`、详情轮播 `w_1080`、灯箱先 `w_1080` 垫底再加载原图；单测钉住 `ossResized` 对当前 OSS 域名（`*.aliyuncs.com`）生效（AD-7，NFR2）
**And** 置顶卡（`promo_pinned_card`）同样按露出位置开窗

### Story 2.3: 露出区域页（取代三档裁剪页）

As a 发帖用户,
I want 选了超宽 / 超长图后，在一个页面里拖动白框选首页露出哪一段，多张图同页切换,
So that 整张图都保留，我只决定首页展示哪一部分。

**Acceptance Criteria:**

**Given** 传入一批超界图（1 张或多张）
**When** 打开露出区域页
**Then** 页面为黑底、左上白色 ✕、标题「Sesuaikan foto」；整图显示，白框 = 展示窗口（宽图窗口比例 1.78、竖图 0.75），框外压暗 55%，框四角白色加粗角标，框中央「‹ Geser ›」半透明深底胶囊（竖图为上下箭头）；图片正下方提示「Bagian di dalam bingkai yang tampil di Sosial」；底部全宽紫色主按钮「Lanjut」（UX-DR1）
**And** 没有比例选择 chips，也没有「比例跟随第一张」的锁定提示

**Given** 页面打开
**When** 用户未做任何拖动
**Then** 每张图的白框默认居中（offset 0.5）

**Given** 宽图 / 竖图
**When** 单指拖动白框
**Then** 宽图只能左右、竖图只能上下移动，到边即停；拖到最左（最上）offset = 0，最右（最下）= 1

**Given** 一次传入 3 张超界图
**When** 查看页面
**Then** 右上显示页码「1 / 3」；取景区可左右滑动切图；下方缩略图条只列这批超界图（48 尺寸、圆角 10），当前张紫框；点缩略图跳到该张（UX-DR2）
**And** 每张图的 offset 各自独立，切换不互相影响（PRD AC7）

**Given** 用户调了其中几张
**When** 点「Lanjut」
**Then** 页面一次返回整批 offset 列表（未调的为 0.5），以及每张是否被调整过

**Given** 页面打开
**When** 点左上 ✕ 或系统返回
**Then** 页面返回「取消」，调用方据此丢弃本次选的全部图（PRD 2026-10-09）

**Given** 新增文案
**When** 切换 EN / ID
**Then** 「Sesuaikan foto / Adjust photo」沿用；提示与「Geser / Drag」为新增 key（D-12）；按钮复用 `onboardNext`（Lanjut / Next）

### Story 2.4: 发布链路改为原图上传并带上露出位置

As a 发帖用户,
I want 我的图片完整上传、不再被裁掉,
So that 别人点开大图能看到我拍的全部画面。

**Acceptance Criteria:**

**Given** 用户选图（含超界图）
**When** 选图完成
**Then** 所有图先经 `ImageProcessor`（剥 EXIF 含 GPS、≤10MB），超界图自动进入露出区域页（Story 2.3）；容差区间 `[0.75, 1.78]` 内的图不进该页（PRD AC6 / AC8）

**Given** 在露出区域页点「Lanjut」
**When** 回到发帖页
**Then** 超界图带各自 offset 加入，区间内图 offset 为 null；不调整直接点「Lanjut」的图 offset = 0.5（PRD AC8）

**Given** 在露出区域页点 ✕ / 返回
**When** 回到发帖页
**Then** 本次选的图一张都不加入（区间内的也不加）

**Given** 发帖页已有图
**When** 长按拖拽重排或点 ✕ 删除
**Then** 每张图的 offset 跟着该图走（`ImageUploadItem.cropOffset`），发布时按最终顺序组装 `imageCropOffsets`（AD-8）

**Given** 一张超界图发布成功
**When** 比对服务端存储文件与该图 `ImageProcessor` 处理后的产物
**Then** 文件哈希一致——处理之后无任何裁切或重编码（PRD AC1）
**And** 发布后的图片不含任何 EXIF / GPS（PRD AC1b，NFR1）

**Given** 发帖页缩略图
**When** 含超界图
**Then** 缩略图仍为方形、取正中间，不跟随露出位置（PRD 2026-10-09）

**Given** 代码层面
**When** 本 story 完成
**Then** `CropPreset`、`cropToAspect`、`computeCropRect`、`applyBatchCrop` 的锁档逻辑与旧三档裁剪页已删除；`needsCrop()`、`kFeedRatioMin / kFeedRatioMax` 保留不改（PRD 不改容差）

**Given** 超界图完成露出区域选择
**When** 上报埋点
**Then** `publish_image_crop_completed` 每张超界图一条，属性 `target_ratio`（`0x75` / `1x78`）+ `adjusted`（布尔）；不再有 `is_batch_lock_source`；`publish_image_crop_shown` / `publish_image_crop_exit_tapped` 保留（PRD §4，NFR12）
**And** story 的 Completion Notes 写明「埋点口径变更，数据侧须按发版日切分」

### Story 2.5: 发帖页点缩略图看大图

As a 发帖用户,
I want 发布前点已选图片的缩略图，全屏查看完整图片,
So that 我能确认图片细节再发。

**Acceptance Criteria:**

**Given** 发帖页已选 4 张图
**When** 点第 2 张缩略图
**Then** 打开灯箱定位第 2 张（`ImageLightbox.openMemory`，`source = 'publish_compose'`），左右滑依次查看全部 4 张，顺序与格子一致；拖拽重排后再点开，顺序同步为新顺序（PRD AC1，AD-9）

**Given** 已选图中有超宽 / 超长图
**When** 在灯箱查看
**Then** 显示完整原图，不套 Feed 窗口裁切（PRD AC2）

**Given** 灯箱打开
**When** 单击图片或黑边、下滑、点 ✕ 或按系统返回
**Then** 关闭回发帖页，已选图、文字、类型等填写内容全部保留（PRD AC3 / AC7）
**And** 双击为缩放、不关闭；灯箱内无删除 / 重排 / 设封面按钮

**Given** 发帖页缩略图
**When** 点右上 ✕
**Then** 只删除该图、不打开灯箱；长按仍为拖拽重排（PRD AC4）

**Given** 某张图上传中或上传失败
**When** 点该缩略图
**Then** 照样打开灯箱看本地图片（PRD AC5）

**Given** 正文输入中、键盘弹起
**When** 点缩略图
**Then** 第一次只收起键盘，再点一次才打开（PRD AC6，灯箱 bug 507 规则）

**Given** 打开 / 关闭灯箱
**When** 上报埋点
**Then** `lightbox_opened` / `lightbox_dismissed` 的 `source` 为 `publish_compose`（PRD §4）

---

## Epic 3: 用户能及时更新到新版本

有新版本时首页顶部出现一行提醒，必须更新时弹不可跳过的弹窗，设置页可手动检查；版本号由运营配置、iOS / Android 分开。顺带更换首页场所入口图标。（FR-124、FR-UI.1；AD-10 ~ AD-11、AD-20）

### Story 3.1: 版本配置搬进数据库，接口按 iOS / Android 分别返回最新版本

As a 运营,
I want 版本配置存在数据库、iOS 与 Android 各有一个最新版本号,
So that 哪端上架就只改哪端，不用改服务器环境变量和重启。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `app_version_config`
**Then** 为单行表（`id = 1` CHECK），列 `ios_latest_version`、`android_latest_version`、`min_supported_version`、`ios_store_url`、`android_store_url`（均非空）、`updated_at`、`updated_by`（可空）（AD-10）
**And** 预置值：`min_supported_version = '1.0.0'`；iOS 链接 `https://apps.apple.com/app/id6785361196`；Android 链接 `https://play.google.com/store/apps/details?id=com.tailtopia.app`；两端最新版本号按**合入时商店实际版本**填写、不得为 `1.0.0`，迁移文件注释写明「发版检查项」（决策日志 D-10）

**Given** 任意客户端（含游客，无 JWT）
**When** `GET /api/v1/app-version`
**Then** 返回 `iosLatestVersion`、`androidLatestVersion`、`minSupportedVersion`、`iosStoreUrl`、`androidStoreUrl`，取自配置表；不再返回 `latestVersion`
**And** `petgo.app-version.*` 配置属性与对应 env 变量已删除，`.env.example` 同步

**Given** 本主题提供的 `AppVersionConfigService.update(...)`（供 admin 主题保存接口调用，AD-1）
**When** 传入非法版本格式（非 `x.y.z`）、商店链接非 `https://` 开头、最低支持版本高于任一端最新版本、或把最低支持版本调高到 `1.0.0` 以上但任一商店链接为空
**Then** 拒绝保存并给出对应错误码（后台 PRD §1.1）；合法输入保存成功并写入 `updated_at` / `updated_by`

**Given** 配置被改
**When** 再次请求接口
**Then** 立即返回新值（无缓存）

### Story 3.2: 用真机版本号判定，并重做更新弹窗实现强制更新

As a 使用旧版本的用户,
I want App 正确识别我装的版本，在必须更新时给我一个清楚、可直达商店的弹窗,
So that 我不会被误判，也不会被卡在没有出路的弹窗里。

**Acceptance Criteria:**

**Given** 真机安装 1.2.0 的包
**When** App 做版本判定
**Then** 判定所用版本 = 1.2.0（`PackageInfo.fromPlatform().version`），不再读 `--dart-define=APP_VERSION`；以真机包验证，不接受模拟器或 dart-define 环境下的结论（PRD AC2，NFR6）

**Given** iOS 设备
**When** 判定
**Then** 只按 `iosLatestVersion` 判定；Android 只按 `androidLatestVersion`；只调高一端时另一端无任何提示（PRD AC7）

**Given** App 冷启动
**When** 启动链（ATT → 推送权限 → …）结束后
**Then** 发一次版本检查；回到前台时距上次检查 ≥ 6 小时才发请求，不足则不产生网络调用（PRD AC1，AD-11）

**Given** 判定为强制更新（当前 < `minSupportedVersion`）
**When** 检查完成
**Then** 弹出重做后的更新弹窗：白卡圆角 24、顶部 64 紫色渐变图标块（手机 + 下载箭头）、标题 `updateForceTitle` / 正文 `updateForceBody` 居中、版本标签「Versi {version}」（取本平台最新版本号）、整行主按钮「Perbarui sekarang」（UX-DR6）
**And** 不可跳过：系统返回键、点遮罩均关不掉，且没有「Nanti」（PRD AC4）；强制态不受 72 小时冷却影响

**Given** 本平台商店链接为空
**When** 强制弹窗显示
**Then** 不渲染「前往更新」按钮（PRD AC6）；点「前往更新」时跳对应平台商店

**Given** 版本接口不可达、超时或返回异常
**When** 自动检查
**Then** 不提示、不阻断启动（PRD AC5，NFR5）

**Given** 强制弹窗曝光 / 点「前往更新」
**When** 上报埋点
**Then** `app_update_prompt_shown{decision: force, surface: dialog}`、`app_update_go_store_tapped{surface: dialog}`

**Given** 本 story 实现的 `appUpdateControllerProvider`
**When** 后续 story（横幅、设置页）接入
**Then** 判定、接口数据、上次检查时刻、冷却读写都由它统一持有（AD-11）；冷却键 `petgo.update_skipped_version` / `petgo.update_skipped_at` 存 `AppPrefs`

### Story 3.3: 首页顶部推荐更新横幅

As a 使用旧版本的用户,
I want 有新版本时首页顶部出现一行不打扰的提醒，可以关掉,
So that 我知道有新版本、又不被弹窗打断看内容。

**Acceptance Criteria:**

**Given** 判定为推荐更新（`minSupported ≤ 当前 < 本平台最新`）、本平台商店链接非空、不在冷却期
**When** 进入首页（Social）
**Then** 滚动头部最上方、场所入口行之前出现一行横幅（高 52、左右 16）：白底小方块内紫色小图标 · 标题「Versi baru tersedia」（`updateRecommendTitle`，不截断）· 紫色胶囊「Perbarui sekarang」（`updateGoStore`）· ✕（热区 44），浅紫底（UX-DR5）；随页面滚动、不悬浮；冷启动不弹窗（PRD AC3b）

**Given** 横幅显示
**When** 点「Perbarui sekarang」
**Then** 跳对应平台商店的 TailTopia 页面，横幅当下收起，记录 72 小时冷却（PRD AC8）
**And** 埋点 `app_update_go_store_tapped{surface: banner}`

**Given** 横幅显示
**When** 点 ✕
**Then** 横幅收起，记录 72 小时冷却；埋点 `app_update_later_tapped{surface: banner}`

**Given** 冷却记录存在
**When** 72 小时内、同一 `latestVersion` 再次判定为推荐
**Then** 不出横幅；服务端最新版本号上升到比记录更新 → 立即恢复（PRD AC3）

**Given** 本次运行内用户多次离开又回到首页
**When** 横幅仍在显示条件内
**Then** 每次都显示，`app_update_prompt_shown{decision: recommend, surface: banner}` 本次运行只报一次（PRD AC10）
**And** 横幅只在首页出现，其他页面没有

**Given** 本平台商店链接为空、接口异常或冷却期内
**When** 进入首页
**Then** 无横幅，首页与现状一致

### Story 3.4: 设置页手动检查更新

As a 用户,
I want 在设置页手动检查是否有新版本,
So that 我想更新时能主动确认。

**Acceptance Criteria:**

**Given** 打开设置页
**When** 查看分组
**Then** 「ZONA BAHAYA」之前新增分组「TENTANG APLIKASI」，内含一行「Periksa pembaruan」，右侧显示当前版本号（tabular 数字）；页面底部版本号小字保留不变（UX-DR7）

**Given** 点「Periksa pembaruan」
**When** 检查进行中
**Then** 行右侧换成紫色转圈，行不可再点

**Given** 有新版本（冷却期内也算，手动检查无视冷却）
**When** 检查完成
**Then** 弹出重做后的更新弹窗（推荐态）：标题 `updateRecommendTitle`、正文 `updateRecommendBody`、「Versi {version}」、主按钮「Perbarui sekarang」，其下文字按钮「Nanti」（UX-DR6）；点「Nanti」进入 72 小时冷却（影响首页横幅），埋点 `app_update_later_tapped{surface: dialog}`（PRD AC9）
**And** 若判定为强制更新，弹强制态弹窗（同 Story 3.2）

**Given** 已是最新版本
**When** 检查完成
**Then** toast「Kamu sudah memakai versi terbaru」/「You're on the latest version」（`showAppToast`）

**Given** 断网或超时
**When** 检查失败
**Then** toast「Gagal memeriksa pembaruan. Coba lagi nanti.」/「Couldn't check for updates. Try again later.」；自动检查失败仍静默（PRD 2026-10-09）

**Given** 新增文案
**When** 切换 EN / ID
**Then** 分组名、入口行、两条 toast、「Versi {version}」共 5 条 EN / ID 齐全（D-12）

### Story 3.5: 首页场所入口换成「折叠地图 + 定位针」图标

As a 刷首页的用户,
I want 场所入口的图标一眼看出是「地点」相关,
So that 我能注意到这个功能。

**Acceptance Criteria:**

**Given** 首页场所入口行 `PlaceEntryRow`
**When** 渲染
**Then** 图标由 `Icons.place_outlined` 换为 UI 稿 C1 中的「折叠地图 + 定位针」SVG（`flutter_svg`），尺寸 20、颜色 `AppColors.mint` 不变；文字、箭头、点击跳转不变（AD-20，UX-DR13）

**Given** 既有测试
**When** 跑 `pet_recommendation_strip_test` 等引用该行的测试
**Then** 全部通过（断言按 key，不依赖图标）

---

## Epic 4: 打卡与性格测试也能解锁里程碑

宠物第一次场所打卡、第一次解锁完整性格解读时，当场弹出庆祝页并点亮新徽章；老用户已做过的自动补上。（FR-126；AD-18 ~ AD-19）

### Story 4.1: 里程碑清单新增两项（双端清单、文案、徽章与存量回填）

As a 宠物主人,
I want 里程碑列表里出现「Check-in pertama」「Kenal kepribadian」两枚新徽章，已做过的自动点亮,
So that 我和宠物在新功能里的「第一次」也被记录。

**Acceptance Criteria:**

**Given** 后端 `MilestoneCatalog`
**When** 本 story 完成
**Then** 猫 / 狗 / 通用三份清单**末尾**（「Lulus Pemula」之后）各追加两条 S 级 `SYSTEM_AUTO`：猫 `C-S17` / `C-S18`、狗 `D-S17` / `D-S18`、通用 `G-S10` / `G-S11`（打卡在前、性格测试在后）；既有 code 与 sortOrder 一个不变；清单数为 33 / 33 / 18（AD-18，FR-126.6）
**And** 中文标题「第一次场所打卡」「解锁性格测试结果」，印尼文标题「Check-in pertama」「Kenal kepribadian」；不纳入新手任务

**Given** App 端
**When** 本 story 完成
**Then** `kMilestoneTitles`（en / id）、`kMilestoneCelebrationCopy`（标题 + 正文，en / id，`{name}` 占位）、`kMilestoneBadgeKeys` 各加 6 个 code；文案按决策日志 D-12（含标题结尾 📍 / 🔮）
**And** 两枚已交付插画（`里程碑-场地打卡.png`、`里程碑-性格测试.png`）转 webp，以新徽章 key 放 App `assets/milestone/` 与后端 `static/milestone/` 两处，文件同名同哈希

**Given** 跨库一致性测试
**When** 运行 `MilestoneCatalogI18nTest`、`MilestoneBadgeMappingTest`（徽章 key 40 → 42）、`MilestoneBadgeAssetSyncTest`、`MilestoneCatalogTest`（33 / 33 / 18）、App `milestone_badge_test`
**Then** 全部按新口径更新并通过（NFR9）

**Given** 回填迁移执行
**When** 查看存量宠物
**Then** ①每只存量宠物按物种补齐两条新 `pet_milestones` 名册行；②有打卡记录的宠物，打卡项写入完成行，`completed_at` = 该宠物首次打卡时间；③有完整解读解锁记录（`tailsonality_results.unlocked_at` 非空）的宠物，性格测试项写入完成行，`completed_at` = 最早 `unlocked_at`；④两类回填 `celebrated_at = completed_at`（PRD 存量回填）
**And** 只单独买过配型（`match_unlocked_at` 非空、`unlocked_at` 为空）的宠物**不**回填性格测试项
**And** 回填直接写表，不经完成服务：不发里程碑通知、不报完成埋点、不触发补庆祝

**Given** 新建宠物档案
**When** 生成名册
**Then** 包含两条新项（33 / 33 / 18）

**Given** 里程碑列表页
**When** 未达成 / 已达成
**Then** S 组末尾显示两枚新徽章：未达成为全局锁定图，已达成为新插画；点已达成徽章重看庆祝页（既有行为，UX-DR12）；H5 分享页印尼文标题随 App 传入（既有链路）

### Story 4.2: 打卡成功、解锁完整解读时自动达成

As a 宠物主人,
I want 打卡成功或解锁完整性格解读后，对应成就自动完成,
So that 我不用手动打卡这两项。

**Acceptance Criteria:**

**Given** 用户对某场所打卡成功，本次选中的宠物为 `petIds`
**When** 打卡事务提交
**Then** `PlaceCheckedInEvent` 携带 `petIds`；里程碑监听为**每只宠物**完成其物种对应的打卡项（`PLACE_CHECKED_IN` 事件，经 `MilestoneAutoCompleteMap` 映射）（FR-126.2，AD-18）
**And** 已完成的再次打卡不重复达成（幂等）

**Given** 某宠物的 Tailsonality **完整解读**解锁成功（`KeepsakeUnlockedEvent.sku == TAILSONALITY`；QRIS 到账、PawCoin 当场成交、配型后补差价升级都走这一 SKU）
**When** 事件提交后
**Then** 该结果所属宠物（`tailsonality_results.pet_profile_id`）完成性格测试项（`TAILSONALITY_UNLOCKED` 事件）（FR-126.3）

**Given** 只单独购买配型（`sku == TS_MATCH`）
**When** 解锁成功
**Then** 不达成性格测试项（PRD 2026-10-12）

**Given** 宠物已达成性格测试项
**When** 重测并再次解锁新结果
**Then** 不重复达成

**Given** 新完成的两项
**When** 完成
**Then** 与既有自动里程碑一致：写 `milestone_completions`、发 `MilestoneCompletedEvent`（→ 里程碑通知 + 埋点，code 自动区分，PRD §4）

**Given** 只答完题未解锁
**When** 提交结果
**Then** 不达成

### Story 4.3: 达成当场弹出庆祝页

As a 宠物主人,
I want 打卡成功、解锁完整解读的那一刻就看到庆祝页,
So that 成就感在当下就出现，而不是下次进里程碑页才补弹。

**Acceptance Criteria:**

**Given** 打卡成功、进入打卡成功页
**When** 后端已为该宠物完成打卡项且尚未庆祝
**Then** 庆祝页（`showMilestoneCelebration`，`path: instant`）盖在打卡成功页上弹出，徽章为新插画，标题 / 正文为 D-12 文案；关闭后回到打卡成功页，其按钮照常可用（FR-126.4，UX-DR11）
**And** 弹出后上报已庆祝（`celebrations` 接口），里程碑列表不再补弹

**Given** 在结果页解锁完整解读成功，或在配型页通过「买完整解读」链接成功
**When** 后端已完成性格测试项且尚未庆祝
**Then** 庆祝页盖在当前页上弹出，关闭回到原页

**Given** 后端达成是异步的
**When** 成功后查询达成状态
**Then** 按健康页既有范式短轮询（约 500 / 800 / 1200 ms）；轮询结束仍未完成或已庆祝过 → 不弹，交既有补庆祝机制兜底（AD-19）

**Given** 只单独买配型成功
**When** 回到配型页
**Then** 不弹庆祝页

**Given** 该宠物此前已达成（如回填过、或重测再解锁）
**When** 再次打卡 / 再次解锁
**Then** 不弹

---

## Epic 5: 运营弹窗——打开 App 就能看到活动

运营配置的活动弹窗在用户冷启动进入 Diary / Social 时弹出，按人群、频次、优先级只弹一个，不和功能弹窗抢；点击可跳商品、品类、5 个 App 页面或外部链接。（FR-137；AD-12 ~ AD-16）

### Story 5.1: 运营弹窗数据与 App 读取接口

As a 运营,
I want 配置好的弹窗由服务端按人群、时间、频次筛好再下发给 App,
So that 每个用户只收到该看、还没看够次数的弹窗。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看库表
**Then** 存在 `ops_popups`（`public_token`、`name`、`status` ACTIVE / DISABLED、`starts_at` / `ends_at`、`priority`、`audience` ALL / NEW / OLD、`new_user_days` 默认 7、`frequency` ONCE / DAILY、`image_url_id` / `image_url_en`（en 可空）、`title_id/en`、`body_id/en`（可空）、`button_text_id/en`、`target_type` NONE / PRODUCT / CATEGORY / APP_PAGE / EXTERNAL、`target_value VARCHAR(512)`、时间戳）与 `ops_popup_impressions(popup_id, user_id, shown_at)`（AD-14）

**Given** 多个弹窗
**When** 已登录用户请求 `GET /api/v1/ops-popups/candidates`
**Then** 只返回：status = ACTIVE、当前在 `[starts_at, ends_at)` 内、人群匹配（NEW = 注册 ≤ `new_user_days` 天，OLD = 超过）、跳转目标有效（PRODUCT 须商品 `is_active`）、频次未满（ONCE 从未展示过；DAILY 当前 WIB 自然日未展示过）的弹窗
**And** 按 `priority DESC, created_at DESC` 排序返回列表；每项含 token、按两种语言的文案与图片、按钮文案、跳转目标、频次类型（FR-137.5 / 137.7 / 137.10）

**Given** 游客（无 JWT）
**When** 请求候选接口
**Then** 只返回 `audience = ALL` 的弹窗，服务端不做频次过滤（游客频次在设备本地，AD-15）

**Given** 弹窗已停用、已过结束时间、或商品已下架
**When** 请求候选接口
**Then** 该弹窗不在列表中（PRD AC8）

**Given** 登录用户的弹窗真正显示后
**When** App 调用 `POST /api/v1/ops-popups/{token}/impressions`
**Then** 记录一条展示；ONCE 弹窗此后不再下发给该用户，DAILY 当天 WIB 内不再下发
**And** 弹窗被后台编辑后频次记录不重置（频次挂在弹窗 id 上）

**Given** 本 story
**When** 交付
**Then** 以迁移或测试夹具预置数据完成集成测试；配置写入由 admin 主题 AB-30 承接（AD-1）

### Story 5.2: 统一跳转目标（运营弹窗与运营通知共用）

As a 用户,
I want 点运营弹窗或运营通知后，准确跳到运营配置的页面,
So that 我能直接看到活动内容。

**Acceptance Criteria:**

**Given** `OpsTarget` 与 `openOpsTarget(context, ref, target)`
**When** 目标为 PRODUCT / CATEGORY
**Then** 分别进入 `/shop/products/{token}` 与 `/shop?category={枚举}`（Tab 根用 `go`）（AD-16）

**Given** 目标为 APP_PAGE
**When** 值为 `HOME` / `PUBLISH` / `TOKO` / `KNOW_YOUR_PET` / `HEALTH`
**Then** 分别进入 `/home` / 发帖弹层（`PublishComposePage.open`）/ `/shop` / `/profile/pet-insights` / `/profile/health`；只接受这 5 个值，其他值视为无效、不跳转（PRD 补充细则）

**Given** 游客点需要登录的页面（发帖、Know Your Pet、健康）
**When** 触发跳转
**Then** 先走登录（`requireLogin` + `RouteIntent`），登录成功后继续进入目标页；不直接跳（路由守卫会把游客打回首页）

**Given** 目标为 EXTERNAL
**When** 触发跳转
**Then** 用 `url_launcher` 的 App 内浏览器模式打开（iOS SFSafariViewController / Android Chrome Custom Tabs），**系统默认配色**，关闭回到原页；设备不支持时退回系统浏览器（FR-137.3，决策日志 D-9）

**Given** 目标为 NONE 或目标值无效
**When** 触发
**Then** 不跳转、不报错

**Given** 单测
**When** 覆盖全部类型
**Then** 每种类型的目标路由 / 打开方式断言通过；不新增任何 Flutter 依赖，不扩展 `tailtopia://` 外部深链（NFR10）

### Story 5.3: 记录本次冷启动出现过的功能弹窗

As a 用户,
I want 打开 App 时不会同时被功能弹窗和活动弹窗轮番打扰,
So that 系统授权、新功能引导这些更重要的弹窗不被挤掉。

**Acceptance Criteria:**

**Given** 进程内的「本次冷启动功能弹窗记录」（AD-12）
**When** 本次冷启动中以下任一弹窗**真正出现**
**Then** 记录为「已出现功能弹窗」：iOS ATT 授权框（请求前状态为未决定且确实弹出，决策日志 D-6）、系统推送权限申请、新功能引导蒙层（`CoachmarkOverlay` 插入时）、里程碑庆祝页（`showMilestoneCelebration` 入口）、强制更新弹窗（Epic 3 已交付时一并登记）

**Given** ATT 状态已决定（老用户）或推送权限已问过
**When** 冷启动
**Then** 这两项不登记（没有真正弹出）

**Given** 推荐更新横幅显示
**When** 冷启动
**Then** 不登记（横幅不算弹窗，PRD）

**Given** 本 story
**When** 交付
**Then** 只增加登记、不改变任何既有弹窗的触发时机与顺序；既有相关测试全部通过

### Story 5.4: 冷启动进入 Diary / Social 时弹出运营弹窗

As a 用户,
I want 打开 App 时看到运营的活动弹窗（每次最多一个、不重复打扰）,
So that 我不会错过活动。

**Acceptance Criteria:**

**Given** 冷启动落在 Diary 或 Social（Tab 根）
**When** 启动链结束、该页首次可见
**Then** 判定一次运营弹窗：本次冷启动未出现过任何功能弹窗（Story 5.3）且本次尚未弹过运营弹窗 → 取候选列表，剔除本地频次已满的，取第一个，**图片加载成功后**弹出（FR-137.4 / 137.6 / 137.9，AD-13）

**Given** 冷启动由推送 / 外部链接打开、落在其他页面
**When** 本次运行内用户之后第一次进入 Diary 或 Social
**Then** 再按同样条件判定（决策日志 D-11）

**Given** 本次冷启动出现过 ATT、推送权限、新功能蒙层、里程碑庆祝或强制更新任一弹窗
**When** 进入落地页
**Then** 运营弹窗本次不出现，下次冷启动再判定（PRD AC2）

**Given** 弹窗图片加载失败
**When** 判定
**Then** 本次不弹、不计频次（PRD AC7）

**Given** 弹窗显示
**When** 渲染
**Then** 居中、圆角 24、柔和投影；4:5 图片 1px 8% 黑色内描边；标题 + 正文（可选）+ 一个按钮；✕ 压在图片右上角、热区 44；背后压暗；无标题正文时图片即主体（UX-DR8，FR-137.1）
**And** 按 App 当前语言取 EN / ID 文案与图片；EN 图片为空时用 ID 图（PRD AC6）

**Given** 弹窗显示
**When** 点图片或按钮
**Then** 按配置经 `openOpsTarget` 跳转（Story 5.2）并关闭弹窗；目标为 NONE 时点按钮 = 关闭，点图片无反应（PRD AC5）
**When** 点 ✕
**Then** 关闭

**Given** 弹窗真正显示后
**When** 记录频次
**Then** 本地记录（`AppPrefs`，游客与登录用户都写）：ONCE 记为已弹，DAILY 记 WIB 日期（UTC+7 计算，不用手机时区）；登录用户另调曝光上报接口（AD-15）
**And** 游客期间弹过的 ONCE 弹窗，登录后因本地记录剔除，不再弹（PRD AC4）；DAILY 过 WIB 午夜可再弹（PRD AC3）

**Given** 同一次冷启动
**When** 已弹过一个运营弹窗
**Then** 不再弹第二个（PRD AC1）

**Given** 候选接口异常或超时
**When** 判定
**Then** 不弹、不影响启动（NFR5）

**Given** 弹窗曝光 / 点击 / 关闭
**When** 上报埋点
**Then** `ops_popup_shown` / `ops_popup_tapped` / `ops_popup_closed`，属性 `popup_id`（FR-137.11）

---

## Epic 6: 运营系统通知——运营能给用户发消息

运营选定的用户在通知中心收到运营写的消息（按账号语言），同时收到推送；点开按配置跳转。发送核心在本 epic，发送页面（AB-31）在 admin 主题。（FR-138；AD-17）

### Story 6.1: 运营通知类型与活动数据

As a 运营,
I want 系统里有「运营通知」这一类型、能存下一次发送活动的内容与跳转,
So that 我写的标题、正文和跳转能原样送到用户手上。

**Acceptance Criteria:**

**Given** 迁移执行后
**When** 查看 `ck_notifications_type`
**Then** 按 `V20260915_1614` 写法 DROP + ADD 全量重建，取值 = `NotificationType.java` 现有 31 值 + `OPS_ANNOUNCEMENT` 共 32 值；开工当天对全部远端分支做并集核查并在迁移注释记录（NFR11，FR-138.7）

**Given** 迁移执行后
**When** 查看库表
**Then** 存在 `ops_notification_campaigns`（`public_token`、`title_id/en`（≤120）、`body_id/en`（≤255）、`target_type` / `target_value`（同运营弹窗，≤512）、`audience_spec JSONB`、`scheduled_at`（可空 = 立即）、`status` PENDING / SENDING / DONE / PARTIAL_FAILED / CANCELED、`recipient_count`、`failed_count`、`created_by`、时间戳）与指定用户子表 `ops_notification_campaign_targets(campaign_id, user_id)`（AD-17）

**Given** 某活动、某收件人（`users.locale = 'en'`）
**When** 为其写入通知
**Then** 新增一行 `notifications`：`type = OPS_ANNOUNCEMENT`、`title` / `body` 为 EN 版本、`deep_link_type = OPS_ANNOUNCEMENT`、`target_ref` = 活动 `public_token`；`locale` 非 `en` 的收件人写 ID 版本（FR-138.2）

**Given** App 拉通知列表
**When** 列表含运营通知
**Then** `NotificationItem` 对该类型附带 `opsTarget {type, value}`（取自活动），其他类型不变

### Story 6.2: 运营通知发送核心（人群、分批推送、重试、定时）

As a 运营,
I want 发送后系统自动筛掉无效账号、分批推送并在失败时重试，定时的到点自动发,
So that 大批量发送不会因限流整批失败，发送结果可信。

**Acceptance Criteria:**

**Given** 活动 `audience_spec` 为「全部 / 条件筛选 / 指定用户」
**When** `OpsNotificationDispatcher` 解析人群
**Then** 只包含 `role = USER`、`account_type = REAL`、未注销、`enabled`、`status = ACTIVE` 的用户；封号、注销、虚拟号一律排除（FR-138.5，PRD AC5）
**And** 条件筛选为「且」（注册时间、最近活跃时间、是否建档、宠物物种、是否下过电商订单、是否填写手机号；后台 PRD AB-31）；指定用户中无效 ID 跳过并计数

**Given** 立即发送的活动
**When** 开始发送
**Then** 状态 PENDING → SENDING（条件更新，防重复执行）；按批（如 500 人）写入通知中心并批量自增未读角标；`recipient_count` = 成功写入通知中心的人数

**Given** 写入完成的收件人
**When** 推送
**Then** 按语言分组、经腾讯 IM 批量推送接口分批下发（每批 ≤ 500，批间限速），推送文案直接用该条通知的 title / body；未授权推送的用户只在通知中心（FR-138.1 / 138.6）

**Given** 某推送批次失败
**When** 自动重试 3 次仍失败
**Then** 计入 `failed_count`，全部批次结束后状态为 PARTIAL_FAILED；否则为 DONE；已写入通知中心的不受影响（PRD AC6）

**Given** 定时活动（`scheduled_at` 在未来）
**When** `@Scheduled` 扫描到到期的 PENDING 活动
**Then** 按**实际发送时刻**解析人群并发送；CANCELED 的不发

**Given** 多实例部署
**When** 两个实例同时扫描到同一活动
**Then** 只有一个实例执行（状态条件更新）

**Given** 本 story
**When** 交付
**Then** 以服务方法供 admin 主题的新建 / 取消发送接口调用（AD-1）；异步只用 `@Async` + DB 状态机（NFR10）；以集成测试覆盖全部状态流转

### Story 6.3: 通知中心显示运营通知并按配置跳转

As a 用户,
I want 在通知中心看到运营发来的消息原文，点开去到对应页面,
So that 我能看懂并参与活动。

**Acceptance Criteria:**

**Given** 通知中心列表含运营通知
**When** 渲染
**Then** 与其他系统通知同列、不分栏；左侧 40×40 图标块用紫色（mint / cream2）配色、图标 `campaign_rounded`；标题与正文显示后端下发的 `item.title` / `item.body`（不是本地固定文案）；未读紫点与 `#F8F2FF` 底、已读白底（UX-DR10，PRD AC3）

**Given** 已授权推送的用户收到运营推送
**When** 点系统推送
**Then** 先落到通知中心并把该条标为已读（2026-08-07 既有规则不变）（PRD AC4）

**Given** 通知中心里的运营通知
**When** 点击
**Then** 按 `opsTarget` 经 `openOpsTarget` 跳转（规则同 Story 5.2）；目标为 NONE 时只标已读、不跳转

**Given** 用户从推送或通知中心打开运营通知
**When** 上报埋点
**Then** `ops_notification_opened{campaign_id, source}`，`source` 区分推送 / 通知中心（FR-138.8）

**Given** 既有通知类型
**When** 渲染与点击
**Then** 行为不变（回归）
