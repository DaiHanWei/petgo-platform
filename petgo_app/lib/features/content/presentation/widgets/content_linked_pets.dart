import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../profile/domain/pet_age.dart';
import '../../../profile/presentation/visitor_archive_view.dart';
import '../../../profile/presentation/widgets/pet_avatar_stack.dart';
import '../../../profile/presentation/widgets/pet_names.dart';
import '../../../profile/presentation/widgets/pet_picker_sheet.dart';
import '../../domain/content_pet_ref.dart';

/// 访客视图 `?from=` 的新取值（AC4.7 / AC7.3，`diary_visitor_viewed.from`）。
const String kVisitorFromContentDetail = 'content_detail';

/// 胶囊固定高度：头像环扣 24 + 2 白边 ×2 = 28，上下内边距各 4。
const double kLinkedPetsCapsuleHeight = 36;

/// 详情页「关联宠物」胶囊（V1.4.0 multi-pet Story 3.3 · AC4 · UI 稿 F6 / F6b / F6c `.pkchip`）。
///
/// - [dark] = 有图（F6）：叠在首图左下的深色毛玻璃胶囊，白字「Orenji & Kopi」；
/// - 否则 = 无图（F6b）：正文之后的浅色胶囊「bersama **Orenji & Kopi**」。
/// 点击：1 只直接进访客视图；多只先弹底部抽屉列出全部（AC4.7）。
class ContentLinkedPetsCapsule extends ConsumerWidget {
  const ContentLinkedPetsCapsule({super.key, required this.pets, required this.dark});

  /// 按创建顺序，非空（调用方已判显示条件）。
  final List<ContentPetRef> pets;
  final bool dark;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final names = [for (final p in pets) p.name];
    final plain = formatPetNames(l10n, names);
    final Widget label;
    if (dark) {
      label = Text(plain,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: Colors.white));
    } else {
      // 「bersama **Orenji & Kopi**」：宠物名部分 w600 ink，其余 w500 ink2。
      final full = formatPetNames(l10n, names, withPrefix: true);
      final at = full.indexOf(plain);
      const base = TextStyle(fontSize: 12.5, fontWeight: FontWeight.w500, color: AppColors.ink2);
      const bold = TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: AppColors.ink);
      label = Text.rich(
        at < 0
            ? TextSpan(text: full, style: base)
            : TextSpan(style: base, children: [
                if (at > 0) TextSpan(text: full.substring(0, at)),
                TextSpan(text: plain, style: bold),
                if (at + plain.length < full.length) TextSpan(text: full.substring(at + plain.length)),
              ]),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      );
    }
    final content = SizedBox(
      height: kLinkedPetsCapsuleHeight,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(4, 4, 10, 4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            PetAvatarStack(avatarUrls: [for (final p in pets) p.avatarUrl]),
            const SizedBox(width: 8),
            Flexible(child: label),
            const SizedBox(width: 2),
            Icon(Icons.chevron_right_rounded, size: 16, color: dark ? Colors.white : AppColors.ink2),
          ],
        ),
      ),
    );
    final shape = BorderRadius.circular(999);
    final Widget capsule = dark
        ? ClipRRect(
            borderRadius: shape,
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 8, sigmaY: 8),
              child: DecoratedBox(
                decoration: BoxDecoration(color: const Color(0x8C14101E), borderRadius: shape),
                child: content,
              ),
            ),
          )
        : DecoratedBox(
            decoration: BoxDecoration(color: AppColors.mintTint2, borderRadius: shape),
            child: content,
          );
    void onTap() => openContentLinkedPets(context, ref, pets);
    return Semantics(
      button: true,
      label: plain,
      excludeSemantics: true,
      onTap: onTap,
      child: GestureDetector(
        key: ValueKey(dark ? 'detailLinkedPetsDark' : 'detailLinkedPetsLight'),
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: capsule,
      ),
    );
  }
}

/// 点胶囊（AC4.7）：只有 1 只 → 直接进访客视图；多只 → 底部抽屉列出全部，点某行再进。
Future<void> openContentLinkedPets(BuildContext context, WidgetRef ref, List<ContentPetRef> pets) async {
  if (pets.isEmpty) return;
  if (pets.length == 1) {
    _openVisitor(context, ref, pets.first.petId);
    return;
  }
  final picked = await showModalBottomSheet<int>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.card,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
    builder: (sheetCtx) => _LinkedPetsSheet(pets: pets),
  );
  if (picked != null && context.mounted) _openVisitor(context, ref, picked);
}

/// 访客视图必须登录（站内路由 `/pets/:petId`）；游客 → 登录门控。
void _openVisitor(BuildContext context, WidgetRef ref, int petId) {
  requireLogin(
    ref,
    context,
    onAllowed: () =>
        context.push('${VisitorArchiveView.inAppRouteBase}/$petId?from=$kVisitorFromContentDetail'),
  );
}

/// 关联宠物抽屉：把手 + 统一卡片行（无勾选框、尾部 `chevron_right`），不加标题（「待确认」5）。
class _LinkedPetsSheet extends StatelessWidget {
  const _LinkedPetsSheet({required this.pets});

  final List<ContentPetRef> pets;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 6, 20, 16),
        child: Column(
          key: const ValueKey('detailLinkedPetsSheet'),
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(9999)),
              ),
            ),
            const SizedBox(height: 16),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  children: [
                    for (final p in pets)
                      PetCardRow(
                        key: ValueKey('detailLinkedPetRow-${p.petId}'),
                        avatarUrl: p.avatarUrl,
                        name: p.name,
                        // 详情响应只有物种（无生日）→ 副标题只出物种。
                        subtitle: petSpeciesLabel(l10n, p.petType) ?? '',
                        trailing: const Icon(Icons.chevron_right_rounded, color: AppColors.ink2),
                        onTap: () => Navigator.of(context).pop(p.petId),
                      ),
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
