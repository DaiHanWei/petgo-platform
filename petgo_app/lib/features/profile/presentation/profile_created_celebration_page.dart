import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';

/// 建档「创建成功」庆祝页（Story 1.7 R2 / AC4 · FR-0G · 决策 F15 · pet-success.html 1:1 还原）。
///
/// 深墨底 #141019 + 底部紫辉光 + Pop Art 装饰；110 头像双光环 + 「Halo, [宠物名]! 🎉」+ 副文
/// + 第一条里程碑解锁卡 + 主 CTA「Rekam Momen Pertama」+ 次链接「Lihat profil dulu」。
/// 主 CTA 串接推送权限时机（庆祝页后、进首页前）由 [onStartExplore] 注入（路由侧接 Story 6.4 闸门）。
class ProfileCreatedCelebrationPage extends StatelessWidget {
  const ProfileCreatedCelebrationPage({
    super.key,
    required this.petName,
    required this.onStartExplore,
    this.avatarUrl,
  });

  final String petName;

  /// 进入 App 的动作：路由侧注入「触发推送权限时机 → 进首页」。主/次 CTA 共用。
  final Future<void> Function() onStartExplore;

  final String? avatarUrl;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.splashInk,
      body: Stack(
        children: [
          // 底部紫色大辉光（原型 radial-gradient at 50% 110%）。
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment(0, 1.15),
                  radius: 1.4,
                  colors: [Color(0x80845EC9), Color(0x00845EC9)],
                  stops: [0.0, 0.65],
                ),
              ),
            ),
          ),
          // Pop Art 装饰小块（烟花感）。
          const Positioned(top: 70, left: 30, child: _Deco(14, AppColors.popRed, .35)),
          const Positioned(top: 66, left: 46, child: _Deco(14, AppColors.mint, .35)),
          const Positioned(top: 78, right: 34, child: _Deco(12, Color(0xFFF6A609), -.26)),
          const Positioned(top: 92, right: 50, child: _Deco(12, Color(0xFF1FB877), -.26)),
          const Positioned(top: 132, left: 22, child: _Deco(8, AppColors.mint500, 0, circle: true)),
          const Positioned(top: 144, right: 24, child: _Deco(8, AppColors.popRed, 0, circle: true)),
          // 主内容。
          SafeArea(
            child: Center(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 40),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _avatar(),
                    const SizedBox(height: 20),
                    Text(
                      l10n.profileCreatedGreeting(petName),
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          fontSize: 26, fontWeight: FontWeight.w700, color: Colors.white, height: 1.2),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      l10n.profileCreatedJourney(petName),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                          fontSize: 14, height: 1.65, color: Colors.white.withValues(alpha: 0.7)),
                    ),
                    const SizedBox(height: 28),
                    _milestoneCard(l10n),
                    const SizedBox(height: 28),
                    // 主 CTA「Rekam Momen Pertama 📸」（key 沿用 celebrationStartExplore，串推送闸门）。
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        key: const ValueKey('celebrationStartExplore'),
                        onPressed: onStartExplore,
                        style: FilledButton.styleFrom(
                          backgroundColor: AppColors.mint,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 15),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                          elevation: 0,
                        ),
                        child: Text(l10n.profileCreatedRecordFirst,
                            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                      ),
                    ),
                    const SizedBox(height: 13),
                    // 次链接「Lihat profil dulu」（同样进 App）。
                    GestureDetector(
                      key: const ValueKey('celebrationViewProfile'),
                      behavior: HitTestBehavior.opaque,
                      onTap: onStartExplore,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 12),
                        child: Text(l10n.profileCreatedViewProfile,
                            style: TextStyle(fontSize: 13, color: Colors.white.withValues(alpha: 0.45))),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 110 头像：渐变圆 + 双光环；有图用真图,否则爪印 emoji。
  Widget _avatar() {
    final provider = AppImage.provider(avatarUrl, thumbWidth: 240);
    return Container(
      key: const ValueKey('celebrationAvatar'),
      width: 110,
      height: 110,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint500, AppColors.mint],
        ),
        image: provider != null ? DecorationImage(image: provider, fit: BoxFit.cover) : null,
        boxShadow: [
          BoxShadow(color: AppColors.mint.withValues(alpha: 0.3), spreadRadius: 6),
          BoxShadow(color: AppColors.mint.withValues(alpha: 0.12), spreadRadius: 12),
        ],
      ),
      child: provider == null ? const Text('🐾', style: TextStyle(fontSize: 52)) : null,
    );
  }

  Widget _milestoneCard(AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.mint.withValues(alpha: 0.4), width: 1.5),
      ),
      child: Row(
        children: [
          const Text('🏅', style: TextStyle(fontSize: 28)),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l10n.profileCreatedMilestoneTitle,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w700, color: Colors.white)),
                const SizedBox(height: 2),
                Text(l10n.profileCreatedMilestoneDesc,
                    style: TextStyle(fontSize: 12, color: Colors.white.withValues(alpha: 0.6))),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Pop Art 装饰小块（方块/圆点，旋转角度 [angle] 弧度近似 = 度/57）。
class _Deco extends StatelessWidget {
  const _Deco(this.size, this.color, this.angle, {this.circle = false});

  final double size;
  final Color color;
  final double angle;
  final bool circle;

  @override
  Widget build(BuildContext context) {
    return Transform.rotate(
      angle: angle,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(circle ? 999 : 3),
        ),
      ),
    );
  }
}
