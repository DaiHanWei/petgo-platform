import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../tailsonality/presentation/widgets/tailsonality_badge_chip.dart';
import '../../domain/pet_age.dart';

/// 宠物圆形头像项（V1.3.4 UI 稿 A2 宠物方案 B）—— 他人主页「Hewan」与我的页「Hewanku」**共用**。
///
/// 从 `public_profile_page.dart` 的 `_PetSection._item` 原样抽出（bug 20261015-590：
/// 我的页原为整宽行卡，与他人主页圆形头像不一致；2026-10-15 拍板统一成圆形头像，为多宠物预留）。
///
/// 每项宽 84：头像 64（外圈 2px 浅紫环 + 2px 白边）+ 右上角 diary 数角标（[diaryCount] 为 null 时不画），
/// 下方名字 → Tailsonality 小标（佩戴才有）→「物种 · 年龄」。无头像时为宠物图标占位。
/// 外层由调用方放进横向 `SingleChildScrollView`（角标向右上溢出 6 / 4，需留 padding）。
class PetAvatarItem extends StatelessWidget {
  const PetAvatarItem({
    super.key,
    required this.name,
    required this.avatarUrl,
    required this.petType,
    required this.birthday,
    required this.tailsonalityBadge,
    required this.diaryCount,
    required this.onTap,
    this.diaryBadgeKey,
    this.tailsonalityKey,
    this.metaKey,
  });

  final String name;
  final String? avatarUrl;
  final String? petType;
  final DateTime? birthday;
  final String? tailsonalityBadge;

  /// diary（GROWTH_MOMENT）数；>99 显示「99+」；null = 还没取到，不画角标。
  final int? diaryCount;
  final VoidCallback onTap;

  /// 各页测试沿用各自的 key（`profilePet*` / `mePet*`）。
  final Key? diaryBadgeKey;
  final Key? tailsonalityKey;
  final Key? metaKey;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final count = diaryCount;
    final hasAvatar = avatarUrl != null && avatarUrl!.isNotEmpty;
    final meta = _meta(l10n);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: 84,
        child: Column(
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                Container(
                  padding: const EdgeInsets.all(2),
                  decoration: const BoxDecoration(
                      color: AppColors.dashedViolet, shape: BoxShape.circle),
                  child: Container(
                    padding: const EdgeInsets.all(2),
                    decoration:
                        const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                    child: CircleAvatar(
                      // key 随 URL 变：换头像后强制重建，杜绝旧图不重绘。
                      key: ValueKey('petAvatar-$avatarUrl'),
                      radius: 32,
                      backgroundColor: AppColors.border,
                      backgroundImage: AppImage.provider(avatarUrl, thumbWidth: 200),
                      child: hasAvatar
                          ? null
                          : const Icon(Icons.pets, size: 28, color: AppColors.textTertiary),
                    ),
                  ),
                ),
                if (count != null)
                  Positioned(
                    right: -6,
                    top: -4,
                    child: Container(
                      key: diaryBadgeKey,
                      constraints: const BoxConstraints(minWidth: 20),
                      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                      decoration: BoxDecoration(
                        color: AppColors.mint,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: Colors.white, width: 2),
                      ),
                      child: Text(
                        count > 99 ? '99+' : '$count',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          color: Colors.white,
                          fontFeatures: [FontFeature.tabularFigures()],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            Text(
              name,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
            if (tailsonalityBadge != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                // T-7：名字下方，放不下省略（裁掉而不是撑破 84 宽）。
                child: ClipRect(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: TailsonalityBadgeChip(
                      key: tailsonalityKey,
                      letters: tailsonalityBadge,
                      compact: true,
                    ),
                  ),
                ),
              ),
            Text(
              meta,
              key: metaKey,
              style: const TextStyle(fontSize: 11, color: AppColors.ink2),
              // 验收 2026-10-15：84 宽单行放不下「Anjing · 1th 0bln」被截成「…」→ 允许两行，
              // 年龄内用不换行空格，只在「·」处折行
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  /// 「物种 · 年龄」—— **不含品种、不含 diary 数**（diary 数在头像角标上，UI 稿 A2）。
  String _meta(AppLocalizations l10n) => [
        ?petSpeciesLabel(l10n, petType),
        // 不满 1 个月按天表达，避免「0th 0bln」（与档案页同一出口）。
        ?formatPetAge(l10n, birthday)?.replaceAll(' ', '\u00A0'),
      ].join(' · ');
}
