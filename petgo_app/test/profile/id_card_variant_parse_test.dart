import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/id_card.dart';

/// V1.3.4 ktp Story 1.1：App 能解析新字段 `cardVariant`；缺键（旧后端 / 非学生卡）为 null；
/// 建卡响应多出的 `numberReused` 不影响解析；`effectiveCardType` 逻辑不变。
void main() {
  Map<String, dynamic> base() => <String, dynamic>{
        'id': 5,
        'serialId': 11,
        'name': 'Momo',
        'cardType': 'STUDENT',
        'hdUnlocked': false,
      };

  test('学生卡解析 cardVariant', () {
    final card = IdCard.fromJson({...base(), 'cardVariant': 'BINTANG'});
    expect(card.cardVariant, 'BINTANG');
    expect(card.effectiveCardType, 'STUDENT');
  });

  test('缺 cardVariant 键为 null（旧后端 / 非学生卡）', () {
    final card = IdCard.fromJson({...base(), 'cardType': 'SIM'});
    expect(card.cardVariant, isNull);
    expect(card.effectiveCardType, 'SIM');
  });

  test('建卡响应带 numberReused 也能解析', () {
    final card = IdCard.fromJson({...base(), 'cardVariant': 'KLASIK', 'numberReused': false});
    expect(card.id, 5);
    expect(card.cardVariant, 'KLASIK');
  });

  test('cardType 缺省仍视同 KTP', () {
    final json = base()..remove('cardType');
    expect(IdCard.fromJson(json).effectiveCardType, 'KTP');
  });
}
