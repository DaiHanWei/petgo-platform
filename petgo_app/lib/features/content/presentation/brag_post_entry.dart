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
/// V1.4.2 social-topic Story 3.1（D-21 · G-6）：第 5 个来源「里程碑庆祝页」同样走这里（仍不自建发布流程）——
/// 它要「先转好 JPEG → 关庆祝页 → 再开发布页」，故拆出 [BragPostEntry.prepare] / [BragPostEntry.openPrepared]；
/// 并带 Diary 预设 + 宠物预选 + 来源（发布成功的 `diary_published` 据此追加 `source`）。
/// 🔴 **绝不**向 `PublishComposePage.open` 传 `milestoneCode`（那是「去发布」自动打卡用的，会把新帖关联成打卡内容）。
/// 命名避开 `share`：这是「发到站内」，与出站的分享卡预览 / 存相册分开，埋点口径也不混。
/// 「Pamer di postingan」入口来源（埋点 `brag_post_tapped.source` 值域）。
enum BragPostSource {
  tailsonalityResult('tailsonality_result'),
  tailsonalityMatch('tailsonality_match'),
  matchPreview('match_preview'),
  boardingPass('boarding_pass'),

  /// V1.4.2 Story 3.1：里程碑庆祝页「Share to community」。
  milestoneCelebration('milestone_celebration');

  const BragPostSource(this.wire);
  final String wire;
}

class BragPostEntry {
  BragPostEntry._();

  /// 入口点击埋点（待确认 4.12，2026-10-02）：**点击那一刻**报（截图 / 转码失败也算一次点击），
  /// 看各入口的使用量。不带卡面内容 / token。
  ///
  /// [extra]（V1.4.2 Story 3.1 · PRD §4 FR-156）：合并进属性 —— 里程碑来源带 `code` / `level`；
  /// 原 4 处调用点不传，属性仍只有 `source`。🛡 不放宠物名等用户数据。
  static void reportTap(BragPostSource source, {Map<String, Object> extra = const {}}) =>
      Analytics.capture('brag_post_tapped', {...extra, 'source': source.wire});

  /// 图像处理测试缝：`compute` 起真 isolate，widget test 的假时钟里不会完成。
  @visibleForTesting
  static Future<Uint8List> Function(Uint8List png)? prepareForTest;

  static bool _busy = false;

  /// 是否正在处理（入口按钮据此 disabled）。
  static bool get busy => _busy;

  /// 转换失败 → 轻提示 `shareCardExportError`、**不**打开发帖页。进行中重复调用直接忽略。
  ///
  /// = [prepare] + [openPrepared]。新参数全部可选，默认值与 V1.4.2 前逐字节等价（Moment、不预选宠物、无来源）。
  static Future<void> open(
    BuildContext context, {
    required Uint8List cardPng,
    required String text,
    ContentType preset = ContentType.daily,
    List<int>? petIds,
    BragPostSource? source,
  }) async {
    final jpeg = await prepare(context, cardPng);
    if (jpeg == null || !context.mounted) return;
    await openPrepared(context, jpeg: jpeg, text: text, preset: preset, petIds: petIds, source: source);
  }

  /// 卡图 PNG → 可上传 JPEG（isolate）。失败 → 轻提示 `shareCardExportError`（[toastExtraBottom] 抬高，
  /// 默认 0 = 原位置）并返回 null；进行中重复调用直接返回 null（[busy] 防重入）。
  static Future<Uint8List?> prepare(BuildContext context, Uint8List png, {double toastExtraBottom = 0}) async {
    if (_busy) return null;
    _busy = true;
    try {
      return await (prepareForTest ?? prepareBragPostImage)(png);
    } catch (_) {
      // ImageProcessingException（解不开）为主；isolate 里的其它异常同样不该把用户带进一个没图的发帖页。
      if (context.mounted) {
        showAppToast(context, AppLocalizations.of(context).shareCardExportError, extraBottom: toastExtraBottom);
      }
      return null;
    } finally {
      _busy = false;
    }
  }

  /// 用已转好的 [jpeg] 打开发帖页（预填 [text] + 1 张图）。[preset] 缺省 Moment（UI A16b）；
  /// [petIds] 预选宠物（发布页会过滤非 ACTIVE）；[source] 透传给发布页做发布成功埋点。
  static Future<void> openPrepared(
    BuildContext context, {
    required Uint8List jpeg,
    required String text,
    ContentType preset = ContentType.daily,
    List<int>? petIds,
    BragPostSource? source,
  }) =>
      PublishComposePage.open(
        context,
        preset: preset,
        initialText: text,
        initialImages: [jpeg],
        initialPetIds: petIds,
        entrySource: source,
      );
}
