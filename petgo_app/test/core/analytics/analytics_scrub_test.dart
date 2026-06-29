import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';

void main() {
  group('Analytics.scrub', () {
    test('剥离 PII / 健康数据键，保留普通键', () {
      final out = Analytics.scrub({
        'email': 'a@b.com',
        'name': 'Aurel',
        'symptom': '呕吐',
        'screen': 'HomePage',
        'count': 3,
      });
      expect(out.containsKey('email'), isFalse);
      expect(out.containsKey('name'), isFalse);
      expect(out.containsKey('symptom'), isFalse);
      expect(out['screen'], 'HomePage');
      expect(out['count'], 3);
    });

    test('键名大小写不敏感', () {
      final out = Analytics.scrub({'Email': 'x', 'Phone': '1', 'ok': 'y'});
      expect(out.keys, ['ok']);
    });

    test('snake_case / kebab 键归一化后命中黑名单', () {
      final out = Analytics.scrub({
        'display_name': 'A',
        'first-name': 'B',
        'avatar_url': 'u',
        'keep': 1,
      });
      expect(out.keys, ['keep']);
    });

    test('递归剥离嵌套 map 内的敏感键', () {
      final out = Analytics.scrub({
        'meta': {'email': 'a@b.com', 'screen': 'Home'},
        'top': 'ok',
      });
      expect(out['top'], 'ok');
      final meta = out['meta'] as Map;
      expect(meta.containsKey('email'), isFalse);
      expect(meta['screen'], 'Home');
    });

    test('返回新 map，不改原 map', () {
      final input = <String, Object>{'email': 'x', 'ok': 'y'};
      Analytics.scrub(input);
      expect(input.containsKey('email'), isTrue);
    });
  });

  group('Analytics.sanitizeTapLabel', () {
    test('普通 CTA 文案原样保留(去多余空白)', () {
      expect(Analytics.sanitizeTapLabel('  Lanjut  '), 'Lanjut');
      expect(Analytics.sanitizeTapLabel('Masuk dengan Google'), 'Masuk dengan Google');
    });

    test('空标签 → (unlabeled)', () {
      expect(Analytics.sanitizeTapLabel(''), '(unlabeled)');
      expect(Analytics.sanitizeTapLabel('   '), '(unlabeled)');
    });

    test('疑似 PII/自由文本 → (redacted)', () {
      expect(Analytics.sanitizeTapLabel('aurel@tailtopia.id'), '(redacted)'); // 含 @
      expect(Analytics.sanitizeTapLabel('0812345678'), '(redacted)'); // 长数字串
      expect(
        Analytics.sanitizeTapLabel('This is a long free-form note exceeding forty characters'),
        '(redacted)',
      ); // 过长自由文本(>40)
    });
  });

  group('Analytics.distinctIdFor', () {
    test('确定性：同 id 同输出', () {
      expect(Analytics.distinctIdFor(42), Analytics.distinctIdFor(42));
    });

    test('不同 id 不同输出', () {
      expect(Analytics.distinctIdFor(1) == Analytics.distinctIdFor(2), isFalse);
    });

    test('输出为 64 位十六进制哈希，且不含明文 id', () {
      final out = Analytics.distinctIdFor(12345);
      expect(out, matches(RegExp(r'^[0-9a-f]{64}$')));
      expect(out.contains('12345'), isFalse);
    });
  });
}
