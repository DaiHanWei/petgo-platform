import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/notify/domain/push_settings.dart';

/// V1.4.0 ④ Story 1.3 · AC3：`PushSettings.fromJson` 线格式解析（纯单元）。
Map<String, dynamic> _json(Map<String, bool> overrides, {Map<String, Object?> lockable = const {}}) => {
      'categories': [
        for (final c in PushCategoryCode.all)
          {
            'code': c,
            'enabled': overrides[c] ?? true,
            'lockable': lockable.containsKey(c) ? lockable[c] : c != PushCategoryCode.orderAccount,
          },
      ],
    };

void main() {
  // V1.4.2 social-topic Story 1.5：新增 PET_DIARY → 10 项（按设计更新断言）。
  test('10 项全开', () {
    final s = PushSettings.fromJson(_json({}));
    expect(s.enabled.length, 10);
    expect(PushCategoryCode.all.every(s.isEnabled), isTrue);
    expect(s.locked, {PushCategoryCode.orderAccount});
  });

  test('部分关闭', () {
    final s = PushSettings.fromJson(_json({'ACHIEVEMENT': false, 'PROMO': false}));
    expect(s.isEnabled('ACHIEVEMENT'), isFalse);
    expect(s.isEnabled('PROMO'), isFalse);
    expect(s.isEnabled('LIKE'), isTrue);
  });

  test('未知 code 忽略；已知 code 缺失视为开启', () {
    final s = PushSettings.fromJson({
      'categories': [
        {'code': 'FOO', 'enabled': false, 'lockable': true},
        {'code': 'LIKE', 'enabled': false, 'lockable': true},
      ],
    });
    expect(s.enabled.containsKey('FOO'), isFalse);
    expect(s.isEnabled('LIKE'), isFalse);
    expect(s.isEnabled('GIFT'), isTrue);
    expect(PushSettings.fromJson(null).enabled.length, 10);
  });

  test('ORDER_ACCOUNT 在服务端 lockable 缺失 / 为 true 时仍锁定且恒开', () {
    for (final lockable in [null, true]) {
      final s = PushSettings.fromJson(_json({'ORDER_ACCOUNT': false}, lockable: {'ORDER_ACCOUNT': lockable}));
      expect(s.isLocked(PushCategoryCode.orderAccount), isTrue);
      expect(s.isEnabled(PushCategoryCode.orderAccount), isTrue);
    }
  });

  test('copyWith 只改一项', () {
    final s = PushSettings.fromJson(_json({})).copyWith('LIKE', false);
    expect(s.isEnabled('LIKE'), isFalse);
    expect(s.enabled.values.where((v) => !v).length, 1);
    expect(s.locked, {PushCategoryCode.orderAccount});
  });

  test('V1.4.2 Story 1.5 · AC7.1：PET_DIARY 紧跟 FOLLOWER（= 后端声明顺序）；认 PET_DIARY、缺失视为开启、可关', () {
    final all = PushCategoryCode.all;
    expect(all.indexOf(PushCategoryCode.petDiary), all.indexOf(PushCategoryCode.follower) + 1);
    expect(PushCategoryCode.petDiary, 'PET_DIARY');
    final off = PushSettings.fromJson(_json({'PET_DIARY': false}));
    expect(off.isEnabled(PushCategoryCode.petDiary), isFalse);
    expect(off.isLocked(PushCategoryCode.petDiary), isFalse);
    final missing = PushSettings.fromJson({
      'categories': [
        {'code': 'LIKE', 'enabled': true, 'lockable': true},
      ],
    });
    expect(missing.isEnabled(PushCategoryCode.petDiary), isTrue);
  });
}
