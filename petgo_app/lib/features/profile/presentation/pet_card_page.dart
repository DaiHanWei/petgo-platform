import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/pet_age.dart';

/// 宠物名片公开分享页（FR-14 · namecard.html 1:1 还原）。
///
/// 深色档案卡（#141019 + 紫辉光 + Pop Art 装饰）：宠物 hero + 成就徽章条 +
/// 最新里程碑 + 最近快乐时刻 5 格 + 双 CTA（下载拉新 / 建相似档案）。
/// 对外分享观感（非 App 用户在浏览器看到）；name/breed/统计取真实档案，
/// 成就徽章 + 照片格为代表性展示内容（与原型一致，公开卡为策展呈现）。
class PetCardPage extends ConsumerWidget {
  const PetCardPage({super.key});

  static const Color _bg = Color(0xFF141019);
  static const Color _badgeLocked = Color(0xFF3A3453);

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final profile = ref.watch(petProfileProvider).asData?.value;
    final stats = ref.watch(archiveStatsProvider).asData?.value;
    final name = profile?.name ?? 'Mochi';
    final age = computePetAge(profile?.birthday);
    final breedLine = [
      _speciesLabel(l10n, profile?.petType),
      if (profile?.breed != null && profile!.breed!.isNotEmpty) profile.breed!,
      if (profile?.birthday != null) l10n.petCardYears(age.years),
    ].join(' · ');
    final daysTogether = profile?.birthday == null
        ? 365
        : DateTime.now().difference(profile!.birthday!).inDays;
    final statsLine = l10n.petCardStatsLine(
      stats?.happyMomentCount ?? 12,
      stats?.consultCount ?? 2,
      stats?.milestoneCompleted ?? 5,
    );

    return Scaffold(
      backgroundColor: _bg,
      body: Stack(
        children: [
          // 底部紫色辉光
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: const Alignment(0, 1.6),
                  radius: 1.1,
                  colors: [AppColors.mint.withValues(alpha: 0.5), _bg],
                  stops: const [0.0, 0.62],
                ),
              ),
            ),
          ),
          // Pop Art 装饰方块（左上 / 右上，双层错位）
          _popSquare(top: 48, left: 22, size: 16, color: AppColors.popRed, angle: 0.28),
          _popSquare(top: 51, left: 25, size: 16, color: AppColors.mint, angle: 0.28),
          _popSquare(top: 55, right: 26, size: 12, color: AppColors.gold, angle: -0.21),
          _popSquare(top: 58, right: 29, size: 12, color: AppColors.mint, angle: -0.21),
          SafeArea(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 28, 20, 32),
              children: [
                // —— Hero ——
                Center(
                  child: Column(
                    children: [
                      Container(
                        width: 88,
                        height: 88,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: const LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [AppColors.mint500, AppColors.mint],
                          ),
                          boxShadow: [
                            BoxShadow(color: AppColors.mint.withValues(alpha: 0.3), blurRadius: 0, spreadRadius: 4),
                            BoxShadow(color: AppColors.mint.withValues(alpha: 0.12), blurRadius: 0, spreadRadius: 8),
                          ],
                        ),
                        child: const Text('🐱', style: TextStyle(fontSize: 42)),
                      ),
                      const SizedBox(height: 12),
                      Text(name,
                          style: const TextStyle(
                              fontSize: 22, fontWeight: FontWeight.w700, color: Colors.white)),
                      const SizedBox(height: 3),
                      Text(breedLine,
                          style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.6))),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Container(
                            width: 22,
                            height: 22,
                            alignment: Alignment.center,
                            decoration: const BoxDecoration(
                              shape: BoxShape.circle,
                              gradient: LinearGradient(colors: [AppColors.mint500, AppColors.mint]),
                            ),
                            child: const Text('H',
                                style: TextStyle(
                                    fontSize: 10, fontWeight: FontWeight.w700, color: Colors.white)),
                          ),
                          const SizedBox(width: 6),
                          _ownerLine(l10n, daysTogether),
                        ],
                      ),
                      const SizedBox(height: 10),
                      Text(statsLine,
                          style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.5))),
                    ],
                  ),
                ),
                const SizedBox(height: 22),
                // —— PENCAPAIAN 徽章条 ——
                _sectionLabel(l10n.petCardAchievements),
                const SizedBox(height: 10),
                SizedBox(
                  height: 72,
                  child: ListView(
                    scrollDirection: Axis.horizontal,
                    children: [
                      for (final b in _badges) _badge(l10n, b),
                    ],
                  ),
                ),
                const SizedBox(height: 18),
                // —— 最新里程碑 ——
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.07),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Row(
                    children: [
                      const Text('🎂', style: TextStyle(fontSize: 22)),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('${l10n.petCardBadgeFirstBirthday} 🏅',
                                style: const TextStyle(
                                    fontSize: 12, fontWeight: FontWeight.w700, color: Colors.white)),
                            const SizedBox(height: 2),
                            Text('Milestone L · 15 Jun 2025 · "Sudah 1 tahun bersama!"',
                                style: TextStyle(
                                    fontSize: 11, color: Colors.white.withValues(alpha: 0.45))),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                // —— MOMEN BAHAGIA TERAKHIR 5 格 ——
                _sectionLabel(l10n.petCardRecentMoments),
                const SizedBox(height: 10),
                Row(
                  children: [
                    for (var i = 0; i < _momentTiles.length; i++) ...[
                      if (i > 0) const SizedBox(width: 6),
                      Expanded(child: _momentTile(_momentTiles[i])),
                    ],
                  ],
                ),
                const SizedBox(height: 20),
                // —— 双 CTA ——
                _primaryCta(context, l10n),
                const SizedBox(height: 10),
                _secondaryCta(context, l10n),
                const SizedBox(height: 12),
                Center(
                  child: GestureDetector(
                    onTap: () => Navigator.of(context).maybePop(),
                    child: Text('${l10n.petCardViewFullStory(name)} →',
                        style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.4))),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _ownerLine(AppLocalizations l10n, int days) => RichText(
        text: TextSpan(
          style: TextStyle(fontSize: 11, color: Colors.white.withValues(alpha: 0.6)),
          children: [
            TextSpan(text: '${l10n.petCardOwnedBy} '),
            const TextSpan(
                text: '@hexsfile',
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
            TextSpan(text: ' · ${l10n.petCardTogetherFor} '),
            TextSpan(
                text: l10n.petCardDays(days),
                style: const TextStyle(color: AppColors.mint500, fontWeight: FontWeight.w700)),
          ],
        ),
      );

  Widget _sectionLabel(String text) => Text(text,
      style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.5,
          color: Colors.white.withValues(alpha: 0.4)));

  Widget _popSquare(
          {double? top, double? left, double? right, required double size, required Color color, required double angle}) =>
      Positioned(
        top: top,
        left: left,
        right: right,
        child: Transform.rotate(
          angle: angle,
          child: Container(
            width: size,
            height: size,
            decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(4)),
          ),
        ),
      );

  Widget _badge(AppLocalizations l10n, _Badge b) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: SizedBox(
        width: 44,
        child: Column(
          children: [
            Container(
              width: 44,
              height: 44,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: b.locked ? null : LinearGradient(colors: b.colors),
                color: b.locked ? _badgeLocked : null,
                boxShadow: b.locked
                    ? null
                    : [BoxShadow(color: b.colors.first.withValues(alpha: 0.4), blurRadius: 10, offset: const Offset(0, 3))],
              ),
              child: Opacity(opacity: b.locked ? 0.5 : 1, child: Text(b.emoji, style: const TextStyle(fontSize: 20))),
            ),
            const SizedBox(height: 4),
            Text(_badgeLabel(l10n, b.labelKey),
                textAlign: TextAlign.center,
                maxLines: 2,
                style: TextStyle(
                    fontSize: 8,
                    height: 1.3,
                    color: Colors.white.withValues(alpha: b.locked ? 0.4 : 0.5))),
          ],
        ),
      ),
    );
  }

  Widget _momentTile(List<dynamic> t) => AspectRatio(
        aspectRatio: 1,
        child: Container(
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [t[1] as Color, t[2] as Color],
            ),
          ),
          child: Text(t[0] as String, style: const TextStyle(fontSize: 22)),
        ),
      );

  Widget _primaryCta(BuildContext context, AppLocalizations l10n) => SizedBox(
        width: double.infinity,
        child: FilledButton(
          onPressed: () => Navigator.of(context).maybePop(),
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.mint,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(vertical: 14),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          child: Text('🐾 ${l10n.petCardDownloadCta}',
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
        ),
      );

  Widget _secondaryCta(BuildContext context, AppLocalizations l10n) => SizedBox(
        width: double.infinity,
        child: OutlinedButton(
          onPressed: () => Navigator.of(context).maybePop(),
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.white,
            backgroundColor: Colors.white.withValues(alpha: 0.1),
            side: BorderSide(color: Colors.white.withValues(alpha: 0.2), width: 1.5),
            padding: const EdgeInsets.symmetric(vertical: 13),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          ),
          child: Text(l10n.petCardCreateSimilar,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
        ),
      );

  static String _speciesLabel(AppLocalizations l10n, String? petType) {
    switch (petType) {
      case 'CAT':
        return l10n.petTypeCat;
      case 'DOG':
        return l10n.petTypeDog;
      default:
        return l10n.petTypeCat;
    }
  }

  static String _badgeLabel(AppLocalizations l10n, String key) => switch (key) {
        'birthday' => l10n.petCardBadgeBirthday,
        'profileComplete' => l10n.petCardBadgeProfileComplete,
        'firstPhoto' => l10n.petCardBadgeFirstPhoto,
        'firstConsult' => l10n.petCardBadgeFirstConsult,
        'tenMoments' => l10n.petCardBadgeTenMoments,
        'firstBath' => l10n.petCardBadgeFirstBath,
        'sleepTogether' => l10n.petCardBadgeSleepTogether,
        _ => '',
      };

  // 代表性成就徽章（公开卡策展展示，配色对齐原型）。
  static const List<_Badge> _badges = [
    _Badge('🎂', 'birthday', [AppColors.gold, Color(0xFFFFD166)]),
    _Badge('📋', 'profileComplete', [Color(0xFF1F9E6A), Color(0xFF56D4A0)]),
    _Badge('📷', 'firstPhoto', [AppColors.mint, AppColors.mint500]),
    _Badge('🏥', 'firstConsult', [AppColors.popRed, Color(0xFFFF7089)]),
    _Badge('🌟', 'tenMoments', [Color(0xFF5BCBBB), Color(0xFF31B3A2)]),
    _Badge('🛁', 'firstBath', [], locked: true),
    _Badge('😴', 'sleepTogether', [], locked: true),
  ];

  // 最近快乐时刻 5 格（emoji + 渐变底，对齐原型）。
  static const List<List<dynamic>> _momentTiles = [
    ['☀️', Color(0xFFF8F6FF), Color(0xFFDCD2F7)],
    ['🧶', Color(0xFFFEF3DE), Color(0xFFFFCD85)],
    ['🌟', Color(0xFFE7F8F0), Color(0xFF9DEBB6)],
    ['🐾', Color(0xFFEFF7F4), Color(0xFFB3E6DA)],
    ['😻', Color(0xFFF8F6FF), Color(0xFFC4B0F0)],
  ];
}

class _Badge {
  const _Badge(this.emoji, this.labelKey, this.colors, {this.locked = false});
  final String emoji;
  final String labelKey;
  final List<Color> colors;
  final bool locked;
}
