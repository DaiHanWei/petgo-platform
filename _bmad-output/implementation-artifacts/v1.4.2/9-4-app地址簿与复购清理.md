# Story 9.4: 地址簿与复购清理收尾（App，H1–H5、I1）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、串行）
> 口径优先级：`决策日志-shop.md`（I10「地址簿用标准探测包裹按邮编询价判断可配送」）> PRD `电商/1-4-2电商prd.md`（FR-133「地址簿管理页」、FR-136 复购下线、FR-172.1、§4 停用事件、§6 H 组与 `petProfileWeightHint` 行）> `architecture-v1.4.2-shop-delta.md`（D-9 地址簿 `serviceable`、D-20、P-4、P-10）> UI 稿 `电商/ui-1-4-2-shop-integrated-v1.html` H1 ~ H5、I1 屏下说明。`cloud-rules-shop.md` 优先于通用步骤。
> 前置：**3.1**（`GET /me/shipping-addresses` 每项加 `serviceable: true | false | null`）、**6.2**（复购下线后端：`/me/shop/repurchase-cards` 返回空、扫描停用）、**7.1**（结算页内地址表单组件 D5 —— 本 story 让地址簿用同一组件）、**8.1**（Toko 首页删补货卡 / 引导条、`/shop?category=` 忽略参数）、**8.2**（送达地区选择抽屉 B6 —— 本 story 的 H4 复用同一组件）。
> 范围层：**只 App**。后台「未填每日建议喂量」警告与「复购引擎效果」看板归后台线（架构 §6.4）。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。

## Story

As a 用户,
I want 地址簿里一眼看出哪个地址送不到,
so that 下单前就能换。

## 接口契约（来自 3.1，实现前核对后端实际 JSON）

- `GET /api/v1/me/shipping-addresses` → 地址数组，每项在现有字段（`token, receiverName, receiverPhone, provinsi, kotaKabupaten, kecamatan, addressLine, kodePos, label, isDefault`）外新增 `serviceable`：区域未开通 → `false`；已开通且标准探测包裹询价至少一家快递 → `true`；已开通但无快递可达 → `false`；**询价失败 → `null`（App 不标注）**（D-9 I10）。
- `POST / PUT / DELETE /me/shipping-addresses[/{token}]`、`POST …/{token}/default`、`GET /shop/regions` 不变。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test` · `[L1]` 连本地 / stag 后端（10.1）· `[L2]` Android 模拟器视觉与还原度（本地）。

### AC1 — 地址簿（H1 / H2）`[L0 widget]` `[L2]`（FR-133.11、FR-172.1）
1. 页面换电商统一样式（UI H1「修线上：线上是 Material 默认样式」；全局规范同 9.1 AC1）：白顶栏、返回 `chevron_left_rounded` 28、标题「Alamat pengiriman」（`addressBookTitle`）；页底色 `--bg`；地址卡白底圆角 16、内边距 16、卡内间距 8（`.card.tight`）、卡间 12。
2. 卡内容（原有保留）：首行「{收件人} · {电话}」15 / w600 + 默认地址紫标签「Utama」（`addressDefaultBadge`，`.tag`）；拼接地址（`{addressLine}, {kecamatan}, {kotaKabupaten}, {provinsi} {kodePos}`，13 号 `ink2`）；标签行：地址标签（灰标签 `.tag.gray`，显示文案见 AC3.6）+ **`serviceable == false` 时**橙标签「Belum bisa dikirim kurir」（§6 H1 新 key，`.tag.warn`：`warnt` #976932 字、`warnbg` #FDF7F0 底）；`serviceable == null` 或 `true` 不标。操作行（右对齐链接 13 / w600 `mint`）：非默认「Jadikan utama」（`addressSetDefault`）、「Ubah」（`addressEdit`）、「Hapus」（`addressDelete`）。非默认卡描边问题随新卡片样式消失（不再用 `mintTint` 描边）。
3. 新增入口：**FAB 改为吸底按钮**（`.bar` 白底 + 上阴影、内边距 12·16·安全区；`.btn` 高 48，左侧 `Icons.add`）「Tambah alamat」（`addressAdd`）；满 20 条时按钮禁用并改文「Maksimal 20 alamat」（`addressLimitReached`，原有规则）。
4. 空态（H2）：`.empty`（图标块 72×72 圆角 24 `tint` 底 + `Icons.location_on` 紫）+ 标题「Belum ada alamat.」（`addressEmpty`）+ 新副句「Tambah alamat untuk mulai belanja.」（§6 H2）；吸底「Tambah alamat」照常。
5. 加载失败：`Icons.cloud_off` + `addressLoadFailed` +「Coba lagi」（`commonRetry`）→ `ref.invalidate(addressListProvider)`（修线上：只有一行字）。
6. **选择器模式（`?select=1`）行为不变**：整卡可点 pop 回 token、不改默认地址、仍保留「设为默认」（`address_book_page.dart`:19-29、:79-95；4 条现有测试全绿）。选择器下 `serviceable=false` 的卡同样显示标签、**仍可选**（结算页选中后由试算返回 `NOT_SERVICEABLE` 处理，7.1 D3）。

### AC2 — 删除确认（H5）`[L0 widget]`（UI H5「修线上：线上删除没有确认、没有错误处理」）
1. 点「Hapus」→ 居中确认框（沿用电商确认框 `showShopConfirm`，确认键墨色）：标题「Hapus alamat ini?」（§6 H5）；正文「{收件人} · {addressLine 截断}」（UI H5 示意，单行省略，非文案 key，直接拼数据）；按钮「Batal」（`commonCancel`）/「Hapus」（复用 `addressDelete`，§6 备注「确认键复用 addressDelete」）。
2. 确认后调 `DELETE`；成功 → 刷新列表；删的是默认地址 → 服务端自动把另一条设为默认，toast 照线上 `addressDefaultPromoted(name)`（改用 `showAppToast`，不再用 `SnackBar`）。失败 → toast `addressDeleteFailed`（新 key，§6 未列，见 AC5 末段），列表不变。删除中卡片操作禁用、不可重复点。

### AC3 — 新增 / 编辑地址表单（H3）`[L0 widget]` `[L2]`（FR-133.11「表单与结算页内同一套」）
1. 地址簿的新增 / 编辑页（`/me/addresses/new`、`/me/addresses/:token`）改为承载 7.1 story 抽出的**同一个地址表单组件** `features/shop/address/presentation/address_form_body.dart` `AddressFormBody({ShippingAddress? initial, required String submitLabel, required ValueChanged<ShippingAddress> onSaved})`（以 7.1 实际提交为准，T0 核对；7.1 未抽则本 story 按此签名抽出并让结算页 D5 改用）。原有字段与校验**全部保留**：收件人 2–50 字、手机 +62 规则与帮助文字（`addressPhoneHelper`）、邮编 5 位、详细地址 ≥ 10 字、标签必填（Rumah / Kantor / Lainnya）、首个地址固定为默认、开通范围提示只告知不阻止保存（`addressInServiceArea` / `addressOutOfServiceArea`）、空表单点保存第一个错字段滚到可见。
2. **修线上缺陷——编辑预填**：`/me/addresses/:token` 进入时把该地址作为 `AddressFormBody.initial` 传入、预填全部字段（收件人、手机〔去掉 +62 显示规则同线上新增态〕、省市区、邮编、详细地址、标签、默认开关）；数据源 = `addressListProvider` 中同 token 项（列表已含全部字段，无单条 GET 接口）；列表未加载时先 `await` 列表；找不到该 token → toast `addressLoadFailed` 并 pop。
3. **修线上缺陷——标题**：新增「Tambah alamat」（`addressAdd`）；编辑「Ubah alamat」（§6 H3 新 key）。
4. **省市区三个下拉合并成一行「Wilayah」**（§6 H3）：未选时占位「Pilih wilayah」（§6 H3）、选后显示「{provinsi}, {kota}, {kecamatan}」，右侧 `chevron_right`；点击打开 H4 抽屉（AC4）。行下方保留开通范围提示（`check_circle` 紫 + `addressInServiceArea`「Wilayah ini sudah kami layani」/ 橙提示块 `addressNotServiceable` 标题 + `addressOutOfServiceArea` 正文，原逻辑 `_serviceHint`）。分组标题：「Penerima」/「Alamat」/「Label & utama」照 UI（现有 `addressSectionReceiver` / `addressSectionAddress` / `addressSectionLabel`，T0 核对现值，不改字）。
5. **提交失败提示放顶部**（UI H3）：保存失败的 `addressSaveFailed` 由线上的表单底部位置改为表单顶部的错误条（红底浅色 + 12 号红字），并滚回顶部。
6. **标签文案走 arb**（§6 H3「线上写死在代码里」）：选择胶囊显示「Rumah / Kantor / Lainnya」新 key（ID / EN）；**提交给后端的值保持原样 `'Rumah' | 'Kantor' | 'Lainnya'`**（存量地址的 `label` 都是这三个印尼语字面量，后端不做映射）；列表卡显示时把这三个字面量映射为当前语言文案，其他未知值原样显示。
7. 保存按钮「Simpan」（`addressSave`）吸底，主按钮高 48。保存成功：`invalidate(addressListProvider)` 并 pop（结算页内嵌态的「Simpan & pakai」行为归 7.1，不改）。

### AC4 — 地区选择抽屉（H4）`[L0 widget]` `[L2]`
1. UI H4 写「与 B6 送达地区选择是同一个组件」，但 8.2 story 把 B6 定为「详情页专用的只记本地抽屉」、H4 明确归 9.4。本 story 处置：把 8.2 的 B6 抽屉泛化为共享组件（加可选参数「表单模式」：选完回调、不写本地记忆、无「Alamat saya」快捷组；默认值保持 8.2 现状，8.2 测试不改），H4 以表单模式调用，`AddressFormBody` 内的地区选择换成它。表现：标题「Pilih wilayah」（§6 H3）+ 关闭 `close`；省 → 市 → 区逐级（数据取 `regionTreeProvider` 开通区域树）；当前已选项打勾（`check`）；**未开通的区同样可选**，行尾灰字「Belum dilayani」（复用现有 `addressNotServiceable`「belum dilayani」——UI 显示首字母大写，按句首大写渲染或新增大写 key，见「待确认」3）。
2. 换上级清空下级（线上规则，`address_form_page_v2_test.dart`:152 的语义保留，断言改为抽屉内行为）。选完区后关闭抽屉、回填「Wilayah」行并触发开通范围提示。
3. 泛化时若 8.2 抽屉与表单模式差异过大（数据源或面包屑交互不同），退而在 `features/shop/address/presentation/` 新建 `region_picker_sheet.dart`，视觉须与 B6 一致，并在 Completion Notes 说明未能复用的原因。

### AC5 — 文案 `[L0]`（P-10、PRD §6 H 组与体重说明行；改 ARB 后 `flutter gen-l10n`）
EN / ID 两份 arb 同加；新 key 前缀 `address*`。

| 屏 | 建议 key | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| H1 | `addressNotDeliverable` | Belum bisa dikirim kurir | Couriers can't deliver here yet | 地址标签 |
| H2 | `addressEmptyHint` | Tambah alamat untuk mulai belanja. | Add an address to start shopping. | 标题复用 `addressEmpty` |
| H3 | `addressEditTitle` | Ubah alamat | Edit address | |
| H3 | `addressRegionLabel` | Wilayah | Area | |
| H3 | `addressRegionPick` | Pilih wilayah | Choose area | H4 抽屉标题同此 key |
| H3 | `addressLabelHome` | Rumah | Home | 显示用；提交值不变 |
| H3 | `addressLabelOffice` | Kantor | Office | 同上 |
| H3 | `addressLabelOther` | Lainnya | Other | 同上 |
| H5 | `addressDeleteConfirmTitle` | Hapus alamat ini? | Delete this address? | 确认键复用 `addressDelete` |
| — | **改** `petProfileWeightHint` | Opsional. Dipakai untuk merekomendasikan produk yang pas. | Optional. Used to recommend the right products. | §6 末行；只改值不改 key |

**§6 未列**：`addressDeleteFailed`「Gagal menghapus alamat. Coba lagi. / Couldn't delete the address. Try again.」（UI H5 只说「补错误处理」未给文案，草拟，Completion Notes「待确认」复述）。

### AC6 — 复购清理收尾（I1、FR-136）`[L0]`
1. **Diary 复购卡删除**：`features/profile/presentation/growth_archive_page.dart`:581 `const RepurchaseTriggerCardV2(source: 'diary')` 与 :48 的 import 删除；**其余区域原样**（UI I1：删除后为「成就卡 → 12 → 双按钮 → 10 → 时间线」——复购卡在线上本就与双按钮零间距，删除后无需补间距；用 widget 测试断言成就卡与 `_viewToggleRow` 之间无多余组件）。
2. `features/shop/presentation/widgets/repurchase_zones_v2.dart`：删除 `RepurchaseTriggerCardV2` 与私有 `_Card` / `_CardState`（:50-246）及其埋点（`toko_repurchase_card_shown` / `_tapped` / `_why_tapped` / `_dismiss_tapped`）。**仍被引用的推荐组件保留**——`ProfileRecoZoneV2` / `_BasicCard`（:248-）若在 8.1 之后仍有引用则保留；8.1 已改用新三列推荐模块、`ProfileRecoZoneV2` 无引用 → 一并删除，文件无剩余内容则整文件删除（T0 用 `grep -rn ProfileRecoZoneV2 lib` 判定）。
3. 数据层：`features/shop/data/shop_repurchase_repository.dart` 删 `cards()`（:23-29）、`dismiss()`（:31-33）、`repurchaseCardsProvider`（:56-63）；**保留 `recommendations()` 与 `recommendationsProvider`（:44-53）**（「为宠物推荐」接口与复购同包，PRD FR-136 实现提醒：勿连带删）。`features/shop/domain/shop_repurchase.dart` 删 `RepurchaseCard`（:11-）及仅它使用的类型，保留 `Recommendations` / `RecommendationItem`（:100-）。`core/network/api_paths.dart`:524 `meShopRepurchaseCards` 无引用后删除。
4. arb：删除**无引用**的复购 key（两份 arb 同删）：`recoTriggerEyebrow` / `recoTriggerConclusion` / `recoTriggerConclusionOverdue` / `recoTriggerBasis` / `recoTriggerWhy` / `recoTriggerLater`（`app_id.arb`:2245-2250）、`tokoRestockLabel` / `tokoRestockCard` / `tokoRestockCardOverdue` / `tokoRestockDismiss` / `tokoRestockTag` / `tokoRestockTagOverdue` / `tokoBuyAgain`（:2136-2139、:2156-2158，前提是 8.1 已删 `toko_page_v2` 的补货行、无引用）；`recoBasic*` / `recoDegradedBody` / `recoSettingsEntry*`（:2253-2257）只在 `ProfileRecoZoneV2` 一并删除时删。删除前对每个 key `grep -rn "l10n\.<key>" lib` 确认零引用（P-10「删除复购相关 key」）。
5. 旧复购推送深链：`DeepLinkRoutes.pushPayloadToLocation` 固定目标类 switch 加 `case 'REPURCHASE_FOOD_LOW': return '/shop';`（D-20「旧推送深链落 Toko 首页」；`/shop` 是 Shell Tab 根，调用方已按 `isShellTabRoot` 用 `go`）——若 8.1 已加则跳过（T0）。通知中心该类型标题 / 正文维持系统兜底（历史行，不新增文案）。
6. 编辑宠物档案体重说明：`petProfileWeightHint` 改值（AC5 表），`pet_profile_edit_page.dart`:452 引用不变。
7. 埋点清单：`test/analytics/v140_events_inventory_test.dart`:44-46 把 `repurchase_card_impression` / `_clicked` / `_dismissed` 三条从「必须存在」清单移到新增的「V1.4.2 FR-136 已停用、必须不存在于 lib/」断言（PRD §4「随 FR-136 停用的事件」；不删测试、改断言表达新规则）。若 8.1 已改则跳过。
8. 归因数据保留：订单行 `entry_source` / `trigger_type` 的 `toko_repurchase_card` 等取值是历史归因（PRD「历史数据保留不删」），`test/shop/attribution_closure_test.dart` 不改。
9. `flutter analyze` 零问题（无未用 import / 未用 key 告警）、全量 `flutter test` 绿。

### AC7 — 还原度与回归 `[L0]` `[L2]`
1. widget 测试覆盖：H1（三种 `serviceable`：false 标签 / true 与 null 不标；默认卡 Utama；标签本地化；满 20 禁用）、H2（副句 + 吸底按钮）、加载失败重试、H5（确认 / 取消 / 默认被删提示 / 删除失败 toast）、H3（编辑预填全部字段 + 标题 Ubah alamat、新增标题 Tambah alamat、Wilayah 一行、提交失败错误条在顶部、标签提交值仍为印尼语字面量）、H4（逐级、未开通可选且带「Belum dilayani」、换上级清空下级）、选择器模式 4 条既有用例、I1（Diary 无复购卡）、`petProfileWeightHint` 新值（en / id 各断言不含「habis」/「runs out」）。
2. 会被按设计打破的测试（改断言，不删不 skip）：`address_form_page_v2_test.dart`「三级都是选择器，没有可输入的行政区文本框」（:140）→ 改为「Wilayah 一行 + 抽屉逐级」；「换了上级会清空下级」（:152）→ 断言移到抽屉；`address_book_page_test.dart` 若依赖 `FloatingActionButton` 查找新增入口 → 改找吸底按钮 key；`test/profile/growth_archive_test.dart`（:4 import、:76 `ProfileRecoZoneV2` findsNothing）→ 若 `ProfileRecoZoneV2` 被删，改为断言 Diary 不含任何复购 / 推荐组件（按 key 或类型名字符串），去掉对已删文件的 import；`test/shop/repurchase_zones_v2_test.dart` 中针对 `RepurchaseTriggerCardV2` 的用例**随被删组件删除**（组件本身下线，不是被打破的断言），`ProfileRecoZoneV2` 的用例跟随组件去留。
3. **不写 golden**；还原度 ≥ 95 本地模拟器打分（L2）。

## Tasks / Subtasks

- [ ] **T0 开工核对**：对照 3.1 / 6.2 / 7.1 / 8.1 / 8.2 **已提交代码**核对：地址列表 `serviceable` 字段、7.1 抽出的地址表单组件名与参数、8.2 地区抽屉组件名与是否支持表单模式、8.1 是否已删 `toko_page_v2` 补货行 / `ProfileRecoZoneV2` 是否仍被引用 / 是否已加 `REPURCHASE_FOOD_LOW` 深链 / 是否已改 `v140_events_inventory_test`；有出入先改本文件（Completion Notes 记）
- [ ] **T1 数据层**：`ShippingAddress` 加 `bool? serviceable`（缺键 = null）；`addressDisplayLabel(l10n, raw)` 映射函数
- [ ] **T2 地址簿页**（AC1、AC2）：重写 `address_book_page.dart` 视觉、吸底按钮、空态 / 失败态、删除确认与错误处理；保留选择器模式
- [ ] **T3 表单**（AC3、AC4）：地址簿新增 / 编辑页承载共用表单组件；编辑预填与标题；Wilayah 一行 + H4 抽屉；提交失败错误条置顶；标签走 arb
- [ ] **T4 复购清理**（AC6）：Diary 卡、组件、数据层、api path、arb key、深链、体重说明、埋点清单测试
- [ ] **T5 文案**（AC5）+ `flutter gen-l10n`
- [ ] **T6 测试**（见 Dev Notes「测试」）
- [ ] **T7** `flutter analyze` + 全量 `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

- **地址簿** `petgo_app/lib/features/shop/address/presentation/address_book_page.dart`（179 行）：Material 默认 `AppBar` + `AppColors.cream`（:36-38）；失败只显示 `addressLoadFailed` 一行（:41）；空态只一行 `addressEmpty`（:43）；`FloatingActionButton.extended` 新增入口，满 20 禁用（:55-63）；`_AddressCard`（:68-179）选择器模式整卡可点（:79-95）、卡片描边 `mintTint`（:103，非默认卡几乎看不见）；标签直接显示 `address.label` 原值（:130-133）；**删除无确认、无错误处理**（`_delete` :160-178，`await repo.delete` 直接调，失败会抛到 UI 外），默认被删用 `SnackBar` 提示（:169-176）。
- **地址表单** `features/shop/address/presentation/address_form_page_v2.dart`（599 行）：**标题恒为 `addressAdd`**（:99，编辑也是「Tambah」）；`widget.token` 只用于提交时走 `update`（:542、:554），**编辑不预填**（state 字段 :47-60 全部空初始化，无 initState 读取）；省 / 市 / 区三个 `DropdownButtonFormField`（`_picker` :431-470，:171-208），不可配送区在下拉项后缀「· belum dilayani」（:202-203、:476）；开通范围提示 `_serviceHint`（:331-361）；标签胶囊 `for (final tag in const ['Rumah', 'Kantor', 'Lainnya'])` 写死（:264）；提交失败 `_submitError = l10n.addressSaveFailed`（:564）。
- **地址模型** `features/shop/address/domain/shipping_address.dart`：`ShippingAddress`（:6-41）无 `serviceable`；`RegionTree` / `RegionProvinsi` / `RegionKota` / `RegionKecamatan(name, serviceable)`（:61-113）。仓库 `address_repository.dart`：`list / create / update / delete / setDefault / regions`（:17-51），`addressListProvider`、`regionTreeProvider`（:57-63）。
- **路由** `app_router.dart`:731-749：`/me/addresses`（`?select=1` 选择器）、`/me/addresses/new`、`/me/addresses/:token`。
- **Diary 复购卡** `features/profile/presentation/growth_archive_page.dart`:48 import、:581 `const RepurchaseTriggerCardV2(source: 'diary')`（插在 `DiaryHeader` 之后、`_viewToggleRow(l10n)`（:595）之前；其后注释块说明档案推荐区早已从 Diary 撤下）。
- **复购组件** `features/shop/presentation/widgets/repurchase_zones_v2.dart`：`RepurchaseTriggerCardV2`（:50）+ `_Card`（:71-246，埋点、`?from=REPURCHASE` 跳转 :206）、`ProfileRecoZoneV2`（:248）+ `_BasicCard`（:400-）。外部引用：`growth_archive_page.dart`:48/:581；`toko_page_v2.dart`:288 注释提到 `ProfileRecoZoneV2`（8.1 重写 Toko 首页后以实际引用为准）。
- **复购数据** `features/shop/data/shop_repurchase_repository.dart`：`cards()`（:23-29）、dismiss（:31-33）、`recommendationsProvider`（:44-53，**保留**）、`repurchaseCardsProvider`（:56-63）；`toko_page_v2.dart`:236 / :817 也引用 `repurchaseCardsProvider`（8.1 删 v2 后消失）。
- **体重说明** `features/profile/presentation/pet_profile_edit_page.dart`:452 `Text(l10n.petProfileWeightHint, …)`；现值 ID「Opsional. Dipakai untuk merekomendasikan ukuran yang pas dan memperkirakan kapan makanannya habis.」（`app_id.arb`:2135）、EN（`app_en.arb`:3242）。
- **深链** `core/router/deep_link_routes.dart`:72-104 固定目标类 switch；当前无 `REPURCHASE_FOOD_LOW`（落通知中心兜底）。
- **埋点清单** `test/analytics/v140_events_inventory_test.dart`:44-46 要求三个复购卡事件在 `lib/` 中存在（`test('🔴 §5 清单逐条都有落地实现')` :53）——删组件后必须改这里。
- **arb 现值**：`addressBookTitle` Alamat pengiriman（:1944）、`addressAdd` Tambah alamat（:1945）、`addressEdit` Ubah（:1946）、`addressDelete` Hapus（:1947）、`addressSetDefault` Jadikan utama（:1948）、`addressDefaultBadge` Utama（:1950）、`addressEmpty` Belum ada alamat.（:1951）、`addressLoadFailed`（:1952）、`addressLimitReached` Maksimal 20 alamat（:1953）、`addressNotServiceable` belum dilayani（:1961）、`addressDefaultPromoted`（:1969）、`addressInServiceArea`（:2186）、`addressOutOfServiceArea`（:2187）、`commonCancel` Batal（:75）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 标签显示走 arb、提交值保持印尼语字面量（存量数据与后端都按字面量），避免为显示改数据契约。
2. 编辑预填数据源用地址列表（无单条 GET），不新增后端接口。
3. 选择器模式下不可配送地址仍可选（结算页统一处理不可配送，7.1 D3），只做标注。
4. 复购相关代码按「零引用才删」逐个判定，`recommendations` 一律保留。

### 实现提示

- 吸底按钮用现有 `ShopBottomBarActions` 外壳（`widgets/shop_buttons.dart`）则需确认它能出 48 高主按钮；不能就页内写 `.bar` 结构（白底、上阴影 `0 -4 16 rgba(46,42,69,.06)`、内边距 12·16 + `MediaQuery.paddingOf(context).bottom`）。
- 编辑预填：`ref.read(addressListProvider.future)` 取列表 → `firstWhereOrNull(token)`；预填只在首次进入做一次（`initState` 里启动、`_prefilled` 标记），避免列表刷新覆盖用户正在改的值。手机号展示按线上新增态的规则（`normalizeIdPhone` 的逆向：去 `+62` 前缀显示本地号，UI H3 帮助文字「angka 0 di depan otomatis dihapus」）。
- `serviceable` 只是展示：不要在地址簿里拦截「设为默认」或编辑。
- 删除确认用 `showShopConfirm`（`widgets/shop_dialog.dart`），与 9.1 取消订单确认同一组件。
- 复购 key 删除后跑 `flutter gen-l10n`，生成文件随之变化，不手改。

### 架构约束

- P-4：地址相关组件留在 `features/shop/address/`（新 `presentation/widgets/address_form.dart` 若由本 story 抽出）；P-10：文案严格 §6，删除复购 key；P-9：停用事件随组件删除。
- 地址是 PII：不进埋点、不进日志（现状无埋点，保持）。
- 不引新依赖；不整文件 `dart format`。

### 与前后 story 的边界

- **3.1**：`serviceable` 的计算（区域开通 + 标准探测包裹按邮编询价、24 小时复用）全在后端。
- **6.2**：复购扫描停用、`repurchase-cards` 返回空、`REPURCHASE_FOOD_LOW` 不再产生（后端）。
- **7.1**：结算页 D4 地址抽屉、D5 页内新增（「Simpan & pakai」）；本 story 只让地址簿用同一表单组件。
- **8.1**：Toko 首页补货卡 / 引导条删除、`/shop?category=` 忽略；**8.2**：B6 送达地区抽屉。
- **10.1**：真后端核对不可配送地址标注与结算页不可提交一致。

### 测试（全部 L0）

- `test/shop/address/address_book_page_test.dart`（补 + 改）：AC7.1 地址簿部分、选择器 4 条既有用例。
- `test/shop/address/address_form_page_v2_test.dart`（改 :140 / :152 断言 + 补编辑预填、标题、顶部错误条、标签提交值）。
- `test/shop/address/region_picker_form_mode_test.dart`（新，或并入 8.2 的抽屉测试文件）：H4。
- `test/shop/address/address_test.dart`（补 `serviceable` 缺键容错、标签映射）。
- `test/profile/growth_archive_test.dart`（改）、`test/profile/pet_profile_edit_weight_hint_test.dart`（新，或并入现有编辑页测试）。
- `test/notify/deep_link_routes_test.dart`（补 `REPURCHASE_FOOD_LOW → /shop`，若 8.1 未加）。
- `test/analytics/v140_events_inventory_test.dart`（改，若 8.1 未改）。
- `test/shop/repurchase_zones_v2_test.dart`（删 TriggerCard 用例；文件随组件去留）。

### Project Structure Notes

- 改：`address_book_page.dart`、`address_form_page_v2.dart`（变为承载共用表单的页）、`shipping_address.dart`、`growth_archive_page.dart`、`repurchase_zones_v2.dart`（删部分或整文件）、`shop_repurchase_repository.dart`、`shop_repurchase.dart`、`api_paths.dart`、`deep_link_routes.dart`、两份 arb。
- 可能新：`features/shop/address/presentation/widgets/address_form.dart`（7.1 未抽时）。

### References

- PRD §3.7「地址簿管理页」、§3.9 复购下线（含「实现提醒」与 `petProfileWeightHint` 一条）、§3.10「能不能送」、§4「随 FR-136 停用的事件」、§6 H 组与末行
- 架构 D-9（地址簿 `serviceable`，I10）、D-20、P-4、P-9、P-10；§5.2 address / profile / notify 三行
- 决策日志 I10
- UI 稿 H1 ~ H5、I1 屏下说明
- epics Story 9.4

## 待确认 / 发现的规划冲突

1. **地址表单「同一套」的抽取归属**：epics 9.4 写「8.2 已复用，此处完成地址簿侧」（应为 7.1）；7.1 story 已定抽出 `AddressFormBody`。本 story 用它，并负责地址簿侧的编辑预填、标题、Wilayah 一行与顶部错误条。
2. **H4 与 B6 是否同一组件**：UI 稿说是同一个，8.2 story 定 B6 为详情页专用、H4 归 9.4。本 story 优先把 B6 泛化复用（加可选参数，默认不变），做不到再新建并保持视觉一致。
3. **「Belum dilayani」大小写**：现有 `addressNotServiceable` 是小写「belum dilayani」（用作下拉项后缀「· belum dilayani」），UI H4 行尾显示「Belum dilayani」。本 story 复用原 key、渲染时句首大写，不新增 key；若产品要求独立 key 再补。
4. **删除失败文案 §6 未列**：UI H5 只说「补错误处理」，`addressDeleteFailed` 为草拟。
5. **复购清理与 8.1 的先后**：补货行、`toko_*Restock*` key、`REPURCHASE_FOOD_LOW` 深链、`v140_events_inventory_test` 的停用断言，8.1 可能已做部分。本 story 一律「零引用才删、已做则跳过」，Completion Notes 列明实际删了什么。
6. **地址簿「暂不支持快递配送」标注的口径**：§6 H1 文案「Belum bisa dikirim kurir」与 B3 / 结算页 D3「Wilayah ini belum bisa dikirim kurir」并存（§6 B3 备注「结算页、地址簿同义处共用」）。地址簿按 H1 的短句实现，B3 长句留给详情 / 结算页。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev）。
