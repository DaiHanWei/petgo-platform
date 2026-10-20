# Story 2.4: App——名额管理、租用续租与只读体验

Status: review

> 版本 v1.4.0 · 主题 multi-pet · 分支 `feat/1.4.0-multi-pet`
> 口径优先级：`decision-log.md` ② 节 > `多宠物/1-4-0多宠物prd.md` > `architecture-v1.4.0-multi-pet-delta.md` > UI 稿 `多宠物/ui-1-4-0-multi-pet-integrated-v1.html`（定稿，A4 / B4 / D1 ~ D15 / E1 ~ E3 / K1）（均在 `_bmad-output/planning-artifacts/v1.4.0/`）；新增文案取 `多宠物/1-4-0多宠物-新增文案对照表.md`。
> 前置：**Story 1.5**（`petListProvider`、`currentPetIdProvider`、Diary Tab 切换器、A4 名额已满抽屉与「月租」占位入口、A1「Kelola ›」入口）、**Story 2.1**（`/pet-profiles/me/list` 带 `status` / `keepActive`、`PUT /pet-profiles/me/active-selection`、409 `pet-read-only`）、**Story 2.2**（`GET /pet-slots/me`、`POST /pet-slots/me/rentals` / `renewals`、`GET /pet-slots/me/purchases/{token}`）、**Story 2.3**（`PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` 通知，title / body 由后端按账号语言写入）。
> 范围层：**只 App**（`petgo_app/`）。🔴 不整文件 `dart format`（存量未格式化，只手工对齐改动行）；不写 golden；不渲染后端显示串——**唯一例外**是两类到期通知读后端 title / body（K1 拍板，同 `OPS_ANNOUNCEMENT`）。

## Story

As a 多宠物用户,
I want 在名额管理页看清名额构成、租用续租、选择保留哪几只，并在只读宠物上看到清楚的提示,
so that 名额与只读状态对我是透明的。

## Acceptance Criteria

**AC1 — 名额管理页 `features/pet_slot/`（D1 ~ D4、D15）** `[L0]` `[L2]`
1. 路由 `/me/pet-slots`（受 `/me` 登录守卫，`app_router.dart` 照 :1013 `/me/pawcoin` 写法）；**接通 1.5 的占位**：A1 Hewanku「Kelola ›」、A4「Sewa slot」（A4 主按钮 → 打开本页并直接弹 D5 租用抽屉，用 query `?action=rent`）都指向它。若 1.5 已用别的路径占位，以 1.5 的为准并删除占位页。
2. AppBar「Kelola Hewan」（D-1）。从上到下：**名额卡**（大数字「已用」+ 后缀「/ {total} slot dipakai」D-2 + 副标题 D-3；下方 5 个名额格子：已占用格显示宠物头像 + 来源标签 Bawaan / Level / Sewa（D-4 ~ D-6；只读宠物格加锁 + 「Hanya-baca」D-9）、未拥有格「Bisa disewa」+ 锁（D-7）、已拥有未占用格「Kosong」+ 加号（D-8））→ **Slot sewa** 小节（D-10，仅有月租时；每个名额一行「Slot sewa {n}」D-11 +「Berakhir {date}」D-12；≤ 3 天显示提醒色「Berakhir {date} · {n} hari lagi」D-13）→ **Hewanku**（每只宠物：头像、名字、物种 · 年龄、状态「Aktif」/「Hanya-baca」；有只读时标题右侧「Ubah pilihan ›」D-17 + 说明 D-16）→ 页底说明 D-14 → **吸底按钮**：「Sewa slot · {price} / {days} hari」（D-15）；有月租时右侧加次按钮「Perpanjang」（B-3）；`rentable = 0` 时主按钮置灰「Sudah maksimal 5 slot」（D-18，D4），续租照常。
3. 数据：`petSlotOverviewProvider`（`GET /pet-slots/me`）+ `petListProvider`（1.5）；价格、天数一律取后端 `unitPrice` / `days`，**无本地兜底价**（D1 说明）。
4. **D15 加载失败**：整页 `cloud_off` 图标 +「Gagal memuat slot hewan」（D-33）+「Coba lagi」（`commonRetry`），点了重取（写法照 KTP 列表 B5）。
5. 进入本页、租 / 续 / 改选成功后：`invalidate` `petSlotOverviewProvider`、`petListProvider`（宠物状态可能变）与当前宠物相关 provider（只读横幅随之消失）。

**AC2 — 租用抽屉（D5 ~ D10b）** `[L0]` `[L2]`
1. 复用 `PayChannelPicker`，**加可选数量步进器 + 总价行**（D5 拍板）：新增可选参数（例如 `PayQuantityConfig? quantity`：`min = 1`、`max = rentable`、`initial = 1`、`unitPrice`、`onChanged`），给了才显示「Jumlah slot」（D-23）步进器与「{n} × {price}」/ 总价行；PawCoin 行的「Saldo {balance} · Potong {cost} koin」（`idCardHdPawcoinSub`）、余额判断、QRIS 行金额、主按钮「Bayar Rp{price}」（`keepsakePayConfirm`）全部按**总价**。🔴 不传 `quantity` 时与现状逐像素一致，现有 5 处调用（护照快照 / 登机牌 / Tailsonality 结果 / 配型 / 高清卡）不改。
2. 抽屉头卡：图标 +「Slot hewan」（D-20）+「{price} / slot · {days} hari」（D-21）；标题「Sewa slot hewan」（D-19）；说明 D-22。
3. **D6 余额不足**：沿用 `PayChannelPicker` 现状（PawCoin 行置灰 +「Isi saldo dulu →」`triageUnlockTopupFirst` → `/me/pawcoin/recharge`，默认选中 QRIS）；数量变化时重新判断余额。
4. 流程复用 `runKeepsakePurchase`（`keepsake_pay_flow.dart:39`）：`sheet` 构建带数量的 picker（数量由 picker 回调写进本地 holder）、`start` 调 `POST /pet-slots/me/rentals`（`Idempotency-Key` 每次点击主按钮生成一次 UUID，重试同一次沿用）、`pollPaid` 轮询 `GET /pet-slots/me/purchases/{purchaseToken}`（`PAID` → true；`EXPIRED` → `throw QrPaymentAborted('EXPIRED')`；`FAILED` → `throw QrPaymentAborted('FAILED')`）、`purchasePurpose: 'PET_SLOT_RENT'`（QRIS 到账时 `Analytics.capturePurchase` 自动上报）、`cashPriceIdr: () => total`。
5. 给 `runKeepsakePurchase` **加可选参数** `void Function(QrPaymentAborted abort)? onQrAborted`，原样透传给 `showQrPaymentSheet(onAborted:)`（`qr_payment_sheet.dart:36`）；不传时行为不变，现有 4 处调用不改。
6. 结果：
   - **D9 成功**：抽屉关闭、页面刷新，SnackBar「Slot hewan bertambah {n} · berlaku sampai {date}」（D-24，`{date}` 取购买查询返回的 `expiresAt`，`d MMM yyyy` 本地化）。
   - **D10 失败**：SnackBar「Pembayaran gagal. Coba lagi.」（沿用 `idCardHdPurchaseError`），名额不变。
   - **D10b QRIS 过期**（拍板：只在名额流程补提示，不改通用面板）：`onQrAborted` 里 `category == 'EXPIRED'` → SnackBar「Pembayaran kedaluwarsa. Coba lagi.」（D-25）；`FAILED` → D10 文案。
   - 后端 409 `pet-slot-rent-unavailable`（名额已变）→ 刷新页面 + D10 文案；`pawcoin-insufficient` 沿用 `idCardHdInsufficientBalance`（flow 已处理）。
7. D7 二维码面板沿用 `showQrPaymentSheet`（不可下滑关闭、3 秒轮询、「Batalkan Pembayaran」），不改。

**AC3 — 续租抽屉（D11 / D12）** `[L0]` `[L2]`
1. 同 `PayChannelPicker` 骨架，把数量步进器换成**名额勾选列表**（同样以可选参数注入，例如 `Widget? itemsArea` + 由调用方算好的 `total`）：每行「Slot sewa {n}」+「{原到期} → {新到期}」（新到期 = 原到期 + `days`），复选框。标题「Perpanjang slot」（D-26）、说明 D-27、总价行「{n} × {price}」。
2. **默认勾选**：全部 ACTIVE 月租名额，但最多勾 `renewableMax` 个（后端下发 = 5 − 1 − 等级名额；本期升级拿到永久名额时自然「少勾一个」，PRD）；超过上限的行可见、不可勾。一个都不勾时主按钮禁用。
3. `start` 调 `POST /pet-slots/me/renewals`（`rentalTokens` = 勾选项）；其余同 AC2.4 ~ AC2.6。成功 SnackBar「Slot diperpanjang sampai {date}」（D-28），页面到期日刷新（D12）。
4. 后端 409 `pet-slot-renew-invalid`（某名额刚过期）→ 刷新页面 + SnackBar D10 文案。

**AC4 — 改选保持正常的宠物（D13 / D14）** `[L0]` `[L2]`
1. 只在名额管理页 Hewanku 标题右侧「Ubah pilihan ›」进入（2026-10-15 拍板：B4 横幅 / E1 抽屉**不放**改选入口）。仅当 `readOnlyCount > 0`（或宠物数 > 名额）时显示。
2. 多选抽屉（骨架参照 `place_list_page.dart` 的 `_FilterSheet`：把手 → 粗体标题 → 勾选行 → 底部按钮）：标题 D-29、说明「Kamu punya {n} slot. …」（D-30）、每行头像 + 名字 + 物种 · 年龄 + 复选框；默认勾选当前 `ACTIVE` 的宠物；勾满名额后其余行不可再勾；底部「{selected} / {total} dipilih」（D-31）+「Simpan」（`commonSave`），未选满禁用。
3. 保存调 `PUT /pet-profiles/me/active-selection`；成功 → 关抽屉、刷新、Toast「Pilihan disimpan」（D-32，`showAppToast`）；若当前 Diary Tab 宠物变成只读，回到 Diary Tab 时显示只读态。失败 → D10 文案式通用失败提示（`commonRetry` 可重试）。

**AC5 — 只读体验（B4、E1 ~ E3、A3 锁标）** `[L0]` `[L2]`
1. **切换器锁标**：1.5 的 Diary Tab 切换器、「我的」页 Hewanku 头像，对 `status == READ_ONLY` 的宠物头像灰化 + 锁角标（B4 / A3）。1.5 若已做则只核对。
2. **B4 只读横幅**：`growth_archive_page.dart` `_ArchiveBody` 的 `ListView` children 最前（:444 `DiaryHeader` 之前）插入横幅：锁图标 +「Slot sudah berakhir」（B-1）+「Perpanjang untuk lanjut mencatat.」（B-2）+ 按钮「Perpanjang」（B-3）；当前宠物只读时，`DiaryHeader` 的编辑铅笔（`diary_header.dart:120` `editProfileButton`）隐藏。查看、分享照常；入口两栏、里程碑仍可进。
3. **「续租」按钮去向**（B4 横幅、E1 主按钮共用一个 helper `openPetSlotRenewal(context, ref)`）：进入名额管理页，若有 ACTIVE 月租名额 → 自动弹 D11 续租抽屉；**没有任何 ACTIVE 月租（只读正是因为月租已全部过期）→ 自动弹 D5 租用抽屉，数量默认 = `shortBy`**（后端 AD-6：已过期名额不能续，只能新租；见 Dev Notes「待确认」）。
4. **E1 统一拦截抽屉**：`showConfirmSheet`（`confirm_sheet.dart:21`），锁图标（紫）→「Slot {name} sudah berakhir」（E-1）→ E-2 正文 → 主按钮「Perpanjang slot」（D-26）→ AC5.3 / 次按钮「Nanti saja」（沿用 `pushSoftGuideLater`）。封装为 `showPetReadonlyBlockSheet(context, ref, pet)`。
5. **E1 触发入口**（前端先按 `status` 拦，不发请求）：编辑档案（`DiaryHeader` 编辑入口、档案编辑页保存）、健康记录「+」（`health_list_page.dart:149` `healthAddTop`、:342 `healthAddBottom`）与分类卡点击预选添加、里程碑页未完成徽章（`milestone_list_page.dart` 打开打卡抽屉处 :656 / :830 一带，改为弹 E1）、Kenali Hewanmu 里的付费项（KTP 创建 / Tailsonality / 护照快照 / 登机牌入口）。**发 Diary、打卡不走 E1**（3.3：只读宠物在选择器里直接不出现）；AI 问诊存档 H2 在 4.1。
6. **后端兜底**：上述接口返回 409 `pet-read-only`（`ProblemDetail.typeSlug`）时，统一弹 E1（宠物名取扩展成员 `petId` 对应的列表项，取不到用当前宠物），并 `invalidate(petListProvider)`（状态可能刚变）。提供 `handlePetReadOnly(context, ref, ProblemDetail pd) → bool` 供各入口复用。
7. **E2 里程碑页**（`milestone_list_page.dart`）：Header 头像带锁标，页面其余不变、可查看。
8. **E3 健康记录页**（`health_list_page.dart`）：AppBar 标题多宠时「Catatan Kesehatan · {name}」（新键 `petSlotHealthTitleNamed`；只 1 只时仍用 `healthListTitle`）；只读时「+」与「Tambah catatan」带锁、点了弹 E1；分类卡计数「{n} catatan」（E-3，UI 稿 E3 把分类卡副文从最近日期改为条数——见 Dev Notes「待确认」）。

**AC6 — 通知中心两类到期（K1）** `[L0]` `[L2]`
1. `notification_center_page.dart`：
   - 图标（:817 起的类型 → 图标 / 色表）：`PET_SLOT_EXPIRING` → 金色沙漏（`hourglass_bottom`），`PET_SLOT_EXPIRED` → coral 锁钟（`lock_clock`），配色参照 `ACCOUNT_WARNED` / `ACCOUNT_SUSPENDED`（:817-826）。
   - 标题 `_typeLabel`（:872）/ 正文 `_typeBody`（:911）：两类型照 `OPS_ANNOUNCEMENT` 读后端 `item.title` / `item.body`（复用 `_opsTitle` / `_opsBody` :968-976 的「trim 后为空回退 `notifyTypeSystem` / `notifyBodySystem`」逻辑，可提成通用 `_serverTitle` / `_serverBody`），**不按 App 当前语言再选**；更新两处注释里「唯一读后端 title 的类型」的措辞。
   - 埋点类型映射 `_notifTypeForAnalytics`（:270）补两类型。
2. 点击：`deep_link_routes.dart` `pushPayloadToLocation`（:62）固定目标 switch（:65-90）加 `case 'PET_SLOT_EXPIRING': case 'PET_SLOT_EXPIRED': return '/me/pet-slots';`（不依赖 targetRef，须在「无 targetRef → 通知中心」短路之前）；推送点开（`push_service.dart`）走同一映射。
3. `notification_item.dart:32` 的已知类型注释补两类型。

**AC7 — 埋点** `[L0]`（PRD §4，按仓库命名守卫）
1. `pet_slot_rent_started`：用户在租用 / 续租抽屉确认渠道时（`runKeepsakePurchase` 的 `onChannelConfirmed`），属性 `count`、`is_renewal`、`channel`。
2. `pet_slot_rent_paid`（**PRD 口径名 `pet_slot_rent_paid`**：仓库后缀白名单无 `_paid`，按「改名迁就规则」改为 `_succeeded`，与 `toko_order_payment_succeeded` 同理；Completion Notes 写口径名 → 实际名映射）：发放成功时，属性同上。
3. `pet_readonly_banner_shown`：B4 横幅首次出现（每次进入该宠物 Diary Tab 一次），属性无 PII（不带宠物名）。
4. `pet_readonly_renew_tapped`：B4 / E1「续租」点击，属性 `source`（`banner` / `block_sheet`）。
5. `test/analytics/v112_events_test.dart` 的 `allowedPrefixes`（:217 起）加 `'pet_slot_'`、`'pet_readonly_'`（写注释说明是新模块入表，照现有条目格式；若 1.5 已为 `pet_slot_full_shown` 加过 `pet_slot_` 则只补 `pet_readonly_`）。事件经 `Analytics.capture`，属性走 `scrub`。
   〔T0〕1.5 实际入表的是更宽的 `'pet_'`（注释已写明「宠物 / 宠物名额」模块），已覆盖 `pet_slot_*` / `pet_readonly_*` 四个新事件 → 本 story **不改**前缀表（不重复登记、不放宽后缀）。

**AC8 — 文案（arb，EN / ID 逐条）** `[L0]`
1. 新键一律 `petSlot*` / `petReadonly*` 前缀（已 grep：`app_en.arb` / `app_id.arb` 现无此前缀键）；同时加进 `lib/l10n/app_id.arb` 与 `app_en.arb`，带 `@key` 占位符描述；`{price}` / `{days}` 取后端，不写死（对照表说明）。
2. 键表（ID 为对照表「印尼语（提议）」，终稿以本地运营审核为准）：

| 键 | 对照表 | ID | EN | 占位符 |
|---|---|---|---|---|
| `petReadonlyBannerTitle` | B-1 | Slot sudah berakhir | Slot has expired | — |
| `petReadonlyBannerBody` | B-2 | Perpanjang untuk lanjut mencatat. | Renew to keep recording. | — |
| `petSlotRenew` | B-3 | Perpanjang | Renew | — |
| `petSlotPageTitle` | D-1 | Kelola Hewan | Manage Pets | — |
| `petSlotUsedSuffix` | D-2 | / {total} slot dipakai | / {total} slots used | total:int |
| `petSlotCapNote` | D-3 | Maksimal 5 hewan per akun | Up to 5 pets per account | — |
| `petSlotSourceDefault` | D-4 | Bawaan | Default | — |
| `petSlotSourceLevel` | D-5 | Level | Level | — |
| `petSlotSourceRent` | D-6 | Sewa | Rented | — |
| `petSlotCellAvailable` | D-7 | Bisa disewa | Available to rent | — |
| `petSlotCellEmpty` | D-8 | Kosong | Empty | — |
| `petReadonlyBadge` | D-9 | Hanya-baca | Read-only | — |
| `petSlotRentalsSection` | D-10 | Slot sewa | Rented slots | — |
| `petSlotRentalName` | D-11 | Slot sewa {n} | Rented slot {n} | n:int |
| `petSlotRentalEnds` | D-12 | Berakhir {date} | Ends {date} | date:String |
| `petSlotRentalEndsSoon` | D-13 | Berakhir {date} · {n} hari lagi | Ends {date} · {n} days left | date:String, n:int |
| `petSlotNoAutoRenewNote` | D-14 | Slot sewa tidak diperpanjang otomatis. Kami ingatkan 3 hari sebelum berakhir. | Rented slots don't renew automatically. We'll remind you 3 days before they end. | — |
| `petSlotRentCta` | D-15 | Sewa slot · {price} / {days} hari | Rent a slot · {price} / {days} days | price:String, days:int |
| `petReadonlyShortNote` | D-16 | Slot kamu kurang {n}. Pilih hewan yang tetap aktif — yang lain jadi hanya-baca sampai slot diperpanjang. | You're {n} slot short. Choose which pets stay active — the others become read-only until you renew. | n:int |
| `petSlotChangeSelection` | D-17 | Ubah pilihan | Change selection | — |
| `petSlotMaxReached` | D-18 | Sudah maksimal 5 slot | You've reached the 5-slot limit | — |
| `petSlotRentSheetTitle` | D-19 | Sewa slot hewan | Rent a pet slot | — |
| `petSlotItemName` | D-20 | Slot hewan | Pet slot | — |
| `petSlotUnitPrice` | D-21 | {price} / slot · {days} hari | {price} / slot · {days} days | price:String, days:int |
| `petSlotRentSheetNote` | D-22 | Berlaku {days} hari sejak pembayaran. Tidak diperpanjang otomatis. | Valid for {days} days from payment. Doesn't renew automatically. | days:int |
| `petSlotQuantityLabel` | D-23 | Jumlah slot | Number of slots | — |
| `petSlotQuantityTotal` | （D5 总价行，对照表未单列） | {n} × {price} | {n} × {price} | n:int, price:String |
| `petSlotRentSuccess` | D-24 | Slot hewan bertambah {n} · berlaku sampai {date} | +{n} pet slot · valid until {date} | n:int, date:String |
| `petSlotPaymentExpired` | D-25 | Pembayaran kedaluwarsa. Coba lagi. | Payment expired. Try again. | — |
| `petSlotRenewSheetTitle` | D-26 | Perpanjang slot | Renew slot | — |
| `petSlotRenewSheetNote` | D-27 | Ditambah {days} hari dari tanggal berakhir sekarang. | Adds {days} days from the current end date. | days:int |
| `petSlotRenewRowRange` | （D11 行，对照表未单列） | {from} → {to} | {from} → {to} | from:String, to:String |
| `petSlotRenewSuccess` | D-28 | Slot diperpanjang sampai {date} | Slot renewed until {date} | date:String |
| `petSlotSelectTitle` | D-29 | Pilih hewan yang tetap aktif | Choose pets to keep active | — |
| `petSlotSelectNote` | D-30 | Kamu punya {n} slot. Hewan yang tidak dipilih jadi hanya-baca sampai slot ditambah. | You have {n} slots. Pets you don't choose become read-only until you add slots. | n:int |
| `petSlotSelectCount` | D-31 | {selected} / {total} dipilih | {selected} / {total} selected | selected:int, total:int |
| `petSlotSelectSaved` | D-32 | Pilihan disimpan | Selection saved | — |
| `petSlotLoadError` | D-33 | Gagal memuat slot hewan | Couldn't load pet slots | — |
| `petSlotStatusActive` | （稿 D1「Aktif」，对照表未列） | Aktif | Active | — |
| `petReadonlyBlockTitle` | E-1 | Slot {name} sudah berakhir | {name}'s slot has expired | name:String |
| `petReadonlyBlockBody` | E-2 | Perpanjang slot untuk lanjut mencatat. Kamu tetap bisa melihat dan membagikan catatan {name}. | Renew the slot to keep recording. You can still view and share {name}'s records. | name:String |
| `petReadonlyHealthCount` | E-3 | {n} catatan | {n} records | n:int |
| `petSlotHealthTitleNamed` | （稿 E3 标题，对照表未列） | Catatan Kesehatan · {name} | Health Records · {name} | name:String |

3. 沿用不新增：`commonRetry`（Coba lagi）、`commonSave`（Simpan）、`pushSoftGuideLater`（Nanti saja，E-4）、`idCardHdPawcoinSub`、`triageUnlockTopupFirst`、`keepsakePayConfirm`、`idCardHdPurchaseError`、`idCardHdInsufficientBalance`、`healthListTitle`。
4. A 组（A-1 ~ A-7，名额已满抽屉 / 已达 5 只）属 1.5；K-1 ~ K-4 在后端（2.3）；K-5 属 4.4——本 story 不加。
5. 价格格式统一 `formatIdrAmount`（「Rp5.000」）；日期 `d MMM yyyy` 按 App 语言本地化。

**AC9 — 验收（Epic AC 逐条）** `[L0]` `[L2]`
- Given 名额管理页，When 打开，Then 显示构成（默认 / 等级 / 月租）与每个月租名额到期日；已到上限显示 D4；加载失败可重试（D15）。
- Given 租用抽屉，When 选数量与渠道，Then 显示「N × Rp5.000」与总价，PawCoin 余额不足显示 D6；QRIS 成功（D9）/ 失败（D10）/ 过期（D10b SnackBar）。
- Given 续租，When 勾选两个名额支付，Then 一次付款，成功后两个到期日各 +30 天（D12）。
- Given 有只读宠物，When 进入其 Diary Tab 或点新增类入口，Then 切换器锁标、顶部横幅「Slot sudah berakhir / Perpanjang untuk lanjut mencatat.」+「Perpanjang」；新增类入口弹统一拦截抽屉 E1（只提供续租）；改选只在名额管理页「Ubah pilihan ›」（D13 / D14）。
- Given 通知中心有到期通知，When 查看，Then 显示后端文案，点击进名额管理页（K1）。
- `flutter analyze` 与相关测试绿；**Android 模拟器演示 D2 / D5 / E1**（连 stag 后端 + 真登录；L2 视觉须本地）。

## Tasks / Subtasks

- [x] **T0 开工核对**：1.5 / 2.1 / 2.2 / 2.3 已合入；读 1.5 的 `petListProvider` / `currentPetIdProvider` / 切换器 / A4 占位实际实现
- [x] **T1** `features/pet_slot/`：`data/pet_slot_repository.dart`（四个接口 + `ApiPaths` 常量）、`domain/`（`PetSlotOverview`、`PetSlotRental`、`PetSlotPurchaseStatus`）、`petSlotOverviewProvider`〔T0：`PetSlotOverview` 1.5 已建在 `data/pet_slot_repository.dart`，按其注释在原处追加字段；`PetSlotRental` / `PetSlotPurchaseStatus` 放 `domain/pet_slot_purchase.dart`〕
- [x] **T2** 名额管理页 D1 ~ D4 / D15 + 路由 `/me/pet-slots` + 接通 A1 / A4（AC1）
- [x] **T3** `PayChannelPicker` 可选数量 / 自定义条目区；`runKeepsakePurchase` 可选 `onQrAborted`（AC2.1 / AC2.5，其他调用点零改动）
- [x] **T4** 租用流程 D5 ~ D10b、续租 D11 / D12（AC2 / AC3）
- [x] **T5** 改选抽屉 D13 / D14（AC4）
- [x] **T6** 只读：锁标、B4 横幅、`openPetSlotRenewal`、E1 `showPetReadonlyBlockSheet` + `handlePetReadOnly`、各入口接入、E2 / E3（AC5）
- [x] **T7** 通知中心 K1 + 深链（AC6）
- [x] **T8** 埋点 + 命名守卫前缀（AC7）；arb 两份（AC8）
- [ ] **T9** `flutter analyze` + 相关测试 ✅；Android 模拟器演示 D2 / D5 / E1 ⏳（L2 待本地验收，云端跑不了模拟器）

## Dev Notes

### 现状（改动前必读，2026-10-18 核 `dev_1.4.0` @ 8ca69f9f；路径前缀 `petgo_app/`）

| 文件 | 现状 | 本 story 改什么 | 必须保持 |
|---|---|---|---|
| `lib/features/keepsake/presentation/keepsake_pay_flow.dart` | `runKeepsakePurchase`（:39）：读余额 10s 超时（:52）→ `showModalBottomSheet<HdPayChannel>`（:57）→ `onChannelConfirmed`（:67）→ `start`（:74）→ `unlocked` 即成（:76-78）→ QRIS payload 空报错（:82-84）→ `showQrPaymentSheet`（:86，**未传 onAborted**）→ 到账 `Analytics.capturePurchase`（:89）→ 错误按 `typeSlug` 分流（:94-103：`keepsake-already-unlocked` / `pawcoin-insufficient` / 其他）；结果枚举 `KeepsakeFlowOutcome`（:17）。调用点：`tailsonality_result_page.dart:290`、`tailsonality_match_page.dart:335`、`boarding_pass_detail_page.dart:84`、`pet_passport_page.dart:70`（完整示例，成功 SnackBar :88） | 加可选 `onQrAborted` | 4 处调用零改动 |
| `lib/features/keepsake/domain/keepsake_purchase_result.dart:6` | `KeepsakePurchaseResult`（解析 `unlocked / payment / payload / purchaseToken`） | 复用（后端 2.2 响应同形） | — |
| `lib/shared/widgets/pay_channel_picker.dart` | 构造（:27-38）：`header`、`title`、`body`、`price`(`AsyncValue<int>`)、`onRetryPrice`、`balance`、`confirmLabel`、`confirmKey`、`retryKey`；**无数量 / 总价**；默认渠道按余额（:65-67）；余额不足置灰 + 去充值（:104-114）；QRIS 行金额硬编码 `'Rp${formatIdrAmount(priceIdr)}'`（:120）；确认 `pop(HdPayChannel)`（:128） | 加可选数量 / 条目区参数 | 不传时逐像素不变；5 处调用（含 `passport_snapshot_sheet.dart:80`、`boarding_pass_unlock_sheet.dart:87`）零改动 |
| `lib/shared/widgets/qr_payment_sheet.dart` | `showQrPaymentSheet(context, {payload, pollPaid, orderRef, onAborted})`（:36）；`QrPaymentAborted([category])`（:14-17）；3 秒轮询（:78）；`pollPaid` 抛 `QrPaymentAborted` → 回调 `onAborted` 后静默 `pop(false)`（:100-107）；用户取消不触发 `onAborted`（:115-120）；`isDismissible: false`（:46）；注释（:29-31）要求新能力只能「可选具名参数 + 默认关闭」 | **不改** | — |
| `lib/features/profile/domain/id_card.dart:293` | `enum HdPayChannel` | 复用 | — |
| `lib/shared/widgets/confirm_sheet.dart:21` | `showConfirmSheet(title, message, confirmLabel, cancelLabel, icon, leading, danger, onConfirm, confirmKey, cancelKey)` → `Future<bool>` | E1 用 | — |
| `lib/shared/widgets/app_toast.dart:35` / `:46` | `showAppToast` / `showAppToastOnOverlay`；付费流程现状用 `ScaffoldMessenger…showSnackBar`（`keepsake_pay_flow.dart:70`） | D9 / D10 / D10b / D12 用 SnackBar（与付费流程一致），D14 用 Toast（稿写 Toast） | — |
| `lib/features/notify/presentation/notification_center_page.dart` | 图标表（:817 起，`ACCOUNT_WARNED` :817、`ACCOUNT_SUSPENDED` :822、`OPS_ANNOUNCEMENT` :863、兜底 :869）；`_typeLabel`（:872，OPS :905）；`_typeBody`（:911，OPS :959）；`_opsTitle` / `_opsBody`（:968-976）；点击（:311 OPS 特判，:334 其他走 `NotificationDeepLink.open`）；埋点映射（:270） | 加两类型 | 其他类型不变 |
| `lib/features/notify/domain/notification_item.dart` | `type` 为 String（:33）；`serverCopyTypes`（:25，后端拼文案的四种互动类型）；`title` / `body` 已解析 | 只补注释 | — |
| `lib/core/router/deep_link_routes.dart:62-90` | `pushPayloadToLocation` 固定目标 switch；无 targetRef 落通知中心 | 加 `PET_SLOT_*` → `/me/pet-slots` | — |
| `lib/core/router/app_router.dart` | `/me/pawcoin`（:1013）、`/me/pawcoin/recharge`（:1015）；带 query 示例 `/profile/health`（:868-874）；登录守卫前缀 `_controlledLocations`（:158，含 `/me`）；里程碑路由（:1212-1217） | 加 `/me/pet-slots` | — |
| `lib/core/network/api_paths.dart` | `base = '/api/v1'`（:5）；`petProfiles`（:156）、`petProfileMe`（:157） | 加 `petSlotsMe` / `petSlotRentals` / `petSlotRenewals` / `petSlotPurchase(token)` / `petActiveSelection` | — |
| `lib/core/network/problem_detail.dart` | `typeSlug`（:26）、`fromDioException`（:60） | `pet-read-only` / `pet-slot-rent-unavailable` / `pet-slot-renew-invalid` 分流 | — |
| `lib/features/profile/presentation/growth_archive_page.dart` | `_ArchiveBody`（:284）build（:426）`ListView`（:439）children 从 :444 起：`DiaryHeader`（:447-473）→ `RepurchaseTriggerCardV2`（:477）→ `_viewToggleRow`（:491）；健康 / 里程碑入口 :471-472 | :444 最前插 B4 横幅 | 非只读宠物零变化 |
| `lib/features/profile/presentation/widgets/diary_header.dart` | `DiaryHeader`（:30）；编辑按钮 `editProfileButton`（:120） | 只读时隐藏 | — |
| `lib/features/profile/presentation/health_list_page.dart` | 标题 `healthListTitle`（:136）；`healthAddTop`（:149）、`healthAddBottom`（:342）；分类卡（:262 起）副文为最近日期 / `healthCategoryEmpty` | E3 标题 / 锁 / 计数 | — |
| `lib/features/profile/presentation/milestone_list_page.dart` | 页面（:33），标题 `milestoneListTitleNamed`（:173-176）；未完成徽章 → 打卡抽屉 `showModalBottomSheet`（:656、:830） | 只读时改弹 E1、Header 锁标 | — |
| `lib/core/storage/prefs.dart:8` | `AppPrefs`，键 `petgo.<snake_case>` | 本 story 不新增键（当前宠物键由 1.5 定） | — |
| `lib/core/analytics/analytics.dart` | `capture`（:163，`scrub`）、`capturePurchase`（:214）、按钮白名单（:282）；`button_ids.dart:5` | 新事件用 `capture` 字面量 | — |
| `test/analytics/v112_events_test.dart` | 前缀表 `allowedPrefixes`（:217 起，已有 `pet_card_` :270 但无 `pet_slot_`）；后缀表（:346 起，有 `_shown` / `_tapped` / `_started` / `_succeeded`，**无 `_paid`**） | 加两前缀 | 不放宽后缀 |
| `lib/features/pet_slot/` | **不存在，需新建** | — | — |

### 待确认（默认按括号内做，Completion Notes 记一句）

1. **只读后的「续租」去向（AC5.3）**：PRD / 稿写「续租后立即恢复」，但架构 AD-6「已过期名额不可续，走新租」——宠物变只读恰恰是因为月租过期（默认：有 ACTIVE 月租 → D11；无 → D5 新租，数量默认 `shortBy`）。
2. **E3 分类卡计数（AC5.8）**：稿 E3 与对照表 E-3 显示「{n} catatan」，现状显示最近日期（默认按稿改为条数；「以 UI 稿为判断依据」）。
3. **对照表未列的 4 个键**：`petSlotStatusActive`（Aktif）、`petSlotHealthTitleNamed`、`petSlotQuantityTotal`、`petSlotRenewRowRange`（默认按上表提议值，交本地运营审核）。
4. **D-24 数量**：对照表写死「+1」，多租时改为 `{n}`。
5. **D-5 来源标签**：对照表「Level」，稿 D1 说明写「Dari level」（默认按对照表「Level」）。

### 与前后 story 的边界（本 story 不做）

- `petListProvider` / 切换器 / A4 抽屉本体与 A 组文案 → **1.5**（本 story 只接通「月租」与「Kelola」去向、补锁标）。
- 发 Diary / 打卡的宠物选择器里过滤只读宠物 → **3.3**；AI 问诊 / 兽医咨询选宠物与 H2 存档拦截 → **4.1**；KTP 只列正常宠物 → **4.2**。
- 升级庆祝页永久名额提示（K2 / K3）、名额管理页「永久名额 +1，下次续租默认少勾一个」文案 → **4.4**（本 story 的默认少勾靠后端 `renewableMax` 已生效）。
- 推送分类开关 → **④**。

### 项目规矩提醒

- 不整文件 `dart format`（只对齐改动行）；不写 golden；不改 `kApiBaseUrl` 默认值。
- 页面状态多态要全：加载中 / 失败（D15）/ 无月租（D1）/ 有月租（D2）/ 有只读（D3）/ 已满（D4）/ 他人设备上名额已变（409 后刷新）。
- 实现完在 **Android 模拟器**打开名额管理页给用户看（连 stag 后端；stag QRIS 不可用时用后台支付模拟器或 PawCoin 路径演示）。UI 还原度对 UI 稿逐屏打分，目标 ≥ 95%。

### 测试

**验证层级**
- 〔T0〕仓库测试目录与 features 平行、不带 `features/` 前缀（1.5 已用 `test/pet_slot/`）：下列 `test/features/pet_slot/*` 实际落在 `test/pet_slot/*`、`test/features/notify/*` 落在 `test/notify/*`；续租抽屉 / 改选抽屉用例并入 `test/pet_slot/pet_slot_page_test.dart`（经页面真实打开抽屉），`pet_readonly_block_test.dart` 另含 E2 / E3 页面接入用例。
- **L0（云端可跑）**：
  - `test/features/pet_slot/pet_slot_overview_test.dart`：`PetSlotOverview.fromJson`（构成、rentals 顺序、`daysLeft`）。
  - `test/features/pet_slot/pet_slot_page_test.dart`（widget）：D1 / D2 / D3 / D4 四态的关键文案与按钮可用性；D15 重试重取；`rentable = 0` 主按钮禁用。
  - `test/shared/widgets/pay_channel_picker_quantity_test.dart`：不传 `quantity` 时与现状一致（现有 picker 测试全绿即证明）；传入时步进器上下限、总价与 PawCoin 余额判断随数量变化。
  - `test/features/keepsake/keepsake_pay_flow_test.dart` 补：`onQrAborted` 被透传；不传时行为不变。
  - `test/features/pet_slot/pet_slot_renew_sheet_test.dart`：默认勾选 ≤ `renewableMax`；新到期 = 原到期 + days；全不勾禁用。
  - `test/features/pet_slot/active_selection_sheet_test.dart`：默认勾选 ACTIVE；勾满后其余禁用；未满 Simpan 禁用。
  - `test/features/pet_slot/pet_readonly_block_test.dart`：`handlePetReadOnly` 对 `pet-read-only` 返回 true 并弹 E1；`openPetSlotRenewal` 有 / 无 ACTIVE 月租分流。
  - `test/features/notify/notification_center_pet_slot_test.dart`：两类型显示后端 title / body、空串回退；`pushPayloadToLocation('PET_SLOT_EXPIRED', null) == '/me/pet-slots'`。
  - `test/analytics/v112_events_test.dart` 跑绿（新前缀已登记）；arb 键 id / en 齐全（现有 l10n 生成与 analyze 即覆盖）。
  - `flutter analyze` 绿。
- **L2（真机 / 模拟器，云端不跑，待本地验收）**：Android 模拟器演示 D2 / D5 / E1；PawCoin 租 1 个 → D9；续租两条 → D12；改选 → D14；只读宠物 Diary Tab 横幅 + 点健康「+」弹 E1；通知中心两类卡片点击直达名额管理页。
- 云端只跑 L0；Completion Notes 标「L2 待本地验收」。

### References

- [Source: _bmad-output/planning-artifacts/v1.4.0/epics-v1.4.0-multi-pet.md#Story 2.4、UX-DR4 / UX-DR5 / UX-DR11]
- [Source: _bmad-output/planning-artifacts/v1.4.0/architecture-v1.4.0-multi-pet-delta.md#AD-6、AD-8、AD-14、§5（App 状态单一来源 / 文案）]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物prd.md#3.2 名额管理页 / 只读状态 / 通知、§4 埋点]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/ui-1-4-0-multi-pet-integrated-v1.html#A4、B4、D1 ~ D15、E1 ~ E3、K1]
- [Source: _bmad-output/planning-artifacts/v1.4.0/多宠物/1-4-0多宠物-新增文案对照表.md#B、D、E 组]
- [Source: _bmad-output/planning-artifacts/v1.4.0/decision-log.md#② 第二轮拍板（D5 数量步进器 + 总价、D10b 仅名额流程补 SnackBar、E3 标题）、2026-10-15（D13 改选只在名额管理页）、第一轮（K1 读后端 title / body）]

### 拍板（2026-10-18，Dai；优先于本文件正文与「待确认」）

- P-5：只读横幅 / E1 拦截抽屉的「续租」按钮：有生效中月租 → 续租抽屉；没有 → 新租抽屉（数量默认 = 缺的名额数），按钮文案仍为「续租」（原「需拍板」按此关闭）。

## Dev Agent Record

### Agent Model Used

Claude（云端 headless session，bmad-dev-story 流程）

### Debug Log References

- 基线（改动前，`feat/1.4.0-multi-pet` @ 7388a1ca）：`flutter analyze` → No issues found；`flutter test` 基线 3493 通过 / 1 跳过（1.5 记录）。
- 首轮全量：唯一失败为 `test/l10n/microcopy_rules_test.dart` D-3（`petSlotSourceLevel` EN / ID 同为「Level」）→ 对照表 D-5 规定两语同写，按该测试的既定扩展方式把 `level` 登记进 `_sameInBothLocales`（带注释），未放宽判据。
- 改动后：`flutter analyze` → No issues found；`flutter test` 全量 → 3550 通过 / 1 跳过 / 0 失败（+57）。
- code review 修复后：`flutter analyze` → **No issues found**；`flutter test` 全量 → **3557 通过 / 1 跳过 / 0 失败**（再 +7）。

### Completion Notes List

**L1/L2 待本地验收**：Android 模拟器演示 D2 / D5 / E1（连 stag 后端 + 真登录）、PawCoin 租 1 个 → D9、续租两条 → D12、改选 → D14、只读宠物 Diary Tab 横幅 + 健康「+」弹 E1、通知中心两类卡片点击直达名额管理页，以及 UI 稿逐屏还原度打分（≥ 95%）——云端 headless 跑不了，需本地验收。本 story **只 App，后端零改动**，无 L1 测试；**无 Flyway 迁移**（`check-flyway-versions.sh origin/main` OK，241 个迁移）。

**T0 核对结果**（与 1.5 / 2.1 / 2.2 / 2.3 已提交代码）：
- 后端端点 / DTO 与本文件一致：`GET /api/v1/pet-slots/me`（`PetSlotOverview` 1.4 的 11 个字段 + 2.2 末尾追加 `readOnlyCount / shortBy / renewableMax / rentals[]{token,index,source,startsAt,expiresAt,daysLeft}`）；`POST /pet-slots/me/rentals` `{quantity, channel}`、`POST /pet-slots/me/renewals` `{rentalTokens, channel}`，均强制 `Idempotency-Key` 头（≤ 80 字符，缺失 422），响应与 `KeepsakePurchaseResponse` 同形（`unlocked / payment / payload / purchaseToken`）；`GET /pet-slots/me/purchases/{purchaseToken}` → `{status, expiresAt}`（`expiresAt` 仅 PAID 时有）；`PUT /pet-profiles/me/active-selection` `{petIds}` → 最新宠物列表。channel 线格式 `PAWCOIN` / `QRIS`（复用 `HdPayChannel.wire`）。
- ProblemDetail：`pet-read-only`（扩展成员 `petId`）、`pet-slot-rent-unavailable`（`rentable` [+ `renewableMax`]）、`pet-slot-renew-invalid`、`pet-selection-not-needed`；购买状态除 PENDING / PAID / FAILED / EXPIRED 外还有 `ORPHAN_PAID` / `OVERPAID`（2.2 复审新增）。
- App 侧：`petListProvider` / `currentPetIdProvider` / `effectivePetIdProvider` / `PetSwitcher`（锁标已做，AC5.1 只核对）/ 「我的」页 Hewanku 锁标与「Kelola ›」（已指向 `/me/pet-slots`）/ A4 抽屉 / `/me/pet-slots` 占位页（同路由同文件替换）与本文件一致；`PetSlotOverview` 1.5 建在 `data/pet_slot_repository.dart`（注释要求 2.4 原处追加），故未迁到 `domain/`；`v112_events_test.dart` 1.5 已入表 `'pet_'`（覆盖本 story 四个事件）。本文件已就 T1 / AC7.5 / 测试路径加〔T0〕标注。

**实现要点**
- 数据层：`ApiPaths.petSlotRentals / petSlotRenewals / petSlotPurchase(token) / petActiveSelection`；`PetSlotRepository.rent / renew / purchaseStatus / changeSelection`；`newPetSlotIdempotencyKey()`（`Random.secure` 128 bit，`petslot-` + 32 hex，每次点主按钮生成一次）；`PetSlotOverview` 追加四字段（均有默认值，1.5 构造调用零改动）+ `hasRentals` / `needsSelection`；`ProblemDetail.extensions`（标准字段外的顶层键，默认空 map，既有调用零影响）。
- 名额管理页 `PetSlotPage(action)`：进页 post-frame 失效摘要 + 宠物列表；名额卡（大数字 = `petCount`，后缀 D-2，5 格：正常宠物在前、只读在后依次占格，来源按 默认 → 等级 → 月租；已拥有未占用「Kosong · 来源」+ 加号；其余「Bisa disewa」+ 锁）→ Slot sewa（≤ 3 天金色 D-13）→ Hewanku（状态胶囊 Aktif / 锁 Hanya-baca；`needsSelection` 时「Ubah pilihan ›」+ D-16）→ D-14 → 吸底主按钮（`rentable = 0` 置灰 D-18）+ 有月租时「Perpanjang」；D15 整页 `EmptyState(cloud_off)` + `commonRetry` 重取两 provider。`?action=rent|renew`：拿到最新摘要后自动弹 D5 / D11（P-5：renew 无生效中月租 → D5、数量默认 `shortBy`）。
- `PayChannelPicker`：可选 `PayQuantityConfig quantity`（步进器 + 「{n} × {price}」总价行）与 `itemsArea / itemsTotal / itemsLineLabel`（续租勾选列表）；给了后 PawCoin 余额判断 / 扣币数 / QRIS 金额 / 主按钮全部按总价；都不传时 build 路径与改动前相同（5 处既有调用零改动，其测试全绿）。
- `runKeepsakePurchase` 加可选 `onQrAborted`（原样透传 `showQrPaymentSheet(onAborted:)`，4 处既有调用不传）。
- 租用 / 续租 `runPetSlotRent / runPetSlotRenew`（`pet_slot_purchase_flow.dart`）：`onChannelConfirmed` 生成幂等键 + `pet_slot_rent_started`；`pollPaid` PAID → true、EXPIRED / FAILED → `QrPaymentAborted('EXPIRED' / 'FAILED')`、ORPHAN_PAID / OVERPAID → `QrPaymentAborted('PAID_NEEDS_SUPPORT')`；`purchasePurpose: 'PET_SLOT_RENT'`、`cashPriceIdr = 单价 × 个数`；结束后**不论成败**失效摘要 + 宠物列表（409 `pet-slot-rent-unavailable` / `pet-slot-renew-invalid` 由 flow 报 D10 文案，这里负责刷新）；成功 `pet_slot_rent_paid` + D9 / D12 SnackBar（日期取购买查询 `expiresAt`，PawCoin 成交后补查一次，取不到按今天 + days 近似）；D10b 过期 SnackBar 只在名额流程补（通用面板未改）。
- 续租抽屉 `PetSlotRenewPaySheet`：默认勾前 `renewableMax` 个生效中月租，超上限的行可见不可勾，全不勾主按钮禁用；行「{原到期 d MMM} → {新到期 d MMM yyyy}」，新到期 = 原到期 + days。
- 改选 `ActiveSelectionSheet`：把手 → 标题 D-29 → D-30 → 勾选行（头像 / 名字 / 物种 · 年龄）→「{n} / {total} dipilih」+ Simpan；默认勾 ACTIVE；勾满后其余不可勾；未选满禁用；成功关抽屉 + 失效 + Toast D-32；失败抽屉保持 + 顶部 Toast（`petProfileSaveFailed` + `commonRetry` 动作）。
- 只读：`pet_readonly_block.dart`（`currentPetIfLoaded`（只在 provider 已存在时读，不额外发请求）/ `guardPetWritable` / `showPetReadonlyBlockSheet`（E1，`showConfirmSheet` 紫锁，不放改选入口）/ `handlePetReadOnly`（409 → E1 + 失效宠物列表，返回 bool）/ `openPetSlotRenewal`（`pet_readonly_renew_tapped {source}` + push `/me/pet-slots?action=renew`））；`PetReadonlyBanner`（B4，挂载一次报 `pet_readonly_banner_shown`，属性为空）。
- 接入点：Diary `_ArchiveBody`（切换器之后、`DiaryHeader` 之前插 B4；`DiaryHeader.hideEditButton` 隐藏铅笔，访客态 `readOnly` 语义不变）；档案编辑页保存（前端拦 + 409 兜底）；健康记录页（标题多宠带名、只读「+」/「Tambah catatan」带锁、新增类 `_openForm` 拦（含分类卡预选、`?add=` 预弹）；已有记录可打开查看，表单保存 / 删除时拦；表单保存 409 兜底）；里程碑页（Header 锁标、未完成徽章改弹 E1）；Kenali Hewanmu 付费项（KTP 选卡款建卡、Tailsonality 无结果开测 / 结果页空态开测 / 重测、护照快照 `_buy`、登机牌 `_buy`）；`runKeepsakePurchase` 的 409 `pet-read-only` 分支（护照 / 登机牌 / Tailsonality 解锁的后端兜底）。发 Diary / 打卡未接（3.3）。
- 通知中心（K1）：图标表 `PET_SLOT_EXPIRING` 金色 `hourglass_bottom_rounded` / `PET_SLOT_EXPIRED` coral `lock_clock_rounded`；`_typeLabel / _typeBody` 两类型与 OPS 共用 `_storedTitle / _storedBody`（原 `_opsTitle / _opsBody` 改名，trim 空回退 `notifyTypeSystem / notifyBodySystem`；`_serverTitle` 名字已被「后端文案条目」占用，故用 `_stored*`），注释措辞已更新；埋点映射显式登记两类型；`DeepLinkRoutes.pushPayloadToLocation` 固定目标 switch 加两类型 → `/me/pet-slots`（在无 targetRef 短路之前，推送点开走同一映射）；`notification_item.dart` 类型注释补两类型。
- 文案：AC8 表 42 键 + 3 个额外键 `petSlotPaymentProcessing`（见待确认 6）、`petSlotRentOnlyN` / `petSlotRentPendingPayment`（code review #3，见待确认 12），EN / ID 两份（仓库只有这两个 arb），已 `flutter gen-l10n`；价格统一 `formatIdrAmount`，日期 `formatDayMonthYear / formatDayMonth`（`d MMM yyyy` 按 App 语言）。

**埋点口径名 → 实际名**：`pet_slot_rent_started`（同名，`{count, is_renewal, channel}`，channel = `pawcoin` / `qris`）；PRD `pet_slot_rent_paid` → **`pet_slot_rent_paid`**（后缀白名单无 `_paid`）；`pet_readonly_banner_shown`（同名，无属性）；`pet_readonly_renew_tapped`（同名，`{source: banner | block_sheet}`）。均经 `Analytics.capture`、无宠物名。

**L0 测试（均已跑、全绿）**
- 新增：`test/pet_slot/pet_slot_overview_test.dart`（11）、`test/pet_slot/pet_slot_page_test.dart`（25，含 code review 补的幂等键 4 例 + rentable 1 提示：D1 / 价格不写死 / D2 / D3 / D4 / D15；D5 PawCoin + 埋点 / 步进器上下限 / D10b / D10 / OVERPAID / QRIS 到账 / 409 rent-unavailable 刷新；续租默认勾选 + 一次付款 + D12 / 超上限不可勾 + 全不勾禁用 / 409 renew-invalid；`?action=` 三分流；改选成功 / 失败）、`test/shared/pay_channel_picker_quantity_test.dart`（6，含 PawCoin 超余额回落 2 例）、`test/keepsake/keepsake_pay_flow_test.dart`（4：onQrAborted 透传 / 不传不变 / 409 pet-read-only → E1 / 余额不足照旧）、`test/pet_slot/pet_readonly_block_test.dart`（12：guard / E1 两按钮 / handlePetReadOnly / B4 横幅续租去向；E3 标题 / 分类卡仍为日期 / 锁 / 拦截 / 已有记录可看、保存删除才拦；E2 锁标 / 徽章拦截 / 正常宠物不变）、`test/notify/notification_center_pet_slot_test.dart`（4），共用桩 `test/pet_slot/pet_slot_fakes.dart`。
- 补：`test/profile/growth_archive_state_branch_test.dart`（+2：B4 只读横幅在页头前 + 铅笔隐藏 + 埋点无宠物名 / 正常宠物零变化）。
- 改：`test/l10n/microcopy_rules_test.dart`（`_sameInBothLocales` 登记 `level`，见 Debug Log）。
- `test/analytics/v112_events_test.dart`、`test/l10n/*`、`test/pet_slot/add_pet_flow_test.dart` 及既有 picker / 通知 / 健康 / 里程碑 / 付费流程测试全绿；全量 3557 / 1 skip / 0 fail（code review 修复后）。
- L1：无（本 story 不碰后端）。

**与本文件文字的偏差**
1. `PetSlotOverview` 留在 `data/pet_slot_repository.dart`（1.5 现状 + 其注释），`PetSlotRental` / `PetSlotPurchaseStatus` 在 `domain/pet_slot_purchase.dart`（T1 写三者都在 `domain/`）。
2. 测试路径为 `test/pet_slot/*` / `test/notify/*`（仓库惯例，非 `test/features/*`）；续租 / 改选抽屉用例并入页面测试。
3. AC7.5 不改前缀表（1.5 的 `'pet_'` 已覆盖）。
4. `handlePetReadOnly` 返回同步 `bool`，E1 不 await（调用方据返回值短路，不阻塞）；名字取不到（列表未加载）时 E1 用空名字照弹。
5. `openPetSlotRenewal` 的 D11 / D5 分流放在名额管理页拿到最新摘要之后做（`?action=renew`），而不是 helper 内先取摘要——结果与 AC5.3 相同，且不会用到过期缓存。
6. 名额卡大数字取 `petCount`（UI 稿 D3「3 / 2 slot dipakai」= 宠物数 / 名额；D4 稿件 4 只却写 5/5 视为稿件笔误）。
7. 已拥有未占用格文案「Kosong · {来源}」（稿 D4「Kosong」下带「Sewa」来源）。
8. B4 横幅位于切换器之后、`DiaryHeader`（含标题行）之前；稿 E1 画面里标题行在横幅之上，但标题行属于 `DiaryHeader`，按 AC5.2「`DiaryHeader` 之前」实现。
9. 健康记录页：已有记录可打开查看，保存 / 删除时才弹 E1（code review #4）；E3 分类卡「{n} catatan」**未采用**，保持最近日期（code review #5，见待确认 2），`petReadonlyHealthCount` 键已加但暂未使用。
10. `runKeepsakePurchase` 新增 409 `pet-read-only` 分支（AC5.6 后端兜底的统一落点），对 4 处既有调用而言只改变该新错误类型的提示（原会报「支付失败」）。
11. 改选失败提示用 `petProfileSaveFailed`（「Gagal menyimpan, silakan coba lagi」）+ `commonRetry` 动作——D10 原文是「支付失败」，用在改选上语义不对。

### code review 结果（2026-10-18，均已修复）

1. **CONFIRMED · 重复扣款 / 重复出码**：每次确认都新生成 `Idempotency-Key`，重试永远不重放。→ 新增 `PetSlotAttemptStore`（`petSlotAttemptStoreProvider`）：按「种类 + 数量（或排序后的名额 token）+ 渠道」记键，跨重试沿用；终态（PAID 成交 / EXPIRED / FAILED / ORPHAN_PAID / OVERPAID）或 4xx 业务拒绝（408 / 429 除外）后清掉；网络错误 / 5xx / 关面板未付保留。测试：网络超时后重试同键、成交后再买新键、换数量新键、QRIS 关面板再付同键、409 后新键。
2. **CONFIRMED · 选 PawCoin 后金额涨过余额仍按 PawCoin 确认**：`PayChannelPicker` 显式选了 PawCoin 但 `!enoughCoin` → 回落 QRIS（与界面一致；减回余额内恢复 PawCoin）。不传新参数的既有调用里该分支只会在价格重取变高时触发，行为同样与界面一致。测试 +2。
3. **PLAUSIBLE · 待付单占位导致 409 一直报「支付失败」**：`runKeepsakePurchase` 加可选 `onProblem`（返回 true = 调用方已提示；既有调用不传）；名额新租读 409 扩展成员 `rentable`：> 0 →「只能租 {n} 个」，= 0 →「还有未完成的名额付款」；配合 #1，409 后换新键、同意图网络重试重放。测试 +1（改写 1）。
4. **PLAUSIBLE · 只读宠物点已有健康记录被 E1 挡住查看**：`_openForm` 只对新增拦；表单 `_save` / `_delete` 入口拦。测试 +1。
5. **单宠回归 · 分类卡改成条数**：恢复「最近日期」原显示（代码与改动前一致），条数口径转待确认 2。测试改写 1。
6. **Minor · 页面销毁后 `ref.invalidate` 抛错**：流程结束的刷新改为 `context.mounted` 时才做（页面已走则下次进页 `_enter` 自会重取）；其余异步后用到的依赖均在开头取好。

### 待确认

1. **只读后的「续租」去向**（AC5.3 / P-5）：按拍板——有生效中月租 → D11；无 → D5 新租、数量默认 `shortBy`。
2. **E3 分类卡计数**：UI 稿 E3 / 对照表 E-3 为「{n} catatan」，但改了会让单宠 ACTIVE 用户也变样 → 按 code review 保持现状（最近日期），文案键 `petReadonlyHealthCount` 已备；是否只对只读 / 多宠页改为条数，请产品确认。
10. **AD-8 与本文件 AC5.5 的冲突**：AD-8「查看永不受限」；本文件把 KTP 创建 / Tailsonality 开测 / 护照快照 / 登机牌购买入口列为 E1 拦截——这些都是写 / 付费动作（查看已有卡 / 结果 / 护照 / 登机牌不拦），按本文件保留；健康记录「点已有记录」已改为可看（拦在保存 / 删除）。
11. **幂等键存放**：`petSlotAttemptStoreProvider` 为进程内、非 autoDispose；换账号不清（键是随机串，后端按账号隔离幂等键，旧键在新账号下只是一个新键）。
12. **409 `pet-slot-rent-unavailable` 两条新文案**（`petSlotRentOnlyN` / `petSlotRentPendingPayment`，EN + ID，本 agent 拟稿，**不在对照表**），待产品定稿。只提示、不自动下调步进器上限（页面的 `rentable` 不含待付单，记住后端值会在付款窗过后变成过期限制）。
3. **对照表未列的 4 个键**（`petSlotStatusActive` / `petSlotHealthTitleNamed` / `petSlotQuantityTotal` / `petSlotRenewRowRange`）按本文件提议值，交本地运营审核。
4. **D-24 数量**：`{n}`（多租时不写死 +1）。
5. **D-5 来源标签**：按对照表「Level」（稿 D1 说明为「Dari level」）。
6. **ORPHAN_PAID / OVERPAID 的 App 文案**（2.2 待确认移交）：保守按「付款已收到、处理中 / 请联系客服」新增 `petSlotPaymentProcessing`（ID「Pembayaran diterima dan sedang diproses. Hubungi CS jika slot belum bertambah.」/ EN「Payment received and being processed. Contact support if your slot doesn't appear.」，本 agent 拟稿，**不在对照表**），只在 QRIS 轮询到这两态时出 SnackBar；PawCoin 路径后端会整笔回滚 409，不会出现这两态。待产品定稿。
7. **SnackBar 日期**：D9 / D12 的 `{date}` 用设备本地时区格式化 `expiresAt`（后端 2.3 通知按 WIB）；跨日边界时两者可能差一天，L2 时留意。
8. **E1 名字回落**：409 兜底时宠物列表未加载 → E1 标题为「Slot  sudah berakhir」（名字空），是否需要换无名字的通用文案请确认。
9. **B4 横幅曝光口径**：每次横幅挂载报一次（切宠物 / 重进 Diary 页面重建时）；IndexedStack 保活下仅切底部 Tab 回来不重报。

### File List

新增：
- `petgo_app/lib/features/pet_slot/domain/pet_slot_purchase.dart`
- `petgo_app/lib/features/pet_slot/presentation/pet_slot_purchase_flow.dart`
- `petgo_app/lib/features/pet_slot/presentation/active_selection_sheet.dart`
- `petgo_app/lib/features/pet_slot/presentation/pet_slot_pet_row.dart`
- `petgo_app/lib/features/pet_slot/presentation/pet_readonly_block.dart`
- `petgo_app/lib/features/pet_slot/presentation/pet_readonly_banner.dart`
- `petgo_app/test/pet_slot/pet_slot_fakes.dart`
- `petgo_app/test/pet_slot/pet_slot_overview_test.dart`
- `petgo_app/test/pet_slot/pet_slot_page_test.dart`
- `petgo_app/test/pet_slot/pet_readonly_block_test.dart`
- `petgo_app/test/shared/pay_channel_picker_quantity_test.dart`
- `petgo_app/test/keepsake/keepsake_pay_flow_test.dart`
- `petgo_app/test/notify/notification_center_pet_slot_test.dart`

修改（lib）：
- `petgo_app/lib/core/network/api_paths.dart`、`petgo_app/lib/core/network/problem_detail.dart`
- `petgo_app/lib/core/router/app_router.dart`、`petgo_app/lib/core/router/deep_link_routes.dart`
- `petgo_app/lib/shared/widgets/pay_channel_picker.dart`
- `petgo_app/lib/features/keepsake/presentation/keepsake_pay_flow.dart`
- `petgo_app/lib/features/pet_slot/data/pet_slot_repository.dart`、`.../presentation/pet_slot_page.dart`（占位页替换）、`.../presentation/add_pet_flow.dart`
- `petgo_app/lib/features/notify/presentation/notification_center_page.dart`、`petgo_app/lib/features/notify/domain/notification_item.dart`
- `petgo_app/lib/features/profile/presentation/{growth_archive_page,health_list_page,milestone_list_page,pet_profile_edit_page,pet_insights_page,id_card_page}.dart`、`.../widgets/diary_header.dart`
- `petgo_app/lib/features/pet_passport/presentation/pet_passport_page.dart`、`petgo_app/lib/features/boarding_pass/presentation/boarding_pass_detail_page.dart`
- `petgo_app/lib/features/tailsonality/presentation/{tailsonality_results_page,tailsonality_retake}.dart`
- `petgo_app/lib/l10n/app_en.arb`、`petgo_app/lib/l10n/app_id.arb`

修改（test）：
- `petgo_app/test/profile/growth_archive_state_branch_test.dart`（+2 用例）
- `petgo_app/test/l10n/microcopy_rules_test.dart`（同形词表登记 `level`）

文档：
- `_bmad-output/implementation-artifacts/v1.4.0/2-4-app名额管理-租用续租与只读体验.md`（〔T0〕标注、状态、任务勾选、Dev Agent Record）

### Change Log

- 2026-10-18（3-3 提交后补正）：埋点名改回 PRD §4 原名 `pet_slot_rent_paid`（原实现为迁就命名守卫改成 `_succeeded`，与「事件名照 PRD §4」不符）；守卫后缀表按 `_played` / `_published` 先例登记 `_paid`，正则不变。上文涉及该事件名处已同步。

- 2026-10-18：code review 修复 6 项（幂等键跨重试、PawCoin 超余额回落 QRIS、409 rent-unavailable 具体提示、只读健康记录可查看、分类卡恢复最近日期、销毁后不刷新）。analyze 0 issue；test 3557 / 1 skip / 0 fail；仍 review（L1/L2 待本地验收）。
- 2026-10-18：实现 Story 2.4（名额管理页 D1 ~ D4 / D15、`PayChannelPicker` 数量步进器 / 条目区、租用 D5 ~ D10b、续租 D11 / D12、改选 D13 / D14、只读 B4 / E1 ~ E3 与各写入口拦截、通知中心 K1 两类到期 + 深链、四个埋点、43 个新文案键）。L0 绿（analyze 0 issue；test 3550 / 1 skip / 0 fail），Status → review；L1/L2 待本地验收（L2 模拟器演示与还原度打分）。
