# Story 1.4: PawCoin 余额与流水页

Status: review

> V1.1 Epic 1（资金地基）第 4 story，接已 done 的 1.1/1.2/1.3。**本批第一个前端(Flutter)story**（全栈：后端只读 GET + Flutter 页）。**brownfield**：**无新 Flyway**（复用 1.2 的 `pawcoin_wallets`/`pawcoin_transactions`）。
> 源：`epics-v1.1.md` Story 1.4 + UX-DR3 · UX `DESIGN.delta.md §3`（PawCoin 余额块）+ `EXPERIENCE.delta.md`（`p-pawcoin-balance`）· 架构 §3.1/§8。
> **范围边界**：余额块 + 只读流水页 + 后端 GET。**不做**充值档位页 `p-pawcoin-recharge` / 失败态 / 暂停态（**Story 1.5**）——本 story「Isi Saldo」按钮只负责跳转到充值路由（1.5 填充）。**不做**转账（PawCoin 不可转赠，禁转账 UI）。

## Story

As a 用户,
I want 在「我的 → PawCoin」看到我的 PawCoin 余额与消费/充值明细,
so that 我清楚自己有多少 koin、花在哪、充了多少（FR-50）。

## Acceptance Criteria

1. **后端 GET 余额+流水端点（L0/L1）**
   **Given** `GET /api/v1/me/pawcoin?cursor=&limit=`（JWT `role=user`，默认 limit 20 上限 50）
   **When** 当前用户请求
   **Then** 返回 `{ balance, items[], nextCursor, hasMore }`；`balance` 复用 `PawCoinWalletService.balanceOf`（无钱包→0）；`items` 复用**已存在**的 `findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc` 游标分页（limit+1 探 hasMore，cursor=末条 epochMillis）（**L1**）
   **And** **仅作用当前 JWT `sub`，绝不接受任意 userId**（防越权，决策 C1）；DTO **只回 `delta/type/refType/createdAt`，不暴露 `id/refId/entryGroup`**（对外不泄可枚举 id / 内部对账字段）（**L0/L1**）

2. **品牌渐变余额头卡（L0）**
   **Given** PawCoin 余额块（UX-DR3 / DESIGN.delta §3）
   **When** 进入余额页
   **Then** 品牌渐变头卡（`brand-primary→brand-secondary` 线性渐变，白字大号余额如「120.000」）+ 副行「= Rp120.000 · 1 koin = Rp1」+ 主按钮「Isi Saldo」；**色值一律引用 theme token，禁硬编码 hex**（**L0**）
   **And** 「Isi Saldo」`context.push('/me/pawcoin/recharge')`（充值页属 1.5，本 story 只接线路由）（**L0**）

3. **只读流水列表（L0）**
   **Given** PawCoin ledger row（继承 canonical）
   **When** 渲染流水
   **Then** 充值/退款（`delta>=0`）绿 `+`、消费（`delta<0`）红 `−`；日期倒序；类型标签按 `type` code 本地化（`TOPUP/SPEND/REFUND/BONUS`）（**L0**）
   **And** **整行非交互、绝不可点**（禁转账 UI，无障碍豁免 44pt；`type` 标签不渲染后端串）（**L0**）

4. **空/错/加载态（L0）**
   **Given** 加载/无流水/请求失败
   **Then** 加载态 spinner；空态 warm 语气「Belum ada catatan…」（内容缺口非 error）；**错误态显式画「加载失败 + 重试」，绝不把请求错误静默画成空态**（bug 20260625-088 纪律）（**L0**）

5. **游标加载更多（L0，若做无限滚动）**
   **Given** `hasMore=true`
   **When** 距底加载更多
   **Then** 追加不清空、失败**保留已加载 + 底部「点击重试」**、不整屏报错不回顶（FeedController 纪律）（**L0**）
   （流水量小可用一次性 `FutureBuilder`，按实现繁简定；两者都须满足 AC4 态。）

6. **双语 l10n（L0）**
   **Given** `app_en.arb` + `app_id.arb` 同步加键 + `flutter gen-l10n`
   **Then** 余额/流水/类型/空态/错误文案 id+en 双套；**源码零硬编码用户可见串**（**L0**）

7. **路由与入口（L0）**
   **Given** `/me/pawcoin` GoRoute（shell 外顶层，隐 Tab Bar；`/me` 前缀已被门控守卫）
   **When** 从「我的」进入
   **Then** `me_page` 加 PawCoin 入口行 `context.push('/me/pawcoin')`；游客深链被既有门控 redirect（**L0**）

8. **端到端视觉（L2）**
   **Given** 真机/模拟器（Android，连正式后端或本地 L1 库）
   **Then** 余额块渐变、正负色、只读流水、空/错态视觉符合 UX（**L2**，留本地）

## Tasks / Subtasks

> 全栈 story：先后端只读端点 → 前端页 → 联调。后端无迁移。

- [x] **T1 后端：DTO + query service**（AC1）
  - [x] `pay/dto/PawCoinTxnItem.java`（record：`delta/type/refType/createdAt` + 静态 `from(PawCoinTransaction)`；**不含 id/refId/entryGroup**）。**L0**
  - [x] `pay/dto/PawCoinWalletView.java`（record：`balance/items/nextCursor/hasMore`）。**L0**
  - [x] `pay/service/PawCoinQueryService.java`（`@Transactional(readOnly)`：`balanceOf` + 游标分页组合，limit+1 探 hasMore，cursor=epochMillis；照 `NotificationCenterService.list`）。复用 `PawCoinTransactionRepository.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc`（**已存在**）。**L0/L1**
- [x] **T2 后端：GET controller**（AC1）
  - [x] `pay/web/PawCoinController.java`（`@RequestMapping("/api/v1/me")` + `@GetMapping("/pawcoin")` + `@AuthenticationPrincipal Jwt` + `currentUserId(jwt)`；`?cursor=&limit=` 夹取 [1,50]）。照 `PawCoinTopupController`/`MeController`。**不动 SecurityConfig**（`/api/v1/me/**` 已需 JWT）。**L1**
- [x] **T3 前端：features/pawcoin/**（AC2-5）
  - [x] `domain/pawcoin_transaction.dart`（`PawCoinTxnItem` + `PawCoinWalletPage` + `fromJson`）。**L0**
  - [x] `data/pawcoin_repository.dart`（`fetch({cursor,limit})` 用 `dioProvider` GET `ApiPaths.mePawcoin` + `Provider`）。**L0**
  - [x] （可选无限滚动）`domain/pawcoin_controller.dart`（`AsyncNotifier<PawCoinState>` + `loadMore/refresh`，照 `feed_controller.dart`）。**L0**
  - [x] `presentation/pawcoin_page.dart`（渐变余额头卡 + Isi Saldo + 只读流水列表 + 空/错/加载态；行不可点；token 引用）。**L0**
- [x] **T4 前端接线**（AC2/6/7）
  - [x] `core/network/api_paths.dart` 加 `mePawcoin = '$base/me/pawcoin'`。**L0**
  - [x] `core/router/app_router.dart` 加 `GoRoute('/me/pawcoin', PawCoinPage)`（shell 外顶层）。**L0**
  - [x] `features/me/presentation/me_page.dart` 加 PawCoin 入口行 `context.push('/me/pawcoin')`。**L0**
  - [x] `lib/l10n/app_en.arb` + `app_id.arb` 加文案键 → `flutter gen-l10n`。**L0**
  - [x] 正负色/渐变用 `core/theme/colors.dart` token（见 Dev Notes 色表），**禁硬编码**。**L0**
- [x] **T5 测试**（AC1-7）
  - [x] 后端 L0：`PawCoinQueryServiceTest`（mock repo/wallet：余额+游标 hasMore/nextCursor、空、DTO 不暴露内部字段）。**L0**
  - [x] 后端 L1：`PawCoinReadIntegrationTest extends ApiIntegrationTest`（干净库：credit 几条后 GET 断言 balance+items+游标；越权：别人 token 拿不到本人流水）。**L1**（留本地净库，见记忆库清库法）
  - [x] 前端 L0：`test/pawcoin/pawcoin_page_test.dart`（Fake repo `overrideWithValue`：余额渲染、正负色/符号、**流水行不可点**、空态、错误态+重试）。照 `test/notify/notification_center_test.dart`。**L0**
  - [x] `flutter analyze`（零警告）+ `flutter test` 绿；后端 `mvn -B package`。云端只跑 L0；L1 留本地净库、L2 留模拟器。

## Dev Notes

> 全部范式来自实测勘探，照抄不另起炉灶。**后端重活已在 1.2/1.3 做完**（余额 + 游标查询方法已存在），本 story 后端 = 薄只读端点；重点是前端首个 feature。

### 承接 1.1-1.3（已 done）
- 1.2 已建 `PawCoinWalletService.balanceOf(userId)`（无钱包→0，`@Transactional(readOnly)`）+ **`PawCoinTransactionRepository.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(long, Instant, Pageable)`（1.2 就为 1.4 预埋）** + `findByUserIdOrderByCreatedAtDesc(long, Pageable)`（首页可用）。
- `PawCoinTransaction` 字段：`id/userId/delta(+入/-出)/type/refType/refId/entryGroup/createdAt(Instant)`。`PawCoinTxnType`=`TOPUP/SPEND/REFUND/BONUS`。
- **无新 Flyway**（表已建）；**不动 SecurityConfig**（`/api/v1/me/**` 默认需 JWT）。

### 后端游标分页范式（照 `notify/service/NotificationCenterService.list` + `notify/dto/NotificationPage`）
```java
@Transactional(readOnly = true)
public PawCoinWalletView view(long userId, String cursor, int limit) {
    long balance = walletService.balanceOf(userId);
    Instant before = parseCursor(cursor);                 // null → Instant.now().plusSeconds(60)
    List<PawCoinTransaction> rows = txns.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
            userId, before, PageRequest.of(0, limit + 1)); // 多取 1 判 hasMore
    boolean hasMore = rows.size() > limit;
    List<PawCoinTransaction> page = hasMore ? rows.subList(0, limit) : rows;
    List<PawCoinTxnItem> items = page.stream().map(PawCoinTxnItem::from).toList();
    String nextCursor = hasMore && !page.isEmpty()
            ? String.valueOf(page.get(page.size() - 1).getCreatedAt().toEpochMilli()) : null;
    return new PawCoinWalletView(balance, items, nextCursor, hasMore);
}
// parseCursor: 空/非法 → now+60s；否则 Instant.ofEpochMilli(Long.parseLong(cursor))
```
Controller 接 `?cursor=&limit=`：`int size = limit==null?20:Math.min(Math.max(limit,1),50);`。**DTO 只暴露展示字段**（`delta/type/refType/createdAt`），`refId`/`entryGroup`/`id` 不外泄。

### 前端 feature 三层（照 `features/notify` + `features/content`）
新建 `features/pawcoin/{data,domain,presentation}`：
- **repository**（`dioProvider` GET，`AuthInterceptor` 自动带 JWT/续期，repository 不碰 token/401）：
```dart
Future<PawCoinWalletPage> fetch({String? cursor, int limit = 20}) async {
  final resp = await dio.get<Map<String, dynamic>>(ApiPaths.mePawcoin,
      queryParameters: {if (cursor != null) 'cursor': cursor, 'limit': limit});
  return PawCoinWalletPage.fromJson(resp.data!);
}
final pawCoinRepositoryProvider = Provider((ref) => PawCoinRepository(dio: ref.read(dioProvider)));
```
- **无限滚动**（若做）：`AsyncNotifier<PawCoinState>` + `copyWith`，`loadMore` 失败保留已加载 + `loadMoreFailed` 底部重试（照 `feed_controller.dart`）。流水量小也可 `FutureBuilder` 一次拉。
- **page**：错误态照 `notification_center_page.dart` —— `snapshot.hasError`/`AsyncError` 显式画错误+重试，**绝不静默画空态**（bug 20260625-088）。

### api_paths / 路由 / 入口
- `core/network/api_paths.dart`：`static const String mePawcoin = '$base/me/pawcoin';`（base=`/api/v1`，先例 `mePosts`）。
- `core/router/app_router.dart`：在 `StatefulShellRoute` **之外**顶层 `routes:` 加 `GoRoute(path:'/me/pawcoin', builder:(c,s)=>const PawCoinPage())`（shell 外顶层 push 自动隐 Tab Bar；`_controlledLocations` 已含 `/me` 前缀，游客自动守卫，**无需改门控**）。
- `features/me/presentation/me_page.dart`：加入口行，`onTap: () => context.push('/me/pawcoin')`（照现有 `context.push('/me/settings')`）。
- Isi Saldo 按钮 → `context.push('/me/pawcoin/recharge')`（该页属 **Story 1.5**；本 story 只接线，1.5 填充充值档位/失败/暂停态）。

### 主题色 token（`core/theme/colors.dart`；⚠️主色是紫 violet #845EC9，`mint*` 是遗留别名，勿被名字骗）
| 用途 | token | 值 |
|---|---|---|
| 主色/渐变强调 | `AppColors.mint` | #845EC9 |
| 渐变副色/浅底 | `AppColors.mintTint` / `cream2` | #F8F2FF |
| **消费(负)红** | `AppColors.coral` | #F0425A |
| **充值/退款(正)绿** | `AppColors.triageGreen` | #1F9E6A |
| 主/次/弱文字 | `ink`/`ink2`/`muted` | #2E2A45 / #544864 / #9690A6 |
| 卡面/分割线 | `card` / `line` | #FFF / #E6E6E6 |

`delta>=0 → triageGreen`（前缀 `+`）；`delta<0 → coral`（值自带 `-`）。**一律引用 token，禁 hex**。

### l10n（双写 ARB + gen-l10n；类型标签按 code 本地化，不渲染后端串）
`app_en.arb` 加（`app_id.arb` 印尼语对应）：`pawcoinBalanceLabel`/`pawcoinTxnListTitle`/`pawcoinEmpty`/`pawcoinEmptyHint`/`pawcoinLoadFailed`/`pawcoinLoadRetry`/`pawcoinTxnTopup`/`pawcoinTxnSpend`/`pawcoinTxnRefund`/`pawcoinTxnBonus`/`pawcoinIsiSaldo`/`pawcoinRateHint`（"= Rp{amount} · 1 koin = Rp1"，带占位符）。改完必跑 `flutter gen-l10n`（否则编译失败）。类型标签用 `switch(type)` 映射本地化键（照 notify `_typeLabel`）。

### UX 规格（DESIGN.delta §3 + EXPERIENCE.delta）
- 品牌渐变头卡：`{colors.brand-primary}`→`{colors.brand-secondary}` 线性渐变，白字大号余额「120.000」+ 副行「= Rp120.000 · 1 koin = Rp1」+ primary「Isi Saldo」。
- 流水行沿用 canonical ledger row：消费红 `−`/充值·退款绿 `+`；**整行 `lrow` 绝不可点**（禁转账 UI，无障碍豁免 44pt）。
- 入口：Profil → PawCoin（也是任意 paywall「余额不足」deep-link 目标 `/me/pawcoin`）。
- 空态 warm 语气；**禁 emoji 与俏皮**（PawCoin 组件保持克制）；金额永不截断（dynamic type）。

### 测试范式
- 后端 L0：`@ExtendWith(MockitoExtension.class)` mock repo/wallet；L1 `extends ApiIntegrationTest`（**干净库**，见记忆库清库法：专用 `petgo_l1` + `mvn clean` + `DB_NAME` override）。
- 前端 L0：Fake repo 继承真 repository + `overrideWithValue` 注入（照 `test/notify/notification_center_test.dart`）；`MaterialApp` 带 `AppLocalizations.localizationsDelegates` + `locale: Locale('en')`；测空态/余额/正负色/**行不可点**/错误态。需登录态时照 `test/consult/consult_history_test.dart`（`UncontrolledProviderScope` + applyLogin + 大视口）。
- 目录 `test/pawcoin/`。云端只跑 L0（`flutter analyze`+`flutter test`+`mvn -B package`）；L1 净库、L2 Android 模拟器留本地。

### Project Structure Notes
- 后端新增全落 `com.tailtopia.pay/{dto,service,web}`；前端新增 `features/pawcoin/*` + 改 4 处接线文件（api_paths/router/me_page/arb×2）。
- 复用不重造：`balanceOf` + 游标 repo 方法 + dio/interceptor/theme/l10n/router 既有基建。

### References
- [Source: epics-v1.1.md#Story 1.4] · [UX-DR3]
- [Source: /Users/dai/work/petGo/V1.1.0/ux-delta/DESIGN.delta.md#3. PawCoin balance block]
- [Source: /Users/dai/work/petGo/V1.1.0/ux-delta/EXPERIENCE.delta.md#p-pawcoin-balance]
- [Source: architecture-v1.1-delta.md#§3.1 资金核心][#§8 前端 Delta]
- [Source: 1-2-双分录总账与pawcoin钱包.md][1-3-pawcoin充值下单与到账.md]（已 done）
- 代码范式：后端 `notify/service/NotificationCenterService.java` + `notify/dto/NotificationPage.java`、`auth/web/MeController.java`、`pay/web/PawCoinTopupController.java`、`pay/service/PawCoinWalletService.java`、`pay/repository/PawCoinTransactionRepository.java`；前端 `features/notify/{data,domain,presentation}`、`features/content/presentation/feed_controller.dart`、`core/network/{api_paths,dio_client,problem_detail}.dart`、`core/router/app_router.dart`、`core/theme/colors.dart`、`features/me/presentation/me_page.dart`、`test/notify/notification_center_test.dart`

### 待澄清（不阻塞）
- [OPEN] 流水是否做无限滚动 vs 一次拉：按 V1 用户流水量（多数几条）可先一次拉 20，`hasMore` 时再加载更多；实现者按繁简定，两者都须满足 AC4 态。
- [OPEN] 「Isi Saldo」目标页 `/me/pawcoin/recharge` 由 Story 1.5 建；1.4 期间该路由未填则按钮暂无落地页（同一 sprint 顺序推进，可接受；或 1.5 先行）。

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m]（bmad-dev-story，2026-07-11）

### Debug Log References

- 全量 `flutter test` 有 1 个失败 `test/auth/story_1_5_gating_test.dart::AC2 已登录点受控Tab`（"Timer still pending"）——**stash 全部本 story 改动后纯 HEAD 复现，确认为既有 flaky（问诊 hub AI 脉冲常驻动画），非本 story 引起**。

### Completion Notes List

- **全栈实现完成，全绿**：后端薄只读 GET `/api/v1/me/pawcoin`（复用 1.2 预埋的 `balanceOf` + `findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc`，无新 Flyway）+ Flutter 首个 `features/pawcoin/`（渐变余额头卡 + 只读流水）。
- **后端 L0** `PawCoinQueryServiceTest` 3/3；**后端 L1** `PawCoinReadIntegrationTest` 3/3（净库 petgo_l1：余额+流水、游标分页、**越权隔离**别人 token 拿不到本人流水）。
- **前端 L0**：`flutter analyze` 0 问题；`pawcoin_page_test` 3/3（余额渲染、+绿/−红符号、空态、错误态+重试不静默画空态）；`test/l10n` 契约 4/4（en/id 键对齐）。
- **护栏全落实**：流水行 `_LedgerRow` 无 InkWell/onTap（禁转账 UI）；错误态显式（bug 20260625-088）；类型按 code 本地化 `_typeLabel`；色值全引用 `AppColors` token；DTO 只回 `delta/type/refType/createdAt`（不暴露 id/refId/entryGroup，L1 断言）。
- **范围边界**：「Isi Saldo」→ `/me/pawcoin/recharge`（充值页属 Story 1.5，本 story 只接线）。
- **L2 已本地视觉验收（2026-07-11，Android emulator-5554）**：真机连本地后端（petgo_l1，seed 演示数据）走 桩会话 → 我的 → PawCoin Balance，页面完美渲染——紫色渐变余额头卡「120.000 = Rp120.000 · 1 koin=Rp1」+ Top Up + 只读流水（Refund +5.000 绿 / Spent −5.000 红 / Top-up +100.000 绿 …倒序、类型本地化、千分位、行不可点），与 UX-DR3 一致；「我的」页 PawCoin 入口行正确渲染。

### File List

**后端（新增）**
- `petgo-backend/src/main/java/com/tailtopia/pay/dto/PawCoinTxnItem.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/dto/PawCoinWalletView.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/service/PawCoinQueryService.java`
- `petgo-backend/src/main/java/com/tailtopia/pay/web/PawCoinController.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/service/PawCoinQueryServiceTest.java`
- `petgo-backend/src/test/java/com/tailtopia/pay/PawCoinReadIntegrationTest.java`

**前端（新增）**
- `petgo_app/lib/features/pawcoin/domain/pawcoin_transaction.dart`
- `petgo_app/lib/features/pawcoin/data/pawcoin_repository.dart`
- `petgo_app/lib/features/pawcoin/presentation/pawcoin_controller.dart`
- `petgo_app/lib/features/pawcoin/presentation/pawcoin_page.dart`
- `petgo_app/test/pawcoin/pawcoin_page_test.dart`

**前端（修改·接线）**
- `petgo_app/lib/core/network/api_paths.dart`（+`mePawcoin`）
- `petgo_app/lib/core/router/app_router.dart`（+`/me/pawcoin` GoRoute + import）
- `petgo_app/lib/features/me/presentation/me_page.dart`（+PawCoin 入口行 `_PawCoinEntry`）
- `petgo_app/lib/l10n/app_en.arb` + `app_id.arb`（+PawCoin 文案键；`flutter gen-l10n` 已重生成）

### Change Log

- 2026-07-11：Story 1.4 全栈实现完成（后端只读 GET + Flutter 余额与流水页），L0/L1 全绿，Status → review。
