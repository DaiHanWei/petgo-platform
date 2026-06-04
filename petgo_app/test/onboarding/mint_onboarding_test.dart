import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/onboarding/presentation/mint_onboarding_page.dart';

/// PetGo Prototype 引导流（welcome → create pet → done）回归。
///
/// 注：含无限漂浮/眨眼动效，统一用 `pump(Duration)` 而非 `pumpAndSettle`。
void main() {
  testWidgets('引导流三步：欢迎 → 创建宠物 → 完成', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: MintOnboardingPage()));
    await tester.pump(const Duration(milliseconds: 100));

    // —— Step 0 欢迎 ——
    expect(find.text('Mulai sekarang'), findsOneWidget);
    expect(find.text('PETGO'), findsOneWidget);

    await tester.tap(find.text('Mulai sekarang'));
    await tester.pump(const Duration(milliseconds: 100));

    // —— Step 1 创建宠物 ——
    expect(find.text('Kenalan dengan anabul'), findsOneWidget);

    // 「Lanjut」初始禁用（name + breed 未填）：补全后才可进下一步。
    await tester.enterText(find.byType(TextField).first, 'Mochi');
    await tester.pump();
    await tester.tap(find.text('Kucing Oren'));
    await tester.pump(const Duration(milliseconds: 100));

    await tester.tap(find.text('Lanjut'));
    await tester.pump(const Duration(milliseconds: 600)); // 等 popIn

    // —— Step 2 完成 ——
    expect(find.text('Masuk ke PetGo'), findsOneWidget);
    expect(find.textContaining('Halo, Mochi'), findsOneWidget);
    expect(find.textContaining('petgo.id/m/mochi'), findsOneWidget);
  });

  testWidgets('未填名称/品种时 Lanjut 禁用，不进入下一步', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: MintOnboardingPage()));
    await tester.pump(const Duration(milliseconds: 100));
    await tester.tap(find.text('Mulai sekarang'));
    await tester.pump(const Duration(milliseconds: 100));

    // 直接点 Lanjut（禁用）→ 仍停留在创建页。
    await tester.tap(find.text('Lanjut'));
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Kenalan dengan anabul'), findsOneWidget);
    expect(find.text('Masuk ke PetGo'), findsNothing);
  });
}
