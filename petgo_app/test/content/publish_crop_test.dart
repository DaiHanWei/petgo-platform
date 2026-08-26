import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/image_crop.dart';
import 'package:tailtopia/features/content/presentation/publish_crop_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.6 Story 3.5：批次裁剪编排与裁剪页。
///
/// <p>批次锁定规则有两处特别容易做反 —— 「区间内的图不参与」和「全在区间内时连比例都不用选」——
/// 靠手点很难覆盖全，所以把编排逻辑与界面拆开、在这里用 L0 钉住。
Uint8List _jpeg(int w, int h) =>
    Uint8List.fromList(img.encodeJpg(img.Image(width: w, height: h), quality: 80));

/// 落在容差区间内（3:4）—— 不该被打扰。
Uint8List get _inRange => _jpeg(1200, 1600);

/// 超宽（16:9）—— 需要裁。
Uint8List get _wide => _jpeg(1920, 1080);

/// 超长（9:16）—— 需要裁。
Uint8List get _tall => _jpeg(1080, 1920);

void main() {
  tearDown(() => Analytics.debugCaptureSink = null);

  group('AC3 批次锁定', () {
    /// 🛡 全部落在区间内 → **一次裁剪页都不出现**，也不涉及比例锁定。
    /// 比例锁定只在真正需要裁剪时才发生，不是每次上传都强制走一遍。
    testWidgets('全在容差区间内 → 从不询问，字节原样', (tester) async {
      var asked = 0;
      final input = [_inRange, _jpeg(1000, 1000), _jpeg(4000, 3000)];
      final out = await applyBatchCrop(input, ask: (b, s, l) async {
        asked++;
        return const CropChoice(preset: CropPreset.square, alignX: .5, alignY: .5);
      });
      expect(asked, 0);
      expect(out, isNotNull);
      for (var i = 0; i < input.length; i++) {
        expect(identical(out![i], input[i]), isTrue, reason: '免裁的图一个字节都不该动');
      }
    });

    /// 🛡 混合批次：只有超出区间的图进裁剪页，**区间内的图不受锁定影响**。
    ///
    /// 同一帖里图 1 保持 0.75、图 2 被裁成 1:1 —— 这是符合规则的正确结果，不是 bug。
    testWidgets('混合批次：只问需要裁的那些，区间内的原样', (tester) async {
      final input = [_inRange, _wide];
      var asked = 0;
      final out = await applyBatchCrop(input, ask: (b, s, l) async {
        asked++;
        return const CropChoice(preset: CropPreset.square, alignX: .5, alignY: .5);
      });
      expect(asked, 1, reason: '只有超宽那张需要裁');
      expect(identical(out![0], input[0]), isTrue, reason: '区间内的图不该被跟着改成 1:1');
      final cropped = imageSizeOf(out[1])!;
      expect(cropped.w, cropped.h, reason: '超宽那张被裁成 1:1');
    });

    /// 🔴 第二张需要裁的图**不能换档** —— 同一批次不允许一部分 1:1、一部分 4:5。
    ///
    /// 这里让 ask 在第二次故意返回另一个档位，验证编排层**不听它的**。
    testWidgets('第二张需要裁的沿用第一张的档位', (tester) async {
      final lockedSeen = <CropPreset?>[];
      final input = [_wide, _inRange, _tall];
      final out = await applyBatchCrop(input, ask: (b, s, l) async {
        lockedSeen.add(l);
        // 第二次故意返回 4:5，编排层应当忽略、继续用锁定的 1:1
        return CropChoice(
            preset: lockedSeen.length == 1 ? CropPreset.square : CropPreset.portrait,
            alignX: .5,
            alignY: .5);
      });
      expect(lockedSeen, [null, CropPreset.square],
          reason: '第一张时无锁定；第二张必须带着已锁定的档位进裁剪页');
      final first = imageSizeOf(out![0])!;
      final third = imageSizeOf(out[2])!;
      expect(first.w, first.h);
      expect(third.w, third.h, reason: '第三张也必须是 1:1，不能变成 4:5');
      expect(identical(out[1], input[1]), isTrue, reason: '夹在中间的免裁图一个字节都不该动');
    });

    testWidgets('用户在裁剪页退出 → 整批取消', (tester) async {
      final out = await applyBatchCrop([_inRange, _wide], ask: (b, s, l) async => null);
      expect(out, isNull);
    });
  });

  group('AC5 埋点 E-8', () {
    /// 🔴 这个事件的分布是**判断容差区间取值是否合适的唯一依据**。
    ///
    /// ⚠️ 事件名与 PRD 原文的 `_required` 不同：本项目命名规范要求动作落在词尾，
    /// 已订正为 `_shown`（PRD §3.2 同步改过）。
    testWidgets('每张触发裁剪的图报一次，带原始比例与批次大小', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await applyBatchCrop([_inRange, _wide, _tall], ask: (b, s, l) async =>
          const CropChoice(preset: CropPreset.square, alignX: .5, alignY: .5));

      final crops = seen.where((e) => e.$1 == 'publish_image_crop_shown').toList();
      expect(crops, hasLength(2), reason: '只有两张超出区间');
      expect(crops.every((e) => e.$2!['batch_size'] == 3), isTrue,
          reason: 'batch_size 是本次选图的总张数，含免裁的');
      expect(crops.first.$2!['original_ratio'], closeTo(1920 / 1080, 0.001));
      expect(crops.last.$2!['original_ratio'], closeTo(1080 / 1920, 0.001));
    });


    /// E-9：确认裁剪。`is_batch_lock_source` 区分"主动选的档位"与"被前一张锁进来的"。
    testWidgets('确认裁剪上报目标比例，并标出哪一张是锁定源', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await applyBatchCrop([_wide, _inRange, _tall], ask: (b, s, l) async =>
          const CropChoice(preset: CropPreset.portrait, alignX: .5, alignY: .5));

      final done = seen.where((e) => e.$1 == 'publish_image_crop_completed').toList();
      expect(done, hasLength(2), reason: '只有两张需要裁');
      expect(done.first.$2!['target_ratio'], '4x5');
      expect(done.first.$2!['is_batch_lock_source'], isTrue, reason: '第一张定档');
      expect(done.last.$2!['is_batch_lock_source'], isFalse, reason: '第二张是被锁进来的');
    });

    /// E-10：退出裁剪。带原始比例 —— 与 E-8 对照才看得出哪一类图让用户直接放弃。
    testWidgets('退出裁剪上报原始比例', (tester) async {
      final seen = <(String, Map<String, Object>?)>[];
      Analytics.debugCaptureSink = (e, p) => seen.add((e, p));

      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: PublishCropPage(bytes: _wide, size: const ImageSize(1920, 1080)),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('cropClose')));
      await tester.pumpAndSettle();

      final quit = seen.where((e) => e.$1 == 'publish_image_crop_exit_tapped').toList();
      expect(quit, hasLength(1));
      expect(quit.first.$2!['original_ratio'], closeTo(1920 / 1080, 0.001));
    });

    testWidgets('全在区间内 → 一条都不报（理想状态就是这个事件量很小）', (tester) async {
      final seen = <String>[];
      Analytics.debugCaptureSink = (e, p) => seen.add(e);
      await applyBatchCrop([_inRange], ask: (b, s, l) async => null);
      expect(seen.where((e) => e.startsWith('publish_image_crop')), isEmpty);
    });
  });

  group('AC2/AC4 裁剪页', () {
    Future<void> pump(WidgetTester tester, {CropPreset? locked}) async {
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: PublishCropPage(
          bytes: _wide,
          size: const ImageSize(1920, 1080),
          lockedPreset: locked,
        ),
      ));
      await tester.pumpAndSettle();
    }

    /// 🔴 退出在**左上角**，前进在**底部** —— 右上角按平台惯例是前进位。
    ///
    /// ⚠️ UI 稿屏 08 的图注仍写「右上角返回按钮」，那是未同步 PRD 2026-08-07 订正的旧稿。
    testWidgets('退出按钮在左上角，前进按钮在底部', (tester) async {
      await pump(tester);
      final screen = tester.getRect(find.byType(Scaffold));
      final close = tester.getRect(find.byKey(const ValueKey('cropClose')));
      final apply = tester.getRect(find.byKey(const ValueKey('cropApply')));

      expect(close.center.dx, lessThan(screen.center.dx), reason: '退出必须在左半边');
      expect(close.center.dy, lessThan(screen.height * 0.25), reason: '退出必须在顶部');
      expect(apply.center.dy, greaterThan(screen.height * 0.75), reason: '前进动作在底部');
      expect(apply.center.dx, closeTo(screen.center.dx, 1), reason: '底部主按钮通栏居中');
    });

    /// 🛡 只有两档，**刻意不给横图档**。谁想"顺手补个 16:9"，这条会红。
    testWidgets('只有 1:1 与 4:5 两档', (tester) async {
      await pump(tester);
      expect(find.byKey(const ValueKey('cropPreset_1:1')), findsOneWidget);
      expect(find.byKey(const ValueKey('cropPreset_4:5')), findsOneWidget);
      expect(CropPreset.values, hasLength(2));
      expect(find.byKey(const ValueKey('cropPreset_16:9')), findsNothing);
    });

    /// 档位已锁定时不该再让用户换档。
    testWidgets('档位锁定时不显示档位选择', (tester) async {
      await pump(tester, locked: CropPreset.portrait);
      expect(find.byKey(const ValueKey('cropPreset_1:1')), findsNothing);
      expect(find.byKey(const ValueKey('cropPreset_4:5')), findsNothing);
    });

    testWidgets('点应用返回所选档位', (tester) async {
      CropChoice? got;
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              got = await Navigator.of(ctx).push<CropChoice>(MaterialPageRoute<CropChoice>(
                builder: (_) => PublishCropPage(
                    bytes: _wide, size: const ImageSize(1920, 1080)),
              ));
            },
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('cropPreset_4:5')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('cropApply')));
      await tester.pumpAndSettle();
      expect(got!.preset, CropPreset.portrait);
    });
  });
}
