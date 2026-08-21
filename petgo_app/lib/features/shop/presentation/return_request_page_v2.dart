/// 退货申请 —— **设计稿版式**（V1.4.0 · `02_screens_orders_refund.md` 屏 4）。
///
/// 与 [ReturnRequestPage]（v1 版式）并存，由 `shopUiVariantProvider` 二选一。
///
/// ## 🔴 四条不能省的告知（每一条对应一类真实客诉，与 v1 逐条相同）
///
/// 1. **「开封不退」的行保留可见但置灰**并标注原因 —— 比提交后再驳回体验好得多。
///    这里是该规则**三处明示的第 3 处**（第 1 处商品详情页、第 2 处结算页）。
/// 2. **每个原因选项直接标出回程运费由谁承担** —— 不等提交后才告知。
/// 3. **去程运费提示行随勾选实时切换**（UX-DR2）：全退 → 会退回；部分退 → 不退回。
///    这是堵住「免运门槛凑单 → 退掉凑单商品」套利的**唯一告知点**。
/// 4. **可退判定以服务端为准**：用服务端下发的 `selectable` / `blockedReason` 渲染。
///
/// ## ⚠️ 与代码库既有约定的两处冲突（按设计稿实现，在此标注）
///
/// **① 照片张数**：设计稿要求 **min 2 / max 5**；v1 实现是「质量问题必填 ≥1，最多 6」。
/// 本页按设计稿收紧。两者都比服务端校验更严，因此不会破坏契约，
/// 但**收紧下限会挡住只拍了一张照片的用户** —— 这是产品取舍，不是实现细节，需产品确认。
///
/// **② 原因四选项 vs 两个 API 值**：设计稿列了 4 个原因，而 `ReturnType` 只有
/// `QUALITY_ISSUE` / `NON_QUALITY` 两值。此处按 4 项渲染（用户视角更准），
/// 提交时映射回两值，并把所选原因的文案写进 `reasonNote` 供质检看 ——
/// **不新增枚举值**（那要改后端 + Flyway）。
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/shop_tokens.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';
import 'widgets/shop_buttons.dart';
import 'widgets/shop_controls.dart';
import 'widgets/shop_decor.dart';
import 'widgets/shop_surface.dart';

/// 设计稿的 4 个退货原因 → 后端两个 `ReturnType`。
enum ReturnReasonOption {
  wrongVariant(ReturnType.qualityIssue),
  damaged(ReturnType.qualityIssue),
  nearExpiry(ReturnType.qualityIssue),
  changedMind(ReturnType.nonQualityIssue);

  const ReturnReasonOption(this.type);

  final ReturnType type;

  /// 🔴 回程运费由谁承担 —— 直接取自 [ReturnType]，**不在 UI 层另算一遍**。
  bool get platformPaysReturnShipping => type.platformPaysReturnShipping;
}

class ReturnRequestPageV2 extends ConsumerStatefulWidget {
  const ReturnRequestPageV2({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<ReturnRequestPageV2> createState() => _ReturnRequestPageV2State();
}

class _ReturnRequestPageV2State extends ConsumerState<ReturnRequestPageV2> {
  /// 🔴 **不预选原因**。预选会让人在没看清运费归属的情况下提交 ——
  /// 而「改主意」那一档的回程运费是买家自付。
  ReturnReasonOption? _reason;

  final Map<int, int> _selected = {};
  final List<String> _evidence = [];
  bool _busy = false;

  /// 设计稿：min 2 / max 5（见文件头冲突说明）。
  static const int kMinPhotos = 2;
  static const int kMaxPhotos = 5;

  ReturnType get _type => _reason?.type ?? ReturnType.qualityIssue;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_return_request_viewed');
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(returnEligibilityProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: ShopColors.bg,
      appBar: ShopAppBar(title: l10n.returnRequestTitle),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => _hint(l10n.returnRequestLoadFailed),
        data: (data) => data.eligible ? _content(l10n, data) : _blocked(l10n, data),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (data) => data.eligible ? _bottomBar(l10n, data) : null,
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ReturnEligibility data) => ListView(
        padding: EdgeInsets.zero,
        children: [
          _itemsBlock(l10n, data),
          _reasonBlock(l10n),
          _photoBlock(l10n),
          _noReturnBlock(l10n),
          const SizedBox(height: kShopGutter),
        ],
      );

  // ---------------------------------------------------------------- 商品

  Widget _itemsBlock(AppLocalizations l10n, ReturnEligibility data) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.returnRequestItems, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 10),
            for (final line in data.lines) _lineRow(l10n, line),
          ],
        ),
      );

  Widget _lineRow(AppLocalizations l10n, ReturnableLine line) {
    // 🔴 可退判定以服务端为准；切换原因时按同一份规则做本地预判
    //    （质量问题下「开封不退」的行仍可选 —— 破损/临期/错发与是否开封无关）。
    final selectable = line.selectableFor(_type);
    final picked = _selected[line.orderLineId] ?? 0;

    return Opacity(
      // 🔴 不可退的行**保留可见但置灰**并标注原因 —— 直接隐藏会让用户
      //    以为自己记错了买过什么；提交后再驳回则更差。
      opacity: selectable ? 1 : .5,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ShopCheckbox(
              key: ValueKey('returnLine_${line.orderLineId}'),
              value: picked > 0,
              enabled: selectable,
              onChanged: (v) => setState(() {
                if (v) {
                  _selected[line.orderLineId] = line.returnableQty;
                } else {
                  _selected.remove(line.orderLineId);
                }
              }),
            ),
            const SizedBox(width: 2),
            const ShopImage(url: null, size: 46, radius: ShopShape.radiusChip),
            const SizedBox(width: 9),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${line.productName} · ${line.specName}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: ShopText.productNameCard),
                  const SizedBox(height: 3),
                  Text(formatIdr(line.unitPrice),
                      style: ShopText.priceInline.copyWith(
                          color: selectable ? ShopColors.accent : ShopColors.text4)),
                  // 🔴 不可退的原因由**服务端**给，前端直接展示 ——
                  //    前端自己拼会和服务端判定漂移。
                  if (!selectable && line.blockedReason != null) ...[
                    const SizedBox(height: 3),
                    Text(line.blockedReason!,
                        key: ValueKey('returnBlocked_${line.orderLineId}'),
                        style: ShopText.meta.copyWith(color: ShopColors.warnTitle)),
                  ],
                ],
              ),
            ),
            if (selectable && picked > 0) ...[
              const SizedBox(width: 8),
              ShopStepper(
                value: picked,
                min: 1,
                // 数量 ≥1 且 ≤ 原购可退数（设计稿）。
                max: line.returnableQty,
                onChanged: (v) => setState(() => _selected[line.orderLineId] = v),
              ),
            ],
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------- 原因

  Widget _reasonBlock(AppLocalizations l10n) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.returnRequestReason, style: ShopText.sectionTitle.copyWith(fontSize: 12)),
            const SizedBox(height: 10),
            for (final o in ReturnReasonOption.values) ...[
              ShopRadioTile(
                key: ValueKey('returnReason_${o.name}'),
                label: _reasonLabel(l10n, o),
                selected: _reason == o,
                onTap: () => setState(() {
                  _reason = o;
                  // 换了原因可能改变各行的可退性 —— 清掉已选，避免留下一个
                  // 在新原因下不可退的勾选（服务端会拒，用户看不懂为什么）。
                  _selected.removeWhere((id, _) => true);
                }),
              ),
              const SizedBox(height: 7),
            ],
          ],
        ),
      );

  String _reasonLabel(AppLocalizations l10n, ReturnReasonOption o) => switch (o) {
        ReturnReasonOption.wrongVariant => l10n.returnReasonWrongVariant,
        ReturnReasonOption.damaged => l10n.returnReasonDamaged,
        ReturnReasonOption.nearExpiry => l10n.returnReasonNearExpiry,
        ReturnReasonOption.changedMind => l10n.returnReasonChangedMind,
      };

  // ---------------------------------------------------------------- 照片

  Widget _photoBlock(AppLocalizations l10n) => ShopSection(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(l10n.returnRequestEvidence,
                      style: ShopText.sectionTitle.copyWith(fontSize: 12)),
                ),
                Text(l10n.returnPhotoRequired,
                    style: ShopText.meta.copyWith(fontSize: 10)),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (var i = 0; i < _evidence.length; i++)
                  SizedBox(
                    width: 72,
                    height: 72,
                    child: Stack(
                      children: [
                        const ShopImage(url: null, size: 72, radius: ShopShape.radiusField),
                        Positioned(
                          right: 2,
                          top: 2,
                          child: InkWell(
                            key: ValueKey('returnEvidenceRemove_$i'),
                            onTap: () => setState(() => _evidence.removeAt(i)),
                            child: Container(
                              width: 18,
                              height: 18,
                              alignment: Alignment.center,
                              decoration: const BoxDecoration(
                                  color: ShopColors.imageButtonScrim, shape: BoxShape.circle),
                              child: const Icon(Icons.close,
                                  size: 11, color: ShopColors.surface),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                if (_evidence.length < kMaxPhotos)
                  InkWell(
                    key: const ValueKey('returnEvidenceAddV2'),
                    onTap: () => setState(() =>
                        _evidence.add('return-evidence-${_evidence.length + 1}')),
                    child: CustomPaint(
                      painter: DashedRRectPainter(
                          color: ShopColors.border, radius: ShopShape.radiusField),
                      child: SizedBox(
                        width: 72,
                        height: 72,
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.add, size: 15, color: ShopColors.purple),
                            const SizedBox(height: 2),
                            Text('${_evidence.length}/$kMaxPhotos',
                                style: ShopText.badge.copyWith(color: ShopColors.text3)),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 7),
            Text(l10n.returnPhotoHint, style: ShopText.meta),
          ],
        ),
      );

  /// 开封不退明示（三处的**第 3 处**）+ 追加拒退后果。
  ///
  /// 🔴 与商品详情页、结算页**同一批 ARB key**，逐字一致（文案一致性契约）。
  Widget _noReturnBlock(AppLocalizations l10n) => ShopSection(
        child: ShopWarnBlock(
          key: const ValueKey('returnNoReturnBlockV2'),
          title: l10n.tokoNoReturnAfterOpenTitle,
          body: '${l10n.tokoNoReturnAfterOpenBody} ${l10n.returnNoReturnAfterOpenNotice}',
        ),
      );

  // ---------------------------------------------------------------- 底部条

  Widget _bottomBar(AppLocalizations l10n, ReturnEligibility data) {
    final full = _isFullReturn(data);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // 🔴 运费归属说明**随原因实时切换**（设计稿）：平台责任 → 平台承担；
        //    改主意 → 买家承担。等提交后才告知是最典型的客诉来源。
        Container(
          width: double.infinity,
          color: ShopColors.surface,
          padding: const EdgeInsets.fromLTRB(kShopScreenEdge, 8, kShopScreenEdge, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_reason != null)
                Text(
                  _reason!.platformPaysReturnShipping
                      ? l10n.returnShipBearerPlatform
                      : l10n.returnShipBearerBuyer,
                  key: const ValueKey('returnShipBearerNoticeV2'),
                  style: ShopText.meta,
                ),
              // 🔴 去程运费提示随勾选切换 —— 堵凑单套利的唯一告知点（UX-DR2）。
              Text(
                full ? l10n.returnOutboundWillRefund : l10n.returnOutboundWillNotRefund,
                key: const ValueKey('returnOutboundFeeNoticeV2'),
                style: ShopText.meta.copyWith(
                    color: full ? ShopColors.purple : ShopColors.text3),
              ),
            ],
          ),
        ),
        ShopBottomBarActions(
          primary: ShopButton(
            key: const ValueKey('returnSubmitV2'),
            label: l10n.returnNextChooseRefund,
            variant: _busy ? ShopButtonVariant.disabled : ShopButtonVariant.pay,
            onTap: _busy ? null : () => _submit(l10n),
          ),
        ),
      ],
    );
  }

  bool _isFullReturn(ReturnEligibility data) {
    if (_selected.isEmpty) return false;
    for (final line in data.lines) {
      final picked = _selected[line.orderLineId] ?? 0;
      if (picked < line.returnableQty) return false;
    }
    return true;
  }

  Widget _blocked(AppLocalizations l10n, ReturnEligibility data) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(
            data.activeRequestToken != null
                ? l10n.returnBlockedActive
                : (data.ineligibleReason ?? l10n.returnRequestLoadFailed),
            textAlign: TextAlign.center,
            style: ShopText.body,
          ),
        ),
      );

  Widget _hint(String text) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(text, textAlign: TextAlign.center, style: ShopText.body),
        ),
      );

  // ---------------------------------------------------------------- 提交

  Future<void> _submit(AppLocalizations l10n) async {
    if (_selected.isEmpty) {
      showAppToast(context, l10n.returnSelectAtLeastOne);
      return;
    }
    if (_reason == null) {
      showAppToast(context, l10n.returnRequestReason);
      return;
    }
    // 🔴 设计稿：照片 min 2。质量问题那一档恰恰是平台承担运费 + 发补偿溢价的，
    //    没有凭证既无法质检也无法复盘。服务端也会再判一次。
    if (_evidence.length < kMinPhotos) {
      showAppToast(context, l10n.returnPhotoTooFew);
      return;
    }

    Analytics.capture('toko_return_request_submitted');
    setState(() => _busy = true);
    try {
      final progress = await ref.read(shopReturnRepositoryProvider).submit(
            orderToken: widget.orderToken,
            returnType: _type,
            selections: Map.of(_selected),
            // 4 个原因映射回 2 个 API 值后，把具体原因写进备注供质检看
            // （见文件头冲突②：不新增枚举值）。
            reasonNote: _reasonLabel(l10n, _reason!),
            evidenceKeys: List.of(_evidence),
          );
      if (!mounted) return;
      ref.invalidate(returnEligibilityProvider(widget.orderToken));
      showAppToast(context, l10n.returnRequestSubmitted);
      // 🔴 下一步**不是提交完成**：退款去向由支付构成决定且不可选，
      //    必须先让用户看到拆分结果再确认。
      context.push('/shop/returns/${progress.returnToken}/refund-method');
    } catch (_) {
      if (mounted) showAppToast(context, l10n.returnRequestFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
