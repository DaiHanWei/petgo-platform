import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/consult_repository.dart';
import '../domain/consult_request.dart';

/// IDR 千分位手工格式（不引 intl locale，照 triage_paywall / recharge 惯例）。
String formatVetConsultIdr(int v) {
  final s = v.toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
    buf.write(s[i]);
  }
  return 'Rp$buf';
}

/// 确认发起付费问诊屏（Story 3.5，`p-vet-request-confirm`）。说明 + 单价 + CTA。
/// 点击发起 → `POST /consultations` → 按返回 state 跳等待/支付屏（占用命中 alreadyActive 直跳对应态）。
class VetRequestConfirmPage extends ConsumerStatefulWidget {
  const VetRequestConfirmPage({super.key});

  @override
  ConsumerState<VetRequestConfirmPage> createState() => _VetRequestConfirmPageState();
}

class _VetRequestConfirmPageState extends ConsumerState<VetRequestConfirmPage> {
  bool _busy = false;

  Future<void> _start() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final req = await ref.read(consultRepositoryProvider).createRequest();
      if (!mounted) return;
      // 占用命中（alreadyActive）已在支付态 → 直跳支付屏；否则入队等待屏。
      final path = req.state == ConsultRequestState.acceptedAwaitPay
          ? '/consult/vet-request/pay/${req.requestToken}'
          : '/consult/vet-request/waiting/${req.requestToken}';
      context.pushReplacement(path);
    } on DioException catch (e) {
      if (!mounted) return;
      setState(() => _busy = false);
      final l10n = AppLocalizations.of(context);
      // 无宠物档案 → 409（引导先建档）；其余 → 通用失败（不显后端 detail 原文）。
      showAppToast(context, e.response?.statusCode == 409 ? l10n.vetRequestNoPet : l10n.vetPayFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 后台可配价实时下发（bug 20260729-417）；无本地兜底——失败显示重试，价格未取到禁发起。
    final priceAsync = ref.watch(vetConsultPriceProvider);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        elevation: 0,
        foregroundColor: AppColors.ink,
        title: Text(l10n.vetRequestConfirmTitle,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Center(
                      child: Container(
                        width: 88,
                        height: 88,
                        alignment: Alignment.center,
                        decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
                        child: const Text('🩺', style: TextStyle(fontSize: 40)),
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text(l10n.vetRequestConfirmDesc,
                        key: const ValueKey('vetRequestDesc'),
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 14, height: 1.7, color: AppColors.ink2)),
                    const SizedBox(height: 24),
                    // 单价卡（紫浅底）。
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
                      decoration: BoxDecoration(
                        color: AppColors.cream2,
                        borderRadius: BorderRadius.circular(14),
                        border: Border.all(color: AppColors.lineViolet),
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(l10n.vetRequestPriceLabel,
                              style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
                          priceAsync.when(
                            data: (price) => Text(formatVetConsultIdr(price),
                                key: const ValueKey('vetRequestPrice'),
                                style: const TextStyle(
                                    fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.mint)),
                            loading: () => const SizedBox(
                                width: 18, height: 18,
                                child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.mint)),
                            error: (_, _) => ConsultPriceRetry(
                                key: const ValueKey('vetRequestPriceRetry'),
                                onRetry: () => ref.invalidate(vetConsultPriceProvider)),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 20),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('vetRequestStart'),
                  // 价格未取到（加载中/失败）禁发起：用户必须先看到本次费用（bug 20260729-417）。
                  onPressed: _busy || !priceAsync.hasValue ? null : _start,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: _busy
                      ? const SizedBox(
                          width: 20, height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Text(l10n.vetRequestStart,
                          style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 咨询价拉取失败的行内重试件（bug 20260729-417）：「加载失败 · 重试」，点击重拉。
/// 确认页价格卡 / 病例表单价格提示 / 支付页共用——价格取不到时替代价格文本出现。
class ConsultPriceRetry extends StatelessWidget {
  const ConsultPriceRetry({super.key, required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return InkWell(
      onTap: onRetry,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.vetPriceLoadFailed,
                style: const TextStyle(fontSize: 12, color: AppColors.danger)),
            const SizedBox(width: 6),
            const Icon(Icons.refresh, size: 15, color: AppColors.mint),
            const SizedBox(width: 2),
            Text(l10n.vetPriceRetry,
                style: const TextStyle(
                    fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint)),
          ],
        ),
      ),
    );
  }
}
