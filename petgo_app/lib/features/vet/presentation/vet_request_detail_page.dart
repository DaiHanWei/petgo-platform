import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../data/vet_repository.dart';
import '../domain/vet_inbox_item.dart';

/// 请求详情 / 抢单预览页（Story 5.2 AC5 · 决策 F11）。
///
/// 抢单模式：从待接单列表点开某 WAITING 请求进入本页，**进入即开始 3 分钟预览计时**。
/// 兽医点「接单」走 5.3 的 DB 原子条件更新（先到先得，影响 0 行 = 已被抢）。预览期内三种返回列表态：
/// 1. 用户取消请求（轮询见 `CANCELLED`）→「此请求已关闭」→ 返回列表；
/// 2. 其他兽医率先接单（轮询见非 WAITING / 接单 409）→「此请求已被其他兽医接单」→ 返回列表；
/// 3. 3 分钟预览未操作 → 自动返回列表（请求继续对其他兽医可见，不被本兽医独占）。
///
/// 并发互斥语义由 5.3 的后端原子写承担；本页只消费「接单成功 / 已被抢 / 已关闭」三种响应（不引 MQ / 分布式锁）。
class VetRequestDetailPage extends ConsumerStatefulWidget {
  const VetRequestDetailPage({super.key, required this.item});

  /// 预览总时长（决策 F11：3 分钟）。
  static const Duration previewWindow = Duration(minutes: 3);

  /// 状态轮询间隔——检测用户取消 / 他人接单。
  static const Duration pollInterval = Duration(seconds: 5);

  final VetInboxItem item;

  @override
  ConsumerState<VetRequestDetailPage> createState() => _VetRequestDetailPageState();
}

class _VetRequestDetailPageState extends ConsumerState<VetRequestDetailPage> {
  Timer? _countdown;
  Timer? _poll;
  Duration _remaining = VetRequestDetailPage.previewWindow;
  bool _resolved = false; // 三态之一已触发后，封锁后续计时/轮询/接单
  bool _accepting = false;

  int get _sessionId => widget.item.sessionId;

  @override
  void initState() {
    super.initState();
    _countdown = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
    _poll = Timer.periodic(VetRequestDetailPage.pollInterval, (_) => _pollStatus());
  }

  @override
  void dispose() {
    _countdown?.cancel();
    _poll?.cancel();
    super.dispose();
  }

  void _tick() {
    if (_resolved) return;
    final next = _remaining - const Duration(seconds: 1);
    if (next <= Duration.zero) {
      // 状态 3：预览超时未操作 → 自动返回，不独占请求。
      _leave(AppLocalizations.of(context).vetRequestPreviewExpired);
      return;
    }
    setState(() => _remaining = next);
  }

  Future<void> _pollStatus() async {
    if (_resolved) return;
    try {
      final s = await ref.read(vetRepositoryProvider).session(_sessionId);
      if (_resolved || !mounted) return;
      if (s.status == 'CANCELLED') {
        // 状态 1：用户在预览期取消请求。
        _leave(AppLocalizations.of(context).vetRequestClosed);
      } else if (s.status != 'WAITING') {
        // 状态 2：他人率先接单（IN_PROGRESS/PENDING_CLOSE/...）。
        _leave(AppLocalizations.of(context).vetInboxTaken);
      }
    } on DioException {
      // 轮询失败静默重试（下一拍）；不打断预览。
    }
  }

  Future<void> _accept() async {
    if (_resolved || _accepting) return;
    setState(() => _accepting = true);
    final l10n = AppLocalizations.of(context);
    try {
      final session = await ref.read(vetRepositoryProvider).accept(_sessionId);
      if (!mounted) return;
      _resolved = true;
      _countdown?.cancel();
      _poll?.cancel();
      context.pushReplacement('/vet/conversation/${session.id}');
    } on DioException catch (e) {
      if (!mounted) return;
      if (e.response?.statusCode == 409) {
        // 状态 2：原子写命中——本兽医接单影响 0 行 → 已被其他兽医接单。
        _leave(l10n.vetInboxTaken);
      } else {
        setState(() => _accepting = false);
        ScaffoldMessenger.of(context)
          ..clearSnackBars()
          ..showSnackBar(SnackBar(content: Text(l10n.vetStatusUpdateFailed)));
      }
    }
  }

  /// 三种返回态共用：提示 + 返回列表（一次性，幂等）。
  void _leave(String message) {
    if (_resolved) return;
    _resolved = true;
    _countdown?.cancel();
    _poll?.cancel();
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(message)));
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/vet/workbench');
    }
  }

  String get _countdownLabel {
    final m = _remaining.inMinutes.toString().padLeft(2, '0');
    final s = (_remaining.inSeconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final item = widget.item;
    final isYellow = item.aiDangerLevel == 'YELLOW';
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.vetRequestDetailTitle),
        actions: [
          // 预览倒计时（MM:SS）——抢单 3 分钟窗口可见。
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
              child: Text(
                l10n.vetRequestPreviewTimeLeft(_countdownLabel),
                key: const ValueKey('vetPreviewCountdown'),
                style: AppTypography.caption.copyWith(color: AppColors.textSecondary),
              ),
            ),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(AppSpacing.md),
        children: [
          if (item.isAiUpgrade) ...[
            Text(
              isYellow ? l10n.vetAiContextLevelYellow : l10n.vetAiContextLevelGreen,
              style: AppTypography.caption.copyWith(
                color: isYellow ? AppColors.triageYellow : AppColors.triageGreen,
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
          ] else
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: Text(l10n.vetInboxDirect, style: AppTypography.body),
            ),
          Text(
            item.symptomPreview ?? l10n.vetRequestNoDetail,
            style: AppTypography.body,
          ),
          if (item.imageCount > 0) ...[
            const SizedBox(height: AppSpacing.sm),
            Text(l10n.vetInboxImages(item.imageCount), style: AppTypography.caption),
          ],
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: FilledButton(
            key: const ValueKey('vetRequestAccept'),
            onPressed: _accepting ? null : _accept,
            child: Text(l10n.vetInboxAccept),
          ),
        ),
      ),
    );
  }
}
