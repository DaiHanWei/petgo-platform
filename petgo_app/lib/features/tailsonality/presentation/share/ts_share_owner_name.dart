import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../auth/domain/auth_state.dart';

/// 分享卡上的主人名 = 当前登录用户昵称（取法照 `age_card_page.dart` 的 `_pawrentName`）。
///
/// 🔴 **拿不到就返回 null，卡上不显示** —— 不兜底成邮箱（PII，这张图会发给陌生人）、
/// 也不填「Kamu」这类占位（用户会以为自己的名字没存上）。结果卡 / 配型卡共用。
String? tsShareOwnerName(WidgetRef ref) {
  final profile = ref.read(authControllerProvider).profile;
  final name = profile?.nickname?.trim();
  if (name != null && name.isNotEmpty) return name;
  final display = profile?.displayName?.trim();
  return display == null || display.isEmpty ? null : display;
}
