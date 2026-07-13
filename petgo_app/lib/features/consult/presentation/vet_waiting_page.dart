import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../data/consult_repository.dart';
import '../domain/consult_request.dart';

/// 等待接单屏（Story 3.5，`p-vet-waiting`，1min）。轮询 `GET /consultations/{token}`：
/// 接单（ACCEPTED_AWAIT_PAY）→ 跳限时支付屏；404（超时无兽医）→ 整屏切「暂无兽医」态。
///
/// 中断=**可丢弃无痕**（UX-DR14）：返回二次确认 → `POST /cancel` 物理删；退出 App（detached）→ 自动取消。
class VetWaitingPage extends ConsumerStatefulWidget {
  const VetWaitingPage({super.key, required this.requestToken, this.queueDeadlineAt});

  final String requestToken;
  final DateTime? queueDeadlineAt;

  @override
  ConsumerState<VetWaitingPage> createState() => _VetWaitingPageState();
}

class _VetWaitingPageState extends ConsumerState<VetWaitingPage> with WidgetsBindingObserver {
  static const Duration _pollInterval = Duration(seconds: 3);
  static const int _fallbackSeconds = 60;

  Timer? _poll;
  Timer? _display;
  DateTime? _deadline;
  int _remaining = _fallbackSeconds;
  bool _noVet = false;
  bool _navigating = false;
  bool _exitCancelDisabled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _deadline = widget.queueDeadlineAt;
    _recomputeRemaining();
    _startPolling();
    _display = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(_recomputeRemaining);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _poll?.cancel();
    _display?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // UX-DR14 待接单可丢弃无痕：退出 App（含 kill）→ best-effort 取消，兽医不会接到此单。
    if (state == AppLifecycleState.detached && !_exitCancelDisabled) {
      _exitCancelDisabled = true;
      _poll?.cancel();
      unawaited(_cancelSilently());
    }
  }

  void _recomputeRemaining() {
    final d = _deadline;
    _remaining = d == null
        ? _remaining
        : d.difference(DateTime.now()).inSeconds.clamp(0, _fallbackSeconds);
  }

  void _startPolling() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
    _tick();
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).requestStatus(widget.requestToken);
      if (!mounted) return;
      if (s.queueDeadlineAt != null) {
        _deadline = s.queueDeadlineAt; // 服务端权威 deadline 纠偏
      }
      if (s.state == ConsultRequestState.acceptedAwaitPay) {
        // 接单 → 已决态，退出不再取消 → 换到限时支付屏。
        _exitCancelDisabled = true;
        _poll?.cancel();
        _display?.cancel();
        if (mounted) {
          context.pushReplacement('/consult/vet-request/pay/${widget.requestToken}');
        }
        return;
      }
      setState(_recomputeRemaining);
    } on DioException catch (e) {
      if (!mounted) return;
      // 404 = 请求已消失（超时无兽医接单，服务端已删）→ 暂无兽医态。
      if (e.response?.statusCode == 404) {
        _exitCancelDisabled = true; // 已无请求，无需再取消
        _triggerNoVet();
      }
      // 其它错误静默重试（下个 tick）。
    }
  }

  void _triggerNoVet() {
    if (_noVet) return;
    _poll?.cancel();
    _display?.cancel();
    setState(() => _noVet = true);
  }

  Future<void> _confirmCancel() async {
    final l10n = AppLocalizations.of(context);
    final ok = await showConfirmSheet(
      context,
      title: l10n.vetWaitingCancelConfirmTitle,
      confirmLabel: l10n.vetWaitingCancelYes,
      cancelLabel: l10n.vetWaitingCancelNo,
      icon: Icons.close_rounded,
      confirmKey: const ValueKey('vetWaitingCancelYes'),
      cancelKey: const ValueKey('vetWaitingCancelNo'),
    );
    if (!ok) return;
    await _doCancel();
  }

  Future<void> _doCancel() async {
    _exitCancelDisabled = true;
    _poll?.cancel();
    _display?.cancel();
    await _cancelSilently();
    if (!mounted) return;
    _navigating = true;
    context.go('/triage');
  }

  Future<void> _cancelSilently() async {
    try {
      await ref.read(consultRepositoryProvider).cancelRequest(widget.requestToken);
    } catch (_) {
      // best-effort；服务端支付前状态以扫描/删除为准。
    }
  }

  String get _mmss {
    final r = _remaining.clamp(0, _fallbackSeconds);
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
        if (_noVet) {
          _navigating = true;
          context.go('/triage');
        } else {
          _confirmCancel();
        }
      },
      child: Scaffold(
        backgroundColor: AppColors.base,
        body: SafeArea(child: _noVet ? _buildNoVet(l10n) : _buildWaiting(l10n)),
      ),
    );
  }

  Widget _buildWaiting(AppLocalizations l10n) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 110, height: 110, alignment: Alignment.center,
              decoration: const BoxDecoration(color: AppColors.mintTint, shape: BoxShape.circle),
              child: const Text('🩺', style: TextStyle(fontSize: 44)),
            ),
            const SizedBox(height: 24),
            Text(l10n.vetWaitingTitle,
                key: const ValueKey('vetWaitingTitle'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 20, height: 1.3, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 8),
            Text(l10n.vetWaitingSubtitle,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
            const SizedBox(height: 28),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
              decoration: BoxDecoration(color: AppColors.cream2, borderRadius: BorderRadius.circular(14)),
              child: Column(
                children: [
                  Text(_mmss,
                      key: const ValueKey('vetWaitingCountdown'),
                      style: const TextStyle(
                          fontSize: 28, fontWeight: FontWeight.w700, letterSpacing: 2,
                          color: AppColors.mint, fontFeatures: [FontFeature.tabularFigures()])),
                  const SizedBox(height: 3),
                  Text(l10n.vetWaitingSearchLimit,
                      style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                ],
              ),
            ),
            const SizedBox(height: 28),
            TextButton(
              key: const ValueKey('vetWaitingCancel'),
              onPressed: _navigating ? null : _confirmCancel,
              child: Text(l10n.vetWaitingCancel,
                  style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildNoVet(AppLocalizations l10n) {
    return Center(
      key: const ValueKey('vetWaitingNoVet'),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 88, height: 88, alignment: Alignment.center,
              decoration: const BoxDecoration(color: Color(0xFFEFEDF3), shape: BoxShape.circle),
              child: const Text('⏳', style: TextStyle(fontSize: 38)),
            ),
            const SizedBox(height: 22),
            Text(l10n.vetWaitingNoVetTitle,
                key: const ValueKey('vetWaitingNoVetTitle'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 19, height: 1.3, fontWeight: FontWeight.w700, color: AppColors.ink)),
            const SizedBox(height: 8),
            Text(l10n.vetWaitingNoVetBody,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('vetWaitingBackHome'),
                onPressed: _navigating ? null : () { _navigating = true; context.go('/triage'); },
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint, foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 15),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                child: Text(l10n.vetWaitingBackHome,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
