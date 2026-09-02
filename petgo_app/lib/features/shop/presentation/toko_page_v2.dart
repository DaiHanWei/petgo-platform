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
import 'widgets/shop_product_masonry.dart';
import 'widgets/shop_surface.dart';

class TokoPageV2 extends ConsumerStatefulWidget {
  const TokoPageV2({super.key, this.initialCategory});

  final String? initialCategory;

  @override
  ConsumerState<TokoPageV2> createState() => _TokoPageV2State();
}

class _TokoPageV2State extends ConsumerState<TokoPageV2> {
  ShopCategory? _selected;

  /// 🔴 本页**不再持有关键词**（2026-09-02 产品定形）：吸顶行只剩一个放大镜，
  /// 输入、防抖、结果全在 [ShopSearchPage]。这里恒以 `keyword: null` 取数 ——
  /// 分类是列表的筛选，搜索是另一条路径，两者不再挤同一个页面状态。
  ShopProductsQuery get _query => (category: _selected, keyword: null);

  @override
  void initState() {
    super.initState();
    _selected = ShopCategory.fromApi(widget.initialCategory);
    Analytics.capture('toko_tab_viewed');
  }

  /// FR-110 品类跳转二次注入：/shop 是 indexedStack 分支根，State 被保活 ——
  /// 第二次 `go('/shop?category=Y')` 只重建 widget、initState 不重跑，须在此消费新品类。
  /// `_selected` 变更后 build 里 `shopProductsProvider(_selected)` 随 watch 自动换源，无需额外加载。
  /// 已知边界：同一 category 二连跳（新旧值相同）不触发 —— 页面本就停在该品类，可接受。
  @override
  void didUpdateWidget(covariant TokoPageV2 oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.initialCategory != oldWidget.initialCategory && widget.initialCategory != null) {
      setState(() => _selected = ShopCategory.fromApi(widget.initialCategory));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final products = ref.watch(shopProductsProvider(_query));
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;
    // banner 拉取失败一律当作"没有"（repository 里已吞掉异常）：
    // 它是锦上添花的展示位，不该让整页进错误态。加载中同样按"没有"处理 ——
    // 先给白色顶栏、图到了再换，比先留一块空白再弹出 banner 稳。
    final banner = ref.watch(shopBannerProvider).asData?.value;
    // 有 banner → 透明浮在图上；没有 → 白色顶栏（产品要求与其他板块区分）。
    final tone = banner != null ? ShopAppBarTone.transparent : ShopAppBarTone.light;
    // 🔴 顶栏里的标题与两个胶囊**一律从 tone 取色**（D-1）。此前它们各自写死
    //    `ShopColors.surface`（白）+ `onInk12`（白 12% 底），于是无 banner 的白底顶栏上
    //    整条恒为 RGB(255,255,255)：标题、金币余额、购物车入口全部不可见。
    final bar = ShopAppBar.colorsOf(tone);

    return Scaffold(
      backgroundColor: ShopColors.bg,
      // 🔴 有 banner 时让 body 穿到 AppBar 之下 —— 这是"图顶到屏幕最上沿、
      //    上方不留纯色条"的前提。没有它，AppBar 会先占掉状态栏+48px 的高度。
      extendBodyBehindAppBar: banner != null,
      appBar: ShopAppBar(
        title: l10n.tokoTitle,
        large: true,
        tone: tone,
        actions: [
          _pawcoinCapsule(l10n, loggedIn, bar),
          const SizedBox(width: 8),
          _CartCapsule(bar: bar),
          const SizedBox(width: kShopScreenEdge),
        ],
        // 筛选行（2026-09-02 产品定形）：**放大镜 + 分类依次排开**，共用一行。
        //
        // 🔴 挂在顶栏的 bottom 槽而不是做成 pinned sliver：有 banner 时 Scaffold 开了
        //    extendBodyBehindAppBar，滚动区从 y=0 起算，pinned sliver 会吸在 0 上、
        //    正好躲进浮着的顶栏底下被盖住。bottom 槽由 AppBar 自己占位，
        //    天然不存在这类重叠，且「滚动不走」是无条件成立的。
        //
        // 🔴 分类**必须吸顶**（R-4）：原先它夹在屏幕 45% 处、跟内容一起滚，
        //    往下翻两屏就完全离开视野，换品类得一路滚回顶部。它是页面级导航，
        //    不是流中的一个区块。
        bottom: _FilterBar(
          selected: _selected,
          allLabel: l10n.tokoCategoryAll,
          searchLabel: l10n.tokoSearchOpen,
          labelOf: (c) => _categoryLabel(l10n, c),
          onSelect: (c) => setState(() => _selected = c),
          onSearch: () => context.push('/shop/search'),
        ),
      ),
      body: RefreshIndicator(
        onRefresh: () async {
          ref.invalidate(shopProductsProvider(_query));
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
            products.when(
              loading: () => const SliverToBoxAdapter(child: _CenteredBox(child: CircularProgressIndicator())),
              error: (_, _) => SliverToBoxAdapter(
                child: ShopRetryState(
                  message: l10n.tokoLoadFailed,
                  retryLabel: l10n.commonRetry,
                  onRetry: () => ref.invalidate(shopProductsProvider(_query)),
                ),
              ),
              data: (items) => items.isEmpty
                  // 本页只会是「这个品类下没有商品」。「关键词搜不到」是搜索页的空态，
                  // 两句话必须分开 —— 混用会让用户以为整个店没货。
                  ? SliverToBoxAdapter(
                      child: _CenteredBox(
                        child: Text(l10n.tokoEmpty,
                            style: ShopText.body, textAlign: TextAlign.center),
                      ),
                    )
                  : SliverToBoxAdapter(
                      child: ShopProductMasonry(
                        items: items,
                        entrySource:
                            _selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY',
                      ),
                    ),
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
  Widget _pawcoinCapsule(AppLocalizations l10n, bool loggedIn, ShopAppBarColors bar) {
    if (!loggedIn) {
      return _Capsule(
        key: const ValueKey('tokoLoginCapsule'),
        background: bar.capsule,
        onTap: () => context.push('/login'),
        child: Text(l10n.loginTitle,
            style: ShopText.badge.copyWith(fontSize: 11, color: bar.foreground)),
      );
    }
    // ⚠️ 复用余额页的 provider —— 它同时会拉一页流水，对顶栏而言偏重。
    //    后端有独立的余额端点后应换过去；此处不自行拼一个接口。
    final balance = ref.watch(pawCoinProvider).maybeWhen(
        data: (s) => s.balance, orElse: () => null);
    return _Capsule(
      key: const ValueKey('tokoPawcoinCapsule'),
      background: bar.capsule,
      onTap: () => context.push('/me/pawcoin'),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const ShopCoinMark(size: 14),
          const SizedBox(width: 5),
          Text(
            // 余额未加载完时先只画胶囊壳，不画 0（同上：0 是错的信息，不是缺失的信息）。
            balance == null ? '—' : _compactIdr(balance),
            style: ShopText.badge.copyWith(fontSize: 11, color: bar.foreground),
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
  String _categoryLabel(AppLocalizations l10n, ShopCategory c) => switch (c) {
        ShopCategory.makanan => l10n.tokoCategoryMakanan,
        ShopCategory.obatVitamin => l10n.tokoCategoryObatVitamin,
        ShopCategory.camilan => l10n.tokoCategoryCamilan,
        ShopCategory.perawatan => l10n.tokoCategoryPerawatan,
      };
}

/// 吸顶筛选行：**放大镜 + 分类**（2026-09-02 产品定形，R-4 + C-18 的落位合并）。
///
/// ## 为什么搜索只留一个图标
/// C-18 要给搜索一个常驻位，R-4 要把分类挪进同一个吸顶槽 —— 两者争这一个位置。
/// 叠成两行会让顶栏增高约 170px、吃掉首屏；挤一行则输入框窄到打不了字、分类也剩不下几个。
/// 产品的解法是**把搜索收成一个放大镜**：它只花一个图标的宽度，剩下的全给分类，
/// 完整的搜索形态（键盘、输入、结果）搬进 [ShopSearchPage]。
///
/// ## 显式的「全部」
/// 🔴 没有它时，取消筛选只能「再点一次已选中的标签」—— 隐藏交互，用户不会主动试。
/// 「全部」默认选中、放最左，直观且可发现。
class _FilterBar extends StatelessWidget implements PreferredSizeWidget {
  const _FilterBar({
    required this.selected,
    required this.allLabel,
    required this.searchLabel,
    required this.labelOf,
    required this.onSelect,
    required this.onSearch,
  });

  /// `null` = 全部（对应最左那个「全部」标签选中）。
  final ShopCategory? selected;
  final String allLabel;
  final String searchLabel;
  final String Function(ShopCategory) labelOf;
  final ValueChanged<ShopCategory?> onSelect;
  final VoidCallback onSearch;

  /// 行高 = 标签 28 + 上下各 8。计入 [ShopAppBar.preferredSize]，改这里顶栏会自己变高。
  static const double _rowHeight = 28;
  static const double _vPad = 8;

  @override
  Size get preferredSize => const Size.fromHeight(_rowHeight + _vPad * 2);

  @override
  Widget build(BuildContext context) => SizedBox(
        height: _rowHeight + _vPad * 2,
        child: Row(
          children: [
            const SizedBox(width: kShopScreenEdge),
            Semantics(
              button: true,
              label: searchLabel,
              child: ShopPressable(
                key: const ValueKey('tokoSearchEntryV2'),
                onTap: onSearch,
                // 命中区撑到 44：图标本身只有 20，按设计稿画出来会小到点不准。
                minSize: kShopMinTapTarget,
                child: const Icon(Icons.search, size: 20, color: ShopColors.purple),
              ),
            ),
            const SizedBox(width: 4),
            Expanded(
              // 🔴 横滑，不换行：品类数一旦增加（运营加类目）换行会继续往下涨，
              //    把首屏可见商品越挤越少。横滑把这行的高度**钉死为一行**，与品类数解耦。
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.only(right: kShopScreenEdge),
                // 内容不足一屏时不要回弹，否则短列表也能被拖动，像是还有内容没显示。
                physics: const ClampingScrollPhysics(),
                child: Row(
                  children: [
                    _chip(allLabel, selected == null, () => onSelect(null)),
                    for (final c in ShopCategory.values) ...[
                      const SizedBox(width: 6),
                      // 🔴 再点一次已选中的标签**不再取消** —— 取消现在有「全部」这个显式出口。
                      //    保留「点一下取消」会让用户在两种心智之间猜。
                      _chip(labelOf(c), selected == c, () => onSelect(c)),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      );

  /// 🔴 选中态 = **品牌紫实心底 + 白字**（R-4 ②）。此前选中与未选中几乎不可辨，
  /// 而这一行现在是页面级导航，选中态读不出来等于不知道自己在看哪个品类。
  /// 紫色与 D-1 定的顶栏主体色同源 —— 整条顶栏读起来才是同一组控件。
  Widget _chip(String label, bool isSelected, VoidCallback onTap) => ShopChip(
        label: label,
        selected: isSelected,
        selectedColor: ShopColors.purple,
        onTap: onTap,
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
  const _Capsule({super.key, required this.child, required this.background, this.onTap});

  final Widget child;

  /// 🔴 由顶栏 tone 决定（[ShopAppBar.colorsOf]），不可写死：
  /// 原先固定 `onInk12`（白 12%），在白底顶栏上等于没有底 —— D-1 的一半。
  final Color background;

  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => ShopPressable(
        onTap: onTap,
        // 胶囊视觉高约 26，命中区撑到 44（顶栏 toolbarHeight 是 48，放得下）。
        minSize: kShopMinTapTarget,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
          decoration: BoxDecoration(
            color: background,
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
  const _CartCapsule({required this.bar});

  /// 🔴 见 [_Capsule.background]：图标与底色都得跟着顶栏 tone 走，否则白底上整个不可见。
  final ShopAppBarColors bar;

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
                color: bar.capsule,
                borderRadius: BorderRadius.circular(ShopShape.radiusButton),
              ),
              child: Icon(Icons.shopping_cart_outlined,
                  size: 16, color: bar.foreground),
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

/// 设计稿：横滑商品图 120×120。
const double kRailImageSize = 120;

/// 横滑卡的固定高度（含来源标签那一行；游客态无标签时留白，卡片仍等高）。
double _railCardExtent(BuildContext context) {
  final ts = MediaQuery.textScalerOf(context);
  return kRailImageSize + 6 + ts.scale(kCardProductNameHeight) + 4 + ts.scale(22) + 4 + ts.scale(18);
}
