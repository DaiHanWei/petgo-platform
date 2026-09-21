import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/gestures.dart' show kLongPressTimeout;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/content/data/content_repository.dart';
import 'package:tailtopia/features/content/domain/content_type.dart';
import 'package:tailtopia/features/content/domain/feed_image_layout.dart';
import 'package:tailtopia/features/content/domain/publish_controller.dart';
import 'package:tailtopia/features/content/presentation/publish_compose_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.3.0 批次 A · Story 4.1（L0）：发布页拖拽重排与封面角标（FR-116 · AD-A16 / AD-A27）。
///
/// 拖拽手感必须真机验；这里钉住 L0 能钉的三件：
/// **顺序只有一份**（AC3）、**上传期锁定**（AC4/AC5，「上传中还能拖」必然产生两份顺序）、
/// **封面不落字段**（AC2 的反面）、以及没引 reorder 类包（AC8）。
class _FakeRepo implements ContentRepository {
  List<String>? lastUrls;
  List<ImageSize?>? lastSizes;

  @override
  Future<int> publish({
    required ContentType type,
    int? petId,
    String? text,
    List<String> imageUrls = const [],
    List<ImageSize?> imageSizes = const [],
    DateTime? eventDate,
    required String idempotencyKey,
    bool syncToMoment = true,
    List<int> mentionedUserIds = const [],
  }) async {
    lastUrls = imageUrls;
    lastSizes = imageSizes;
    return 123;
  }
}

void main() {
  Uint8List bytes(String s) => Uint8List.fromList(utf8.encode(s));

  ({PublishController c, _FakeRepo repo, Set<String> failing}) make() {
    final repo = _FakeRepo();
    final failing = <String>{};
    final c = PublishController(
      repository: repo,
      uploadOne: (b) async {
        final content = utf8.decode(b);
        if (failing.contains(content)) throw Exception('upload failed');
        return 'https://cdn/$content.jpg';
      },
    );
    return (c: c, repo: repo, failing: failing);
  }

  List<String> order(PublishController c) =>
      [for (final i in c.items) utf8.decode(i.bytes)];

  group('AC1/AC3 重排就是改这一份顺序', () {
    test('往前挪：把第 3 张拖到第 1 位', () {
      final m = make();
      for (final n in ['a', 'b', 'c']) {
        m.c.addImage(bytes(n));
      }

      m.c.reorderImage(2, 0);

      expect(order(m.c), ['c', 'a', 'b']);
    });

    test('往后挪：把第 1 张拖到最后', () {
      final m = make();
      for (final n in ['a', 'b', 'c']) {
        m.c.addImage(bytes(n));
      }

      m.c.reorderImage(0, 2);

      expect(order(m.c), ['b', 'c', 'a']);
    });

    test('拖回原位 / 越界下标 → 什么都不发生，不崩', () {
      final m = make();
      for (final n in ['a', 'b']) {
        m.c.addImage(bytes(n));
      }

      m.c.reorderImage(1, 1);
      m.c.reorderImage(5, 0);
      m.c.reorderImage(0, 9);
      m.c.reorderImage(-1, 0);

      expect(order(m.c), ['a', 'b']);
    });

    /// 🔴 AC3：**界面顺序即上传顺序**。这条是整个 story 的落点 ——
    /// 发布时提交的 URL 序列必须是用户拖完之后的样子。
    test('发布时提交的是拖完之后的顺序', () async {
      final m = make();
      for (final n in ['a', 'b', 'c']) {
        m.c.addImage(bytes(n));
      }
      m.c.reorderImage(2, 0); // c 提到首位 → 它成为封面

      await m.c.publish(idempotencyKey: 'k');

      expect(m.repo.lastUrls, [
        'https://cdn/c.jpg',
        'https://cdn/a.jpg',
        'https://cdn/b.jpg',
      ]);
      // 尺寸与 URL 必须同序等长（后端对长度/顺序不符是整组作废）。
      expect(m.repo.lastSizes, hasLength(3));
    });
  });

  group('AC2 封面是顺序的投影，不是一个字段', () {
    /// 另设「哪张是封面」的字段，迟早出现「界面第一张是 A、封面却是 B」。
    /// 所以判据很直接：控制器里**根本没有**这样的字段或方法。
    test('控制器没有任何 cover 字段 / setCover 方法', () {
      final src =
          File('lib/features/content/domain/publish_controller.dart').readAsStringSync();
      for (final banned in ['coverIndex', 'setCover', 'coverId', 'isCover']) {
        expect(src, isNot(contains(banned)), reason: '封面只能是「第 0 张」这件事的投影');
      }
    });

    test('角标文案两语都在（EN Cover / ID Sampul）', () {
      final en = File('lib/l10n/app_en.arb').readAsStringSync();
      final id = File('lib/l10n/app_id.arb').readAsStringSync();
      expect(en, contains('"publishCoverBadge": "Cover"'));
      expect(id, contains('"publishCoverBadge": "Sampul"'));
    });
  });

  group('AC4 🔴 上传期锁定', () {
    test('有图正在上传 → 不能重排，拖了也不动', () async {
      final m = make();
      for (final n in ['a', 'b']) {
        m.c.addImage(bytes(n));
      }
      final pending = m.c.uploadAll(); // 不 await：上传在途
      expect(m.c.isUploading, isTrue);
      expect(m.c.canReorder, isFalse);

      m.c.reorderImage(1, 0);
      expect(order(m.c), ['a', 'b'], reason: '上传中拖拽必须一动不动');

      await pending;
      expect(m.c.canReorder, isTrue, reason: '传完就该能拖了');
    });

    test('上传结束 → 解锁，此时重排照常生效', () async {
      final m = make();
      for (final n in ['a', 'b']) {
        m.c.addImage(bytes(n));
      }
      await m.c.uploadAll();

      m.c.reorderImage(1, 0);

      expect(order(m.c), ['b', 'a']);
    });
  });

  group('AC5 🔴 部分成功后重试沿用同一份顺序', () {
    test('发布中某张失败 → 会话保持锁定，不能改顺序', () async {
      final m = make();
      m.c.addImage(bytes('good'));
      m.c.addImage(bytes('bad'));
      m.failing.add('bad');

      final id = await m.c.publish(idempotencyKey: 'k');

      expect(id, isNull, reason: '有失败件就不提交');
      expect(m.c.hasFailed, isTrue);
      // 🔴 锁不解除：重试要沿用同一份顺序。这一步若解锁，用户可以在两次尝试之间
      // 把顺序改掉，于是"重试"重试的就不是同一件事了。
      expect(m.c.canReorder, isFalse);

      m.c.reorderImage(1, 0);
      expect(order(m.c), ['good', 'bad'], reason: '锁定期拖拽不生效');
    });

    test('整体取消 → 回到可编辑态，才可以改顺序', () async {
      final m = make();
      m.c.addImage(bytes('good'));
      m.c.addImage(bytes('bad'));
      m.failing.add('bad');
      await m.c.publish(idempotencyKey: 'k');

      m.c.cancelUploadSession();

      expect(m.c.canReorder, isTrue);
      m.c.reorderImage(1, 0);
      expect(order(m.c), ['bad', 'good']);
    });

    test('重试成功后再发布 → 解锁，且顺序仍是当初那一份', () async {
      final m = make();
      for (final n in ['a', 'bad', 'c']) {
        m.c.addImage(bytes(n));
      }
      m.failing.add('bad');
      await m.c.publish(idempotencyKey: 'k'); // 失败 → 锁住

      m.failing.remove('bad');
      await m.c.retryFailed();
      final id = await m.c.publish(idempotencyKey: 'k');

      expect(id, 123);
      expect(m.repo.lastUrls, [
        'https://cdn/a.jpg',
        'https://cdn/bad.jpg',
        'https://cdn/c.jpg',
      ]);
      expect(m.c.canReorder, isTrue, reason: '发布成功，会话结束');
    });

    /// 🔴 batch-a 复审 #9：锁的唯一理由是「有失败件等重试」。失败件没了锁必须放掉 ——
    /// 「整体取消」按钮随 hasFailed 一起消失，锁还在的话顺序就被永久锁死。
    test('重试成功（未再发布）→ 失败件没了，锁随之放掉', () async {
      final m = make();
      m.c.addImage(bytes('good'));
      m.c.addImage(bytes('bad'));
      m.failing.add('bad');
      await m.c.publish(idempotencyKey: 'k');
      expect(m.c.canReorder, isFalse);

      m.failing.remove('bad');
      await m.c.retryFailed();

      expect(m.c.hasFailed, isFalse, reason: '取消按钮随之消失');
      expect(m.c.canReorder, isTrue, reason: '没有出口时锁不能留着');
      m.c.reorderImage(1, 0);
      expect(order(m.c), ['bad', 'good']);
    });

    test('删掉失败的那张 → 锁随之放掉', () async {
      final m = make();
      m.c.addImage(bytes('a'));
      m.c.addImage(bytes('bad'));
      m.c.addImage(bytes('c'));
      m.failing.add('bad');
      await m.c.publish(idempotencyKey: 'k');
      expect(m.c.canReorder, isFalse);

      m.c.removeImage(1);

      expect(m.c.hasFailed, isFalse);
      expect(m.c.canReorder, isTrue);
    });

    test('重试仍失败 → 锁保持（出口按钮还在）', () async {
      final m = make();
      m.c.addImage(bytes('good'));
      m.c.addImage(bytes('bad'));
      m.failing.add('bad');
      await m.c.publish(idempotencyKey: 'k');

      await m.c.retryFailed();

      expect(m.c.hasFailed, isTrue);
      expect(m.c.canReorder, isFalse);
    });

    /// 🔴 AC3 的另一面：**不存在两套顺序**。
    /// 收口方式不是"再存一份快照"（那恰恰造出第二份数据），而是把 items 本身冻住 ——
    /// 所以控制器里不该有任何第二个图片列表。
    test('控制器里只有一份图片列表', () {
      final src =
          File('lib/features/content/domain/publish_controller.dart').readAsStringSync();
      final listFields = RegExp(r'List<ImageUploadItem>[?]?\s+\w+')
          .allMatches(src)
          .map((m) => m.group(0)!)
          .toList();
      expect(listFields, hasLength(1), reason: '第二个列表就是第二份顺序：$listFields');
    });
  });


  group('AC2/AC4 网格上的两件事（widget 层）', () {
    /// 真 PNG：缩略图要真的解码出来，塞假字节会在绘制时抛异常。
    Uint8List png(int r, int g, int b) {
      final im = img.Image(width: 8, height: 8);
      img.fill(im, color: img.ColorRgb8(r, g, b));
      return Uint8List.fromList(img.encodePng(im));
    }

    Future<PublishController> pumpGrid(WidgetTester tester, {int images = 3}) async {
      tester.view.physicalSize = const Size(1200, 3200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final repo = _FakeRepo();
      final c = PublishController(
        repository: repo,
        // 挂起不返回：用来制造"上传在途"这一态。
        uploadOne: (b) => Completer<String>().future,
      );
      for (var i = 0; i < images; i++) {
        c.addImage(png(10 * i, 20, 30));
      }
      final container = ProviderContainer(
          overrides: [publishControllerProvider.overrideWithValue(c)]);
      addTearDown(container.dispose);

      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('en'),
          home: const Scaffold(body: PublishComposePage()),
        ),
      ));
      await tester.pump();
      return c;
    }

    testWidgets('封面角标只有一个，挂在第一格', (tester) async {
      await pumpGrid(tester);

      expect(find.byKey(const ValueKey('publishCoverBadge')), findsOneWidget);
      final l10n = await AppLocalizations.delegate.load(const Locale('en'));
      expect(find.text(l10n.publishCoverBadge), findsOneWidget);
    });

    testWidgets('可编辑态：每格都能长按拖起', (tester) async {
      await pumpGrid(tester);
      expect(find.byType(LongPressDraggable<int>), findsNWidgets(3));
    });

    /// U2：拖动途中其余格**实时让位**，但那只是 UI 层的预览 ——
    /// controller 的顺序在松手前一动不动，松手才落一次（AC3 顺序只有一份）。
    testWidgets('拖动中预览让位、controller 不变；松手才落 controller', (tester) async {
      final c = await pumpGrid(tester);
      final original = [for (final i in c.items) i.bytes];
      Finder cellOf(int data) => find.byWidgetPredicate(
          (w) => w is LongPressDraggable<int> && w.data == data);
      final Offset slot0 = tester.getTopLeft(cellOf(0));
      final Offset slot1 = tester.getTopLeft(cellOf(1));

      // 长按第 3 张抬起，拖到第 1 张的位置上。
      final g = await tester.startGesture(tester.getCenter(cellOf(2)));
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      await g.moveBy(const Offset(-5, 0));
      await tester.pump();
      await g.moveTo(tester.getCenter(cellOf(0)));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300)); // 让位动画走完

      // 预览：第 3 张的空位移到首位，原第 1、2 张各后退一格。
      expect(tester.getTopLeft(cellOf(2)), slot0);
      expect(tester.getTopLeft(cellOf(0)), slot1);
      // 🔴 controller 一动不动。
      expect([for (final i in c.items) i.bytes], original);
      // 拖动途中所有 ✕ 都收起（反馈卡本身也不带 ✕）。
      expect(find.byIcon(Icons.close), findsNothing);

      await g.up();
      // 不能 pumpAndSettle：pending 态的格子带着常转的 spinner，永远 settle 不了。
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect([for (final i in c.items) i.bytes], [original[2], original[0], original[1]]);
      expect(find.byIcon(Icons.close), findsNWidgets(3), reason: '松手后 ✕ 回来');
    });

    testWidgets('拖出网格再松手 → 预览复原，controller 不变', (tester) async {
      final c = await pumpGrid(tester);
      final original = [for (final i in c.items) i.bytes];
      Finder cellOf(int data) => find.byWidgetPredicate(
          (w) => w is LongPressDraggable<int> && w.data == data);
      final Offset home2 = tester.getTopLeft(cellOf(2));

      final g = await tester.startGesture(tester.getCenter(cellOf(2)));
      await tester.pump(kLongPressTimeout + const Duration(milliseconds: 50));
      await g.moveTo(tester.getCenter(cellOf(0)));
      await tester.pump();
      await g.moveTo(const Offset(600, 3000)); // 远离网格
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(tester.getTopLeft(cellOf(2)), home2, reason: '拖出网格，空位回到原处');

      await g.up();
      // 不能 pumpAndSettle：pending 态的格子带着常转的 spinner，永远 settle 不了。
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect([for (final i in c.items) i.bytes], original);
    });

    /// 🔴 AC4：锁定期**整个拖拽入口不挂**，不是挂上去再判空 ——
    /// 挂着的话手指还是能把图抬起来，只是放下没反应，看着像卡了。
    testWidgets('上传在途：拖拽入口整个不挂', (tester) async {
      final c = await pumpGrid(tester);
      unawaited(c.uploadAll());
      await tester.pump();

      expect(c.canReorder, isFalse);
      expect(find.byType(LongPressDraggable<int>), findsNothing);
      // 角标还在（它是顺序的投影，不随锁定消失），只是不会再跟着谁跑了。
      expect(find.byKey(const ValueKey('publishCoverBadge')), findsOneWidget);
    });
  });

  group('AC6/AC8 不扩散、不引包', () {
    /// 已发布内容的图片重排属内容编辑范畴，本版本不做 —— 重排入口只在发布页。
    test('重排入口只存在于发布页', () {
      final hits = <String>[];
      for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
        if (!f.path.endsWith('.dart')) continue;
        if (f.readAsStringSync().contains('reorderImage')) hits.add(f.path);
      }
      expect(hits, hasLength(2), reason: '只应是控制器与发布页两处：$hits');
      expect(hits.any((p) => p.endsWith('publish_compose_page.dart')), isTrue);
      expect(hits.any((p) => p.endsWith('publish_controller.dart')), isTrue);
    });

    /// AD-A22：拖拽重排**自实现**，pubspec 里至今没有任何 reorder 类依赖。
    test('没引 reorder / drag 类第三方包', () {
      final pubspec = File('pubspec.yaml').readAsStringSync().toLowerCase();
      for (final banned in ['reorderable', 'drag_and_drop', 'draggable_grid', 'flutter_reorder']) {
        expect(pubspec, isNot(contains(banned)));
      }
    });
  });
}
