import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics_autocapture.dart';

void main() {
  testWidgets('autocapture 旁路监听，不吞下层点击：按钮 onPressed 照常触发', (tester) async {
    var tapped = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: AnalyticsAutocapture(
          child: Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () => tapped++,
                child: const Text('Lanjut'),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Lanjut'));
    await tester.pump();

    // 全局 Listener 用 translucent + 仅 onPointerUp，不得拦截手势 → 业务回调必须照常执行。
    expect(tapped, 1);
  });
}
