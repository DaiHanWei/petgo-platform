import 'package:flutter/material.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/pill_tag.dart';
import '../../../shared/widgets/design/striped_photo.dart';

/// Gabung Gath 宠物聚会（TailTopia Prototype 占位页）。
///
/// V1 演示范围外，仅占位展示活动卡样式。完整含：发起活动、海报生成、报名管理、
/// 临时群聊、GPS 签到（FR-6→FR-10）。
class GathPage extends StatelessWidget {
  const GathPage({super.key});

  static const _events = [
    ('Corgi Gathering Tribeca', 'Min, 8 Jun · 16.00', 'Tribeca Park, Jakpus', '12/20', '🐶'),
    ('Cat Lovers Picnic', 'Sab, 14 Jun · 09.00', 'Taman Menteng', '7/15', '🐱'),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(
        backgroundColor: AppColors.cream,
        elevation: 0,
        scrolledUnderElevation: 0,
        foregroundColor: AppColors.ink,
        title: const Text('Gabung Gath',
            style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: -0.4)),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(18, 4, 18, 24),
        children: [
          const Padding(
            padding: EdgeInsets.only(bottom: 12),
            child: Text('Kumpul bareng anabul di Jakarta 📍',
                style: TextStyle(fontSize: 13, color: AppColors.muted)),
          ),
          for (final e in _events) ...[
            _EventCard(title: e.$1, when: e.$2, loc: e.$3, count: e.$4, emoji: e.$5),
            const SizedBox(height: 14),
          ],
          const SizedBox(height: 4),
          const Center(
            child: Text('Acara / Pendaftaran / Grup / Check-in GPS — pratinjau, masih placeholder',
                style: TextStyle(fontSize: 12, color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}

class _EventCard extends StatelessWidget {
  const _EventCard({
    required this.title,
    required this.when,
    required this.loc,
    required this.count,
    required this.emoji,
  });

  final String title;
  final String when;
  final String loc;
  final String count;
  final String emoji;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppShadows.md,
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const StripedPhoto(label: 'poster acara', height: 130, radius: 0),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    PillTag(label: '$emoji Open'),
                    const SizedBox(width: 8),
                    PillTag(label: '$count ikut', color: AppColors.muted, background: AppColors.cream2),
                  ],
                ),
                const SizedBox(height: 8),
                Text(title,
                    style: const TextStyle(fontSize: 16.5, fontWeight: FontWeight.w900)),
                const SizedBox(height: 6),
                _line(Icons.calendar_today_outlined, when),
                const SizedBox(height: 4),
                _line(Icons.place_outlined, loc),
                const SizedBox(height: 12),
                Btn3d(
                  expand: true,
                  onPressed: () => ScaffoldMessenger.of(context)
                    ..clearSnackBars()
                    ..showSnackBar(const SnackBar(content: Text('Pendaftaran segera hadir 🐾'))),
                  fontSize: 14.5,
                  child: const Text('Daftar ikut'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _line(IconData icon, String text) => Row(
        children: [
          Icon(icon, size: 15, color: AppColors.muted),
          const SizedBox(width: 7),
          Expanded(
            child: Text(text, style: const TextStyle(fontSize: 13, color: AppColors.ink2)),
          ),
        ],
      );
}
