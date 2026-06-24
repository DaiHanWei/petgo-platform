import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/vet/presentation/vet_final_diagnosis_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 兽医最终诊断表单：全字段必填门控（提交按钮置灰逻辑）。
void main() {
  Future<void> pump(WidgetTester tester) async {
    // 高视口：让 ListView 一次性构建全部字段（否则底部必填字段离屏不渲染、enterText 找不到）。
    tester.view.physicalSize = const Size(1200, 4000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('en'),
      home: const VetFinalDiagnosisPage(petName: 'Mochi'),
    ));
  }

  FilledButton submitBtn(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const ValueKey('vetDiagSubmit')));

  // 填满全部当前可见文本框。
  Future<void> fillVisible(WidgetTester tester, String text) async {
    final fields = find.byType(TextField);
    for (var i = 0; i < tester.widgetList(fields).length; i++) {
      await tester.enterText(fields.at(i), text);
    }
    await tester.pump();
  }

  testWidgets('全空 / 仅填诊断 → 提交禁用；全部必填填满 → 启用', (tester) async {
    await pump(tester);
    expect(submitBtn(tester).onPressed, isNull); // 全空

    await tester.enterText(find.byKey(const ValueKey('vetDiagInput')), 'Gastritis');
    await tester.pump();
    expect(submitBtn(tester).onPressed, isNull); // 仅诊断，其余必填仍空

    await fillVisible(tester, 'x'); // 不需用药：5 个可见字段填满
    expect(submitBtn(tester).onPressed, isNotNull);
  });

  testWidgets('选「需要用药」但药名/频次空 → 提交禁用', (tester) async {
    await pump(tester);
    await fillVisible(tester, 'x'); // 先填满基础 5 字段（此时启用）
    expect(submitBtn(tester).onPressed, isNotNull);

    // 切「需要用药」→ 露出药名/频次（空）→ 重新禁用。
    await tester.tap(find.text('Yes, needs medication'));
    await tester.pump();
    expect(submitBtn(tester).onPressed, isNull);

    // 填满药名 + 频次（此时可见 7 字段，全部填 x）→ 再次启用。
    await fillVisible(tester, 'x');
    expect(submitBtn(tester).onPressed, isNotNull);
  });
}
