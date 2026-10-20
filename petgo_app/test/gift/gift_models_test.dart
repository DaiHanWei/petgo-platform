import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/api_paths.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';
import 'package:tailtopia/features/gift/domain/gift_send_result.dart';

/// V1.4.0 gift Story 1.3 · AC8（L0）：目录 / 发起响应 / 订单状态 JSON 解析（字段名对 1.1 的 DTO），缺字段不崩。
void main() {
  test('ApiPaths：三条送礼路径', () {
    expect(ApiPaths.giftCatalog, '/api/v1/gifts/catalog');
    expect(ApiPaths.petGifts(42), '/api/v1/pets/42/gifts');
    expect(ApiPaths.meGiftOrder('abc'), '/api/v1/me/gift-orders/abc');
  });

  test('GiftCatalog.fromJson：GiftCatalogResponse 全字段', () {
    final c = GiftCatalog.fromJson({
      'recipient': {'petId': 42, 'name': 'Mochi', 'avatarUrl': null, 'petType': 'CAT', 'breed': 'Persia'},
      'qrisMinAmount': 10000,
      'tiers': [
        {
          'price': 5000,
          'gifts': [
            {'giftToken': 'b', 'nameEn': 'Drumstick', 'nameId': 'Paha ayam', 'iconUrl': 'https://x/b.png', 'price': 5000, 'kind': 'NORMAL', 'minLevel': null},
          ],
        },
        {
          'price': 1000,
          'gifts': [
            {'giftToken': 'a', 'nameEn': 'Bone', 'nameId': 'Tulang', 'iconUrl': null, 'price': 1000, 'kind': 'NORMAL'},
          ],
        },
      ],
      'exclusiveGroups': [
        {
          'minLevel': 10,
          'gifts': [
            {'giftToken': 'x10', 'nameEn': 'Cape', 'nameId': 'Jubah', 'price': 25000, 'kind': 'EXCLUSIVE', 'minLevel': 10},
          ],
        },
        {
          'minLevel': 5,
          'gifts': [
            {'giftToken': 'x5', 'nameEn': 'Crown', 'nameId': 'Mahkota', 'price': 25000, 'kind': 'EXCLUSIVE', 'minLevel': 5},
          ],
        },
      ],
      'lockBlock': {'requiredLevel': 15},
    });
    expect(c.recipient.petId, 42);
    expect(c.recipient.name, 'Mochi');
    expect(c.recipient.petType, 'CAT');
    expect(c.qrisMinAmount, 10000);
    expect(c.lockBlock?.requiredLevel, 15);
    // 价位升序 → 专属组等级升序；第一项即默认选中。
    expect(c.orderedItems.map((g) => g.giftToken), ['a', 'b', 'x5', 'x10']);
    expect(c.orderedItems[2].isExclusive, isTrue);
    expect(c.orderedItems[2].minLevel, 5);
    expect(c.orderedItems[1].iconUrl, 'https://x/b.png');
    expect(c.itemByToken('b')?.price, 5000);
    expect(c.itemByToken('gone'), isNull);
  });

  test('GiftCatalog.fromJson：缺字段不崩；lockBlock null = B3', () {
    final c = GiftCatalog.fromJson({});
    expect(c.tiers, isEmpty);
    expect(c.exclusiveGroups, isEmpty);
    expect(c.lockBlock, isNull);
    expect(c.orderedItems, isEmpty);
    expect(c.recipient.name, '');
    expect(GiftCatalog.fromJson({'lockBlock': null, 'tiers': 'bad'}).lockBlock, isNull);
  });

  test('GiftItem.nameFor：id → nameId，其余 → nameEn；一侧为空退另一侧', () {
    const g = GiftItem(giftToken: 't', nameEn: 'Bone', nameId: 'Tulang', price: 1000);
    expect(g.nameFor(const Locale('id')), 'Tulang');
    expect(g.nameFor(const Locale('en')), 'Bone');
    expect(g.nameFor(const Locale('zh')), 'Bone');
    const onlyId = GiftItem(giftToken: 't', nameEn: '', nameId: 'Tulang', price: 1000);
    expect(onlyId.nameFor(const Locale('en')), 'Tulang');
  });

  test('GiftSendResult.fromJson：PawCoin 送达 / QRIS 待付（字段同 KeepsakePurchaseResult）', () {
    final d = GiftSendResult.fromJson({
      'delivered': true,
      'payment': null,
      'payload': null,
      'orderToken': 'ord',
      'status': 'DELIVERED',
    });
    expect(d.delivered, isTrue);
    expect(d.orderToken, 'ord');
    expect(d.status, GiftOrderStatus.delivered);

    final q = GiftSendResult.fromJson({
      'delivered': false,
      'payment': {'token': 'pi', 'displayNo': 'PAY-20261018-1'},
      'payload': '000201',
      'orderToken': 'ord2',
      'status': 'PENDING',
    });
    expect([q.delivered, q.paymentToken, q.paymentDisplayNo, q.paymentRef, q.payload],
        [false, 'pi', 'PAY-20261018-1', 'PAY-20261018-1', '000201']);
    expect(q.status, GiftOrderStatus.pending);
    // 网关出码失败（后端 chargeOrFailed，与重放旧 FAILED 单同形）
    final f = GiftSendResult.fromJson({
      'delivered': false,
      'payment': null,
      'payload': null,
      'orderToken': 'ord3',
      'status': 'FAILED',
    });
    expect([f.delivered, f.payload, f.paymentToken, f.orderToken], [false, null, null, 'ord3']);
    expect(f.status, GiftOrderStatus.failed);
    expect(GiftSendResult.fromJson({}).delivered, isFalse);
    expect(GiftSendResult.fromJson({'payment': {'token': 'pi'}}).paymentRef, 'pi');
  });

  test('GiftOrderStatus：七个后端状态 + 未知值不崩（按 PENDING 处理）', () {
    for (final s in [
      'PENDING',
      'DELIVERED',
      'EXPIRED',
      'FAILED',
      'UNDELIVERED_REFUND_PENDING',
      'UNDELIVERED_REFUNDED',
      'REFUND_MANUAL',
    ]) {
      expect(GiftOrderStatus.fromWire(s).wire, s);
    }
    expect(GiftOrderStatus.fromWire('SOMETHING_NEW'), GiftOrderStatus.unknown);
    expect(GiftOrderStatus.fromWire(null), GiftOrderStatus.unknown);
    expect(GiftOrderStatus.fromWire(''), GiftOrderStatus.unknown);
    expect(GiftOrderStatus.undeliveredRefunded.isUndelivered, isTrue);
    expect(GiftOrderStatus.refundManual.isUndelivered, isTrue);
    expect(GiftOrderStatus.expired.isUndelivered, isFalse);
  });

  test('GiftRecipientRef：值相等（family provider 的 key）；gift 4.1 起只有按宠物一种形态', () {
    expect(const GiftRecipientRef.pet(1), const GiftRecipientRef.pet(1));
    expect(const GiftRecipientRef.pet(1) == const GiftRecipientRef.pet(2), isFalse);
    expect(const GiftRecipientRef.pet(5).hashCode, const GiftRecipientRef.pet(5).hashCode);
  });
}
