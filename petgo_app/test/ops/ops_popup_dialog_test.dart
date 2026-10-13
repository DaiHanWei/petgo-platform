import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/features/ops/presentation/ops_popup_dialog.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// Story 5.4 · AC2 / AC5：弹窗结构断言（不写 golden，视觉 L2 留本地）。

/// 永不完成加载的图（结构断言不需要像素）。
class _NeverImage extends ImageProvider<_NeverImage> {
  const _NeverImage();

  @override
  Future<_NeverImage> obtainKey(ImageConfiguration configuration) =>
      SynchronousFuture<_NeverImage>(this);

  @override
  ImageStreamCompleter loadImage(_NeverImage key, ImageDecoderCallback decode) =>
      OneFrameImageStreamCompleter(Completer<ImageInfo>().future);
}

class _Probe {
  int shown = 0;
  bool done = false;
  OpsPopupOutcome? outcome;
}

Future<_Probe> _open(
  WidgetTester tester, {
  double width = 360,
  double height = 800,
  double textScale = 1,
  String? title = 'Diskon 20%',
  String? body = 'Cuma sampai Minggu.',
  bool imageTappable = true,
}) async {
  tester.view.physicalSize = Size(width, height);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final probe = _Probe();
  await tester.pumpWidget(MaterialApp(
    locale: const Locale('en'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
      child: child!,
    ),
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: ElevatedButton(
            key: const Key('trigger'),
            onPressed: () async {
              probe.outcome = await showOpsPopupDialog(
                context,
                OpsPopupDialog(
                  image: const _NeverImage(),
                  title: title,
                  body: body,
                  buttonText: 'Lihat sekarang',
                  imageTappable: imageTappable,
                  onShown: () => probe.shown++,
                ),
              );
              probe.done = true;
            },
            child: const Text('go'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.byKey(const Key('trigger')));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
  return probe;
}

final _card = find.byKey(const Key('opsPopupCard'));
final _image = find.byKey(const Key('opsPopupImage'));
final _close = find.byKey(const Key('opsPopupClose'));
final _button = find.byKey(const Key('opsPopupButton'));

void main() {
  testWidgets('卡片宽 min(288, 屏宽-48)：360 屏 = 288，320 屏 = 272；圆角 24 + 投影 + 白底', (tester) async {
    await _open(tester);
    expect(tester.getSize(_card).width, 288);
    final deco = tester.widget<Container>(_card).decoration! as BoxDecoration;
    expect(deco.borderRadius, BorderRadius.circular(24));
    expect(deco.color, Colors.white);
    expect(deco.boxShadow, const [
      BoxShadow(color: Color(0x4714101E), blurRadius: 48, offset: Offset(0, 20)),
    ]);
    final clip = tester.widget<ClipRRect>(find.descendant(of: _card, matching: find.byType(ClipRRect)).first);
    expect(clip.borderRadius, BorderRadius.circular(24));
    // 水平垂直居中。
    expect(tester.getCenter(_card).dx, 180);
  });

  testWidgets('320 屏 → 272', (tester) async {
    await _open(tester, width: 320);
    expect(tester.getSize(_card).width, 272);
  });

  testWidgets('遮罩色 rgba(20,16,30,.6)；点遮罩不关', (tester) async {
    final probe = await _open(tester);
    final barriers = tester.widgetList<ModalBarrier>(find.byType(ModalBarrier)).toList();
    expect(barriers.last.color, const Color(0x9914101E));
    await tester.tapAt(const Offset(5, 5));
    await tester.pumpAndSettle();
    expect(_card, findsOneWidget);
    expect(probe.done, isFalse);
  });

  testWidgets('图片 AspectRatio 0.8 + BoxFit.cover + 1px 8% 黑内描边', (tester) async {
    await _open(tester);
    final img = tester.widget<Image>(_image);
    expect(img.fit, BoxFit.cover);
    final ar = tester.widget<AspectRatio>(find.ancestor(of: _image, matching: find.byType(AspectRatio)));
    expect(ar.aspectRatio, 0.8);
    final frame = tester.widget<Container>(
        find.ancestor(of: _image, matching: find.byType(Container)).first);
    final fg = frame.foregroundDecoration! as BoxDecoration;
    expect(fg.border, Border.all(color: const Color(0x14000000), width: 1));
    expect(tester.getSize(_image).width, 288);
    expect(tester.getSize(_image).height, 360);
  });

  testWidgets('✕：热区 44×44、距图片上/右各 4（视觉圆 32 距上/右各 10）、Semantics=commonClose', (tester) async {
    final handle = tester.ensureSemantics();
    await _open(tester);
    final hit = tester.getRect(_close);
    expect(hit.size, const Size(44, 44));
    final img = tester.getRect(_image);
    expect(hit.top - img.top, 4);
    expect(img.right - hit.right, 4);
    final circle = find.descendant(
        of: _close, matching: find.byWidgetPredicate((w) => w is Container && w.decoration is BoxDecoration));
    final circleRect = tester.getRect(circle);
    expect(circleRect.size, const Size(32, 32));
    expect(circleRect.top - img.top, 10);
    expect(img.right - circleRect.right, 10);
    final deco = tester.widget<Container>(circle).decoration! as BoxDecoration;
    expect(deco.color, const Color(0x59000000));
    final icon = tester.widget<Icon>(find.descendant(of: _close, matching: find.byType(Icon)));
    expect(icon.icon, Icons.close_rounded);
    expect(icon.size, 16);
    expect(icon.color, Colors.white);
    expect(find.bySemanticsLabel('Close'), findsOneWidget);
    // excludeSemantics 不得吞掉点击动作：读屏双击要能关。
    final node = tester.getSemantics(find.bySemanticsLabel('Close'));
    expect(node.getSemanticsData().hasAction(SemanticsAction.tap), isTrue);
    handle.dispose();
  });

  testWidgets('D1：标题 16/w700/ink 2 行、正文 13/ink2/1.5 4 行、间距 6 / 14；按钮高 46 圆角 12 mint', (tester) async {
    await _open(tester);
    final t = tester.widget<Text>(find.byKey(const Key('opsPopupTitle')));
    expect(t.maxLines, 2);
    expect(t.style!.fontSize, 16);
    expect(t.style!.fontWeight, FontWeight.w700);
    expect(t.style!.color, AppColors.ink);
    final b = tester.widget<Text>(find.byKey(const Key('opsPopupBody')));
    expect(b.maxLines, 4);
    expect(b.style!.fontSize, 13);
    expect(b.style!.color, AppColors.ink2);
    expect(b.style!.height, 1.5);
    final titleRect = tester.getRect(find.byKey(const Key('opsPopupTitle')));
    final bodyRect = tester.getRect(find.byKey(const Key('opsPopupBody')));
    expect(bodyRect.top - titleRect.bottom, 6);
    final btnRect = tester.getRect(_button);
    expect(btnRect.top - bodyRect.bottom, 14);
    expect(btnRect.height, 46);
    expect(btnRect.width, 288 - 32, reason: '整行（文案区 padding 16）');
    expect(tester.getRect(_card).bottom - btnRect.bottom, 16);
    final m = tester.widget<Material>(find.ancestor(of: _button, matching: find.byType(Material)).first);
    expect(m.color, AppColors.mint);
    expect(m.borderRadius, const BorderRadius.all(Radius.circular(12)));
    final label = tester.widget<Text>(find.descendant(of: _button, matching: find.byType(Text)));
    expect(label.maxLines, 1);
    expect(label.style!.fontSize, 14.5);
    expect(label.style!.fontWeight, FontWeight.w700);
    expect(label.style!.color, Colors.white);
  });

  testWidgets('D2：无标题正文 → 文案区只有按钮（按钮前间距 0）', (tester) async {
    await _open(tester, title: null, body: null);
    expect(find.byKey(const Key('opsPopupTitle')), findsNothing);
    expect(find.byKey(const Key('opsPopupBody')), findsNothing);
    final btnRect = tester.getRect(_button);
    expect(btnRect.top - tester.getRect(_image).bottom, 16, reason: '只剩 padding 16，无 14 间距');
    expect(tester.getRect(_card).bottom - btnRect.bottom, 16);
  });

  testWidgets('读屏点 ✕（SemanticsAction.tap）→ close', (tester) async {
    final handle = tester.ensureSemantics();
    final probe = await _open(tester);
    tester.semantics.tap(find.semantics.byLabel('Close'));
    await tester.pumpAndSettle();
    expect(probe.outcome, OpsPopupOutcome.close);
    handle.dispose();
  });

  testWidgets('360×640 + 字号 1.3x + 长文案：卡片不超过「可用高度 − 48」、不溢出，文案区可滚动', (tester) async {
    final probe = await _open(
      tester,
      height: 640,
      textScale: 1.3,
      title: 'Diskon 20% makanan kucing untuk semua anabul kesayanganmu minggu ini saja',
      body: List.filled(12, 'Cuma sampai Minggu. Yuk, cek pilihannya di Toko!').join(' '),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull, reason: 'RenderFlex overflow 会在这里冒出来');
    final card = tester.getRect(_card);
    expect(card.height, lessThanOrEqualTo(640 - 48));
    expect(card.top, greaterThanOrEqualTo(24));
    expect(find.byKey(const Key('opsPopupTextScroll')), findsOneWidget);
    // 按钮仍完整可见、可点。
    final btn = tester.getRect(_button);
    expect(btn.bottom, lessThanOrEqualTo(card.bottom));
    expect(btn.height, 46);
    await tester.tap(_button);
    await tester.pumpAndSettle();
    expect(probe.outcome, OpsPopupOutcome.tap);
  });

  testWidgets('首帧后 onShown 一次', (tester) async {
    final probe = await _open(tester);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(probe.shown, 1);
  });

  testWidgets('点按钮 → tap', (tester) async {
    final probe = await _open(tester);
    await tester.tap(_button);
    await tester.pumpAndSettle();
    expect(probe.done, isTrue);
    expect(probe.outcome, OpsPopupOutcome.tap);
    expect(_card, findsNothing);
  });

  testWidgets('点 ✕ → close', (tester) async {
    final probe = await _open(tester);
    await tester.tap(_close);
    await tester.pumpAndSettle();
    expect(probe.outcome, OpsPopupOutcome.close);
    expect(probe.done, isTrue);
  });

  testWidgets('系统返回键 → null', (tester) async {
    final probe = await _open(tester);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(probe.done, isTrue);
    expect(probe.outcome, isNull);
  });

  testWidgets('imageTappable: true → 点图片 = tap', (tester) async {
    final probe = await _open(tester);
    await tester.tap(_image);
    await tester.pumpAndSettle();
    expect(probe.outcome, OpsPopupOutcome.tap);
  });

  testWidgets('imageTappable: false → 点图片无反应（不关）', (tester) async {
    final probe = await _open(tester, imageTappable: false);
    await tester.tap(_image);
    await tester.pumpAndSettle();
    expect(_card, findsOneWidget);
    expect(probe.done, isFalse);
  });
}
