import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/emoji_avatar.dart';
import '../../../shared/widgets/design/momo.dart';
import '../../../shared/widgets/design/striped_photo.dart';
import '../data/profile_repository.dart';

/// 宠物名片 H5 对外分享页（FR-14 · TailTopia Prototype）。
///
/// 模拟非 App 用户在浏览器看到的公开页：浏览器地址栏 + 名片 hero + 最近照片流 +
/// 「Unduh TailTopia」下载引导（拉新飞轮）。App 内作为「预览名片」入口呈现。
class PetCardPage extends ConsumerWidget {
  const PetCardPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final profileAsync = ref.watch(petProfileProvider);
    final profile = profileAsync.asData?.value;
    final name = profile?.name ?? 'Mochi';
    final slug = name.toLowerCase().replaceAll(' ', '');
    final breed = profile?.breed ?? 'Kucing Oren';
    final bday = profile?.birthday;
    final bdayStr = bday == null
        ? '14 Feb 2023'
        : '${bday.day} ${_month(bday.month)} ${bday.year}';
    final bio = (profile?.intro?.isNotEmpty ?? false)
        ? profile!.intro!
        : 'Mochi, kucing oren termalas se-Jakarta';

    final photos = ['☀️ berjemur', '😴 tidur siang', '🎀 ulang tahun', '🐾 main', '🍣 makan'];

    return Scaffold(
      backgroundColor: AppColors.card,
      body: Column(
        children: [
          // —— 浏览器外壳：关闭 + 地址栏 ——
          SafeArea(
            bottom: false,
            child: Container(
              color: const Color(0xFFECEAE4),
              padding: const EdgeInsets.fromLTRB(12, 9, 12, 9),
              child: Row(
                children: [
                  _circle(Icons.close, () => Navigator.of(context).maybePop()),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Container(
                      height: 32,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      decoration: BoxDecoration(
                          color: AppColors.card, borderRadius: BorderRadius.circular(9)),
                      child: Row(
                        children: [
                          const Icon(Icons.public, size: 14, color: AppColors.muted),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text('petgo.id/m/$slug',
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Expanded(
            child: Stack(
              children: [
                ListView(
                  padding: const EdgeInsets.only(bottom: 96),
                  children: [
                    // hero
                    Container(
                      padding: const EdgeInsets.fromLTRB(22, 26, 22, 60),
                      decoration: const BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [AppColors.mint, Color(0xFF9BDCC3), AppColors.card],
                          stops: [0.0, 0.7, 1.0],
                        ),
                      ),
                      child: Column(
                        children: [
                          const Text('PETGO · KARTU ANABUL',
                              style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: 2,
                                  color: Colors.white)),
                          const SizedBox(height: 18),
                          EmojiAvatar(emoji: '🐱', size: 110, tone: AppColors.card),
                          const SizedBox(height: 14),
                          Text(name,
                              style: const TextStyle(
                                  fontSize: 30,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: -0.5,
                                  color: Color(0xFF1F5C45))),
                          Text('$breed · $bdayStr',
                              style: const TextStyle(
                                  fontSize: 14.5,
                                  fontWeight: FontWeight.w700,
                                  color: Color(0xCC1F5C45))),
                          const SizedBox(height: 12),
                          Text('“$bio”',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                  fontSize: 15,
                                  height: 1.5,
                                  fontWeight: FontWeight.w500,
                                  color: Color(0xFF2C6E54))),
                        ],
                      ),
                    ),
                    // 最近照片流（上移盖住 hero 底）
                    Transform.translate(
                      offset: const Offset(0, -34),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 22),
                        child: Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: AppColors.card,
                            borderRadius: BorderRadius.circular(22),
                            boxShadow: AppShadows.lg,
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                                children: const [
                                  Text('Momen terbaru',
                                      style:
                                          TextStyle(fontSize: 15, fontWeight: FontWeight.w900)),
                                  Text('5 terakhir',
                                      style: TextStyle(fontSize: 12.5, color: AppColors.muted)),
                                ],
                              ),
                              const SizedBox(height: 12),
                              StripedPhoto(label: photos[0], height: 150, radius: 16),
                              const SizedBox(height: 9),
                              Row(
                                children: [
                                  Expanded(
                                      child:
                                          StripedPhoto(label: photos[1], height: 110, radius: 16)),
                                  const SizedBox(width: 9),
                                  Expanded(
                                      child:
                                          StripedPhoto(label: photos[2], height: 110, radius: 16)),
                                ],
                              ),
                              const SizedBox(height: 9),
                              Row(
                                children: [
                                  Expanded(
                                      child:
                                          StripedPhoto(label: photos[3], height: 110, radius: 16)),
                                  const SizedBox(width: 9),
                                  Expanded(
                                      child:
                                          StripedPhoto(label: photos[4], height: 110, radius: 16)),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                    // 下载引导
                    Padding(
                      padding: const EdgeInsets.fromLTRB(22, 0, 22, 0),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              const Expanded(child: Divider(color: AppColors.line)),
                              const Padding(
                                padding: EdgeInsets.symmetric(horizontal: 14),
                                child: Momo(size: 36, happy: false),
                              ),
                              const Expanded(child: Divider(color: AppColors.line)),
                            ],
                          ),
                          const SizedBox(height: 14),
                          Text('Mau lihat cerita lengkap $name?\nUnduh TailTopia untuk ikuti tumbuh kembangnya 🐾',
                              textAlign: TextAlign.center,
                              style: const TextStyle(
                                  fontSize: 14.5, height: 1.5, color: AppColors.ink2)),
                        ],
                      ),
                    ),
                  ],
                ),
                // sticky 下载 CTA
                Positioned(
                  left: 0,
                  right: 0,
                  bottom: 0,
                  child: Container(
                    padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
                    decoration: const BoxDecoration(
                      color: AppColors.card,
                      border: Border(top: BorderSide(color: AppColors.line2)),
                    ),
                    child: Btn3d(
                      expand: true,
                      onPressed: () => Navigator.of(context).maybePop(),
                      child: const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.download_rounded, size: 20, color: Colors.white),
                          SizedBox(width: 8),
                          Text('Unduh TailTopia'),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _circle(IconData icon, VoidCallback onTap) => Material(
        color: AppColors.card,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: Padding(padding: const EdgeInsets.all(7), child: Icon(icon, size: 16, color: AppColors.ink2)),
        ),
      );

  static String _month(int m) => const [
        'Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'
      ][(m - 1).clamp(0, 11)];
}
