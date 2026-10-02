import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../../../core/analytics/analytics.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../domain/brag_post_image.dart';
import '../domain/content_type.dart';
import 'publish_compose_page.dart';

/// 「Pamer di postingan」统一入口（V1.3.2 Story 4.4 · AD-11）：卡图 → 可上传 JPEG → 带图带字打开发帖页。
///
/// 结果页 ⋯ / 配型页 / 配型卡预览 / 登机牌 B3c 四处都走这里，**不自建发布流程**。
/// 命名避开 `share`：这是「发到站内」，与出站的分享卡预览 / 存相册分开，埋点口径也不混。
/// 「Pamer di postingan」入口来源（埋点 `brag_post_tapped.source` 值域）。
enum BragPostSource {
  tailsonalityResult('tailsonality_result'),
  tailsonalityMatch('tailsonality_match'),
  matchPreview('match_preview'),
  boardingPass('boarding_pass');

  const BragPostSource(this.wire);
  final String wire;
}

class BragPostEntry {
  BragPostEntry._();

  /// 入口点击埋点（待确认 4.12，2026-10-02）：**点击那一刻**报（截图 / 转码失败也算一次点击），
  /// 看四个入口各自的使用量。不带卡面内容 / token。
  static void reportTap(BragPostSource source) =>
      Analytics.capture('brag_post_tapped', {'source': source.wire});

  /// 图像处理测试缝：`compute` 起真 isolate，widget test 的假时钟里不会完成。
  @visibleForTesting
  static Future<Uint8List> Function(Uint8List png)? prepareForTest;

  static bool _busy = false;

  /// 是否正在处理（入口按钮据此 disabled）。
  static bool get busy => _busy;

  /// 转换失败 → 轻提示 `shareCardExportError`、**不**打开发帖页。进行中重复调用直接忽略。
  static Future<void> open(BuildContext context, {required Uint8List cardPng, required String text}) async {
    if (_busy) return;
    _busy = true;
    final Uint8List jpeg;
    try {
      jpeg = await (prepareForTest ?? prepareBragPostImage)(cardPng);
    } catch (_) {
      // ImageProcessingException（解不开）为主；isolate 里的其它异常同样不该把用户带进一个没图的发帖页。
      if (context.mounted) showAppToast(context, AppLocalizations.of(context).shareCardExportError);
      return;
    } finally {
      _busy = false;
    }
    if (!context.mounted) return;
    await PublishComposePage.open(
      context,
      // UI A16b：类型预选 Momen。
      preset: ContentType.daily,
      initialText: text,
      initialImages: [jpeg],
    );
  }
}
