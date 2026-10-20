# Story 1.3: App——送礼入口、面板、支付与动效

Status: review

> 版本 v1.4.0 · 主题 gift · 分支 `feat/1.4.0-gift`
> 口径优先级：`decision-log.md` ③ 节 > `送礼/1-4-0送礼prd.md` > `architecture-v1.4.0-gift-delta.md`（AD-1 ~ AD-13、P-1 ~ P-3）> UI 稿 `送礼/ui-1-4-0-gift-integrated-v1.html`（定稿；均在 `_bmad-output/planning-artifacts/v1.4.0/`）。
> 前置：**Story 1.1**（`GET /api/v1/gifts/catalog?petId=|postId=`、`POST /api/v1/pets/{petId}/gifts`、`GET /api/v1/me/gift-orders/{orderToken}`、6 个 ProblemDetail 类型）、**Story 1.2**（后端通知，本 story 不碰通知中心）。
> 范围层：**只 App**（`petgo_app/`）。UI 稿 A1、A3、A4、A6（入口与不显示）、A7（**本分支单宠过渡，不做抽屉**）、B1 ~ B7、C1 ~ C9、D1 ~ D2。
> 🔴 并行约束：② 的多宠接口 / 详情页关联宠物胶囊、① 的等级页路由 `/me/growth` 在本分支**不存在**，不得引用或自造。A7 选宠物抽屉与「锁区块跳等级页」留给对齐 story 4.1；本 story 写过渡行为（见 AC1.4、AC2.6）。
> 🔴 工程纪律：**不整文件 `dart format`**（手工对齐缩进，只跑 `flutter analyze`）；**不写 golden**；不引新依赖（动效用 CustomPainter，**不用 Lottie** —— UI 稿 D1「导出 Lottie」与架构 §0.6「不引新依赖」冲突，按架构）；不改 `kApiBaseUrl`。

## Story

As a 想送礼的用户,
I want 在他人宠物页和 Diary 详情页点「送礼」，挑礼物、数量、渠道后送出,
so that 送礼又快又不出错。

## Acceptance Criteria

**AC1 — 入口与不显示（A1 / A3 / A4 / A6）** `[L0]` `[L2]`（PRD AC1、AD-13）
1. **A1 他人宠物 Diary Tab（访客视图）**：`VisitorArchiveView` 给 `DiaryHeader` 传 `titleAction`：紫色实心胶囊「Kirim Hadiah」（高 34、圆角 999、padding `0 14 0 11`、间距 4、底 `AppColors.mint` #845EC9、白字 12.5 / w700、阴影 `0 4 12 rgba(132,94,201,.28)`、前置 `Icons.redeem` 16 白；点击热区补到 ≥ 44）。显示条件：`scope.isInApp && scope.petId != null` 且 `scope.petId != ref.watch(petProfileProvider).value?.id`（Riverpod 3 无 `valueOrNull`，`.value` 即同义）（自己的宠物不显示；`petProfileProvider` 加载中 / 失败时**不显示**，防自己的宠物闪现）。
2. **A3 他人 Diary 详情**：`detail.type == ContentType.growthMoment.wire && !detail.isAuthor && !detail.authorDeleted` 时，在 `content_detail_page.dart` 打卡场所条之后、分隔线之前（:316 与 :317 之间）插入礼物区 `GiftDetailZone`：浅紫底卡（底 #F8F6FF、圆角 24、padding 10、上间距 12、纵向间距 8）→ 区标题「HADIAH」（11 / w700、字间距 .6、`AppColors.muted`）→ 整宽次级按钮「Kirim Hadiah」（高 44、圆角 14、白底、紫字 14 / w700、1.5px #C2B0EC 描边、前置 `Icons.redeem` 18 紫）。**宠物行（头像 + n hadiah + 礼物缩略）属 Story 2.2**，本 story 不画。
3. **A6 自己的 Diary 详情 / Moment（`DAILY`）/ Tips（`KNOWLEDGE`）**：本 story 不显示礼物区与按钮（A6 的礼物区展示在 2.2 补）。`comment_composer.dart` `_actions()`（:444-481）底栏**不加**送礼按钮（UI 稿把入口放在礼物区，不进点赞 / 分享栏）。
4. **A4 / A7（多宠）本分支过渡**：详情页只按 `GET /gifts/catalog?postId={detail.id}` 返回的 `recipient`（单宠）送，不弹选宠物抽屉；Completion Notes 写「A7 在对齐 story 4.1 接入」。
5. 分享链接进入的访客视图（`/pet/:token`，`scope.petId == null`）本分支**不显示**按钮（UI 稿要求显示，但该入口拿不到 petId），Completion Notes「待确认」记一句。
6. 我拉黑了主人：访客视图本就 403、详情页 404，进不来，无需额外处理；主人拉黑我时详情页照常显示按钮（PRD AC9）。

**AC2 — 送礼面板 B1 ~ B7** `[L0]` `[L2]`（PRD FR-148.2 / .3、AC3、UI 稿 B 泳道）
1. 外壳：`showModalBottomSheet(isScrollControlled: true)`，高度约屏幕 88%，上圆角 24、把手 36×4（#E6E6E6、圆角 2、margin `10 auto 14`）、遮罩 `rgba(20,16,25,.45)`；网格区内部滚动，底部数量 / 合计 / 按钮固定。
2. 标题行（padding `0 20 6`、间距 8）：收礼宠物头像 32（底 #EDE3FF、双环 `0 0 0 2 #fff, 0 0 0 3.5 #C2B0EC`）+ 标题「Kirim hadiah untuk {petName}」18 / w700 + 副标「物种 · 品种」12 muted（物种复用 `petTypeCat / petTypeDog / petTypeOther`，同 `pet_info_card.dart:67-72`）。
3. **网格**：按 `tiers` 价格升序分组；档标题行（两端对齐，margin `14 2 8`、首个 margin-top 6）左「Rp1.000」13 / w700、右档名 11.5 / w500 muted（价格 → 档名映射见 AC7 `giftTier*`，非五档价位只显示价格）；4 列、间距 8。礼物格：圆角 16、padding `8 2 7`、外圈 1px #F0EDF5；图标圆底 46（#F8F6FF）内放 32 图标（`AppImage` 网络图）；名称 11 / w600 单行省略（按 App 语言取 `nameId` / `nameEn`，**不渲染后端显示串以外的拼接**）；价格 10.5 muted 等宽数字。**选中态**：1.5px #845EC9 描边 + 底 #F8F6FF + 阴影 `0 4 12 rgba(132,94,201,.16)` + 图标底变白 + 右上 16 圆紫底白勾（top 5 / right 5）+ 价格变紫 w600（不只靠颜色）。按压 `transform` 0.15s。
4. **默认选中**排序后的第一个（第一档第一个，AC3a 的排序由后端给）。
5. **专属组（S2）**：`exclusiveGroups` 排在所有价格档之后，每组标签「Eksklusif · Lv{N}+」（白字 11 / w700、圆角 999、`Icons.workspace_premium` 12；底色按 `minLevel`：5–9 #0A7FA3、10–14 #2B4FBF、≥ 15 渐变 `135deg #4A2E9E → #B5307F`，定义为 gift 模块私有常量并注释「① 段位色，对齐时改用 ① 的 token」）；专属格 1.5px #E9DDFB 描边、图标底 `160deg #EAF6FA → #F8F6FF`（≥ 15 为 `#F1E8FB → #FBEAF3`）。
6. **锁区块（B1 / B2）**：`lockBlock != null` 时放在网格最后：浅紫渐变卡（`135deg #F3EDFF → #FBF8FF`、圆角 16、padding `12 14`、margin `14 0 4`）+ 白圆锁圈 36（外环 2px 段位色、`Icons.lock` 18 FILL）+ 标题「Lv{N} buka lebih banyak hadiah」13.5 + 小字「Naik level untuk membuka hadiah eksklusif」11.5 ink2 + `chevron_right` 20。**本分支点击**：面板不关，Toast「Terbuka di Lv{N}」（用 `showAppToastOnOverlay` 挂根 Overlay，避免被抽屉盖住）+ 埋点 `gift_locked_block_tapped`；对齐 story 4.1 改为关面板跳 `/me/growth`。`lockBlock == null`（B3）不显示。
7. **数量（B4 / B5）**：胶囊 ×1 / ×5 / ×10（高 36、最小宽 48、圆角 999、1.5px #E6E6E6、12.5 / w600 等宽；选中紫底白字 + 阴影）+ 右侧步进器（容器 #F8F6FF 圆角 999 padding 3；32 白圆钮 + 数字最小宽 24；**热区补到 44**）；默认 ×1；范围 1 ~ 99：1 时「−」置灰、99 时「+」置灰（#B6B6B6、无阴影）；手动调到非 1 / 5 / 10 时三个胶囊都不高亮；换礼物不重置数量。
8. **合计行**：左「图标 22 + {礼物名} × {n}」13 ink2、右「Rp{总价}」18 / w800 等宽；主按钮「Kirim · Rp{总价}」（高 48、圆角 14、紫底白字 14.5 / w700、品牌投影）；下方小字「Hadiah virtual tidak bisa dikembalikan setelah terkirim.」11 muted 居中。底部区 padding `12 16 24`、上方阴影 `0 -6 18 rgba(22,34,51,.06)`。金额一律 `Rp` + `formatIdrAmount`（`pay_channel_picker.dart:11`，点号千分位）。
9. **B6 加载中**：标题行 + 居中 `CircularProgressIndicator`（28、紫）。**B7 失败**：`Icons.cloud_off` 48 muted + 「Gagal memuat hadiah」18 / w600 + 描边按钮「Coba lagi」（`commonRetry`）重拉。目录接口返回 `gift-pet-gone` / `gift-own-pet` / `gift-blocked`（404 / 422 / 403）→ 关面板 + C9 Toast，不进 B7。
10. 打开面板埋点 `gift_panel_opened`；点「Kirim」埋点 `gift_send_tapped` 后**收起面板**再弹选渠道抽屉（S1，不叠层）。

**AC3 — 选渠道 C1 ~ C4：`PayChannelPicker` 扩展（可选参数，不传时行为不变）** `[L0]` `[L2]`（AD-13、PRD AC4、UI 稿 C 泳道）
1. `lib/shared/widgets/pay_channel_picker.dart` 新增可选构造参数：
   - `String? qrisDisabledReason`：非空 → QRIS 行 `enabled: false`（opacity .5、图标 #9690A6、无单选圈）、副标题改为该文案（C2「Minimal Rp10.000」）；
   - `String? qrisDisabledHint`：非空 → QRIS 行下方一行 11.5 muted 提示（C2「Isi PawCoin biar kirim hadiah lebih praktis.」）；
   - `Future<bool> Function(HdPayChannel channel)? onConfirm`：非空 → 点确认时**不立刻 pop**：主按钮内换 18px 白色转圈、不显示文字、按钮与两行渠道禁点（C5）；回调返回 `true` → `Navigator.pop(selected)`；返回 `false` → 恢复按钮、抽屉保留可重试（C8）；
   - `VoidCallback? onTopUp`：非空 → 余额不足时 PawCoin 行的「Isi saldo dulu →」点击改为 `pop()` 后调 `onTopUp()`（替代现状 :112 的 `pop(); context.push('/me/pawcoin/recharge')` 不 await）。
2. 默认选中规则在现状（:65-67：余额够 → PawCoin，否则 QRIS）上加一条：QRIS 被禁用且余额不足 → **无选中**、确认按钮置灰（C4）。
3. **不传任何新参数时**（现有 5 个调用方：`hd_paywall_sheet.dart:31`、`tailsonality_result_page.dart:625`、`tailsonality_match_page.dart:503`、`boarding_pass_unlock_sheet.dart:87`、`passport_snapshot_sheet.dart:89`）**像素与行为不变**；`test/keepsake/keepsake_contract_test.dart` 全绿。
4. 送礼调用：`header` = 礼物摘要卡（`135deg #845EC9 → #6C48AE` 渐变、圆角 16、padding 14、白字；48 白框圆角 14 内放 34 图标；「{礼物名} × {n}」15 / 「untuk {petName}」12 opacity .85）；`title`「Kirim hadiah」；`body`「Hadiah langsung terkirim setelah pembayaran berhasil.」；`price = AsyncValue.data(total)`；`balance` 读 `pawCoinProvider`（失败按 0，照 `keepsake_pay_flow.dart:52`）；`confirmLabel` 复用 `keepsakePayConfirm`（「Bayar Rp{price}」）；`total < catalog.qrisMinAmount` 时传 `qrisDisabledReason` / `qrisDisabledHint`，并上报一次 `gift_qris_low_amount_shown`。
5. **关闭选渠道抽屉（下滑 / 点遮罩）→ 重新打开送礼面板，礼物与数量保留**（S1）。
6. **先充值（C3 / C4，S3）**：`onTopUp` 置标记 → 抽屉关闭后 `await context.push('/me/pawcoin/recharge')` → `ref.invalidate(pawCoinProvider)` → **自动重开选渠道抽屉**（礼物、数量保留）；写法照 `vet_timed_pay_page.dart` `_topUp`（:218-239）。

**AC4 — PawCoin 扣款 C5 / C8 / C9 与 QRIS C6 / C7** `[L0]` `[L2]`（PRD AC4 / AC9、UI 稿 C 泳道、§0.3 第 9 条）
1. 发起：`POST /api/v1/pets/{recipient.petId}/gifts`，body `{giftToken, quantity, channel: channel.wire, requestId, source, postId?}`；`source` = `diary_tab`（访客视图）/ `content_detail`（详情页）（`gift_page` 由 2.2 的礼物页传）。
2. **`requestId`**：每次在面板点「Kirim」后、针对**某一渠道**第一次确认时生成（`Random.secure()` 16 字节 hex，不引 uuid 依赖）；同一渠道失败重试（C8）**复用**；换渠道或重新点「Kirim」→ 新生成（否则先开过 QRIS 再改 PawCoin 会被后端按幂等返回那张 QRIS 单）。
3. **PawCoin**（`onConfirm` 里完成）：成功 `delivered = true` → 返回 true（抽屉关）→ 播放 D1 → Toast D2 → `ref.invalidate(pawCoinProvider)` → 埋点 `gift_payment_succeeded`（`channel: PAWCOIN`）。
4. **错误映射**（`ProblemDetail.fromDioException(e)?.typeSlug`，`lib/core/network/problem_detail.dart:26 / :59`）：

   | typeSlug | 行为 | 文案 |
   |---|---|---|
   | `pawcoin-insufficient`（409） | 抽屉保留、按钮恢复、刷新余额 | `idCardHdInsufficientBalance` |
   | `gift-pet-gone` / `gift-own-pet` / `gift-blocked` | 关抽屉、结束流程（C9，三种同一句，不暴露拉黑） | `giftCannotSend` |
   | `gift-locked` | 关抽屉、结束流程 | `giftLockedError` |
   | `gift-unavailable` | 关抽屉、Toast 后重开面板并重拉目录 | `giftUnavailable` |
   | 其它（网络 / 5xx / `rate-limited` / `gift-qris-below-min`） | 抽屉保留可重试（C8） | `idCardHdPurchaseError` |

5. **QRIS**：返回 `delivered = false` + `payload` → `onConfirm` 返回 true 关抽屉 → `showQrPaymentSheet(context, payload:, orderRef: payment.displayNo, pollPaid:, onAborted:)`（`qr_payment_sheet.dart:36-42`，C6 沿用现状：标题 / 二维码 240 / 订单号 / 红描边「Batalkan Pembayaran」，无倒计时）。`payload` 为空 → Toast `idCardHdPurchaseError`。
   - `pollPaid` = `GET /api/v1/me/gift-orders/{orderToken}`：`DELIVERED` → `true`；`EXPIRED` → `throw QrPaymentAborted('EXPIRED')`；`FAILED` → `throw QrPaymentAborted('FAILED')`；`UNDELIVERED_REFUND_PENDING` / `UNDELIVERED_REFUNDED` / `REFUND_MANUAL` → `throw QrPaymentAborted('UNDELIVERED')`；`PENDING` → `false`；请求异常 → 普通异常（组件忽略、下次再轮询，`qr_payment_sheet.dart:108`）。
   - `onAborted`（C7，S5）：`EXPIRED` → Toast `giftPayExpired`「Pembayaran kedaluwarsa. Coba lagi.」；`FAILED` → `idCardHdPurchaseError`「Pembayaran gagal. Coba lagi.」；`UNDELIVERED` → `giftUndelivered`。用户自己点「Batalkan」不提示（现状）。Toast 一律 `showAppToastOnOverlay`（`app_toast.dart:46`，await 后 context 可能已失效）。
   - 返回 `true` → D1 → D2 → 埋点 `gift_payment_succeeded`（`channel: QRIS`）+ `Analytics.capturePurchase(amountIdr: total, purpose: 'GIFT', orderRef: payment.displayNo)`（照 `keepsake_pay_flow.dart:87-90`，现金付款统一回传）。
   - 用户离开 / 关 App 后付款：后端照常交付（1.1），App 无需处理。
6. 整个流程封装为 `lib/features/gift/presentation/gift_send_flow.dart` `Future<void> runGiftSendFlow({required BuildContext context, required WidgetRef ref, required GiftRecipientRef recipient, required String source, GlobalKey? flightTargetKey})`（`GiftRecipientRef` = `petId` 或 `postId` 二选一）；状态机：面板 → 选渠道 →（充值 → 选渠道）→（关闭 → 面板）→ 成功 / 结束；不在任何时刻叠两层抽屉。

**AC5 — 送出动效 D1 与 Toast D2** `[L0]` `[L2]`（PRD 送礼流程 5、UI 稿 D 泳道、§0.6）
1. `lib/features/gift/presentation/gift_send_animation.dart`：`OverlayEntry` + `AnimationController(1600ms)`，礼物图标用 `Transform` 驱动 `AppImage`（复用面板已加载的同一 URL），粒子用 **CustomPainter**（参照 `milestone_celebration.dart` `_Confetti` :439-553 的 `AnimatedBuilder + CustomPaint` 写法，但**一次性播放、不 repeat**）：
   - 0–200ms：图标在屏幕底部中央出现，缩放 0.6 → 1；
   - 200–900ms：沿二次贝塞尔弧线飞向目标（顶点高于起终点连线 80），缓动 `Cubic(.32, .72, 0, 1)`；
   - 900–1300ms：落点 8 颗粒子（#845EC9 / #C2B0EC / #F6A609 三色）扩散半径 36、400ms 淡出；`quantity ≥ 2` 时目标旁「×N」弹性缩放 1 → 1.25 → 1；
   - 1300–1600ms：图标淡出、移除 Overlay，然后 Toast。
   - 目标：访客视图 = 宠物信息卡头像（`PetInfoCard` / `DiaryHeader` 新增可选 `GlobalKey? avatarKey` 透传，默认 null 行为不变）；详情页 = `GiftDetailZone` 的 key；取不到目标 → 屏幕水平中线、高 30% 处。
2. **减少动态效果**：`MediaQuery.maybeDisableAnimationsOf(context) ?? false`（先例 `place_stamp_ceremony_page.dart:106`、`stamp_ceremony.dart:142`）为 true → 不飞行、不出粒子、直接 Toast。
3. D2 Toast：`showAppToast`（`app_toast.dart:35`，默认时长）「{giftName} ×{n} terkirim ke {petName}」（数量 1 省略「×1」）——**按拍板 P-4 用礼物名、不用 emoji**（取代 PRD「已送给 Mochi 🎁」；T0 核对改）。
4. 统计格「Hadiah +N」数字滚动与详情页礼物区「n hadiah」更新属 Story 2.2；本 story 成功后只 `ref.invalidate(visitorStatsProvider(scope))`（访客视图）以便 2.2 接上。
5. 同屏只此一处动效；不做全屏特效。

**AC6 — 埋点（守卫测试登记，按仓库规则改名）** `[L0]`（PRD §4、FR-AN）
1. 事件（字面量写在 `Analytics.capture('…')` 里，`test/analytics/v112_events_test.dart:146-166` 的正则要能提取）：

   | PRD 口径名 | 代码实际名 | 属性 |
   |---|---|---|
   | `gift_panel_opened` | `gift_panel_opened` | `source`、`pet_id`、`is_multi_pet_post`（本分支恒 false） |
   | `gift_send_tapped` | `gift_send_tapped` | `gift_id`（= giftToken）、`quantity`、`total_amount`、`is_exclusive` |
   | `gift_paid` | **`gift_payment_succeeded`** | `gift_id`、`quantity`、`total_amount`、`channel`（`PAWCOIN` / `QRIS`） |
   | `gift_qris_blocked_low_amount` | **`gift_qris_low_amount_shown`** | `total_amount` |
   | `gift_locked_block_tapped` | `gift_locked_block_tapped` | `required_level` |

   改名理由：守卫要求动作在词尾且属 `allowedSuffixes`（:346-379），`_paid` / `_amount` 不在表内；**改名迁就规则，不放宽规则**（`v140_events_inventory_test.dart` 头注释同口径）。
2. `v112_events_test.dart` `allowedPrefixes`（:217-333）加 `'gift_'`，写注释「V1.4.0 ③ 送礼，新模块入表，名字由 PRD §4 定、按词尾规则改名两条」。不加新后缀、不删守卫。
3. 新建 `test/analytics/v140_gift_events_test.dart`：口径名 → 实际名映射表逐条断言源码里存在（写法照 `v140_events_inventory_test.dart:53` 的清单对账；**该文件是 Toko 线的，别往里加**）；属性键全部通过既有 NFR-5 体检（:75，禁 `name / nickname / avatar / token_value` 等）。
4. 不进 `appsflyerEvents`（`analytics.dart:137`）；不新增 `ButtonId`。
5. `gift_wall_opened` / `gift_sender_list_opened` 属 2.2。

**AC7 — 文案（arb EN / ID 两份都加；子前缀 `gift*`）** `[L0]`
已 grep 确认 `app_en.arb` / `app_id.arb` **无任何 `gift` 开头的键**（大小写不敏感 0 命中），下列新键不重名。ID 取 UI 稿原文；**UI 稿只有印尼语，EN 为提议值**，Completion Notes 标「待本地团队审」。带参数的键要写 `@` 元数据（`placeholders`）。

| 键 | id | en |
|---|---|---|
| `giftSendButton` | Kirim Hadiah | Send Gift |
| `giftZoneTitle` | Hadiah（代码里 `toUpperCase()`，同 `detailCommentsTitle` 用法） | Gifts |
| `giftPanelTitle(petName)` | Kirim hadiah untuk {petName} | Send a gift to {petName} |
| `giftTierSnack` | Camilan | Snacks |
| `giftTierTreat` | Makan enak | Treats |
| `giftTierToy` | Mainan | Toys |
| `giftTierStyle` | Gaya | Style |
| `giftTierBig` | Hadiah besar | Big gifts |
| `giftExclusiveGroup(level)` | Eksklusif · Lv{level}+ | Exclusive · Lv{level}+ |
| `giftLockTitle(level)` | Lv{level} buka lebih banyak hadiah | Unlock more gifts at Lv{level} |
| `giftLockSubtitle` | Naik level untuk membuka hadiah eksklusif | Level up to unlock exclusive gifts |
| `giftLockToast(level)` | Terbuka di Lv{level} | Unlocks at Lv{level} |
| `giftQuantityChip(count)` | ×{count} | ×{count} |
| `giftSummaryLine(giftName, count)` | {giftName} × {count} | {giftName} × {count} |
| `giftSendConfirm(amount)` | Kirim · Rp{amount} | Send · Rp{amount} |
| `giftNoRefundNote` | Hadiah virtual tidak bisa dikembalikan setelah terkirim. | Virtual gifts can't be refunded once sent. |
| `giftLoadFailed` | Gagal memuat hadiah | Couldn't load gifts |
| `giftPayTitle` | Kirim hadiah | Send gift |
| `giftPayBody` | Hadiah langsung terkirim setelah pembayaran berhasil. | Your gift is sent as soon as the payment succeeds. |
| `giftPayFor(petName)` | untuk {petName} | for {petName} |
| `giftQrisMinimum(amount)` | Minimal Rp{amount} | Minimum Rp{amount} |
| `giftQrisMinimumHint` | Isi PawCoin biar kirim hadiah lebih praktis. | Top up PawCoin to send gifts more easily. |
| `giftPayExpired` | Pembayaran kedaluwarsa. Coba lagi. | Payment expired. Please try again. |
| `giftCannotSend` | Hadiah tidak bisa dikirim. Hewan ini sudah tidak ada. | This gift can't be sent. This pet is no longer available. |
| `giftLockedError` | Hadiah ini belum terbuka untuk levelmu. | This gift isn't unlocked for your level yet. |
| `giftUnavailable` | Hadiah ini sudah tidak tersedia. | This gift is no longer available. |
| `giftUndelivered` | Hadiah tidak terkirim. PawCoin akan dikembalikan ke saldomu. | Gift not delivered. Your PawCoin will be returned to your balance. |
| `giftSentToast(giftName, count, petName)` | `{count, plural, =1{{giftName} terkirim ke {petName}} other{{giftName} ×{count} terkirim ke {petName}}}` | `{count, plural, =1{{giftName} sent to {petName}} other{{giftName} ×{count} sent to {petName}}}` |

- 复用既有键（不新建）：`keepsakePayConfirm`（Bayar Rp{price}）、`idCardHdPawcoinSub`（Saldo {balance} · Potong {cost} koin）、`triageUnlockTopupFirst`（Isi saldo dulu →）、`idCardHdPurchaseError`、`idCardHdInsufficientBalance`、`commonRetry`、`petTypeCat / petTypeDog / petTypeOther`。
- `giftPayExpired` 与 ② 新增的同句文案在对齐时可合并，本分支先用自己的键（② 的键在本分支不存在）。
- `giftLockedError` / `giftUnavailable` / `giftUndelivered` 是 UI 稿未画的错误分支补的提议值。

**AC8 — 数据层** `[L0]`
1. `lib/core/network/api_paths.dart` 加 `giftCatalog`（`$base/gifts/catalog`）、`petGifts(int petId)`、`meGiftOrder(String token)`。
2. `lib/features/gift/data/gift_repository.dart`：`fetchCatalog({int? petId, int? postId})`、`send(...)`、`orderStatus(token)`；provider 照 `keepsake_repository.dart:20-25`（`Provider` + `FutureProvider.autoDispose`；目录按收礼方取用 `.family`，并照 `boarding_pass_repository.dart:38-49` 加 `retry: (_, _) => null`）。
3. `lib/features/gift/domain/`：`GiftCatalog`（`recipient`、`qrisMinAmount`、`tiers`、`exclusiveGroups`、`lockBlock`）、`GiftItem`（`nameFor(Locale)`：`id` → `nameId`，其余 → `nameEn`）、`GiftSendResult`（字段与 `KeepsakePurchaseResult` 同名：`delivered`、`paymentToken`、`paymentDisplayNo`、`payload`、`orderToken`、`status`）、`GiftOrderStatus` 枚举（未知值 → `unknown`，按 PENDING 处理）。
4. 只用 `fromJson`，缺字段不崩；金额 `int`。

**AC9 — 回归与验收** `[L0]` `[L2]`
1. `flutter analyze` 0 issue；`flutter test` 下列新测试 + 既有 `test/keepsake/keepsake_contract_test.dart`、`test/shared/qr_payment_sheet_test.dart`、`test/analytics/*`、访客视图 / 详情页既有测试全绿。
2. Android 模拟器（本地 L2）演示 B1（Lv1 锁区块）、C2（总价 Rp5.000 QRIS 置灰）、D1（PawCoin 送达动效 + Toast），连 stag 后端；云端只到 L0。

## Tasks / Subtasks

- [x] **T0 开工核对**：对照 1.1 实际提交的接口 / 字段 / 错误类型核对本文件；有出入先改本文件（Completion Notes 记一句）
- [x] **T1 数据层**（AC8）
- [x] **T2 `PayChannelPicker` 扩展**（AC3.1 ~ 3.3）+ 新参数单测；既有调用方零改动
- [x] **T3 送礼面板** `gift_panel_sheet.dart`（AC2）
- [x] **T4 流程** `gift_send_flow.dart`（AC3.4 ~ 3.6、AC4）
- [x] **T5 动效** `gift_send_animation.dart` + `PetInfoCard` / `DiaryHeader` 可选 `avatarKey`（AC5）
- [x] **T6 入口**：`visitor_archive_view.dart` titleAction、`content_detail_page.dart` `GiftDetailZone`（AC1）
- [x] **T7 埋点**登记与清单测试（AC6）；**T8 arb**（AC7，`flutter gen-l10n` 由构建生成）
- [x] **T9** `flutter analyze` + 相关测试；Completion Notes 标「L2 待本地验收（模拟器 B1 / C2 / D1）」与「待确认」两条

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 688f9bd5；路径相对 `petgo_app/`）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `lib/shared/widgets/pay_channel_picker.dart` | `formatIdrAmount`（:11）；`class PayChannelPicker`（:26），构造参数 `header / title / body / price(AsyncValue<int>) / onRetryPrice / balance / confirmLabel / confirmKey / retryKey`（:27-38）；默认选中 `enoughCoin ? pawcoin : qris`（:65-67）；PawCoin 行余额不足置灰 + `triageUnlockTopupFirst`，点击 `Navigator.pop(); context.push('/me/pawcoin/recharge')` **不 await**（:104-114，pop 在 :112）；QRIS 行无禁用能力（:117-123）；确认 `pop(selected)`（:127），返回 `HdPayChannel`，下滑关闭 = null | 加 4 个可选参数 + 「无选中」规则 | 不传参时像素 / 行为 / key 不变 |
| `lib/features/profile/domain/id_card.dart` | `enum HdPayChannel { qris('QRIS'), pawcoin('PAWCOIN') }`（:293），`.wire` | 复用 | — |
| `lib/shared/widgets/qr_payment_sheet.dart` | `Future<bool> showQrPaymentSheet(context, {payload, pollPaid, orderRef, onAborted})`（:36-42），返回值不得改枚举（:29-31）；3 秒轮询（:78）；`pollPaid` 抛 `QrPaymentAborted(category)`（:14-24）才触发 `onAborted` 并 `pop(false)`（:100-107）；普通异常忽略（:108）；`_cancel` 不触发 onAborted（:115） | 不改 | 唯一 onAborted 先例 `shop_order_detail_page_v2.dart:803-810` |
| `lib/features/consult/presentation/vet_timed_pay_page.dart` | `_topUp`（:218-239）：`await context.push('/me/pawcoin/recharge')` → `ref.invalidate(pawCoinProvider)` → 续原流程 | 不改；送礼照此写 | — |
| `lib/features/keepsake/presentation/keepsake_pay_flow.dart` | `runKeepsakePurchase`（:39-48）：读余额失败按 0（:52）→ 弹 picker（:57-65）→ `start` → `unlocked` 失效余额（:76-80）→ QRIS `showQrPaymentSheet` 不传 onAborted（:86）→ `capturePurchase`（:87-90）；错误按 `typeSlug` 分流（:94-106），toast 用 SnackBar | 不改；送礼另写 `gift_send_flow.dart`（多了面板往返、扣款中保持打开、充值回流） | — |
| `lib/features/profile/presentation/visitor_archive_view.dart` | `VisitorArchiveView`（:40）；`DiaryHeader(readOnly: true, …)`（:105-115），**不传 `titleAction`**（:109 注释）；路由常量 `/pets/:petId`（:61 / :64，注册在 `core/router/app_router.dart:1083-1089`）；petId 只在 `widget.scope.petId`（`archive_scope.dart:38`，`inAppVisitor` :32，`isInApp` :47），分享链接 `/pet/:token` 时为 null；本页**不判断是不是自己的宠物**（入口 `public_profile_page.dart:771`、`recommended_pet_card.dart:243` 只 `requireLogin`） | 传 `titleAction`（送礼胶囊）+ 自己宠物判断 + `avatarKey` | 6 个模块与顺序不变 |
| `lib/features/profile/domain/visitor_profile.dart` | 字段 name / petType / avatarUrl / breed / sex / birthday / intro / ownerNickname（:11），**无 petId / ownerId** | 不改 | — |
| `lib/features/profile/data/profile_repository.dart` | `petProfileProvider`（:143，当前用户自己的宠物，`PetProfile.id` :71） | 只读 | — |
| `lib/features/profile/presentation/widgets/diary_header.dart` | `titleAction`（:40 / :88），标题行 `if (titleAction != null) …`（:114-115）；内嵌 `PetInfoCard(`（:132） | 加可选 `avatarKey` 透传 | 默认 null 行为不变 |
| `lib/features/profile/presentation/widgets/pet_info_card.dart` | 头像 `_avatar()`（:44），物种映射（:67-72），三格统计 Consultations 格（:166） | 加可选 `avatarKey`；**统计格不动（2.2 改）** | — |
| `lib/features/content/presentation/content_detail_page.dart` | 打卡场所条（:313-316）→ `SizedBox(md)` + `Divider`（:317-318）→ KOMENTAR；`detail.isAuthor`（:448 / :466）、`authorDeleted`；类型判断写法 `detail.type == ContentType.growthMoment.wire`（:563） | :316 与 :317 之间插 `GiftDetailZone` | 其余结构不动 |
| `lib/features/content/domain/content_type.dart` | `daily('DAILY')` = Moment、`growthMoment('GROWTH_MOMENT')` = Diary、`knowledge('KNOWLEDGE')` = Tips（:2） | — | — |
| `lib/features/content/domain/content_detail.dart` | 字段无 petId（:8），有 `id / type / isAuthor / authorDeleted` | 不改（2.1 / 2.2 加 `giftPets`） | — |
| `lib/features/content/presentation/comment_composer.dart` | `_actions()`（:444-481）= `LikeButton` + `DetailShareCardButton`，`_tapTarget` 44×44（:484） | **不改** | 底栏只点赞 + 分享 |
| `lib/features/profile/presentation/widgets/milestone_celebration.dart` | `_Confetti`（:439）/ `_ConfettiState`（:446，54 粒子 :463、5200ms `repeat` :477）/ `_ConfettiPainter extends CustomPainter`（:522，`shouldRepaint` :553）；**无 disableAnimations 处理** | 不改；只参照 CustomPainter 写法 | — |
| `disableAnimations` 先例 | `place_stamp_ceremony_page.dart:106`、`stamp_ceremony.dart:142`（`MediaQuery.maybeDisableAnimationsOf`）；`ts_generating_view.dart:29`、`pet_passport_new_stamp_page.dart:46`、`splash_page.dart:231`、`bottom_tab_bar.dart:368`；测试先例 `test/place/place_stamp_ceremony_page_test.dart:177 / :406` | 照用 | — |
| `lib/core/analytics/analytics.dart` | `capture`（:163）、`capturePurchase`（:214）、`scrub`（:320）、`appsflyerEvents`（:137）、`_allowedButtonIds`（:282）；PostHog 事件名无白名单 | 只调用 | — |
| `test/analytics/v112_events_test.dart` | 源码提取正则（:146-166）、snake_case（:204）、`allowedPrefixes`（:217-333，**无 `gift_`**）、`allowedSuffixes`（:346-379 起，无 `_paid`）、`legacyEvents` 不得新增（:186） | 加 `'gift_'` 前缀 | 不放宽规则 |
| `test/analytics/v140_events_inventory_test.dart` | Toko 线清单对账（:53）+ NFR-5 全局属性体检（:75） | 不改 | — |
| `lib/l10n/app_en.arb` / `app_id.arb` | 无 `gift*` 键；`keepsakePayConfirm`（id :2546）、`idCardHdPawcoinSub`（id :1587 / en :2418）、`triageUnlockTopupFirst`（id :371 / en :586）、`idCardHdPurchaseError`（id :1604 / en :2435）、`idCardHdInsufficientBalance`（id :1603 / en :2434）、`commonRetry`（id :2114 / en :3201）、`petTypeCat`（id :83 / en :119） | 加 AC7 新键 | 模板 = `app_en.arb`（`l10n.yaml`） |
| `lib/features/pawcoin/presentation/pawcoin_controller.dart` | `pawCoinProvider`（:81） | 送达后 invalidate | — |
| `lib/core/network/problem_detail.dart` | `typeSlug`（:26）、`fromDioException`（:59） | 复用 | — |
| `lib/shared/widgets/app_toast.dart` | `showAppToast`（:35，默认 2600ms）/ `showAppToastOnOverlay`（:46） | 复用 | — |
| `lib/core/network/dio_client.dart` / `api_paths.dart` | `dioProvider`（:43）；`ApiPaths.base = '/api/v1'`（:5） | 加 3 条路径 | — |
| `lib/core/theme/colors.dart` | `mint` #845EC9（:19）、`dashedViolet` #C2B0EC（:41）、`gold` #F6A609（:46） | 复用 | 当前主题淡紫，`mint*` 是遗留别名 |
| `pubspec.yaml` | `rive`（:59）有、**无 lottie / uuid** | 不加依赖 | — |
| `lib/features/gift/`、`lib/shared/format/` | 不存在 | 新建 `features/gift/{data,domain,presentation}`（`shared/format/idr_compact.dart` 属 2.2） | — |

### UI 稿内部不一致的处理（照下列取值，不再问）

- 礼物区圆角：说明写 16、CSS 为 24 → **取 24**（CSS 是第三步定稿值）。
- 面板标题头像：说明写 28、CSS 为 32 带环 → **取 32**（B6 / B7 内联 28 只是加载态）。
- B1 合计行示例「🦴 Tulang × 1」与画面「Ikan teri」选中矛盾 → 以「默认选中排序后第一个」为准（给猫送时是 Ikan teri）。
- D 泳道「底部上方 90」与 CSS bottom 120 → 用 `showAppToast` 现有默认位置，不改组件。
- 步进器圆钮 32、热区 44 → 视觉 32，点击区域补到 44。
- D2 画面统计格画成纯数字「29」→ 统计格属 2.2，按金额显示，本 story 不涉及。

### 架构约束

- AD-13：面板与 `PayChannelPicker` **依次弹不叠层**；`PayChannelPicker` 只加可选参数；QRIS 过期 / 失败用 `onAborted` 提示；动效 CustomPainter 1–2 秒、尊重 `disableAnimations`。
- NFR5：礼物名按 App 语言取 `nameId` / `nameEn` 快照字段；其余文案全部取 arb。
- AD-5 / P-3：App 不判断多宠；详情页按 `postId` 由后端取单宠 `recipient`。
- 金额显示统一 `Rp` + `formatIdrAmount`；1 koin = Rp1。
- 埋点属性不带 PII；`gift_id` 用 giftToken（不可枚举）。

### 本 story 不做

- 统计卡「Hadiah」格、礼物页 E 组（含「送礼」入口三）、详情页礼物区的宠物行与缩略、A5 空态、通知中心 G 组、金额缩写 `idr_compact.dart`、埋点 `gift_wall_opened` / `gift_sender_list_opened`（Story 2.2）。
- 钱包页「送礼记录」入口与列表、流水 `refType` 文案（Story 2.3）。
- A7 选宠物抽屉、锁区块跳 ① 等级页、DiaryHeader 与 ② 切换器合并（Story 4.1，对齐分支）。
- 后端任何改动。

### 测试

**验证层级**

- **L0（云端可跑：`flutter analyze` + `flutter test`，结构断言，不写 golden）**：
  - `test/shared/pay_channel_picker_ext_test.dart`：不传新参数时与现状一致（PawCoin 默认、余额不足默认 QRIS、充值 pop + push）；`qrisDisabledReason` → QRIS 不可点、副标题替换、hint 出现；QRIS 禁用 + 余额不足 → 无选中、确认置灰；`onConfirm` 返回 false 抽屉不关、按钮恢复，true 关闭并返回渠道；转圈期间渠道行不可点；`onTopUp` 被调用。
  - `test/gift/gift_panel_sheet_test.dart`：默认选中第一项；×5 高亮、手动 +1 后无胶囊高亮；1 时「−」禁、99 时「+」禁；合计与按钮金额联动（Rp5.000）；专属组标签与锁区块文案（Lv5 / Lv15 / 无）；锁区块点击出 Toast 且面板不关；B6 / B7 与重试；名称按 locale 取 id / en。
  - `test/gift/gift_send_flow_test.dart`（假仓储 + 假路由）：PawCoin 成功 → 抽屉关 → Toast「Tulang ×5 terkirim ke Mochi」（P-4）→ 余额 invalidate；`pawcoin-insufficient` 抽屉保留；`gift-blocked` 关闭 + `giftCannotSend`；关闭选渠道回面板且选择保留；充值返回后重开选渠道且礼物 / 数量保留；换渠道后 `requestId` 变化、同渠道重试不变；QRIS `pollPaid` 三种中止类别对应三种 Toast。
  - `test/gift/gift_entry_visibility_test.dart`：访客视图自己的宠物 / `petProfileProvider` 加载中不显示、他人显示；分享链接（petId null）不显示；详情页 `GROWTH_MOMENT` 非作者显示、作者 / 已注销作者不显示、`DAILY` / `KNOWLEDGE` 不显示；`_actions()` 仍只有两个按钮（`detailBottomActions` 子项数）。
  - `test/gift/gift_send_animation_test.dart`：`MediaQuery(disableAnimations: true)` → 无飞行 Overlay、直接 Toast；正常 → `pump(1600ms)` 后 Overlay 移除、Toast 出现；`quantity = 1` 无「×N」。
  - `test/gift/gift_models_test.dart`：目录 / 发起响应 / 订单状态 JSON 解析，未知状态不崩。
  - `test/analytics/v140_gift_events_test.dart`（AC6.3）+ 既有 `test/analytics/*` 全绿。
- **L2（本地，Android 模拟器 + stag 后端，云端不做）**：B1 / C2 / D1 演示录屏；C3 充值回流、C6 真二维码、C7 过期 Toast。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-gift.md#Story 1.3]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-gift-delta.md#§0.3 第 9 条、§0.6、AD-5、AD-10、AD-13、§6.2]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/1-4-0送礼prd.md#3.1 送礼流程 / 支付规则 / AC1 ~ AC4 / AC9 / §4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/送礼/ui-1-4-0-gift-integrated-v1.html#A1 / A3 / A4 / A6 / A7、B1 ~ B7、C1 ~ C9、D1 ~ D2、S1 ~ S8]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#③ 送礼 · 第二步 S1 ~ S8]
- [Source: _bmad-output/implementation-artifacts/v1.4.0/1-1-礼物目录与送礼后端.md]

### 拍板（2026-10-18，Dai；优先于本文件正文与「待确认」）

- P-4：送达 Toast 用礼物名，不用 emoji（如「Tulang ×5 已送给 Mochi」，以 arb 句式为准）。

## Dev Agent Record

### Agent Model Used

Claude (cloud dev agent)

### Debug Log References

- `flutter analyze` 基线（改动前）0 issue → 改动后 0 issue。
- `flutter test` 全量：见 Completion Notes「L0 结果」。
- 测试环境踩点：flutter_test 的测试字体每个字形是 fontSize 见方的方块，360 宽画布下 `PayChannelPicker` 余额不足行「Isi saldo dulu →」会把副标题挤成逐字换行而溢出（真机字体不会）——流程测试改用 800 宽逻辑画布，组件未改。

### Completion Notes List

- **T0 开工核对（对照 1.1 = 459f9f2e、1.2 = 0feee5e6）**：`GiftController` 三条接口、`GiftCatalogResponse`（`recipient{petId,name,avatarUrl,petType,breed}` / `qrisMinAmount` / `tiers{price,gifts}` / `exclusiveGroups{minLevel,gifts}` / `lockBlock{requiredLevel}`；`GiftItem{giftToken,nameEn,nameId,iconUrl,price,kind,minLevel}`）、`GiftSendRequest{giftToken,quantity,channel,requestId(≤64),source,postId}`、`GiftSendResponse{delivered,payment{token,displayNo,…},payload,orderToken,status}`、`GiftOrderStatusResponse.status` 七值、6 个 ProblemDetail slug（`gift-own-pet` / `gift-blocked` / `gift-locked` / `gift-qris-below-min` / `gift-pet-gone` / `gift-unavailable`）与 `pawcoin-insufficient` 均与本文件一致；`GiftSource` 小写 `diary_tab` / `content_detail` / `gift_page` 一致。App 侧 `PayChannelPicker`（:11 / :26-38 / :65-67 / :104-114 / :117-123 / :127）、5 个调用方行号、`qr_payment_sheet.dart`（:14-24 / :36-42 / :78 / :100-108 / :115）、`vet_timed_pay_page._topUp`（:218-239）、`keepsake_pay_flow`（:39-48 / :52 / :86-90）、`visitor_archive_view`（:40 / :61 / :64 / :105-115）、`diary_header`（:40 / :88 / :114-115 / :132）、`pet_info_card`（:44 / :67-72）、`comment_composer._actions`（:444-481 / :484）、`milestone_celebration._Confetti`（:439 / :446 / :477 / :522 / :553）、`analytics.dart`（:137 / :163 / :214 / :320）、`v112_events_test`（:146-166 / :217-333）、`v140_events_inventory_test`（:53 / :75）一致。**改了本文件四处**：① `content_detail_page.dart` 行号整体前移 1（打卡场所条 :313-316、分隔线 :317-318，插入点改为 :316 与 :317 之间）；② Riverpod 3 无 `valueOrNull`，AC1.1 改为 `.value`；③ `keepsake_repository.dart` 的 provider 是 `FutureProvider.autoDispose`（非 family，:20-25），AC8.2 改写为「照它 + 目录用 `.family` + `retry: (_, _) => null`（照 `boarding_pass_repository.dart`）」；④ **拍板 P-4**（本文件末尾 + 架构增量 §P-4「Toast 用礼物名、不用 emoji」）与 AC5.3 / AC7 / 测试小节的「Terkirim ke {petName} 🎁」冲突 → 按 P-4 改为 `giftSentToast(giftName, count, petName)`：「{礼物名} ×{n} terkirim ke {宠物名}」，数量 1 省略「×1」（与 1.2 推送同口径）。
- AC1 ~ AC8 实现完毕；AC9.1 L0 全绿（见下）；AC9.2 模拟器演示留本地。
- **流程（AC3 / AC4）**：`runGiftSendFlow` 面板 → 选渠道 →（充值 → 选渠道）→（关闭 → 面板）→ 成功 / 结束，任何时刻只有一层抽屉；目录 provider 在整条流程内 `listenManual` 保活（关选渠道回面板不重拉、礼物 / 数量照旧）；`gift-unavailable` 时 `invalidate` 后重开面板。扣款中 `PayChannelPicker` 用 `PopScope(canPop: !_busy)` 挡返回 / 点遮罩；流程另在抽屉关闭后等待在途请求结果再判（防「请求途中被拖下去」时漏掉已扣款的送达）。
- **埋点**：5 个事件（本 story 范围；`gift_wall_opened` / `gift_sender_list_opened` 属 2.2）。`gift_panel_opened` 在**目录首次显示出来时**报（`pet_id` 取目录 `recipient.petId`——详情页按 postId 进来时 App 事先不知道 petId；每条流程只报一次，关选渠道回面板不重报）；`gift_send_tapped` 在面板点「Kirim」时报；`gift_qris_low_amount_shown` 每次「Kirim」后首次展示置灰 QRIS 时报一次。`v112_events_test.dart` 只加 `gift_` 前缀（不加后缀、不放宽正则）；新建 `test/analytics/v140_gift_events_test.dart` 对账。
- **数量 1 的 Toast** 省略「×1」；`×N` 动效只在数量 ≥ 2 时出现（AC5.1）。
- 无新依赖（pubspec 未动）、无 golden、未整文件 `dart format`、未改 `kApiBaseUrl`、无后端改动。

#### L0 结果

- `flutter gen-l10n` ✅；`flutter analyze` ✅ No issues found（基线同为 0）。
- 新测试：`test/shared/pay_channel_picker_ext_test.dart`（7）、`test/gift/gift_models_test.dart`（7）、`test/gift/gift_panel_sheet_test.dart`（12）、`test/gift/gift_send_flow_test.dart`（14）、`test/gift/gift_entry_visibility_test.dart`（13）、`test/gift/gift_send_animation_test.dart`（5）、`test/analytics/v140_gift_events_test.dart`（5）全绿。
- 既有：`test/keepsake/keepsake_contract_test.dart`、`test/shared/*`（含 `qr_payment_sheet_test.dart`）、`test/analytics/*`、`test/profile/visitor_archive_view_test.dart`、`test/content/*`、`test/user_profile/*`、`test/l10n/*` 全绿。
- `flutter test` 全量：✅ `+3474 ~1: All tests passed!`（~1 为既有 skip，非本 story）。首轮全量曾红 2 条（`test/profile/pet_recommendation_test.dart` AC7 两条：访客视图在档案失效分支也去读 `petProfileProvider`，真 dio 留下 pending timer）→ 改为只在档案加载成功分支里读（`_giftPill()` 挪进 `data:`），测试未改、复跑全量绿。

#### L1/L2 待本地验收

- L2（Android 模拟器 + stag 后端）：B1（Lv1 锁区块 + Toast）、C2（总价 Rp5.000 QRIS 置灰 + 提示）、D1（PawCoin 送达动效 + Toast）演示录屏。
- L2：C3 充值回流（充值页返回后自动重开选渠道、余额刷新）、C5 扣款中转圈与禁点、C6 真二维码付款、C7 过期 Toast「Pembayaran kedaluwarsa. Coba lagi.」。
- L2：视觉对稿（面板 88% 高、礼物格选中态、专属组段位色、锁区块、详情页礼物区、访客视图胶囊位置）；系统「减少动态效果」开启时无飞行直接 Toast。
- L1：无（本 story 只 App）。

#### 待确认

1. **分享链接访客视图（`/pet/:token`）不显示送礼按钮**（AC1.5）：该入口拿不到 petId，UI 稿要求显示；本分支按 story 不显示。
2. **A7 选宠物抽屉在对齐 story 4.1 接入**：本分支详情页按 `GET /gifts/catalog?postId=` 返回的单宠 `recipient` 送；锁区块点击只出 Toast「Terbuka di Lv{N}」，4.1 改为关面板跳 `/me/growth`；段位色为 gift 模块私有常量（`GiftStyle`），4.1 改用 ① 的 token。
3. **Toast 文案按 P-4 改用礼物名**（取代 PRD / UI 稿「Terkirim ke Mochi 🎁」）：ID「{礼物名} ×{n} terkirim ke {宠物名}」、EN「{gift} ×{n} sent to {pet}」，数量 1 省略「×1」。
4. **EN 文案全部为提议值、待本地团队审**（UI 稿只有印尼语）；`giftLockedError` / `giftUnavailable` / `giftUndelivered` 是 UI 稿未画的错误分支提议值（ID 亦待审）。`giftPayExpired` 与 ② 同句文案在对齐时可合并。
5. **详情页游客点「Kirim Hadiah」先走登录门控**（`requireLogin`，`entrySource: 'gift'`）：story 未写游客情形，送礼接口仅 USER，取与点赞 / 评论同口径的保守做法。
6. **`gift_panel_opened` 上报时机**：在目录首次显示时（为拿到 `pet_id`），目录加载失败 / 被拒（C9）的那次打开不计入；如需「点了入口即报」需另定 `pet_id` 口径。
7. **送达后 `onDelivered`**：`runGiftSendFlow` 比 AC4.6 的签名多一个可选 `VoidCallback? onDelivered`（访客视图用它 `invalidate(visitorStatsProvider(scope))`，供 2.2 接上「Hadiah」格）。
8. **QRIS 发起返回 `delivered=false` 但无 `payload`**（理论上只有 1.1「待确认 7」的极端重放）：Toast `idCardHdPurchaseError`、抽屉保留可重试（同渠道复用 requestId，后端按幂等返回同一笔）。PawCoin 渠道返回未送达同样按失败提示。
9. 步进器圆钮视觉 32、热区 44：为补足热区，步进器容器整体高度随之变为 50（视觉圆钮尺寸不变），L2 对稿时确认可接受。

### File List

**新增（petgo_app/）**
- `lib/features/gift/domain/gift_catalog.dart`
- `lib/features/gift/domain/gift_send_result.dart`
- `lib/features/gift/data/gift_repository.dart`
- `lib/features/gift/presentation/gift_style.dart`
- `lib/features/gift/presentation/gift_panel_sheet.dart`
- `lib/features/gift/presentation/gift_send_flow.dart`
- `lib/features/gift/presentation/gift_send_animation.dart`
- `lib/features/gift/presentation/widgets/gift_entry.dart`
- `test/gift/gift_fakes.dart`
- `test/gift/gift_models_test.dart`
- `test/gift/gift_panel_sheet_test.dart`
- `test/gift/gift_send_flow_test.dart`
- `test/gift/gift_entry_visibility_test.dart`
- `test/gift/gift_send_animation_test.dart`
- `test/shared/pay_channel_picker_ext_test.dart`
- `test/analytics/v140_gift_events_test.dart`

**修改（petgo_app/）**
- `lib/core/network/api_paths.dart`（+3 路径）
- `lib/shared/widgets/pay_channel_picker.dart`（+4 可选参数、无选中规则；不传时行为不变）
- `lib/features/profile/presentation/visitor_archive_view.dart`（titleAction 送礼胶囊 + 自己宠物判断 + avatarKey）
- `lib/features/profile/presentation/widgets/diary_header.dart`（可选 `avatarKey` 透传）
- `lib/features/profile/presentation/widgets/pet_info_card.dart`（可选 `avatarKey`）
- `lib/features/content/presentation/content_detail_page.dart`（`GiftDetailZone` + `_showGiftZone`）
- `lib/l10n/app_en.arb` / `lib/l10n/app_id.arb`（+28 个 `gift*` 键）
- `test/analytics/v112_events_test.dart`（`allowedPrefixes` 加 `gift_`）

**修改（规划 / 实现产物）**
- `_bmad-output/implementation-artifacts/v1.4.0/1-3-app送礼入口-面板-支付与动效.md`（T0 修订 + 本记录）
- `_bmad-output/implementation-artifacts/v1.4.0/sprint-status-v1.4.0-gift.yaml`（1-3 → review）

### Change Log

- 2026-10-18：T0 核对后修订本文件四处（详情页行号、Riverpod 3 `.value`、keepsake provider 形态、P-4 Toast 文案）；实现 App 送礼入口 / 面板 / 选渠道扩展 / PawCoin 与 QRIS 支付 / 送出动效 / 埋点 / 文案；L0 绿，状态 → review。
- 2026-10-18（code review 修复 2 条）：① `PayChannelPicker` 扣款中下滑仍能关抽屉（BottomSheet 拖拽关闭直接 `pop`、不经 `PopScope`）→ 传 `onConfirm` 时扣款中用内层竖向拖拽手势吃掉拖动（不传新参数时树与行为不变）；② `pawcoin-insufficient` 后只 invalidate 余额、抽屉里仍是旧余额与「已选 PawCoin」，用户只能反复撞 409 → 选渠道抽屉改为 watch `pawCoinProvider` 取余额并按余额 re-key（AC4.4「刷新余额」）；补测试 2 条，`flutter analyze` 0 issue、`flutter test` 全量 +3476 ~1 绿。
