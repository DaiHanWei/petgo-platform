/// Toko 首页 —— **设计稿版式**（V1.4.0 · `design_handoff_ecommerce/01_screens_browse_order.md` 屏 1/2）。
///
/// ⚠️ 2026-08-28：v1 版式（TokoPage）已整体删除，双 UI 并存机制一并移除。
/// 文件名保留 `_v2` 后缀是为了不制造一次纯改名的大 diff —— 现在它就是唯一的 Toko 页。
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
import '../../../shared/widgets/app_image.dart';
import '../../auth/domain/auth_state.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_repository.dart';
import '../data/shop_repurchase_repository.dart';
import '../domain/shop_banner.dart';
import '../domain/shop_product.dart';
import '../domain/shop_repurchase.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_pressable.dart';
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
    // banner 拉取失败一律当作"没有"（repository 里已吞掉异常）：
    // 它是锦上添花的展示位，不该让整页进错误态。加载中同样按"没有"处理 ——
    // 先给白色顶栏、图到了再换，比先留一块空白再弹出 banner 稳。
    final banner = ref.watch(shopBannerProvider).asData?.value;

    return Scaffold(
      backgroundColor: ShopColors.bg,
      // 🔴 有 banner 时让 body 穿到 AppBar 之下 —— 这是"图顶到屏幕最上沿、
      //    上方不留纯色条"的前提。没有它，AppBar 会先占掉状态栏+48px 的高度。
      extendBodyBehindAppBar: banner != null,
      appBar: ShopAppBar(
        title: l10n.tokoTitle,
        large: true,
        // 有 banner → 透明浮在图上；没有 → 白色顶栏（产品要求与其他板块区分）。
        tone: banner != null ? ShopAppBarTone.transparent : ShopAppBarTone.light,
        actions: [
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
          // 🔴 内容撑不满一屏时（错误态、空态）Android 的 ClampingScrollPhysics 拉不动，
          //    RefreshIndicator 于是形同虚设。Always… 让下拉刷新在任何长度下都可用。
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            // 顶部 banner（2026-08-27）。🔴 必须是第一个 sliver，且自己吃掉状态栏高度 ——
            // 见 [_BannerHeader]。没有 banner 时整块不渲染（不留占位）。
            if (banner != null) SliverToBoxAdapter(child: _BannerHeader(banner: banner)),
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
              error: (_, _) => SliverToBoxAdapter(
                child: ShopRetryState(
                  message: l10n.tokoLoadFailed,
                  retryLabel: l10n.commonRetry,
                  onRetry: () => ref.invalidate(shopProductsProvider(_selected)),
                ),
              ),
              data: (items) => items.isEmpty
                  ? SliverToBoxAdapter(child: _CenteredBox(child: Text(l10n.tokoEmpty, style: ShopText.body)))
                  : _masonry(items),
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
          const ShopCoinMark(size: 14),
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

  /// 全部商品：**两列瀑布流**（2026-08-27 产品改版）。
  ///
  /// 🔴 为什么从等高网格改成瀑布流：商品图比例并不统一（1:1 的素材规格没被严格遵守）。
  /// 定高框下只有两种坏结果 —— `cover` 裁掉竖图的上下两端，`contain` 留出大片左右空白，
  /// 两者都让「用户看到的」与「运营上架的」不是同一张图。宽度撑满 + 高度按比例，
  /// 才能既不裁切也不留白。**代价是卡片参差、价格行不再横向对齐**，产品已确认接受。
  ///
  /// **按高度贪心分列**（2026-08-27 后端补上尺寸后启用）：每张卡放进当前较矮的那列，
  /// 两列底部因此大致齐平。此前拿不到图片比例，只能奇偶交替、底部参差。
  ///
  /// ⚠️ 高度用**相对值**估算（以列宽为 1），不换算像素 —— 分列只需要比较谁更矮，
  /// 绝对值没有意义，而列宽要等 layout 才知道。
  /// 🔴 尺寸未知的商品按 1:1 计入：估错的代价只是两列略不齐，不影响正确性。
  ///
  /// 🔴 用 [SliverToBoxAdapter] + 两个 [Column]，**失去了 Sliver 的视口懒构建** ——
  /// 全部商品一次性构建、图片同时开始加载。当前 92 个商品尚可承受，
  /// 商品量再上去必须改为分页加载，否则首屏会同时发出上百个图片请求。
  Widget _masonry(List<ShopProductSummary> items) {
    final left = <Widget>[];
    final right = <Widget>[];
    // 以列宽为 1 的相对高度累计值。
    var leftH = 0.0;
    var rightH = 0.0;
    for (var i = 0; i < items.length; i++) {
      final p = items[i];
      final card = _GridCard(
        product: p,
        entrySource: _selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY',
      );
      // 图片相对高度 = 1 / (w/h)；未知比例按 1:1。再加上名称+价格那块的固定占位。
      final cardH = 1 / (p.mainImageAspect ?? 1.0) + kMasonryTextBlockRatio;
      if (leftH <= rightH) {
        left.add(card);
        leftH += cardH;
      } else {
        right.add(card);
        rightH += cardH;
      }
    }
    return SliverToBoxAdapter(
      child: Row(
        // 🔴 必须是 start：默认的 stretch 会把两列拉成等高，瀑布流当场退化回网格。
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: _masonryColumn(left)),
          const SizedBox(width: kShopGutter),
          Expanded(child: _masonryColumn(right)),
        ],
      ),
    );
  }

  Widget _masonryColumn(List<Widget> cards) => Column(
        children: [
          for (var i = 0; i < cards.length; i++) ...[
            if (i > 0) const SizedBox(height: kShopGutter),
            cards[i],
          ],
        ],
      );
}

/// Toko 顶部 banner（2026-08-27）。
///
/// ## 为什么自己吃掉状态栏高度
/// 页面用 `extendBodyBehindAppBar: true` 把内容穿到了 AppBar 之下，于是本组件的
/// 顶边就是**屏幕最上沿**。图必须从这里开始画，上方才不会留出一条纯色 ——
/// 这正是产品要的"banner 到顶显示"。代价是图的**最上面一条会被状态栏文字压住**，
/// 所以高度要额外加上 `padding.top`，把被压的那部分让出来。
///
/// ## 🔴 渐变不是装饰，是可读性的唯一保障
/// banner 图的内容完全由运营决定，浅色图上白色的标题与按钮会直接看不见。
/// 顶部压一层从半透明黑到全透明的渐变，让文字始终有足够对比度。
/// ⚠️ 渐变高度覆盖到 AppBar 底部即可，再往下会把图的主视觉也压灰。
class _BannerHeader extends StatelessWidget {
  const _BannerHeader({required this.banner});

  final ShopBanner banner;

  /// 渐变覆盖到状态栏 + 顶栏之下再多一点，保证顶栏文字整行都在保护范围内。
  static const double _gradientExtra = 12;

  @override
  Widget build(BuildContext context) {
    final topInset = MediaQuery.paddingOf(context).top;
    final width = MediaQuery.sizeOf(context).width;
    // 图按自身比例铺满屏宽；再加上状态栏高度，让图真正顶到屏幕最上沿。
    final imageHeight = width / banner.aspect;
    final gradientHeight = topInset + kShopAppBarHeight + _gradientExtra;

    return SizedBox(
      height: topInset + imageHeight,
      width: double.infinity,
      child: Stack(
        fit: StackFit.expand,
        children: [
          Image.network(
            banner.imageUrl,
            fit: BoxFit.cover,
            // 🔴 高度已由外层 SizedBox 定死（比例来自服务端下发的宽高），
            //    所以图到达前后布局不变，不会把下方内容顶开。
            errorBuilder: (_, _, _) => const ColoredBox(color: ShopColors.ink),
          ),
          // 顶部渐变遮罩：保证标题与右上角按钮在任何图上都可读。
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            height: gradientHeight,
            child: const IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [ShopColors.bannerScrimTop, ShopColors.bannerScrimBottom],
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 顶栏胶囊（半透明白底，圆角 7）。
class _Capsule extends StatelessWidget {
  const _Capsule({super.key, required this.child, this.onTap});

  final Widget child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => ShopPressable(
        onTap: onTap,
        // 胶囊视觉高约 26，命中区撑到 44（顶栏 toolbarHeight 是 48，放得下）。
        minSize: kShopMinTapTarget,
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
    // 🔴 角标改用 [ShopCountBadge]（2026-08-27）：原先这里和商品详情页各写了一份，
    //    两份都用 `BoxShape.circle` —— 那个形状只按最短边画圆，`99+` 会溢到圆外面。
    //    命中区同时由 30 撑到 44。
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
                child: ShopCountBadge(
                    key: const ValueKey('cartBadgeV2'), count: count),
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
      return Column(children: [for (final c in cards) _RestockCard(card: c)]);
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

  Widget _profileSetupRow(BuildContext context, AppLocalizations l10n,
          {required bool loggedIn}) =>
      _profileSetupRowImpl(context, l10n, loggedIn: loggedIn);
}

/// 补货提醒卡。
///
/// 🔴 拆成 [StatefulWidget] 只为一件事：**曝光埋点不能写在 `build()` 里**（2026-08-27）。
/// 原实现每次重建都会再报一次 `toko_repurchase_card_shown` —— 切品类、拉刷新、
/// 购物车角标变化都会重建这一行，于是曝光数被放大了不知道多少倍。
class _RestockCard extends StatefulWidget {
  const _RestockCard({required this.card});

  final RepurchaseCard card;

  @override
  State<_RestockCard> createState() => _RestockCardState();
}

class _RestockCardState extends State<_RestockCard> {
  @override
  void initState() {
    super.initState();
    Analytics.capture(
        'toko_repurchase_card_shown', {'trigger_type': widget.card.triggerType});
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final c = widget.card;
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
                    // 🔴 9px 是 ShopText 写死的下限（「最小字号 9px 仅用于徽标与角标」），
                    //    此前这里写的 8 违反了本项目自己的硬规则。
                    child: Text(c.petName!.toUpperCase(),
                        style: ShopText.badge.copyWith(color: ShopColors.surface)),
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
                      fontSize: 9.5, color: ShopColors.accent, letterSpacing: .8),
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
            variant: ShopButtonVariant.pay,
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
}

Widget _profileSetupRowImpl(BuildContext context, AppLocalizations l10n,
        {required bool loggedIn}) =>
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
                // 🔴 这里原本画着一个紫色 w600 带 `›` 的「查看全部」，长得完全像可点，
                //    但它只是 Row 里一个裸 [Text]，**没有任何手势** —— 点了不会有反应。
                //    个性化推荐没有独立的「全部」页（`recommendationsProvider` 只喂这条横滑），
                //    所以按本页自己的规则处理：没有去处就不画入口，而不是画一个假的。
                //    补齐需要一个推荐列表页或对应的品类深链。
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
            // 2026-08-27：服务端补下发 mainImageUrl 后这里才真的有图可显示。
            // 此前只有裸 mainImageKey、客户端无 key→URL 拼装 ⇒ 恒为斜纹占位。
            // 🔴 与网格同口径用 [BoxFit.contain]：滑道卡是 1:1 的方框，而商品图素材也是 1:1，
            //    正常情况下不会留白；真遇到非方图时宁可留白也不裁掉商品主体。
            ShopImage(
                url: it.mainImageUrl,
                size: kRailImageSize,
                fit: BoxFit.contain,
                radius: ShopShape.radiusButton),
            const SizedBox(height: 6),
            Expanded(
              child: Align(
                alignment: Alignment.topLeft,
                child: Text(it.name,
                    maxLines: 2, overflow: TextOverflow.ellipsis, style: ShopText.productNameCard),
              ),
            ),
            Text(formatIdr(it.minPrice),
                style: ShopText.priceRail.copyWith(color: ShopColors.accent)),
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
    // ⚠️ <b>这不是真正的「曝光」</b>（2026-08-27 审查记录，未修）：瀑布流用
    //    [SliverToBoxAdapter] 一次性构建全部卡片，因此首屏会同时报出 92 条
    //    `toko_product_shown`，而用户实际只看得到 4 个。要变成真曝光需要
    //    `VisibilityDetector`（当前不是依赖）或把网格改成惰性 sliver ——
    //    后者本来就是这里 TODO 的分页改造。在那之前，**不要拿这个事件算曝光转化率**，
    //    它现在的含义是「进过列表的商品数」。
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
              // 🔴 2026-08-27 瀑布流：宽度撑满、高度随图片比例走，
              //    既不裁切（cover 的毛病）也不留白（contain 的毛病）。
              _AutoHeightImage(url: p.mainImageUrl, aspect: p.mainImageAspect),
              const SizedBox(height: 6),
              // 🔴 **不能再用 Expanded**：瀑布流下卡片高度由内容决定，Column 处于
              //    无界高度约束中，Expanded 会直接抛「RenderFlex has unbounded height」。
              //    原来用它是为了让价格行在等高网格里天然对齐 —— 瀑布流本就不要求对齐，
              //    这个诉求随定高一起消失了。溢出也不再可能：高度跟着内容长。
              Align(
                alignment: Alignment.topLeft,
                child: Text(p.name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: ShopText.productNameCard),
              ),
              const SizedBox(height: 4),
              Text(
                // 🔴 无 SKU 时显占位而非 `Rp 0` —— 那是错的价格，不是缺失的价格。
                p.minPrice == null ? l10n.tokoPriceUnavailable : formatIdr(p.minPrice!),
                style: ShopText.priceGrid.copyWith(
                    color: p.minPrice == null ? ShopColors.text4 : ShopColors.accent),
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
/// 高度随图片比例走的商品图（瀑布流专用）。
///
/// 🔴 与 [ShopImage] 的分工：那个**必须**给定高（`size`），服务于等高网格与各处方框；
/// 这个的高度由图片比例决定 —— 这正是瀑布流成立的前提。
///
/// ## 两条路径，取决于 [aspect] 有没有
///
/// - **已知比例**（后端下发了宽高）：[AspectRatio] 预置高度 ⇒ 图片解码前后**布局不变**，
///   没有任何跳动。这是 2026-08-27 补上尺寸列后的默认路径。
/// - **未知比例**（存量商品，尺寸恒为 null）：退回「宽度撑满、高度等解码」——
///   加载中用 1:1 方块占位而不是零高度，把跳动收敛成「1:1 → 实际比例」这一次；
///   零高度会让整列卡片在图片陆续到达时反复向下弹跳。
///
/// ⚠️ 已知比例时用 [BoxFit.cover]：容器比例就是图片比例，正常情况一个像素都不会裁掉；
/// 只有比例被 [kShopImageRatioMin]/[kShopImageRatioMax] 收敛过的极端长图才会裁 ——
/// 那正是收敛想要的效果（宁可裁，不让一张长图把后面的商品全挤出首屏）。
class _AutoHeightImage extends StatelessWidget {
  const _AutoHeightImage({required this.url, this.aspect});

  final String? url;

  /// 图片宽高比（w / h），已在 domain 层收敛过。null = 未知。
  final double? aspect;

  @override
  Widget build(BuildContext context) {
    // 占位要撑满列宽再取高，故用 LayoutBuilder 拿实际宽度。
    final placeholder = LayoutBuilder(
      builder: (ctx, c) => ShopImage(
        url: null,
        size: c.maxWidth.isFinite ? c.maxWidth : kGridImageHeight,
        fillWidth: true,
        radius: ShopShape.radiusChip,
      ),
    );
    final u = url;
    final a = aspect;
    if (u == null || u.isEmpty) {
      // 无图：比例已知时仍按该比例占位，免得后续补图时整列重排。
      return a == null ? placeholder : AspectRatio(aspectRatio: a, child: placeholder);
    }
    final dpr = MediaQuery.devicePixelRatioOf(context);
    // 列表图走 OSS 缩略图省流量；非 OSS 域原样返回（见 AppImage.ossResized）。
    final src = AppImage.ossResized(u, width: (kMasonryThumbWidth * dpr).round());
    final radius = BorderRadius.circular(ShopShape.radiusChip);

    if (a != null) {
      return ClipRRect(
        borderRadius: radius,
        child: AspectRatio(
          aspectRatio: a,
          child: Image.network(
            src,
            fit: BoxFit.cover,
            // 高度已由 AspectRatio 定死，占位不会改变布局 ⇒ 无跳动。
            frameBuilder: (ctx, child, frame, wasSync) =>
                frame == null && !wasSync ? placeholder : child,
            errorBuilder: (_, _, _) => placeholder,
          ),
        ),
      );
    }

    return ClipRRect(
      borderRadius: radius,
      child: Image.network(
        src,
        width: double.infinity,
        fit: BoxFit.fitWidth,
        // frame == null ⇒ 首帧尚未解码，先占方块。wasSync 为真表示同步命中缓存，直接给图。
        frameBuilder: (ctx, child, frame, wasSync) =>
            frame == null && !wasSync ? placeholder : child,
        errorBuilder: (_, _, _) => placeholder,
      ),
    );
  }
}

/// 卡片里名称+价格那块相对于列宽的高度占比，仅用于分列时估算总高。
/// ⚠️ 不需要精确：估错只会让两列略不齐，不影响任何正确性。
const double kMasonryTextBlockRatio = 0.35;

/// 瀑布流列宽下的缩略图取图宽度（逻辑像素）。两列 + 3px 灰缝，单列约占屏宽一半。
const double kMasonryThumbWidth = 200;

/// 横滑卡的固定高度（含来源标签那一行；游客态无标签时留白，卡片仍等高）。
double _railCardExtent(BuildContext context) {
  final ts = MediaQuery.textScalerOf(context);
  return kRailImageSize + 6 + ts.scale(kCardProductNameHeight) + 4 + ts.scale(22) + 4 + ts.scale(18);
}
