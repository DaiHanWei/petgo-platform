# Story 2.4: App——名额管理、租用续租与只读体验

Status: ready-for-dev

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
2. `pet_slot_rent_succeeded`（**PRD 口径名 `pet_slot_rent_paid`**：仓库后缀白名单无 `_paid`，按「改名迁就规则」改为 `_succeeded`，与 `toko_order_payment_succeeded` 同理；Completion Notes 写口径名 → 实际名映射）：发放成功时，属性同上。
3. `pet_readonly_banner_shown`：B4 横幅首次出现（每次进入该宠物 Diary Tab 一次），属性无 PII（不带宠物名）。
4. `pet_readonly_renew_tapped`：B4 / E1「续租」点击，属性 `source`（`banner` / `block_sheet`）。
5. `test/analytics/v112_events_test.dart` 的 `allowedPrefixes`（:217 起）加 `'pet_slot_'`、`'pet_readonly_'`（写注释说明是新模块入表，照现有条目格式；若 1.5 已为 `pet_slot_full_shown` 加过 `pet_slot_` 则只补 `pet_readonly_`）。事件经 `Analytics.capture`，属性走 `scrub`。

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

- [ ] **T0 开工核对**：1.5 / 2.1 / 2.2 / 2.3 已合入；读 1.5 的 `petListProvider` / `currentPetIdProvider` / 切换器 / A4 占位实际实现
- [ ] **T1** `features/pet_slot/`：`data/pet_slot_repository.dart`（四个接口 + `ApiPaths` 常量）、`domain/`（`PetSlotOverview`、`PetSlotRental`、`PetSlotPurchaseStatus`）、`petSlotOverviewProvider`
- [ ] **T2** 名额管理页 D1 ~ D4 / D15 + 路由 `/me/pet-slots` + 接通 A1 / A4（AC1）
- [ ] **T3** `PayChannelPicker` 可选数量 / 自定义条目区；`runKeepsakePurchase` 可选 `onQrAborted`（AC2.1 / AC2.5，其他调用点零改动）
- [ ] **T4** 租用流程 D5 ~ D10b、续租 D11 / D12（AC2 / AC3）
- [ ] **T5** 改选抽屉 D13 / D14（AC4）
- [ ] **T6** 只读：锁标、B4 横幅、`openPetSlotRenewal`、E1 `showPetReadonlyBlockSheet` + `handlePetReadOnly`、各入口接入、E2 / E3（AC5）
- [ ] **T7** 通知中心 K1 + 深链（AC6）
- [ ] **T8** 埋点 + 命名守卫前缀（AC7）；arb 两份（AC8）
- [ ] **T9** `flutter analyze` + 相关测试；Android 模拟器演示 D2 / D5 / E1；Completion Notes 标「L2 待本地验收」（云端跑不了模拟器）

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

### Debug Log References

### Completion Notes List

### File List

### Change Log
