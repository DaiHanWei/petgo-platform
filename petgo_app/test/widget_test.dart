import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/app.dart';

void main() {
  testWidgets('App boots and renders localized appTitle on home placeholder',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: PetGoApp()));
    await tester.pumpAndSettle();

    // appTitle 在 en/id 下均为 "PetGo"，验证 i18n 脚手架接通且首页可渲染。
    expect(find.text('PetGo'), findsOneWidget);
  });
}
