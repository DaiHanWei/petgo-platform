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
  test('9 项全开', () {
    final s = PushSettings.fromJson(_json({}));
    expect(s.enabled.length, 9);
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
    expect(PushSettings.fromJson(null).enabled.length, 9);
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
}
