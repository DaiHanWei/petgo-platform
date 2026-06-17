import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/consult_repository.dart';

/// 等待界面（Story 5.3 F2/F3/F4）：「正在为你匹配兽医…」+ 轮询 + 1min 超时弹层 + 取消二次确认。
///
/// 超时**不迁移状态**（仍 WAITING）：弹「继续等待 / 先用 AI 分诊」。
/// 「先用 AI」→ 跳 FR-4A，原 WAITING 请求保留（不取消），兽医接单后推送通知。
class ConsultWaitingPage extends ConsumerStatefulWidget {
  const ConsultWaitingPage({super.key, required this.sessionId});

  final int sessionId;

  @override
  ConsumerState<ConsultWaitingPage> createState() => _ConsultWaitingPageState();
}

class _ConsultWaitingPageState extends ConsumerState<ConsultWaitingPage>
    with WidgetsBindingObserver {
  static const Duration _pollInterval = Duration(seconds: 3);

  Timer? _poll;
  Timer? _display; // 倒计时显示用本地 1s timer（不影响轮询/状态机）
  int _seconds = 0; // 已等待秒数（服务端 waitingElapsedSeconds 权威，本地逐秒推进）
  bool _timeoutShown = false;
  bool _navigating = false;

  /// AC7（F12）：退出取消的抑制位。接单成功 / 「先用 AI」保留请求 / 主动取消 时置真，
  /// 避免在这些已决态下因生命周期 detached 误触发取消。
  bool _exitCancelDisabled = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startPolling();
    // 显示用本地倒计时（逐秒，仅驱动 UI；轮询/超时/取消逻辑不依赖它）。
    _display = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() => _seconds++);
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
    // AC7（F12）：等待期间退出 App（含 kill 进程）→ 自动取消本次匹配，从待接单队列删除，兽医不会接到此单。
    // detached = 进程终止前最后信号；best-effort 复用既有 cancel 路径（无二次确认）。
    // 注：硬 SIGKILL 可能不触发 detached；可靠兜底需后端心跳/TTL（V1 禁中间件、从简，见 story Dev Notes）。
    if (state == AppLifecycleState.detached && !_exitCancelDisabled) {
      _exitCancelDisabled = true;
      _poll?.cancel();
      unawaited(_cancelOnExit());
    }
  }

  Future<void> _cancelOnExit() async {
    try {
      await ref.read(consultRepositoryProvider).cancel(widget.sessionId);
    } catch (_) {
      // 进程终止中无法保证送达；硬 kill 兜底依赖后端（见 Dev Notes）。
    }
  }

  void _startPolling() {
    _poll?.cancel();
    _poll = Timer.periodic(_pollInterval, (_) => _tick());
    _tick();
  }

  Future<void> _tick() async {
    try {
      final s = await ref.read(consultRepositoryProvider).get(widget.sessionId);
      if (!mounted) return;
      // 服务端等待秒数权威，回填本地倒计时显示。
      setState(() => _seconds = s.waitingElapsedSeconds);
      if (s.isInProgress) {
        // 接单 → 进会话界面（Story 5.5）。已决态，退出不再取消。
        _exitCancelDisabled = true;
        _poll?.cancel();
        if (mounted) context.go('/consult/conversation/${widget.sessionId}');
        return;
      }
      if (s.timedOut && !_timeoutShown) {
        _timeoutShown = true;
        _poll?.cancel();
        _showTimeoutSheet();
      }
    } catch (_) {
      // 轮询失败静默重试（下个 tick）。
    }
  }

  Future<void> _showTimeoutSheet() async {
    final l10n = AppLocalizations.of(context);
    await showModalBottomSheet<void>(
      context: context,
      isDismissible: false,
      enableDrag: false,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(l10n.consultTimeoutTitle, style: AppTypography.headline),
              const SizedBox(height: AppSpacing.sm),
              Text(l10n.consultTimeoutBody, style: AppTypography.body),
              const SizedBox(height: AppSpacing.section),
              FilledButton(
                key: const ValueKey('consultContinueWaiting'),
                onPressed: () {
                  Navigator.of(ctx).pop();
                  _continueWaiting();
                },
                child: Text(l10n.consultContinueWaiting),
              ),
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton(
                key: const ValueKey('consultUseAi'),
                onPressed: () {
                  Navigator.of(ctx).pop();
                  _useAi();
                },
                child: Text(l10n.consultUseAi),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _continueWaiting() async {
    try {
      await ref.read(consultRepositoryProvider).continueWaiting(widget.sessionId);
    } catch (_) {
      // 失败也恢复轮询，下次超时再弹。
    }
    if (!mounted) return;
    _timeoutShown = false;
    _startPolling();
  }

  void _useAi() {
    // 原 WAITING 请求保留（不取消）；跳分诊上传页，提示兽医接单后会通知。
    // 显式保留请求 → 退出时不自动取消（与 AC7 退出取消区分）。
    _exitCancelDisabled = true;
    final l10n = AppLocalizations.of(context);
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(l10n.consultUseAiKeptHint)));
    context.push('/triage/upload');
    // 留在等待页（返回时仍可见）；继续保留请求不轮询，避免重复弹。
  }

  Future<void> _confirmCancel() async {
    final l10n = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.consultCancelConfirmTitle),
        actions: [
          TextButton(
            key: const ValueKey('consultCancelConfirmNo'),
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(l10n.consultCancelConfirmNo),
          ),
          FilledButton(
            key: const ValueKey('consultCancelConfirmYes'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(l10n.consultCancelConfirmYes),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    _exitCancelDisabled = true; // 主动取消进行中，避免 detached 重复触发
    _poll?.cancel();
    try {
      await ref.read(consultRepositoryProvider).cancel(widget.sessionId);
    } catch (_) {
      // 取消失败也回主页（用户意图明确）；后端 TTL/状态以服务端为准。
    }
    if (!mounted) return;
    _navigating = true;
    context.go('/triage');
  }

  String get _mmss {
    final m = (_seconds ~/ 60).toString().padLeft(2, '0');
    final s = (_seconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 28),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                // 3 层脉冲环 + 中心呼吸医生头像（自绘，不引依赖）。
                const _MatchPulse(),
                const SizedBox(height: 24),
                Text(
                  l10n.consultMatching,
                  key: const ValueKey('consultMatching'),
                  style: const TextStyle(
                      fontSize: 20, height: 1.3, fontWeight: FontWeight.w700, color: AppColors.ink),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(l10n.consultMatchingSubtitle,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink2)),
                const SizedBox(height: 28),
                // 倒计时盒（已等待 MM:SS + 1 分钟搜索上限说明）。
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
                  decoration: BoxDecoration(
                      color: AppColors.cream2, borderRadius: BorderRadius.circular(14)),
                  child: Column(
                    children: [
                      Text(_mmss,
                          key: const ValueKey('consultCountdown'),
                          style: const TextStyle(
                              fontSize: 28,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 2,
                              color: AppColors.mint,
                              fontFeatures: [FontFeature.tabularFigures()])),
                      const SizedBox(height: 3),
                      Text(l10n.consultSearchLimit,
                          style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                    ],
                  ),
                ),
                const SizedBox(height: 28),
                TextButton(
                  key: const ValueKey('consultCancel'),
                  onPressed: _navigating ? null : _confirmCancel,
                  child: Text(l10n.consultCancelRequest,
                      style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 匹配脉冲动画（原型 match-wait）：3 层 matchPulse 扩散环（scale 1→1.9 交错延迟）
/// + 中心 breathGlow 呼吸医生头像。用单个 [AnimationController] 自绘，不引三方动画库。
class _MatchPulse extends StatefulWidget {
  const _MatchPulse();

  @override
  State<_MatchPulse> createState() => _MatchPulseState();
}

class _MatchPulseState extends State<_MatchPulse> with SingleTickerProviderStateMixin {
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 2400))..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 160,
      height: 160,
      child: AnimatedBuilder(
        animation: _c,
        builder: (context, child) {
          final breath = 0.28 + 0.27 * (0.5 + 0.5 * math.sin(_c.value * 2 * math.pi));
          return Stack(
            alignment: Alignment.center,
            children: [
              _ring(phase: 0.0, base: 80),
              _ring(phase: 1 / 3, base: 80),
              _ring(phase: 2 / 3, base: 80),
              // 中心呼吸医生头像。
              Container(
                width: 70,
                height: 70,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [AppColors.mint, AppColors.mint500],
                  ),
                  boxShadow: [
                    BoxShadow(
                        color: AppColors.mint.withValues(alpha: breath),
                        blurRadius: 28,
                        spreadRadius: 2),
                  ],
                ),
                child: const Text('🩺', style: TextStyle(fontSize: 30)),
              ),
            ],
          );
        },
      ),
    );
  }

  /// 单层扩散环：local t = (value + phase) % 1；scale 1→1.9，opacity 0.7→0。
  Widget _ring({required double phase, required double base}) {
    final t = (_c.value + phase) % 1.0;
    final scale = 1.0 + 0.9 * t;
    final opacity = 0.7 * (1 - t);
    return Opacity(
      opacity: opacity,
      child: Transform.scale(
        scale: scale,
        child: Container(
          width: base,
          height: base,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            border: Border.all(color: AppColors.mint.withValues(alpha: 0.4), width: 2),
          ),
        ),
      ),
    );
  }
}
