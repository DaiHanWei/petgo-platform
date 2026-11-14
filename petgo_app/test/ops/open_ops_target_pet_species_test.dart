import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';
import 'package:tailtopia/features/ops/presentation/open_ops_target.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

/// L0：V1.5.0 pet-species Story 3.5 · AC2 / AC4 —— 运营跳转「宠物类型」落地分流（测试 GoRouter + 假列表读口）。
class _FakeList implements PetListRepository {
  _FakeList(this.pets, {this.fail = false});
  final List<PetListItem> pets;
  final bool fail;
  int calls = 0;

  @override
  Future<List<PetListItem>> fetch() async {
    calls++;
    if (fail) throw Exception('boom');
    return pets;
  }
}

PetListItem _pet(int id, String species) =>
    PetListItem(id: id, name: 'P$id', petType: species == 'CAT' ? 'CAT' : 'OTHER', species: species);

class _H {
  _H(this.router);
  final GoRouter router;
  String get location => router.state.uri.toString();
}

Future<_H> _pump(WidgetTester tester, {LoginResponse? login, required PetListRepository list}) async {
  final container = ProviderContainer(overrides: [petListRepositoryProvider.overrideWithValue(list)]);
  addTearDown(container.dispose);
  if (login != null) container.read(authControllerProvider.notifier).applyLogin(login);
  late final _H h;
  final router = GoRouter(
    navigatorKey: rootNavigatorKey,
    initialLocation: '/origin',
    routes: [
      GoRoute(
        path: '/origin',
        builder: (_, _) => Scaffold(
          body: Consumer(builder: (context, ref, _) {
            return ElevatedButton(
              key: const ValueKey('open'),
              onPressed: () => openOpsTarget(context, ref, const OpsTarget(OpsTargetType.appPage, 'PET_SPECIES')),
              child: const Text('open'),
            );
          }),
        ),
      ),
      GoRoute(path: '/profile/create', builder: (_, _) => const Scaffold(body: Text('page:create'))),
      GoRoute(path: '/profile/edit', builder: (_, _) => const Scaffold(body: Text('page:edit'))),
    ],
  );
  addTearDown(router.dispose);
  h = _H(router);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp.router(
      routerConfig: router,
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
    ),
  ));
  await tester.pumpAndSettle();
  return h;
}

const _user = LoginResponse(
    accessToken: 'a', refreshToken: 'r', role: 'USER', isNewUser: false, onboardingCompleted: true);

void main() {
  test('排序：只有细分类型仍是 OTHER 的提前，CIVET 保持原位', () {
    final ordered = petsForSpeciesLanding([_pet(1, 'CAT'), _pet(2, 'CIVET'), _pet(3, 'OTHER')]);
    expect(ordered.map((p) => p.id), [3, 1, 2]);
    // 旧后端无 species：按大类 OTHER 算。
    const legacy = PetListItem(id: 9, name: 'L', petType: 'OTHER');
    expect(petsForSpeciesLanding([_pet(1, 'CAT'), legacy]).first.id, 9);
  });

  testWidgets('0 只 → 建档页（经 openOpsTarget，已登录）', (tester) async {
    final list = _FakeList(const []);
    final h = await _pump(tester, login: _user, list: list);
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
    expect(list.calls, 1);
    expect(find.text('page:create'), findsOneWidget);
    expect(h.location, isNot('/origin'));
  });

  testWidgets('游客 → 强弹登录，不取列表、不跳转', (tester) async {
    final list = _FakeList([_pet(1, 'CAT')]);
    await _pump(tester, list: list);
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
    expect(find.byType(LoginHardDialog), findsOneWidget);
    expect(list.calls, 0);
    expect(find.text('page:edit'), findsNothing);
  });

  testWidgets('1 只 → select(id) + 编辑页', (tester) async {
    final selected = <int>[];
    await _pump(tester, login: _user, list: _FakeList(const []));
    await openPetSpeciesLanding(_FakeList([_pet(7, 'OTHER')]).fetch, (id) async => selected.add(id));
    await tester.pumpAndSettle();
    expect(selected, [7]);
    expect(find.text('page:edit'), findsOneWidget);
  });

  testWidgets('2 只（猫 + OTHER）→ 抽屉第一行是 OTHER，点它 → select + 编辑页', (tester) async {
    final selected = <int>[];
    await _pump(tester, login: _user, list: _FakeList(const []));
    final f = openPetSpeciesLanding(_FakeList([_pet(1, 'CAT'), _pet(2, 'OTHER')]).fetch, (id) async => selected.add(id));
    await tester.pumpAndSettle();
    expect(find.text('Pilih hewan yang mau diperbarui jenisnya'), findsOneWidget);
    final rows = find.byWidgetPredicate(
        (w) => w.key is ValueKey<String> && (w.key as ValueKey<String>).value.startsWith('petPickerRow-'));
    expect((tester.widgetList(rows).first.key as ValueKey<String>).value, 'petPickerRow-2');
    await tester.tap(find.byKey(const ValueKey('petPickerRow-2')));
    await tester.pumpAndSettle();
    await f;
    expect(selected, [2]);
    expect(find.text('page:edit'), findsOneWidget);
  });

  testWidgets('列表接口抛错 → 不导航、不抛出', (tester) async {
    final h = await _pump(tester, login: _user, list: _FakeList(const [], fail: true));
    await tester.tap(find.byKey(const ValueKey('open')));
    await tester.pumpAndSettle();
    expect(h.location, '/origin');
    expect(find.text('page:create'), findsNothing);
  });
}
