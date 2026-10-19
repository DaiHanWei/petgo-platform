import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/presentation/gift_pet_switch.dart';

/// V1.4.0 gift Story 4.1 · AC6.4（L0）：收礼深链里取宠物 id（推送冷 / 热启动切宠物用）。
void main() {
  test('只认收礼页深链 /pets/{id}/gifts', () {
    expect(giftDeepLinkPetId('/pets/12/gifts?wall=expanded'), 12);
    expect(giftDeepLinkPetId('/pets/12/gifts'), 12);
    expect(giftDeepLinkPetId('/pets/12/giftsx'), isNull);
    expect(giftDeepLinkPetId('/pets/12/visitor'), isNull);
    expect(giftDeepLinkPetId('/profile'), isNull);
    expect(giftDeepLinkPetId('/me/gift-orders'), isNull);
  });
}
