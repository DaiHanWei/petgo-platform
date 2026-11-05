# Story 9.3: 我的券与兑换（App，G1–G9）

Status: ready-for-dev

> 版本 v1.4.2 · 主题 shop · 分支 `feat/1.4.2-shop`（从 `dev_1.4.2` 切出，全线 25 story 同一分支、串行）
> 口径优先级：`决策日志-shop.md`（「我的券可用列表不显示被待付款订单占用的券」、I8）> PRD `电商/1-4-2电商prd.md`（FR-130「券的状态」「兑换码与过期提醒细节」、§3.0 旅程 E、§4、§6 G 组）> `architecture-v1.4.2-shop-delta.md`（D-2、D-4、D-6、D-21、D-22、P-2、P-5、P-9、P-10）> UI 稿 `电商/ui-1-4-2-shop-integrated-v1.html` G1 ~ G9 屏下说明。`cloud-rules-shop.md` 优先于通用步骤。
> 前置：**2.2**（`GET /me/coupons`、`POST /me/coupons/redeem`、错误码表 P-5）、**2.3**（`COUPON_EXPIRING` 类型与推送，归 `PushCategory.PROMO`）、**7.1 / 8.1**（`features/shop/coupon/coupon_card.dart` 券面组件、`toko_coupon_claimed` 埋点与 `_claimed` 后缀登记、`couponMyTitle` 等已建 key）、**9.1**（订单列表「我的券」入口）。
> 范围层：**只 App**。
> 迁移：无。

## ⚠️ 规划收口补充（2026-11-05，优先于下文正文；来源 `决策日志-shop.md`「story 编写后收口」）

- **C-3**：「我的券」可用列表**显示**被待付款订单占用（LOCKED）的券：带 `lockedByPendingOrder=true`，App 标「已被待付款订单占用」、置灰不可点；结算页券列表中 LOCKED 券不出现。推翻正文中「LOCKED 不出现」的写法。
- **C-9**：已使用券出参补：`usedAt`、`usedOrderNo`、`usedOrderToken`（供 G2「Dipakai {date} · {no}」与点进订单）。
- **C-10**：券快过期、重新下单两类推送点开**直达**对应页面（我的券 / 新订单），按 PRD。
- **C-11**：文案：PRD §6 与 UI 稿冲突以 PRD §6 为准（推荐栏头「Untuk {pet}」、详情「标题 → 价格」顺序、确认收货提示）；PRD §6 未列、UI 稿有的文案先按 UI 原文实现，Completion Notes「待设计确认」列出。
- **C-2**：错误码一律连字符风格（`coupon-not-found`、`coupon-code-used`、`coupon-not-in-period`、`coupon-already-claimed`、`coupon-sold-out`、`coupon-redeem-locked`、`coupon-invalid`、`quote-expired`、`quote-failed`、`not-serviceable`、`sku-not-sellable`、`product-delisted`），登记进 `ErrorTypes`。

## Story

As a 用户,
I want 一个地方看我能用的券、输入兑换码,
so that 知道手里有什么券、快过期的先用掉。

## 接口契约（来自 2.2 / 2.3，实现前核对后端实际 JSON；有出入以已提交代码为准并改本文件）

- `GET /api/v1/me/coupons?tab=available|used&cursor=` → `{items[], nextCursor, availableCount}`（2.2 story AC8；每页 20；可用按 `claimed_at DESC`，已使用按 `updated_at DESC`）。可用 = AVAILABLE 且未过期（**LOCKED、VOIDED、已过期都不列**）；已使用 = USED。`items[]` 为 2.2 AC11 的 `CouponView`：`couponToken`（模板 token，埋点用）、`userCouponToken`、`kind`（`DISCOUNT | FREE_SHIPPING`）、`nameId` / `nameEn`（App 按当前语言取）、`amount`（满减额；免运券为封顶额）、`minSpend`、`scopeType`（`ALL | CATEGORY | PRODUCT`）、`scopeCategories[]`、`scopeProductCount`、`validFrom`、`validUntil`、`validDays`、`expiringSoon`、`claimed`、`newbie`、`isNew`、`status`（`AVAILABLE | USED`）。**G2 需要的 `usedAt` / `usedOrderNo` / `usedOrderToken` 不在 `CouponView` 里**（见「待确认」2）。
- `GET /api/v1/me/coupons/summary` → `{availableCount}`（9.1 入口计数）。
- `POST /api/v1/me/coupons/redeem {code}` → 200 返回新用户券（`CouponView`）。失败 ProblemDetail `type`（2.2 AC1：`https://petgo/errors/<后缀>`，后缀含点号）的末段（`ProblemDetail.typeSlug`，`core/network/problem_detail.dart`:31-36）：`coupon-not-found`（码无效）/ `coupon-code-used` / `coupon-not-in-period` / `coupon-already-claimed` / `coupon-sold-out` / `coupon-redeem-locked`（429）；401 → 未登录。
- 推送 / 通知 `COUPON_EXPIRING`（D-22）：深链 `/me/coupons`；张数在通知行 / 推送载荷的 `targetRef`（2.3 story 定，十进制字符串）；推送与通知中心文案由后端按收件人语言渲染后落库（`title` / `body`）。

## Acceptance Criteria

> 层级：`[L0]` `flutter analyze` / `flutter test` · `[L1]` 连本地 / stag 后端（10.1）· `[L2]` Android 模拟器视觉与还原度（本地）。

### AC1 — 入口与路由 `[L0]`（FR-130.8、UI G 组 FLOW）
1. 新路由 `/me/coupons` → `features/shop/coupon/my_coupons_page.dart`（`MyCouponsPage`）。挂在 `/me` 前缀下，**已在 `_controlledLocations`**（`app_router.dart`:172-179）→ 游客自动拦截去登录（满足「未登录先登录再兑换」）；若 8.1 已注册该路由，本 story 只替换 builder。
2. 入口：9.1 订单列表「Belanja」下的「Kupon Saya · {n} bisa dipakai」横条、8.1 首页「给你的券」右上「Kupon Saya ›」、G9 推送 / 通知中心。若 9.1 因本页未就绪而隐藏了入口，本 story 打开（T0 核对）。
3. 入口计数：9.1 横条用 `/me/coupons/summary` 的 `availableCount`；本页「Bisa dipakai ({n})」用列表响应的 `availableCount`（每次刷新 / 兑换成功后以最新响应为准）。

### AC2 — 页面结构（G1 / G2 / G4 / G5）`[L0 widget]` `[L2]`
1. 全局视觉规范（同 9.1 AC1）：白顶栏、返回 `chevron_left_rounded` 28、标题「Kupon Saya」（复用 8.1 / 7.1 已建 `couponMyTitle`，不存在则本 story 新增，§6 G1）；页底色 `--bg`。
2. 顶部兑换区（白底，页边 16）：输入框（`.field .in`：高 48、圆角 12、1px `line` 描边、15 号，占位「Masukkan kode kupon」§6 G1）+ 右侧「Tukar」按钮（§6 G1，`.btn.s` 小胶囊、高 36，输入为空时禁用）。输入自动转大写、去首尾空格（`TextCapitalization.characters` + 提交前 `trim().toUpperCase()`），最大 32 字（D-4 `VARCHAR(32)`）。**兑换区在加载失败（G5）时仍可用**。
3. Tab（`.seg`：白底、底部 1px `line`、两等分、13 号 `ink2`，选中 `ink` w600 + 2px `mint` 下划线）：「Bisa dipakai ({n})」（§6 G1，n 取 AC1.3；列表首屏未返回 / 失败时用新 key `couponTabAvailablePlain`「Bisa dipakai」，§6 未列不带数的写法）/「Sudah dipakai」（§6 G1）。**无「已过期」Tab**（PRD 11-05：已过期券不展示；UI 稿 lane 描述里的「已过期」已作废）。
4. 可用券列表（G1）：复用 7.1 / 8.1 的 `CouponCard`（`.coupon`：左 108 宽色块〔满减 `tint` 底 / `mint6` 字；免运 `#E6F5F7` 底 / `#1B7F8C` 字〕+ 1.5 虚线分隔；左块上行面额 16 / w700、下行门槛 11 号；右侧名称 13 / w600、有效期与范围 12 `muted`），卡间 8。卡面文案（与结算页券面同一套，复用 7.1 / 8.1 已建 key；未建则本 story 新增，见 AC6 表末）：
   - 左块面额：满减「Rp {amount}」；免运「Ongkir」；门槛：满减「Min. Rp {minSpend}」、免运「Maks. Rp {amount}」（免运券的 `amount` 是每张封顶额，D-1）。
   - 右侧：名称；有效期「s/d {date}」（WIB 日期）；`expiringSoon=true` 时改为「Berakhir {date, time}」+ 红标签「Segera berakhir」（§6 G1，`.tag.red`）；范围「Semua produk」（复用 8.1 `tokoAllProducts` 或 §6 A1「Semua produk」）/「Kategori {品类名}」/「Produk tertentu」。
   - **点券不跳转**（使用在结算页，UI G1）。
   - **被待付款订单占用（LOCKED）的券不显示**（决策日志「按推荐定下」第 6 条、D-6；UI G1 说明「显示在可用 Tab 标『Dipakai di pesanan menunggu bayar』」作废，见「待确认」1）。
   - 过期判断以服务端为准（列表即服务端过滤结果），App 不按本地时钟隐藏。
5. 已使用（G2）：同券面整体降权（不透明度 0.55）、去掉「Segera berakhir」标签；右侧末行「Dipakai {date} · {no}」（§6 G2，date = `usedAt` WIB `d MMM yyyy`，no = `usedOrderNo`），**单号可点**（UI G2「可点进订单」）→ `context.push('/shop/orders/{usedOrderToken}')`；无 `usedOrderToken` 时不可点；**三个字段 2.2 均未下发时**（见「待确认」2）整行不显示，只保留有效期行。
6. 可用为空（G4）：`.empty`（图标块 72 圆角 24 `tint` 底 + `Icons.confirmation_number_outlined`）+ 标题「Belum ada kupon」+ 副句「Tukar kode di atas, atau klaim kupon di halaman produk.」（§6 G4）。「已使用」为空：同图标 + 标题「Belum ada kupon」、无副句（§6 未给专句，见「待确认」4）。
7. 加载失败（G5）：`Icons.cloud_off` +「Gagal memuat kupon.」（§6 G5）+「Coba lagi」（`commonRetry`）→ 重拉当前 Tab；加载更多失败同样给重试。
8. 游标分页 + 下拉刷新；切 Tab 各自独立缓存（来回切不重复请求，下拉强刷）。新 provider（`myCouponsProvider(tab)`，autoDispose.family）**登记 `app.dart` `resetUserScopedCaches`**。

### AC3 — 兑换（G6 / G7 / G8）`[L0 widget]` `[L2]`（FR-130.9、D-4、P-5）
1. 点「Tukar」→ `POST /me/coupons/redeem`；请求中按钮转圈、输入框只读、不可重复提交。
2. 成功（G6）：toast「Kupon berhasil ditukar」（§6 G6，底部、在键盘收起后显示）；输入框清空并收起键盘；切到「Bisa dipakai」Tab，**新券插在列表顶部**并带「Baru」标签（优先用响应的 `isNew`；同时本页记一个 `Set<userCouponToken>` 兜底）（§6 G6，`.tag` 紫）——本地插入响应体，同时后台刷新列表与计数（刷新后「Baru」标签保留到离开本页：用一个本页内 `Set<userCouponToken>` 记）；失效 9.1 的入口计数、结算页相关券 provider（7.1 的券列表 / 试算若有缓存）。
3. 埋点：成功后报 `toko_coupon_claimed{coupon_id: couponToken, source: 'code'}`（PRD §4；`coupon_id` 取**模板 token**，与 `toko_order_submitted.coupon_ids` 同口径 D-23；`source` 取值与 8.1 / 8.2 已用的字面量体系一致，T0 核对）。失败不报。
4. 失败（G7）：**不用 toast**，错误显示在输入框下（输入框转红框 `red` #F0425A + 12 号 `redt` #C4263C 文字），按 `typeSlug`：
   | typeSlug | 印尼语（§6 G7）|
   |---|---|
   | `coupon-not-found` | Kode kupon tidak valid |
   | `coupon-code-used` | Kode ini sudah dipakai |
   | `coupon-not-in-period` | Kode ini tidak dalam masa berlaku |
   | `coupon-already-claimed` | Kamu sudah klaim kupon ini（复用 8.x 的 A5 key） |
   | `coupon-sold-out` | Kupon sudah habis |
   | 其他 / 网络错误 | toast（现无通用失败 key；复用同义的 `refundSubmitFailed`「Terjadi kesalahan, coba lagi」语义不合适，故新增 `couponRedeemFailed`「Terjadi kesalahan, coba lagi / Something went wrong, try again」，与现有两处同字），输入框不标红 |
   用户修改输入后清除错误态。
5. 锁定（G8）：429 或 `typeSlug == 'coupon-redeem-locked'` → 输入框与「Tukar」禁用，框下提示「Terlalu banyak percobaan. Coba lagi dalam 1 jam.」（§6 G8）；**本页存续期间保持禁用**（锁定由服务端计时 1 小时，App 不本地倒计时；离开再进可再试，服务端仍锁会再返回 429 → 回到 G8）。
6. 401（会话失效）→ 走全局登录态处理（现有拦截器），不在本页另做。

### AC4 — 券快过期推送与通知（G9）`[L0]`（FR-130.10、D-21、D-22）
1. 深链：`DeepLinkRoutes.pushPayloadToLocation` 在**固定目标类** switch（`deep_link_routes.dart`:72-104，与 `PET_SLOT_EXPIRING` 同位，不依赖 targetRef）加 `case 'COUPON_EXPIRING': return '/me/coupons';`。
2. 推送点击直达：`core/push/push_service.dart`:63-66 `pushClickLocation` 的直达白名单加 `COUPON_EXPIRING`（先例 `GROWTH_LEVEL_UP`；PRD「点击进我的券」、UI G9「点开进 G1」；9.1 已为 `SHOP_ORDER_REORDERED` 开同类例外时合并为一个 `Set`）。
3. 推送点开埋点：推送点击（不含通知中心列表点击）后报 `toko_coupon_expiry_push_opened{coupon_count}`（PRD §4：现有推送中只有运营推送会上报打开，本推送须单独埋）——在 `_onNotificationClicked` 中、与 `GROWTH_LEVEL_UP` 埋点同位置按 `p.type == 'COUPON_EXPIRING'` 上报；`coupon_count` = `int.tryParse(p.targetRef)`，解析不出则不带该属性。字面量、`_opened` 后缀已在白名单。
4. 通知中心（`notification_center_page.dart`）：标题 / 正文读后端落库的 `title` / `body`（后端已按收件人语言渲染，正文带日期，App 无从本地拼——沿用 `_refundTitle` / `_refundBody` 的「读后端 + 本地兜底」写法），为空时兜底：标题「{n} kupon segera berakhir」（§6 G9，n 取 `targetRef` 数字；取不到时不猜数字，用新 key `couponExpiringNotifyTitleFallback`「Kupon segera berakhir」，见「待确认」3）、正文兜底不带日期的新 key（见 AC6）。图标 `Icons.confirmation_number_rounded`、`AppColors.gold` / `goldTint`（与生日等提醒类同色系）。`_notifTypeForAnalytics` 显式登记 `'COUPON_EXPIRING' => 'coupon_expiring'`。
5. 推送设置**不新增分类**：`COUPON_EXPIRING` 归「INFO & PROMO → Promo & aktivitas」开关（后端 `PushCategory.PROMO`，2.3 已映射）；App 推送设置页无改动（加一条断言证明分类数不变）。

### AC5 — 埋点后缀守卫 `[L0]`（P-9）
`toko_coupon_claimed` 的 `_claimed` 后缀**当前不在** `test/analytics/v112_events_test.dart` 的 `allowedSuffixes`（:375-493）。若 8.1 / 8.2 已先加（他们先用到此事件），本 story 不动；未加则本 story 追加 `'_claimed'`（附注释「PRD §4 定名」），不放宽正则、不删守卫。

### AC6 — 文案 `[L0]`（P-10、PRD §6 G 组；改 ARB 后 `flutter gen-l10n`）
EN / ID 两份 arb 同加；新 key 前缀 `coupon*`。T0 先查 7.1 / 8.1 / 8.2 已建的同文案 key，**已有则复用不重复建**。

| 屏 | 建议 key | 印尼语 | 英语 | 备注 |
|---|---|---|---|---|
| G1 | `couponMyTitle` | Kupon Saya | My coupons | 8.1 已建则复用（§6 A1 / G1 同句） |
| G1 | `couponRedeemHint` | Masukkan kode kupon | Enter coupon code | |
| G1 | `couponRedeemButton` | Tukar | Redeem | |
| G1 | `couponTabAvailable` `{n}`(int) | Bisa dipakai ({n}) | Available ({n}) | 7.1 D10 同句已建则复用 |
| G1 | `couponTabUsed` | Sudah dipakai | Used | |
| G1 | `couponExpiringSoon` | Segera berakhir | Expiring soon | |
| G2 | `couponUsedOn` `{date}` `{no}`(String) | Dipakai {date} · {no} | Used {date} · {no} | |
| G4 | `couponEmptyTitle` | Belum ada kupon | No coupons yet | |
| G4 | `couponEmptyHint` | Tukar kode di atas, atau klaim kupon di halaman produk. | Redeem a code above, or claim coupons on product pages. | |
| G5 | `couponLoadFailed` | Gagal memuat kupon. | Couldn't load coupons. | 重试复用 `commonRetry` |
| G6 | `couponRedeemSuccess` | Kupon berhasil ditukar | Coupon redeemed | |
| G6 | `couponNewTag` | Baru | New | |
| G7 | `couponRedeemInvalid` | Kode kupon tidak valid | Invalid coupon code | |
| G7 | `couponRedeemUsed` | Kode ini sudah dipakai | This code has already been used | |
| G7 | `couponRedeemNotInPeriod` | Kode ini tidak dalam masa berlaku | This code isn't valid right now | |
| G7 | — 复用 8.x A5 key（`couponAlreadyClaimed`） | Kamu sudah klaim kupon ini | You've already claimed this coupon | §6「与兑换失败共用」 |
| G7 | `couponRedeemSoldOut` | Kupon sudah habis | This coupon has run out | |
| G8 | `couponRedeemLocked` | Terlalu banyak percobaan. Coba lagi dalam 1 jam. | Too many attempts. Try again in 1 hour. | |
| G9 | `couponExpiringNotifyTitle` `{n}`(int) | {n} kupon segera berakhir | {n} coupons expiring soon | 通知中心兜底（有 n 时） |
| G9 | `couponExpiringNotifyBody` `{date}`(String) | Pakai sebelum {date}. Jangan sampai hangus! | Use them before {date} so they don't go to waste! | 留给有日期时用 |

**UI 稿有、PRD §6 未列**（券面组件文案；优先复用 7.1 / 8.1 已建 key；都没建时本 story 按 UI 原文新增，英语草拟，Completion Notes「待确认」复述）：「Min. Rp {min}」（PRD A4 备注「券面『满 X 可用』同此句式」→ 用 `min. belanja Rp {min}` 句式还是 UI「Min. Rp」短式待定）、「Maks. Rp {amount}」、「Ongkir」（免运券面额位）、「s/d {date}」、「Berakhir {datetime}」、「Kategori {name}」、「Produk tertentu」；以及兜底 `couponTabAvailablePlain`「Bisa dipakai / Available」、`couponExpiringNotifyTitleFallback`「Kupon segera berakhir / Coupons expiring soon」、`couponExpiringNotifyBodyFallback`「Pakai sebelum hangus! / Use them before they expire!」（后两句由 §6 G9 去掉变量派生）。

### AC7 — 还原度与回归 `[L0]` `[L2]`
1. widget 测试覆盖 G1（满减 / 免运 / 即将过期 / 范围三种）、G2（降权 + 单号可点 / 无 token 不可点）、G4、已使用空、G5（兑换区仍可用）、G6（顶部插入 + Baru + 清空 + 埋点一次且 `coupon_id` 为模板 token）、G7 五种 typeSlug 各一 + 其他错误走 toast、G8（禁用 + 提示）、输入为空禁用、大写规范化、LOCKED 不出现（模拟后端不下发即可，App 不过滤——加一条断言：App 不对 `status` 字段做过滤逻辑，列表原样渲染）。示例数据用 UI 稿：Rp 20.000 / Min. Rp 100.000「Kupon pengguna baru」、Rp 20.000 / Min. Rp 300.000「Diskon makanan anjing」Kategori Makanan、Ongkir Maks. Rp 15.000、兑换码 `KUCING2026`。
2. 深链 / 推送：`deep_link_routes_test.dart` 加 `COUPON_EXPIRING → /me/coupons`（targetRef 为空也命中）；`push_payload_test.dart` 加 `pushClickLocation(COUPON_EXPIRING)` 直达；推送点开埋点带 / 不带 `coupon_count` 两例；通知中心读后端 title / 兜底两例；推送设置页分类数不变。
3. **不写 golden**；还原度 ≥ 95 本地打分（L2）。`flutter analyze` 零问题、全量 `flutter test` 绿。

## Tasks / Subtasks

- [ ] **T0 开工核对**：对照 2.2 / 2.3 / 7.1 / 8.1 / 8.2 / 9.1 **已提交代码**核对：券接口字段名（`availableCount`、`usedAt / usedOrderNo / usedOrderToken` 是否已补、`nameId / nameEn`）、P-5 `type` 实际串、`CouponCard` 组件 API 与已建券面 key、`toko_coupon_claimed` 的 `source` 字面量与 `_claimed` 后缀是否已登记、`/me/coupons` 路由与 9.1 入口状态、`COUPON_EXPIRING` 的 `targetRef` 是否为张数；有出入先改本文件
- [ ] **T1 数据层**：`features/shop/coupon/data/coupon_repository.dart`（若 7.1 / 8.1 已建则扩）加 `myCoupons(tab, cursor)`、`redeem(code)`；domain `UserCoupon`（缺键容错）、`CouponTab`；`CouponRedeemError` 枚举（由 `typeSlug` 映射）；`api_paths.dart` 加 `meCoupons` / `meCouponsRedeem`
- [ ] **T2 页面**：`my_coupons_page.dart`（兑换区 / Tab / 列表 / 空 / 失败 / 分页）；路由
- [ ] **T3 兑换**：成功插顶 + Baru、失败框下提示、锁定禁用、埋点
- [ ] **T4 推送与通知**：深链、`pushClickLocation`、推送点开埋点、通知中心标题正文图标与埋点映射
- [ ] **T5 文案**（AC6）+ `flutter gen-l10n`；后缀守卫（AC5）
- [ ] **T6 测试**（见 Dev Notes「测试」）；`resetUserScopedCaches` 登记与登记测试
- [ ] **T7** `flutter analyze` + 全量 `flutter test`；Completion Notes 标「**L1/L2 待本地验收**」

## Dev Notes

### 现状（改动前必读，2026-11-05 核 `dev_1.4.2` @ 2c507755）

- App 内**没有任何券相关代码**：`lib/features/shop/` 下无 `coupon/` 目录，arb 无 `coupon*` key（`grep -n coupon lib/l10n/app_id.arb` 为空）。券卡组件、领券埋点等由 7.1 / 8.1 / 8.2 先建（本 story 在其后），T0 必须先读它们的提交。
- **路由守卫** `core/router/app_router.dart`:172-179 `_controlledLocations` 含 `/me` → `/me/coupons` 游客自动重定向登录；精确例外表（:181-）不需改。
- **深链** `core/router/deep_link_routes.dart`：固定目标类 switch（:72-104）——`PET_SLOT_EXPIRING` / `PET_SLOT_EXPIRED` 先例「后端 targetRef 恒空 → 必须在短路之前」，`COUPON_EXPIRING` 同理放这里。
- **推送点击** `core/push/push_service.dart`：`pushClickLocation`（:63-66）只有 `GROWTH_LEVEL_UP` 直达，其余「有 token → 通知中心」（2026-08-07 产品决策，注释 :225-238）；`_onNotificationClicked`（:239-）在跳转后对 `GROWTH_LEVEL_UP` 补埋点（:265-268）——`toko_coupon_expiry_push_opened` 放同一位置。
- **通知中心** `features/notify/presentation/notification_center_page.dart`：`_typeLabel`（:980-1023）、`_typeBody`（:1026-1083）、图标表（:811-966，`PET_BIRTHDAY` 用 `AppColors.gold` / `goldTint`）、`_notifTypeForAnalytics`（:276-289）、读后端兜底范式 `_refundTitle` / `_refundBody`（:1104-1112）。
- **ProblemDetail** `core/network/problem_detail.dart`:31-36 `typeSlug` = `type` 按 `/` 切的末段（`…/coupon-not-found` → `coupon-not-found`）。
- **埋点守卫** `test/analytics/v112_events_test.dart`：前缀表已含 `toko_`；后缀白名单 `allowedSuffixes`（:375-493）有 `_shown` / `_tapped` / `_opened` / `_sent`，**没有 `_claimed`**。
- **推送设置** `features/notify/presentation/push_settings_page.dart` 按后端分类渲染；`PROMO` 对应「Promo & aktivitas」开关（架构 §1.2 第 15 条）。

### 拍板点（按建议实现，Completion Notes 复述）

1. 可用列表不显示 LOCKED 券（决策日志按推荐定下第 6 条 + D-6），UI G1 说明里的「占用标签」不做。
2. 无「已过期」Tab（PRD 11-05 最新），UI lane 描述中的「已过期」作废。
3. 兑换失败只在输入框下提示、不 toast（UI G7）；未知错误才 toast。
4. 锁定期不做本地倒计时（服务端计时）。
5. 券快过期推送点开直达我的券（PRD / UI），对「通知类推送落通知中心」口径开 `COUPON_EXPIRING` 例外。

### 实现提示

- 券面文案集中在 `CouponCard`，本页不另写一套；需要「已使用」降权、「Baru」标签、「Dipakai…」末行时给 `CouponCard` 加可选参数（默认值保持 7.1 / 8.1 现状，其测试不改）。
- 日期格式：有效期 `d MMM yyyy`（WIB），「Berakhir」带 `HH:mm`；用 `shared/utils/date_format.dart` 现有函数，按 `Localizations.localeOf(context)` 出月份名，时区统一 `Asia/Jakarta`（与后端 `ScheduleWindow.WIB` 一致，P-6）。
- 金额用 `formatIdr`；§6 句式自带「Rp」时传去前缀的数字串（同 9.1 helper）。
- 兑换成功后要让结算页拿到新券：7.1 结算试算每次进入都会重新 `POST /checkout/preview`，一般无需失效；若 7.1 / 8.1 有券列表缓存 provider，在此 `invalidate`。
- 输入框错误态与 `PayoutAccountForm`（9.2）/ 地址表单是同一视觉（红框 + 12 号红字），可直接复用同一 `InputDecoration` 构造。

### 架构约束

- P-2：App 不改券状态（只调兑换接口）；P-5：按 `typeSlug` 分支，**不解析 `detail`**；P-9：埋点字面量；P-10：文案 §6。
- 对外标识：埋点 `coupon_id` 用模板 `couponToken`，不用数字 id。
- 不引新依赖；不整文件 `dart format`。

### 与前后 story 的边界

- **2.2**：领券 / 兑换 / 新人券 / 券查询与防暴力试码（Redis 计数与锁）全部在后端。
- **2.3**：`COUPON_EXPIRING` 落库、推送文案、服务端埋点 `toko_coupon_expiry_push_sent`、PROMO 偏好过滤。
- **7.1**：结算页券选择抽屉（D10 / D11）用的是同一张 `CouponCard`。
- **8.1 / 8.2**：首页「给你的券」、详情页领券、banner 领券（`toko_coupon_claimed` 其他 source）、新人弹窗。
- **9.1**：订单列表「我的券」入口。
- **10.1**：真机收券快过期推送并点开落本页。

### 测试（全部 L0）

- `test/shop/coupon/my_coupons_page_test.dart`（新）：AC7.1 全部。
- `test/shop/coupon/user_coupon_model_test.dart`（新）：缺键容错、`kind` / `scopeType` 未知值降级。
- `test/shop/coupon/coupon_redeem_error_test.dart`（新，纯函数）：`typeSlug` → 错误枚举表驱动（含 429 无 body）。
- `test/notify/deep_link_routes_test.dart`（补）、`test/notify/push_payload_test.dart`（补）、`test/notify/coupon_expiring_notification_test.dart`（新：通知中心标题 / 正文 / 图标 / 埋点映射 + 推送点开埋点）。
- `test/notify/push_settings_page_test.dart`（补分类数不变断言）。
- `test/app/user_scoped_cache_registry_test.dart`（补登记）。
- `test/analytics/v112_events_test.dart`（如需追加 `_claimed`）。

### Project Structure Notes

- 新：`lib/features/shop/coupon/my_coupons_page.dart`（P-4 列名）、`lib/features/shop/coupon/domain/user_coupon.dart`（若 7.1 / 8.1 未建）、`lib/features/shop/coupon/data/coupon_repository.dart`（同上）。
- 改：`app_router.dart`、`api_paths.dart`、`deep_link_routes.dart`、`push_service.dart`、`notification_center_page.dart`、`app.dart`、`coupon_card.dart`（只加可选参数）、两份 arb、可能 `v112_events_test.dart`。

### References

- PRD §3.4「券的状态」「券快过期提醒」「兑换码与过期提醒细节」、§3.0 旅程 E、§4（`toko_coupon_claimed`、`toko_coupon_expiry_push_opened`）、§6 G 组
- 架构 D-2、D-4、D-6、D-21、D-22、D-23、P-2、P-5、P-9、P-10
- 决策日志「按推荐定下」第 6 条、I8
- UI 稿 G1 ~ G9 屏下说明
- epics Story 9.3

## 待确认 / 发现的规划冲突

1. **LOCKED 券在「我的券」显示与否**：UI G1 说明（第三步补）要显示在可用 Tab 并标「Dipakai di pesanan menunggu bayar」；决策日志（按推荐定下）与架构 D-6 定为不显示。按决策日志实现（不显示），UI 该说明作废。
2. **已使用券缺「使用时间 / 订单号」**：G2「Dipakai {date} · {no}」与「可点进订单」需要 `usedAt`、`usedOrderNo`、`usedOrderToken`，2.2 story 的 `CouponView` 未含（`used_order_id` 只在库里）。建议 2.2 在 `tab=used` 时补这三项（订单号与订单 token 都是对外标识，不违反护栏）；未补则 G2 不显示该行。（计数问题已由 2.2 的 `availableCount` 与 `/me/coupons/summary` 解决。）
3. **`COUPON_EXPIRING` 张数位置**：架构 D-22 写「`variant` = 张数」，2.3 story 改为放 `targetRef`（`NotificationItem` 不下发 `variant`）。本 story 按 2.3 读 `targetRef`；属已对齐的偏差，仅登记。
4. **「已使用」Tab 为空没有文案**：§6 只给了可用为空（G4）。本 story 只显示「Belum ada kupon」标题，需产品确认。
5. **券面组件文案 §6 未列全**：「Min. Rp / Maks. Rp / Ongkir / s/d / Berakhir / Kategori / Produk tertentu」在 UI 上出现但 §6 只有 A4「min. belanja Rp {min}（券面『满 X 可用』同此句式）」。按 7.1 / 8.1 已建 key 复用为先；都没建时按 UI 原文新增并待确认，且需决定券面门槛用「Min. Rp」短式还是 §6 A4 的「min. belanja Rp」。
6. **推送点开直达我的券**改变「通知类推送一律落通知中心」的现行口径（同 9.1 对 `SHOP_ORDER_REORDERED` 的处理），按 PRD / UI 实现，请产品确认。

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

#### 待确认

### File List

### Change Log

- 2026-11-05：story 文件创建（ready-for-dev）。
