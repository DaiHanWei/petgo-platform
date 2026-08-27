import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/analytics/analytics.dart';
import '../../../../l10n/app_localizations.dart';
import '../../data/detail_repository.dart';
import '../../domain/content_detail.dart';
import '../../domain/share_card_data.dart';
import 'share_card_preview_page.dart';

/// 打开分享卡预览 —— **详情页与信息流共用的唯一入口**（bug 20260826 新增信息流入口时抽出）。
///
/// 🔴 抽出来的理由是**埋点**：E-11 是分享漏斗的起点，它的事件名与三个属性来自埋点清单 §3。
/// 两处各写一份，迟早有一处漏属性或改了词表 —— 而看板维度一旦发版就改不动了
/// （E-12/E-13 已经因为这个返工过一次）。现在只有这一处会上报。
///
/// ⚠️ **刻意不加 `source` 属性区分「详情页 / 信息流」**：E-11 在清单里只有这三个属性，
/// 顺手加一个不在词表里的属性正是上次返工的成因。要区分入口应当先改清单再改代码。
class ShareCardEntry {
  ShareCardEntry._();

  /// 埋点 `content_type` 词表（`diary`/`moment`/`tips`）。
  ///
  /// 🔴 **刻意做一次显式映射**，不把线格式 `GROWTH_MOMENT`/`DAILY`/`KNOWLEDGE` 直接发上去 ——
  /// 埋点清单 §3 把这两套写法并列标了「需与工程统一」。
  static String analyticsContentType(String wireType) => switch (wireType) {
        'GROWTH_MOMENT' => 'diary',
        'KNOWLEDGE' => 'tips',
        _ => 'moment',
      };

  /// 已经拿到 [ContentDetail] 时的入口（详情页走这条）。
  ///
  /// E-11 在**点击这一刻**上报：放在取链接成功之后，会把「取链接失败」的人从分母里抹掉，
  /// 而那批人恰恰是这个漏斗最该看见的流失。
  static Future<void> openForDetail(
    BuildContext context,
    WidgetRef ref,
    ContentDetail detail,
  ) async {
    final l10n = AppLocalizations.of(context);
    Analytics.capture('post_share_card_tapped', {
      'content_type': analyticsContentType(detail.type),
      'is_private_diary': detail.isPrivateDiary,
      'has_image': detail.imageUrls.isNotEmpty,
    });
    try {
      final url = await ref.read(detailRepositoryProvider).getShareUrl(detail.id);
      if (!context.mounted) return;
      final data = ShareCardData.fromDetail(
        detail,
        shareUrl: url,
        fallbackAuthorName: l10n.feedDeletedUser,
      );
      await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => ShareCardPreviewPage(data: data),
      ));
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(l10n.shareCardExportError)));
      }
    }
  }

  /// 只有帖子 id 时的入口（**信息流**走这条，bug 20260826）。
  ///
  /// ⚠️ 先取详情再走 [openForDetail]，而不是拿信息流那份轻量数据直接拼卡：
  /// 卡面要的正文全文、作者头像、首图原图在流里都是裁过的摘要版，
  /// 拿它拼出来的卡会与从详情页分享出去的**不是同一张**，而没有任何东西会提示这件事。
  /// 代价是多一次请求，发生在用户已经点了分享之后，可以接受。
  static Future<void> openForPostId(
    BuildContext context,
    WidgetRef ref,
    int postId,
  ) async {
    final l10n = AppLocalizations.of(context);
    try {
      final detail = await ref.read(detailRepositoryProvider).getDetail(postId);
      if (!context.mounted) return;
      await openForDetail(context, ref, detail);
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(l10n.shareCardExportError)));
      }
    }
  }
}
