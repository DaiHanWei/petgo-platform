/// 商品详情页 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 3 在售 / 屏 4 售罄）。
///
/// ⚠️ 2026-08-28：v1 版式已整体删除，本文件是该页唯一实现（`_v2` 后缀保留以免制造纯改名 diff）。
///
/// ## 🔴 从 v1 原样继承的三条硬规则（版式变了，规则一条不改）
///
/// 1. **多规格不默认选中第一个**（FR-94A）—— 默认选中会让人在没意识到时买错规格；
///    1.5kg 与 7.5kg 的粮差价近 4 倍，误购的退货成本由平台承担（自营）。
/// 2. **`Sisa {n}` 取真实剩余数**（FR-95）—— 虚构的紧迫感被戳穿一次，
///    赔掉的是整个平台的可信度而不是一单。
/// 3. **「开封不退」必须在本页明示**（FR-104 三处明示的第 1 处）。
///
/// ## 设计稿要求但当前无数据源的部分
///
/// 同样按设计稿自带的空态规则降级，逐条都是**合规实现**而非半成品：
///
/// | 设计元素 | 缺什么 | 降级 |
/// |---|---|---|
/// | 玫红价格块 + 促销倒计时 | 无原价/促销字段 | 「无活动时整块降为白底墨字，**不留空倒计时行**」 |
/// | `-20%` 角标与划线原价 | 同上 | 「无原价则两者都不显示」 |
/// | `128 terjual` 已售数 | 接口无该字段 | 整段不显示（**不拿评价数冒充已售数** —— 那是另一个量） |
/// | 档案匹配条 | 推荐接口不给「本商品与本宠物的匹配依据」 | 「无宠物档案时不渲染，其余布局不变」 |
/// | 到货通知订阅 | 无订阅端点 | **整块不渲染** —— 做一个点了没反应的开关比不做更糟 |
/// | 替代品横滑 | 无「同类目 ±40% 价格带」端点 | 「不足 2 个则整区不显示，不用占位卡凑数」 |
/// | 产地 | 无字段 | 元信息行少一项 |
///
/// ⚠️ 售罄态的 `Lihat Alternatif` 设计上是滚到替代品区。替代品区无数据源，
/// 故改为跳**同品类列表**（`/shop?category=`，既有能力）—— 语义仍是「看看别的」，
/// 不是编一个不存在的推荐。品类未知时该按钮不渲染。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/router/route_intent.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../auth/domain/auth_state.dart';
import '../../auth/domain/login_guide_controller.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/cart_repository.dart';
import '../data/shop_repository.dart';
import '../data/shop_review_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

class ProductDetailPageV2 extends ConsumerStatefulWidget {
  const ProductDetailPageV2({super.key, required this.token, this.entrySource});

  final String token;

  /// 归因来源（Story 3.10 归因链起点）。深链进来时为 null —— **不编默认值**。
  final String? entrySource;

  @override
  ConsumerState<ProductDetailPageV2> createState() => _ProductDetailPageV2State();
}

class _ProductDetailPageV2State extends ConsumerState<ProductDetailPageV2> {
  /// 🔴 初值 null 且**不在任何地方被自动赋值** —— 多规格必须由用户显式选择（FR-94A）。
  String? _selectedSkuToken;

  /// 图集当前页（页码指示器用）。
  int _galleryIndex = 0;

  bool _adding = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_product_detail_viewed', {'product_token': widget.token});
  }

  ShopSku? _effectiveSku(ShopProductDetail d) {
    if (d.isSingleSku) return d.skus.first;
    if (_selectedSkuToken == null) return null;
    for (final s in d.skus) {
      if (s.token == _selectedSkuToken) return s;
    }
    return null;
  }

  /// 售罄判定：**已选中的 SKU 售罄**，或**所有 SKU 都售罄**。
  ///
  /// 🔴 多规格且未选时不算售罄 —— 那时用户还没表达要买哪个，
  /// 把整页切成售罄态会挡住本来买得到的其它规格。
  bool _isSoldOut(ShopProductDetail d) {
    final sku = _effectiveSku(d);
    if (sku != null) {
      final out = sku.stockStatus == StockStatus.outOfStock;
      if (out) _reportOutOfStock(sku.token);
      return out;
    }
    final allOut =
        d.skus.isNotEmpty && d.skus.every((s) => s.stockStatus == StockStatus.outOfStock);
    if (allOut) {
      for (final s in d.skus) {
        _reportOutOfStock(s.token);
      }
    }
    return allOut;
  }

  /// 已上报过售罄曝光的 SKU（Story 1.8 埋点收口）。
  final Set<String> _outOfStockReported = {};

  /// 售罄曝光。
  ///
  /// 🔴 <b>2026-08-28 补</b>：`toko_out_of_stock_shown` 原本只在 v1 的详情页上报，
  /// 而 v2 自 2026-08-21 起就是默认版式 —— 这个指标实际上**已经空了一周多**，
  /// 只是 v1 代码还在、埋点清单对账测试就一直是绿的，直到 v1 整体删除才暴露。
  ///
  /// 售罄曝光是转化漏斗上最值得看的流失点之一（用户想买但没货 ≠ 用户不想买），
  /// Epic 6 的补货提醒要靠它判断值不值得做。
  ///
  /// ⚠️ 按 SKU 去重：[_isSoldOut] 在 build 期间被调用，不去重会随每次重建反复上报。
  void _reportOutOfStock(String skuToken) {
    if (!_outOfStockReported.add(skuToken)) return;
    Analytics.capture('toko_out_of_stock_shown', {
      'product_token': widget.token,
      'sku_token': skuToken,
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(shopProductDetailProvider(widget.token));

    // 🔴 <b>loading / error 态必须自带 AppBar</b>（2026-08-27）。本页正常态没有 AppBar ——
    //    返回箭头画在图区里（[_gallery] 的 Positioned）。而 loading/error 分支不渲染图区，
    //    于是接口一挂，用户面对的是一屏文字、**界面上一个可点的控件都没有**。
    //    系统返回手势还能用，但那是「用户得知道有这么个手势」，不是界面给的出口。
    final placeholderBar = async.hasValue ? null : ShopAppBar(title: l10n.tokoTitle);
    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: placeholderBar,
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => ShopRetryState(
          message: l10n.tokoDetailLoadFailed,
          retryLabel: l10n.commonRetry,
          onRetry: () => ref.invalidate(shopProductDetailProvider(widget.token)),
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
    // 未选规格用商品级规则；选中后以该 SKU 的为准（同商品不同 SKU 可不同）。
    final policy = sku?.returnPolicy ?? d.returnPolicy;
    final soldOut = _isSoldOut(d);

    return ListView(
      padding: EdgeInsets.zero,
      children: [
        _gallery(l10n, d, soldOut),
        _priceBlock(l10n, d, sku, soldOut),
        _titleBlock(l10n, d, sku, soldOut),
        if (d.skus.length > 1) _variantBlock(l10n, d),
        _returnPolicyBlock(l10n, policy),
        if (d.detailHtml != null) _detailBlock(l10n, d),
        // 到货通知 / 替代品区：均无数据源 → 整块不渲染（见文件头表格）。
      ],
    );
  }

  // ---------------------------------------------------------------- 图区

  /// 图区 266px 高。售罄时整图盖蒙层。
  Widget _gallery(AppLocalizations l10n, ShopProductDetail d, bool soldOut) {
    final images = <String?>[
      if (d.mainImageUrl != null) d.mainImageUrl,
      ...d.galleryUrls,
    ];
    // 一张都没有时仍渲染一个占位位，保持页面结构（不塌陷成没有图区的怪样子）。
    final pages = images.isEmpty ? <String?>[null] : images;

    final topInset = MediaQuery.paddingOf(context).top;
    // 🔴 本页图区是全幅出血、顶到状态栏之下，而页面没有 AppBar 去声明 overlay 样式。
    //    不声明的话状态栏图标颜色取决于上一个路由残留的设置，浅色商品图上会整排消失。
    //    这里显式声明 light（浅色图标），并在图片顶部铺一层极轻的墨色渐变兜底 ——
    //    渐变同时也是返回/购物车两个按钮的底衬（它们本来就带 imageButtonScrim）。
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: SizedBox(
      height: 266,
      child: Stack(
        children: [
          Positioned.fill(
            child: PageView.builder(
              itemCount: pages.length,
              onPageChanged: (i) => setState(() => _galleryIndex = i),
              itemBuilder: (c, i) => ShopImage(
                url: pages[i],
                size: 266,
                fillWidth: true,
                radius: 0,
              ),
            ),
          ),
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: topInset + 46,
            child: const IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: ShopColors.imageTopScrim,
                  ),
                ),
              ),
            ),
          ),
          if (soldOut)
            ShopSoldOutOverlay(
              label: l10n.tokoOutOfStock,
              scrim: ShopColors.soldOutScrimDetail,
              labelSize: 17,
              radius: 0,
              // 🔴 预计到货时间**必须是区间或不显示**。后台无补货计划字段，
              //    设计稿把 `segera`（很快）这类无信息文案列为禁用 → 传 null。
              subtitle: null,
            ),
          // ⚠️ 减去 [kShopImageButtonInset]：按钮的命中区已撑到 44×44，可见的 30×30
          //    方块居中其中；不减的话可见方块会整体被推进 7px。
          Positioned(
            left: kShopScreenEdge - kShopImageButtonInset,
            top: topInset + 8 - kShopImageButtonInset,
            child: ShopImageButton(
              icon: Icons.arrow_back_ios_new,
              semanticLabel: MaterialLocalizations.of(context).backButtonTooltip,
              onTap: () => context.pop(),
            ),
          ),
          Positioned(
            right: kShopScreenEdge - kShopImageButtonInset,
            top: topInset + 8 - kShopImageButtonInset,
            child: const _CartButton(),
          ),
          if (pages.length > 1)
            Positioned(
              right: kShopScreenEdge,
              bottom: 10,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
                decoration: BoxDecoration(
                  color: ShopColors.countdownScrim,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text('${_galleryIndex + 1}/${pages.length}',
                    style: const TextStyle(
                        fontFamily: ShopText.mono, fontSize: 10, color: ShopColors.surface)),
              ),
            ),
        ],
      ),
      ),
    );
  }

  // ---------------------------------------------------------------- 价格块

  /// 价格块。
  ///
  /// 🔴 设计稿的玫红块是**促销态专属**：主价 + 划线原价 + 折扣角标 + 促销倒计时。
  /// 当前接口无任何促销字段，按设计稿规则「无活动时整块降为白底 + 墨色价格，
  /// **不保留空倒计时行**」实现。售罄时再降一档：价格转灰 + `harga terakhir`。
  Widget _priceBlock(
      AppLocalizations l10n, ShopProductDetail d, ShopSku? sku, bool soldOut) {
    final price = sku?.price ?? d.minPrice;
    return ShopSection(
      padding: const EdgeInsets.fromLTRB(kShopScreenEdge, 12, kShopScreenEdge, 13),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          Text(
            price == null ? l10n.tokoPriceUnavailable : formatIdr(price),
            style: ShopText.priceHero.copyWith(
              // 🔴 三色分工：售罄价转灰（不用玫红做买不到的促销刺激），
              //    在售用玫红（未完成的付款动作）。
              fontSize: soldOut ? 24 : 26,
              color: soldOut ? ShopColors.text4 : ShopColors.accent,
            ),
          ),
          if (soldOut) ...[
            const SizedBox(width: 8),
            Text(l10n.tokoLastPrice, style: ShopText.body.copyWith(color: ShopColors.text4)),
          ],
          // 划线原价与 -20% 角标：无原价字段 → 两者都不显示（成对出现规则）。
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 标题块

  Widget _titleBlock(
      AppLocalizations l10n, ShopProductDetail d, ShopSku? sku, bool soldOut) {
    final reviews = ref.watch(productReviewsProvider(widget.token)).maybeWhen(
          data: (r) => r,
          orElse: () => null,
        );
    final loggedIn = ref.watch(authControllerProvider).isLoggedIn;

    final meta = <String?>[
      // 🔴 无评分时整段不显示，**不显示 0**（`averageRating` 后端就是 null 而非 0）。
      if (reviews?.averageRating != null)
        '★ ${reviews!.averageRating!.toStringAsFixed(1)}',
      if (reviews != null && reviews.total > 0) l10n.tokoReviewsCount(reviews.total),
      // 库存口径（设计稿）：≤20 显具体数，>20 只显「有货」不暴露备货量。
      if (sku != null && !soldOut) _stockLabel(l10n, sku),
      // 产地：接口无该字段 → 少一项，不占位。
    ].whereType<String>().toList();

    return ShopSection(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(d.name, style: ShopText.productNameDetail),
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(meta.join('  ·  '), style: ShopText.meta.copyWith(fontSize: 10.5)),
          ],
          // PawCoin 行。🔴 售罄时不渲染 —— 买不到就不谈怎么付。
          if (!soldOut && loggedIn) ...[
            const SizedBox(height: 10),
            _pawcoinRow(l10n),
          ],
        ],
      ),
    );
  }

  String? _stockLabel(AppLocalizations l10n, ShopSku sku) {
    final n = sku.remaining;
    if (n == null) {
      return sku.stockStatus == StockStatus.lowStock ? l10n.tokoLowStockNoCount : null;
    }
    // 🔴 n 是后端给的真实剩余数，不虚构（FR-95）。
    return n <= 20 ? l10n.tokoLowStock(n) : l10n.tokoStockAvailable;
  }

  /// PawCoin 行。
  ///
  /// 🔴 文案必须是「**部分**抵扣」。写成「可用 PawCoin 支付」会让用户以为能全额付，
  /// 而混合支付的规则是 PawCoin 先扣满、余额走 QRIS —— 真钱那部分永远存在。
  /// 余额不足时该行**仍显示**（实际拆分在结算页做）。
  Widget _pawcoinRow(AppLocalizations l10n) {
    final balance = ref.watch(pawCoinProvider).maybeWhen(
          data: (s) => s.balance,
          orElse: () => null,
        );
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
      decoration: BoxDecoration(
        color: ShopColors.purpleBg,
        borderRadius: BorderRadius.circular(ShopShape.radiusField),
      ),
      child: Row(
        children: [
          const ShopCoinMark(),
          const SizedBox(width: 8),
          Expanded(
            child: Text(l10n.tokoPawcoinPartial,
                style: ShopText.cardTitle.copyWith(
                    fontSize: 11, fontWeight: FontWeight.w600, color: ShopColors.purpleText)),
          ),
          if (balance != null)
            Text(l10n.tokoPawcoinBalance(formatIdr(balance)),
                style: ShopText.badge
                    .copyWith(fontSize: 10.5, color: ShopColors.purple)),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 规格

  /// 规格选择。🔴 **不默认选中**（FR-94A）。
  Widget _variantBlock(AppLocalizations l10n, ShopProductDetail d) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.tokoChooseVariant, style: ShopText.sectionTitle),
            const SizedBox(height: 9),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final s in d.skus)
                  ShopChip(
                    key: ValueKey('skuChip_${s.token}'),
                    label: s.stockStatus == StockStatus.outOfStock
                        ? '${s.specName} · ${l10n.tokoOutOfStock}'
                        : s.specName,
                    selected: _selectedSkuToken == s.token,
                    // 售罄规格**仍可选中** —— 选中后整页切售罄态，用户才看得到
                    // 「这个规格买不到、别的可能可以」。禁用它等于让用户点不动又不知为何。
                    onTap: () => setState(() => _selectedSkuToken = s.token),
                  ),
              ],
            ),
            // 🔴 「先选规格」的提示放在**这里**而不是按钮上。
            //    按钮文案必须稳定：把长句塞进底部条会把按钮挤成两行（真机上实测），
            //    而且用户读到「请选规格」时眼睛在屏幕底部、要选的东西却在中间。
            if (_selectedSkuToken == null) ...[
              const SizedBox(height: 8),
              Text(l10n.tokoChooseVariantFirst,
                  key: const ValueKey('pdpChooseVariantHint'),
                  style: ShopText.meta.copyWith(color: ShopColors.accent)),
            ],
          ],
        ),
      );

  // ---------------------------------------------------------------- 开封不退

  /// 开封不退明示（FR-104 三处的**第 1 处**）。
  ///
  /// 🔴 文案走 l10n key，与结算页、退货申请页**同 key** —— 设计稿的「文案一致性契约」
  /// 要求这三处同文案，改一处必须同步全部。散落字面量必然漂移。
  Widget _returnPolicyBlock(AppLocalizations l10n, ReturnPolicy policy) {
    final (title, body) = switch (policy) {
      ReturnPolicy.returnable => (l10n.tokoReturnableTitle, l10n.tokoReturnableBody),
      ReturnPolicy.noReturnAfterOpen =>
        (l10n.tokoNoReturnAfterOpenTitle, l10n.tokoNoReturnAfterOpenBody),
      ReturnPolicy.noReturn => (l10n.tokoNoReturnTitle, l10n.tokoNoReturnBody),
    };
    return ShopSection(
      child: ShopWarnBlock(title: title, body: body),
    );
  }

  Widget _detailBlock(AppLocalizations l10n, ShopProductDetail d) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.tokoDetailSectionTitle, style: ShopText.sectionTitle),
            const SizedBox(height: 7),
            Text(_stripHtml(d.detailHtml!),
                style: ShopText.body.copyWith(fontSize: 11, height: 1.75)),
          ],
        ),
      );

  // ---------------------------------------------------------------- 底部条

  /// 底部条。
  ///
  /// 在售：购物车入口 + `+ Keranjang`（墨底）+ `Beli Sekarang`（玫红，双行）
  /// 售罄：`Stok Habis` 置灰（**保留主按钮位置**）+ `Lihat Alternatif`（紫）
  ///
  /// 🔴 售罄时主按钮**置灰但不消失** —— 传达「同一个页面、只是买不了」。
  /// 真正的行动出口是右侧的次按钮。
  Widget _bottomBar(AppLocalizations l10n, ShopProductDetail d) {
    final sku = _effectiveSku(d);
    final soldOut = _isSoldOut(d);

    if (soldOut) {
      final category = d.category;
      final disabled = ShopButton(
        key: const ValueKey('pdpSoldOutDisabled'),
        label: l10n.tokoOutOfStock,
        variant: ShopButtonVariant.disabled,
      );
      // 🔴 品类未知 ⇒ 没有替代出口，这时**置灰按钮整宽**（2026-08-27 修）。
      //    此前 primary 传的是 SizedBox.shrink()，但外层仍包着 Expanded(flex: 1) ——
      //    结果是灰按钮只占半宽、右半边悬空一块白，看着像稿子没画完。
      if (category == null) {
        return ShopBottomBarActions(primary: disabled);
      }
      return ShopBottomBarActions(
        secondary: disabled,
        // 替代品区无数据源 → 次出口改跳同品类列表（既有能力，不编推荐）。
        primary: ShopButton(
          key: const ValueKey('pdpSeeAlternatives'),
          label: l10n.tokoSeeAlternatives,
          variant: ShopButtonVariant.purple,
          // ⚠️ `go` 而非 `push`：DEP-1 闭合后 `/shop` 是 shell 分支根，
          //    push 会二次构建 StatefulShellRoute → GlobalKey 撞车 → release 白屏。
          onTap: () => context.go('/shop?category=${category.api}'),
        ),
      );
    }

    // 🔴 多规格未选 → 主按钮禁用并提示先选规格（FR-94A）。
    //
    // 🔴 <b>「请求进行中」不再走 disabled</b>（2026-08-27）：`_adding` 期间置灰，与
    //    「这个规格卖完了」的视觉完全一样。改为保持底色 + 转圈（[ShopButton.loading]），
    //    `canBuy` 因此不再把 `_adding` 算进去 —— 那是两件事。
    final purchasable = sku != null && sku.stockStatus.purchasable;
    final canBuy = purchasable && !_adding;
    return ShopBottomBarActions(
      secondaryFlex: 1,
      primaryFlex: 1,
      secondary: ShopButton(
        key: const ValueKey('pdpAddToCart'),
        // 文案恒定；不可点由 variant 表达（原因写在规格区的提示行里）。
        label: l10n.tokoAddToCartShort,
        variant: purchasable ? ShopButtonVariant.ink : ShopButtonVariant.disabled,
        loading: _adding,
        onTap: canBuy ? () => _onAddTapped(l10n, sku) : null,
      ),
      primary: ShopButton(
        key: const ValueKey('pdpBuyNow'),
        label: l10n.tokoBuyNow,
        // 副文案 = 售价 − 可抵扣 PawCoin，未含运费。当前无「可抵扣额」接口，
        // 🔴 **宁可不显示也不显示一个算错的数** —— 这一行直接影响用户对要付多少钱的预期。
        variant: purchasable ? ShopButtonVariant.pay : ShopButtonVariant.disabled,
        loading: _adding,
        onTap: canBuy ? () => _onBuyNowTapped(l10n, sku) : null,
      ),
    );
  }

  // ---------------------------------------------------------------- 加购

  /// 🔴 游客走**软性**登录引导，登录成功后自动完成本次加购并停留原页 ——
  /// 不跳登录页、不跳走、不丢意图。把用户踢到登录页再让他自己找回来，
  /// 是转化漏斗上最贵的一次流失，而这一下点击恰恰是他表达购买意图的那一刻。
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

  /// `Beli Sekarang`：加购后直接进结算。
  ///
  /// ⚠️ 设计稿要求「跳过详情页与购物车直达结算」。当前**没有单 SKU 直购端点**，
  /// 故实现为「加购成功 → 跳结算页」。失败时停在本页并提示，**不跳转** ——
  /// 否则用户会带着一个空车进结算页。
  void _onBuyNowTapped(AppLocalizations l10n, ShopSku sku) {
    Analytics.capture('toko_buy_now_tapped', {
      'product_token': widget.token,
      'sku_token': sku.token,
    });
    if (ref.read(authControllerProvider).isLoggedIn) {
      _performAdd(l10n, sku, thenCheckout: true);
      return;
    }
    ref.read(loginGuideControllerProvider).showSoftSheet(
          context,
          pendingAction:
              RouteIntent(onResume: () => _performAdd(l10n, sku, thenCheckout: true)),
          entrySource: 'toko_buy_now',
          allowRepeat: true,
        );
  }

  Future<void> _performAdd(AppLocalizations l10n, ShopSku sku,
      {bool thenCheckout = false}) async {
    if (_adding) return;
    setState(() => _adding = true);
    try {
      await ref.read(cartProvider.notifier).add(sku.token, entrySource: widget.entrySource);
      if (!mounted) return;
      if (thenCheckout) {
        context.push('/shop/checkout');
      } else {
        showAppToast(context, l10n.cartAdded);
      }
    } on CartMutationError catch (e) {
      if (mounted) {
        showAppToast(
            context, e == CartMutationError.stock ? l10n.cartStockError : l10n.cartGenericError);
      }
    } finally {
      if (mounted) setState(() => _adding = false);
    }
  }

  /// 后台富文本 → 纯文本。
  ///
  /// 🔴 <b>块级标签要换成换行，不能一并压成空格</b>（2026-08-27 修）。原实现是
  /// `<[^>]*>` → 空格，再把所有 `\s+` 折叠成一个空格 —— 段落、换行、列表全没了，
  /// 一篇有结构的商品说明会变成一整段密不透风的文字。
  ///
  /// ⚠️ 这里刻意**不引富文本渲染器**：详情 HTML 由运营在后台自由编辑，直接渲染等于
  /// 把排版控制权交出去，还多一条 XSS 面。要真正还原排版应当先约定受限的标签白名单。
  static String _stripHtml(String html) => html
      .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
      .replaceAll(RegExp(r'<li[^>]*>', caseSensitive: false), '\n• ')
      .replaceAll(
          RegExp(r'</(p|div|li|h[1-6]|tr|ul|ol)\s*>', caseSensitive: false), '\n')
      .replaceAll(RegExp(r'<[^>]*>'), ' ')
      .replaceAll('&nbsp;', ' ')
      .replaceAll('&amp;', '&')
      .replaceAll('&lt;', '<')
      .replaceAll('&gt;', '>')
      .replaceAll('&quot;', '"')
      .replaceAll(RegExp(r'[ \t]+'), ' ')
      .replaceAll(RegExp(r' *\n *'), '\n')
      .replaceAll(RegExp(r'\n{3,}'), '\n\n')
      .trim();
}

/// 图上的购物车按钮（带件数角标）。
///
/// 🔴 角标与命中区都由 [ShopImageButton] 内建（2026-08-27）。此前这里和 Toko 顶栏
/// 各写了一份角标，**两份都用 `BoxShape.circle`** —— 那个形状只按最短边画圆，
/// `99+` 会溢到圆外面去。
class _CartButton extends ConsumerWidget {
  const _CartButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return ShopImageButton(
      key: const ValueKey('cartBadgeV2'),
      icon: Icons.shopping_cart_outlined,
      semanticLabel: l10n.cartOpen,
      badgeCount: ref.watch(cartItemCountProvider),
      onTap: () => context.push('/shop/cart'),
    );
  }
}
