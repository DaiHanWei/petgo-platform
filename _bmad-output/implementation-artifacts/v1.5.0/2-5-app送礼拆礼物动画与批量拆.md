# Story 2.5: App 送礼拆礼物动画与批量拆（G1 ~ G6）

Status: review

> 版本 v1.5.0 · 会话 ② gift-pack · 分支 `feat/1.5.0-gift-pack`
> 口径优先级：`decision-log.md`（11-02 收礼通知直接拆；11-13 设计师新稿 + riv 实测）> PRD A §3.4 / §3.8（FR-190）> 架构 D-7、D-9、D-14 > App 稿 G 泳道、F 泳道主路径注释。
> 前置：1-4（open 接口）、2-3（rive 文件与礼盒素材已入库）、2-4。
> 范围层：**只 App**。

## Story

As a 宠物主人,
I want 点开礼盒、看礼物升起和祝福语，多份礼物也能一次拆完,
so that 收礼是一次惊喜。

## Acceptance Criteria

### AC1 — 单个拆 G1 → G2 / G3 `[L0]` `[L2]`
1. 新页 `A/features/gift/presentation/gift_reveal_page.dart`：浅色客厅插画背景 + `assets/rive/receive_gift.riv`（画板 `GiftReveal`、状态机 `GiftSM`，`Fit.cover`）。待机：礼盒轻晃（G1）；左上白色圆形关闭（不拆，回来源页）；底部「Ketuk untuk membuka」（新文案）。
2. 点礼盒 → App 触发 `GiftVM.open`（**只触发一次**：首次点击后置标志，连点忽略；文件里的 `TapBox` 若也会触发 open，以先到为准、不重复调接口）→ 同时调 `POST /pets/{petId}/gift-orders/open {orderTokens: [token]}`（接口失败：动画照播，结束后显示 toast 通用错误并停在 G2 文字区，返回时列表刷新以服务端为准）。
3. 礼物图：设置 `GiftVM.giftImage`（现文件没有该属性，传了不生效、显示小熊属预期，Completion Notes 记一句）。
4. 结束：Rive 事件 `revealDone` 或 2.5 秒兜底 → 淡入：顶部送礼人头像 +「{nama} mengirim {n} {hadiah} untuk {petName}」（新文案）；礼物下方祝福语（`message`；`messageHidden` 时显示「Ucapan ini disembunyikan karena melanggar pedoman komunitas.」）；「Kamu dapat {n} EXP」（`ownerExp`，0 不显示，复用 2-3 的 key）；紫色主按钮「Lanjut」（复用 `onboardingContinue`）；紫字「Lihat profil {nama}」（新文案，送礼人已注销时不显示）→ `/users/{userId}`。
5. 右上「⋯」→「Laporkan ucapan」→ 2-6 的举报抽屉（只在 `canReport = true` 时出现；G3 无祝福语时既无祝福语区也无「⋯」，不留空白）。
6. 减弱动态效果 / Rive 加载失败 → 直接显示 G2 终态（仍要调 open 接口）。

### AC2 — 批量拆 G4 ~ G6 `[L0]` `[L2]`
1. 礼物页「Buka Semua」→ 同一个礼盒动画，点开后调 `open {all: true}`。
2. 汇总 G4：画面压淡；「Kamu menerima {n} hadiah」（新文案，`n` = 笔数）；每格：礼物图 + 右下送礼人头像 +「×N 礼物名」+ 灰字「dari 谁」；「Kamu dapat {n} EXP」= `totalOwnerExp`。
3. 每页 9 格，超过 9 份分页（G5，圆点可左右滑）。
4. 有祝福语（`message != null`）的笔数 > 0 时按钮「Berikutnya」（新文案）→ G6：同 G2 版式逐条轮播**只带祝福语的那几笔**，圆点为进度，最后一条按钮变「Lanjut」；都没有祝福语则汇总页直接「Lanjut」。
5. 「Lanjut」回礼物页并刷新（未拆筛选随之清空）。

### AC3 — 收礼通知点开 `[L0]`（D-9）
1. `A/core/router/deep_link_routes.dart` 的 `PET_GIFT_RECEIVED`：`targetRef` 是数字 → `/pets/$id/gifts?open=latest`（不再拼 `wall=expanded`）；非数字照旧落通知中心。
2. `notification_center_page.dart` 里 `PET_GIFT_RECEIVED` 的特殊处理（`location.startsWith('/pets/')`）同步改为新 query。
3. `GiftPage` 收到 `open=latest`：主人视角 → 拉 `filter=UNOPENED` 第一条，有 → push G1；没有 → 拉 `ALL` 第一条 → 打开 H1 详情（2-6）；非主人（理论上不会）→ 只显示礼物页。只执行一次（返回礼物页不再自动弹）。
4. 单测：深链映射；`open=latest` 两分支（有未拆 / 无未拆）。

### AC4 — 文案 `[L0]`
新增：`Ketuk untuk membuka` / `Tap to open`、`{nama} mengirim {n} {hadiah} untuk {petName}` / `{name} sent {n} {gift} to {petName}`、`Lihat profil {nama}` / `View {name}'s profile`、`Kamu menerima {n} hadiah` / `You received {n} gifts`、`Berikutnya` / `Next`、`Ucapan ini disembunyikan karena melanggar pedoman komunitas.` / `This message is hidden because it violates the community guidelines.`（与 2-6 共用）。

### AC5 — 测试 `[L0]`
Widget：连点礼盒只发一次 open 请求；无祝福语不渲染「⋯」；批量 1 / 9 / 10 份的分页数；只有部分有祝福语时 G6 只轮播那几笔；`disableAnimations` 直接终态。

## Tasks / Subtasks

- [x] 单个拆页面与 Rive 接入（AC1）
- [x] 批量拆汇总 / 分页 / 逐条（AC2）
- [x] 通知深链与 `open=latest`（AC3）
- [x] ARB + 测试（AC4、AC5）

## Dev Notes

- 接法同 2-3（照 `stamp_ceremony.dart`）。设计师待补：`GiftVM.giftImage`、`revealDone`、`TapBox` 说明——均不阻塞，走兜底。
- 拆礼物**不触发任何成长值入账**（服务端保证）；App 只显示接口给的 `ownerExp`，不自算。
- QA 场景（PRD A §3.8）：连点礼盒、批量 1 / 9 / >9、加载慢 / 失败、减弱动态效果、切后台——L2 本地。

## 验证层级

- L0：`flutter analyze`；`flutter test test/features/gift/ test/core/router/`
- L2（本地）：G1 ~ G6 还原度 ≥ 95%；通知点开直接拆

## Dev Agent Record

### Completion Notes

- **L1/L2 待本地验收**：G1 ~ G6 逐屏还原度（客厅插画 + `receive_gift.riv` 真渲染、礼盒轻晃、点开约 2 秒、文字淡入）；通知点开直接拆；QA 场景（连点礼盒、批量 1 / 9 / >9、加载慢 / 失败、减弱动态效果、切后台）；真后端 open 接口与 `ownerExp`。
- L0：`flutter analyze` 无 error / warning（存量弃用 info 不计）；`flutter test test/gift test/notify test/analytics` 全绿（新增 `test/gift/gift_reveal_page_test.dart` 14 条：解析、分页 1 / 9 / 10、连点只发一次 open 且只触发一次 `GiftVM.open`、关闭不拆、G2 字段与 ⋯ 菜单、G3 无祝福语无 ⋯ / EXP 0 / 注销无 Lihat profil、隐藏文案、减弱动效直出终态仍调接口、接口失败 toast、批量汇总与分页圆点、部分有祝福语的 G6 轮播、`open=latest` 两分支）。测试放 `test/gift/`（仓库现有目录），非 story 写的 `test/features/gift/`、`test/core/router/`；深链映射单测在 2-4 已改（`test/notify/deep_link_routes_test.dart`）。
- 实现要点：新页 `gift_reveal_page.dart`（`GiftRevealPage(petId, orderTokens | all)`）。G1：Rive `GiftReveal` / `GiftSM`（`Fit.cover`）+ 左上白色圆形关闭（不拆，pop false）+「Ketuk untuk membuka」。点礼盒（整块舞台接手势，Rive 自身不吃手势，故文件里的 `TapBox` 不会重复触发）→ 置标志后触发 `GiftVM.open` + 同时调 `POST /pets/{petId}/gift-orders/open`；结束 = `revealDone` 事件或 2.5 秒兜底（自点开起算）。接口失败：动画照播，结束后 toast 通用错误、只留「Lanjut」，pop true 让礼物页按服务端刷新。减弱动态效果 / Rive 不可用或加载失败 → 静态礼盒 + 直接终态（仍调 open）。动画区可注入替身（`riveBuilder` / 测试钩子 `giftRevealRiveBuilderOverride`）。
- **礼物图未接**：`receive_gift.riv` 现无 `GiftVM.giftImage`（小熊为矢量，App 换不掉），本版不传图，显示小熊属预期；设计师补属性后在 `_ReceiveGiftRive` 加数据绑定即可（同 2-3 写法）。
- G2 / G3 / G6 版式：压淡底 + 顶部送礼人头像 +「{nama} mengirim {n} {hadiah} untuk {petName}」（注销显示 `feedDeletedUser`）+「⋯」（仅 `canReport`）+ 礼物 + 祝福语 / 隐藏文案 +「Kamu dapat {n} EXP」（`ownerExp`，0 不显示，复用 2-3 key）+「Lanjut」+「Lihat profil {nama}」（注销 / 无 userId 不显示）→ `/users/{userId}`。
- 批量：「Buka Semua」→ 同一礼盒 → `open {all: true}` → 汇总 G4（`n` = 笔数、每格礼物 + 右下送礼人头像 +「×N 礼物名」+ 灰字「dari 谁」、合计 `totalOwnerExp`），每页 9 格、>9 分页圆点（G5）；有祝福语笔数 > 0 →「Berikutnya」→ G6 只轮播 `message != null` 的笔，圆点为进度，末条「Lanjut」。批量判定 = `all` 或多于 1 个 token（批量只有 1 份也走汇总）。「Lanjut」pop true → 礼物页刷新（未拆筛选随之变化）。
- 「⋯ → Laporkan ucapan」调 `gift_order_actions.dart` 的 `reportGiftMessage`（**2-6 填实举报抽屉**，本 story 为空实现）；同理 `open=latest` 无未拆分支调 `showGiftOrderDetail`（2-6）。
- `open=latest`（AC3.3）：礼物页首屏加载后（仅主人，且只一次）拉 `filter=UNOPENED` 第一条 → 有则 push G1；没有则取 `ALL` 第一条 → 详情（2-6）。AC3.1 / 3.2 的深链与通知中心处理 2-4 已改为 `?open=latest`（通知中心按 `location.startsWith('/pets/')` 判定，无需再改）。
- 文案：`giftRevealTapToOpen`、`giftRevealTitle`、`giftViewSenderProfile`、`giftReportMessage`（Laporkan ucapan，2-6 复用）、`giftRevealReceivedCount`、`giftRevealNext`、`giftMessageHidden`（2-6 复用）均逐字取 App 稿新文案表；另加 `giftRevealQtyName`「×{n} {gift}」（汇总格，稿面写法）与 `giftOpenFailed`（通用错误，文案同仓库现有「Terjadi kesalahan, coba lagi」/「Something went wrong, please try again」）。
- 仓库：`openPetGiftOrders(petId, {orderTokens, all})`；模型 `gift_order_detail.dart`（`GiftOrderDetail` / `GiftDetailSender` / `GiftOpenResult`，2-6 共用）。

- Code review（独立复审）处置：
  - PLAUSIBLE #1 待机循环若发出 `revealDone` 会跳终态且不调接口、无限转圈 → 只认点开后的结束事件。已修（补测）。
  - CONFIRMED #2 open 接口解析错误等非网络异常无限转圈 → 捕获所有异常，按失败处理。已修。
  - CONFIRMED #3 空响应（别处已拆完）显示「Kamu menerima 0 hadiah」→ 空响应只留「Lanjut」。已修（补测）。
  - PLAUSIBLE #4 请求先失败、riv 后加载失败时漏 toast → 直出终态时补提示。已修。
  - PLAUSIBLE #5 AC2.5「未拆筛选随之清空」：理解为「回礼物页刷新后未拆列表随之变空」，保留当前筛选不强切到 Semua（**待产品确认**是否要求切回 Semua）。
  - PLAUSIBLE #6 `open=latest` 拉取在途时手动点卡片 / Buka Semua 可叠两层 G1 → 礼物页拆 / 详情动作加在途互斥。已修。
  - #7 客厅插画背景：设计稿背景即 `receive_gift.riv` 渲染画面（decision-log 11-13），App 只给近似底色兜底，L2 核对。
  - 测试缺口：补「系统返回拆前 false / 拆后 true」；真 Rive（`_ReceiveGiftRive` 迟加载补触发、待机发事件、加载中销毁）依赖原生运行时，L2 验收。

### File List

- `petgo_app/lib/features/gift/presentation/gift_reveal_page.dart`（新）
- `petgo_app/lib/features/gift/domain/gift_order_detail.dart`（新）
- `petgo_app/lib/features/gift/presentation/gift_order_actions.dart`、`gift_page.dart`、`lib/features/gift/data/gift_repository.dart`（改）
- `petgo_app/lib/l10n/app_en.arb`、`app_id.arb`（改）
- `petgo_app/test/gift/gift_reveal_page_test.dart`（新）
