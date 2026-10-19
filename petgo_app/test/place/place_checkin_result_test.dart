import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/place/domain/place_checkin_result.dart';
import 'package:tailtopia/features/place/domain/place_summary.dart';

/// `PlaceCheckinResult` 线上契约（后端 `PlaceCheckinResponse`）。
///
/// V1.4.0 multi-pet Story 3.3：`place_checkin_result.dart` 的注释早就说契约由本文件钉住，但文件此前不存在 ——
/// 本 story 新建：顶层单宠字段（= 第一只，兼容旧版）+ 逐宠物 `pets[]`（Story 1.3）+ 缺省降级。
void main() {
  const wire = <String, dynamic>{
    'checkinToken': 'cccccccccccccccccccccccccccccccc',
    'placeToken': 'pppppppppppppppppppppppppppppppp',
    'placeName': 'Kopi Kucing',
    'placeType': 'CAFE',
    'visitDate': '2026-10-18',
    'isNewStamp': true,
    'visitCount': 1,
    'passportNo': 'TT02P2600128',
    'stampCount': 4,
  };

  test('顶层字段照旧解析', () {
    final r = PlaceCheckinResult.fromJson(Map<String, dynamic>.from(wire));
    expect(r.checkinToken, wire['checkinToken']);
    expect(r.placeToken, wire['placeToken']);
    expect(r.placeName, 'Kopi Kucing');
    expect(r.placeType, PlaceType.cafe);
    expect(r.visitDate, DateTime(2026, 10, 18));
    expect(r.isNewStamp, isTrue);
    expect(r.visitCount, 1);
    expect(r.passportNo, 'TT02P2600128');
    expect(r.stampCount, 4);
  });

  test('逐宠物 pets[]：petId / isNewStamp / visitCount / passportNo / stampCount，保持下发顺序', () {
    final r = PlaceCheckinResult.fromJson({
      ...wire,
      'pets': [
        {'petId': 5, 'isNewStamp': true, 'visitCount': 1, 'passportNo': 'P5', 'stampCount': 1},
        {'petId': 2, 'isNewStamp': false, 'visitCount': 3, 'passportNo': 'P2', 'stampCount': 7},
      ],
    });
    expect(r.pets.map((p) => p.petId), [5, 2]);
    expect(r.pets.first.isNewStamp, isTrue);
    expect(r.pets.last.isNewStamp, isFalse);
    expect(r.pets.last.visitCount, 3);
    expect(r.pets.last.passportNo, 'P2');
    expect(r.pets.last.stampCount, 7);
  });

  test('缺省降级：老后端无 pets → 空表；坏元素跳过；visitCount 非法 → 1', () {
    expect(PlaceCheckinResult.fromJson(Map<String, dynamic>.from(wire)).pets, isEmpty);
    expect(PlaceCheckinResult.fromJson({...wire, 'pets': 'x'}).pets, isEmpty);
    final r = PlaceCheckinResult.fromJson({
      ...wire,
      'pets': [
        {'isNewStamp': true},
        7,
        {'petId': 9, 'visitCount': 0},
      ],
    });
    expect(r.pets.single.petId, 9);
    expect(r.pets.single.visitCount, 1);
    expect(r.pets.single.isNewStamp, isFalse);
    expect(r.pets.single.passportNo, isNull);
  });
}
