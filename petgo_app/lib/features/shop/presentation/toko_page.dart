import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../data/shop_repository.dart';
import '../domain/shop_product.dart';
import 'cart_icon_button.dart';
import 'widgets/repurchase_zones.dart';

/// Toko 首页（Story 1.6，FR-93 / FR-93A）。
///
/// 结构自上而下**只有两段**：区域③「Kategori」+ 区域④「Semua Pilihan」。
///
/// Story 6.4 接入区域①（补货提醒，FR-109）与区域②（为我的宠物精选，FR-107）。
/// 🔴 **两区在「无内容」时整区不渲染，且不留空标题**（原型注释原文：「①② 区域整体不渲染，
/// 无空标题」）——一个写着「补货提醒」却什么都没有的标题，比没有这一区更让人困惑。
/// 游客态在**数据层**短路（provider 不发 `/me` 请求），不是靠这里不画。
/// 这是 PRD 定义的**合法状态**（无触发 / 无档案时本就不渲染），不是待补的占位——
/// 原型注释原文：「①② 区域整体不渲染，无空标题」。页面第一个可见元素必须是「Kategori」。
/// 两区在 Epic 6 接入。
///
/// 🔴 **不提供全站搜索框**（FR-93）——搜索框会把产品心智推向通用货架，与「精选不做货架」的
/// 战略边界冲突。这与 C-7 的 SKU 上限 30 是同一套论证的两半，**不是暂缓，是永久性决策**。
///
/// 🔒 **游客可全程浏览，不触发任何登录引导**（FR-93A）。这与 V1.1.2 FR-78「未登录点击非落地 Tab
/// 触发登录引导」**有意不同**：商品浏览是转化漏斗最上层，用登录墙拦截会直接杀掉转化。
/// 实现上靠「`/shop` 不在 `_controlledLocations` 白名单里」达成，**零安全规则改动**。
/// 登录引导推迟到加入购物车（Epic 3）。
class TokoPage extends ConsumerStatefulWidget {
  const TokoPage({super.key, this.initialCategory});

  /// 进页即预选的品类 code（`/shop?category=OBAT_VITAMIN`）。
  ///
  /// 🔴 目前唯一的注入方 是健康记录页的 FR-110 品类跳转（Story 9.1）——
  /// 那条路径上**只有品类 code 能通过**，所以这里也只收 code，不收商品/SKU。
  final String? initialCategory;

  @override
  ConsumerState<TokoPage> createState() => _TokoPageState();
}

class _TokoPageState extends ConsumerState<TokoPage> {
  /// 当前选中品类（null = 全部精选）。纯 UI 筛选，不提升成全局状态。
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

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        title: Text(l10n.tokoTitle),
        backgroundColor: AppColors.cream,
        actions: const [
          // Story 3.6 接上真实购物车页（角标 = 商品件数）。游客点进去看到的是「登录后查看」
          // 空态 + 软性引导，**不是登录墙**——浏览路径依旧零门槛（FR-93A）。
          CartIconButton(),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () async => ref.invalidate(shopProductsProvider(_selected)),
        child: CustomScrollView(
          slivers: [
            // 区域①（Story 6.4）：无触发时 RepurchaseZone 自己返回 shrink，此处不加任何判断 ——
            // 判断放在组件里，首页才不会因为多一区就多一层状态分支。
            const SliverToBoxAdapter(child: RepurchaseZone()),
            // 区域②（Story 6.4/6.5）：未建档时它自己换成建档引导卡（复用 FR-0G 文案）。
            const SliverToBoxAdapter(child: ProfileRecoZone(zone: 'toko_profile_reco')),
            SliverToBoxAdapter(child: _sectionLabel(l10n.tokoCategoryLabel)),
            SliverToBoxAdapter(child: _categoryChips(l10n)),
            SliverToBoxAdapter(child: _sectionLabel(l10n.tokoAllFeaturedLabel)),
            products.when(
              loading: () => const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(AppSpacing.xl),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ),
              error: (_, _) => SliverToBoxAdapter(
                child: _centeredHint(l10n.tokoLoadFailed),
              ),
              data: (items) => items.isEmpty
                  ? SliverToBoxAdapter(child: _centeredHint(l10n.tokoEmpty))
                  : _productGrid(items),
            ),
            const SliverToBoxAdapter(child: SizedBox(height: AppSpacing.lg)),
          ],
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.sm),
        child: Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
      );

  Widget _centeredHint(String text) => Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Center(child: Text(text, textAlign: TextAlign.center)),
      );

  /// 区域③：四个**固定**品类 chip。选中即重拉；再点一次取消选中回到全部精选。
  Widget _categoryChips(AppLocalizations l10n) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Row(
        children: [
          for (final c in ShopCategory.values)
            Padding(
              padding: const EdgeInsets.only(right: AppSpacing.sm),
              child: ChoiceChip(
                label: Text(_categoryLabel(l10n, c)),
                selected: _selected == c,
                onSelected: (on) => setState(() => _selected = on ? c : null),
              ),
            ),
        ],
      ),
    );
  }

  /// 品类展示名用**印尼语原文**（Makanan / Obat & Vitamin / Camilan / Perawatan）——
  /// 印尼是目标市场，这四个词本身就是用户语言，不做二次翻译。
  String _categoryLabel(AppLocalizations l10n, ShopCategory c) => switch (c) {
        ShopCategory.makanan => l10n.tokoCategoryMakanan,
        ShopCategory.obatVitamin => l10n.tokoCategoryObatVitamin,
        ShopCategory.camilan => l10n.tokoCategoryCamilan,
        ShopCategory.perawatan => l10n.tokoCategoryPerawatan,
      };

  /// 区域④：全部精选，两列网格（照原型 `.pgrid`）。
  Widget _productGrid(List<ShopProductSummary> items) {
    return SliverPadding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      sliver: SliverGrid(
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: AppSpacing.md,
          crossAxisSpacing: AppSpacing.md,
          childAspectRatio: 0.72,
        ),
        delegate: SliverChildBuilderDelegate(
          (context, i) => _ProductCard(
            product: items[i],
            // 选了品类 = 从品类入口进；未选 = 区域④ 全部精选
            entrySource: _selected == null ? 'TOKO_ALL_FEATURED' : 'TOKO_CATEGORY',
          ),
          childCount: items.length,
        ),
      ),
    );
  }
}

/// 商品卡。🔴 图与价都**可能为空**，两者都必须优雅降级而不是崩或显 0。
class _ProductCard extends StatefulWidget {
  const _ProductCard({required this.product, required this.entrySource});

  final ShopProductSummary product;

  /// 🔴 归因来源（Story 3.10）：区域④「全部精选」与按品类筛选是两个不同的入口，
  /// 混为一谈会让 AB-13B 算不出「品类页值不值得做」。
  final String entrySource;

  @override
  State<_ProductCard> createState() => _ProductCardState();
}

class _ProductCardState extends State<_ProductCard> {
  @override
  void initState() {
    super.initState();
    // zone 标明曝光来源区域；本 Story 只有区域④，Epic 6 接入①② 后会出现别的取值。
    // ⚠️ epics AC5 原文写的是 `product_impression`，但仓库的埋点命名护栏要求
    //    「模块前缀 + 动作词尾」（2026-08-04 用户明确要求：产品要能一眼看出是哪个页面的事件）。
    //    `product_impression` 两条都不合 → 改名为 toko_product_shown，语义与 zone 属性不变。
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
      // Story 1.7 已实现详情页 → 接上导航（游客同样可进，路由不在受控名单里）
      // `?from=` 把入口带进详情页 → 加购时落到购物车行（Story 3.10 归因链）
      onTap: () => context.push('/shop/products/${p.token}?from=${widget.entrySource}'),
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.cream,
          borderRadius: BorderRadius.circular(AppSpacing.md),
          border: Border.all(color: AppColors.mintTint),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: ClipRRect(
                borderRadius: const BorderRadius.vertical(top: Radius.circular(AppSpacing.md)),
                child: _thumb(p.mainImageUrl),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.sm),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.name, maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500)),
                  const SizedBox(height: AppSpacing.xs),
                  Text(
                    // 🔴 无 SKU 时不显示 "Rp 0" —— 那是错的价格，不是缺失的价格
                    p.minPrice == null ? l10n.tokoPriceUnavailable : formatIdr(p.minPrice!),
                    style: const TextStyle(
                        fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 🔴 URL 为 null（未配 CDN base / 无主图）或加载失败时都落到占位图，绝不白屏。
  Widget _thumb(String? url) {
    if (url == null) return const ColoredBox(color: AppColors.mintTint2);
    return Image.network(
      url,
      fit: BoxFit.cover,
      width: double.infinity,
      errorBuilder: (_, _, _) => const ColoredBox(color: AppColors.mintTint2),
    );
  }
}
