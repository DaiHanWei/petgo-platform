import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/triage_repository.dart';
import 'triage_result_view.dart';

/// AI 分诊历史结果快照（bug 20260702-238/228）：从问诊历史点某条 AI 记录进入，
/// 按 triageId 拉后端 `GET /triage/{id}` 完整结果，复用 [TriageResultView] 只读呈现
/// （含 advice 建议摘要；红色态自动交棒 TriageRedResult）。
///
/// [symptomSummary] 由历史条目带入：后端结果响应不回传 symptomSummary，若不覆盖，
/// 结果视图会回退到当前 upload 草稿的症状文本 → 历史回看串味，故这里显式补齐。
class TriageSnapshotPage extends ConsumerStatefulWidget {
  const TriageSnapshotPage({super.key, required this.triageId, this.symptomSummary});

  final int triageId;
  final String? symptomSummary;

  @override
  ConsumerState<TriageSnapshotPage> createState() => _TriageSnapshotPageState();
}

class _TriageSnapshotPageState extends ConsumerState<TriageSnapshotPage> {
  late Future<TriageResult> _future;

  @override
  void initState() {
    super.initState();
    _future = ref.read(triageRepositoryProvider).pollTriage(widget.triageId);
  }

  void _reload() {
    setState(() {
      _future = ref.read(triageRepositoryProvider).pollTriage(widget.triageId);
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.surface,
      body: FutureBuilder<TriageResult>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          final result = snap.data;
          // 网络错误，或历史记录非 DONE（理论上历史恒 DONE，兜底防呆）→ 可返回的失败态。
          if (snap.hasError || result == null || result.status != TriageStatus.done) {
            return _ErrorState(message: l10n.detailNetworkError, onRetry: _reload);
          }
          return TriageResultView(
            result: result.copyWith(symptomSummary: widget.symptomSummary),
            triageId: widget.triageId,
          );
        },
      ),
    );
  }
}

/// 失败态：返回箭头 + 提示 + 重试（与结果视图的顶栏返回口径一致，用 pop）。
class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.all(8),
            child: IconButton(
              key: const ValueKey('triageSnapshotBack'),
              icon: const Icon(Icons.arrow_back),
              onPressed: () => context.canPop() ? context.pop() : context.go('/triage'),
            ),
          ),
          Expanded(
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  Text(message, style: const TextStyle(color: AppColors.muted)),
                  const SizedBox(height: 12),
                  OutlinedButton(
                    key: const ValueKey('triageSnapshotRetry'),
                    onPressed: onRetry,
                    child: Text(l10n.publishRetry),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
