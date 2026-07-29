import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/analytics/analytics.dart';
import 'package:tailtopia/core/analytics/button_ids.dart';

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

    test('自由文本键整键丢弃（埋点治理 P0 兜底：button_name/title/label/text/content/caption）', () {
      final out = Analytics.scrub({
        'button_name': 'Haha Dog · 0y 0m',
        'title': 'x',
        'label': 'x',
        'text': 'x',
        'content': 'x',
        'caption': 'x',
        'button_id': 'triage.start',
      });
      expect(out.keys, ['button_id']);
    });

    test('超长字符串值(>64)整键丢弃——大概率是用户内容', () {
      final out = Analytics.scrub({
        'note': 'a' * 65,
        'ok': 'a' * 64,
        'count': 999,
      });
      expect(out.containsKey('note'), isFalse);
      expect(out['ok'], 'a' * 64);
      expect(out['count'], 999);
    });
  });

  group('Analytics.buttonTapped 白名单（埋点治理 P0）', () {
    test('ButtonId 全部常量均已登记', () {
      for (final id in const [
        ButtonId.triageStart, ButtonId.triageUpload, ButtonId.consultStart,
        ButtonId.publishSubmit, ButtonId.profileCreate, ButtonId.milestoneShare,
        ButtonId.vetAcceptQueue, ButtonId.vetAdviceTemplate,
      ]) {
        expect(Analytics.isRegisteredButtonId(id), isTrue, reason: id);
      }
    });

    test('未登记 id 不在白名单（release 静默丢弃、debug 断言拦截）', () {
      expect(Analytics.isRegisteredButtonId('free text from ui'), isFalse);
      expect(Analytics.isRegisteredButtonId(''), isFalse);
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

  group('Analytics.isAppsFlyerEvent（归因白名单，交付文档 §4.1）', () {
    test('归因关键转化在白名单内', () {
      for (final e in [
        'af_complete_registration', 'af_purchase', 'af_initiated_checkout',
        'pet_profile_create_submitted', 'triage_submitted', 'consult_started',
      ]) {
        expect(Analytics.isAppsFlyerEvent(e), isTrue, reason: e);
      }
    });

    test('非白名单事件不分发 AppsFlyer（不复制 PostHog 全量）', () {
      expect(Analytics.isAppsFlyerEvent('login_tapped'), isFalse);
      expect(Analytics.isAppsFlyerEvent('button_tapped'), isFalse);
      expect(Analytics.isAppsFlyerEvent(''), isFalse);
    });

    test('PawCoin 消耗类事件不得进收入白名单（防 ROAS 双倍虚报）', () {
      expect(Analytics.isAppsFlyerEvent('consult_paid'), isFalse);
    });

    test('白名单事件名 ≤45 字符（超长 dashboard 不可见）', () {
      for (final e in Analytics.appsflyerEvents) {
        expect(e.length, lessThanOrEqualTo(45), reason: e);
      }
    });
  });
}
