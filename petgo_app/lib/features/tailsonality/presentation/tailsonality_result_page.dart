import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/domain/pet_profile.dart';
import '../data/tailsonality_providers.dart';
import '../domain/content/ts_dialog_copy.dart';
import '../domain/content/ts_roles.dart';
import '../domain/content/ts_text.dart';
import '../domain/tailsonality_result.dart';
import 'widgets/tailsonality_intro_sheet.dart';
import 'widgets/ts_locked_analysis.dart';
import 'widgets/ts_match_teaser.dart';
import 'widgets/ts_result_card.dart';
import 'widgets/ts_result_menu.dart';
import 'widgets/ts_rich_text.dart';

/// Tailsonality 结果页（V1.3.2 Story 2.4 · 免费态 · UX-DR6）。
///
/// 自上而下：结果卡（3:4，带水印）→ 免费摘要 → 配型引流 → 锁态区；底部**无**主 CTA（「Bagikan」属 Epic 4）。
/// 路由 `extra` 带 [TailsonalityResult] 时先用它首帧渲染；深链无 `extra` 照常按 token 取数。
class TailsonalityResultPage extends ConsumerWidget {
  const TailsonalityResultPage({super.key, required this.token, this.initial});

  final String token;
  final TailsonalityResult? initial;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(tailsonalityResultProvider(token));
    final petName = ref.watch(petProfileProvider).asData?.value?.name ?? '';
    // 答题页带过来的结果一直有效（刚由服务端创建）：后续取数失败 / 重试中都继续显示它，不被重试页顶掉。
    final shown = async.asData?.value ?? initial;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        title: Text(l10n.tailsonalityTitle),
        actions: [
          IconButton(
            key: const ValueKey('tsResultMore'),
            tooltip: l10n.tailsonalityMoreActions,
            constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
            icon: const Icon(Icons.more_horiz),
            onPressed: shown == null ? null : () => _openMenu(context, ref),
          ),
        ],
      ),
      body: shown != null
          ? _Body(result: shown, petName: petName)
          : async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => _isNotFound(e)
                  ? Center(
                      key: const ValueKey('tsResultNotFound'),
                      child: Text(l10n.tailsonalityResultNotFound, style: const TextStyle(color: AppColors.muted)),
                    )
                  : Center(
                      child: TextButton(
                        key: const ValueKey('tsResultRetry'),
                        onPressed: () => ref.invalidate(tailsonalityResultProvider(token)),
                        child: Text(l10n.commonRetry),
                      ),
                    ),
              data: (_) => const SizedBox.shrink(),
            ),
    );
  }

  static bool _isNotFound(Object e) => e is DioException && e.response?.statusCode == 404;

  void _openMenu(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    showTsResultMenu(context, [
      (
        key: const ValueKey('tsMenuRetake'),
        icon: Icons.refresh_rounded,
        label: l10n.tailsonalityMenuRetake,
        onTap: () => _confirmRetake(context, ref),
      ),
    ]);
  }

  /// 重测：免费、不限次、无任何次数提示（PRD §3.2）；确认文案取内容表 `kTsRetakeDialog`（内容设计 §2.6）。
  Future<void> _confirmRetake(BuildContext context, WidgetRef ref) async {
    final locale = Localizations.localeOf(context);
    final d = kTsRetakeDialog;
    final ok = await showConfirmSheet(
      context,
      title: d.title.of(locale),
      message: tsPlainText(d.body.of(locale)),
      confirmLabel: d.confirm.of(locale),
      cancelLabel: d.cancel.of(locale),
      icon: Icons.refresh_rounded,
    );
    if (!ok || !context.mounted) return;
    // 先拿到宠物再报埋点：档案取不到时抽屉开不了，这次重测并没有发生。
    PetProfile? pet = ref.read(petProfileProvider).asData?.value;
    if (pet == null && !ref.read(petProfileProvider).hasError) {
      try {
        pet = await ref.read(petProfileProvider.future).timeout(const Duration(seconds: 10));
      } catch (_) {
        pet = null;
      }
    }
    if (!context.mounted) return;
    if (pet == null) {
      showAppToast(context, AppLocalizations.of(context).growthLoadFailed);
      return;
    }
    Analytics.capture('tailsonality_retake_confirmed');
    await showTailsonalityIntroSheet(context, pet);
  }
}

class _Body extends StatelessWidget {
  const _Body({required this.result, required this.petName});

  final TailsonalityResult result;
  final String petName;

  @override
  Widget build(BuildContext context) {
    final locale = Localizations.localeOf(context);
    final role = kTsRoles[result.letters];
    return ListView(
      key: const ValueKey('tsResultBody'),
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
      children: [
        Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 360),
            child: TsResultCard(result: result, watermarked: !result.unlocked),
          ),
        ),
        const SizedBox(height: 18),
        if (role != null)
          TsRichText(
            tsFillPet(role.summary.of(locale), petName),
            key: const ValueKey('tsResultSummary'),
            style: const TextStyle(fontSize: 15, height: 1.55, color: AppColors.ink),
          ),
        const SizedBox(height: 16),
        // 配型引流放在锁态区之前（它免费，放在墙后等于没有）。本 story 静态、不可点；Story 2.5 接上。
        TsMatchTeaser(petLetters: result.letters),
        const SizedBox(height: 16),
        if (!result.unlocked)
          TsLockedAnalysis(
            typeCode: result.typeCode,
            letters: result.letters,
            energy: result.energy,
            petName: petName,
          ),
        // TODO(3.2): 已解锁 → 完整解读（专属深读 → 四段维度 → 能量段）。
      ],
    );
  }
}
