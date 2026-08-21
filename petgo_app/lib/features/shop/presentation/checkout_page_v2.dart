/// 结算页 —— **设计稿版式**（V1.4.0 · `01_screens_browse_order.md` 屏 6 正常 / 屏 7 超服务范围）。
///
/// 与 [CheckoutPage]（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
///
/// ## 🔴 五条硬规则，与 v1 逐字相同（版式换了，规则一条不改）
///
/// 1. **金额一个都不自己算**：小计 / 运费 / 免运抵扣 / 应付 / 两段拆分全取服务端试算。
///    前端再算一遍必然与下单时固化的那份漂移，表现为「结算页显示 285.000，提交后变成 305.000」。
/// 2. **两段金额同时展示**（FR-100A）：`PawCoin −50.000` 与 `QRIS 154.000` 都要在。
///    只显示一个总数，用户会对扣款构成产生误解 —— 而 PawCoin 段**不能提现**。
/// 3. **PawCoin 段被单笔上限截断时必须多一行**「本单最多可用 …」（C-16 / UX-DR14）。
/// 4. **「开封不退」是三处明示的第 2 处**（FR-104）—— 措辞复用详情页的同一批 ARB key，
///    逐字一致。这是设计稿「文案一致性契约」的硬要求。
/// 5. **不含优惠券 / 促销码 / 会员折扣**（FR-97）。
///
/// ## 🔴 超范围态（FR-99）：运费位显示「暂不可用」，**绝不显示 Rp 0**
///
/// 0 会被读成「免运费」—— 一个买不了的订单显示免运费，是最糟的一种误导。
/// 服务端在超范围时把金额位全给 null，本页据此显示文案而不是数字。
///
/// ## 已开通城市清单取**真实数据**，不写死
///
/// 设计稿把服务范围写死为 `Jabodetabek / Bandung / Surabaya`，而它自己的 README
/// 把这条列为待产品确认项。把城市名写进 app 意味着运营开一个新城、app 就撒谎到下次发版。
/// 本页改从 `GET /shop/regions`（既有接口，即配送区域树）取 kota 列表 ——
/// 这样「拦截文案必须列出已开通城市」这条设计规则用真数据满足，且永远不会过期。
///
/// ## 设计稿要求但当前无数据源
///
/// - **`Beri tahu jika sudah bisa`（开通提醒订阅）**：无城市级订阅端点 → 该次出口不渲染。
///   设计稿给的两个出口里，主出口「换地址」是有的，这一个没有。
/// - **配送时效**：`checkoutShippingReguler` 是固定文案（C-14 已把配送方式退化为单档）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../address/data/address_repository.dart';
import '../address/domain/shipping_address.dart';
import '../data/cart_repository.dart';
import '../data/checkout_repository.dart';
import '../domain/checkout_preview.dart';
import '../domain/shop_product.dart';
import '../domain/shop_product_detail.dart' show ReturnPolicy;
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

class CheckoutPageV2 extends ConsumerStatefulWidget {
  const CheckoutPageV2({super.key});

  @override
  ConsumerState<CheckoutPageV2> createState() => _CheckoutPageV2State();
}

class _CheckoutPageV2State extends ConsumerState<CheckoutPageV2> {
  String? _selectedAddressToken;
  bool _submitting = false;

  /// 开封不退协议勾选。
  ///
  /// 🔴 **默认已勾选，照设计稿实现**。设计稿自己标了「如需强制用户主动勾选请与产品确认」——
  /// 这条我按稿子做而不是自行改成默认未勾选：把它改成必须主动勾会新增一道转化摩擦，
  /// 那是产品决策不是实现细节。⚠️ 但请注意其后果：**默认勾选的同意框实际上不构成门禁**，
  /// 它只是一次告知 + 一个可交互的确认位。若法务要求「主动同意」，改这里的初值即可。
  bool _agreedNoReturn = true;

  bool _outOfRangeReported = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_checkout_page_viewed');
  }

  /// 🔴 默认地址优先，其次第一个。没有地址时返回 null → 走引导态并禁用提交。
  String? _effectiveAddressToken(List<ShippingAddress> list) {
    if (_selectedAddressToken != null && list.any((a) => a.token == _selectedAddressToken)) {
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
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.checkoutTitle),
      body: addresses.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _hint(l10n.checkoutLoadFailed),
        data: (list) {
          final token = _effectiveAddressToken(list);
          if (token == null) return _noAddressState(l10n);
          return ref.watch(checkoutPreviewProvider(token)).when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (_, _) => _hint(l10n.checkoutLoadFailed),
                data: (p) => _content(l10n, p),
              );
        },
      ),
      bottomNavigationBar: addresses.maybeWhen(
        data: (list) {
          final token = _effectiveAddressToken(list);
          if (token == null) return null;
          return ref.watch(checkoutPreviewProvider(token)).maybeWhen(
                data: (p) => _bottomBar(l10n, p),
                orElse: () => null,
              );
        },
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, CheckoutPreview p) {
    if (!p.serviceable && !_outOfRangeReported) {
      _outOfRangeReported = true;
      Analytics.capture('toko_checkout_out_of_range_shown');
    }
    return ListView(
      padding: EdgeInsets.zero,
      children: [
        _addressBlock(l10n, p),
        // 🔴 超范围时下游区块整体降权到 .55（设计稿）—— 它们仍然可读（用户要能核对买了什么），
        //    但视觉上明确「这些还不作数」。
        Opacity(
          opacity: p.serviceable ? 1 : .55,
          child: Column(
            children: [
              _itemsBlock(l10n, p),
              _paymentBlock(l10n, p),
              _amountBlock(l10n, p),
            ],
          ),
        ),
        _agreementBlock(l10n, p),
        if (!p.serviceable) _serviceAreaBlock(l10n),
        const SizedBox(height: kShopGutter),
      ],
    );
  }

  // ---------------------------------------------------------------- 地址

  Widget _addressBlock(AppLocalizations l10n, CheckoutPreview p) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${p.receiverName} · ${p.receiverPhone}',
                          style: ShopText.cardTitle.copyWith(fontSize: 12)),
                      const SizedBox(height: 3),
                      Text(p.addressText, style: ShopText.body),
                    ],
                  ),
                ),
                const SizedBox(width: 10),
                InkWell(
                  key: const ValueKey('checkoutChangeAddressV2'),
                  onTap: () => context.push('/me/addresses'),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                    child: Text('${l10n.checkoutChangeAddress} ›',
                        style: ShopText.badge
                            .copyWith(fontSize: 11, color: ShopColors.purple)),
                  ),
                ),
              ],
            ),
            // 🔴 超范围警示**长在地址块内**，不是页面顶部飘条 —— 问题出在这个地址上，
            //    提示就该贴着这个地址。
            if (!p.serviceable) ...[
              const SizedBox(height: 11),
              _outOfRangeNotice(l10n),
              const SizedBox(height: 10),
              // 设计稿给两个出口；「开通提醒」无订阅端点，故只留主出口。
              SizedBox(
                width: double.infinity,
                child: ShopButton(
                  key: const ValueKey('checkoutPickAnotherAddress'),
                  label: l10n.checkoutPickAnotherAddress,
                  variant: ShopButtonVariant.purple,
                  onTap: () => context.push('/me/addresses'),
                ),
              ),
            ],
          ],
        ),
      );

  Widget _outOfRangeNotice(AppLocalizations l10n) => ShopLeftAccentBlock.money(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 14,
              height: 14,
              margin: const EdgeInsets.only(top: 1),
              alignment: Alignment.center,
              decoration: const BoxDecoration(color: ShopColors.rose, shape: BoxShape.circle),
              child: const Text('!',
                  style: TextStyle(
                      fontSize: 9, fontWeight: FontWeight.w800, color: ShopColors.surface)),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l10n.checkoutOutOfRange,
                      key: const ValueKey('checkoutOutOfRangeTitle'),
                      style: ShopText.cardTitle
                          .copyWith(fontSize: 11, color: ShopColors.roseDark)),
                  const SizedBox(height: 2),
                  Text(l10n.checkoutOutOfRangeBody,
                      style: ShopText.body.copyWith(color: const Color(0xFF8A5560))),
                ],
              ),
            ),
          ],
        ),
      );

  /// 已开通城市块。
  ///
  /// 🔴 设计稿规则：「拦截文案必须给出范围，不写 `di luar jangkauan` 了事 ——
  /// 用户要能自己判断换哪个地址」。城市取自配送区域接口（真数据），不写死。
  Widget _serviceAreaBlock(AppLocalizations l10n) {
    final regions = ref.watch(regionTreeProvider).maybeWhen(
          data: (t) => t,
          orElse: () => null,
        );
    // 区域还没加载出来时整块不渲染 —— 一个写着「我们送这些地方」却列不出地方的块，
    // 比没有这块更让人困惑（设计稿的空态原则）。
    if (regions == null) return const SizedBox.shrink();
    final kota = <String>{
      for (final prov in regions.provinsi)
        for (final k in prov.kota) k.name,
    }.toList()
      ..sort();
    if (kota.isEmpty) return const SizedBox.shrink();

    const maxShown = 8;
    final shown = kota.take(maxShown).toList();
    final rest = kota.length - shown.length;

    return ShopSection(
      key: const ValueKey('checkoutServiceArea'),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutServiceAreaTitle, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
          const SizedBox(height: 9),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final k in shown) ShopChip(label: k, selected: false),
              if (rest > 0) ShopChip(label: l10n.checkoutMoreAreas(rest), selected: false),
            ],
          ),
          const SizedBox(height: 8),
          Text(l10n.checkoutServiceAreaBody, style: ShopText.body),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 商品与配送

  Widget _itemsBlock(AppLocalizations l10n, CheckoutPreview p) => ShopSection(
        child: Column(
          children: [
            for (final line in p.lines) ...[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ShopImage(
                      url: line.mainImageUrl, size: 50, radius: ShopShape.radiusField),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(line.productName ?? line.specName,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: ShopText.productNameCard),
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            Expanded(
                              child: Text(formatIdr(line.price),
                                  style: ShopText.priceInline
                                      .copyWith(color: ShopColors.rose)),
                            ),
                            Text('×${line.qty}', style: ShopText.meta),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              const ShopDivider(margin: EdgeInsets.symmetric(vertical: 10)),
            ],
            Row(
              children: [
                Expanded(
                  child: Text(l10n.checkoutShippingReguler, style: ShopText.body),
                ),
                Text(
                  // 🔴 超范围时显示文案而非数字。**绝不显示 Rp 0** —— 0 读作「免运费」。
                  p.shippingFee == null
                      ? l10n.checkoutShippingUnavailable
                      : formatIdr(p.shippingFee!),
                  key: const ValueKey('checkoutShippingFeeV2'),
                  style: ShopText.badge.copyWith(
                      fontSize: 11,
                      color: p.shippingFee == null ? ShopColors.text4 : ShopColors.text),
                ),
              ],
            ),
          ],
        ),
      );

  // ---------------------------------------------------------------- 支付方式

  /// 支付方式块。
  ///
  /// 🔴 两条都是**既定结果、不可点选**（[ShopLeftAccentBlock] 的说明）：
  /// PawCoin 先扣满、余额走 QRIS，用户无从调节。给出单选控件会让人预期能选真钱。
  Widget _paymentBlock(AppLocalizations l10n, CheckoutPreview p) {
    final coin = p.coinAmount ?? 0;
    final cash = p.cashAmount ?? 0;
    final muted = !p.serviceable;
    return ShopSection(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.checkoutPaymentSection,
              style: ShopText.sectionTitle.copyWith(fontSize: 12)),
          const SizedBox(height: 9),
          // 🔴 两段金额同时展示（FR-100A）。coin 为 0 时该条不渲染 —— 一条写着
          //    「−Rp 0」的抵扣行是噪音，不是信息。
          if (coin > 0) ...[
            _payRow(
              muted: muted,
              purple: true,
              title: l10n.checkoutPawcoinFull,
              subtitle: l10n.checkoutCoinBalance(formatIdr(p.coinBalance)),
              amount: '− ${formatIdr(coin)}',
              amountColor: ShopColors.purple,
            ),
            // 🔴 被单笔上限截断时必须多这一行，否则用户以为系统算错了（C-16 / UX-DR14）。
            if (p.coinCapped) ...[
              const SizedBox(height: 5),
              Text(l10n.checkoutCoinCapped(formatIdr(p.maxCoinPerOrder)),
                  key: const ValueKey('checkoutCoinCappedV2'),
                  style: ShopText.meta.copyWith(color: ShopColors.purple)),
            ],
            const SizedBox(height: 7),
          ],
          _payRow(
            muted: muted,
            purple: false,
            title: l10n.checkoutQris,
            subtitle: l10n.checkoutQrisRemainder,
            amount: p.serviceable ? formatIdr(cash) : '—',
            amountColor: ShopColors.ink,
          ),
          // 🔴 防套现提示必须在**支付前**可见、不可折叠（合规位点）。
          //    与退款方式页同一个 ARB key —— 文案一致性契约。
          //
          // ⚠️ 但**本单没用到 PawCoin 时不渲染**（coin == 0）：一段警告用户
          //    「PawCoin 部分只退 PawCoin」的文字，出现在一个一分 PawCoin 都没用的订单上，
          //    是在提醒一件没发生的事 —— 与 coin 行本身适用同一条空态规则。
          //    合规位点的意义是「用到它时必须提前说清」，不是「无论如何都要出现」。
          if (coin > 0) ...[
            const SizedBox(height: 8),
            ShopWarnBlock(
              key: const ValueKey('checkoutPawcoinNoteV2'),
              title: l10n.checkoutPawcoin,
              body: l10n.checkoutPawcoinNote,
            ),
          ],
        ],
      ),
    );
  }

  Widget _payRow({
    required bool muted,
    required bool purple,
    required String title,
    required String subtitle,
    required String amount,
    required Color amountColor,
  }) {
    final child = Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: ShopText.cardTitle.copyWith(fontSize: 11.5)),
              Text(subtitle, style: ShopText.meta),
            ],
          ),
        ),
        Text(amount,
            style: ShopText.priceInline
                .copyWith(color: muted ? ShopColors.text4 : amountColor)),
      ],
    );
    if (muted) return ShopLeftAccentBlock.muted(child: child);
    return purple
        ? ShopLeftAccentBlock.pawcoin(child: child)
        : ShopLeftAccentBlock.money(child: child);
  }

  // ---------------------------------------------------------------- 金额明细

  Widget _amountBlock(AppLocalizations l10n, CheckoutPreview p) => ShopSection(
        child: Column(
          children: [
            _amountRow(l10n.checkoutSubtotal, formatIdr(p.goodsSubtotal)),
            const SizedBox(height: 5),
            _amountRow(
              l10n.checkoutShippingFee,
              p.shippingFee == null
                  ? l10n.checkoutShippingUnavailable
                  : formatIdr(p.shippingFee!),
            ),
            if ((p.shippingDiscount ?? 0) != 0) ...[
              const SizedBox(height: 5),
              // 🔴 免运是**一条负数行**，不是把运费改成 0（FR-99）——
              //    用户要看得见「本来多少、减了多少」。
              _amountRow(l10n.checkoutFreeShipping, formatIdr(p.shippingDiscount!),
                  color: ShopColors.purple),
            ],
            if ((p.coinAmount ?? 0) > 0) ...[
              const SizedBox(height: 5),
              _amountRow(l10n.checkoutPawcoin, '− ${formatIdr(p.coinAmount!)}',
                  color: ShopColors.purple),
            ],
          ],
        ),
      );

  Widget _amountRow(String label, String value, {Color? color}) => Row(
        children: [
          Expanded(child: Text(label, style: ShopText.body.copyWith(fontSize: 11))),
          Text(value,
              style: ShopText.body.copyWith(
                  fontSize: 11,
                  fontWeight: color == null ? FontWeight.w400 : FontWeight.w600,
                  color: color ?? ShopColors.text2)),
        ],
      );

  // ---------------------------------------------------------------- 开封不退

  /// 开封不退勾选（FR-104 三处明示的**第 2 处**）。
  ///
  /// 🔴 文案与商品详情页、退货申请页**同一批 ARB key**，逐字一致 ——
  /// 设计稿的「文案一致性契约」要求改一处必须同步全部。
  /// 多 SKU 取**最严**规则（S-6）：不可退 > 开封不退 > 可退。
  Widget _agreementBlock(AppLocalizations l10n, CheckoutPreview p) {
    final body = switch (p.strictestReturnPolicy) {
      ReturnPolicy.returnable => l10n.tokoReturnableBody,
      ReturnPolicy.noReturnAfterOpen => l10n.tokoNoReturnAfterOpenBody,
      ReturnPolicy.noReturn => l10n.tokoNoReturnBody,
    };
    return ShopSection(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ShopCheckbox(
            key: const ValueKey('checkoutAgreeNoReturn'),
            value: _agreedNoReturn,
            size: 15,
            onChanged: (v) => setState(() => _agreedNoReturn = v),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(top: 13),
              child: Text(body, style: ShopText.body.copyWith(fontSize: 10)),
            ),
          ),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 底部条

  Widget _bottomBar(AppLocalizations l10n, CheckoutPreview p) {
    final canSubmit = p.canSubmit && _agreedNoReturn && !_submitting;
    return ShopBottomBarWithTotal(
      label: l10n.checkoutPayable,
      // 🔴 超范围时总价位显示文案而非数字，且转灰。
      amount: p.payableTotal == null
          ? l10n.checkoutShippingUnavailable
          : formatIdr(p.payableTotal!),
      amountColor: p.serviceable ? ShopColors.rose : ShopColors.text4,
      action: ShopButton(
        key: const ValueKey('checkoutSubmitV2'),
        label: l10n.checkoutSubmit,
        variant: canSubmit ? ShopButtonVariant.rose : ShopButtonVariant.disabled,
        padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
        onTap: canSubmit ? () => _submit(l10n, p) : null,
      ),
    );
  }

  // ---------------------------------------------------------------- 提交

  Future<void> _submit(AppLocalizations l10n, CheckoutPreview p) async {
    Analytics.capture('toko_checkout_submit_tapped');
    setState(() => _submitting = true);
    try {
      final order = await ref.read(checkoutRepositoryProvider).placeOrder(p.addressToken);
      await ref.read(cartProvider.notifier).refresh();
      if (!mounted) return;
      // 🔒 items 里只有受控标识与数量：sku_id 是不可枚举 token，无价格、无名称、无 PII。
      //    客户端这一份与服务端的行级归因**互为校验**，偏差过大即说明端上埋点有丢失。
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
      // 🔴 直接进待支付订单详情：60 分钟窗口从下单那一刻就在走，
      //    把用户留在结算页等他自己去找订单，等于让他在倒计时里找路。
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

  /// 🔴 **停在本页**，不跳转、不清空地址与勾选（设计稿：第二次库存校验失败的处置）。
  /// 只移除被挡住的那几行，其余原样留在车里 —— 整单打回会让用户重来一遍。
  Future<void> _showUnavailable(
      AppLocalizations l10n, CheckoutPreview p, List<UnavailableLine> lines) async {
    final removed = await showDialog<bool>(
      context: context,
      builder: (dlgCtx) => AlertDialog(
        key: const ValueKey('checkoutUnavailableDialogV2'),
        title: Text(l10n.checkoutUnavailableTitle, style: ShopText.sectionTitle),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final l in lines)
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  '${l.productName ?? l.skuToken} · '
                  '${l.isDelisted ? l10n.checkoutUnavailableDelisted : l10n.checkoutUnavailableStock(l.available, l.requested)}',
                  style: ShopText.body,
                ),
              ),
          ],
        ),
        actions: [
          ShopButton(
            key: const ValueKey('checkoutRemoveUnavailableV2'),
            label: l10n.checkoutUnavailableRemove,
            variant: ShopButtonVariant.rose,
            dense: true,
            onTap: () => Navigator.of(dlgCtx).pop(true),
          ),
        ],
      ),
    );
    if (removed != true || !mounted) return;
    for (final l in lines) {
      try {
        await ref.read(cartProvider.notifier).remove(l.skuToken);
      } on CartMutationError {
        // 单行移除失败不该阻断其余行；试算刷新后用户会看到真实状态
      }
    }
    if (mounted) ref.invalidate(checkoutPreviewProvider(p.addressToken));
  }

  // ---------------------------------------------------------------- 杂项

  Widget _noAddressState(AppLocalizations l10n) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(l10n.checkoutAddAddress,
                  textAlign: TextAlign.center, style: ShopText.sectionTitle),
              const SizedBox(height: 14),
              ShopButton(
                key: const ValueKey('checkoutAddAddressV2'),
                label: l10n.checkoutChooseAddress,
                variant: ShopButtonVariant.purple,
                onTap: () => context.push('/me/addresses/new'),
              ),
            ],
          ),
        ),
      );

  Widget _hint(String text) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(text, textAlign: TextAlign.center, style: ShopText.body),
        ),
      );
}
