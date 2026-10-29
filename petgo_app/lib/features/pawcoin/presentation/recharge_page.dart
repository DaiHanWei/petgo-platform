import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/qr_payment/qr_pay_parts.dart';
import '../../../shared/widgets/qr_payment/qr_pay_save.dart';
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

enum _Phase { loading, select, paying, fail, pause, error, expired }

class _RechargePageState extends ConsumerState<RechargePage> {
  /// 无过期时刻兜底的轮询上限（后端返回 expiresAt 时以其为准；仅旧后端/异常回退用）。
  static const int _fallbackWindowSeconds = 3600; // 60min @3s = 1200 polls

  _Phase _phase = _Phase.loading;
  TopupOptions? _options;
  String? _selectedTier;
  String _channel = 'QRIS';
  TopupResult? _topup;
  Timer? _pollTimer;
  Timer? _displayTimer; // 1s 心跳刷新倒计时显示
  int _polls = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _pollTimer?.cancel(); // 防 Timer 泄漏
    _displayTimer?.cancel();
    super.dispose();
  }

  /// 付款窗剩余（服务端权威 expiresAt 为准；重开返回同一笔时为原始过期时刻→剩余不重置）。
  Duration get _remaining {
    final exp = _topup?.expiresAt;
    if (exp == null) return const Duration(seconds: _fallbackWindowSeconds);
    final left = exp.difference(DateTime.now().toUtc());
    return left.isNegative ? Duration.zero : left;
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
      // 归因（AppsFlyer P1）：充值下单未支付。af_ 标准名供投放渠道识别。
      Analytics.capture('af_initiated_checkout', {
        'af_price': res.amount,
        'af_currency': 'IDR',
        'af_content_id': tier,
      });
      _startPolling(res.intentToken);
    } catch (_) {
      if (mounted) setState(() => _phase = _Phase.fail);
    }
  }

  void _startPolling(String token) {
    _polls = 0;
    _pollTimer?.cancel();
    _displayTimer?.cancel();
    // 60min 窗内 3s 轮询到账；剩余归零即过期（服务端权威 EXPIRED 也会经轮询返回）。
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      _polls++;
      if (_remaining <= Duration.zero) {
        _onExpired();
        return;
      }
      String status;
      try {
        status = await _repo.pollStatus(token);
      } catch (_) {
        status = 'PENDING'; // 网络抖动：继续轮询
      }
      if (!mounted) return;
      if (status == 'PAID') {
        _onPaid();
      } else if (status == 'EXPIRED') {
        _onExpired();
      } else if (status == 'FAILED' || _polls > _fallbackWindowSeconds ~/ 3) {
        _onFailed();
      }
    });
    // 1s 刷新倒计时显示（纯客户端显示，跃迁靠轮询服务端）。
    _displayTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      if (_remaining <= Duration.zero) {
        _onExpired();
      } else if (_phase == _Phase.paying) {
        setState(() {});
      }
    });
  }

  void _stopTimers() {
    _pollTimer?.cancel();
    _displayTimer?.cancel();
  }

  void _onPaid() {
    _stopTimers();
    // 归因（AppsFlyer P0）：**唯一的真实收入事件**——af_revenue 只计充值实付 IDR；
    // PawCoin 消耗（问诊/解锁等）绝不能再计 af_purchase，否则收入/ROAS 双倍虚报。
    final t = _topup;
    if (t != null) {
      Analytics.capturePurchase(
        amountIdr: t.amount,
        purpose: 'PAWCOIN_TOPUP',
        orderRef: t.intentToken, // 对账去重用（不可枚举 token，非自增 id）
      );
    }
    if (!mounted) return;
    ref.invalidate(pawCoinProvider); // 刷新余额页
    Navigator.of(context).pop(); // 返回余额页（余额已更新）
  }

  void _onFailed() {
    _stopTimers();
    if (mounted) setState(() => _phase = _Phase.fail);
  }

  /// 付款窗 60min 到期（订单过期）：可重新发起。
  void _onExpired() {
    _stopTimers();
    if (mounted) setState(() => _phase = _Phase.expired);
  }

  void _retry() {
    _stopTimers();
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
        _Phase.expired => _expiredView(l10n),
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
          childAspectRatio: 1.9,
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
        child: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('Rp${_grouped(t.amount)}',
                style: TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w700,
                    color: selected ? AppColors.mint : AppColors.ink)),
            const SizedBox(height: 2),
            // 换算副行（原型每档价格下的「= X koin」，1 koin = Rp1）。
            Text('= ${_grouped(t.coins)} koin',
                style: const TextStyle(fontSize: 12, color: AppColors.muted)),
          ],
        ),
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
  // V1.4.1 QRIS 支付页 B1：原有标题 / 二维码 / 倒计时 / 等待中全部保留（扫码提示并入步骤 2），
  // 新增金额卡（含到账 koin）、订单号、付款 App、付款步骤、吸底「Simpan Kode QR」。
  // 充值页原本没有取消按钮（返回即离开），本次不加。B6：后端没给 payload（测试环境）→ 占位框、保存置灰。
  Widget _payingView(AppLocalizations l10n) {
    final t = _topup;
    final payload = t?.payload;
    final hasPayload = payload != null && payload.isNotEmpty;
    return Column(
      key: const ValueKey('rechargePaying'),
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(2, 2, 2, 12),
                child: Text(l10n.rechargePayingTitle,
                    style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
              ),
              // 60min 付款窗倒计时（服务端权威 expiresAt；重开不重置）。
              QrPaySummary(
                density: QrPayDensity.page,
                amountIdr: t?.amount,
                coins: t?.coins,
                remaining: _remaining,
                expiresAt: t?.expiresAt,
                countdownKey: const ValueKey('rechargeCountdown'),
              ),
              const SizedBox(height: 12),
              QrPayCodeCard(
                density: QrPayDensity.page,
                payload: payload,
                orderRef: t?.paymentRef,
                stubHint: l10n.rechargeStubHint,
                qrKey: const ValueKey('rechargeQr'),
              ),
              QrPayGuide(expiresAt: t?.expiresAt),
            ],
          ),
        ),
        QrPayBottomBar(
          density: QrPayDensity.page,
          onSave: !hasPayload
              ? null
              : () => saveQrPaymentImage(context,
                  payload: payload,
                  amountIdr: t?.amount,
                  orderRef: t?.paymentRef,
                  expiresAt: t?.expiresAt),
        ),
      ],
    );
  }

  Widget _expiredView(AppLocalizations l10n) => Center(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            key: const ValueKey('rechargeExpired'),
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.timer_off_outlined, size: 56, color: AppColors.muted),
              const SizedBox(height: AppSpacing.md),
              Text(l10n.rechargeExpiredTitle,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.rechargeExpiredBody,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
              const SizedBox(height: AppSpacing.xl),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('rechargeExpiredRetry'),
                  onPressed: _retry,
                  child: Text(l10n.rechargeRetry),
                ),
              ),
            ],
          ),
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
            icon: Icons.settings_outlined,
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
