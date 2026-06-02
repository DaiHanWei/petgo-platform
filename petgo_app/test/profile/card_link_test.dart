import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/profile/domain/card_link.dart';

void main() {
  test('拼出 /p/{cardToken}，用不可枚举 token', () {
    expect(petCardShareUrl('TOK123', baseUrl: 'https://petgo.app'), 'https://petgo.app/p/TOK123');
  });

  test('容忍 base 尾斜杠', () {
    expect(petCardShareUrl('T', baseUrl: 'https://petgo.app/'), 'https://petgo.app/p/T');
  });
}
