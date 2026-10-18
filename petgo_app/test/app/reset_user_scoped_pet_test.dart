import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';

/// V1.4.0 multi-pet · code review 2026-10-18 #1：`resetUserScopedCaches` 在冷启动恢复会话时也会跑
/// （guest → authenticated）。它不能把「记住上次查看」的宠物清掉；真正换了账号才清。
void main() {
  setUp(() => SharedPreferences.setMockInitialValues({
        CurrentPetIdNotifier.prefKey: 30,
        CurrentPetIdNotifier.ownerPrefKey: 7,
      }));

  Future<(ProviderContainer, WidgetRef)> pump(WidgetTester tester) async {
    final c = ProviderContainer(overrides: [
      petListProvider.overrideWith((ref) async => const [
            PetListItem(id: 10, name: 'A', isDefault: true),
            PetListItem(id: 30, name: 'C'),
          ]),
    ]);
    addTearDown(c.dispose);
    late WidgetRef captured;
    await tester.pumpWidget(UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        home: Consumer(builder: (context, ref, _) {
          captured = ref;
          return const SizedBox.shrink();
        }),
      ),
    ));
    return (c, captured);
  }

  testWidgets('冷启动恢复同一账号 → 当前宠物保留（AC3.6 记住上次查看）', (tester) async {
    final (c, ref) = await pump(tester);
    c.read(currentPetIdProvider);
    await tester.runAsync(() async {
      resetUserScopedCaches(ref, userId: 7);
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    expect(c.read(currentPetIdProvider), 30);
    expect((await SharedPreferences.getInstance()).getInt(CurrentPetIdNotifier.prefKey), 30);
  });

  testWidgets('登录的是另一个账号 → 当前宠物清掉', (tester) async {
    final (c, ref) = await pump(tester);
    c.read(currentPetIdProvider);
    await tester.runAsync(() async {
      resetUserScopedCaches(ref, userId: 8);
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    expect(c.read(currentPetIdProvider), isNull);
    expect((await SharedPreferences.getInstance()).getInt(CurrentPetIdNotifier.prefKey), 0);
  });

  testWidgets('同进程直接换号（A → B）→ 立即清掉', (tester) async {
    final (c, ref) = await pump(tester);
    await tester.runAsync(() async {
      resetUserScopedCaches(ref, userId: 7);
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    expect(c.read(currentPetIdProvider), 30);
    resetUserScopedCaches(ref, userId: 8);
    expect(c.read(currentPetIdProvider), isNull);
  });
}
