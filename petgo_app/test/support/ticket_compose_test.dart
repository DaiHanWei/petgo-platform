import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/support/presentation/ticket_compose_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

Future<void> _pump(WidgetTester tester) async {
  tester.platformDispatcher.localesTestValue = const [Locale('id')];
  // 表单较长，加高视口让 ListView 全部子项构建（否则加图/开关落在 800px 视口外）。
  tester.view.physicalSize = const Size(1200, 2600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(const ProviderScope(
    child: MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TicketComposePage(),
    ),
  ));
  await tester.pump();
}

void main() {
  /// L0 widget（Story 4.2）。渲染表单核心元素：联系方式选择 + 标签 chips + 加图 + 提交/需联系开关。
  testWidgets('renders form elements', (tester) async {
    await _pump(tester);
    expect(find.byKey(const ValueKey('ticketSubmit')), findsOneWidget);
    expect(find.byKey(const ValueKey('ticketAddPhoto')), findsOneWidget);
    expect(find.byKey(const ValueKey('ticketNeedContact')), findsOneWidget);
    // 8 个标签 chip 全渲染。
    expect(find.byType(FilterChip), findsNWidgets(8));
  });

  /// 正文 + 联系方式必填：都空时提交禁用；填齐后启用。
  testWidgets('submit disabled until body & contact filled', (tester) async {
    await _pump(tester);
    FilledButton submit() => tester.widget<FilledButton>(find.byKey(const ValueKey('ticketSubmit')));

    expect(submit().onPressed, isNull); // 初始禁用

    final fields = find.byType(TextField);
    await tester.enterText(fields.at(0), '兽医没回复我'); // body（第 1 个 TextField）
    await tester.pump();
    expect(submit().onPressed, isNull); // 仅正文还不够

    await tester.enterText(fields.at(2), '+62123'); // contactValue（第 3 个 TextField）
    await tester.pump();
    expect(submit().onPressed, isNotNull); // 正文 + 联系方式齐 → 启用
  });
}
