import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../data/milestone_repository.dart';
import 'card_link.dart';
import 'milestone.dart';
import 'milestone_celebration_copy.dart';
import 'share_service.dart';

/// P-35 庆祝分享（带对外 H5 链接）。先在后端为该已完成里程碑建分享拿到不可枚举 shareToken，
/// 拼出 `/m/{token}` H5 URL，连同社区文案一起走系统分享面板。
///
/// 后端建分享失败（网络 / 后端不可用）→ **退化为纯文案分享，不阻断用户**（沿用 signalCardShared 的静默风格）。
/// 提交给后端的 [title]/[body] 取客户端已本地化的庆祝文案（与弹层显示一致），杜绝后端中文泄漏。
Future<void> shareMilestoneWithLink(
  WidgetRef ref, {
  required MilestoneItem item,
  required Locale locale,
  required String petName,
  required String shareText,
  List<MilestoneItem> collection = const [],
}) async {
  var message = shareText;
  final lang = locale.languageCode == 'id' ? 'id' : 'en';
  // 「已解锁合集」快照：与 P-35 _collection 同序（按 collection 顺序取已完成项），级别串每字符 S/M/L。
  final collectionLevels =
      collection.where((m) => m.completed).map((m) => m.level.name.toUpperCase()).join();
  try {
    final copy = localizedMilestoneCelebration(item.code, locale, petName);
    final token = await ref.read(milestoneRepositoryProvider).createShare(
          item.code,
          title: copy.title,
          body: copy.body,
          locale: lang,
          collectionLevels: collectionLevels,
        );
    message = '$shareText\n${milestoneShareUrl(token)}';
    Analytics.capture('milestone_share_created', {'code': item.code, 'level': item.level.name});
  } catch (_) {
    // 退化：仅分享文案（无链接），不阻断。
  }
  await ref.read(shareServiceProvider)(message);
}
