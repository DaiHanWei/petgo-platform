import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/presentation/gift_send_animation.dart';

/// V1.4.0 gift Story 1.3 · AC5（L0）：送出动效 D1 —— 一次性 OverlayEntry、1600ms 后自移除、尊重减少动态效果。
void main() {
  late OverlayState overlay;
  final target = GlobalKey();

  Future<void> pumpHost(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) {
        return Scaffold(
          body: Align(
            alignment: Alignment.topCenter,
            child: Padding(
              padding: const EdgeInsets.only(top: 120),
              child: SizedBox(key: target, width: 62, height: 62),
            ),
          ),
        );
      }),
    ));
    overlay = tester.state<OverlayState>(find.byType(Overlay).first);
  }

  testWidgets('disableAnimations → 不插 Overlay、立即完成（调用方直接 Toast）', (tester) async {
    await pumpHost(tester);
    var done = false;
    playGiftSendAnimation(
            overlay: overlay, iconUrl: null, quantity: 3, targetKey: target, disableAnimations: true)
        .then((_) => done = true);
    await tester.pump();
    expect(done, isTrue);
    expect(find.byType(GiftSendAnimation), findsNothing);
    expect(find.byKey(const ValueKey('giftSendFlyingIcon')), findsNothing);
  });

  testWidgets('正常：飞行 → 粒子 → 1600ms 后 Overlay 移除、Future 完成', (tester) async {
    await pumpHost(tester);
    var done = false;
    playGiftSendAnimation(overlay: overlay, iconUrl: null, quantity: 3, targetKey: target)
        .then((_) => done = true);
    await tester.pump();
    expect(find.byType(GiftSendAnimation), findsOneWidget);
    final startY = tester.getCenter(find.byKey(const ValueKey('giftSendFlyingIcon'))).dy;

    await tester.pump(const Duration(milliseconds: 1000));
    // 已落到目标（头像中心）附近，粒子与「×3」出现。
    final landed = tester.getCenter(find.byKey(const ValueKey('giftSendFlyingIcon')));
    final targetCenter = tester.getCenter(find.byKey(target));
    expect((landed - targetCenter).distance, lessThan(4));
    expect(landed.dy, lessThan(startY), reason: '从屏幕底部往上飞');
    expect(find.byKey(const ValueKey('giftSendParticles')), findsOneWidget);
    expect(find.byKey(const ValueKey('giftSendQuantity')), findsOneWidget);
    expect(find.text('×3'), findsOneWidget);

    await tester.pump(const Duration(milliseconds: 700));
    await tester.pump();
    expect(find.byType(GiftSendAnimation), findsNothing, reason: '一次性播放、自己移除');
    expect(done, isTrue);
  });

  testWidgets('quantity = 1 → 无「×N」', (tester) async {
    await pumpHost(tester);
    playGiftSendAnimation(overlay: overlay, iconUrl: null, quantity: 1, targetKey: target);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1000));
    expect(find.byKey(const ValueKey('giftSendQuantity')), findsNothing);
    await tester.pump(const Duration(milliseconds: 700));
  });

  testWidgets('取不到目标 → 落点为屏幕水平中线、高 30%', (tester) async {
    await pumpHost(tester);
    playGiftSendAnimation(overlay: overlay, iconUrl: null, quantity: 1, targetKey: GlobalKey());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 1000));
    final size = tester.view.physicalSize / tester.view.devicePixelRatio;
    final landed = tester.getCenter(find.byKey(const ValueKey('giftSendFlyingIcon')));
    expect((landed - Offset(size.width / 2, size.height * 0.3)).distance, lessThan(4));
    await tester.pump(const Duration(milliseconds: 700));
  });

  test('源码：粒子用 CustomPainter、一次性 forward（不 repeat）、不引 Lottie', () {
    final src = File('lib/features/gift/presentation/gift_send_animation.dart').readAsStringSync();
    expect(src, contains('extends CustomPainter'));
    expect(src, contains('_c.forward()'));
    expect(src.contains('.repeat('), isFalse);
    expect(File('pubspec.yaml').readAsStringSync().contains('lottie'), isFalse);
  });
}
