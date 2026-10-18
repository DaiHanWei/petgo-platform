import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/colors.dart';
import 'package:tailtopia/shared/widgets/empty_state.dart';

/// V1.4.0 ④ Story 2.4：`EmptyState.iconColor` 最小扩展——不传时两条图标路径仍为 `textTertiary`。
void main() {
  Future<Icon> iconOf(WidgetTester tester, EmptyState w) async {
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: w)));
    return tester.widget<Icon>(find.byType(Icon));
  }

  testWidgets('默认色不变（带圆底 / 不带圆底）', (tester) async {
    expect((await iconOf(tester, const EmptyState(title: 't', iconBackground: AppColors.cream2))).color,
        AppColors.textTertiary);
    expect((await iconOf(tester, const EmptyState(title: 't'))).color, AppColors.textTertiary);
  });

  testWidgets('传 iconColor 用传入色', (tester) async {
    expect(
        (await iconOf(tester,
                const EmptyState(title: 't', iconBackground: AppColors.cream2, iconColor: AppColors.mint)))
            .color,
        AppColors.mint);
    expect((await iconOf(tester, const EmptyState(title: 't', iconColor: AppColors.mint))).color, AppColors.mint);
  });
}
