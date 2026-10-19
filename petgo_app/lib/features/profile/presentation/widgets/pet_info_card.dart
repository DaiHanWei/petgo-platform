import 'package:flutter/material.dart';

import '../../../../core/theme/colors.dart';
import '../../../../l10n/app_localizations.dart';
import '../../../../shared/format/idr_compact.dart';
import '../../../../shared/widgets/app_image.dart';
import '../../domain/pet_age.dart';
import '../../domain/pet_header_info.dart';
import '../../../tailsonality/presentation/widgets/tailsonality_badge_chip.dart';

/// 宠物护照卡（Story 2.4 · paspor.html 1:1 还原）。
///
/// 横向 pet-top（大头像 + 名字/品种·年龄/一句话简介）+ 卡内三列统计
/// （Momen Bahagia / Hadiah / Milestone）。
///
/// V1.4.0 L2 还原度（稿 `.pic`）：淡紫渐变卡 + 白色统计条（原为白卡 + 淡紫统计条，配色相反）；头像白 + 淡紫双环；
/// 统计数字深色，仅可点的礼物金额用紫色强调。
///
/// V1.4.0 gift Story 2.2 · AC2：中间格由「Konsultasi」换成**礼物总价值**（缩写，`Rp53rb`）；
/// 传 [onOpenGifts] 时该格可点进礼物页（标签后加紫色「›」）。
class PetInfoCard extends StatelessWidget {
  const PetInfoCard({
    super.key,
    required this.profile,
    this.happyCount,
    this.giftTotalValue,
    this.onOpenGifts,
    this.milestoneCount,
    this.topRight,
    this.avatarKey,
  });

  final PetHeaderInfo profile;

  /// 头像的位置锚点（V1.4.0 gift Story 1.3：访客视图送出动效的落点）。只用来量位置；默认 null 行为不变。
  final GlobalKey? avatarKey;

  /// 卡片右上角（头像行最右）的附加控件——作者态放「只看 Diary」开关（2026-10-06 产品）；访客态不传。
  final Widget? topRight;

  /// 统计三列（archiveStatsProvider 未就绪时传 null，显占位「·」）。
  final int? happyCount;
  final int? milestoneCount;

  /// 中间格：收到的礼物总价值（IDR）。null（统计未就绪）→ 占位「·」。
  final int? giftTotalValue;

  /// 点礼物格进礼物页（主人 / 站内访客注入）。null → 不可点、无「›」（游客示例、分享 token 态）。
  final VoidCallback? onOpenGifts;

  /// 无头像时的 emoji 占位：**按物种取**（原先恒为 🐱，狗主人会看到自家狗顶着猫脸 ——
  /// 2026-08-04 用户实机反馈）。未知物种走中性爪印。
  String get _placeholderEmoji => switch (profile.petType) {
        'CAT' => '🐱',
        'DOG' => '🐶',
        _ => '🐾',
      };

  /// 头像：有 avatarUrl 渲染真实头像图（填满圆圈，与 Me/编辑页同用 AppImage），
  /// 加载中/失败回退 emoji 占位。无 URL → 按物种的 emoji 占位。
  Widget _avatar() {
    final provider = AppImage.provider(profile.avatarUrl, thumbWidth: 240);
    if (provider == null) {
      return Text(_placeholderEmoji, style: const TextStyle(fontSize: 26));
    }
    final fallback = Text(_placeholderEmoji, style: const TextStyle(fontSize: 26));
    return Image(
      image: provider,
      key: ValueKey('petAvatar-${profile.avatarUrl}'), // URL 变即重建，避免换头像后旧图残留
      width: 62,
      height: 62,
      fit: BoxFit.cover,
      gaplessPlayback: true,
      errorBuilder: (_, _, _) => fallback,
      loadingBuilder: (_, child, progress) => progress == null ? child : fallback,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // A3/A4 稿的 meta 行是「物种 · 品种 · 年龄」；此前漏了物种，只有品种时
    // 「Pomeranian」这类品种名并不能让人一眼看出是猫还是狗（我的页宠物卡一直是带物种的）。
    final species = switch (profile.petType) {
      'CAT' => l10n.petTypeCat,
      'DOG' => l10n.petTypeDog,
      'OTHER' => l10n.petTypeOther,
      _ => null,
    };
    final sub = [
      ?species,
      if (profile.breed != null && profile.breed!.isNotEmpty) profile.breed!,
      ?formatPetAge(l10n, profile.birthday),
    ].join(' · ');

    return Container(
      key: const ValueKey('petInfoCard'),
      padding: const EdgeInsets.all(14),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.diaryCardTop, AppColors.diaryCardEnd],
        ),
        borderRadius: BorderRadius.all(Radius.circular(18)),
      ),
      child: Column(
        children: [
          // pet-top：横向 大头像(62，白 2 + 淡紫 2 双环) + 名字/品种·年龄/简介
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 62,
                height: 62,
                decoration: const BoxDecoration(
                  shape: BoxShape.circle,
                  boxShadow: [
                    BoxShadow(color: AppColors.dashedViolet, spreadRadius: 4),
                    BoxShadow(color: Colors.white, spreadRadius: 2),
                  ],
                ),
                child: Container(
                  key: avatarKey,
                  width: 62,
                  height: 62,
                  alignment: Alignment.center,
                  clipBehavior: Clip.antiAlias,
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.violet100),
                  child: _avatar(),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(profile.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                        ),
                        // V1.3.2 Story 3.3：佩戴 + 已解锁才有；null 不渲染任何占位。
                        if (profile.tailsonalityBadge != null) ...[
                          const SizedBox(width: 6),
                          TailsonalityBadgeChip(
                              key: const ValueKey('petInfoTailsonality'), letters: profile.tailsonalityBadge),
                        ],
                      ],
                    ),
                    if (sub.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(sub,
                          style: const TextStyle(fontSize: 12, color: AppColors.ink2)),
                    ],
                    if (profile.intro != null && profile.intro!.isNotEmpty) ...[
                      const SizedBox(height: 3),
                      Text('“${profile.intro!}”',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.ink2,
                              fontStyle: FontStyle.italic)),
                    ],
                  ],
                ),
              ),
              if (topRight != null) ...[
                const SizedBox(width: 8),
                topRight!,
              ],
            ],
          ),
          const SizedBox(height: 12),
          // statsrow（稿 `.pic-s`）：白底 rounded-12，三列等分、无分隔线
          Container(
            padding: const EdgeInsets.symmetric(vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                _statCol(happyCount, l10n.petInfoStatHappyMoments),
                _giftCol(l10n),
                _statCol(milestoneCount, l10n.petInfoStatMilestone),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _statCol(int? n, String label) => Expanded(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          child: Column(
            children: [
              Text(n?.toString() ?? '·',
                  style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
              const SizedBox(height: 1),
              Text(label,
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 11, color: AppColors.ink2)),
            ],
          ),
        ),
      );

  /// 礼物格（AC2.2）：数字 16 号，「Rp」与「rb / jt」0.6 倍字号；可点（进礼物页）时紫色强调，否则同其余两格深色。
  Widget _giftCol(AppLocalizations l10n) {
    final numberStyle = TextStyle(
        fontSize: 16, fontWeight: FontWeight.w700, color: onOpenGifts != null ? AppColors.mint : AppColors.ink);
    final v = giftTotalValue;
    final Widget value;
    if (v == null) {
      value = Text('·', style: numberStyle);
    } else {
      final p = idrCompactParts(v);
      final unitStyle = numberStyle.copyWith(fontSize: 16 * 0.6);
      value = Text.rich(
        key: const ValueKey('petInfoGiftValue'),
        TextSpan(children: [
          TextSpan(text: 'Rp', style: unitStyle),
          TextSpan(text: p.number, style: numberStyle),
          if (p.unit.isNotEmpty) TextSpan(text: p.unit, style: unitStyle),
        ]),
        maxLines: 1,
      );
    }
    final tappable = onOpenGifts != null;
    final column = Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      child: Column(
        children: [
          value,
          const SizedBox(height: 1),
          Text.rich(
            TextSpan(children: [
              TextSpan(text: l10n.giftStatLabel),
              if (tappable)
                const TextSpan(
                    text: ' ›',
                    style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.mint)),
            ]),
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 11, color: AppColors.ink2),
          ),
        ],
      ),
    );
    return Expanded(
      child: tappable
          ? Semantics(
              button: true,
              child: InkWell(
                key: const ValueKey('petInfoGiftStat'),
                borderRadius: BorderRadius.circular(12),
                onTap: onOpenGifts,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(minHeight: 44),
                  child: column,
                ),
              ),
            )
          : KeyedSubtree(key: const ValueKey('petInfoGiftStat'), child: column),
    );
  }

}
