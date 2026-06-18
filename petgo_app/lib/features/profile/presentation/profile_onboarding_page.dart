import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';

/// 状态 A 档案创建引导页（Story 1.7 F1 · onboard.html 1:1 还原）。
///
/// 顶部彩色渐变条 + 🎉 + 紫短线 + 欢迎语 + 副文 + 档案预览卡（零态统计）+
/// 「Buat Profil Hewan Sekarang」紫钮 + 「Nanti saja...」跳过链接。
class ProfileOnboardingPage extends ConsumerWidget {
  const ProfileOnboardingPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.base,
      body: Column(
        children: [
          // 顶部彩色渐变条（紫→violet→popRed）。
          Container(
            height: 5,
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [AppColors.mint, AppColors.mint500, AppColors.popRed],
              ),
            ),
          ),
          Expanded(
            child: SafeArea(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
                child: Column(
                  children: [
                    const Text('🎉', style: TextStyle(fontSize: 64)),
                    const SizedBox(height: 6),
                    // 紫短线装饰。
                    Container(
                      width: 48,
                      height: 4,
                      decoration: BoxDecoration(
                        color: AppColors.mint,
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    const SizedBox(height: 22),
                    Text(l10n.profileOnboardingTitle,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            fontSize: 21,
                            fontWeight: FontWeight.w700,
                            height: 1.35,
                            color: AppColors.ink)),
                    const SizedBox(height: 9),
                    Text(l10n.profileOnboardingBody,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                            fontSize: 13, height: 1.6, color: AppColors.ink2)),
                    const SizedBox(height: 26),
                    _previewCard(),
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        key: const ValueKey('profileOnboardingCreate'),
                        onPressed: () => context.go('/profile/create'),
                        style: FilledButton.styleFrom(
                          backgroundColor: AppColors.mint,
                          foregroundColor: AppColors.onAccent,
                          padding: const EdgeInsets.symmetric(vertical: 15),
                          shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(14)),
                        ),
                        child: Text(l10n.profileOnboardingCreate,
                            style: const TextStyle(
                                fontSize: 15, fontWeight: FontWeight.w700)),
                      ),
                    ),
                    const SizedBox(height: 13),
                    TextButton(
                      key: const ValueKey('profileOnboardingSkip'),
                      onPressed: () => context.go('/home'),
                      child: Text(l10n.profileOnboardingSkip,
                          style: const TextStyle(
                              fontSize: 13, color: AppColors.textTertiary)),
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

  /// 档案预览卡（onboard.html）：头像 + 「Nama hewan kamu」+ meta + 零态三列统计。
  Widget _previewCard() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.line2, width: 1.5),
        boxShadow: const [
          BoxShadow(color: Color(0x1A2B2A27), offset: Offset(0, 8), blurRadius: 28),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 50,
                height: 50,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                    shape: BoxShape.circle, color: AppColors.cream2),
                child: const Text('🐱', style: TextStyle(fontSize: 24)),
              ),
              const SizedBox(width: 12),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: const [
                  Text('Nama hewan kamu',
                      style: TextStyle(
                          fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink)),
                  SizedBox(height: 2),
                  Text('Kucing · Betina · 2 tahun',
                      style: TextStyle(fontSize: 11, color: AppColors.muted)),
                ],
              ),
            ],
          ),
          const SizedBox(height: 13),
          Container(
            decoration: BoxDecoration(
                color: AppColors.cream2, borderRadius: BorderRadius.circular(10)),
            child: Row(
              children: [
                _stat('0', 'Momen'),
                _statDivider(),
                _stat('0', 'Konsultasi'),
                _statDivider(),
                _stat('0/30', 'Milestone'),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _stat(String n, String label) => Expanded(
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
          child: Column(
            children: [
              Text(n,
                  style: const TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
              const SizedBox(height: 1),
              Text(label,
                  style: const TextStyle(fontSize: 9, color: AppColors.ink2)),
            ],
          ),
        ),
      );

  Widget _statDivider() => Container(width: 1, height: 30, color: AppColors.line);
}
