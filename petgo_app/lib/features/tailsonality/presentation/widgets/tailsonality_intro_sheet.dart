import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../profile/domain/pet_profile.dart';
import '../tailsonality_routes.dart';

/// 测试说明抽屉（V1.3.2 Story 2.3 · AC2 · UX-DR3）：贴底抽屉，不是整页。
///
/// 视觉规格同 `shared/widgets/confirm_sheet.dart`（手柄 36×4、圆角 24、内边距 22/12/22/30、主按钮 radius 14）。
/// 「Mulai Tes」→ 关抽屉 → 进答题页。Story 2.4 / 2.6 的「Tes Ulang」复用本函数。
Future<void> showTailsonalityIntroSheet(BuildContext context, PetProfile pet) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => TailsonalityIntroSheet(
      pet: pet,
      onStart: () {
        Navigator.of(ctx).pop();
        context.push(TailsonalityRoutes.quiz);
      },
    ),
  );
}

/// 海报素材路径（16:9）。**素材未入库**（D-21）：缺失时画代码占位。
const String kTsIntroPosterAsset = 'assets/tailsonality/intro_poster.webp';

/// 「测的是谁、物种对不对」的唯一确认处（PRD §3.2）：宠物名与品种（或物种名）都必须出现。
String tailsonalityIntroSubject(AppLocalizations l10n, PetProfile pet) {
  final breed = pet.breed?.trim() ?? '';
  final shown = breed.isNotEmpty
      ? breed
      : switch (pet.petType) {
          'CAT' => l10n.petTypeCat,
          'DOG' => l10n.petTypeDog,
          _ => '',
        };
  return shown.isEmpty
      ? l10n.tailsonalityIntroSubjectNoBreed(pet.name)
      : l10n.tailsonalityIntroSubject(pet.name, shown);
}

class TailsonalityIntroSheet extends StatelessWidget {
  const TailsonalityIntroSheet({super.key, required this.pet, required this.onStart});

  final PetProfile pet;
  final VoidCallback onStart;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Container(
      key: const ValueKey('tsIntroSheet'),
      width: double.infinity,
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      padding: const EdgeInsets.fromLTRB(22, 12, 22, 30),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                margin: const EdgeInsets.only(bottom: 18),
                decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(99)),
              ),
            ),
            ClipRRect(
              borderRadius: BorderRadius.circular(16),
              child: AspectRatio(
                aspectRatio: 16 / 9,
                child: Image.asset(
                  kTsIntroPosterAsset,
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => Container(
                    key: const ValueKey('tsIntroPosterPlaceholder'),
                    color: AppColors.violet100,
                    alignment: Alignment.center,
                    child: const Icon(Icons.psychology_alt_outlined, size: 48, color: AppColors.mint),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 18),
            Text(l10n.tailsonalityTitle,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.ink)),
            const SizedBox(height: 6),
            Text(tailsonalityIntroSubject(l10n, pet),
                key: const ValueKey('tsIntroSubject'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.ink2)),
            const SizedBox(height: 4),
            Text(l10n.tailsonalityIntroMeta,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12.5, color: AppColors.textSecondary)),
            const SizedBox(height: 22),
            FilledButton(
              key: const ValueKey('tsIntroStart'),
              onPressed: onStart,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text(l10n.tailsonalityIntroStart,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
            ),
          ],
        ),
      ),
    );
  }
}
