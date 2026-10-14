import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/core/storage/prefs.dart';
import 'package:tailtopia/features/ops/domain/ops_popup.dart';
import 'package:tailtopia/features/ops/domain/ops_popup_frequency.dart';

/// Story 5.4 · AC3：WIB 自然日、本地频次剔除、AppPrefs 存储（L0）。
OpsPopupCandidate _c(String token, OpsPopupFrequency f) =>
    OpsPopupCandidate(token: token, frequency: f, imageUrlId: 'https://x/$token', buttonTextId: 'B');

void main() {
  group('wibDateOf（UTC+7，不用手机本地时区）', () {
    test('WIB 午夜边界 = UTC 17:00', () {
      expect(wibDateOf(DateTime.utc(2026, 10, 12, 16, 59, 59)), '2026-10-12');
      expect(wibDateOf(DateTime.utc(2026, 10, 12, 17)), '2026-10-13');
      expect(wibDateOf(DateTime.utc(2026, 1, 5)), '2026-01-05', reason: '零填充');
      expect(wibDateOf(DateTime.utc(2026, 12, 31, 17)), '2027-01-01');
    });

    test('本地时区 DateTime 输入与其 UTC 等价值结果一致', () {
      final utc = DateTime.utc(2026, 10, 12, 16, 30);
      final local = utc.toLocal();
      expect(local.isUtc, isFalse);
      expect(wibDateOf(local), wibDateOf(utc));
    });
  });

  group('pickOpsPopup', () {
    const today = '2026-10-12';

    test('ONCE：有任何记录即剔除（含 once / 日期值）', () {
      final once = _c('a', OpsPopupFrequency.once);
      expect(pickOpsPopup([once], {'a': 'once'}, today), isNull);
      expect(pickOpsPopup([once], {'a': '2020-01-01'}, today), isNull);
      expect(pickOpsPopup([once], {'b': 'once'}, today), same(once));
    });

    test('DAILY：今天剔除；昨天 / once 不剔除', () {
      final daily = _c('d', OpsPopupFrequency.daily);
      expect(pickOpsPopup([daily], {'d': today}, today), isNull);
      expect(pickOpsPopup([daily], {'d': '2026-10-11'}, today), same(daily));
      expect(pickOpsPopup([daily], {'d': 'once'}, today), same(daily));
      expect(pickOpsPopup([daily], {}, today), same(daily));
    });

    test('保持服务端顺序取第一个未满的；全被剔除 → null', () {
      final a = _c('a', OpsPopupFrequency.once);
      final b = _c('b', OpsPopupFrequency.daily);
      final c = _c('c', OpsPopupFrequency.once);
      expect(pickOpsPopup([a, b, c], {}, today), same(a));
      expect(pickOpsPopup([a, b, c], {'a': 'once'}, today), same(b));
      expect(pickOpsPopup([a, b, c], {'a': 'once', 'b': today}, today), same(c));
      expect(pickOpsPopup([a, b, c], {'a': 'once', 'b': today, 'c': 'once'}, today), isNull);
      expect(pickOpsPopup(const [], {}, today), isNull);
    });

    test('写入值：ONCE → once，DAILY → 当天 WIB', () {
      final now = DateTime.utc(2026, 10, 12, 18);
      expect(opsPopupShownValue(OpsPopupFrequency.once, now), 'once');
      expect(opsPopupShownValue(OpsPopupFrequency.daily, now), '2026-10-13');
    });
  });

  group('AppPrefs 运营弹窗频次', () {
    Future<AppPrefs> prefsWith(Map<String, Object> init) async {
      SharedPreferences.setMockInitialValues(init);
      return AppPrefs(await SharedPreferences.getInstance());
    }

    test('缺失 → 空 Map', () async {
      expect((await prefsWith({})).opsPopupShown(), isEmpty);
    });

    test('损坏（非 JSON / 非对象）→ 空 Map，不抛；非字符串值条目丢弃', () async {
      expect((await prefsWith({AppPrefs.kOpsPopupShown: '{oops'})).opsPopupShown(), isEmpty);
      expect((await prefsWith({AppPrefs.kOpsPopupShown: '[1,2]'})).opsPopupShown(), isEmpty);
      expect((await prefsWith({AppPrefs.kOpsPopupShown: '"x"'})).opsPopupShown(), isEmpty);
      expect((await prefsWith({AppPrefs.kOpsPopupShown: '{"a":"once","b":1}'})).opsPopupShown(),
          {'a': 'once'});
    });

    test('mark：写入、覆盖同 token 旧值并移到末尾', () async {
      final p = await prefsWith({});
      await p.markOpsPopupShown('a', '2026-10-11');
      await p.markOpsPopupShown('b', 'once');
      await p.markOpsPopupShown('a', '2026-10-12');
      final m = p.opsPopupShown();
      expect(m, {'b': 'once', 'a': '2026-10-12'});
      expect(m.keys.toList(), ['b', 'a']);
    });

    test('第 201 条挤掉最早一条', () async {
      final p = await prefsWith({});
      for (var i = 0; i < AppPrefs.kOpsPopupShownCap; i++) {
        await p.markOpsPopupShown('t$i', 'once');
      }
      expect(p.opsPopupShown().length, 200);
      await p.markOpsPopupShown('t200', 'once');
      final m = p.opsPopupShown();
      expect(m.length, 200);
      expect(m.containsKey('t0'), isFalse);
      expect(m.containsKey('t1'), isTrue);
      expect(m.keys.last, 't200');
    });

    test('损坏值上 mark → 覆盖成合法 JSON', () async {
      final p = await prefsWith({AppPrefs.kOpsPopupShown: 'garbage'});
      await p.markOpsPopupShown('a', 'once');
      expect(p.opsPopupShown(), {'a': 'once'});
    });
  });
}
