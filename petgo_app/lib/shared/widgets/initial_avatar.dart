import 'package:flutter/material.dart';

import '../../core/theme/colors.dart';
import 'app_image.dart';

/// 头像：有 URL 用网络图，否则彩色圆 + 昵称首字母（对齐设计稿 S17）。
///
/// ⚠️ V1.3.0 batch-b1 Story 2.4 **从 `me_page.dart` 原样抽出**（那时它是私有的
/// `_InitialAvatar`）：资料编辑抽屉与「我的」页现在住在两个文件里，都要用它。
/// **抽取时一个像素都没改**。
class InitialAvatar extends StatelessWidget {
  const InitialAvatar({
    super.key,
    required this.avatarUrl,
    required this.nickname,
    required this.radius,
  });

  final String? avatarUrl;
  final String nickname;
  final double radius;

  @override
  Widget build(BuildContext context) {
    if (avatarUrl != null && avatarUrl!.isNotEmpty) {
      return CircleAvatar(
        // key 随 URL 变：换头像后 URL 变 → 强制重建,杜绝「provider 原地变更但旧图不重绘」。
        key: ValueKey('avatar-$avatarUrl'),
        radius: radius,
        backgroundImage: AppImage.provider(avatarUrl, thumbWidth: 240),
      );
    }
    final trimmed = nickname.trim();
    if (trimmed.isEmpty) {
      return CircleAvatar(
        radius: radius,
        backgroundColor: AppColors.divider,
        child: const Icon(Icons.person, color: AppColors.textTertiary),
      );
    }
    final initial = trimmed.characters.first.toUpperCase();
    // 原型 avlg：紫色渐变（135deg #845EC9 → 深紫）+ 白首字母。
    return Container(
      width: radius * 2,
      height: radius * 2,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        shape: BoxShape.circle,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [AppColors.mint, AppColors.mint600],
        ),
      ),
      child: Text(
        initial,
        style: TextStyle(
          fontSize: radius * 0.8,
          fontWeight: FontWeight.w700,
          color: AppColors.onAccent,
        ),
      ),
    );
  }
}
