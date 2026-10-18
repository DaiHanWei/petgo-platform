import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/profile/data/current_pet_provider.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';

/// V1.4.0 multi-pet Story 1.5 · AC1.2 / AC1.3 / AC3.6：当前宠物单一来源。
///
/// - `currentPetIdProvider`：本机偏好 `petgo.currentPetId` 恢复 / 写入 / 清除，读写失败静默降级；
/// - `effectivePetIdProvider`：记忆 id 在列表里 → 它；否则默认宠物（`isDefault`，没有则第一只）；空列表 → null。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const pets = [
    PetListItem(id: 10, name: 'A'),
    PetListItem(id: 20, name: 'B', isDefault: true),
    PetListItem(id: 30, name: 'C'),
  ];

  ProviderContainer containerWith(List<PetListItem> list) {
    final c = ProviderContainer(overrides: [
      petListProvider.overrideWith((ref) async => list),
    ]);
    addTearDown(c.dispose);
    return c;
  }

  /// 等 `build` 里发起的异步恢复落地。
  Future<void> settle() async {
    for (var i = 0; i < 5; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  group('resolveEffectivePetId（纯函数）', () {
    test('记忆 id 在列表里 → 用它', () {
      expect(resolveEffectivePetId(pets, 30), 30);
    });
    test('未设 / 已删 / 换了账号（不在列表）→ 默认宠物', () {
      expect(resolveEffectivePetId(pets, null), 20);
      expect(resolveEffectivePetId(pets, 99), 20);
    });
    test('isDefault 缺失 → 第一只', () {
      expect(resolveEffectivePetId(const [PetListItem(id: 1, name: 'x'), PetListItem(id: 2, name: 'y')], 99), 1);
    });
    test('空列表 → null', () {
      expect(resolveEffectivePetId(const [], 10), isNull);
    });
  });

  group('currentPetIdProvider + effectivePetIdProvider', () {
    setUp(() => SharedPreferences.setMockInitialValues({}));

    test('偏好恢复：上次选的那只（记住上次查看）', () async {
      SharedPreferences.setMockInitialValues({CurrentPetIdNotifier.prefKey: 30});
      final c = containerWith(pets);
      c.read(currentPetIdProvider);
      await settle();
      expect(c.read(currentPetIdProvider), 30);
      expect(await c.read(effectivePetIdProvider.future), 30);
    });

    test('偏好里的那只已删除 → 回落默认宠物', () async {
      SharedPreferences.setMockInitialValues({CurrentPetIdNotifier.prefKey: 99});
      final c = containerWith(pets);
      c.read(currentPetIdProvider);
      await settle();
      expect(await c.read(effectivePetIdProvider.future), 20);
    });

    test('select 写入偏好，effective 跟着换', () async {
      final c = containerWith(pets);
      await c.read(currentPetIdProvider.notifier).select(10);
      expect(c.read(currentPetIdProvider), 10);
      expect(await c.read(effectivePetIdProvider.future), 10);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(CurrentPetIdNotifier.prefKey), 10);
    });

    test('clear() 后回落默认宠物，且偏好不再记住旧 id', () async {
      final c = containerWith(pets);
      await c.read(currentPetIdProvider.notifier).select(30);
      await c.read(currentPetIdProvider.notifier).clear();
      expect(c.read(currentPetIdProvider), isNull);
      expect(await c.read(effectivePetIdProvider.future), 20);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(CurrentPetIdNotifier.prefKey) ?? 0, 0);
    });

    test('恢复晚到不覆盖用户刚做的选择', () async {
      SharedPreferences.setMockInitialValues({CurrentPetIdNotifier.prefKey: 30});
      final c = containerWith(pets);
      // build 触发恢复，但还没落地就先 select。
      c.read(currentPetIdProvider);
      await c.read(currentPetIdProvider.notifier).select(10);
      await settle();
      expect(c.read(currentPetIdProvider), 10);
    });

    test('空列表 → effective 为 null', () async {
      final c = containerWith(const []);
      expect(await c.read(effectivePetIdProvider.future), isNull);
    });
  });

  group('偏好读写抛异常 → 不崩，静默降级', () {
    const channel = MethodChannel('plugins.flutter.io/shared_preferences');

    setUp(() {
      // 让 SharedPreferences 平台通道全部抛错（模拟偏好损坏 / 平台不可用）。
      SharedPreferences.resetStatic();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => throw PlatformException(code: 'boom'));
    });
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
      SharedPreferences.resetStatic();
    });

    test('读不到偏好 → 保持未设，回落默认宠物', () async {
      final c = containerWith(pets);
      c.read(currentPetIdProvider);
      await settle();
      expect(c.read(currentPetIdProvider), isNull);
      expect(await c.read(effectivePetIdProvider.future), 20);
    });

    test('写不进偏好 → 本次照常切换 / 清除', () async {
      final c = containerWith(pets);
      await c.read(currentPetIdProvider.notifier).select(30);
      expect(c.read(currentPetIdProvider), 30);
      await c.read(currentPetIdProvider.notifier).clear();
      expect(c.read(currentPetIdProvider), isNull);
    });
  });

  group('requestPetIdProvider', () {
    test('列表取不到 → null（请求不带 petId，后端落默认宠物）', () async {
      final c = ProviderContainer(overrides: [
        petListProvider.overrideWith((ref) async => throw StateError('offline')),
      ]);
      addTearDown(c.dispose);
      expect(await c.read(requestPetIdProvider.future), isNull);
    });

    test('列表正常 → 同 effective', () async {
      SharedPreferences.setMockInitialValues({});
      final c = containerWith(pets);
      expect(await c.read(requestPetIdProvider.future), 20);
    });
  });

  // ===== code review 2026-10-18 #1：按账号绑定（冷启动保留 / 换号清除）=====
  group('bindUser：记住上次查看只对同一账号生效', () {
    const owner = CurrentPetIdNotifier.ownerPrefKey;
    const key = CurrentPetIdNotifier.prefKey;

    test('冷启动（首次绑定）同一账号 → 保留偏好里的宠物', () async {
      SharedPreferences.setMockInitialValues({key: 30, owner: 7});
      final c = containerWith(pets);
      c.read(currentPetIdProvider);
      await c.read(currentPetIdProvider.notifier).bindUser(7);
      await settle();
      expect(c.read(currentPetIdProvider), 30);
      expect(await c.read(effectivePetIdProvider.future), 30);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(key), 30);
    });

    test('冷启动但偏好是别的账号选的（登出后换人登录）→ 清掉', () async {
      SharedPreferences.setMockInitialValues({key: 30, owner: 7});
      final c = containerWith(pets);
      c.read(currentPetIdProvider);
      await c.read(currentPetIdProvider.notifier).bindUser(8);
      await settle();
      expect(c.read(currentPetIdProvider), isNull);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(key), 0);
      expect(prefs.getInt(owner), 8);
    });

    test('进程内直接换号 → 同步清掉（不让上个账号的宠物 id 多活一帧），偏好也清', () async {
      SharedPreferences.setMockInitialValues({});
      final c = containerWith(pets);
      final n = c.read(currentPetIdProvider.notifier);
      await n.bindUser(7);
      await n.select(30);
      final f = n.bindUser(8);
      expect(c.read(currentPetIdProvider), isNull, reason: '同步清');
      await f;
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(key), 0);
      expect(prefs.getInt(owner), 8);
    });

    test('select 记下主人：同账号重启后能恢复', () async {
      SharedPreferences.setMockInitialValues({});
      final c = containerWith(pets);
      await c.read(currentPetIdProvider.notifier).bindUser(7);
      await c.read(currentPetIdProvider.notifier).select(10);
      final prefs = await SharedPreferences.getInstance();
      expect(prefs.getInt(key), 10);
      expect(prefs.getInt(owner), 7);

      // 模拟重启：新容器、同一份偏好。
      final c2 = containerWith(pets);
      c2.read(currentPetIdProvider);
      await c2.read(currentPetIdProvider.notifier).bindUser(7);
      await settle();
      expect(c2.read(currentPetIdProvider), 10);
    });

    test('同一账号重复绑定（改资料等）→ 不清', () async {
      SharedPreferences.setMockInitialValues({});
      final c = containerWith(pets);
      final n = c.read(currentPetIdProvider.notifier);
      await n.bindUser(7);
      await n.select(30);
      await n.bindUser(7);
      expect(c.read(currentPetIdProvider), 30);
    });
  });

  group('code review #5：列表取不到时请求带用户选过的那只', () {
    test('requestPetIdProvider：列表失败 + 选过 → 带它（后端校验归属），不悄悄落默认', () async {
      SharedPreferences.setMockInitialValues({});
      final c = ProviderContainer(overrides: [
        petListProvider.overrideWith((ref) async => throw StateError('offline')),
      ]);
      addTearDown(c.dispose);
      await c.read(currentPetIdProvider.notifier).select(30);
      expect(await c.read(requestPetIdProvider.future), 30);
    });
  });
}
