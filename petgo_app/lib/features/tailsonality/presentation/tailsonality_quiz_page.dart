import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/domain/pet_profile.dart';
import '../data/tailsonality_providers.dart';
import '../data/tailsonality_repository.dart';
import '../domain/content/ts_questions.dart';
import '../domain/content/ts_text.dart';
import '../domain/tailsonality_quiz_layout.dart';
import 'tailsonality_routes.dart';
import 'widgets/ts_generating_view.dart';
import 'widgets/ts_image_option_grid.dart';
import 'widgets/ts_option_tile.dart';

enum _Phase { answering, submitting, failed }

/// Tailsonality 答题页（V1.3.2 Story 2.3 · AC3–AC5）。
///
/// 🔴 **中途退出 = 放弃**（C-5）：答案只存在本页 State 的内存里，不落本地存储、不写服务端、不进 provider；
/// 下次进入从第 1 题开始。「生成中」视图因此在同一路由内切换，失败重试直接复用这份答案。
class TailsonalityQuizPage extends ConsumerStatefulWidget {
  const TailsonalityQuizPage({super.key, this.minGenerating = const Duration(milliseconds: 2500)});

  /// 「生成中」最短展示时长（避免闪一下就跳走）。测试可注入更短值。
  final Duration minGenerating;

  @override
  ConsumerState<TailsonalityQuizPage> createState() => _TailsonalityQuizPageState();
}

class _TailsonalityQuizPageState extends ConsumerState<TailsonalityQuizPage> {
  final Map<String, int> _answers = {};
  int _page = 0;
  _Phase _phase = _Phase.answering;
  final ScrollController _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    Analytics.capture('tailsonality_started');
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  bool get _pageComplete => kTsQuizPages[_page].every(_answers.containsKey);

  void _goToPage(int page) {
    setState(() {
      _page = page;
      if (_phase == _Phase.failed) _phase = _Phase.answering;
    });
    if (_scroll.hasClients) _scroll.jumpTo(0);
  }

  Future<void> _handleBack() async {
    if (_phase == _Phase.submitting) return; // 请求在飞，不给半途退出（AC5.5）。
    if (_page > 0) {
      _goToPage(_page - 1);
      return;
    }
    if (_answers.isEmpty) {
      Navigator.of(context).pop();
      return;
    }
    final l10n = AppLocalizations.of(context);
    final leave = await showConfirmSheet(
      context,
      title: l10n.tailsonalityQuizExitTitle,
      message: l10n.tailsonalityQuizExitBody,
      confirmLabel: l10n.tailsonalityQuizExitConfirm,
      cancelLabel: l10n.commonCancel,
      icon: Icons.logout_rounded,
    );
    if (leave && mounted) Navigator.of(context).pop();
  }

  Future<void> _submit() async {
    setState(() => _phase = _Phase.submitting);
    final repo = ref.read(tailsonalityRepositoryProvider);
    final body = {for (final q in kTsQuizPages.expand((p) => p)) q: _answers[q]!};
    try {
      final submitting = repo.submit(body);
      await Future.wait<void>([submitting, Future<void>.delayed(widget.minGenerating)]);
      final result = await submitting;
      Analytics.capture('tailsonality_completed', {'role_code': result.typeCode});
      ref.invalidate(tailsonalityResultsProvider);
      if (!mounted) return;
      context.pushReplacement(TailsonalityRoutes.result(result.token), extra: result);
    } catch (_) {
      if (!mounted) return;
      setState(() => _phase = _Phase.failed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final petAsync = ref.watch(petProfileProvider);
    final pet = petAsync.asData?.value;
    final petLoading = petAsync.isLoading && pet == null;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _handleBack();
      },
      child: Scaffold(
        backgroundColor: AppColors.cream,
        appBar: AppBar(
          backgroundColor: AppColors.cream,
          leading: IconButton(
            key: const ValueKey('tsQuizBack'),
            icon: const Icon(Icons.arrow_back),
            onPressed: _phase == _Phase.submitting ? null : _handleBack,
          ),
          centerTitle: true,
          title: _phase == _Phase.submitting
              ? null
              : Text(
                  l10n.tailsonalityQuizProgress(_page + 1, kTsQuizPages.length),
                  key: const ValueKey('tsQuizProgress'),
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
        ),
        body: pet == null
            ? (petLoading ? const Center(child: CircularProgressIndicator()) : _petUnavailable(l10n))
            : _phase == _Phase.submitting
                ? TsGeneratingView(petName: pet.name)
                : _questions(context, pet),
        bottomNavigationBar: pet == null || _phase == _Phase.submitting ? null : _bottomBar(l10n),
      ),
    );
  }

  /// 档案取失败 / 为空：给重试入口，不无限转圈（返回键照常可用）。
  Widget _petUnavailable(AppLocalizations l10n) => Center(
        key: const ValueKey('tsQuizPetUnavailable'),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(l10n.growthLoadFailed, style: const TextStyle(color: AppColors.muted)),
            const SizedBox(height: 8),
            TextButton(
              key: const ValueKey('tsQuizPetRetry'),
              onPressed: () => ref.invalidate(petProfileProvider),
              child: Text(l10n.commonRetry),
            ),
          ],
        ),
      );

  Widget _questions(BuildContext context, PetProfile pet) {
    final locale = Localizations.localeOf(context);
    final set = tsQuestionSetFor(pet.petType);
    final ids = kTsQuizPages[_page];
    final firstNumber = _page * 6 + 1;
    return ListView(
      key: const ValueKey('tsQuizList'),
      controller: _scroll,
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      children: [
        for (var i = 0; i < ids.length; i++)
          _QuestionBlock(
            key: ValueKey('tsQuestion_${ids[i]}'),
            number: firstNumber + i,
            question: kTsQuestions['$set.${ids[i]}']!,
            questionId: ids[i],
            petName: pet.name,
            locale: locale,
            selected: _answers[ids[i]],
            onSelect: (v) => setState(() => _answers[ids[i]] = v),
          ),
      ],
    );
  }

  Widget _bottomBar(AppLocalizations l10n) {
    final last = _page == kTsQuizPages.length - 1;
    final failed = _phase == _Phase.failed;
    final VoidCallback? onPressed = !_pageComplete
        ? null
        : failed || last
            ? _submit
            : () => _goToPage(_page + 1);
    return SafeArea(
      top: false,
      child: Container(
        key: const ValueKey('tsQuizBottomBar'),
        color: AppColors.cream,
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (failed) ...[
              Text(l10n.tailsonalitySubmitFailed,
                  key: const ValueKey('tsQuizSubmitFailed'),
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 12.5, color: AppColors.popRed)),
              const SizedBox(height: 8),
            ],
            FilledButton(
              key: const ValueKey('tsQuizPrimary'),
              onPressed: onPressed,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                minimumSize: const Size.fromHeight(48),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(
                failed ? l10n.commonRetry : (last ? l10n.tailsonalityQuizFinish : l10n.tailsonalityQuizNext),
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _QuestionBlock extends StatelessWidget {
  const _QuestionBlock({
    super.key,
    required this.number,
    required this.question,
    required this.questionId,
    required this.petName,
    required this.locale,
    required this.selected,
    required this.onSelect,
  });

  final int number;
  final TsQuestion question;
  final String questionId;
  final String petName;
  final Locale locale;
  final int? selected;
  final ValueChanged<int> onSelect;

  @override
  Widget build(BuildContext context) {
    final labels = [for (final o in question.options) tsFillPet(o.of(locale), petName)];
    return Padding(
      padding: const EdgeInsets.only(bottom: 22),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('$number. ${tsFillPet(question.stem.of(locale), petName)}',
              key: ValueKey('tsQuestionStem_$questionId'),
              style: const TextStyle(fontSize: 15, height: 1.4, fontWeight: FontWeight.w700, color: AppColors.ink)),
          const SizedBox(height: 10),
          if (question.imageGroup != null)
            TsImageOptionGrid(group: question.imageGroup!, labels: labels, selected: selected, onSelect: onSelect)
          else
            for (var i = 0; i < labels.length; i++) ...[
              TsOptionTile(
                key: ValueKey('tsOption_${questionId}_$i'),
                label: labels[i],
                selected: selected == i,
                onTap: () => onSelect(i),
              ),
              if (i < labels.length - 1) const SizedBox(height: 8),
            ],
        ],
      ),
    );
  }
}
