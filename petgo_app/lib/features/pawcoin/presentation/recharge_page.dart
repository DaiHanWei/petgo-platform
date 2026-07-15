import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../data/pawcoin_repository.dart';
import '../domain/topup.dart';
import 'pawcoin_controller.dart';

/// PawCoin 充值页（Story 1.5 · `p-pawcoin-recharge`）。选档位+渠道 → 下单 → 支付载荷 + 轮询到账。
/// 六态：加载 / 选档位 / 支付中 / 失败(余额不变 FR-43F) / 暂停(AB-6C) / 加载错误。
/// <b>轮询 Timer 必须在 dispose 与终态 cancel</b>（防 "Timer still pending" 泄漏）。
class RechargePage extends ConsumerStatefulWidget {
  const RechargePage({super.key});

  @override
  ConsumerState<RechargePage> createState() => _RechargePageState();
}

enum _Phase { loading, select, paying, fail, pause, error }

class _RechargePageState extends ConsumerState<RechargePage> {
  static const int _maxPolls = 40; // ~2min @3s（轮询上限，超时按失败；QRIS 超时 [OPEN] 可调）

  _Phase _phase = _Phase.loading;
  TopupOptions? _options;
  String? _selectedTier;
  String _channel = 'QRIS';
  TopupResult? _topup;
  Timer? _pollTimer;
  int _polls = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _pollTimer?.cancel(); // 防 Timer 泄漏
    super.dispose();
  }

  PawCoinRepository get _repo => ref.read(pawCoinRepositoryProvider);

  Future<void> _load() async {
    setState(() => _phase = _Phase.loading);
    try {
      final opts = await _repo.fetchTopupOptions();
      if (!mounted) return;
      setState(() {
        _options = opts;
        _phase = opts.paused ? _Phase.pause : _Phase.select;
        _selectedTier ??= opts.tiers.isNotEmpty ? opts.tiers.first.id : null;
      });
    } catch (_) {
      if (mounted) setState(() => _phase = _Phase.error);
    }
  }

  Future<void> _submit() async {
    final tier = _selectedTier;
    if (tier == null) return;
    setState(() => _phase = _Phase.loading);
    try {
      final res = await _repo.createTopup(
        tierId: tier,
        channel: _channel,
        idemKey: 'topup-${DateTime.now().microsecondsSinceEpoch}',
      );
      if (!mounted) return;
      setState(() {
        _topup = res;
        _phase = _Phase.paying;
      });
      _startPolling(res.intentToken);
    } catch (_) {
      if (mounted) setState(() => _phase = _Phase.fail);
    }
  }

  void _startPolling(String token) {
    _polls = 0;
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      _polls++;
      String status;
      try {
        status = await _repo.pollStatus(token);
      } catch (_) {
        status = 'PENDING'; // 网络抖动：继续轮询
      }
      if (!mounted) return;
      if (status == 'PAID') {
        _onPaid();
      } else if (status == 'FAILED' || status == 'EXPIRED' || _polls >= _maxPolls) {
        _onFailed();
      }
    });
  }

  void _onPaid() {
    _pollTimer?.cancel();
    if (!mounted) return;
    ref.invalidate(pawCoinProvider); // 刷新余额页
    Navigator.of(context).pop(); // 返回余额页（余额已更新）
  }

  void _onFailed() {
    _pollTimer?.cancel();
    if (mounted) setState(() => _phase = _Phase.fail);
  }

  void _retry() {
    _pollTimer?.cancel();
    setState(() {
      _topup = null;
      _phase = _options == null ? _Phase.loading : (_options!.paused ? _Phase.pause : _Phase.select);
    });
    if (_options == null) _load();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.rechargeTitle)),
      body: switch (_phase) {
        _Phase.loading => const Center(child: CircularProgressIndicator()),
        _Phase.select => _selectView(l10n),
        _Phase.paying => _payingView(l10n),
        _Phase.fail => _failView(l10n),
        _Phase.pause => _pauseView(l10n),
        _Phase.error => _errorView(l10n),
      },
    );
  }

  // ---- 选档位 ----
  Widget _selectView(AppLocalizations l10n) {
    final tiers = _options?.tiers ?? const [];
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        Text(l10n.rechargeSelectAmount,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2)),
        const SizedBox(height: AppSpacing.md),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: AppSpacing.md,
          crossAxisSpacing: AppSpacing.md,
          childAspectRatio: 2.4,
          children: tiers.map((t) => _tierCard(t)).toList(),
        ),
        const SizedBox(height: AppSpacing.xl),
        Text(l10n.rechargeChannel,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink2)),
        const SizedBox(height: AppSpacing.md),
        Row(children: [
          _channelChip('QRIS'),
        ]),
        const SizedBox(height: AppSpacing.section),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            key: const ValueKey('rechargeSubmit'),
            onPressed: _selectedTier == null ? null : _submit,
            child: Text(l10n.rechargePay),
          ),
        ),
      ],
    );
  }

  Widget _tierCard(TopupTierOption t) {
    final selected = _selectedTier == t.id;
    return InkWell(
      key: ValueKey('rechargeTier-${t.id}'),
      borderRadius: BorderRadius.circular(12),
      onTap: () => setState(() => _selectedTier = t.id),
      child: Container(
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : AppColors.card,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: selected ? 2 : 1),
        ),
        alignment: Alignment.center,
        child: Text('Rp${_grouped(t.amount)}',
            style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w700,
                color: selected ? AppColors.mint : AppColors.ink)),
      ),
    );
  }

  Widget _channelChip(String channel) {
    final selected = _channel == channel;
    return Expanded(
      child: InkWell(
        key: ValueKey('rechargeChannel-$channel'),
        borderRadius: BorderRadius.circular(10),
        onTap: () => setState(() => _channel = channel),
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: AppSpacing.md),
          decoration: BoxDecoration(
            color: selected ? AppColors.mintTint : AppColors.card,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: selected ? 2 : 1),
          ),
          alignment: Alignment.center,
          child: Text(channel,
              style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: selected ? AppColors.mint : AppColors.ink)),
        ),
      ),
    );
  }

  // ---- 支付中（载荷 + 轮询）----
  Widget _payingView(AppLocalizations l10n) {
    final payload = _topup?.payload;
    // GemPay 返回 QRIS/EMVCo 串（qrcode），本地生成二维码渲染（不是图片 URL）。
    // 兼容历史：若 payload 恰是 http(s) 图片链接则回退 Image.network；空则占位框。
    final hasPayload = payload != null && payload.isNotEmpty;
    final isHttp = hasPayload && (payload.startsWith('http://') || payload.startsWith('https://'));
    return ListView(
      key: const ValueKey('rechargePaying'),
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: [
        Text(l10n.rechargePayingTitle,
            style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
        const SizedBox(height: AppSpacing.lg),
        Center(
          child: !hasPayload
              ? _stubQrBox(l10n)
              : isHttp
                  ? Image.network(payload, width: 220, height: 220, fit: BoxFit.contain,
                      errorBuilder: (_, _, _) => _stubQrBox(l10n))
                  : Container(
                      width: 220,
                      height: 220,
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: AppColors.line),
                      ),
                      child: QrImageView(
                        data: payload,
                        version: QrVersions.auto,
                        backgroundColor: Colors.white,
                        errorStateBuilder: (_, _) => _stubQrBox(l10n),
                      ),
                    ),
        ),
        const SizedBox(height: AppSpacing.md),
        Center(child: Text(l10n.rechargeScanHint, style: const TextStyle(fontSize: 13, color: AppColors.ink2))),
        const SizedBox(height: AppSpacing.xl),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
          const SizedBox(width: AppSpacing.md),
          Text(l10n.rechargeWaiting, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
        ]),
      ],
    );
  }

  Widget _stubQrBox(AppLocalizations l10n) => Container(
        width: 220,
        height: 220,
        decoration: BoxDecoration(
          color: AppColors.cream2,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.line),
        ),
        alignment: Alignment.center,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.qr_code_2, size: 64, color: AppColors.mint),
            const SizedBox(height: AppSpacing.sm),
            Text(l10n.rechargeStubHint,
                textAlign: TextAlign.center, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ]),
        ),
      );

  // ---- 失败/取消（FR-43F：余额不变、不落流水）----
  Widget _failView(AppLocalizations l10n) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            key: const ValueKey('rechargeFail'),
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.info_outline, size: 48, color: AppColors.muted),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.rechargeFailTitle,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.rechargeFailHint,
                  textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
              const SizedBox(height: AppSpacing.xl),
              FilledButton(
                key: const ValueKey('rechargeRetry'),
                onPressed: _retry,
                child: Text(l10n.rechargeRetry),
              ),
            ],
          ),
        ),
      );

  // ---- 暂停（浮存门槛，措辞刻意区别于失败，非 bug）----
  Widget _pauseView(AppLocalizations l10n) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: EmptyState(
            key: const ValueKey('rechargePause'),
            title: l10n.rechargePauseTitle,
            message: l10n.rechargePauseHint,
            icon: Icons.pause_circle_outline,
            iconBackground: AppColors.cream2,
          ),
        ),
      );

  Widget _errorView(AppLocalizations l10n) => Center(
        child: Column(
          key: const ValueKey('rechargeLoadError'),
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off_outlined, size: 48, color: AppColors.muted),
            const SizedBox(height: AppSpacing.md),
            Text(l10n.rechargeLoadFailed, style: const TextStyle(fontSize: 14, color: AppColors.ink2)),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              key: const ValueKey('rechargeReload'),
              onPressed: _load,
              child: Text(l10n.rechargeRetry),
            ),
          ],
        ),
      );

  String _grouped(int n) {
    final s = n.abs().toString();
    final buf = StringBuffer();
    for (int i = 0; i < s.length; i++) {
      if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
      buf.write(s[i]);
    }
    return buf.toString();
  }
}
