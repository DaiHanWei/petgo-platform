import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/gift/domain/gift_catalog.dart';

/// V1.5.0 gift-pack Story 2-1 · AC1（L0）：新旧目录 JSON 都能解析；receivedFrom 可空；分类名按语言取。
void main() {
  const oldJson = {
    'recipient': {'petId': 42, 'name': 'Mochi', 'petType': 'CAT'},
    'qrisMinAmount': 10000,
    'tiers': [
      {
        'price': 1000,
        'gifts': [
          {'giftToken': 'g1', 'nameEn': 'Bone', 'nameId': 'Tulang', 'price': 1000, 'kind': 'NORMAL'}
        ]
      }
    ],
    'exclusiveGroups': [],
  };

  test('old JSON (no categories / senderExp / receivedFrom) parses', () {
    final c = GiftCatalog.fromJson(Map<String, dynamic>.from(oldJson));
    expect(c.categories, isEmpty);
    expect(c.tiers.single.gifts.single.senderExp, 0);
    expect(c.tiers.single.gifts.single.receivedFrom, isNull);
  });

  test('new JSON parses categories, senderExp and receivedFrom', () {
    final c = GiftCatalog.fromJson({
      ...oldJson,
      'categories': [
        {
          'categoryId': 7,
          'nameEn': 'Snacks',
          'nameId': 'Camilan',
          'gifts': [
            {
              'giftToken': 'g1',
              'nameEn': 'Bone',
              'nameId': 'Tulang',
              'price': 1000,
              'senderExp': 5,
              'receivedFrom': {
                'senders': [
                  {'nickname': 'Ana', 'deleted': false},
                  {'deleted': true}
                ],
                'more': 2
              }
            },
            {'giftToken': 'g2', 'nameEn': 'Fish', 'nameId': 'Ikan', 'price': 1000, 'senderExp': 5}
          ]
        }
      ],
    });
    final cat = c.categories.single;
    expect(cat.categoryId, 7);
    expect(cat.nameFor(const Locale('id')), 'Camilan');
    expect(cat.nameFor(const Locale('en')), 'Snacks');
    final g1 = cat.gifts.first;
    expect(g1.senderExp, 5);
    expect(g1.receivedFrom!.senders.first.nickname, 'Ana');
    expect(g1.receivedFrom!.senders.last.deleted, isTrue);
    expect(g1.receivedFrom!.senders.last.nickname, isNull);
    expect(g1.receivedFrom!.more, 2);
    expect(cat.gifts.last.receivedFrom, isNull);
    expect(c.itemByToken('g2'), isNotNull);
  });
}
