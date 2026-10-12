import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/ops/domain/ops_popup.dart';
import 'package:tailtopia/features/ops/domain/ops_target.dart';

/// Story 5.4 · AC4.2 / AC4.3：候选模型容错与语言回落（L0）。
Map<String, dynamic> _full() => {
      'token': 'popTok000000000000001',
      'frequency': 'ONCE',
      'imageUrlId': 'https://x/id.webp',
      'imageUrlEn': 'https://x/en.webp',
      'titleId': 'Judul',
      'titleEn': 'Title',
      'bodyId': 'Isi',
      'bodyEn': 'Body',
      'buttonTextId': 'Lihat',
      'buttonTextEn': 'See',
      'target': {'type': 'CATEGORY', 'value': 'MAKANAN'},
    };

void main() {
  test('完整一条：字段全部解析，target 交给 OpsTarget.fromJson', () {
    final c = OpsPopupCandidate.fromJson(_full())!;
    expect(c.token, 'popTok000000000000001');
    expect(c.frequency, OpsPopupFrequency.once);
    expect(c.target, const OpsTarget(OpsTargetType.category, 'MAKANAN'));
  });

  test('DAILY 解析；缺 target / target 非法 → OpsTarget.none', () {
    final json = _full()
      ..['frequency'] = 'DAILY'
      ..remove('target');
    final c = OpsPopupCandidate.fromJson(json)!;
    expect(c.frequency, OpsPopupFrequency.daily);
    expect(c.target, OpsTarget.none);
    expect(OpsPopupCandidate.fromJson(_full()..['target'] = {'type': 'BOGUS'})!.target, OpsTarget.none);
    expect(OpsPopupCandidate.fromJson(_full()..['target'] = 'x')!.target, OpsTarget.none);
  });

  test('缺 token / imageUrlId / buttonTextId、frequency 非 ONCE/DAILY → 丢弃（null），不抛', () {
    for (final k in ['token', 'imageUrlId', 'buttonTextId', 'frequency']) {
      expect(OpsPopupCandidate.fromJson(_full()..remove(k)), isNull, reason: '缺 $k');
    }
    expect(OpsPopupCandidate.fromJson(_full()..['frequency'] = 'once'), isNull, reason: '区分大小写');
    expect(OpsPopupCandidate.fromJson(_full()..['frequency'] = 'WEEKLY'), isNull);
    expect(OpsPopupCandidate.fromJson(_full()..['token'] = 123), isNull);
    expect(OpsPopupCandidate.fromJson(_full()..['buttonTextId'] = '  '), isNull, reason: '空白按钮文案当缺失');
    expect(OpsPopupCandidate.fromJson(null), isNull);
    expect(OpsPopupCandidate.fromJson('x'), isNull);
    expect(OpsPopupCandidate.fromJson(<dynamic, dynamic>{..._full()}), isNotNull,
        reason: 'Map<dynamic, dynamic> 也要能解');
  });

  test('语言：EN 取 *En，缺失回落 *Id；ID 一律取 *Id（D-23）', () {
    final c = OpsPopupCandidate.fromJson(_full())!;
    expect(c.imageUrlFor(true), 'https://x/en.webp');
    expect(c.titleFor(true), 'Title');
    expect(c.bodyFor(true), 'Body');
    expect(c.buttonTextFor(true), 'See');
    expect(c.imageUrlFor(false), 'https://x/id.webp');
    expect(c.titleFor(false), 'Judul');
    expect(c.bodyFor(false), 'Isi');
    expect(c.buttonTextFor(false), 'Lihat');

    final idOnly = OpsPopupCandidate.fromJson({
      'token': 't',
      'frequency': 'DAILY',
      'imageUrlId': 'https://x/id.webp',
      'titleId': 'Judul',
      'buttonTextId': 'Buka',
    })!;
    expect(idOnly.imageUrlFor(true), 'https://x/id.webp', reason: 'EN 图为空回落 ID 图');
    expect(idOnly.titleFor(true), 'Judul');
    expect(idOnly.bodyFor(true), isNull);
    expect(idOnly.buttonTextFor(true), 'Buka');
  });
}
