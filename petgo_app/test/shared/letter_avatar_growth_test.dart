import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/growth/domain/growth_badge.dart';
import 'package:tailtopia/features/growth/domain/growth_models.dart';
import 'package:tailtopia/shared/widgets/growth_avatar_decoration.dart';
import 'package:tailtopia/shared/widgets/initial_avatar.dart';
import 'package:tailtopia/shared/widgets/letter_avatar.dart';

/// V1.4.0 ① Story 3.2 · AC2：头像组件接入等级装饰；不传 growth 时与改动前一致。
const _badge = GrowthBadge(level: 7, tier: GrowthTier.buddy, frame: GrowthFrame.basic);

Future<void> _pump(WidgetTester tester, Widget w) =>
    tester.pumpWidget(MaterialApp(home: Scaffold(body: Center(child: w))));

void main() {
  testWidgets('LetterAvatar 不传 growth：只有一个 CircleAvatar，无装饰', (tester) async {
    await _pump(tester, const LetterAvatar(name: 'Rina', size: 32));
    expect(find.byType(CircleAvatar), findsOneWidget);
    expect(find.byType(GrowthAvatarDecoration), findsNothing);
  });

  testWidgets('LetterAvatar 有 growth → 角标 + 框', (tester) async {
    await _pump(tester, const LetterAvatar(name: 'Rina', size: 32, growth: _badge));
    expect(find.text('7'), findsOneWidget);
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsOneWidget);
  });

  testWidgets('LetterAvatar deleted + growth → 忽略 growth（A7）', (tester) async {
    await _pump(tester, const LetterAvatar(name: '', size: 32, deleted: true, growth: _badge));
    expect(find.byType(GrowthAvatarDecoration), findsNothing);
    expect(find.text('7'), findsNothing);
  });

  testWidgets('InitialAvatar：不传 growth 无装饰；showLevelBadge false 只戴框', (tester) async {
    await _pump(tester, const InitialAvatar(avatarUrl: null, nickname: 'Rina', radius: 42));
    expect(find.byType(GrowthAvatarDecoration), findsNothing);
    await _pump(tester,
        const InitialAvatar(avatarUrl: null, nickname: 'Rina', radius: 42, growth: _badge, showLevelBadge: false));
    expect(find.byKey(const ValueKey('growthFrame_basic')), findsOneWidget);
    expect(find.byKey(const ValueKey('growthLevelBadge')), findsNothing);
  });
}
