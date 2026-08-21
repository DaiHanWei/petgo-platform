import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/shop_return_repository.dart';
import '../domain/shop_product.dart';
import '../domain/shop_return.dart';

/// 退货申请页（Story 5.7，FR-104A / FR-104 / C-12 / UX-DR2 / UX-DR3）。
///
/// 🔴 **四条不能省的告知**，每一条都对应一类真实客诉：
/// 1. **「开封不退」的行保留可见但置灰**并标注原因 —— 比提交后再驳回体验好得多；
///    这里是该规则**三处明示的第 3 处**（第 1 处商品详情页 1.7，第 2 处结算页 3.7）。
/// 2. **每个原因选项右侧直接标出回程运费由谁承担** —— 不等提交后才告知，
///    那是最典型的客诉来源。
/// 3. **去程运费提示行随勾选实时切换**（UX-DR2）：全选 → 会退回；部分选 → 不退回。
///    这是堵住「免运门槛凑单 → 退掉凑单商品」套利的**唯一告知点**。
/// 4. **已有进行中申请时入口置灰**（UX-DR3 / C-12）—— 让用户点进来再被 409 挡住
///    是最差的一种告知方式。
///
/// 🔴 **可退判定以服务端为准**：本页用服务端下发的 `selectable` / `blockedReason` 渲染，
/// 只在切换退货类型时按同一份规则做本地预判（质量问题下「开封不退」仍可选）。
class ReturnRequestPage extends ConsumerStatefulWidget {
  const ReturnRequestPage({super.key, required this.orderToken});

  final String orderToken;

  @override
  ConsumerState<ReturnRequestPage> createState() => _ReturnRequestPageState();
}

class _ReturnRequestPageState extends ConsumerState<ReturnRequestPage> {
  ReturnType _type = ReturnType.qualityIssue;
  final Map<int, int> _selected = {};
  final List<String> _evidence = [];
  final TextEditingController _note = TextEditingController();
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    Analytics.capture('toko_return_request_viewed');
  }

  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(returnEligibilityProvider(widget.orderToken));

    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar:
          AppBar(title: Text(l10n.returnRequestTitle), backgroundColor: AppColors.cream),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (_, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.xl),
            child: Text(l10n.returnRequestLoadFailed, textAlign: TextAlign.center),
          ),
        ),
        data: (data) => _content(l10n, data),
      ),
      bottomNavigationBar: async.maybeWhen(
        data: (data) => data.eligible ? _bottomBar(l10n, data) : null,
        orElse: () => null,
      ),
    );
  }

  Widget _content(AppLocalizations l10n, ReturnEligibility data) {
    if (!data.eligible) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Text(
            // 🔴 UX-DR3：已有进行中申请 → 明确说清楚，而不是让用户提交后吃 409
            data.activeRequestToken != null
                ? l10n.returnBlockedActive
                : (data.ineligibleReason ?? l10n.returnRequestLoadFailed),
            key: const ValueKey('returnIneligible'),
            textAlign: TextAlign.center,
          ),
        ),
      );
    }

    return ListView(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
      children: [
        _section(l10n.returnRequestItems),
        for (final line in data.lines) _lineTile(l10n, line),
        // 🔴 UX-DR2：去程运费提示行，随勾选范围实时切换
        _outboundFeeNotice(l10n, data),
        _section(l10n.returnRequestReason),
        RadioGroup<ReturnType>(
          groupValue: _type,
          onChanged: (v) => setState(() => _type = v ?? _type),
          child: Column(
            children: [
              _reasonTile(ReturnType.qualityIssue, l10n.returnReasonQuality,
                  l10n.returnReasonQualitySub),
              _reasonTile(ReturnType.nonQualityIssue, l10n.returnReasonNonQuality,
                  l10n.returnReasonNonQualitySub),
            ],
          ),
        ),
        _section(l10n.returnRequestEvidence),
        _evidencePicker(l10n),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
          child: TextField(
            controller: _note,
            maxLines: 3,
            maxLength: 500,
            decoration: InputDecoration(labelText: l10n.returnRequestNote),
          ),
        ),
        // 🔴 「开封不退」三处明示的第 3 处 —— 整页级的那一句
        Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Container(
            padding: const EdgeInsets.all(AppSpacing.md),
            decoration: BoxDecoration(
              color: AppColors.mintTint,
              borderRadius: BorderRadius.circular(AppSpacing.sm),
            ),
            child: Text(l10n.returnNoReturnAfterOpenNotice,
                key: const ValueKey('returnNoReturnAfterOpenNotice'),
                style: const TextStyle(fontSize: 12)),
          ),
        ),
        if (data.returnAddress != null) ...[
          _section(l10n.returnAddressTitle),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
            child: Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                title: Text(
                    '${data.returnAddress!.receiverName} · '
                    '${data.returnAddress!.receiverPhone}',
                    style: const TextStyle(fontWeight: FontWeight.w600)),
                subtitle: Text(data.returnAddress!.addressText),
              ),
            ),
          ),
          // 🔴 S-7：用户自寄，明确说明没有上门取件 —— 不说，用户就会等一个不会来的快递员
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, AppSpacing.xs, AppSpacing.lg, 0),
            child: Text(l10n.returnNoPickupNotice,
                key: const ValueKey('returnNoPickupNotice'),
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ),
        ],
        const SizedBox(height: AppSpacing.xl),
      ],
    );
  }

  /// 一行商品。🔴 不可退的行**保留可见但置灰**，并直接标注原因。
  Widget _lineTile(AppLocalizations l10n, ReturnableLine line) {
    final selectable = line.selectableFor(_type);
    final picked = _selected[line.orderLineId] ?? 0;
    return Padding(
      padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg, vertical: AppSpacing.xxs),
      child: Opacity(
        opacity: selectable ? 1 : 0.45,
        child: Card(
          margin: EdgeInsets.zero,
          child: CheckboxListTile(
            key: ValueKey('returnLine_${line.orderLineId}'),
            value: picked > 0,
            onChanged: selectable
                ? (v) => setState(() {
                      if (v == true) {
                        _selected[line.orderLineId] = line.returnableQty;
                      } else {
                        _selected.remove(line.orderLineId);
                      }
                    })
                : null,
            title: Text(line.productName,
                style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${line.specName} × ${line.returnableQty} · '
                    '${formatIdr(line.unitPrice)}'),
                if (!selectable)
                  Text(
                      // 🔴 原因来自服务端；只有「质量问题下才可选」这一条是本地态，
                      //    因为它随用户当前选的原因变化，服务端下发时还不知道用户会选哪个。
                      line.blockedReason ?? l10n.returnNoReturnAfterOpenNotice,
                      key: ValueKey('returnLineBlocked_${line.orderLineId}'),
                      style: const TextStyle(fontSize: 12, color: AppColors.danger)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 🔴 UX-DR2：去程运费提示行 —— 随勾选实时切换，是堵凑单套利的唯一告知点。
  Widget _outboundFeeNotice(AppLocalizations l10n, ReturnEligibility data) {
    final full = _isFullReturn(data);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, 0),
      child: Text(
        full ? l10n.returnOutboundWillRefund : l10n.returnOutboundWillNotRefund,
        key: const ValueKey('returnOutboundFeeNotice'),
        style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w600,
            color: full ? AppColors.mint600 : AppColors.muted),
      ),
    );
  }

  /// 整单退 = 每一行的**剩余可退数量**都被选满（与服务端 C-12 判定同口径）。
  bool _isFullReturn(ReturnEligibility data) {
    var anySelectable = false;
    for (final line in data.lines) {
      if (line.returnableQty <= 0) continue;
      if (!line.selectableFor(_type)) continue;
      anySelectable = true;
      if ((_selected[line.orderLineId] ?? 0) < line.returnableQty) return false;
    }
    return anySelectable;
  }

  /// 原因选项。🔴 副标题**就是**回程运费归属 —— 它必须和选项在同一行被看见。
  ///
  /// 选中态与切换由外层 [RadioGroup] 统管。
  Widget _reasonTile(ReturnType type, String title, String subtitle) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Card(
        margin: const EdgeInsets.only(bottom: AppSpacing.xs),
        child: RadioListTile<ReturnType>(
          key: ValueKey('returnReason_${type.api}'),
          value: type,
          title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text(subtitle,
              key: ValueKey('returnReasonFee_${type.api}'),
              style: TextStyle(
                  fontSize: 12,
                  // 用户要付钱的那一档用警示色 —— 它是本页最容易被略过、
                  // 事后最容易引起客诉的一行。
                  color: type.platformPaysReturnShipping
                      ? AppColors.mint600
                      : AppColors.danger)),
        ),
      ),
    );
  }

  Widget _evidencePicker(AppLocalizations l10n) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: AppSpacing.xs,
              children: [
                for (var i = 0; i < _evidence.length; i++)
                  Chip(
                    key: ValueKey('returnEvidence_$i'),
                    label: Text('${i + 1}'),
                    onDeleted: () => setState(() => _evidence.removeAt(i)),
                  ),
                if (_evidence.length < 6)
                  ActionChip(
                    key: const ValueKey('returnEvidenceAdd'),
                    avatar: const Icon(Icons.add, size: 16),
                    label: Text(l10n.returnRequestEvidence),
                    // V1 用占位 key：真机相册接入沿用既有 media 能力，本页不重复实现。
                    onPressed: () => setState(
                        () => _evidence.add('return-evidence-${_evidence.length + 1}')),
                  ),
              ],
            ),
            const SizedBox(height: AppSpacing.xxs),
            Text(l10n.returnRequestEvidenceHint,
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ],
        ),
      );

  Widget _bottomBar(AppLocalizations l10n, ReturnEligibility data) => SafeArea(
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: const BoxDecoration(
            color: AppColors.cream,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('returnSubmit'),
              onPressed: _busy ? null : () => _submit(l10n),
              child: Text(l10n.returnRequestSubmit),
            ),
          ),
        ),
      );

  Future<void> _submit(AppLocalizations l10n) async {
    if (_selected.isEmpty) {
      showAppToast(context, l10n.returnSelectAtLeastOne);
      return;
    }
    // 🔴 质量问题必填凭证：它恰恰是平台承担运费 + 发补偿溢价的那一类，
    //    没有凭证既无法质检也无法复盘。服务端也会再判一次。
    if (_type == ReturnType.qualityIssue && _evidence.isEmpty) {
      showAppToast(context, l10n.returnEvidenceRequired);
      return;
    }
    Analytics.capture('toko_return_request_submitted');
    setState(() => _busy = true);
    try {
      final progress = await ref.read(shopReturnRepositoryProvider).submit(
            orderToken: widget.orderToken,
            returnType: _type,
            selections: Map.of(_selected),
            reasonNote: _note.text.trim().isEmpty ? null : _note.text.trim(),
            evidenceKeys: _evidence.isEmpty ? null : List.of(_evidence),
          );
      if (!mounted) return;
      ref.invalidate(returnEligibilityProvider(widget.orderToken));
      showAppToast(context, l10n.returnRequestSubmitted);
      // 提交后直接进退款方式页 —— 两段拆分要在用户还记得这笔单的时候讲清楚
      context.push('/shop/returns/${progress.returnToken}/refund-method');
    } catch (_) {
      if (mounted) showAppToast(context, l10n.returnRequestFailed);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _section(String text) => Padding(
        padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, AppSpacing.sm),
        child: Text(text, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
      );
}
