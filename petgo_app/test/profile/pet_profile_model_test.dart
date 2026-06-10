import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';

void main() {
  test('fromJson 解析全字段 + 日期', () {
    final p = PetProfile.fromJson({
      'id': 5,
      'name': 'Momo',
      'cardToken': 'TOK123',
      'avatarUrl': 'https://cdn/x.jpg',
      'breed': 'Shiba',
      'birthday': '2022-01-01',
      'intro': '好奇宝宝',
      'createdAt': '2026-06-02T00:00:00Z',
    });
    expect(p.id, 5);
    expect(p.cardToken, 'TOK123');
    expect(p.birthday, DateTime.parse('2022-01-01'));
    expect(p.createdAt!.isUtc, isTrue);
  });

  test('fromJson 容忍可空字段缺省', () {
    final p = PetProfile.fromJson({'id': 1, 'name': 'A', 'cardToken': 'T'});
    expect(p.avatarUrl, isNull);
    expect(p.breed, isNull);
    expect(p.birthday, isNull);
  });

  test('copyWith 保留 id/cardToken，覆盖可变字段', () {
    final p = PetProfile.fromJson({'id': 1, 'name': 'A', 'cardToken': 'T'});
    final p2 = p.copyWith(name: 'B', intro: 'hi');
    expect(p2.id, 1);
    expect(p2.cardToken, 'T');
    expect(p2.name, 'B');
    expect(p2.intro, 'hi');
  });
}
