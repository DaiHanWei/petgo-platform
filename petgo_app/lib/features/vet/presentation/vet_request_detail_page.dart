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
import '../domain/consult_ai_context.dart';
import '../domain/vet_inbox_item.dart';
import 'vet_ai_context_card.dart';

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
  ConsultAiContext? _aiContext; // 病例真图(签名 URL):异步拉,到了换掉占位方块

  int get _sessionId => widget.item.sessionId;

  @override
  void initState() {
    super.initState();
    _countdown = Timer.periodic(const Duration(seconds: 1), (_) => _tick());
    _poll = Timer.periodic(VetRequestDetailPage.pollInterval, (_) => _pollStatus());
    _loadAiContext();
  }

  /// 拉病例真图(签名 URL):仅有照片时有意义;失败静默,回退占位。
  Future<void> _loadAiContext() async {
    if (widget.item.imageCount <= 0) return;
    try {
      final ctx = await ref.read(vetRepositoryProvider).aiContext(_sessionId);
      if (mounted) setState(() => _aiContext = ctx);
    } catch (_) {
      // 拉不到签名 URL → 保留占位方块,不崩。
    }
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

  /// 「Lewati」：不接单直接返回列表（请求继续对其他兽医可见）。dispose 负责清计时/轮询。
  void _skip() {
    if (_resolved || _accepting) return;
    if (context.canPop()) {
      context.pop();
    } else {
      context.go('/vet/workbench');
    }
  }

  // ── 等级映射（与待接单卡片同源）────────────────────────────────
  Color _levelColor() {
    switch (widget.item.aiDangerLevel) {
      case 'RED':
        return AppColors.triageRed;
      case 'YELLOW':
        return AppColors.triageYellow;
      default:
        return AppColors.triageGreen;
    }
  }

  String _levelEmoji() {
    switch (widget.item.aiDangerLevel) {
      case 'RED':
        return '🔴';
      case 'YELLOW':
        return '🟡';
      default:
        return '🟢';
    }
  }

  String _aiEvalTitle(AppLocalizations l10n) {
    switch (widget.item.aiDangerLevel) {
      case 'RED':
        return l10n.vetRequestAiEvalRed;
      case 'YELLOW':
        return l10n.vetRequestAiEvalYellow;
      default:
        return l10n.vetRequestAiEvalGreen;
    }
  }

  /// AI 评估框底色（tint）：黄→琥珀、红→珊瑚浅红、绿→薄荷派生。
  Color _aiBoxBg() {
    switch (widget.item.aiDangerLevel) {
      case 'RED':
        return AppColors.coralTint;
      case 'YELLOW':
        return AppColors.goldTint;
      default:
        return AppColors.triageGreen.withValues(alpha: 0.10);
    }
  }

  String _speciesEmoji() {
    switch (widget.item.petSpecies) {
      case 'CAT':
        return '🐱';
      case 'DOG':
        return '🐶';
      default:
        return '🐾';
    }
  }

  /// 宠物标签：种类 / 年龄，缺项跳过（无性别——后端不下发 petSex）。
  List<String> _tags(AppLocalizations l10n) {
    final tags = <String>[];
    switch (widget.item.petSpecies) {
      case 'CAT':
        tags.add(l10n.vetSpeciesCat);
      case 'DOG':
        tags.add(l10n.vetSpeciesDog);
    }
    final m = widget.item.petAgeMonths;
    if (m != null) tags.add(m >= 12 ? l10n.vetAgeYears(m ~/ 12) : l10n.vetAgeMonths(m));
    return tags;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final item = widget.item;
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      body: Column(
        children: [
          _header(l10n),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.md, AppSpacing.md, AppSpacing.lg),
              children: [
                if (item.petName != null) ...[
                  _petCard(l10n, item),
                  const SizedBox(height: AppSpacing.md),
                ],
                // 单一 symptomPreview 字段：AI 升级项 → AI 评估框承载；DIRECT → 主诉卡承载（不复制不臆造）。
                if (item.isAiUpgrade)
                  _aiEvalBox(l10n, item)
                else
                  _complaintCard(l10n, item),
                if (item.imageCount > 0) ...[
                  const SizedBox(height: AppSpacing.md),
                  _photosCard(l10n, item),
                ],
              ],
            ),
          ),
          _bottomBar(l10n),
        ],
      ),
    );
  }

  /// 深色顶栏 #2B2540：返回钮 + 标题/副标题 + 红色 3 分钟倒计时框。
  Widget _header(AppLocalizations l10n) {
    return Container(
      color: AppColors.vetTopBar,
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.md),
          child: Row(
            children: [
              InkWell(
                onTap: _skip,
                borderRadius: BorderRadius.circular(10),
                child: Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.arrow_back, size: 18, color: Colors.white),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(l10n.vetRequestDetailTitle,
                        style: AppTypography.title.copyWith(color: Colors.white)),
                    Text(l10n.vetRequestPreviewSubtitle,
                        style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.55))),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              // 红色倒计时框：MM:SS 大字 + sisa waktu 小字（保留 key 供测试）。
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: AppColors.triageRed,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Column(
                  children: [
                    Text(
                      _countdownLabel,
                      key: const ValueKey('vetPreviewCountdown'),
                      style: AppTypography.title.copyWith(
                          color: Colors.white, fontFeatures: const [FontFeature.tabularFigures()]),
                    ),
                    Text(l10n.vetRequestPreviewLabel,
                        style: AppTypography.micro.copyWith(color: Colors.white.withValues(alpha: 0.8))),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 宠物信息卡：头像渐变圈 + 名 + 标签 Wrap + 主人行。
  Widget _petCard(AppLocalizations l10n, VetInboxItem item) {
    final tags = _tags(l10n);
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: _cardDecoration(),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 56,
            height: 56,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: (item.isAiUpgrade ? _levelColor() : AppColors.muted).withValues(alpha: 0.12),
              shape: BoxShape.circle,
            ),
            child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 26)),
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(item.petName!, style: AppTypography.headline.copyWith(color: AppColors.ink)),
                if (tags.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 5,
                    runSpacing: 5,
                    children: [
                      for (final t in tags)
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                          decoration: BoxDecoration(
                            color: AppColors.vetSurface,
                            borderRadius: BorderRadius.circular(5),
                          ),
                          child: Text(t,
                              style: AppTypography.micro
                                  .copyWith(color: AppColors.vetPrimaryDeep, fontWeight: FontWeight.w600)),
                        ),
                    ],
                  ),
                ],
                if (item.ownerHandle != null) ...[
                  const SizedBox(height: 6),
                  Text('${l10n.vetRequestOwnerLabel} @${item.ownerHandle} · 🐾',
                      style: AppTypography.caption.copyWith(color: AppColors.textTertiary)),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// AI 分诊评估框（仅 AI 升级项）：图标 + 等级标题 + symptomPreview 正文。
  Widget _aiEvalBox(AppLocalizations l10n, VetInboxItem item) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: _aiBoxBg(),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(_levelEmoji(), style: const TextStyle(fontSize: 18)),
              const SizedBox(width: 8),
              Expanded(
                child: Text(_aiEvalTitle(l10n),
                    style: AppTypography.body.copyWith(color: _levelColor(), fontWeight: FontWeight.w700)),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(item.symptomPreview ?? l10n.vetRequestNoDetail,
              style: AppTypography.body.copyWith(color: AppColors.ink, height: 1.6)),
        ],
      ),
    );
  }

  /// 主诉卡（DIRECT 项）：KELUHAN PEMILIK 标签 + 主诉正文。
  Widget _complaintCard(AppLocalizations l10n, VetInboxItem item) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: _cardDecoration(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetRequestComplaintLabel,
              style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: 8),
          Text(item.symptomPreview ?? l10n.vetRequestNoDetail,
              style: AppTypography.body.copyWith(color: AppColors.ink, height: 1.65)),
        ],
      ),
    );
  }

  /// 症状图片卡：FOTO GEJALA (n) + 占位缩略格（最多 3 格并排）。
  Widget _photosCard(AppLocalizations l10n, VetInboxItem item) {
    final urls = _aiContext?.imageUrls ?? const <String>[];
    final shown = item.imageCount.clamp(0, 3);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: _cardDecoration(),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(l10n.vetRequestPhotosLabel(item.imageCount),
              style: AppTypography.micro.copyWith(color: AppColors.textTertiary, letterSpacing: 0.5)),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              for (var i = 0; i < shown; i++) ...[
                if (i > 0) const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: AspectRatio(
                    aspectRatio: 1,
                    child: i < urls.length
                        // 真图(签名 URL):点开看大图。
                        ? GestureDetector(
                            onTap: () => showCaseImageFullScreen(context, urls[i]),
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(12),
                              child: Image.network(
                                urls[i],
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) => _photoPlaceholder(i),
                              ),
                            ),
                          )
                        // 未拉到签名 URL(加载中/失败)→ 占位方块。
                        : _photoPlaceholder(i),
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }

  /// 占位缩略(原型 FOTO GEJALA 紫/黄渐变交替),真图未就绪时兜底。
  Widget _photoPlaceholder(int i) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: i.isEven
              ? const [AppColors.skyTint, AppColors.dashedViolet]
              : const [AppColors.goldTint, AppColors.gold],
        ),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(_speciesEmoji(), style: const TextStyle(fontSize: 30)),
    );
  }

  /// 底部双钮：Lewati（outline）+ Ambil Kasus（薄荷主钮，保留 key）。
  Widget _bottomBar(AppLocalizations l10n) {
    return Material(
      color: AppColors.surface,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: [
              Expanded(
                flex: 1,
                child: OutlinedButton(
                  onPressed: _accepting ? null : _skip,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.textSecondary,
                    side: BorderSide(color: AppColors.border),
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    // 原型 vet-case：圆角矩形 13px（非默认 StadiumBorder 半圆）。
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
                  ),
                  child: Text(l10n.vetQueueSkip),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                flex: 2,
                child: FilledButton(
                  key: const ValueKey('vetRequestAccept'),
                  onPressed: _accepting ? null : _accept,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.vetPrimary,
                    foregroundColor: AppColors.vetOnAccent,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(13)),
                  ),
                  child: Text(l10n.vetRequestAcceptCta),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  BoxDecoration _cardDecoration() => BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: AppColors.ink.withValues(alpha: 0.06),
            blurRadius: 12,
            offset: const Offset(0, 3),
          ),
        ],
      );
}
