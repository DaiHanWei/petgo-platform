/// Toko 首页 —— **设计稿版式**（V1.4.0 · `design_handoff_ecommerce/01_screens_browse_order.md` 屏 1/2）。
///
/// 与 [TokoPage]（v1 版式）**并存**，由 `shopUiVariantProvider` 二选一。
/// 两者走同一批 provider、同一条路由，差异只在渲染层 —— 见 `shop_ui_variant.dart` 的说明。
///
/// ## 🔴 设计稿要求但**当前无数据源**的四处
///
/// 设计稿自带「空态即不渲染」规则，所以下面四处按规则降级后<b>仍是合规实现</b>，
/// 不是半成品。数据补齐后各自打开即可，布局不用改：
///
/// | 设计元素 | 缺什么 | 按哪条规则降级 |
/// |---|---|---|
/// | 顶部促销条（免运门槛 + 倒计时） | 无免运/促销接口 | 「无免运活动时凑单条与促销条整条不渲染」→ 整条不画 |
/// | 横滑第 3 位的视频卡 | 无内容素材接口 | 「无视频素材时该位补商品，不留空」→ 第 3 位就是商品 |
/// | 卡片的 `★ 评分 · 已售数` | 列表接口无这两个字段 | 「无数据时整行不显示，**不显示 0**」→ 整行不画 |
/// | 划线原价与 `-xx%` 角标 | 列表接口无原价 | 「无原价则两者都不显示」→ 都不画 |
///
/// ⚠️ 第四条尤其**不许只画角标**：设计稿明写促销价与划线原价成对出现，
/// 只留一个 `-20%` 是无据的促销刺激。
///
/// ## 另外两处与设计稿的偏差（数据层，非视觉）
///
/// - **补货行没有商品图与价格**：`RepurchaseCard` 只有商品名与 daysLeft，没有 image/price。
///   这里用占位图 + 不画价格行。补齐要动后端 `RepurchaseCardView`。
/// - **档案精选没有可用图片 URL**：`RecommendationItem.mainImageKey` 是裸 key，
///   全仓没有 key→URL 的拼装（v1 版式压根没画图，所以一直没暴露）。此处一律走占位斜纹。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_repository.dart';
import '../data/shop_repurchase_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_repurchase.dart';
import 'shop_ui_variant.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

class TokoPageV2 extends ConsumerStatefulWidget {
  const TokoPageV2({super.key, this.initialCategory});

  final String? initialCategory;

  @override
  ConsumerState<TokoPageV2> createState() => _TokoPageV2State();
}

class _TokoPageV2State extends ConsumerState<TokoPageV2> {
  ShopCategory? _selected;

  @override
  void initState() {
    super.initState();
    _selected = ShopCategory.fromApi(widget.initialCategory);
    Analytics.capture('toko_tab_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final products = ref.watch(shopProductsProvider(_selected));
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(
        title: l10n.tokoTitle,
        large: true,
        actions: [
          const ShopUiVariantToggle(color: ShopColors.surface),
          _pawcoinCapsule(l10n, loggedIn),
          const SizedBox(width: 8),
          const _CartCapsule(),
          const SizedBox(width: kShopScreenEdge),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(shopProductsProvider(_selected));
          ref.invalidate(repurchaseCardsProvider);
          ref.invalidate(recommendationsProvider);
        },
        child: CustomScrollView(
          slivers: [
            // 🔴 顺序固定，不可调换（设计稿「结构自上而下」）。
            //    促销条位于此处 —— 无免运活动数据源，整条不渲染。
            const SliverToBoxAdapter(child: _RestockRow()),
            // 区域②：档案精选。
            // ⚠️ 推荐接口目前不下发「命中的记录 + 日期」，故 [ProfileRecoZoneV2]
            //    恒走降级态（MODE DASAR）—— 见那个文件的说明。这里保留本页自有的
            //    横滑实现是为了首页的转化形态（4 个横滑位），两者数据源同一套。
            const SliverToBoxAdapter(child: _ProfilePicksRail()),
            SliverToBoxAdapter(child: _categoryChips(l10n)),
            products.when(
              loading: () => const SliverToBoxAdapter(child: _CenteredBox(child: CircularProgressIndicator())),
              error: (_, _) => SliverToBoxAdapter(child: _CenteredBox(child: Text(l10n.tokoLoadFailed, style: ShopText.body))),
              data: (items) => items.isEmpty
                  ? SliverToBoxAdapter(child: _CenteredBox(child: Text(l10n.tokoEmpty, style: ShopText.body)))
                  : _grid(items),
            ),
            const SliverToBoxAdapter(child: SizedBox(height: kShopGutter)),
          ],
        ),
      ),
    );
  }

  /// 顶栏右侧：已登录显示 PawCoin 余额胶囊，游客显示 `Masuk` 胶囊。
  ///
  /// 🔴 **游客不显示余额 0**（设计稿明写）。余额 0 会让人以为账户里本该有钱。
  Widget _pawcoinCapsule(AppLocalizations l10n, bool loggedIn) {
    if (!loggedIn) {
      return _Capsule(
        key: const ValueKey('tokoLoginCapsule'),
        onTap: () => context.push('/login'),
        child: Text(l10n.loginTitle,
            style: ShopText.badge.copyWith(fontSize: 11, color: ShopColors.surface)),
      );
    }
    // ⚠️ 复用余额页的 provider —— 它同时会拉一页流水，对顶栏而言偏重。
    //    后端有独立的余额端点后应换过去；此处不自行拼一个接口。
    final balance = ref.watch(pawCoinProvider).maybeWhen(
        data: (s) => s.balance, orElse: () => null);
    return _Capsule(
      key: const ValueKey('tokoPawcoinCapsule'),
      onTap: () => context.push('/me/pawcoin'),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 13,
            height: 13,
            decoration: const BoxDecoration(color: ShopColors.purple, shape: BoxShape.circle),
          ),
          const SizedBox(width: 5),
          Text(
            // 余额未加载完时先只画胶囊壳，不画 0（同上：0 是错的信息，不是缺失的信息）。
            balance == null ? '—' : _compactIdr(balance),
            style: ShopText.badge.copyWith(fontSize: 11, color: ShopColors.surface),
          ),
        ],
      ),
    );
  }

  /// 分类 chips。再点一次取消选中回到全部精选（沿用 v1 行为）。
  ///
  /// 🔴 **横滑，不换行** —— 2026-08-19 产品决策，**刻意偏离设计稿**。
  /// 设计文档 `01_screens_browse_order.md` 屏 1 第 6 条原文写的是「白底行，6px 间距，**可换行**」，
  /// 但真机 360dp 窄屏上四个品类正好折成两行，把下方网格整体推下去一行的高度，
  /// 且品类数一旦增加（运营加类目）行数会继续涨，首屏可见商品越来越少。
  /// 横滑把这块的高度**钉死为一行**，与品类数解耦。
  ///
  /// ⚠️ 左右内边距给在 [SingleChildScrollView.padding] 而不是 [ShopSection] 上：
  /// 给在外层会让 chips 在 16dp 处被裁掉、滑不到屏幕边缘，看着像「滑不动了」。
  Widget _categoryChips(AppLocalizations l10n) => ShopSection(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
          // 内容不足一屏时不要回弹，否则短列表也能被拖动，像是还有内容没显示。
          physics: const ClampingScrollPhysics(),
          child: Row(
            children: [
              for (final (i, c) in ShopCategory.values.indexed) ...[
                if (i > 0) const SizedBox(width: 6),
                ShopChip(
                  label: _categoryLabel(l10n, c),
                  selected: _selected == c,
                  onTap: () => setState(() => _selected = _selected == c ? null : c),
                ),
              ],
            ],
          ),
        ),
      );

  String _categoryLabel(AppLocalizations l10n, ShopCategory c) => switch (c) {
        ShopCategory.makanan => l10n.tokoCategoryMakanan,
        ShopCategory.obatVitamin => l10n.tokoCategoryObatVitamin,
        ShopCategory.camilan => l10n.tokoCategoryCamilan,
        ShopCategory.perawatan => l10n.tokoCategoryPerawatan,
      };

  /// 全部商品网格：2 列，`gap: 3px`，容器底色即灰缝。
  ///
  /// 🔴 间距用 [kShopGutter] 而不是「好看的」12/16 —— 网格的分隔靠灰缝，
  /// 这是整套密度设计的一部分（见 [ShopSection] 的说明）。
  ///
  /// 🔴 <b>用 `mainAxisExtent` 而不是 `childAspectRatio`</b>。卡片的纵向内容是
  /// **定高**的（图 104 + 名 31 + 价一行），而比例式高度 = 卡宽 × 比例，随屏宽变化 ——
  /// 两者必然在某个屏宽对不上。实测 411dp 宽的 Pixel 9 上 `.78` 差 4.4px，
  /// 每张卡都溢出。定高则与屏宽无关，任何设备都不会溢出。
  Widget _grid(List<ShopProductSummary> items) => SliverGrid(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: kShopGutter,
          crossAxisSpacing: kShopGutter,
          mainAxisExtent: _gridCardExtent(context),
        ),
        delegate: SliverChildBuilderDelegate(
          (context, i) => _GridCard(
            product: items[i],
            entrySource: _selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY',
          ),
          childCount: items.length,
        ),
      );
}

/// 顶栏胶囊（半透明白底，圆角 7）。
class _Capsule extends StatelessWidget {
  const _Capsule({super.key, required this.child, this.onTap});

  final Widget child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
          decoration: BoxDecoration(
            color: ShopColors.onInk12,
            borderRadius: BorderRadius.circular(ShopShape.radiusField),
          ),
          child: child,
        ),
      );
}

/// 顶栏购物车（30×30 圆角方块 + 玫红圆形角标）。
///
/// 🔴 角标是**商品件数**不是种类数（FR-96）—— 与 v1 的 `CartIconButton` 同一口径、
/// 同一个 provider，只是外观按设计稿重画。
class _CartCapsule extends ConsumerWidget {
  const _CartCapsule();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final count = ref.watch(cartItemCountProvider);
    return Semantics(
      button: true,
      label: l10n.cartOpen,
      child: InkWell(
        onTap: () => context.push('/shop/cart'),
        borderRadius: BorderRadius.circular(ShopShape.radiusButton),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Container(
              width: 30,
              height: 30,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: ShopColors.onInk12,
                borderRadius: BorderRadius.circular(ShopShape.radiusButton),
              ),
              child: const Icon(Icons.shopping_cart_outlined,
                  size: 16, color: ShopColors.surface),
            ),
            if (count > 0)
              Positioned(
                right: -5,
                top: -5,
                child: Container(
                  key: const ValueKey('cartBadgeV2'),
                  constraints: const BoxConstraints(minWidth: 16),
                  height: 16,
                  alignment: Alignment.center,
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  decoration: const BoxDecoration(
                      color: ShopColors.rose, shape: BoxShape.circle),
                  child: Text(count > 99 ? '99+' : '$count',
                      style: ShopText.badge.copyWith(color: ShopColors.surface)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 区域①：补货提醒行 / 游客与未建档的建档引导条。
///
/// 🔴 三态（FR-93 状态矩阵）：
/// - 有补货触发 → 补货行
/// - 游客 或 已登录未建档 → 建档引导条（设计稿屏 2 的「差异」第 2 条）
/// - 已建档但无触发 → **整区不渲染，不留空标题**
class _RestockRow extends ConsumerWidget {
  const _RestockRow();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final cards = ref.watch(repurchaseCardsProvider)
        .maybeWhen(data: (v) => v, orElse: () => const <RepurchaseCard>[]);
    if (cards.isNotEmpty) {
      return Column(children: [for (final c in cards) _card(context, ref, l10n, c)]);
    }
    final reco = ref.watch(recommendationsProvider)
        .maybeWhen<Recommendations?>(data: (v) => v, orElse: () => null);
    // 🔴 游客与「已登录未建档」在这一行是同一个出口（都要先有档案才谈得上补货提醒），
    //    但按钮去向不同：游客去登录，已登录去建档。
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    if (!loggedIn || (reco?.needsProfileCreation ?? false)) {
      return _profileSetupRow(context, l10n, loggedIn: loggedIn);
    }
    return const SizedBox.shrink();
  }

  Widget _card(BuildContext context, WidgetRef ref, AppLocalizations l10n, RepurchaseCard c) {
    Analytics.capture('toko_repurchase_card_shown', {'trigger_type': c.triggerType});
    return ShopSection(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Stack(
            children: [
              const ShopImage(url: null, size: 64, radius: ShopShape.radiusButton),
              if (c.petName != null)
                Positioned(
                  left: 0,
                  top: 0,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                    decoration: const BoxDecoration(
                      color: ShopColors.purple,
                      borderRadius: BorderRadius.only(
                        topLeft: Radius.circular(ShopShape.radiusButton),
                        bottomRight: Radius.circular(ShopShape.radiusChip),
                      ),
                    ),
                    child: Text(c.petName!.toUpperCase(),
                        style: ShopText.badge.copyWith(fontSize: 8, color: ShopColors.surface)),
                  ),
                ),
            ],
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  // 🔴 给估算依据而非断言 —— `±N 天` 的 ± 是刻意的（沿用 v1 的同一条纪律）。
                  c.isOverdue ? l10n.tokoRestockTagOverdue : l10n.tokoRestockTag(c.daysLeft),
                  style: ShopText.badge.copyWith(
                      fontSize: 9.5, color: ShopColors.rose, letterSpacing: .8),
                ),
                const SizedBox(height: 3),
                Text(c.productName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: ShopText.cardTitle.copyWith(fontSize: 12.5, fontWeight: FontWeight.w600)),
                // ⚠️ 价格行：RepurchaseCardView 不下发价格，故整行不画（不编造、不显示 0）。
              ],
            ),
          ),
          const SizedBox(width: 9),
          ShopButton(
            key: ValueKey('tokoRestockBuy_${c.triggerId}'),
            label: l10n.tokoBuyAgain,
            variant: ShopButtonVariant.rose,
            dense: true,
            onTap: () {
              Analytics.capture('toko_repurchase_card_tapped',
                  {'trigger_type': c.triggerType, 'product_id': c.productToken});
              // 🔴 设计稿：`Beli Lagi` 跳过详情页与购物车直达结算。
              //    ⚠️ 当前无「一键加购并结算」的端点，故仍落详情页 ——
              //    这是刻意的保守选择：直达结算需要后端先支持单 SKU 直购，
              //    前端自行 add-to-cart 再跳转会在失败时留下脏购物车。
              context.push('/shop/products/${c.productToken}?from=REPURCHASE');
            },
          ),
        ],
      ),
    );
  }

  Widget _profileSetupRow(BuildContext context, AppLocalizations l10n, {required bool loggedIn}) =>
      ShopSection(
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: ShopColors.purpleTagBg,
                borderRadius: BorderRadius.circular(ShopShape.radiusField),
              ),
              child: const Icon(Icons.auto_awesome, size: 17, color: ShopColors.purple),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.tokoGuestProfileTitle,
                      style: ShopText.cardTitle.copyWith(fontSize: 12.5)),
                  const SizedBox(height: 2),
                  Text(l10n.tokoGuestProfileBody, style: ShopText.meta),
                ],
              ),
            ),
            const SizedBox(width: 9),
            ShopButton(
              key: const ValueKey('tokoGuestProfileCta'),
              label: loggedIn ? l10n.tokoRecoCreateProfileCta : l10n.loginTitle,
              variant: ShopButtonVariant.purple,
              dense: true,
              onTap: () => context.push(loggedIn ? '/profile/create' : '/login'),
            ),
          ],
        ),
      );
}

/// 区域②：档案精选横滑。
///
/// 🔴 游客态换标题为「最多人买」并**不渲染任何依赖档案的标签**
/// （设计稿屏 2：避免出现无主体的推荐理由）。
class _ProfilePicksRail extends ConsumerWidget {
  const _ProfilePicksRail();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final reco = ref.watch(recommendationsProvider)
        .maybeWhen<Recommendations?>(data: (v) => v, orElse: () => null);
    if (reco == null || reco.items.isEmpty) {
      // 🔴 整区不渲染，不留空标题。游客态目前也落这里 —— 全站热销尚无接口，
      //    与其画一个空标题，不如按设计稿的空态规则整区不画。
      return const SizedBox.shrink();
    }
    final isGuest = reco.isGuest;
    return ShopSection(
      padding: const EdgeInsets.fromLTRB(0, 12, 0, 13),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    isGuest
                        ? l10n.tokoTopSellersLabel
                        : l10n.tokoRecoLabel(reco.petName ?? ''),
                    style: ShopText.sectionTitle.copyWith(fontSize: 14),
                  ),
                ),
                Text('${l10n.tokoSeeAll} ›',
                    style: ShopText.body.copyWith(
                        fontSize: 11, color: ShopColors.purple, fontWeight: FontWeight.w600)),
              ],
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            height: _railCardExtent(context),
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: kShopScreenEdge),
              itemCount: reco.items.length,
              separatorBuilder: (_, _) => const SizedBox(width: kShopRailGap),
              itemBuilder: (c, i) => _RailCard(item: reco.items[i], showReason: !isGuest),
            ),
          ),
        ],
      ),
    );
  }
}

class _RailCard extends StatefulWidget {
  const _RailCard({required this.item, required this.showReason});

  final RecommendationItem item;

  /// 🔴 游客态 false —— 推荐理由依赖档案，无主体时整条不渲染。
  final bool showReason;

  @override
  State<_RailCard> createState() => _RailCardState();
}

class _RailCardState extends State<_RailCard> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_shown', {'zone': 'profile_reco'});
  }

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    return SizedBox(
      width: 120,
      child: InkWell(
        onTap: () {
          Analytics.capture('toko_profile_reco_tapped',
              {'zone': 'profile_reco', 'product_id': it.productToken});
          context.push('/shop/products/${it.productToken}?from=PROFILE_RECO');
        },
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // mainImageKey 是裸 key，全仓无 key→URL 拼装 → 一律占位斜纹（见文件头说明）。
            const ShopImage(url: null, size: kRailImageSize, radius: ShopShape.radiusButton),
            const SizedBox(height: 6),
            Expanded(
              child: Align(
                alignment: Alignment.topLeft,
                child: Text(it.name,
                    maxLines: 2, overflow: TextOverflow.ellipsis, style: ShopText.productNameCard),
              ),
            ),
            Text(formatIdr(it.minPrice),
                style: ShopText.priceRail.copyWith(color: ShopColors.rose)),
            // ★ 评分 · 已售数：接口无此字段 → 整行不显示（不显示 0）。
            if (widget.showReason && it.reason.isNotEmpty) ...[
              const SizedBox(height: 4),
              ShopBadge.recoSource(it.reason),
            ],
          ],
        ),
      ),
    );
  }
}

/// 全部商品网格卡。
class _GridCard extends StatefulWidget {
  const _GridCard({required this.product, required this.entrySource});

  final ShopProductSummary product;
  final String entrySource;

  @override
  State<_GridCard> createState() => _GridCardState();
}

class _GridCardState extends State<_GridCard> {
  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_shown', {
      'product_token': widget.product.token,
      'zone': 'all_featured',
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final p = widget.product;
    return InkWell(
      onTap: () => context.push('/shop/products/${p.token}?from=${widget.entrySource}'),
      child: ColoredBox(
        color: ShopColors.surface,
        child: Padding(
          padding: const EdgeInsets.all(9),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 设计稿：网格图 **104px 高**（不是正方形）。宽度撑满，超出部分裁切。
              SizedBox(
                height: kGridImageHeight,
                width: double.infinity,
                child: ShopImage(
                    url: p.mainImageUrl,
                    size: kGridImageHeight,
                    fillWidth: true,
                    radius: ShopShape.radiusChip),
              ),
              const SizedBox(height: 6),
              // 🔴 名称用 [Expanded] 吸收余量、价格贴底 —— **这样价格行天然对齐**，
              //    而不是靠把名称锁成 31px 定高再祈祷高度算得准。
              //    手算文本高度试过两轮（4.4px → 2.0px 溢出），换成结构约束后
              //    溢出在原理上不可能发生：余量多则名称框变高，余量少则它变矮。
              Expanded(
                child: Align(
                  alignment: Alignment.topLeft,
                  child: Text(p.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: ShopText.productNameCard),
                ),
              ),
              Text(
                // 🔴 无 SKU 时显占位而非 `Rp 0` —— 那是错的价格，不是缺失的价格。
                p.minPrice == null ? l10n.tokoPriceUnavailable : formatIdr(p.minPrice!),
                style: ShopText.priceGrid.copyWith(
                    color: p.minPrice == null ? ShopColors.text4 : ShopColors.rose),
              ),
              // ★ 评分 · 已售数：接口无此字段 → 整行不显示。
            ],
          ),
        ),
      ),
    );
  }
}

class _CenteredBox extends StatelessWidget {
  const _CenteredBox({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => ShopSection(
        padding: const EdgeInsets.symmetric(vertical: 32, horizontal: kShopScreenEdge),
        child: Center(child: child),
      );
}

/// 顶栏胶囊里的余额缩写：`50000` → `50rb`（印尼语 ribu = 千）。
///
/// 🔴 只用于顶栏这一处的**空间受限**场景。金额正文一律用 [formatIdr] 全写 ——
/// 缩写会让「50rb」和「50」在扫读时混淆，价格位上绝不能用。
String _compactIdr(int amount) {
  if (amount >= 1000000) {
    final jt = amount / 1000000;
    return '${jt.toStringAsFixed(jt.truncateToDouble() == jt ? 0 : 1)}jt';
  }
  if (amount >= 1000) return '${amount ~/ 1000}rb';
  return '$amount';
}

/// 设计稿：网格商品图高 104。
const double kGridImageHeight = 104;

/// 设计稿：横滑商品图 120×120。
const double kRailImageSize = 120;

/// 网格卡的固定高度。
///
/// 🔴 <b>文字部分乘 textScaler</b>：app 允许系统字号放大到 1.3 倍（NFR-13），
/// 不跟着放大会在大字号下把商品名裁掉一行。
///
/// ⚠️ 这里的数值**不需要精确**：卡片内部用 [Expanded] 吸收余量，多了名称框变高、
/// 少了变矮，都不会溢出。它只决定「一排卡片有多高」这个观感，不承担正确性。
double _gridCardExtent(BuildContext context) {
  final ts = MediaQuery.textScalerOf(context);
  return 9 + kGridImageHeight + 6 + ts.scale(kCardProductNameHeight) + 4 + ts.scale(24) + 9;
}

/// 横滑卡的固定高度（含来源标签那一行；游客态无标签时留白，卡片仍等高）。
double _railCardExtent(BuildContext context) {
  final ts = MediaQuery.textScalerOf(context);
  return kRailImageSize + 6 + ts.scale(kCardProductNameHeight) + 4 + ts.scale(22) + 4 + ts.scale(18);
}
