import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/tailsonality/data/tailsonality_repository.dart';
import 'package:tailtopia/features/tailsonality/domain/tailsonality_result.dart';

/// V1.3.2 Story 2.1 · AC6.4：结果 DTO 线格式契约。
///
/// fixture 键集 = 后端 `TailsonalityResultResponseContractTest.FULL_FIELDS`，改任一侧须同步另一侧。
void main() {
  const fullFields = {
    'token', 'typeCode', 'letters', 'energy', 'questionSet', 'resultIndex',
    'unlocked', 'unlockedAt', 'contentVersion', 'createdAt',
    // V1.3.2 Story 3.3
    'equipped',
    // 2026-10-09 配型改回付费
    'matchUnlocked', 'upgradePrice',
  };

  Map<String, dynamic> fixture() => {
        'token': 't' * 32,
        'typeCode': 'ENTJ-H',
        'letters': 'ENTJ',
        'energy': 'H',
        'questionSet': 'CAT',
        'resultIndex': 2,
        'unlocked': true,
        'unlockedAt': '2026-09-30T09:00:00Z',
        'contentVersion': 1,
        'createdAt': '2026-09-30T08:00:00Z',
        'equipped': true,
        'matchUnlocked': true,
        'upgradePrice': 2000,
      };

  test('fixture 与后端 FULL_FIELDS 同集，全字段解析', () {
    expect(fixture().keys.toSet(), fullFields);
    final r = TailsonalityResult.fromJson(fixture());
    expect(r.token, 't' * 32);
    expect(r.typeCode, 'ENTJ-H');
    expect(r.letters, 'ENTJ');
    expect(r.energy, 'H');
    expect(r.questionSet, 'CAT');
    expect(r.resultIndex, 2);
    expect(r.unlocked, isTrue);
    expect(r.unlockedAt, DateTime.parse('2026-09-30T09:00:00Z'));
    expect(r.contentVersion, 1);
    expect(r.createdAt, DateTime.parse('2026-09-30T08:00:00Z'));
    expect(r.equipped, isTrue);
    expect(TailsonalityResult.fromJson(fixture()..remove('equipped')).equipped, isFalse);
    expect(r.matchUnlocked, isTrue);
    expect(r.upgradePrice, 2000);
  });

  test('🔴 缺 matchUnlocked 键不得默认 true；缺 upgradePrice → null（按原价）', () {
    final m = fixture()
      ..remove('matchUnlocked')
      ..remove('upgradePrice');
    expect(TailsonalityResult.fromJson(m).matchUnlocked, isFalse);
    expect(TailsonalityResult.fromJson(m).upgradePrice, isNull);
    expect(TailsonalityResult.fromJson(fixture()..['matchUnlocked'] = 'true').matchUnlocked, isFalse);
  });

  test('缺 unlockedAt → null', () {
    final m = fixture()
      ..remove('unlockedAt')
      ..['unlocked'] = false;
    expect(TailsonalityResult.fromJson(m).unlockedAt, isNull);
  });

  test('🔴 缺 unlocked 键不得默认 true', () {
    final m = fixture()..remove('unlocked');
    expect(TailsonalityResult.fromJson(m).unlocked, isFalse);
    expect(TailsonalityResult.fromJson(fixture()..['unlocked'] = 'true').unlocked, isFalse);
  });

  group('仓库', () {
    late List<RequestOptions> sent;
    TailsonalityRepository repo(Object? Function(RequestOptions o) reply) {
      sent = [];
      final dio = Dio()
        ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
          sent.add(o);
          h.resolve(Response(requestOptions: o, statusCode: 200, data: reply(o)));
        }));
      return TailsonalityRepository(dio: dio);
    }

    test('submit：POST 只带 18 个题号键，不带题套', () async {
      final answers = {
        for (final q in [for (var i = 1; i <= 15; i++) 'Q$i', 'P1', 'P2', 'P3']) q: 0,
      };
      final r = await repo((_) => fixture()).submit(answers);
      expect(r.typeCode, 'ENTJ-H');
      expect(sent.single.method, 'POST');
      expect(sent.single.path, ApiPaths.petTailsonalityResults);
      final body = sent.single.data as Map;
      expect(body.length, 18);
      expect(body.containsKey('questionSet'), isFalse);
    });

    test('fetchResults / fetchResult 路径与解析', () async {
      final list = await repo((_) => {'items': [fixture()]}).fetchResults();
      expect(list.single.token, 't' * 32);
      expect(sent.single.path, ApiPaths.petTailsonalityResults);

      await repo((_) => fixture()).fetchResult('abc');
      expect(sent.single.path, '${ApiPaths.petTailsonalityResults}/abc');
      expect(ApiPaths.petTailsonalityResults, endsWith('/api/v1/pet-profiles/me/tailsonality/results'));
    });

    test('空列表', () async {
      expect(await repo((_) => {'items': []}).fetchResults(), isEmpty);
    });
  });
}
