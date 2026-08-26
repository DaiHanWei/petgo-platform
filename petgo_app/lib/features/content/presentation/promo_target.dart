import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../app.dart';
import '../../../core/router/deep_link_routes.dart';
import '../domain/pinned_slot.dart';

/// 打开推广卡片的跳转目标（V1.1.6 Story 4.3 · AC1）。
///
/// 🛡 **复用既有深链设施，不新建体系**：
/// - 外部链接 → 交给系统浏览器（项目里兽医页与登录页的条款链接已是同款用法）。
/// - App 内部深链 → 丢给**既有**的 `tailtopia://` 映射函数换算成站内位置。
///
/// 🔴 **认不出的目标什么都不做** —— 既不崩也不弹错。
/// 运营在后台把链接填错一个字符，不该让首页出问题；用户看到的只是"点了没反应"，
/// 这比弹一个看不懂的报错好。
Future<void> openPromoTarget(BuildContext context, PromoCard promo) async {
  final url = promo.linkUrl;
  if (url == null) return;
  final uri = Uri.tryParse(url);
  if (uri == null) return;

  switch (promo.jumpTarget) {
    case PromoJumpTarget.externalUrl:
      // 失败（无浏览器 / 被系统拒绝）同样静默 —— 不给用户一个他解决不了的错误。
      try {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      } catch (_) {
        // 静默
      }
    case PromoJumpTarget.deeplink:
      final location = deepLinkToLocation(uri);
      if (location == null || !context.mounted) return;
      // 分支根只能 go（push 会二次构建 shell → GlobalKey 撞车），沿用既有判定。
      if (DeepLinkRoutes.isShellTabRoot(location)) {
        context.go(location);
      } else {
        context.push(location);
      }
    case PromoJumpTarget.unknown:
      return;
  }
}
