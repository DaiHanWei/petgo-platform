import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../tailsonality/presentation/widgets/tailsonality_badge_chip.dart';
import '../../domain/pet_age.dart';
import 'pet_species_avatar.dart';

/// 宠物圆形头像项（V1.3.4 UI 稿 A2 宠物方案 B）—— 他人主页「Hewan」与我的页「Hewanku」**共用**。
///
/// 从 `public_profile_page.dart` 的 `_PetSection._item` 原样抽出（bug 20261015-590：
/// 我的页原为整宽行卡，与他人主页圆形头像不一致；2026-10-15 拍板统一成圆形头像，为多宠物预留）。
///
/// 每项宽 84：头像 64（外圈 2px 浅紫环 + 2px 白边）+ 右上角 diary 数角标（[diaryCount] 为 null 时不画），
/// 下方名字 → Tailsonality 小标（佩戴才有）→「物种 · 年龄」。无头像时为宠物图标占位。
/// 外层由调用方放进横向 `SingleChildScrollView`（角标向右上溢出 6 / 4，需留 padding）。
///
/// V1.4.0 multi-pet（Story 1.5 · AC2.4 / AC3.1）追加可选参数，**默认值 = 改动前的外观**：
/// - [size]：头像直径（默认 64 = 改动前；「我的」页 56，Diary 切换器 40）；
/// - [readOnly]：只读宠物 → 头像灰化 + **左下**锁标（避开右上 diary 角标），名字转弱化色；
/// - [selected]：当前宠物 → 外环换主色（切换器「紫环」）；
/// - [compact]：切换器形态 → 只有头像 + 名字（不画性格小标 / 物种 · 年龄），宽 = 头像 + 12。
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
    this.size = kDefaultSize,
    this.readOnly = false,
    this.selected = false,
    this.compact = false,
    this.lockKey,
  });

  /// 改动前的头像直径（CircleAvatar radius 32）。
  static const double kDefaultSize = 64;

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

  final double size;
  final bool readOnly;
  final bool selected;
  final bool compact;

  /// 只读锁标的 key（测试定位用）。
  final Key? lockKey;

  /// 灰化矩阵（UI 稿 `.pav.ro` / `.psw-i.ro`：grayscale(.85) + 降透明度）。
  static const ColorFilter _greyscale = ColorFilter.matrix(<double>[
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0.2126, 0.7152, 0.0722, 0, 0, //
    0, 0, 0, 0.75, 0,
  ]);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 0 篇不画「0」角标（V1.4.0 L2 J0：稿只在有 Diary 时显示数字）。
    final count = (diaryCount ?? 0) > 0 ? diaryCount : null;
    final meta = _meta(l10n);
    Widget avatar = PetSpeciesAvatar(
      // key 随 URL 变：换头像后强制重建，杜绝旧图不重绘。
      key: ValueKey('petAvatar-$avatarUrl'),
      avatarUrl: avatarUrl,
      petType: petType,
      size: size,
      thumbWidth: 200,
    );
    if (readOnly) avatar = ColorFiltered(colorFilter: _greyscale, child: avatar);
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: compact ? size + 12 : 84,
        child: Column(
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                Container(
                  padding: const EdgeInsets.all(2),
                  decoration: BoxDecoration(
                      color: selected ? AppColors.mint : AppColors.dashedViolet,
                      shape: BoxShape.circle),
                  child: Container(
                    padding: const EdgeInsets.all(2),
                    decoration:
                        const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                    child: avatar,
                  ),
                ),
                if (readOnly)
                  Positioned(
                    left: -4,
                    bottom: -2,
                    child: Container(
                      key: lockKey,
                      width: compact ? 18 : 22,
                      height: compact ? 18 : 22,
                      decoration: BoxDecoration(
                        color: AppColors.ink2,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white, width: 2),
                      ),
                      child: Icon(Icons.lock, size: compact ? 9 : 11, color: Colors.white),
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
            SizedBox(height: compact ? 3 : 10),
            Text(
              name,
              style: TextStyle(
                  fontSize: compact ? 10.5 : 13,
                  fontWeight: compact && !selected ? FontWeight.w500 : FontWeight.w600,
                  color: readOnly
                      ? AppColors.muted
                      : (compact && !selected ? AppColors.ink2 : AppColors.ink)),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
            if (!compact && tailsonalityBadge != null)
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
            if (!compact)
              Padding(
                // 左右各让 3：相邻两只都被省略时，两段「…」之间仍留出间隔，不糊成一串。
                padding: const EdgeInsets.symmetric(horizontal: 3),
                // 稿 A2 / J0：副标题**单行**（L2 第二轮：原先两行会把一排头像撑得高低不齐）；
                // 放不下时整体缩小而不是省略（L2 第三轮：省略会把年龄截掉）。
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    meta,
                    key: metaKey,
                    style: const TextStyle(fontSize: 11, color: AppColors.ink2),
                    maxLines: 1,
                    softWrap: false,
                    textAlign: TextAlign.center,
                  ),
                ),
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
