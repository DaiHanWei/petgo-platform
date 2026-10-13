# V1.3.4 app 主题决策日志

> 本主题拆 epic / story 过程中的产品决定。**与 PRD / UI 稿冲突时以本文件为准。**
> 功能分支 `feat/1.3.4-app-ux-revamp`（自 `dev_1.3.4` 拉出）。
> 输入文档：`PRD-v1.3.4-app.md`（原名 `1-3-4prd.md`）· `ui-v1.3.4-app.html`（原名 `ui-1-3-4-integrated-v1.html`）· `UI决策记录-app.md`（UI 稿阶段的决策，原 `.decision-log.md`）。
> 后台需求（`v1-3-4后台prd.md` AB-22 / AB-30 / AB-31）**不在本主题**，另开 admin 主题（D-1）。

## 2026-10-12 · 拆 epic 前拍板（操作者 Hex）

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-1 | 本主题范围 | **App + 后端**；后台配置页（AB-22 / AB-30 / AB-31）另开 admin 主题 | — |
| D-2 | 后端在 app / admin 两主题间怎么切 | **按「谁用」切**：数据表、App 调用的接口、发通知等核心逻辑归本主题；后台页面及其保存 / 发送接口归 admin 主题。本主题用迁移预置数据自测 | — |
| D-3 | FR-126 依赖「配型改回付费」（`1b49d51c`） | 该提交已随 PR #42（V1.3.3 → main）进入 main，`dev_1.3.4` 已包含，无需再合并 | — |
| D-4 | FR-126「定价为 0 时的免费解锁也算达成」 | **删掉这句**。解锁价下限 100（V1.3.2 D-3「不做 0 元」），系统内不存在 0 元解锁 | PRD §3.4 测试达成口径 |
| D-5 | 拆 epic 前是否写架构增量 | **先写架构增量**（`architecture-v1.3.4-app-delta.md`），再拆 epic | — |
| D-6 | iOS 系统「允许跟踪」授权框（ATT）算不算功能弹窗 | **算**。当次冷启动弹过 ATT，运营弹窗本次不弹，下次冷启动再弹 | 补 PRD §3.6「与功能弹窗的先后」清单 |
| D-7 | 新增文案怎么定 | 印尼文用 UI 稿提议值；缺的英文、里程碑后端中文标题由 dev agent 拟稿，**操作者审定后**写进 story | — |
| D-8 | 运营弹窗「冷启动进入首页后」的「首页」 | **冷启动后落地的那个页**（Diary 或 Social 都算），不限 Social | 订正 PRD §3.6「出现时机」；UI 稿 D1 画在 Social 上仅为示意 |
| D-9 | App 内浏览器着品牌紫 | **不加依赖，用系统默认配色**。`url_launcher` 的 App 内浏览器模式不支持改色 | 作废 UI 稿 D3 / D3b 与 UI 决策记录 2026-10-09「App 内浏览器按品牌紫着色」 |
| D-10 | 版本配置表初始值 | 商店链接用标准地址：iOS `https://apps.apple.com/app/id6785361196`、Android `https://play.google.com/store/apps/details?id=com.tailtopia.app`；最低支持版本 `1.0.0`；**两端最新版本号列为发版检查项**，上线前按商店实际版本填写 | 后台 PRD §1.2「取生产 env 现值」（生产 env 不可读） |
| D-11 | 冷启动由推送 / 外部链接打开、没落在 Diary / Social | **当时不弹**；本次运行内用户之后进入 Diary 或 Social 时再弹（其余条件不变） | 补 PRD §3.6「出现时机」 |
| D-12 | 新增文案审定 | 架构评审时列出的 14 条新增文案（印尼文按 UI 稿、英文 / 中文由 dev agent 拟）**全部采用**；两条庆祝页标题按既有惯例**结尾加 emoji**：打卡 📍、性格测试 🔮 | UI 稿 F1 / F2 标题无 emoji |
| D-13 | 架构增量技术选择 | `architecture-v1.3.4-app-delta.md` AD-1 ~ AD-20 **全部同意** | — |
| D-14 | 发帖页上传失败的图：⚠ 与 ✕ 叠在同一角（UI 稿 B5 画为失败时无 ✕） | **不在本版处理**，保持现状（两个图标都在）；B5 只示意「失败的图也能点开看大图」。重叠问题记入待办 | UI 稿 B5「此时无 ✕」 |

## 2026-10-12 · 云端执行前预检拍板（操作者 Dai，「全部按推荐」）

> 来源：上云前就绪审查（云端 headless 只能跑 L0、不能问人）。以下项写进对应 story 与 `cloud-rules-app.md`，云端**一律按此执行，不自行发明**。

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-15 | 两条新里程碑的「何时自动点亮」说明文案（`milestone_checkin_prompt_copy.dart`，缺则徽章详情抽屉空白） | 打卡：ID `Otomatis selesai saat {name} pertama kali check-in di sebuah tempat.` / EN `Automatically completed when {name} checks in at a place for the first time.`；性格测试：ID `Otomatis selesai saat hasil lengkap kepribadian {name} terbuka.` / EN `Automatically completed when {name}'s full personality result is unlocked.` | 补 AD-19 / D-12 的 14 条文案之外的 2 条 |
| D-16 | 运营通知 `audience_spec` JSON 结构（与 admin 主题的契约） | `{ "mode": "ALL" \| "FILTER" \| "IDS", "registeredFrom"?: date, "registeredTo"?: date, "activeFrom"?: date, "activeTo"?: date, "hasPet"?: bool, "species"?: ["CAT","DOG","OTHER"], "hasShopOrder"?: bool, "hasPhone"?: bool }`；未出现的键 = 不筛；日期按 WIB 自然日含两端；`IDS` 模式的用户 id 一律存子表（不进 JSON）。「下过电商订单」= **有过已支付的商城订单**；「最近活跃」取 `users.last_active_at`；「填过手机号」= 手机号非空 | 后台 PRD AB-31 只列条件、未给结构（且在仓库外） |
| D-17 | 版本配置保存的错误码（AB-22 §1.1 在仓库外） | 沿用后台既有 `admin.err.config.*` 命名，定 4 个：`admin.err.config.version.format`（非 x.y.z）、`admin.err.config.version.min-gt-latest`（最低支持版本 > 任一端最新版本）、`admin.err.config.store-url.scheme`（非 https）、`admin.err.config.store-url.blank`（为空）。本主题只交付服务层校验与码，四语文案随 admin 主题落 | epics 3.1「给出对应错误码（后台 PRD §1.1）」 |
| D-18 | `app_version_config` 两端最新版本号预置值 | iOS / Android **都预置 `1.3.3`**，迁移注释标「发版检查项」。迁移一旦提交即冻结；日后改值走后台（AB-22）或另起 UPDATE 迁移，**不得改原迁移** | AD-10「App Store 1.3.2 / Google Play 待查」 |
| D-19 | 运营通知推送方式 | 本版**循环调用既有单人 `NotificationPusher.pushOffline` + 限速**，不新接腾讯 IM 批量接口 `/v4/timpush/batch` | AD 中的批量接口 |
| D-20 | 运营通知运行参数 | 每批 500 人；批间隔 1 秒；单批失败重试 3 次，退避 1s / 2s / 4s；定时扫描每 60 秒；SENDING 状态超过 15 分钟无进展 → 置 `PARTIAL_FAILED`，**不自动重发**（防重复推送） | epics 6.2 未给数值 |
| D-21 | 运营弹窗图片加载超时 | 5 秒内未加载完成 → 本次不弹、**不计频次** | PRD §3.6 未给 |
| D-22 | 运营弹窗与 Diary 新功能引导蒙层（`growth_archive_page.dart` 首帧后弹 `CoachmarkOverlay`）同时出现 | 运营弹窗判定在 Diary / Social 首次可见后**延一帧再加约 500ms**；真正 show 之前**再查一次**本次冷启动功能弹窗记录，已有则不弹 | AD-12 ledger 只登记不排队 |
| D-23 | `ops_popups` 列可空性 | `ends_at` 可空（= 长期有效）；`title_id` / `title_en` 可空；`button_text_id` 必填，`button_text_en` 可空（为空回落 ID 文案）；body 与 en 图片可空（同 AD-14） | AD-14 未标的列 |
| D-24 | 埋点里运营弹窗 / 运营通知的标识 | `popup_id` / `campaign_id` 一律取 `public_token`，不外露自增 id | PRD §4 未指定 |
| D-25 | 版本更新埋点的 `decision` 属性 | 三个事件（`prompt_shown` 等）**都带** `decision` | PRD §4 只给 `prompt_shown`；AD-11 为超集，取超集 |

### 同日技术处置（无需拍板，记录备查）
- 两枚新徽章素材：从 UI 稿 F1 / F2 帧内嵌的 512×512 PNG 解出，`cwebp -q 90` 转 `place_checkin.webp` / `tailsonality.webp`，**预置入库**（App 与后端各一份），本版不适用「设计素材不入库」。
- 2.2 不写 golden 截图（Linux / macOS 字体渲染不同），改结构断言；golden 留本地 L2。
- 迁移共 **8 个**（AD §11），非 6 个；`ck_notifications_type` 现行并集 31 值（43 个远端分支核查），+`OPS_ANNOUNCEMENT` = 32 值。
- 后端 `MilestoneBadgeKeys.java` 也需加两条映射（AD-19 漏列）；H5 分享页标题取后端 `titleId`，非「随 App 传入」（订正 epics 4.1）。
| D-26 | `ck_notifications_type` 与 follow 主题（FR-141 `USER_FOLLOWED` / `PLACE_COMMENTED`）分属两个分支 | **两个分支的重建都列全集 34 值**；`NotificationType` 枚举两分支都在末尾**按同一顺序**追加 `OPS_ANNOUNCEMENT`、`USER_FOLLOWED`、`PLACE_COMMENTED`（改动逐字相同，合并不冲突；`MentionNotifyTest`「枚举 = 约束」两边都成立）。同 follow 主题决策日志 D-1 | AD-17 / Story 6.1 原写 32 值 |

## 2026-10-13 · 云端批跑后待确认项拍板（操作者 Dai：「这个分支会同时上线 / 编写错误码 / 按推荐来」）

| # | 议题 | 决定 | 覆盖/订正了什么 |
|---|---|---|---|
| D-27 | 4.1 存量回填与 4.2 自动达成的上线顺序 | 本分支 23 条 story **同一版本一起上线**，不拆分发布；4.1 回填与 4.2 之间不存在漏记窗口 | 4.1 / 4.2 Completion Notes「须同版上线」 |
| D-28 | `admin.err.config.*` 四个版本配置错误码文案（D-17 原定随 admin 主题落） | **本主题提前写入**四份文案包（基线 / en / zh_CN / id），admin 主题直接复用；L0 守卫 `AppVersionConfigMessagesTest` | D-17「四语文案随 admin 主题落」 |
| D-29 | 推荐更新横幅高度（实现 60 vs UI 稿 52） | **按 UI 稿 52**：卡片上下内边距 8 → 4 | Story 3.3 待确认 |
| D-30 | 云端批跑各 story「待确认」中按保守口径实现的其余项 | **一律按已实现的推荐口径保留**：推荐弹窗点「去更新」也记 72h 冷却；`adjusted` = 拖动过即算；推广卡不开窗保持居中；推荐态点遮罩 / 返回不记冷却不报埋点；运营弹窗点遮罩不关、NONE 目标记 `tapped`、EN 空回落 ID；落地页判据加严（`router.state`）；运营通知「推送 + 中心」两条 `ops_notification_opened` 由看板按 `campaign_id` + 用户去重；运营通知标题 / 正文缺失兜底「系统通知」；外链打不开静默；游客归因 `entrySource = ops_target` | 各 story「待确认」 |
| D-31 | 复审记录的「已知残留」 | **另开任务处理，不在本版修**：① `feed_rank_config` 后台保存与改库的丢失更新（`@DynamicUpdate` / `@Version`）；② 运营弹窗空闲检查覆盖不到 Tab 分支 Navigator 上的底部弹层；③ 埋点属性名守卫改为扫描源码；④ 运营通知发送在 IM 不可用时的熔断 / 提前终止 | 各 story 复审记录 |
