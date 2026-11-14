import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../../core/theme/colors.dart';
import '../../../../core/theme/typography.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../../auth/domain/auth_guard.dart';
import '../../../profile/presentation/visitor_archive_view.dart';
import '../../domain/followed_pet.dart';
import '../../../profile/domain/pet_age.dart';
import '../../../profile/domain/pet_species_display.dart';
import 'pet_follow_button.dart';

/// 关注列表 Hewan 的一行（V1.4.2 social-topic · Story 1.5 · AC2 / AC3 · UI 稿 A6 / A7 / A9）。
///
/// 布局数值照 [FollowUserRow]（那个绑的是用户模型，不能直接复用）：圆形头像 40 + 间距 12 + 名字 15 / w600 +
/// 第二行「{物种} · milik @{主人}」12 `muted` 单行省略 + 右侧 88×30 追更按钮；行上下 12。
/// 头像 / 文字 / 按钮**垂直居中**（UI 第四步修过「头像低 6px」）。
///
/// [showButton]：只有**我自己的**列表显示（常驻两态，点 Mengikuti 立即取消、行保留）；别人的列表不渲染按钮、
/// 右侧留白不补元素（AC3.1）。点行（除按钮外）→ 宠物访客页 `/pets/{petId}`（站内访客接口仅登录可用 → 游客走登录门控）。
class FollowedPetRow extends ConsumerWidget {
  const FollowedPetRow({super.key, required this.pet, required this.showButton});

  final FollowedPet pet;
  final bool showButton;

  /// 无头像按物种 emoji 占位（同 `pet_info_card.dart` 的规则）。
  /// V1.5.0 pet-species：转调统一函数（旧签名保留，新增可选 [species]）。
  static String placeholderEmoji(String? petType, {String? species}) =>
      petSpeciesView(species: species, petType: petType).emoji;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final species = petSpeciesLabel(l10n, pet.petType, species: pet.species); // V1.5.0 pet-species：统一函数
    final owner = pet.ownerNickname;
    final subtitle = [
      ?species,
      if (owner != null && owner.isNotEmpty) l10n.petFollowOwnedBy(owner),
    ].join(' · ');
    final identity = Row(
      children: [
        _avatar(),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(pet.name,
                  key: ValueKey('followedPetName_${pet.petId}'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTypography.body.copyWith(fontWeight: FontWeight.w600)),
              if (subtitle.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(subtitle,
                    key: ValueKey('followedPetSubtitle_${pet.petId}'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ],
            ],
          ),
        ),
      ],
    );
    return Padding(
      key: ValueKey('followedPetRow_${pet.petId}'),
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(
            child: GestureDetector(
              key: ValueKey('followedPetOpen_${pet.petId}'),
              behavior: HitTestBehavior.opaque,
              onTap: () => requireLogin(
                ref,
                context,
                onAllowed: () => context.push('${VisitorArchiveView.inAppRouteBase}/${pet.petId}'),
              ),
              child: identity,
            ),
          ),
          if (showButton) ...[
            const SizedBox(width: 8),
            PetFollowButton(
              petId: pet.petId,
              initialFollow: pet.iFollow,
              followSnapshotEpoch: pet.followEpoch,
              style: PetFollowButtonStyle.listCompact,
              source: 'follow_list',
              ownerToken: pet.ownerToken,
              iFollowOwner: pet.iFollowOwner,
              // AC2.3：取消后该行保留，下拉刷新才消失 → 列表本身不在这里失效。
              invalidatesFollowedPetList: false,
            ),
          ],
        ],
      ),
    );
  }

  Widget _avatar() {
    final emoji = Text(placeholderEmoji(pet.petType, species: pet.species), style: const TextStyle(fontSize: 20));
    final provider = AppImage.provider(pet.avatarUrl, thumbWidth: 120);
    return Container(
      key: ValueKey('followedPetAvatar_${pet.petId}'),
      width: 40,
      height: 40,
      alignment: Alignment.center,
      clipBehavior: Clip.antiAlias,
      decoration: const BoxDecoration(color: AppColors.cream2, shape: BoxShape.circle),
      child: provider == null
          ? emoji
          : Image(
              image: provider,
              width: 40,
              height: 40,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => emoji,
            ),
    );
  }
}
