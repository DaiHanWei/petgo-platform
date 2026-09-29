# Story 3.2: Tailsonality 结果解锁与挽留弹窗

Status: ready-for-dev

## Story

As a 想看完整解读的用户,
I want 花 Rp5.000 解锁这次结果,
so that 读到只属于我家毛孩子的深度解读、拿到无水印的卡。

## Acceptance Criteria

**AC1 — 发起解锁接口** `[L1]`
`POST /api/v1/pet-profiles/me/tailsonality/results/{token}/unlock`，body `{ "channel": "PAWCOIN" | "QRIS" }`（复用 `HdPurchaseRequest` 同款 `@Valid` 结构或新建同形 `KeepsakePayRequest`），需 `USER` 角色。
1. `{token}` 按 2-1 的结果查找（`TailsonalityResultRepository` 新增带 `@Lock(PESSIMISTIC_WRITE)` 的 `findForUpdateByPublicTokenAndPetProfileId`）：非本人 / 不存在 / 所属宠物已删 → 404（与 2-1 `GET …/results/{token}` 同一不可区分口径）。
2. **加行锁**读结果行（`SELECT … FOR UPDATE`），构造 `KeepsakeRef(TAILSONALITY, result.id, result.publicToken, result.petProfileId, result.unlockedAt != null)` → `KeepsakePurchaseService.start(userId, ref, channel)`（3-1）。
3. 已解锁 → 409 `keepsake-already-unlocked`；MIXED → 422；余额不足 → 409 `pawcoin-insufficient`（均由 3-1 抛出，本 story 不另造）。
4. 响应即 `KeepsakePurchaseResponse`（`unlocked` / `payment` / `payload` / `purchaseToken`）。
5. 限流 `rl:tailsonality:unlock:{userId}` 10 次 / 分钟；`SecurityConfig` 加**精确** matcher `POST /api/v1/pet-profiles/me/tailsonality/results/*/unlock` → `hasRole("USER")`（该前缀当前落在 `anyRequest().authenticated()`，兽医 token 可进）。

**AC2 — 发放（grant）** `[L1]`
tailsonality 包新建 `TailsonalityKeepsakeGranter implements KeepsakeGranter`（`sku() = TAILSONALITY`）：
1. `grant(refId, purchaseId)`：`UPDATE tailsonality_results SET unlocked_at = now() WHERE id = :refId AND unlocked_at IS NULL`；影响 1 行 → `GRANTED`；0 行且行存在 → `ALREADY_UNLOCKED`；行不存在 → `REF_MISSING`。**不抛异常**（DB 异常也 catch 后返回 `REF_MISSING` 并 log.error，只记 refId）。
2. 本 story 的 grant **只**置 `unlocked_at`；自动佩戴由 3-3 在同一方法内追加（留好扩展点，不要在别处另起监听器）。
3. 结果 DTO（2-1 交付）的 `unlocked` 字段 = `unlocked_at != null`；重测产生的新结果恒为锁态，旧结果 `unlocked_at` 不受影响（集成测试：解锁 A → 重测得 B → A 仍 unlocked、B 未解锁）。

**AC3 — 服务端埋点** `[L0]` `[L1]`
1. 新监听器 `TailsonalityUnlockAnalyticsListener`：`@TransactionalEventListener(AFTER_COMMIT)` 订阅 3-1 的 `KeepsakeUnlockedEvent`，`sku == TAILSONALITY` 时发 `tailsonality_unlocked`，属性 `role_code`（**完整代号**如 `ENTJ-H`）、`price`（`priceIdr`）、`result_index`（该宠物按 `created_at` 的序号，与 DTO 同算法）；照 `KtpUnlockAnalyticsListener` 写法（不加 `@Async`、不写库、`AnalyticsDistinctId.of(userId)`）。
2. `AnalyticsEventGuard`：`ALLOWED_EVENTS` 加该事件名（**引用常量**，照 L49-62 注释），`ALLOWED_PROPERTY_KEYS` 加 `role_code`、`price`、`result_index`。已有 `AnalyticsEventGuard` 测试按新白名单补断言。
3. 属性不得带宠物名、品种、token、自由文本（AD-19）。

**AC4 — 结果页锁态区的购买入口** `[L2]` `[L0]`
在 2-4 交付的结果页（未解锁态）锁态区底部：
1. 主按钮「Buka Rp{价格}」，价格读 3-1 扩展后的 `GET …/id-cards/pricing` 的 `tailsonalityUnlockPrice`，**不硬编码**；取价中按钮显示「…」禁用，失败显示 `PriceLoadRetry`（照 `hd_paywall_sheet.dart` L94-101）。2-4「本 epic 内锁态区不显示购买按钮」的源码 / widget 断言按新规则更新（改为「未解锁显示、已解锁不显示」）。
2. 点击 → 选渠道抽屉（**复用 KTP 选渠道抽屉**，见 AC6）→ PawCoin 立即解锁；QRIS → `showQrPaymentSheet(context, payload:, orderRef: displayNo, pollPaid: 刷新该结果 → unlocked)`（`shared/widgets/qr_payment_sheet.dart`，**组件一行不改**）。
3. PawCoin 余额不足：抽屉内 PawCoin 行置灰 +「Isi dulu」跳 `/me/pawcoin/recharge`（抽屉既有行为）；服务端返回 409 且 `typeSlug == 'pawcoin-insufficient'` → 既有文案 `idCardHdInsufficientBalance`；`typeSlug == 'keepsake-already-unlocked'` → 静默刷新结果（视为已解锁）。**不要**像 KTP 详情页那样「409 一律当余额不足」（`id_card_detail_page.dart` L373-375），必须按 `ProblemDetail.typeSlug` 分流。
4. 解锁成功（PawCoin 同步 / QRIS 轮询到 true）→ invalidate `tailsonalityResultProvider(token)`、`tailsonalityResultsProvider`（2-3）、`pawCoinProvider`；toast「Hasil sudah kebuka!」。
5. 用户关掉 QR 面板未付 → 页面保持锁态，不报错（后续到账由服务端发放，下次进页即为已解锁）。

**AC5 — 已解锁态渲染** `[L2]`
1. 同一结果页切换为已解锁态（A9）：结果卡**去水印**（2-4 的 `card_watermark` 按 `unlocked` 关闭）；锁态区替换为付费内容，顺序固定：**角色专属深读（`Khusus buat {code}`）→ 四段维度解读（按 4 个字母各取一段拼装，每段前标该轴两极字母，本极高亮）→ 能量段（`Level energi High|Low` + 对应文案）**；文案取 2-2 的 Dart 内容表（按 4 字母 / 字母 / 能量寻址），EN / ID 按 locale。
2. 已解锁页底部**不显示**任何主 CTA（「Bagikan」由 Epic 4 的 4-1 接上；本 story 不放占位按钮）。
3. 已解锁结果为购买时快照：本版本文案随包固定（`content_version = 1`），不做服务端文案。

**AC6 — 选渠道抽屉复用** `[L0]` `[L2]`
1. 把 `features/profile/presentation/id_card/hd_paywall_sheet.dart` 的「渠道选择 + 价格行 + 确认按钮」主体抽成通用组件（建议 `lib/shared/widgets/pay_channel_picker.dart`：参数 `header`（Widget）、`title`、`body`、`AsyncValue<int> price`、`onRetryPrice`、`balance`，返回 `HdPayChannel?`）；`HdPaywallSheet` 改为该组件的薄包装，**KTP 视觉、文案与 key（`hdPayConfirm`、`hdPriceRetry`）逐字不变**，既有 KTP 相关 widget 测试全绿即为验收。
2. Tailsonality 用同一组件：header = 结果卡缩略（带水印）+ 代号 + 角色名；title「Buka analisis lengkap」；body「Deep dive khusus {pet}, 4 dimensi, level energi, plus kartu tanpa watermark.」；确认按钮「Bayar Rp{价格}」。
3. 新建 `lib/features/keepsake/`（NEW，3-4 / 3-5 复用）：
   - `domain/keepsake_pricing.dart`：`KeepsakePricing(ktpHd, passportSnapshot, boardingPass, tailsonality)`，`fromJson` 读 `price` / `passportPageUnlockPrice` / `passportBoardingUnlockPrice` / `tailsonalityUnlockPrice`，**任一 ≤0 或缺失即抛**（无本地兜底价，照 `DioIdCardRepository.hdPrice` L132-140）；契约测试。
   - `data/keepsake_repository.dart`：`keepsakePricingProvider`（`FutureProvider.autoDispose`）。
   - `domain/keepsake_purchase_result.dart`：解析 `KeepsakePurchaseResponse`（可直接复用 `HdPurchaseResult.fromJson` 的字段口径，另加 `purchaseToken`）。
   - `presentation/keepsake_pay_flow.dart`：`Future<bool> runKeepsakePurchase(...)` 串起「读余额 → 选渠道抽屉 → 调发起接口 → PawCoin 即得 / QRIS 面板轮询」，入参为发起函数与轮询函数，三类 SKU 共用；错误按 `typeSlug` 分流（AC4.3）。

**AC7 — 挽留弹窗** `[L2]` `[L0]`
1. 触发：结果页处于**未解锁态**时用户点返回（系统返回 / AppBar 返回，用 `PopScope(canPop: false, onPopInvokedWithResult: …)` 统一拦），且**本机未对该结果 token 弹过** → 弹 A10：标题「Yakin keluar?」· 正文「Kalau di-unlock, badge kepribadian {pet} bisa dipasang di profilnya — kelihatan sama semua yang mampir. Analisis lengkapnya juga kebuka.」· 次按钮「Nanti aja」（关弹窗并退出页面）· 主按钮「Unlock」（关弹窗、留在页面、直接进 AC6 的购买流程）。
2. **客户端本地按结果 `public_token` 记录**：弹出即记（不论点哪个按钮）；存 `AppPrefs` 新键 `petgo.tailsonality_retention_shown`（`List<String>`，上限保留最近 50 个 token 防无限增长）；换设备再弹一次可接受（AD-3）。已解锁结果、配型页、说明抽屉、答题页都**不弹**。
3. 弹窗样式照项目既有 `AlertDialog` 用法（如 `showMediaPermissionDeniedDialog`），按钮热区 ≥44×44。
4. widget 测试：未解锁 + 未记录 → 返回弹出；点「Nanti aja」→ 页面 pop 且 token 已记录；再次进入同一结果点返回 → 直接退出不弹；已解锁 → 不弹。

**AC8 — App 埋点** `[L0]`
照 `KtpUnlockAnalytics`（`id_card/ktp_unlock_analytics.dart`）新建 `TailsonalityUnlockAnalytics`：
- `tailsonality_unlock_viewed`：锁态区首次进入可视区域时报一次 / 页面实例（属性 `role_code`、`price`、`result_index`）；
- `tailsonality_unlock_initiated`：抽屉内选定渠道并确认时报（`role_code`、`price`、`result_index`、`method`）；
- `tailsonality_paywall_abandoned`：挽留弹窗点「Nanti aja」时报（`result_index`）；
- **不在 App 报 `tailsonality_unlocked`**（成功只由服务端报，理由同 KTP：用户关掉 QR 面板后付款 App 不知道）。
- 属性值只有代号、数字、渠道枚举；App 的 `Analytics` 会丢弃 `name` / `breed` / `token` / `title` / `text` 等键（`core/analytics/analytics.dart` L38-58），不要用这些键名。

## Tasks / Subtasks

- [ ] **T0 核对前置**：2-1（结果表 / DTO / 仓库名）、2-2（内容表寻址 API）、2-4（结果页文件名、锁态区 widget、水印开关）、2-6（结果列表 provider）实际代码；3-1 的 `KeepsakePurchaseService` / `KeepsakeRef` / `KeepsakeGranter` 实际签名
- [ ] **T1 后端：解锁接口**（AC1）
  - [ ] tailsonality 控制器加端点 + 限流常量；结果仓库加 `findForUpdateByPublicTokenAndUserId`
  - [ ] `SecurityConfig` 精确 matcher
  - [ ] 集成测试：本人未解锁 PawCoin → 200 unlocked=true、结果行 `unlocked_at` 非空、钱包扣 5000；QRIS → payload 非空、购买行 PENDING；非本人 404；已解锁 409；MIXED 422
- [ ] **T2 后端：granter**（AC2）
  - [ ] `TailsonalityKeepsakeGranter` + 单测（三种 outcome、异常不外抛）
  - [ ] 集成测试：模拟 QRIS 到账（`applyCallback` PAID）→ 结果行解锁；重测后新结果锁态、旧结果仍解锁
- [ ] **T3 后端：埋点**（AC3）
- [ ] **T4 App：keepsake 公共层**（AC6.1、AC6.3）
  - [ ] 抽 `pay_channel_picker.dart`；`HdPaywallSheet` 改薄包装；跑 KTP 既有测试
  - [ ] `lib/features/keepsake/` 三个文件 + 契约测试（pricing 缺字段 / 0 值抛错）
  - [ ] `ApiPaths` 加 `tailsonalityResultUnlock(token)`
- [ ] **T5 App：结果页接入**（AC4、AC5、AC6.2）
  - [ ] 锁态区按钮 + 购买流程；已解锁态付费区拼装；去水印
  - [ ] 更新 2-4 的「不显示购买按钮」断言
  - [ ] widget 测试：锁态显示价格按钮（mock 价格 5000 → 「Buka Rp5.000」）；价格失败显示重试；PawCoin 成功切已解锁态且付费区三段顺序正确；409 `keepsake-already-unlocked` 不提示余额不足
- [ ] **T6 App：挽留弹窗**（AC7）+ `AppPrefs` 新键
- [ ] **T7 App：埋点**（AC8）
- [ ] **T8 l10n**：en + id 同 key（见下表），`flutter gen-l10n`；`microcopy_rules_test` 通过
- [ ] **T9 联调**（L1 本地 / L2 stag 测试渠道真实 QRIS）

## Dev Notes

⚠️ 前置 story 尚未实现：开工前先对照其实际代码核对本文件引用的类名/接口/字段，有出入先改本文件。（依赖 2-1 / 2-2 / 2-4 / 2-6 与 3-1；下文凡标「2-x 交付」「3-1 交付」的名字都是预期名。）

### 必读：会被本 story 改到的现有代码

| 文件 | 现状 | 本 story 改什么 | 必须保留 |
|---|---|---|---|
| `petgo_app/lib/features/profile/presentation/id_card/hd_paywall_sheet.dart` | `HdPaywallSheet(petName, serialId, cardNo, avatarUrl, balance)`；内部 `ref.watch(idCardHdPriceProvider)`；余额够默认 PawCoin，不够 PawCoin 行置灰 +「充值」跳 `/me/pawcoin/recharge`；价格未取到禁确认（L129-132）；返回 `HdPayChannel` | 抽出通用主体，本类变薄包装 | KTP 头部渐变卡 `'${petName} · No. $no'`、文案 key、`ValueKey('hdPayConfirm')` / `hdPriceRetry`、PawCoin 默认选中规则 |
| `petgo_app/lib/shared/widgets/qr_payment_sheet.dart` | `showQrPaymentSheet(context, payload, pollPaid, orderRef, onAborted) → Future<bool>`；3s 轮询；L27-30「返回类型恒为 bool，新能力走可选参数」 | **不改**，只调用 | 全部 |
| `petgo_app/lib/features/profile/presentation/id_card_detail_page.dart` L297-383 | KTP 付费流程：读余额 → 抽屉 → `purchaseHdForCard` → unlocked / QR 轮询 `idCardDetailProvider` | **不改**；作为 `runKeepsakePurchase` 的范式 | — |
| `petgo_app/lib/features/profile/data/id_card_repository.dart` L132-156 | `hdPrice()` 只取 `price`；`idCardHdPriceProvider` autoDispose、无兜底 | 不改（新价格走 keepsake 层独立解析同一接口） | `idCardHdPriceProvider` 语义 |
| `petgo_app/lib/features/profile/domain/id_card.dart` L274-310 | `HdPayChannel {qris, pawcoin}`（`wire`）、`HdPurchaseResult.fromJson` | 复用 `HdPayChannel`（不新建渠道枚举） | — |
| 2-3 / 2-4 交付的 `TailsonalityResultPage`（`lib/features/tailsonality/presentation/tailsonality_result_page.dart`，路由 `TailsonalityRoutes.result(token)` = `/profile/pet-insights/tailsonality/results/:token`，数据 `tailsonalityResultProvider(token)`） | 未解锁态：卡带水印、摘要、配型引流、锁态区无按钮；⋯ 菜单仅「Tes Ulang」 | 锁态区加购买按钮；已解锁态付费区；返回拦截 | ⋯ 菜单、配型引流、重测确认 |
| 2-1 交付的 tailsonality 控制器 / `TailsonalityResultRepository`（`findByPublicTokenAndPetProfileId` 等）/ `TailsonalityResultResponse`（`token`、`typeCode`=完整代号、`letters`、`energy`、`resultIndex`、`unlocked`、`unlockedAt`） | 提交 / 列表 / 单条 | 加 unlock 端点 | 既有三端点 |
| `petgo-backend/.../shared/analytics/AnalyticsEventGuard.java` L46-86 | 事件名 / 属性键双白名单 | 加 1 事件 + 3 键 | 既有条目 |
| `petgo-backend/.../shared/security/SecurityConfig.java` | `/api/v1/pet-profiles/**` 无专门 matcher，落 `anyRequest().authenticated()`（L303） | 加精确 matcher | 其余规则顺序 |
| `petgo_app/lib/core/storage/prefs.dart` | `AppPrefs` 按键存本地标记 | 加 `petgo.tailsonality_retention_shown` | 既有键与 `_kRemovedKeys` 清理逻辑 |

### 可直接复用

| 要做的事 | 用这个 | 位置 |
|---|---|---|
| 购买发起 / 到账 / 幂等 | `KeepsakePurchaseService.start`、`KeepsakePaidHandler`（3-1） | `com.tailtopia.purchase` |
| 支付面板 | `showQrPaymentSheet` | `shared/widgets/qr_payment_sheet.dart` |
| 价格失败重试 | `PriceLoadRetry` | `shared/widgets/price_load_retry.dart` |
| 余额 | `pawCoinProvider`（`.balance`） | 见 `id_card_detail_page.dart` L300 |
| 错误分流 | `ProblemDetail.typeSlug` | `core/network/problem_detail.dart` |
| 服务端埋点监听器 | `KtpUnlockAnalyticsListener` | `profile/service/KtpUnlockAnalyticsListener.java` |
| App 埋点封装 | `KtpUnlockAnalytics` | `features/profile/presentation/id_card/ktp_unlock_analytics.dart` |
| 水印 | `card_watermark.dart` | `shared/card_render/` |
| 本地标记 | `AppPrefs.create()` | `core/storage/prefs.dart` |

### 关键设计点

- **价格只从服务端读**：D-2 已作废 PRD / 内容设计「Rp5,000 硬编码」；展示价与扣款价同源（`pricing_config`），用户付的是发起那一刻的价（3-1 `price_idr`）。
- **已解锁判定只看服务端**：`unlocked_at` 是唯一真相；App 不做乐观解锁（QRIS 面板关闭后仍可能到账）。
- **挽留卖点是「佩戴」**：文案取内容设计 §2.3（比 epics / UI 稿多一句「Analisis lengkapnya juga kebuka.」——优先级 内容设计 > UI）。
- **只弹一次的粒度是结果 token**，不是宠物、不是账号；重测出新结果会再有一次机会。
- **不要**给结果页加「分享 / 发帖」按钮：属 Epic 4。
- 合规：新文案、埋点、标识符中不得出现 `mbti`（AD-20 静态扫描由 2-2 建立，本 story 的新代码同样受扫）。

### l10n 新 key（en + id，两文件同 key，id 为主口吻）

| key | id | en |
|---|---|---|
| `tailsonalityUnlockCta` | Buka Rp{price} | Unlock Rp{price} |
| `tailsonalityPaywallTitle` | Buka analisis lengkap | Unlock the full reading |
| `tailsonalityPaywallBody` | Deep dive khusus {pet}, 4 dimensi, level energi, plus kartu tanpa watermark. | A deep dive just for {pet}: 4 dimensions, energy level, plus the card without watermark. |
| `tailsonalityPayConfirm` | Bayar Rp{price} | Pay Rp{price} |
| `tailsonalityUnlockedToast` | Hasil sudah kebuka! | Result unlocked! |
| `tailsonalityDeepDiveTitle` | Khusus buat {code} | Just for {code} |
| `tailsonalityDimensionsTitle` | Empat dimensi | Four dimensions |
| `tailsonalityEnergyTitle` | Level energi | Energy level |
| `tailsonalityEnergyHigh` | High | High |
| `tailsonalityEnergyLow` | Low | Low |
| `tailsonalityRetainTitle` | Yakin keluar? | Wait a sec. |
| `tailsonalityRetainBody` | Kalau di-unlock, badge kepribadian {pet} bisa dipasang di profilnya — kelihatan sama semua yang mampir. Analisis lengkapnya juga kebuka. | Unlock it and {pet}'s personality badge goes on its profile — visible to everyone who visits. Full reading included. |
| `tailsonalityRetainLater` | Nanti aja | Not now |
| `tailsonalityRetainUnlock` | Unlock | Unlock |

`{price}` 由调用方按印尼千分位格式化（点分隔，照 `HdPaywallSheet._fmt`），不要在 ARB 里用数字格式占位。余额不足沿用 `idCardHdInsufficientBalance`、失败沿用 `idCardHdPurchaseError`。`microcopy_rules_test`：两文件 key 集相同、每串最多一个 emoji、en 不含印尼语（「Rp」为币种符号，允许）。

### 验证层级

L0：AC3 白名单测试、AC6 契约与 KTP 回归、AC7 widget 测试、AC8 · L1：AC1、AC2、AC3 事件在到账后发出 · L2：AC4、AC5、AC7 视觉，真实 QRIS（stag 测试渠道）

### Project Structure Notes

- 后端：granter / 控制器端点 / 埋点监听器都在 `com.tailtopia.tailsonality`；只依赖 purchase 包公开类型，**不**直接碰 `keepsake_purchases` 表。
- App：通用支付层放 `lib/features/keepsake/` 与 `lib/shared/widgets/pay_channel_picker.dart`，3-4 / 3-5 直接复用；Tailsonality 页面改动限 `lib/features/tailsonality/`。

### References

- [Source: _bmad-output/planning-artifacts/v1.3.2/epics-v1.3.2-batch-a.md#Story 3.2]
- [Source: _bmad-output/planning-artifacts/v1.3.2/architecture-v1.3.2-batch-a-delta.md#AD-1, AD-3, AD-12, AD-19, AD-20]
- [Source: _bmad-output/planning-artifacts/v1.3.2/决策日志-batch-a.md#D-2]
- [Source: _bmad-output/planning-artifacts/v1.3.2/tailsonality-内容设计.md#2.2, 2.3, 5.1]
- [Source: _bmad-output/planning-artifacts/v1.3.2/PRD-v1.3.2-batch-a.md#3.2 付费点, #4 E-11B/E-11D]
- [Source: _bmad-output/planning-artifacts/v1.3.2/ui-v1.3.2-batch-a.html#A8, A9, A10, A11]
- [Source: _bmad-output/planning-artifacts/v1.3.2/代码核对报告-batch-a.md#1 支付]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created

### File List
