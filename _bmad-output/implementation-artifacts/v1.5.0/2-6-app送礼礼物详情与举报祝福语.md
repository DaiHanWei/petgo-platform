# Story 2.6: App 送礼礼物详情与举报祝福语（H1 ~ H6）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-02 访客可看未拆含祝福语；举报复用现有举报后台）> PRD A §3.5（FR-191）> 架构 D-8、§4.1 > App 稿 H 泳道。
> 前置：1-4、1-5、2-4、2-5。
> 范围层：**只 App**。

## Story

As a 宠物主人 / 访客,
I want 点一份礼物看到它的详情和祝福语，觉得祝福语不妥时可以举报,
so that 每份礼物都能回看，问题内容有出口。

## Acceptance Criteria

### AC1 — 详情抽屉 H1（主人）/ H2（访客） `[L0]` `[L2]`
1. `showGiftOrderDetailSheet(context, orderToken)`：底部抽屉（遮罩 + 上圆角），调 `GET /api/v1/gift-orders/{token}`；加载中骨架、失败可重试。
2. 内容：礼物浮在打开的礼盒上方、「{hadiah} untuk {petName}」（新文案）、「Diterima {tanggal}」（新文案，按语言格式化日期）、数量、祝福语、送礼人「dari {nama} · bersama {hewan}」（「bersama {hewan}」新文案，`petNames` 用「, 」连接，空则不显示这段）、「Lihat profil {nama}」（复用 2-5 key）→ `/users/{userId}`。
3. 访客与主人同一抽屉；访客也能看主人未拆的礼物与祝福语，访客侧没有任何「未拆」字样。
4. 右上「⋯」→「Laporkan ucapan」（复用 2-5 key）：仅 `canReport = true` 时有「⋯」。

### AC2 — 变体 `[L0]`
- H3：`messageHidden = true` → 祝福语换成「Ucapan ini disembunyikan karena melanggar pedoman komunitas.」（2-5 已加 key），礼物照常。
- H4：送礼人已注销 →「dari Pengguna terhapus」（复用 `feedDeletedUser`），无宠物行、无「Lihat profil」。
- 无祝福语：祝福语区不显示、无「⋯」。

### AC3 — 举报 H5 / H6 `[L0]` `[L1]`
1. 原样复用 `A/features/content/presentation/report_sheet.dart` 的 `showReportSheet(... submit: ...)`（同场所举报的做法），**文案一字不改**（`reportTitle`、副标题、五个理由、`reportSubmit`、`commonCancel`、成功态 `reportDoneTitle` / `reportDoneBody` / `commonClose`）。
2. `submit` = `POST /api/v1/gift-orders/{token}/message-reports {reasonType: reason.wire}`；422 `gift-message-not-reportable` → 通用失败 toast（理论上 UI 已不展示入口）。
3. 举报成功后本地不隐藏祝福语（以后台处理为准）。

### AC4 — 文案 `[L0]`
新增：`{hadiah} untuk {petName}` / `{gift} for {petName}`、`Diterima {tanggal}` / `Received {date}`、`bersama {hewan}` / `with {pet}`。

### AC5 — 测试 `[L0]`
Widget：H1 / H2 字段渲染；H3 隐藏文案；H4 注销无按钮；`canReport = false` 无「⋯」；举报 submit 调到正确路径（mock 仓库）。

## Tasks / Subtasks

- [x] 仓库方法 `fetchGiftOrderDetail`、`reportGiftMessage`
- [x] 详情抽屉与变体（AC1、AC2）
- [x] 举报接线（AC3）
- [x] ARB + 测试（AC4、AC5）

## Dev Notes

- `report_sheet.dart` 的 `submit` 参数签名：`Future<void> Function(ReportReason reason)`（场所举报已这样用）。
- 2-4 / 2-5 的「点卡片 / 已拆过」统一调本 story 的 `showGiftOrderDetailSheet`。
- H5 宠物名片「Hadiah」列改个数是后端 H5 页面（1-4 AC5），App 无改动。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/`
- L1（本地）：真后端举报落库、后台隐藏后详情显示隐藏文案
- L2（本地）：H1 ~ H6 还原度 ≥ 95%

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：L1 真后端举报落库（`gift_message_reports`）、后台隐藏后详情显示隐藏文案；L2 H1 ~ H6 还原度（抽屉圆角 24、礼物浮在打开的礼盒上方、日期格式）。
- L0：`flutter analyze` 无 error / warning（存量弃用 info 不计）；`flutter test test/gift test/l10n` 全绿（新增 `test/gift/gift_order_detail_sheet_test.dart` 8 条：H1 / H2 字段、数量 1 不显示、无宠物不显示「bersama」、H3 隐藏文案、H4 注销无按钮、无祝福语无 ⋯、加载失败重试、举报提交路径与成功态、举报 422 失败 toast）。测试放 `test/gift/`。
- 实现：`gift_order_detail_sheet.dart` 的 `showGiftOrderDetailSheet(context, orderToken)`（底部抽屉、遮罩 + 上圆角 24；骨架 → 内容 / 失败 `giftLoadFailed` + `commonRetry`）。内容：「{hadiah} untuk {petName}」+「Diterima {tanggal}」（`formatBirthday` 即 `d MMMM yyyy` 按语言）+ 礼物 + 数量（>1 显示「×N」）+ 祝福语 / H3 隐藏文案 + 送礼人「dari {nama} · bersama {hewan}」（`petNames` 用「, 」连接，空则省略该段；注销显示 `feedDeletedUser`、无宠物段）+「Lihat profil {nama}」（注销 / 无 userId 不显示，先关抽屉再 push `/users/{id}`）。访客 / 主人同一抽屉，无「未拆」字样。
- 「⋯」仅 `canReport = true` 显示 →「Laporkan ucapan」（复用 2-5 key）→ **原样复用** `report_sheet.dart` 的 `openReportSheet(submit: ...)`（与场所举报同做法，抽屉文案一字不改）；`submit` = `POST /api/v1/gift-orders/{token}/message-reports {reasonType}`；失败（含 422 `gift-message-not-reportable`）→ 通用失败 toast（复用 2-5 `giftOpenFailed`），抽屉留在表单态；成功后本地不隐藏祝福语。
- 接线：`gift_order_actions.dart` 的 `showGiftOrderDetail` / `reportGiftMessage` 由空实现改为调本抽屉 / 举报（2-4 点卡片、2-5「⋯」与 `open=latest` 无未拆分支随之生效）。
- 「礼物浮在打开的礼盒上方」：打开态礼盒素材未交付（仅有合盖 `gift_box_closed`），本版只放礼物图，正式素材到后补；L2 核对。
- 文案：`giftDetailTitle`、`giftDetailReceivedOn`、`giftDetailWithPets` 逐字取 App 稿新文案表；另加 `giftDetailQuantity`「×{n}」（数量，稿面写法）。
- 仓库：`fetchGiftOrderDetail(token)`、`reportGiftMessage(token, reasonWire)`。

### File List

- `petgo_app/lib/features/gift/presentation/gift_order_detail_sheet.dart`（新）
- `petgo_app/lib/features/gift/presentation/gift_order_actions.dart`、`lib/features/gift/data/gift_repository.dart`（改）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（改）
- `petgo_app/test/gift/gift_order_detail_sheet_test.dart`（新）
