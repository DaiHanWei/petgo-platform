import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_guide_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart';
import 'cart_icon_button.dart';

/// 商品详情页（Story 1.7，FR-94A / FR-95 / FR-104 / UX-DR10）。
///
/// 🔴 **三条硬规则，写错任何一条都会造成真实损失：**
///
/// 1. **多规格时不默认选中第一个**（FR-94A）——默认选中会让用户在没意识到的情况下买错规格；
///    1.5kg 和 7.5kg 的狗粮差价近 4 倍，误购的退货成本由平台承担（自营）。
/// 2. **`Sisa {n}` 的 n 取真实剩余数**（FR-95）——虚构数字制造的紧迫感一旦被戳穿，
///    损失的是整个平台的可信度，而不是一单。
/// 3. **「开封不退」必须在本页明示**（FR-104 三处明示的**第 1 处**；第 2 处结算页 Epic 3、
///    第 3 处退货申请页 Epic 5）——宠物食品拆封即不可退是安全侧默认值，
///    不在购买前说清就是把纠纷推给客服。
///
/// 退货规则标识为 **UX-DR10 的二义三值**：可退 / 开封不退 / 不可退。
/// C-13 砍掉换货后，原「可退可换」措辞作废——换货零实现，标出来就是无法兑现的承诺。
class ProductDetailPage extends ConsumerStatefulWidget {
  const ProductDetailPage({super.key, required this.token});

  final String token;

  @override
  ConsumerState<ProductDetailPage> createState() => _ProductDetailPageState();
}

class _ProductDetailPageState extends ConsumerState<ProductDetailPage> {
  /// 🔴 初值 null 且**不在任何地方被自动赋值**——多规格必须由用户显式选择（FR-94A）。
  /// 单一规格走 [_effectiveSku] 的直通分支，不需要写进这个字段。
  String? _selectedSkuToken;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_detail_viewed', {'product_token': widget.token});
  }

  /// 当前生效的 SKU：单规格直通，多规格必须已选。
  ShopSku? _effectiveSku(ShopProductDetail d) {
    if (d.isSingleSku) return d.skus.first;
    if (_selectedSkuToken == null) return null;
    for (final s in d.skus) {
      if (s.token == _selectedSkuToken) return s;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopProductDetailProvider(widget.token));

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        // 加购后角标当场跟上（同一份 cartProvider）——不给反馈用户会重复点。
        actions: const [CartIconButton()],
      ),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Text(l10n.tokoDetailLoadFailed, textAlign: TextAlign.center),
          ),
        ),
        data: (d) => _content(l10n, d),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (d) => _bottomBar(l10n, d),
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _effectiveSku(d);
    // 未选规格时用商品级规则；选中后以该 SKU 的为准（同商品不同 SKU 可不同）
    final policy = sku?.returnPolicy ?? d.returnPolicy;

    return ListView(
      padding: const EdgeInsets.only(bottom: AppSpacing.xl),
      children: [
        _gallery(d),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: AppSpacing.md),
              Text(d.brand.toUpperCase(),
                  style: const TextStyle(fontSize: 12, letterSpacing: 1, color: AppColors.mint600)),
              const SizedBox(height: AppSpacing.xs),
              Text(d.name,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              const SizedBox(height: AppSpacing.sm),
              _price(l10n, d, sku),
              const SizedBox(height: AppSpacing.lg),

              if (!d.isSingleSku) ...[
                Text(l10n.tokoChooseVariant,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.sm),
                _variantSelector(l10n, d),
                const SizedBox(height: AppSpacing.lg),
              ],

              if (sku != null) ...[_stockLine(l10n, sku), const SizedBox(height: AppSpacing.lg)],

              // 🔴 FR-104 第 1 处明示。三处措辞须一致（UX-DR11 要求跨 Epic 核查）。
              _returnPolicyBanner(l10n, policy),
              const SizedBox(height: AppSpacing.lg),

              if (d.feedingGuide.isNotEmpty) ...[
                Text(l10n.tokoFeedingGuideTitle,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.sm),
                for (final e in d.feedingGuide)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.xs),
                    child: Text('${e.minWeightKg}–${e.maxWeightKg} kg → ${e.gramsPerDay} g/hari'),
                  ),
                const SizedBox(height: AppSpacing.lg),
              ],

              if (d.detailHtml != null) ...[
                Text(l10n.tokoDetailSectionTitle,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.sm),
                // V1 不引富文本渲染库（NFR-1 不加依赖）：剥标签按纯文本呈现。
                Text(_stripHtml(d.detailHtml!)),
                const SizedBox(height: AppSpacing.lg),
              ],

              if (d.shelfLifeNote != null) ...[
                Text(l10n.tokoShelfLifeTitle,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                const SizedBox(height: AppSpacing.xs),
                Text(d.shelfLifeNote!),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _gallery(ShopProductDetail d) {
    final images = [if (d.mainImageUrl != null) d.mainImageUrl!, ...d.galleryUrls];
    if (images.isEmpty) {
      return const AspectRatio(
          aspectRatio: 1, child: ColoredBox(color: AppColors.mintTint2));
    }
    return AspectRatio(
      aspectRatio: 1,
      child: PageView(
        children: [
          for (final url in images)
            Image.network(url,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => const ColoredBox(color: AppColors.mintTint2)),
        ],
      ),
    );
  }

  Widget _price(AppLocalizations l10n, ShopProductDetail d, ShopSku? sku) {
    // 已选规格 → 该规格价；未选 → 起价；无 SKU → 占位（绝不显示 Rp 0）
    final value = sku?.price ?? d.minPrice;
    return Text(
      value == null ? l10n.tokoPriceUnavailable : formatIdr(value),
      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.mint),
    );
  }

  /// 规格选择器。🔴 售罄规格仍可见（便于用户看到「有这个规格但暂时没货」）但不可选。
  Widget _variantSelector(AppLocalizations l10n, ShopProductDetail d) {
    return Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        for (final s in d.skus)
          ChoiceChip(
            label: Text(s.stockStatus == StockStatus.outOfStock
                ? '${s.specName} · ${l10n.tokoOutOfStock}'
                : '${s.specName} · ${formatIdr(s.price)}'),
            selected: _selectedSkuToken == s.token,
            onSelected: s.stockStatus == StockStatus.outOfStock
                ? null
                : (on) => setState(() => _selectedSkuToken = on ? s.token : null),
          ),
      ],
    );
  }

  /// 已上报过售罄的 SKU —— 同一规格反复 rebuild 不重复打点。
  final Set<String> _outOfStockReported = {};

  Widget _stockLine(AppLocalizations l10n, ShopSku sku) {
    // Story 1.8 埋点收口：售罄曝光是转化漏斗上最值得看的流失点之一
    //（用户想买但没货 ≠ 用户不想买），Epic 6 的补货提醒要靠它判断值不值得做。
    if (sku.stockStatus == StockStatus.outOfStock && _outOfStockReported.add(sku.token)) {
      Analytics.capture('toko_out_of_stock_shown', {
        'product_token': widget.token,
        'sku_token': sku.token,
      });
    }
    final text = switch (sku.stockStatus) {
      StockStatus.outOfStock => l10n.tokoOutOfStockLine,
      // 🔴 n 取真实剩余数；remaining 缺失时降级为不展示数字，绝不编一个
      StockStatus.lowStock =>
        sku.remaining == null ? l10n.tokoLowStockNoCount : l10n.tokoLowStock(sku.remaining!),
      StockStatus.inStock => null,
    };
    if (text == null) return const SizedBox.shrink();
    return Text(text,
        style: const TextStyle(fontWeight: FontWeight.w600, color: AppColors.mint600));
  }

  /// 🔴 FR-104：退货规则必须在购买前明示。三值措辞由 UX-DR10 定死。
  Widget _returnPolicyBanner(AppLocalizations l10n, ReturnPolicy policy) {
    final (title, body) = switch (policy) {
      ReturnPolicy.returnable => (l10n.tokoReturnableTitle, l10n.tokoReturnableBody),
      ReturnPolicy.noReturnAfterOpen =>
        (l10n.tokoNoReturnAfterOpenTitle, l10n.tokoNoReturnAfterOpenBody),
      ReturnPolicy.noReturn => (l10n.tokoNoReturnTitle, l10n.tokoNoReturnBody),
    };
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.mintTint,
        borderRadius: BorderRadius.circular(AppSpacing.sm),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: AppSpacing.xxs),
          Text(body, style: const TextStyle(fontSize: 13)),
        ],
      ),
    );
  }

  /// 底部加购栏。
  ///
  /// 🔴 **启用条件：已有生效 SKU 且该 SKU 可售。** 多规格未选 → 禁用（FR-94A）。
  Widget _bottomBar(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _effectiveSku(d);
    final canAdd = sku != null && sku.stockStatus.purchasable && !_adding;
    final label = sku == null
        ? l10n.tokoChooseVariantFirst
        : (sku.stockStatus == StockStatus.outOfStock ? l10n.tokoOutOfStock : l10n.tokoAddToCart);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: SizedBox(
          height: 48,
          child: FilledButton(
            onPressed: canAdd ? () => _onAddTapped(l10n, sku) : null,
            child: Text(label),
          ),
        ),
      ),
    );
  }

  /// 加购请求进行中（防连点重复加）。
  bool _adding = false;

  /// 加购（Story 3.6，FR-96）。
  ///
  /// 🔴 **游客走软性登录引导，登录成功后自动完成本次加购并停留原页**——
  /// 不跳登录页、不跳走、不丢意图。把用户踢到登录页再让他自己找回来，
  /// 是转化漏斗上最贵的一次流失，而这次点击恰恰是他表达购买意图的那一下。
  ///
  /// 用 `allowRepeat: true` 绕过软浮层的 session 去重：主动动作触发的引导若被去重吞掉，
  /// 按钮看起来就是坏的（见 `LoginGuideController.showSoftSheet` 的注释）。
  void _onAddTapped(AppLocalizations l10n, ShopSku sku) {
    Analytics.capture('toko_add_to_cart_tapped', {
      'product_token': widget.token,
      'sku_token': sku.token,
    });
    if (ref.read(authControllerProvider).isLoggedIn) {
      _performAdd(l10n, sku);
      return;
    }
    ref.read(loginGuideControllerProvider).showSoftSheet(
          context,
          pendingAction: RouteIntent(onResume: () => _performAdd(l10n, sku)),
          entrySource: 'toko_add_to_cart',
          allowRepeat: true,
        );
  }

  Future<void> _performAdd(AppLocalizations l10n, ShopSku sku) async {
    if (_adding) return;
    setState(() => _adding = true);
    try {
      await ref.read(cartProvider.notifier).add(sku.token);
      if (mounted) showAppToast(context, l10n.cartAdded);
    } on CartMutationError catch (e) {
      if (mounted) {
        showAppToast(
            context, e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
      }
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }

  static String _stripHtml(String html) =>
      html.replaceAll(RegExp(r'<[^>]*>'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
}
