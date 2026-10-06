import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/card_link.dart';

void main() {
  test('拼出 /p/{cardToken}，用不可枚举 token', () {
    expect(petCardShareUrl('TOK123', baseUrl: 'https://petgo.app'), 'https://petgo.app/p/TOK123');
  });

  test('容忍 base 尾斜杠', () {
    expect(petCardShareUrl('T', baseUrl: 'https://petgo.app/'), 'https://petgo.app/p/T');
  });

  // v1.3.2 Story 4.1：四类分享卡的下载二维码。
  test('petDownloadUrl 拼出 /get（码内不带 ?src=qr）', () {
    expect(petDownloadUrl(baseUrl: 'https://s.tailtopia.id'), 'https://s.tailtopia.id/get');
  });

  test('petDownloadUrl 容忍 base 尾斜杠', () {
    expect(petDownloadUrl(baseUrl: 'https://s.tailtopia.id/'), 'https://s.tailtopia.id/get');
  });
}
