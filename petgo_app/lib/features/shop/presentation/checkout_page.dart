import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../address/data/address_repository.dart';
import '../address/domain/shipping_address.dart';
import '../data/cart_repository.dart';
import '../data/checkout_repository.dart';
import '../domain/checkout_preview.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart' show ReturnPolicy;

/// 结算页（Story 3.7，FR-97 / FR-99 / FR-100A / FR-104 / S-6 / UX-DR13 / UX-DR14）。
///
/// 自上而下：**收货地址 → 商品清单 → 配送方式 → 支付方式 → 退货规则位 → 金额明细 → 提交**。
///
/// 🔴 **五条硬规则：**
///
/// 1. **金额一个都不自己算**：小计 / 运费 / 免运抵扣 / 应付 / 两段拆分全取服务端试算。
///    前端再算一遍必然与下单时固化的那份漂移，表现为「结算页显示 285.000，提交后变成 305.000」。
/// 2. **两段金额同时展示**（FR-100A 规则 2）：`PawCoin −60.000` 与 `QRIS 310.000` 都要在。
///    只显示一个总数，用户会对扣款构成产生误解 —— 而 PawCoin 段是不能提现的。
/// 3. **PawCoin 段被单笔上限截断时必须多一行**「本单最多可用 …」（C-16 / UX-DR14）。
///    不明示则用户以为系统算错了。
/// 4. **「开封不退」在此处是三处明示的第 2 处**（FR-104；第 1 处商品详情页 Story 1.7，
///    第 3 处退货申请页 Epic 5）。🔴 **措辞复用详情页的同一批 ARB key，逐字一致**。
///    多 SKU 取最严标识（S-6），可展开看逐行。
/// 5. **不含优惠券 / 促销码 / 会员折扣**（FR-97）—— 会员制整体暂缓，不提前埋成本。
///
/// ⚠️ **配送方式区已随 C-14 退化**为单选固定项（UX-DR13 二选一取「保留但固定」）：
/// 整区隐藏会让用户不知道货是怎么送的、几天到；保留一档则回答了这两个问题，
/// 成本只是一行文字。原型画的多档选择器作废。
class CheckoutPage extends ConsumerStatefulWidget {
  const CheckoutPage({super.key});

  @override
  ConsumerState<CheckoutPage> createState() => _CheckoutPageState();
}

class _CheckoutPageState extends ConsumerState<CheckoutPage> {
  /// 当前选中的收货地址；null = 还没选（用默认地址填充，见 [_effectiveAddressToken]）。
  String? _selectedAddressToken;

  bool _submitting = false;

  /// 逐行退货标识是否展开（S-6：默认收起，只显示最严那一条）。
  bool _perLineExpanded = false;

  /// 超范围警示已上报（同一地址反复 rebuild 不重复打点）。
  bool _outOfRangeReported = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_checkout_page_viewed');
  }

  /// 🔴 默认地址优先，其次第一个。**没有地址时返回 null**，页面走引导态并禁用提交。
  String? _effectiveAddressToken(List<ShippingAddress> list) {
    if (_selectedAddressToken != null &&
        list.any((a) => a.token == _selectedAddressToken)) {
      return _selectedAddressToken;
    }
    if (list.isEmpty) return null;
    for (final a in list) {
      if (a.isDefault) return a.token;
    }
    return list.first.token;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final addresses = ref.watch(addressListProvider);

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.checkoutTitle), backgroundColor: AppColors.cream),
      body: addresses.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _centered(l10n.checkoutLoadFailed),
        data: (list) {
          final token = _effectiveAddressToken(list);
          if (token == null) return _noAddressBody(l10n);
          return _previewBody(l10n, list, token);
        },
      ),
      bottomNavigationBar: addresses.maybeWhen(
        data: (list) {
          final token = _effectiveAddressToken(list);
          if (token == null) return _bottomBar(l10n, null);
          return ref.watch(checkoutPreviewProvider(token)).maybeWhen(
                data: (p) => _bottomBar(l10n, p),
                orElse: () => null,
              );
        },
        orElse: () => null,
      ),
    );
  }

  // ---------- 地址缺失 ----------

  /// 🔴 无地址：地址区变成「+ 新增收货地址」引导态，**提交禁用**（AC）。
  Widget _noAddressBody(AppLocalizations l10n) => ListView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        children: [
          _sectionTitle(l10n.checkoutAddressSection),
          Card(
            margin: EdgeInsets.zero,
            child: ListTile(
              key: const ValueKey('checkoutAddAddress'),
              leading: const Icon(Icons.add_location_alt_outlined),
              title: Text(l10n.checkoutAddAddress),
              onTap: () => context.push('/me/addresses/new'),
            ),
          ),
        ],
      );

  // ---------- 主体 ----------

  Widget _previewBody(AppLocalizations l10n, List<ShippingAddress> list, String token) {
    final async = ref.watch(checkoutPreviewProvider(token));
    return async.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (_, _) => _centered(l10n.checkoutLoadFailed),
      data: (p) => ListView(
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
        children: [
          _sectionTitle(l10n.checkoutAddressSection),
          _addressCard(l10n, list, p),
          if (!p.serviceable) _outOfRangeBanner(l10n),

          _sectionTitle(l10n.checkoutItemsSection),
          for (final line in p.lines) _lineTile(line),
          if (p.unavailableLines.isNotEmpty) ...[
            _sectionTitle(l10n.checkoutUnavailableSection(p.unavailableLines.length)),
            for (final line in p.unavailableLines) _lineTile(line, dimmed: true),
          ],

          _sectionTitle(l10n.checkoutShippingSection),
          _shippingMethod(l10n),

          _sectionTitle(l10n.checkoutPaymentSection),
          _paymentBlock(l10n, p),

          // 🔴 FR-104 第 2 处明示 —— S-6 定的位置：**金额明细上方**
          _sectionTitle(l10n.checkoutReturnSection),
          _returnPolicyBlock(l10n, p),

          const SizedBox(height: AppSpacing.sm),
          _amountBlock(l10n, p),
          const SizedBox(height: AppSpacing.lg),
        ],
      ),
    );
  }

  Widget _addressCard(AppLocalizations l10n, List<ShippingAddress> list, CheckoutPreview p) =>
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            key: const ValueKey('checkoutAddress'),
            title: Text('${p.receiverName} · ${p.receiverPhone}',
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(p.addressText),
            trailing: TextButton(
              onPressed: () => _pickAddress(l10n, list),
              child: Text(l10n.checkoutChangeAddress),
            ),
          ),
        ),
      );

  /// 🔴 超服务范围：**警示 + 禁用提交**，但页面照常渲染 —— 用户得看见是哪个地址送不到（FR-99）。
  ///
  /// 埋点（Story 3.10）：这是转化漏斗上一个**可运营**的流失点 ——
  /// 若某个区域反复出现，那是「该开通配送了」的信号，不是用户的问题。
  Widget _outOfRangeBanner(AppLocalizations l10n) {
    if (!_outOfRangeReported) {
      _outOfRangeReported = true;
      Analytics.capture('toko_checkout_out_of_range_shown');
    }
    return _outOfRangeBannerBody(l10n);
  }

  Widget _outOfRangeBannerBody(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, 0),
        child: Container(
          key: const ValueKey('checkoutOutOfRange'),
          width: double.infinity,
          padding: const EdgeInsets.all(AppSpacing.md),
          decoration: BoxDecoration(
            color: AppColors.coralTint,
            borderRadius: BorderRadius.circular(AppSpacing.sm),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(l10n.checkoutOutOfRange,
                  style: const TextStyle(
                      fontWeight: FontWeight.w700, color: AppColors.healthEventText)),
              const SizedBox(height: AppSpacing.xxs),
              Text(l10n.checkoutOutOfRangeBody, style: const TextStyle(fontSize: 13)),
            ],
          ),
        ),
      );

  Widget _lineTile(CheckoutLine line, {bool dimmed = false}) => Opacity(
        opacity: dimmed ? 0.55 : 1,
        child: Padding(
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.lg, vertical: AppSpacing.xs),
          child: Row(
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(AppSpacing.sm),
                child: SizedBox(
                  width: 48,
                  height: 48,
                  child: line.mainImageUrl == null
                      ? const ColoredBox(color: AppColors.mintTint2)
                      : Image.network(line.mainImageUrl!,
                          fit: BoxFit.cover,
                          errorBuilder: (_, _, _) =>
                              const ColoredBox(color: AppColors.mintTint2)),
                ),
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(line.productName ?? line.specName,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                    Text('${line.specName} × ${line.qty}',
                        style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                  ],
                ),
              ),
              Text(formatIdr(line.lineTotal),
                  style: const TextStyle(fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      );

  /// ⚠️ C-14：只剩一档，**固定项而非选择器**（UX-DR13）。
  Widget _shippingMethod(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Card(
          margin: EdgeInsets.zero,
          child: ListTile(
            key: const ValueKey('checkoutShippingMethod'),
            leading: const Icon(Icons.local_shipping_outlined),
            title: Text(l10n.checkoutShippingReguler),
            // 🔴 没有第二档，就不给选择控件 —— 留一个恒选中的单选框只会让用户去找别的选项
            trailing: const Icon(Icons.check, color: AppColors.mint),
          ),
        ),
      );

  // ---------- 支付方式 + 两段金额 ----------

  Widget _paymentBlock(AppLocalizations l10n, CheckoutPreview p) {
    final coin = p.coinAmount ?? 0;
    final cash = p.cashAmount ?? 0;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 🔴 FR-100A 规则 2：两段都要在，不能只显示一个总数
              if (coin > 0)
                _amountRow(l10n.checkoutPawcoin, '− ${formatIdr(coin)}',
                    key: const ValueKey('checkoutCoinSegment'), strong: true),
              if (coin > 0 && p.coinCapped) ...[
                const SizedBox(height: AppSpacing.xxs),
                // 🔴 C-16 / UX-DR14：被单笔上限截断必须明示，否则用户以为系统算错
                Text(l10n.checkoutCoinCapped(formatIdr(p.maxCoinPerOrder)),
                    key: const ValueKey('checkoutCoinCapped'),
                    style: const TextStyle(fontSize: 12, color: AppColors.mint600)),
              ],
              if (coin > 0) ...[
                const SizedBox(height: AppSpacing.xxs),
                // 🔴 在付款前就说清，不等退款时才告知
                Text(l10n.checkoutPawcoinNote,
                    key: const ValueKey('checkoutPawcoinNote'),
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                const Divider(height: AppSpacing.lg),
              ],
              if (cash > 0 || coin == 0) ...[
                _amountRow(l10n.checkoutQris, formatIdr(cash),
                    key: const ValueKey('checkoutCashSegment'), strong: true),
                const SizedBox(height: AppSpacing.xxs),
                // 🔴 FR-100：仅 QRIS。副标题防止用户误以为不能用 GoPay
                Text(l10n.checkoutQrisNote,
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ],
              const SizedBox(height: AppSpacing.xs),
              Text(l10n.checkoutCoinBalance(formatIdr(p.coinBalance)),
                  style: const TextStyle(fontSize: 12, color: AppColors.muted)),
            ],
          ),
        ),
      ),
    );
  }

  // ---------- 退货规则位（FR-104 第 2 处明示 / S-6）----------

  Widget _returnPolicyBlock(AppLocalizations l10n, CheckoutPreview p) {
    final (title, body) = _policyText(l10n, p.strictestReturnPolicy);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(AppSpacing.sm),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 🔴 措辞与商品详情页**逐字一致**：同一批 ARB key，不另写一套
            Text(title,
                key: const ValueKey('checkoutStrictestPolicy'),
                style: const TextStyle(fontWeight: FontWeight.w700)),
            const SizedBox(height: AppSpacing.xxs),
            Text(body, style: const TextStyle(fontSize: 13)),
            if (p.lines.length > 1) ...[
              const SizedBox(height: AppSpacing.xs),
              GestureDetector(
                key: const ValueKey('checkoutPerLineToggle'),
                onTap: () => setState(() => _perLineExpanded = !_perLineExpanded),
                child: Text(l10n.checkoutReturnPerLine,
                    style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: AppColors.mint600)),
              ),
              if (_perLineExpanded)
                for (final line in p.lines)
                  Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.xxs),
                    child: Text(
                      '${line.productName ?? line.specName} · '
                      '${_policyText(l10n, line.returnPolicy).$1}',
                      style: const TextStyle(fontSize: 12, color: AppColors.ink2),
                    ),
                  ),
            ],
          ],
        ),
      ),
    );
  }

  (String, String) _policyText(AppLocalizations l10n, ReturnPolicy policy) => switch (policy) {
        ReturnPolicy.returnable => (l10n.tokoReturnableTitle, l10n.tokoReturnableBody),
        ReturnPolicy.noReturnAfterOpen =>
          (l10n.tokoNoReturnAfterOpenTitle, l10n.tokoNoReturnAfterOpenBody),
        ReturnPolicy.noReturn => (l10n.tokoNoReturnTitle, l10n.tokoNoReturnBody),
      };

  // ---------- 金额明细四行 ----------

  Widget _amountBlock(AppLocalizations l10n, CheckoutPreview p) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Column(
          children: [
            _amountRow(l10n.checkoutSubtotal, formatIdr(p.goodsSubtotal)),
            // 算不出运费（超范围）时不显示这两行 —— 绝不填 0，0 会被读成「免运费」
            if (p.shippingFee != null)
              _amountRow(l10n.checkoutShippingFee, formatIdr(p.shippingFee!)),
            // 🔴 免运抵扣是一条**负数行**，不是把运费改成 0（FR-99）：
            //    用户要看见自己省了多少，对账侧也要收入与优惠分开记
            if ((p.shippingDiscount ?? 0) != 0)
              _amountRow(l10n.checkoutFreeShipping, formatIdr(p.shippingDiscount!),
                  key: const ValueKey('checkoutFreeShipping'), highlight: true),
            const Divider(),
            if (p.payableTotal != null)
              _amountRow(l10n.checkoutPayable, formatIdr(p.payableTotal!),
                  key: const ValueKey('checkoutPayable'), strong: true),
          ],
        ),
      );

  Widget _amountRow(String label, String value,
          {Key? key, bool strong = false, bool highlight = false}) =>
      Padding(
        key: key,
        padding: const EdgeInsets.symmetric(vertical: AppSpacing.xxs),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label,
                style: TextStyle(
                    fontSize: strong ? 15 : 13,
                    fontWeight: strong ? FontWeight.w700 : FontWeight.w400)),
            Text(value,
                style: TextStyle(
                    fontSize: strong ? 16 : 13,
                    fontWeight: strong ? FontWeight.w700 : FontWeight.w500,
                    color: highlight || strong ? AppColors.mint : AppColors.ink)),
          ],
        ),
      );

  // ---------- 底栏 ----------

  Widget _bottomBar(AppLocalizations l10n, CheckoutPreview? p) {
    final canSubmit = p != null && p.canSubmit && !_submitting;
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.lg),
        decoration: const BoxDecoration(
          color: AppColors.cream,
          border: Border(top: BorderSide(color: AppColors.line)),
        ),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // 🔴 混合支付时底栏也要写清两段构成（FR-100A 规则 2）
                  if (p != null && p.isMixed)
                    Text(
                      l10n.checkoutMixedBottom(
                          formatIdr(p.coinAmount!), formatIdr(p.cashAmount!)),
                      key: const ValueKey('checkoutBottomMixed'),
                      style: const TextStyle(fontSize: 11, color: AppColors.muted),
                    ),
                  Text(
                    p?.payableTotal == null ? '—' : formatIdr(p!.payableTotal!),
                    style: const TextStyle(
                        fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.mint),
                  ),
                ],
              ),
            ),
            SizedBox(
              height: 44,
              child: FilledButton(
                key: const ValueKey('checkoutSubmit'),
                onPressed: canSubmit ? () => _submit(l10n, p) : null,
                child: Text(l10n.checkoutSubmit),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---------- 动作 ----------

  Future<void> _pickAddress(AppLocalizations l10n, List<ShippingAddress> list) async {
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (sheetCtx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Text(l10n.checkoutChooseAddress,
                  style: const TextStyle(fontWeight: FontWeight.w700)),
            ),
            for (final a in list)
              ListTile(
                key: ValueKey('checkoutAddressOption_${a.token}'),
                title: Text('${a.receiverName} · ${a.receiverPhone}'),
                subtitle: Text('${a.addressLine}, ${a.kecamatan}'),
                onTap: () => Navigator.of(sheetCtx).pop(a.token),
              ),
            ListTile(
              leading: const Icon(Icons.add),
              title: Text(l10n.checkoutAddAddress),
              onTap: () {
                Navigator.of(sheetCtx).pop();
                context.push('/me/addresses/new');
              },
            ),
          ],
        ),
      ),
    );
    if (picked != null && mounted) {
      setState(() => _selectedAddressToken = picked);
    }
  }

  Future<void> _submit(AppLocalizations l10n, CheckoutPreview p) async {
    Analytics.capture('toko_checkout_submit_tapped');
    setState(() => _submitting = true);
    try {
      final order = await ref.read(checkoutRepositoryProvider).placeOrder(p.addressToken);
      // 下单成功后车里已被清掉已下单的行 —— 角标必须跟上
      await ref.read(cartProvider.notifier).refresh();
      if (!mounted) return;
      // 🔴 下单成功（漏斗终点）。**权威归因在服务端**：加购时记在购物车行上、
      //    下单时抄到订单行（Story 3.10 / V114）—— 客户端事件会被广告拦截与丢包吃掉，
      //    而 AB-13B 要用它判定 A-16。
      // 🔴 Story 9.2：客户端仍带一份**行级**归因，不是为了替代服务端，而是为了
      //    【互为校验】—— 两套数据一比就知道端上丢了多少。偏差过大即说明客户端埋点
      //    有丢失，**以服务端为准**。原清单只到 add_to_cart 为止（能算点击率、
      //    算不出转化率），这一份才把归因链闭上。
      // 🔒 items 里只有受控标识与数量：sku_id 是不可枚举 token，无价格、无名称、无 PII。
      Analytics.capture('toko_order_submitted', {
        'item_count': p.lines.length,
        'items': [
          for (final l in p.lines)
            {
              'sku_id': l.skuToken,
              'qty': l.qty,
              'entry_source': l.entrySource ?? 'unknown',
              'trigger_type': l.triggerType ?? 'none',
            },
        ],
      });
      showAppToast(context, l10n.checkoutOrderPlaced);
      // 🔴 直接进待支付订单详情（Story 3.8）：60 分钟窗口从下单那一刻就在走，
      //    把用户留在结算页等他自己去找订单，等于让他在倒计时里找路。
      //    用 pushReplacement：结算页此时已无意义（车已清空），回退到它只会看到空态。
      context.pushReplacement('/shop/orders/${order.orderToken}');
    } on CheckoutFailure catch (e) {
      if (!mounted) return;
      switch (e.kind) {
        case CheckoutFailureKind.unavailableLines:
          await _showUnavailable(l10n, p, e.unavailableLines);
        case CheckoutFailureKind.notPlaceable:
          showAppToast(context, l10n.checkoutNotPlaceable);
        case CheckoutFailureKind.generic:
          showAppToast(context, l10n.checkoutGenericError);
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// 🔴 FR-95 第二次库存校验失败：**指出是哪一件、还剩几件，并允许移除后继续** ——
  /// 不整单打回。整单打回会让用户在结算这一步流失，而问题往往只出在其中一件。
  Future<void> _showUnavailable(
      AppLocalizations l10n, CheckoutPreview p, List<UnavailableLine> lines) async {
    final removed = await showDialog<bool>(
      context: context,
      builder: (dlgCtx) => AlertDialog(
        key: const ValueKey('checkoutUnavailableDialog'),
        title: Text(l10n.checkoutUnavailableTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final l in lines)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.xs),
                child: Text(
                  '${l.productName ?? l.skuToken} · '
                  '${l.isDelisted ? l10n.checkoutUnavailableDelisted : l10n.checkoutUnavailableStock(l.available, l.requested)}',
                  style: const TextStyle(fontSize: 13),
                ),
              ),
          ],
        ),
        actions: [
          FilledButton(
            key: const ValueKey('checkoutRemoveUnavailable'),
            onPressed: () => Navigator.of(dlgCtx).pop(true),
            child: Text(l10n.checkoutUnavailableRemove),
          ),
        ],
      ),
    );
    if (removed != true || !mounted) return;
    // 只移除被挡住的那几行，其余原样留在车里
    for (final l in lines) {
      try {
        await ref.read(cartProvider.notifier).remove(l.skuToken);
      } on CartMutationError {
        // 单行移除失败不该阻断其余行；试算刷新后用户会看到真实状态
      }
    }
    if (mounted) ref.invalidate(checkoutPreviewProvider(p.addressToken));
  }

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.sm),
        child: Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
      );

  Widget _centered(String text) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Text(text, textAlign: TextAlign.center),
        ),
      );
}
