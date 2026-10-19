import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';

/// V1.4.0 multi-pet Story 1.5 · AC1.1 / AC7：宠物列表解析 + `?petId=` 拼接（真 dio + 假适配器，L0）。
class _BodyAdapter implements HttpClientAdapter {
  _BodyAdapter(this.body);

  final String body;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    requests.add(options);
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  test('路径常量', () {
    expect(ApiPaths.petProfileList, '/api/v1/pet-profiles/me/list');
    expect(ApiPaths.petSlotsMe, '/api/v1/pet-slots/me');
    expect(ApiPaths.userPublicPets(7), '/api/v1/users/7/pets');
  });

  group('withPet', () {
    test('null → 原样（后端落默认宠物）', () {
      expect(ApiPaths.withPet(ApiPaths.petProfileMe, null), ApiPaths.petProfileMe);
    });

    test('无 query → ?petId=', () {
      expect(ApiPaths.withPet(ApiPaths.petProfileMe, 12), '/api/v1/pet-profiles/me?petId=12');
    });

    test('已有 query → &petId=', () {
      expect(ApiPaths.withPet('/api/v1/x?limit=20', 3), '/api/v1/x?limit=20&petId=3');
    });
  });

  test('GET /me/list：解析全部字段', () async {
    final adapter = _BodyAdapter('''
      [
        {"id": 1, "name": "Orenji", "avatarUrl": "https://x/a.jpg", "petType": "CAT",
         "breed": "Domestik", "birthday": "2026-06-01", "createdAt": "2026-06-02T03:04:05Z",
         "status": "ACTIVE", "isDefault": true, "tailsonalityBadge": "ENFP", "diaryCount": 2},
        {"id": 2, "name": "Kopi", "petType": "DOG", "status": "READ_ONLY", "isDefault": false,
         "diaryCount": 5}
      ]''');
    final repo = DioPetListRepository(Dio()..httpClientAdapter = adapter);

    final list = await repo.fetch();

    expect(adapter.requests.single.method, 'GET');
    expect(adapter.requests.single.path, ApiPaths.petProfileList);
    expect(list, hasLength(2));
    final a = list.first;
    expect(a.id, 1);
    expect(a.name, 'Orenji');
    expect(a.avatarUrl, 'https://x/a.jpg');
    expect(a.petType, 'CAT');
    expect(a.breed, 'Domestik');
    expect(a.birthday, DateTime(2026, 6, 1));
    expect(a.createdAt, DateTime.utc(2026, 6, 2, 3, 4, 5));
    expect(a.isDefault, isTrue);
    expect(a.isReadOnly, isFalse);
    expect(a.tailsonalityBadge, 'ENFP');
    expect(a.diaryCount, 2);
    expect(list[1].isReadOnly, isTrue);
    expect(list[1].isDefault, isFalse);
  });

  test('status 未知值 / 缺失 → 按 ACTIVE（不把正常宠物锁掉）', () {
    final unknown = PetListItem.fromJson({'id': 1, 'name': 'A', 'status': 'SOMETHING_NEW'});
    final missing = PetListItem.fromJson({'id': 2, 'name': 'B'});
    expect(unknown.isReadOnly, isFalse);
    expect(unknown.status, PetListItem.statusActive);
    expect(missing.isReadOnly, isFalse);
    expect(missing.isDefault, isFalse);
    expect(missing.diaryCount, isNull);
  });

  test('空数组 → 空列表', () async {
    final repo = DioPetListRepository(Dio()..httpClientAdapter = _BodyAdapter('[]'));
    expect(await repo.fetch(), isEmpty);
  });
}
