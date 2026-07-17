import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/consult_repository.dart';
import 'vet_request_confirm_page.dart' show kVetConsultPriceIdr, formatVetConsultIdr;

/// 限时支付屏（Story 3.5，`p-vet-timed-pay`，1.5min）。渠道选择（QRIS/PawCoin）+ 服务端权威倒计时 +
/// **支付按钮全程可用**（倒计时中任意时刻可点）。DONE=PawCoin 即时成功跳会话 / PAYMENT_REQUIRED=现金轮询到账。
///
/// 中断=**服务端权威即重播**（UX-DR14）：返回不本地删——退出后服务端支付窗超时扫描自动作废重播（3-4），
/// 支付表单不丢。跳充值余额不足：`POST /pause` → 充值页 → 返回 `POST /resume` 顺延（A-4）。
class VetTimedPayPage extends ConsumerStatefulWidget {
  const VetTimedPayPage({super.key, required this.requestToken, this.payDeadlineAt});

  final String requestToken;
  final DateTime? payDeadlineAt;

  @override
  ConsumerState<VetTimedPayPage> createState() => _VetTimedPayPageState();
}

class _VetTimedPayPageState extends ConsumerState<VetTimedPayPage> {
  static const Duration _pollInterval = Duration(seconds: 3);
  static const int _windowSeconds = 300; // 5min（服务端 PAY_WINDOW_SECONDS 对齐）

  Timer? _poll;
  Timer? _display;
  DateTime? _deadline;
  int _remaining = _windowSeconds;
  String _channel = 'PAWCOIN';
  bool _paying = false;
  bool _navigating = false;
  bool _awaitingCash = false; // PAYMENT_REQUIRED 后进「等待到账」态

  @override
  void initState() {
    super.initState();
    _deadline = widget.payDeadlineAt;
    _recompute();
    _startPolling();
    _display = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(_recompute);
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _display?.cancel();
    super.dispose();
  }

  void _recompute() {
    final d = _deadline;
    _remaining = d == null
        ? _remaining
        : d.difference(DateTime.now()).inSeconds.clamp(0, _windowSeconds);
  }

  void _startPolling() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).requestStatus(widget.requestToken);
      if (!mounted) return;
      if (s.payDeadlineAt != null) _deadline = s.payDeadlineAt; // 服务端权威纠偏（含 resume 顺延）
      setState(_recompute);
    } on DioException catch (e) {
      if (!mounted) return;
      // 404 = 请求消失：现金付款成功已转单 / 或支付窗超时被服务端作废重播 → 查 active 分流。
      if (e.response?.statusCode == 404) {
        _poll?.cancel();
        _gotoActiveOrExit();
      }
    }
  }

  /// 付款成功后（有活跃会话）跳会话；无会话（超时/重播）回问诊页（表单不丢，服务端已处理）。
  Future<void> _gotoActiveOrExit() async {
    if (_navigating) return;
    try {
      final session = await ref.read(consultRepositoryProvider).active();
      if (!mounted) return;
      if (session != null) {
        _navigating = true;
        context.pushReplacement('/consult/conversation/${session.id}');
        return;
      }
    } catch (_) {
      // 查询失败：回退到问诊页。
    }
    if (mounted) {
      _navigating = true;
      showAppToast(context, AppLocalizations.of(context).vetPayExpired);
      context.go('/triage');
    }
  }

  bool get _pawInsufficient {
    final balance = ref.read(pawCoinProvider).value?.balance ?? 0;
    return _channel == 'PAWCOIN' && balance < kVetConsultPriceIdr;
  }

  Future<void> _pay() async {
    if (_paying) return;
    final l10n = AppLocalizations.of(context);
    if (_pawInsufficient) {
      await _topUp(); // PawCoin 不足 → 跳充值（暂停顺延）
      return;
    }
    setState(() => _paying = true);
    try {
      final result = await ref.read(consultRepositoryProvider).payRequest(widget.requestToken, _channel);
      if (!mounted) return;
      if (result.isDone) {
        // PawCoin 即时成功 → 建单建会话已完成 → 跳会话。
        _poll?.cancel();
        _display?.cancel();
        await _gotoActiveOrExit();
      } else {
        // 现金 → 进等待到账态，轮询 status 侦测转单（404）。
        setState(() {
          _paying = false;
          _awaitingCash = true;
        });
      }
    } on DioException catch (e) {
      if (!mounted) return;
      setState(() => _paying = false);
      final code = e.response?.statusCode;
      // 409=余额不足/支付窗过期/守卫不符；503=IM 建会话失败可重试。均映射 l10n，不显后端 detail。
      showAppToast(context, code == 409 ? l10n.vetPayFailed : l10n.vetPayFailed);
    }
  }

  /// 跳充值暂停顺延（A-4）：pause → 充值页 → 返回 resume → 重拉 status 续倒计时。
  Future<void> _topUp() async {
    try {
      await ref.read(consultRepositoryProvider).pauseRequest(widget.requestToken);
    } catch (_) {/* 暂停失败也继续跳充值（返回后 resume 会纠偏） */}
    if (!mounted) return;
    await context.push('/me/pawcoin/recharge');
    if (!mounted) return;
    try {
      await ref.read(consultRepositoryProvider).resumeRequest(widget.requestToken);
    } catch (_) {/* resume 失败 → 下个 tick 轮询纠偏（可能已超时 404） */}
    // 刷新余额 + 重拉状态续倒计时。
    ref.invalidate(pawCoinProvider);
    unawaited(_tick());
  }

  String get _mmss {
    final r = _remaining.clamp(0, _windowSeconds);
    final m = (r ~/ 60).toString().padLeft(2, '0');
    final s = (r % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop || _navigating) return;
        // UX-DR14 待支付中断即重播：不本地删，提示后离开，服务端支付窗超时自动作废重播。
        _navigating = true;
        showAppToast(context, l10n.vetPayInterruptHint);
        context.go('/triage');
      },
      child: Scaffold(
        backgroundColor: AppColors.base,
        appBar: AppBar(
          backgroundColor: AppColors.base,
          elevation: 0,
          automaticallyImplyLeading: false,
          title: Text(l10n.vetPayTitle,
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: AppColors.ink)),
        ),
        body: SafeArea(child: _awaitingCash ? _buildAwaitingCash(l10n) : _buildPay(l10n)),
      ),
    );
  }

  Widget _buildPay(AppLocalizations l10n) {
    final balance = ref.watch(pawCoinProvider).value?.balance ?? 0;
    return Column(
      children: [
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(l10n.vetPaySubtitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
                const SizedBox(height: 20),
                // 服务端权威倒计时盒。
                Center(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                    decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(14)),
                    child: Text(_mmss,
                        key: const ValueKey('vetPayCountdown'),
                        style: const TextStyle(
                            fontSize: 30, fontWeight: FontWeight.w700, letterSpacing: 2,
                            color: AppColors.mint, fontFeatures: [FontFeature.tabularFigures()])),
                  ),
                ),
                const SizedBox(height: 24),
                Text(l10n.vetPayChannelLabel,
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink2)),
                const SizedBox(height: 12),
                _channelTile('PAWCOIN', l10n.triageUnlockMethodPawcoin,
                    l10n.triageUnlockPawcoinBalance(balance), Icons.account_balance_wallet_outlined),
                const SizedBox(height: 10),
                _channelTile('QRIS', l10n.triageUnlockMethodQris, 'QRIS', Icons.qr_code_2_rounded),
                if (_pawInsufficient) ...[
                  const SizedBox(height: 14),
                  Row(
                    key: const ValueKey('vetPayInsufficient'),
                    children: [
                      const Icon(Icons.info_outline, size: 16, color: AppColors.danger),
                      const SizedBox(width: 6),
                      Expanded(child: Text(l10n.vetPayInsufficient,
                          style: const TextStyle(fontSize: 12, color: AppColors.danger))),
                      TextButton(
                        key: const ValueKey('vetPayTopup'),
                        onPressed: _topUp,
                        child: Text(l10n.vetPayTopup,
                            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.mint)),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
        // 支付按钮全程可用（倒计时中任意时刻可点）。
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 20),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('vetPayButton'),
              onPressed: _paying ? null : _pay,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint, foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: _paying
                  ? const SizedBox(width: 20, height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : Text(l10n.vetPayButton(formatVetConsultIdr(kVetConsultPriceIdr)),
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ),
        ),
      ],
    );
  }

  Widget _channelTile(String value, String title, String subtitle, IconData icon) {
    final selected = _channel == value;
    return InkWell(
      key: ValueKey('vetPayChannel_$value'),
      onTap: () => setState(() => _channel = value),
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: selected ? AppColors.mintTint : AppColors.card,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: selected ? AppColors.mint : AppColors.line, width: selected ? 1.5 : 1),
        ),
        child: Row(
          children: [
            Icon(icon, size: 22, color: selected ? AppColors.mint : AppColors.ink2),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  Text(subtitle, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
                ],
              ),
            ),
            Icon(selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
                size: 20, color: selected ? AppColors.mint : AppColors.muted),
          ],
        ),
      ),
    );
  }

  Widget _buildAwaitingCash(AppLocalizations l10n) {
    return Center(
      key: const ValueKey('vetPayAwaitingCash'),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircularProgressIndicator(color: AppColors.mint),
          const SizedBox(height: 20),
          Text(l10n.vetPayProcessing,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 8),
          Text(l10n.vetPayScanHint,
              style: const TextStyle(fontSize: 12, color: AppColors.muted)),
        ],
      ),
    );
  }
}
