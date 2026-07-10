import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/red_alert_overlay.dart';
import '../../profile/data/profile_repository.dart';
import '../data/triage_repository.dart';
import '../domain/triage_result_controller.dart';
import '../domain/triage_upload_controller.dart';

/// 红色结果（Story 4.5）。进入即自底滑起 [RedAlertOverlay] 半屏强提醒；
/// 🔒 **零兽医 CTA / 零变现引流 / 零地图导航 / 零医院推荐**（F3 · 去导航化），红色态唯一出口
/// 是单一「我已知晓」按钮。
///
/// 点「我已知晓」即**退出 AI 问诊**（与绿/黄结果页「完成」一致），不再停留任何红色摘要页。
/// overlay 之下/退出过渡时仅纯红占位，无摘要内容。
class TriageRedResult extends ConsumerStatefulWidget {
  const TriageRedResult(
      {super.key, required this.result, this.triageId, this.fromHistory = false});

  final TriageResult result;
  final int? triageId;

  /// 从历史记录回看（bug）：跳过强制阅读倒计时（历史不该再等），首次生成仍锁定。
  final bool fromHistory;

  @override
  ConsumerState<TriageRedResult> createState() => _TriageRedResultState();
}

class _TriageRedResultState extends ConsumerState<TriageRedResult> {
  bool _shown = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _showOverlayOnce());
  }

  Future<void> _showOverlayOnce() async {
    if (_shown || !mounted) return;
    _shown = true;
    final pet = await ref.read(petProfileProvider.future);
    if (!mounted) return;
    final l10n = AppLocalizations.of(context);
    final title =
        pet?.name != null ? l10n.triageRedTitle(pet!.name) : l10n.triageRedTitleNoPet;
    // GEJALA TERDETEKSI：优先 AI 摘要，回退用户输入的症状文本。
    final symptom = (widget.result.symptomSummary?.trim().isNotEmpty ?? false)
        ? widget.result.symptomSummary!.trim()
        : ref.read(triageUploadProvider).symptomText;
    // 决策 #5：全屏沉浸（opaque 红屏，barrier 不可点关，PopScope 锁返回键）。
    await showGeneralDialog<void>(
      context: context,
      barrierDismissible: false, // 🔒 背景点击不可关闭
      barrierColor: AppColors.triageRed,
      barrierLabel: l10n.triageRedLevelLabel,
      pageBuilder: (dialogCtx, _, _) => RedAlertOverlay(
        title: title,
        symptom: symptom,
        emergencySteps: widget.result.emergencySteps,
        emergencyAvoid: widget.result.emergencyAvoid,
        lockSeconds: widget.fromHistory ? 0 : 5,
        onAcknowledge: () => Navigator.of(dialogCtx).pop(),
      ),
    );
    // 「我已知晓」关闭 overlay 后：先同步清分诊结果态——否则重进上传页时首帧会用残留的旧 RED 结果
    // 渲染 TriageResultView，红色态再次注册 postFrame 弹出 overlay（重进分诊闪一次红色页的 bug）。
    if (!mounted) return;
    ref.read(triageResultProvider.notifier).reset();
    context.canPop() ? context.pop() : context.go('/triage');
  }

  @override
  Widget build(BuildContext context) {
    // overlay 覆盖全屏；其下 / 退出过渡时仅纯红占位（无摘要、无存档、无任何 CTA）。
    return const ColoredBox(
      key: ValueKey('triageRedSummary'),
      color: AppColors.triageRed,
      child: SizedBox.expand(),
    );
  }
}
