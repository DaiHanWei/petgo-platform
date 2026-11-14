import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/domain/gift_order_browse.dart';

/// V1.5.0 gift-pack Story 2-4 · AC1（L0）：主人 / 访客两种 JSON。
void main() {
  test('owner first page: opened, unopenedCount, categories, pet, viewerIsOwner', () {
    final p = GiftOrderPage.fromJson({
      'items': [
        {
          'orderToken': 'o1',
          'giftNameEn': 'Bone',
          'giftNameId': 'Tulang',
          'quantity': 2,
          'receivedAt': '2026-11-15T01:00:00Z',
          'sender': {'nickname': 'Ana', 'avatarUrl': 'https://a', 'deleted': false},
          'opened': false,
          'hasMessage': true
        }
      ],
      'nextCursor': 'c1',
      'unopenedCount': 1,
      'categories': [
        {'categoryId': 3, 'nameEn': 'Toys', 'nameId': 'Mainan'}
      ],
      'pet': {'petId': 42, 'name': 'Mochi', 'petType': 'CAT'},
      'viewerIsOwner': true,
    });
    final c = p.items.single;
    expect(c.unopened, isTrue);
    expect(c.quantity, 2);
    expect(c.hasMessage, isTrue);
    expect(c.sender.nickname, 'Ana');
    expect(p.nextCursor, 'c1');
    expect(p.unopenedCount, 1);
    expect(p.categories!.single.categoryId, 3);
    expect(p.pet!.name, 'Mochi');
    expect(p.viewerIsOwner, isTrue);
  });

  test('visitor / next page: no opened (null), no unopenedCount, deleted sender without nickname', () {
    final p = GiftOrderPage.fromJson({
      'items': [
        {
          'orderToken': 'o2',
          'giftNameEn': 'Bone',
          'giftNameId': 'Tulang',
          'sender': {'deleted': true}
        }
      ],
    });
    final c = p.items.single;
    expect(c.opened, isNull);
    expect(c.unopened, isFalse);
    expect(c.sender.deleted, isTrue);
    expect(c.sender.nickname, isNull);
    expect(p.unopenedCount, isNull);
    expect(p.categories, isNull);
    expect(p.pet, isNull);
    expect(p.nextCursor, isNull);
  });

  test('preview', () {
    final v = GiftPreview.fromJson({
      'giftCount': 12,
      'recent': [
        {'orderToken': 'o', 'giftNameEn': 'A', 'giftNameId': 'B', 'opened': true}
      ]
    });
    expect(v.giftCount, 12);
    expect(v.unopenedCount, isNull);
    expect(v.recent.single.opened, isTrue);
  });

  test('filter equality', () {
    expect(const GiftOrderFilter.category(3), const GiftOrderFilter.category(3));
    expect(const GiftOrderFilter.all() == const GiftOrderFilter.unopened(), isFalse);
  });
}
