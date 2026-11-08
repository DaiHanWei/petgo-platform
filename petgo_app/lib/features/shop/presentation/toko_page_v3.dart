/// Toko 首页 v3（v1.4.2 shop Story 8.1，UI A1–A9 定稿方向，P-4）。
///
/// 自上而下：**浅紫柔光顶部**（浅紫为主、左上一点蜜桃、右侧一点薄荷，向下渐隐到页面底色，无分界线）
/// → 标题「Toko」墨色 + 右上 PawCoin 余额胶囊（白底紫字；游客「Masuk」）+ 购物车
/// → 搜索框（白底 + 浅紫到蜜桃渐变描边，点击进 `/shop/search`）
/// → banner 轮播（A1 / A2 / A5）→「Kupon buat kamu」（A3）→ 为宠物推荐（A1 / A9）→ 全部商品（A1 / A8）。
///
/// ## 与 v2 的差异（按设计删除）
/// - banner 不再作顶栏背景；吸顶筛选行与品类 chip 下线（D-17：`?category=` 由路由忽略，落首页）；
/// - 补货行 / 补货卡 / 去建档引导条 / 满额免运提示条删除（FR-136：复购下线；Diary 复购卡归 9.4）；
/// - 商品流只有「全部」：族键恒 `category: null, keyword: null`，归因恒 `TOKO_ALL_FEATURED`。
///
/// ## 进入 Toko（AC4）
/// - 已登录：每次进入（initState 与切回该 Tab 时）调 `POST /me/shop/entry`（60 秒进程内去重），
///   返回已发新人券 → 上报 `toko_coupon_claimed{source: newbie}` 并刷新「给你的券」。服务端判新人，App 不判。
/// - 游客：有新人券活动且本设备未对该活动弹过 → A4 弹窗，弹前记 `AppPrefs`（关闭后不再弹）。
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/storage/prefs.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../coupon/data/coupon_repository.dart';
import '../coupon/domain/shop_coupon.dart';
import '../coupon/presentation/coupon_claim.dart';
import '../coupon/presentation/coupons_for_you_row.dart';
import '../coupon/presentation/newbie_popup.dart';
import '../data/cart_repository.dart';
import '../data/shop_repository.dart';
import '../data/shop_repurchase_repository.dart';
import '../domain/shop_banner.dart';
import 'widgets/shop_banner_carousel.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_pressable.dart';
import 'widgets/shop_product_card.dart';
import 'widgets/shop_reco_grid.dart';

/// 首页商品流族键：**恒为全部**（品类入口已下线，D-17）。
const ShopProductsQuery kTokoHomeQuery = (category: null, keyword: null);

class TokoPageV3 extends ConsumerStatefulWidget {
  const TokoPageV3({super.key});

  @override
  ConsumerState<TokoPageV3> createState() => _TokoPageV3State();
}

class _TokoPageV3State extends ConsumerState<TokoPageV3> {
  bool _tickerOn = true;
  bool _newbieChecking = false;

  /// banner 领券在途（按 couponToken，8.1 复审 #2）：await 期间再点同一张直接返回，不重复发请求。
  final Set<String> _bannerClaiming = {};

  /// 页面级曝光去重（8.1 复审 #5）：`toko_product_shown` 按 token + zone 一次进入只报一次。
  final ShopImpressionTracker _impressions = ShopImpressionTracker();

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_tab_viewed');
    WidgetsBinding.instance.addPostFrameCallback((_) => _onEnter());
  }

  /// 「切回该 Tab」= 本页所在分支回到前台：indexedStack 与 Overlay 会把看不见的页面放进
  /// `TickerMode(enabled: false)`，回到前台时翻回 true。
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final on = TickerMode.valuesOf(context).enabled;
    if (on == _tickerOn) return;
    _tickerOn = on;
    // 离开页面再回来重新计曝光（与 banner 曝光同一规则）。
    if (!on) _impressions.clear();
    if (on) WidgetsBinding.instance.addPostFrameCallback((_) => _onEnter());
  }

  void _onEnter() {
    if (!mounted) return;
    if (ref.read(authControllerProvider).isLoggedIn) {
      _enterShop();
    } else {
      _maybeShowNewbiePopup();
    }
  }

  /// AC4.1：`POST /me/shop/entry`，失败静默。
  Future<void> _enterShop() async {
    if (!claimShopEntrySlot()) return;
    final repo = ref.read(couponRepositoryProvider);
    try {
      final issued = await repo.enterShop();
      if (!issued || !mounted) return;
      // 后端 entry 不回券 token（`ShopEntryResponse{newbieCouponIssued}`）：
      // 从「给你的券」里取新人券的模板 token 作 coupon_id（m3：由 App 在 entry 返回已发放时上报）。
      String? token;
      try {
        token = (await repo.forYou()).where((c) => c.newbie).firstOrNull?.couponToken;
      } catch (_) {
        token = null;
      }
      Analytics.capture('toko_coupon_claimed', {'coupon_id': ?token, 'source': 'newbie'});
      if (mounted) ref.invalidate(couponsForYouProvider);
    } catch (_) {
      // 静默：进入 Toko 不因发券失败打扰用户。
    }
  }

  /// AC4.2：游客新人券弹窗（同设备同活动一次）。204 / 失败 / 读不到本地标记 → 不弹。
  Future<void> _maybeShowNewbiePopup() async {
    if (_newbieChecking) return;
    _newbieChecking = true;
    try {
      NewbieCampaign? campaign;
      try {
        campaign = await ref.read(couponRepositoryProvider).newbieCampaign();
      } catch (_) {
        campaign = null;
      }
      if (campaign == null || !mounted || ref.read(authControllerProvider).isLoggedIn) return;
      AppPrefs? prefs;
      try {
        prefs = await AppPrefs.create();
      } catch (_) {
        prefs = null;
      }
      // 读不到本地标记时不弹：记不下「弹过」就会每次进入都弹，比少弹一次更伤。
      if (prefs == null || prefs.shownNewbieCampaigns.contains(campaign.campaignToken)) return;
      // 🔴 8.1 复审 #3：先确认页面仍在前台再记「弹过」，记完立即弹、中间不 await ——
      //    否则「记了但没弹」（期间切走了）会让该活动在本设备永远不再弹。
      if (!mounted || !_tickerOn || ref.read(authControllerProvider).isLoggedIn) return;
      unawaited(prefs.markNewbieCampaignShown(campaign.campaignToken));
      await showNewbieCouponPopup(context, campaign);
    } finally {
      _newbieChecking = false;
    }
  }

  /// banner 点击（AC2.4 / AC2.5）：埋点 + 按 targetType 跳转。连点去重在轮播组件里。
  Future<void> _onBannerTap(ShopBanner b, int position) async {
    final value = b.targetValue ?? '';
    Analytics.capture('toko_banner_tapped', {
      'banner_id': ?b.bannerId,
      'target_type': b.target.analytics,
      'position': position,
    });
    switch (b.target) {
      case ShopBannerTarget.product:
        context.push('/shop/products/${Uri.encodeComponent(value)}?from=BANNER');
      case ShopBannerTarget.coupon:
        if (!_bannerClaiming.add(value)) return; // 同一张在途：不重复领
        try {
          await claimCouponOrLogin(context, ref, couponToken: value, source: CouponClaimSource.banner);
        } finally {
          _bannerClaiming.remove(value);
        }
      case ShopBannerTarget.external:
        final uri = Uri.tryParse(value);
        if (uri != null && uri.scheme == 'https') {
          try {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
          } catch (_) {
            // 打不开（无浏览器等）静默：banner 是锦上添花的入口，不弹错误打断逛店。
          }
        }
      case ShopBannerTarget.tokoHome:
      case ShopBannerTarget.none:
        break; // 留在首页
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // AC4.4：注册 / 登录后首次进 Toko 即走发券（服务端判定）。
    ref.listen<bool>(authControllerProvider.select((a) => a.isLoggedIn), (prev, next) {
      if (next && prev != true) _enterShop();
    });
    final products = ref.watch(shopProductsProvider(kTokoHomeQuery));
    final banners = ref.watch(shopBannersProvider).asData?.value ?? const <ShopBanner>[];

    return ShopImpressionScope(
      tracker: _impressions,
      child: Scaffold(
      backgroundColor: AppColors.base,
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(shopBannersProvider);
          ref.invalidate(couponsForYouProvider);
          ref.invalidate(recommendationsProvider);
          ref.invalidate(shopProductsProvider(kTokoHomeQuery));
        },
        // 🔴 触底预加载（Story 4-5）：距底部 600px 时取下一页。先认领通知再看距离：
        //    `depth != 0`（嵌套滚动：轮播、券横滑）与横向滚动一律不算（2026-09-18 复审 #7）。
        child: NotificationListener<ScrollNotification>(
          onNotification: (n) {
            if (n.depth != 0 || n.metrics.axis != Axis.vertical) return false;
            if (n.metrics.pixels >= n.metrics.maxScrollExtent - 600) {
              ref.read(shopProductsProvider(kTokoHomeQuery).notifier).loadMore();
            }
            return false;
          },
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              SliverToBoxAdapter(child: _Header(l10n: l10n)),
              // A2：空数组 → 整块不渲染，「Kupon buat kamu」直接接在搜索框下。
              if (banners.isNotEmpty)
                SliverToBoxAdapter(
                  child: ShopBannerCarousel(banners: banners, onTap: _onBannerTap),
                ),
              const SliverToBoxAdapter(child: CouponsForYouRow()),
              const SliverToBoxAdapter(child: _RecoSection()),
              SliverToBoxAdapter(
                child: _AllProductsHeader(
                    l10n: l10n, total: products.asData?.value.totalCount),
              ),
              ...products.when(
                loading: () => [
                  const SliverToBoxAdapter(
                    child: Padding(
                      padding: EdgeInsets.symmetric(vertical: AppSpacing.xxl),
                      child: Center(child: CircularProgressIndicator()),
                    ),
                  ),
                ],
                error: (_, _) => [
                  SliverToBoxAdapter(
                    child: _LoadFailedCard(
                      l10n: l10n,
                      onRetry: () => ref.invalidate(shopProductsProvider(kTokoHomeQuery)),
                    ),
                  ),
                ],
                data: (feed) => feed.items.isEmpty
                    ? [
                        SliverToBoxAdapter(
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                                vertical: AppSpacing.xxl, horizontal: AppSpacing.screenEdge),
                            child: Text(l10n.tokoEmpty,
                                textAlign: TextAlign.center, style: AppTypography.body),
                          ),
                        ),
                      ]
                    : [
                        ShopProductSliverGrid(
                          items: feed.items,
                          zone: 'all_featured',
                          entrySource: 'TOKO_ALL_FEATURED',
                        ),
                        SliverToBoxAdapter(child: _ListFooter(feed: feed, l10n: l10n)),
                      ],
              ),
              const SliverToBoxAdapter(child: SizedBox(height: AppSpacing.lg)),
            ],
          ),
        ),
      ),
      ),
    );
  }
}

/// 浅紫柔光顶部 + 标题行 + 搜索框。
class _Header extends ConsumerWidget {
  const _Header({required this.l10n});

  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    return DecoratedBox(
      key: const ValueKey('tokoSoftTop'),
      // 浅紫为主、向下渐隐到页面底色，无分界线。
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [AppColors.violet100, AppColors.base],
        ),
      ),
      child: Stack(
        children: [
          // 左上一点蜜桃、右侧一点薄荷（柔光，纯装饰）。
          const Positioned(left: -60, top: -60, child: _Glow(color: AppColors.coralTint, size: 180)),
          const Positioned(right: -50, top: 10, child: _Glow(color: AppColors.vetSurface, size: 150)),
          SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                  AppSpacing.screenEdge, AppSpacing.sm, AppSpacing.screenEdge, AppSpacing.xs),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(l10n.tokoTitle,
                            style: AppTypography.headline.copyWith(color: AppColors.ink)),
                      ),
                      _pawcoinCapsule(context, ref, loggedIn),
                      const SizedBox(width: AppSpacing.sm),
                      const _CartButton(),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.md),
                  _SearchBox(hint: l10n.tokoSearchHint, label: l10n.tokoSearchOpen),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 已登录：PawCoin 余额胶囊（点击进 `/me/pawcoin`）；游客：「Masuk」进 `/login`。
  /// 🔴 游客不显示余额 0（0 是错的信息，不是缺失的信息）。
  Widget _pawcoinCapsule(BuildContext context, WidgetRef ref, bool loggedIn) {
    if (!loggedIn) {
      return _Capsule(
        key: const ValueKey('tokoLoginCapsule'),
        onTap: () => context.push('/login'),
        child: Text(l10n.loginTitle, style: _capsuleText),
      );
    }
    // ⚠️ 复用余额页的 provider（同 v2）。
    final balance = ref.watch(pawCoinProvider).maybeWhen(data: (s) => s.balance, orElse: () => null);
    return _Capsule(
      key: const ValueKey('tokoPawcoinCapsule'),
      onTap: () => context.push('/me/pawcoin'),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const ShopCoinMark(size: 17),
          const SizedBox(width: 5),
          Text(balance == null ? '—' : _compactIdr(balance), style: _capsuleText),
        ],
      ),
    );
  }

  static final TextStyle _capsuleText =
      AppTypography.caption.copyWith(color: AppColors.mint, fontWeight: FontWeight.w700);
}

class _Glow extends StatelessWidget {
  const _Glow({required this.color, required this.size});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(colors: [color, color.withValues(alpha: 0)]),
          ),
        ),
      );
}

/// 顶栏胶囊：白底紫字，视觉高 32，命中区 44。
class _Capsule extends StatelessWidget {
  const _Capsule({super.key, required this.child, this.onTap});

  final Widget child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => ShopPressable(
        onTap: onTap,
        minSize: kShopMinTapTarget,
        child: Container(
          height: 32,
          alignment: Alignment.center,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: AppColors.lineViolet),
          ),
          child: child,
        ),
      );
}

/// 购物车按钮 + 红色件数角标（🔴 件数非种类数，FR-96；与 v2 同一个 provider）。
class _CartButton extends ConsumerWidget {
  const _CartButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final count = ref.watch(cartItemCountProvider);
    return Semantics(
      button: true,
      label: l10n.cartOpen,
      child: ShopPressable(
        onTap: () => context.push('/shop/cart'),
        minSize: kShopMinTapTarget,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Container(
              width: 32,
              height: 32,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.card,
                shape: BoxShape.circle,
                border: Border.all(color: AppColors.lineViolet),
              ),
              child: const Icon(Icons.shopping_cart_outlined, size: 19, color: AppColors.mint),
            ),
            if (count > 0)
              Positioned(
                right: -5,
                top: -5,
                child: Container(
                  padding: const EdgeInsets.all(1.5),
                  decoration: const BoxDecoration(
                    color: AppColors.card,
                    borderRadius: BorderRadius.all(Radius.circular(999)),
                  ),
                  child: ShopCountBadge(key: const ValueKey('cartBadgeV2'), count: count),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 搜索框：白底 + 浅紫到蜜桃渐变描边，点击进 `/shop/search`（游客同样可用，浏览零门槛）。
class _SearchBox extends StatelessWidget {
  const _SearchBox({required this.hint, required this.label});

  final String hint;
  final String label;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        label: label,
        child: ShopPressable(
          key: const ValueKey('tokoSearchEntryV2'),
          onTap: () => context.push('/shop/search'),
          child: Container(
            height: 44,
            padding: const EdgeInsets.all(1.5),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(22),
              gradient: const LinearGradient(colors: [AppColors.mint500, AppColors.coralTint]),
            ),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(20.5),
              ),
              child: Row(
                children: [
                  const Icon(Icons.search, size: 20, color: AppColors.mint),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(
                    child: Text(hint,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTypography.body.copyWith(color: AppColors.muted)),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

/// 为宠物推荐块（首页：无档案整块不显示）。
class _RecoSection extends StatelessWidget {
  const _RecoSection();

  @override
  Widget build(BuildContext context) => const Padding(
        padding: EdgeInsets.only(top: AppSpacing.lg),
        child: ShopRecoGrid(source: ShopRecoSource.profileOnly),
      );
}

/// 段头「Semua produk」+ 右侧「{n} produk」（仅服务端给了总数时）。
class _AllProductsHeader extends StatelessWidget {
  const _AllProductsHeader({required this.l10n, required this.total});

  final AppLocalizations l10n;
  final int? total;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.screenEdge, AppSpacing.lg, AppSpacing.screenEdge, AppSpacing.md),
        child: Row(
          children: [
            Expanded(
              child: Text(l10n.tokoAllProducts,
                  style: AppTypography.title.copyWith(fontSize: 16, color: AppColors.ink)),
            ),
            if (total != null)
              Text(l10n.tokoProductCount('$total'),
                  key: const ValueKey('tokoProductCount'), style: AppTypography.caption),
          ],
        ),
      );
}

/// A8：商品流首屏失败卡（banner 照常显示；翻页失败维持静默）。
class _LoadFailedCard extends StatelessWidget {
  const _LoadFailedCard({required this.l10n, required this.onRetry});

  final AppLocalizations l10n;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Container(
        key: const ValueKey('tokoProductsFailed'),
        margin: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl, horizontal: AppSpacing.lg),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(kShopCardRadius),
          border: Border.all(color: AppColors.line),
        ),
        child: Column(
          children: [
            const Icon(Icons.cloud_off, size: 32, color: AppColors.muted),
            const SizedBox(height: AppSpacing.sm),
            Text(l10n.tokoLoadFailed, textAlign: TextAlign.center, style: AppTypography.body),
            const SizedBox(height: AppSpacing.sm),
            TextButton(
              key: const ValueKey('tokoProductsRetry'),
              onPressed: onRetry,
              child: Text(l10n.commonRetry),
            ),
          ],
        ),
      );
}

/// 商品流尾部三态（Story 4-5 AC5）：加载中 / 到底了 / 都不是。
class _ListFooter extends StatelessWidget {
  const _ListFooter({required this.feed, required this.l10n});

  final ShopProductFeed feed;
  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) {
    if (feed.loadingMore) {
      return const Padding(
        key: ValueKey('tokoLoadingMore'),
        padding: EdgeInsets.symmetric(vertical: 18),
        child: Center(
            child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
      );
    }
    if (!feed.hasMore) {
      return Padding(
        key: const ValueKey('tokoNoMore'),
        padding: const EdgeInsets.symmetric(vertical: 18),
        child: Center(
          child: Text(l10n.tokoNoMore, style: AppTypography.caption.copyWith(color: AppColors.muted)),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}

/// 顶栏胶囊里的余额缩写：`50000` → `50rb`（印尼语 ribu = 千）。
///
/// 🔴 只用于顶栏这一处的**空间受限**场景。金额正文一律用 `formatIdr` 全写。
String _compactIdr(int amount) {
  if (amount >= 1000000) {
    final jt = amount / 1000000;
    return '${jt.toStringAsFixed(jt.truncateToDouble() == jt ? 0 : 1)}jt';
  }
  if (amount >= 1000) return '${amount ~/ 1000}rb';
  return '$amount';
}
