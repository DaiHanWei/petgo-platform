import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/card_render/card_export.dart';

/// Story 9.1 · AC4 的第五段：**系统分享菜单**。
///
/// 只钉选单本身（存相册 / 分享两项齐全、走本地化文案）。
/// 真机上的相册权限与系统分享面板属 L2，云端 headless 验不了。
///
/// ⚠️ 本 story **不接任何入口** —— 谁在哪儿点分享是 9-3 的事，
/// 所以这里手动调用 `showSheet`，而不是从某个页面点进去。
void main() {
  testWidgets('选单有「存相册」与「分享」两项', (tester) async {
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: AppLocalizations.supportedLocales,
      home: Builder(
        builder: (context) => Center(
          child: ElevatedButton(
            onPressed: () => CardExport.showSheet(
              context,
              bytes: Uint8List.fromList(const [1, 2, 3]),
              name: 'tailtopia_card',
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('cardSaveToGallery')), findsOneWidget);
    expect(find.byKey(const ValueKey('cardShareImage')), findsOneWidget);
    // 文案走 ARB，不是源里硬编码的字符串。
    final l10n = AppLocalizations.of(tester.element(find.byType(ListTile).first));
    expect(find.text(l10n.cardSaveToGallery), findsOneWidget);
    expect(find.text(l10n.cardShareImage), findsOneWidget);
  });
}
