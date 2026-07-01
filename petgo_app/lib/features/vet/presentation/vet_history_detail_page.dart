import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../consult/domain/consult_diagnosis.dart';
import '../../consult/presentation/consult_diagnosis_view.dart';
import '../data/vet_repository.dart';
import '../domain/vet_workbench_lists.dart';
import 'vet_empty_state.dart';

/// 兽医「历史」卡「View」入口的只读问诊结果页（Bug 20260701-196）。
///
/// 兽医只读回看自己接诊会话的最终诊断，复用用户侧 [ConsultDiagnosisView] 平铺渲染。
/// 归属校验在后端（非本会话兽医 → 403）；无诊断（如 INTERRUPTED 未提交结束）→ 空态、不崩。
/// 诊断为健康数据：仅按需展示，不落日志。
class VetHistoryDetailPage extends ConsumerStatefulWidget {
  const VetHistoryDetailPage({super.key, required this.sessionId, this.entry});

  final int sessionId;

  /// 列表带入（供顶栏显示宠物名/机主）；深链无 extra 时为 null，仅按 sessionId 拉诊断。
  final VetHistoryEntry? entry;

  @override
  ConsumerState<VetHistoryDetailPage> createState() => _VetHistoryDetailPageState();
}

class _VetHistoryDetailPageState extends ConsumerState<VetHistoryDetailPage> {
  late final Future<ConsultDiagnosis?> _diagnosis;

  @override
  void initState() {
    super.initState();
    _diagnosis = ref.read(vetRepositoryProvider).diagnosis(widget.sessionId);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final entry = widget.entry;
    final subtitle = entry == null
        ? null
        : (entry.ownerHandle != null ? '${entry.petName} · @${entry.ownerHandle}' : entry.petName);
    return Scaffold(
      backgroundColor: AppColors.vetSurface2,
      appBar: AppBar(
        backgroundColor: AppColors.vetTopBar,
        foregroundColor: Colors.white,
        elevation: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.vetHistoryDetailTitle,
                style: AppTypography.title.copyWith(color: Colors.white, fontSize: 17)),
            if (subtitle != null)
              Text(subtitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.caption.copyWith(color: Colors.white.withValues(alpha: 0.6))),
          ],
        ),
      ),
      body: FutureBuilder<ConsultDiagnosis?>(
        future: _diagnosis,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          final d = snapshot.data;
          if (d == null) {
            return VetEmptyState(
              icon: Icons.description_outlined,
              message: l10n.vetHistoryNoDiagnosis,
            );
          }
          return ConsultDiagnosisView(diagnosis: d);
        },
      ),
    );
  }
}
