import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';

/// 评分结果（Story 5.6）：星级 1-5 必填 + ≤100 字选填。
class RatingResult {
  const RatingResult(this.stars, this.comment);

  final int stars;
  final String? comment;
}

/// 用户评分页（Story 5.6 F2/F3 · 原型 rate.html 1:1）。
///
/// 全屏评价页：兽医头像/姓名/时长 → 评分标题 → 1-5 星（必填）→ 快捷标签 chips（多选）→
/// ≤100 字备注（选填）→「Kirim Ulasan」→「Lewati」。星必选才可提交（客户端预校验仅体验，
/// 服务端权威）。快捷标签为原型呈现元素（不在数据模型）：选中标签随备注一并折叠进 comment
/// 单字段提交（不新增后端字段），≤100 字裁断。文案克制（UX-DR14）。
///
/// 类名/`show` 静态 API 与 [RatingResult] 契约保持不变（3 处调用点：triage 补弹 / 会话页结束 /
/// 入口延迟补弹），仅由 AlertDialog 改全屏页（fullscreenDialog 路由）。
class ConsultRatingDialog extends StatefulWidget {
  const ConsultRatingDialog({super.key});

  static Future<RatingResult?> show(BuildContext context, {bool barrierDismissible = true}) {
    return Navigator.of(context, rootNavigator: true).push<RatingResult>(
      MaterialPageRoute<RatingResult>(
        fullscreenDialog: true,
        builder: (_) => const ConsultRatingDialog(),
      ),
    );
  }

  @override
  State<ConsultRatingDialog> createState() => _ConsultRatingDialogState();
}

/// 快捷标签 emoji（可选）—— 原型呈现元素；label 由 l10n 解析（见 [_tagLabel]）。
const List<String?> _quickTagEmojis = [
  '👍',
  '📋',
  null,
  null,
  null,
];

/// 快捷标签本地化文案（与 [_quickTagEmojis] 同序）。
String _tagLabel(AppLocalizations l10n, int i) {
  switch (i) {
    case 0:
      return l10n.consultRateTagResponsive;
    case 1:
      return l10n.consultRateTagClearExplanation;
    case 2:
      return l10n.consultRateTagPatient;
    case 3:
      return l10n.consultRateTagFriendly;
    default:
      return l10n.consultRateTagProfessional;
  }
}

class _ConsultRatingDialogState extends State<ConsultRatingDialog> {
  static const Color _bg = Color(0xFFFBFAFD); // 原型 --color-bg-vet
  static const Color _heading = Color(0xFF2E2742);
  static const Color _secondary = Color(0xFF808080);
  static const Color _chipIdle = Color(0xFFF3F3F3); // 原型 --color-bg-muted
  static const Color _border = Color(0xFFE6E6E6);

  int _stars = 0;
  final _selected = <int>{};
  final _comment = TextEditingController();

  @override
  void dispose() {
    _comment.dispose();
    super.dispose();
  }

  /// 选中标签 + 备注折叠进单一 comment 字段（≤100 字裁断，不新增后端字段）。
  String? _composeComment(AppLocalizations l10n) {
    final parts = <String>[];
    final tags = [for (final i in (_selected.toList()..sort())) _tagLabel(l10n, i)];
    if (tags.isNotEmpty) parts.add(tags.join(', '));
    final typed = _comment.text.trim();
    if (typed.isNotEmpty) parts.add(typed);
    if (parts.isEmpty) return null;
    var combined = parts.join(' · ');
    if (combined.length > 100) combined = combined.substring(0, 100).trim();
    return combined;
  }

  void _submit() {
    final l10n = AppLocalizations.of(context);
    Navigator.of(context).pop(RatingResult(_stars, _composeComment(l10n)));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: _bg,
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 40, 24, 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 兽医头像（薄荷圆 + 浅边框）。占位内容。
              Center(
                child: Container(
                  width: 72,
                  height: 72,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: AppColors.vetPrimary,
                    shape: BoxShape.circle,
                    border: Border.all(color: AppColors.vetSurface, width: 3),
                  ),
                  child: const Text('D',
                      style: TextStyle(fontSize: 28, fontWeight: FontWeight.w700, color: Colors.white)),
                ),
              ),
              const SizedBox(height: 10),
              const Text('drh. Dewi Santoso',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: _heading)),
              const SizedBox(height: 3),
              const Text('Klinik Hewan Sehat · Durasi: 18 menit',
                  textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: _secondary)),
              const SizedBox(height: 24),
              Text(l10n.consultRateHeading,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: _heading)),
              const SizedBox(height: 5),
              Text(l10n.consultRateSubheading,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 13, color: _secondary, height: 1.5)),
              const SizedBox(height: 22),
              // 1-5 星（必填）。
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(5, (i) {
                  final filled = i < _stars;
                  return Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: GestureDetector(
                      key: ValueKey('ratingStar_${i + 1}'),
                      behavior: HitTestBehavior.opaque,
                      onTap: () => setState(() => _stars = i + 1),
                      child: Icon(
                        filled ? Icons.star_rounded : Icons.star_outline_rounded,
                        size: 42,
                        color: filled ? AppColors.triageYellow : const Color(0xFFD8D5DE),
                      ),
                    ),
                  );
                }),
              ),
              const SizedBox(height: 22),
              // 快捷标签 chips（多选）。
              Wrap(
                alignment: WrapAlignment.center,
                spacing: 8,
                runSpacing: 8,
                children: [
                  for (var i = 0; i < _quickTagEmojis.length; i++) _tagChip(l10n, i),
                ],
              ),
              const SizedBox(height: 18),
              // 备注（≤100 字选填）。
              Container(
                decoration: BoxDecoration(
                  color: AppColors.card,
                  borderRadius: BorderRadius.circular(13),
                  border: Border.all(color: _border, width: 1.5),
                ),
                padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 4),
                child: TextField(
                  key: const ValueKey('ratingComment'),
                  controller: _comment,
                  maxLength: 100,
                  minLines: 2,
                  maxLines: 4,
                  style: const TextStyle(fontSize: 13, color: _heading, height: 1.6),
                  decoration: InputDecoration(
                    isDense: true,
                    border: InputBorder.none,
                    counterText: '',
                    hintText: l10n.consultRateCommentHint,
                    hintStyle: const TextStyle(fontSize: 13, color: AppColors.muted, height: 1.6),
                  ),
                ),
              ),
              if (_stars == 0) ...[
                const SizedBox(height: 8),
                Text(l10n.consultRateStarsRequired,
                    textAlign: TextAlign.center,
                    style: AppTypography.disclaimer.copyWith(color: AppColors.danger)),
              ],
              const SizedBox(height: 20),
              // 主 CTA：星必选才可提交（服务端权威）。
              FilledButton(
                key: const ValueKey('ratingSubmit'),
                onPressed: _stars == 0 ? null : _submit,
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  foregroundColor: Colors.white,
                  disabledBackgroundColor: const Color(0xFFB6B6B6),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                ),
                child: Text(l10n.consultRateSubmitReview),
              ),
              const SizedBox(height: 11),
              // 跳过：返回 null（不提交）。
              Center(
                child: GestureDetector(
                  key: const ValueKey('ratingSkip'),
                  behavior: HitTestBehavior.opaque,
                  onTap: () => Navigator.of(context).pop(),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 16),
                    child: Text(l10n.consultRateSkip,
                        style: const TextStyle(fontSize: 13, color: AppColors.muted)),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tagChip(AppLocalizations l10n, int i) {
    final label = _tagLabel(l10n, i);
    final emoji = _quickTagEmojis[i];
    final on = _selected.contains(i);
    return GestureDetector(
      key: ValueKey('ratingTag_$i'),
      behavior: HitTestBehavior.opaque,
      onTap: () => setState(() => on ? _selected.remove(i) : _selected.add(i)),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 7),
        decoration: BoxDecoration(
          color: on ? AppColors.mint : _chipIdle,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          emoji == null ? label : '$label $emoji',
          style: TextStyle(
            fontSize: 12,
            fontWeight: on ? FontWeight.w600 : FontWeight.w400,
            color: on ? Colors.white : _secondary,
          ),
        ),
      ),
    );
  }
}
