import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/initial_avatar.dart';
import '../../../shared/widgets/keyboard_suggest_panel.dart';
import '../data/mention_candidate_repository.dart';

/// @ 用户选择器面板（V1.3.0 batch-b1 Story 3.2 起；V1.4.2 social-topic Story 5.2 改为「打字即查」，UI E1–E6）。
///
/// <h3>正文 @ 后的字是唯一筛选条件</h3>
/// 面板里**没有**过滤框（V1.4.2 FR-157 移除「Cari nama…」）：`@` 后无字 → 快捷列表（E1）；
/// 有字 → 停 300ms 再按关键词查（E2 / E3 / E4），新请求发出时丢弃旧结果。
///
/// <h3>🔴 这仍然不是全局搜索</h3>
/// 接口只在**关注 / 粉丝 / 互动过**三类范围内查（Story 5.1）。所以：不放放大镜、不写「搜索用户」、
/// 查不到说「没有匹配的名字」而不是「没有找到该用户」。
///
/// <h3>失败 ≠ 没人</h3>
/// 取数失败（快捷列表或查询）落 E5「加载失败 + 重试」，**不再**落到 E6 空态 ——
/// 否则用户以为范围里真的没人（UI E5 说明）。
///
/// 查询结果按「这一次打字」存活在组件状态里，不进全局 provider（换账号不串号，story Dev Notes）。
class MentionPicker extends ConsumerStatefulWidget {
  const MentionPicker({
    super.key,
    required this.keyword,
    required this.onSelected,
    this.maxHeight = 220,
    this.docked = false,
    this.rowHorizontalPadding = AppSpacing.md,
  });

  /// 用户在正文里 `@` 后面已经打出来的字（来自 `MentionDraft.queryAt`）—— 唯一的筛选条件。
  final String keyword;

  /// 选中一个人。调用方负责把 `@昵称` 插进文本并记下 userId（见 `MentionDraft.insert`）。
  final void Function(MentionCandidate candidate) onSelected;

  final double maxHeight;

  /// 发布页通栏样式（贴键盘上沿，外壳 = 4.4 的 [KeyboardSuggestPanel]）：无圆角、无四周描边，
  /// 上沿 1px 描边 + 向上淡阴影。默认 false = 评论框现状外观（圆角卡片）。
  final bool docked;

  /// 候选行左右内边距：发布页 20、评论框 16。
  final double rowHorizontalPadding;

  /// 防抖窗口（同 shop_search_page 与 # 联想口径）。
  static const Duration debounce = Duration(milliseconds: 300);

  @override
  ConsumerState<MentionPicker> createState() => _MentionPickerState();
}

class _MentionPickerState extends ConsumerState<MentionPicker> {
  Timer? _debounce;
  CancelToken? _cancelToken;

  /// 请求序号：回包时序号不等即丢（旧响应晚到不得覆盖新结果）。
  int _seq = 0;

  /// 当前关键词的查询结果；null = 还没有结果（防抖中 / 请求中）。
  List<MentionCandidate>? _results;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    if (widget.keyword.isNotEmpty) _schedule();
  }

  @override
  void didUpdateWidget(MentionPicker oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 只在关键词真变化时重置；每次 build 不得发请求。
    if (oldWidget.keyword == widget.keyword) return;
    if (widget.keyword.isEmpty) {
      _abort();
    } else {
      _schedule();
    }
  }

  @override
  void dispose() {
    _abort();
    super.dispose();
  }

  /// 停掉计时器与在途请求，清空结果（旧结果作废）。
  void _abort() {
    _debounce?.cancel();
    _debounce = null;
    _seq++;
    _cancelToken?.cancel();
    _cancelToken = null;
    _results = null;
    _failed = false;
  }

  /// 关键词变化：立刻进 E2（不展示上一批结果），重置 300ms 计时。
  void _schedule() {
    _abort();
    _debounce = Timer(MentionPicker.debounce, _fetch);
  }

  Future<void> _fetch() async {
    _debounce = null;
    final int mySeq = ++_seq;
    final String keyword = widget.keyword;
    _cancelToken?.cancel();
    final CancelToken token = _cancelToken = CancelToken();
    try {
      final List<MentionCandidate> list =
          await ref.read(mentionCandidateRepositoryProvider).search(keyword, cancelToken: token);
      if (!mounted || mySeq != _seq) return;
      setState(() {
        _results = list;
        _failed = false;
      });
    } catch (e) {
      // 被 cancel 的请求静默忽略（新请求已接手）。
      if (e is DioException && e.type == DioExceptionType.cancel) return;
      if (!mounted || mySeq != _seq) return;
      setState(() {
        _results = null;
        _failed = true;
      });
    }
  }

  /// E5「Coba lagi」：关键词为空 → 重取快捷列表；非空 → 立即重查（不等 300ms）。
  void _retry() {
    if (widget.keyword.isEmpty) {
      ref.invalidate(mentionCandidatesProvider);
      return;
    }
    setState(_abort);
    _fetch();
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    // 快捷列表在面板存活期间一直 watch（autoDispose、不重试）：删回 `@` 后为空时直接回 E1，不另发请求。
    final AsyncValue<List<MentionCandidate>> quick = ref.watch(mentionCandidatesProvider);

    final Widget content;
    if (widget.keyword.isEmpty) {
      if (quick.isLoading && !quick.hasValue) {
        content = _loading();
      } else if (quick.hasError) {
        content = _error(l10n);
      } else {
        final List<MentionCandidate> all = quick.value ?? const <MentionCandidate>[];
        content = all.isEmpty
            ? _emptyState(l10n.mentionEmptyTitle, l10n.mentionEmptyHint, withIcon: true)
            : _list(all);
      }
    } else if (_failed) {
      content = _error(l10n);
    } else if (_results == null) {
      content = _loading();
    } else if (_results!.isEmpty) {
      // 查不到 ≠ 没有候选：说的是"这三类人里没有叫这个的"，
      // ⚠️ 不能说成"没有找到该用户"（那是在承诺能搜到任何人）。
      content = _emptyState(l10n.mentionNoMatch, null);
    } else {
      content = _list(_results!);
    }

    if (widget.docked) {
      return KeyboardSuggestPanel(
        key: const ValueKey('mentionPicker'),
        maxHeight: widget.maxHeight,
        child: content,
      );
    }
    return Container(
      key: const ValueKey('mentionPicker'),
      constraints: BoxConstraints(maxHeight: widget.maxHeight),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: AppRounded.lgRadius,
        border: Border.all(color: AppColors.border),
      ),
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [Flexible(child: content)],
      ),
    );
  }

  /// 原样按接口顺序渲染（排序与范围是 5.1 的服务端规则），本地不二次排序 / 过滤。
  Widget _list(List<MentionCandidate> list) => ListView.builder(
        shrinkWrap: true,
        padding: EdgeInsets.zero,
        itemCount: list.length,
        itemBuilder: (context, i) => _row(list[i]),
      );

  /// E2：一行高，居中 18px 转圈、无底圈（不设 backgroundColor）、内边距 12、无文案。
  Widget _loading() => const Padding(
        key: ValueKey('mentionPickerLoading'),
        padding: EdgeInsets.all(AppSpacing.md),
        // heightFactor: 1 —— 否则被外层 Flexible 包住会撑满 maxHeight（220），不是"一行高"（5.2 复审）。
        child: Center(
          heightFactor: 1,
          child: SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );

  /// E5：一行提示 + 紫色「Coba lagi」（可视高 33、热区 ≥ 44）。
  Widget _error(AppLocalizations l10n) => Padding(
        key: const ValueKey('mentionPickerError'),
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.detailNetworkError,
                style: AppTypography.body.copyWith(color: AppColors.ink2), textAlign: TextAlign.center),
            TextButton.icon(
              key: const ValueKey('mentionPickerRetry'),
              onPressed: _retry,
              style: TextButton.styleFrom(
                foregroundColor: AppColors.mint,
                minimumSize: const Size(0, 33),
                padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
                tapTargetSize: MaterialTapTargetSize.padded,
              ),
              icon: const Icon(Icons.refresh, size: 16),
              label: Text(l10n.commonRetry,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      );

  /// [withIcon]：只有「快捷列表一个人都没有」（E6）带人像图标；
  /// 「查不到」（E4）只是一行提示，不配图（删两个字就回来了）。
  Widget _emptyState(String title, String? hint, {bool withIcon = false}) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.md),
      child: Column(
        key: const ValueKey('mentionPickerEmpty'),
        mainAxisSize: MainAxisSize.min,
        children: [
          if (withIcon) ...[
            const Icon(Icons.people_outline_rounded,
                size: 30, color: AppColors.textTertiary),
            const SizedBox(height: AppSpacing.sm),
          ],
          Text(title,
              style: AppTypography.body.copyWith(color: AppColors.ink2),
              textAlign: TextAlign.center),
          if (hint != null) ...[
            const SizedBox(height: AppSpacing.xs),
            Text(hint, style: AppTypography.micro, textAlign: TextAlign.center),
          ],
        ],
      ),
    );
  }

  /// 行：28 头像 + 昵称 body 15，无等级徽章、不标关系类型（UI E1）。
  Widget _row(MentionCandidate c) {
    return InkWell(
      key: ValueKey('mentionCandidate_${c.userId}'),
      onTap: () => widget.onSelected(c),
      child: Padding(
        padding: EdgeInsets.symmetric(
            horizontal: widget.rowHorizontalPadding, vertical: AppSpacing.sm),
        child: Row(
          children: [
            InitialAvatar(nickname: c.nickname, avatarUrl: c.avatarUrl, radius: 14),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(c.nickname, style: AppTypography.body, overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
      ),
    );
  }
}
