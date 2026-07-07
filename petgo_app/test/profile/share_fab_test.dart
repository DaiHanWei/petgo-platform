import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/presentation/widgets/share_fab.dart';

void main() {
  testWidgets('点击 FAB 调用 onPressed（AC2 分享触发）', (tester) async {
    var tapped = false;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        floatingActionButton: ShareFab(
          semanticLabel: 'Share',
          animate: false,
          onPressed: (_) => tapped = true,
        ),
      ),
    ));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('shareFab')));
    expect(tapped, isTrue);
  });

  testWidgets('首访 animate=true → 动效播完回调 onAnimationShown（AC1 一次性）', (tester) async {
    var shown = false;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        floatingActionButton: ShareFab(
          semanticLabel: 'Share',
          animate: true,
          onAnimationShown: () => shown = true,
          onPressed: (_) {},
        ),
      ),
    ));
    // 动效时长 900ms，推进完成后回调
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(shown, isTrue);
  });

  testWidgets('复访 animate=false → 不触发动效回调', (tester) async {
    var shown = false;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        floatingActionButton: ShareFab(
          semanticLabel: 'Share',
          animate: false,
          onAnimationShown: () => shown = true,
          onPressed: (_) {},
        ),
      ),
    ));
    await tester.pump(const Duration(seconds: 1));
    expect(shown, isFalse);
  });
}
