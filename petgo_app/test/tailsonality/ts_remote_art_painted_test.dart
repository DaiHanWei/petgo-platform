import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:tailtopia/features/tailsonality/data/ts_remote_art.dart';
import 'package:tailtopia/features/tailsonality/presentation/widgets/ts_remote_art_image.dart';

/// 白图帖回归：截卡前 [TsRemoteArtImage.waitPainted] 必须等到插画真正画上屏，取不到 / 解不出时返回 false。
void main() {
  final png = Uint8List.fromList(img.encodePng(img.Image(width: 4, height: 4)));
  final boundaryKey = GlobalKey();

  Widget host() => MaterialApp(
        home: RepaintBoundary(
          key: boundaryKey,
          child: SizedBox(
            width: 40,
            height: 40,
            child: TsRemoteArtImage(name: TsRemoteArt.match(1), placeholder: (_) => const SizedBox()),
          ),
        ),
      );

  tearDown(() {
    TsRemoteArt.debugReset();
    TsRemoteArt.debugLoader = (_) async => null;
  });

  testWidgets('插画取不到 → false（不截占位）', (tester) async {
    await tester.pumpWidget(host());
    await tester.pump();
    final ok = await TsRemoteArtImage.waitPainted(boundaryKey.currentContext!, timeout: const Duration(seconds: 1));
    expect(ok, isFalse);
  });

  testWidgets('字节到手但未解码完 → 等解码画上屏后才 true', (tester) async {
    TsRemoteArt.debugLoader = (_) async => png;
    await tester.pumpWidget(host());
    await tester.pump();
    var done = false;
    bool? result;
    TsRemoteArtImage.waitPainted(boundaryKey.currentContext!).then((v) {
      done = true;
      result = v;
    });
    await tester.pump();
    // 未解码前不得放行。
    expect(done, isFalse);
    await tester.runAsync(() async {
      final element = tester.element(find.byType(Image));
      await precacheImage((element.widget as Image).image, element);
    });
    for (var i = 0; i < 5 && !done; i++) {
      await tester.pump();
    }
    expect(result, isTrue);
  });

  testWidgets('图解不出（坏字节）→ false', (tester) async {
    TsRemoteArt.debugLoader = (_) async => Uint8List.fromList(const [1, 2, 3]);
    await tester.pumpWidget(host());
    await tester.pump();
    bool? result;
    TsRemoteArtImage.waitPainted(boundaryKey.currentContext!, timeout: const Duration(seconds: 5))
        .then((v) => result = v);
    await tester.runAsync(() async {
      final element = tester.element(find.byType(Image));
      await precacheImage((element.widget as Image).image, element, onError: (_, _) {});
    });
    for (var i = 0; i < 5 && result == null; i++) {
      await tester.pump();
    }
    expect(result, isFalse);
  });
}
