import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/pet_passport/domain/pet_passport.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// V1.3.2 Story 1.2 · L0：护照接口线上契约（与后端 `PetPassportResponseContractTest` 同一套键）。
void main() {
  const wire = {
    'petName': 'Momo',
    'passportNo': 'TT02P2600128',
    'stampCount': 2,
    'stamps': [
      {
        'placeToken': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        'placeName': 'Kopi',
        'placeType': 'CAFE',
        'placeStatus': 'ACTIVE',
        'firstVisitDate': '2026-09-01',
        'visitCount': 3,
        'addressText': 'Jl. Kopi 1',
      },
      {
        'placeToken': 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
        'placeName': 'Taman',
        'placeType': 'PARK',
        'placeStatus': 'UNAVAILABLE',
        'firstVisitDate': '2026-09-20',
        'visitCount': 1,
      },
    ],
  };

  test('字段名与后端一一对应', () {
    final p = PetPassport.fromJson(Map<String, dynamic>.from(wire));
    expect(p.petName, 'Momo');
    expect(p.passportNo, 'TT02P2600128');
    expect(p.stampCount, 2);
    final s = p.stamps.first;
    expect(s.placeToken, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa');
    expect(s.placeType, PlaceType.cafe);
    expect(s.available, isTrue);
    expect(s.firstVisitDate, DateTime(2026, 9, 1));
    expect(s.visitCount, 3);
    expect(s.stampImageUrl, isNull, reason: '本 story 恒省略该键');
    expect(p.stamps[1].available, isFalse);
    // Story 1.3：地址只对 ACTIVE 下发。
    expect(s.addressText, 'Jl. Kopi 1');
    expect(p.stamps[1].addressText, isNull);
  });

  test('🔴 placeStatus 缺键 / 未知值 → 不可用（fail-closed）', () {
    final p = PetPassport.fromJson({
      ...wire,
      'stamps': [
        {'placeToken': 'x' * 32, 'placeName': 'A'},
        {'placeToken': 'y' * 32, 'placeName': 'B', 'placeStatus': 'MERGED'},
      ],
    });
    expect(p.stamps.map((s) => s.available), [false, false]);
  });

  test('focus：按 token 找页，找不到停第 1 页', () {
    final p = PetPassport.fromJson(Map<String, dynamic>.from(wire));
    expect(p.indexOfToken('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'), 1);
    expect(p.indexOfToken('nope'), 0);
    expect(p.indexOfToken(null), 0);
  });
}
