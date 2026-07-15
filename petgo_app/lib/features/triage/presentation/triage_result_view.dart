import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/triage_repository.dart';
import '../domain/triage_archive.dart';
import '../domain/triage_unlock_controller.dart';
import '../domain/triage_upload_controller.dart';
import '../domain/triage_wording_guard.dart';
import 'triage_red_result.dart';
import 'widgets/triage_paywall.dart';

/// Debug 截图钩子一次性 guard（DEV_ARCHIVE_PROMPT 在结果就绪后自动弹存档确认，截 archive-confirm 用）。
bool _devArchiveShown = false;

const Color _greenLight = Color(0xFF56D4A0);
const Color _yellowLight = Color(0xFFFFD166);
// RINGKASAN GEJALA 卡按等级取浅色底 + 深色标题（原型 ai-result #FEF3DE/#8A5A00、ai-result-green #EDFBF4/#136B41）。
const Color _yellowSummaryBg = Color(0xFFFEF3DE);
const Color _yellowSummaryLabel = Color(0xFF8A5A00);
const Color _greenSummaryBg = Color(0xFFEDFBF4);
const Color _greenSummaryLabel = Color(0xFF136B41);

/// 分诊绿/黄结果（Story 4.4 · 原型 P-19 黄 / P-19b 绿）：实色渐变等级 header（白字居中）+
/// RINGKASAN GEJALA 症状摘要 + 居家护理建议 + 黄色观察协议三要素 + 前置免责 + 分级 CTA。
/// 红色一律交棒 4.5 全屏强提醒（[TriageRedResult]），本视图不软化渲染。
class TriageResultView extends ConsumerWidget {
  const TriageResultView(
      {super.key, required this.result, this.triageId, this.fromHistory = false});

  final TriageResult result;
  final int? triageId;

  /// 从历史记录回看：红色态跳过强制阅读倒计时（历史不该再等）。首次生成保持锁定。
  final bool fromHistory;
  /// 解锁后用已解锁结果覆盖（Story 2.4）：现金/同步解锁成功 → 结果页去 paywall、显详建。
  TriageResult _resolveResult(WidgetRef ref) {
    final int? id = triageId;
    if (id == null) return result;
    final TriageUnlockState st = ref.watch(triageUnlockControllerProvider);
    return st.isUnlockedFor(id) ? st.result! : result;
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final TriageResult result = _resolveResult(ref);
    final level = result.dangerLevel;

    // 🔒 红色走 4.5 全屏强提醒（自底滑起 overlay）+ 关闭后保留零兽医/零变现红色摘要。红色永不锁（不接 paywall）。
    if (level == DangerLevel.red || level == null) {
      return TriageRedResult(result: result, triageId: triageId, fromHistory: fromHistory);
    }

    final isYellow = level == DangerLevel.yellow;
    final accent = isYellow ? AppColors.triageYellow : AppColors.triageGreen;
    // 终结性表述守卫：模型若吐出「不严重/可以放心」等，拦截降级为中性提示。
    final advice = TriageWordingGuard.sanitize(result.advice, fallback: l10n.triageNeutralAdvice);
    // RINGKASAN GEJALA：优先 AI 摘要，回退用户输入的症状文本。
    final summary = (result.symptomSummary?.trim().isNotEmpty ?? false)
        ? result.symptomSummary!.trim()
        : ref.read(triageUploadProvider).symptomText.trim();

    // Debug 截图钩子（仅 debug + flag）：结果就绪后自动弹存档确认（截 archive-confirm 用）。
    if (kDebugMode && const bool.fromEnvironment('DEV_ARCHIVE_PROMPT') && !_devArchiveShown) {
      _devArchiveShown = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        ref.read(triageArchiveHandlerProvider)(context, ref,
            triageId: triageId, level: level, advice: result.advice, symptom: summary);
      });
    }

    void done() => context.canPop() ? context.pop() : context.go('/triage');

    return ListView(
      key: ValueKey(isYellow ? 'triageYellowPage' : 'triageGreenPage'),
      padding: EdgeInsets.zero,
      children: <Widget>[
        // —— 实色渐变等级 Header（原型 P-19/P-19b：白字居中，52px emoji 无圈）——
        _LevelHeader(
          emoji: isYellow ? '🟡' : '🟢',
          gradient: isYellow
              ? const [AppColors.triageYellow, _yellowLight]
              : const [AppColors.triageGreen, _greenLight],
          title: isYellow ? l10n.triageYellowHeadline : l10n.triageGreenHeadline,
          subtitle: isYellow ? l10n.triageYellowSubhead : l10n.triageGreenSubhead,
          onBack: done,
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              // RINGKASAN GEJALA（症状摘要）。
              if (summary.isNotEmpty) ...<Widget>[
                _SectionCard(
                  label: l10n.triageSummaryLabel,
                  background: isYellow ? _yellowSummaryBg : _greenSummaryBg,
                  labelColor: isYellow ? _yellowSummaryLabel : _greenSummaryLabel,
                  child: Text(summary,
                      style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.ink)),
                ),
                const SizedBox(height: 12),
              ],
              // 居家护理建议（SARAN PERAWATAN）。Story 2.4：锁定态（非红 + 后端 locked）→ paywall 占位 + CTA；
              // 否则原详建要点。安全免费部分（摘要/观察/免责/CTA）不受影响。
              _SectionCard(
                label: isYellow ? l10n.triageCareLabel : l10n.triageHomeCareLabel,
                child: (result.isDetailLocked && triageId != null)
                    ? TriagePaywall(triageId: triageId!)
                    : Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          for (final line in _bullets(advice)) _Bullet(line, accent: accent),
                          if (isYellow && result.medicationRef != null) ...<Widget>[
                            const SizedBox(height: 10),
                            Text(l10n.triageMedicationRefLabel,
                                style: const TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w700,
                                    color: AppColors.muted)),
                            const SizedBox(height: 3),
                            Text(result.medicationRef!,
                                style: const TextStyle(
                                    fontSize: 13, height: 1.5, color: AppColors.ink)),
                          ],
                        ],
                      ),
              ),
              // 黄色：观察协议三要素（指标 chips + 时间窗口卡 + 升级触发卡）。
              if (isYellow && (result.observation?.hasContent ?? false)) ...<Widget>[
                const SizedBox(height: 12),
                _ProtocolBlock(observation: result.observation!),
              ],
              const SizedBox(height: 16),
              // 前置免责（NFR-9）。
              Text(result.disclaimer ?? l10n.triageDisclaimer,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 11, height: 1.55, color: AppColors.muted)),
              const SizedBox(height: 18),
              // —— 分级 CTA ——
              // 黄（原型 ai-result 仅 2 钮）：Konsultasi → 咨询入口 P-20；Selesai → 先弹存档确认 P-25 再退出。
              // 绿（原型 ai-result-green 3 钮）：Simpan 存档 / Tetap Konsultasi → P-20 / Selesai 退出。
              if (isYellow) ...<Widget>[
                _filledBtn(
                  btnKey: const ValueKey('triageConsultVet'),
                  label: '💬 ${l10n.triageConsultNow}',
                  color: AppColors.mint,
                  onTap: () {
                    Analytics.capture('consult_started');
                    context.push(_consultRoute());
                  },
                ),
                _outlineBtn(
                  btnKey: const ValueKey('triageDone'),
                  label: l10n.triageDone,
                  border: AppColors.line,
                  text: AppColors.textSecondary,
                  onTap: () => _archiveThenExit(context, ref, level, summary, done),
                ),
              ] else ...<Widget>[
                _filledBtn(
                  btnKey: const ValueKey('triageSaveToArchive'),
                  label: '📋 ${l10n.triageSaveHealthNote}',
                  color: AppColors.triageGreen,
                  gradient: const LinearGradient(colors: [AppColors.triageGreen, _greenLight]),
                  onTap: () => _archive(context, ref, level, summary),
                ),
                _outlineBtn(
                  btnKey: const ValueKey('triageConsultVet'),
                  label: '💬 ${l10n.triageConsultStill}',
                  border: AppColors.triageGreen,
                  text: AppColors.triageGreen,
                  onTap: () {
                    Analytics.capture('consult_started');
                    context.push(_consultRoute());
                  },
                ),
                _outlineBtn(
                  btnKey: const ValueKey('triageDone'),
                  label: l10n.triageDone,
                  border: AppColors.line,
                  text: AppColors.textSecondary,
                  onTap: done,
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  /// 咨询兽医路由：带 triageId → AI_UPGRADE 升级（后端拉 AI 描述/图片/危险等级绑定兽医会话，
  /// bug 20260702-235）；无 triageId 退回普通直连入口。
  String _consultRoute() =>
      triageId == null ? '/consult' : '/consult?triageTaskId=$triageId';

  void _archive(BuildContext context, WidgetRef ref, DangerLevel level, String summary) =>
      ref.read(triageArchiveHandlerProvider)(context, ref,
          triageId: triageId, level: level, advice: result.advice, symptom: summary);

  /// 黄色「Selesai」：先弹 P-25 存档确认（已建档存/跳过、未建档引导），解决后退出分诊。
  /// triageId 为空 / 已提示过时 handler 自身 no-op，仍照常退出。
  Future<void> _archiveThenExit(BuildContext context, WidgetRef ref, DangerLevel level,
      String summary, VoidCallback exit) async {
    await ref.read(triageArchiveHandlerProvider)(context, ref,
        triageId: triageId, level: level, advice: result.advice, symptom: summary);
    if (context.mounted) exit();
  }

  /// 实色/渐变主按钮（全宽 + 阴影）。
  Widget _filledBtn({
    required Key btnKey,
    required String label,
    required Color color,
    Gradient? gradient,
    required VoidCallback onTap,
  }) =>
      Container(
        margin: const EdgeInsets.only(bottom: 10),
        decoration: BoxDecoration(
          color: gradient == null ? color : null,
          gradient: gradient,
          borderRadius: BorderRadius.circular(14),
          boxShadow: [
            BoxShadow(color: color.withValues(alpha: 0.28), blurRadius: 16, offset: const Offset(0, 6)),
          ],
        ),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: btnKey,
            onTap: onTap,
            borderRadius: BorderRadius.circular(14),
            child: Container(
              height: 50,
              alignment: Alignment.center,
              child: Text(label,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Colors.white)),
            ),
          ),
        ),
      );

  /// 描边次按钮（全宽）。
  Widget _outlineBtn({
    required Key btnKey,
    required String label,
    required Color border,
    required Color text,
    required VoidCallback onTap,
  }) =>
      Container(
        margin: const EdgeInsets.only(bottom: 10),
        child: OutlinedButton(
          key: btnKey,
          onPressed: onTap,
          style: OutlinedButton.styleFrom(
            foregroundColor: text,
            side: BorderSide(color: border, width: 1.5),
            padding: const EdgeInsets.symmetric(vertical: 13),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          child: Text(label, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        ),
      );

  /// 建议文本拆要点：按换行 / 「•」/ 「·」分行，去空；无可拆则整段一条。
  static List<String> _bullets(String advice) {
    final lines = advice
        .split(RegExp(r'[\n•·]'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
    return lines.isEmpty ? <String>[advice] : lines;
  }
}

/// 实色渐变等级 header（原型 P-19/P-19b）：返回钮(左上,白半透) + 右上装饰圆 +
/// 居中 52px emoji + 白色标题 + 白色副文案。
class _LevelHeader extends StatelessWidget {
  const _LevelHeader({
    required this.emoji,
    required this.gradient,
    required this.title,
    required this.subtitle,
    required this.onBack,
  });

  final String emoji;
  final List<Color> gradient;
  final String title;
  final String subtitle;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: Container(
        width: double.infinity,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: gradient,
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
        ),
        child: Stack(
          children: [
            // 右上装饰圆（白 12%）。
            Positioned(
              top: -30,
              right: -30,
              child: Container(
                width: 160,
                height: 160,
                decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.12), shape: BoxShape.circle),
              ),
            ),
            SafeArea(
              bottom: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: InkWell(
                        key: const ValueKey('triageResultBack'),
                        onTap: onBack,
                        borderRadius: BorderRadius.circular(10),
                        child: Container(
                          width: 34,
                          height: 34,
                          alignment: Alignment.center,
                          decoration: BoxDecoration(
                              color: Colors.white.withValues(alpha: 0.25),
                              borderRadius: BorderRadius.circular(10)),
                          child: const Icon(Icons.arrow_back, size: 18, color: Colors.white),
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(emoji, textAlign: TextAlign.center, style: const TextStyle(fontSize: 52)),
                    const SizedBox(height: 6),
                    Text(title,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            fontSize: 21, fontWeight: FontWeight.w700, height: 1.25, color: Colors.white)),
                    const SizedBox(height: 6),
                    Text(subtitle,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            fontSize: 13, height: 1.6, color: Colors.white.withValues(alpha: 0.9))),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 白底圆角分区卡：大写灰小标题 + 内容。
class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.label,
    required this.child,
    this.background,
    this.labelColor,
  });

  final String label;
  final Widget child;

  /// 浅色底（RINGKASAN GEJALA 用）；null=白卡。浅色底不带阴影，白卡保留阴影（原型）。
  final Color? background;
  final Color? labelColor;

  @override
  Widget build(BuildContext context) {
    final tinted = background != null;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: background ?? AppColors.card,
        borderRadius: BorderRadius.circular(14),
        boxShadow: tinted
            ? null
            : const [
                BoxShadow(color: Color(0x14162233), blurRadius: 12, offset: Offset(0, 4)),
              ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label,
              style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.5,
                  color: labelColor ?? AppColors.muted)),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }
}

/// 护理建议要点行：accent 圆点 + 文案。
class _Bullet extends StatelessWidget {
  const _Bullet(this.text, {required this.accent});

  final String text;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            margin: const EdgeInsets.only(top: 6),
            width: 6,
            height: 6,
            decoration: BoxDecoration(color: accent, shape: BoxShape.circle),
          ),
          const SizedBox(width: 9),
          Expanded(
            child: Text(text,
                style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink)),
          ),
        ],
      ),
    );
  }
}

/// 黄色观察协议块（FR-2 · 原型 PROTOKOL OBSERVASI）：观察指标 chips + 时间窗口卡 + 升级触发卡。
/// 底色 #EEF4F7（triageYellowSurface），key=triageProtocolBlock（契约测试钉）。
class _ProtocolBlock extends StatelessWidget {
  const _ProtocolBlock({required this.observation});

  final TriageObservation observation;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('triageProtocolBlock'),
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.triageYellowSurface, // #EEF4F7
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(l10n.triageObservationTitle.toUpperCase(),
              style: const TextStyle(
                  fontSize: 11, fontWeight: FontWeight.w700, letterSpacing: 0.5, color: AppColors.ink2)),
          if (observation.indicators.isNotEmpty) ...<Widget>[
            const SizedBox(height: 10),
            Row(children: [
              const Text('👁 ', style: TextStyle(fontSize: 13)),
              Text(l10n.triageIndicatorsLabel,
                  style: const TextStyle(
                      fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.ink)),
            ]),
            const SizedBox(height: 8),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: [for (final s in observation.indicators) _Chip(s)],
            ),
          ],
          if (observation.timeWindow != null && observation.timeWindow!.isNotEmpty) ...<Widget>[
            const SizedBox(height: 12),
            _IconCard(
              emoji: '⏱',
              label: l10n.triageTimeWindowLabel,
              value: observation.timeWindow!,
              valueColor: AppColors.ink,
            ),
          ],
          if (observation.escalationTriggers.isNotEmpty) ...<Widget>[
            const SizedBox(height: 8),
            _IconCard(
              emoji: '🚨',
              label: l10n.triageEscalationLabel,
              value: observation.escalationTriggers.join(' · '),
              valueColor: AppColors.popRed,
              bold: true,
            ),
          ],
        ],
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
      decoration: BoxDecoration(
          color: AppColors.surface, borderRadius: BorderRadius.circular(9999)),
      child: Text(text, style: const TextStyle(fontSize: 12, color: AppColors.ink)),
    );
  }
}

/// 协议高亮卡（时间窗口 / 升级触发）：白底圆角 + emoji + 小标题 + 值。
class _IconCard extends StatelessWidget {
  const _IconCard({
    required this.emoji,
    required this.label,
    required this.value,
    required this.valueColor,
    this.bold = false,
  });

  final String emoji;
  final String label;
  final String value;
  final Color valueColor;
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 11),
      decoration: BoxDecoration(
          color: AppColors.surface, borderRadius: BorderRadius.circular(11)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('$emoji ', style: const TextStyle(fontSize: 15)),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label,
                    style: const TextStyle(
                        fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.muted)),
                const SizedBox(height: 2),
                Text(value,
                    style: TextStyle(
                        fontSize: 13,
                        height: 1.4,
                        fontWeight: bold ? FontWeight.w700 : FontWeight.w500,
                        color: valueColor)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
