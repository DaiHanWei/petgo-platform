import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/network/problem_detail.dart';
import 'package:tailtopia/features/pet_slot/data/pet_slot_repository.dart';
import 'package:tailtopia/features/pet_slot/domain/pet_slot_purchase.dart';
import 'package:tailtopia/features/pet_slot/presentation/pet_slot_page.dart';
import 'package:tailtopia/features/pet_slot/presentation/pet_slot_purchase_flow.dart';

import 'pet_slot_fakes.dart';

/// V1.4.0 multi-pet Story 2.4 · T1：名额摘要解析（与后端 2.2 `PetSlotOverview` 末尾追加字段对齐）、名额格推导、续租默认勾选。
void main() {
  group('PetSlotOverview.fromJson', () {
    test('1.4 字段 + 2.2 追加字段（readOnlyCount / shortBy / renewableMax / rentals[]）', () {
      final o = PetSlotOverview.fromJson({
        'total': 3,
        'cap': 5,
        'defaultSlots': 1,
        'levelSlots': 1,
        'rentalSlots': 1,
        'petCount': 4,
        'rentable': 2,
        'canRent': true,
        'nextLevelSlot': 10,
        'unitPrice': 7000,
        'days': 30,
        'readOnlyCount': 1,
        'shortBy': 1,
        'renewableMax': 3,
        'rentals': [
          {
            'token': 'tokA',
            'index': 1,
            'source': 'RENT',
            'startsAt': '2026-10-01T00:00:00Z',
            'expiresAt': '2026-10-31T00:00:00Z',
            'daysLeft': 13,
          },
          {
            'token': 'tokB',
            'index': 2,
            'source': 'ADMIN_GRANT',
            'startsAt': '2026-09-16T00:00:00Z',
            'expiresAt': '2026-10-20T10:00:00Z',
            'daysLeft': 2,
          },
        ],
      });
      expect(o.total, 3);
      expect(o.unitPrice, 7000);
      expect(o.readOnlyCount, 1);
      expect(o.shortBy, 1);
      expect(o.renewableMax, 3);
      expect(o.hasRentals, isTrue);
      expect(o.needsSelection, isTrue);
      // 顺序保持后端下发（starts_at, id 升序；index 从 1）。
      expect(o.rentals.map((r) => r.token), ['tokA', 'tokB']);
      expect(o.rentals.map((r) => r.index), [1, 2]);
      expect(o.rentals[0].expiresAt, DateTime.utc(2026, 10, 31));
      expect(o.rentals[0].daysLeft, 13);
      expect(o.rentals[0].endsSoon, isFalse);
      expect(o.rentals[1].daysLeft, 2);
      expect(o.rentals[1].endsSoon, isTrue, reason: 'D-13：≤ 3 天显示提醒色');
      expect(o.rentals[1].source, PetSlotRental.sourceAdminGrant);
    });

    test('旧后端（只有 1.4 字段）→ 追加字段取默认值，不抛', () {
      final o = PetSlotOverview.fromJson({'total': 2, 'petCount': 2, 'unitPrice': 5000, 'days': 30});
      expect(o.readOnlyCount, 0);
      expect(o.shortBy, 0);
      expect(o.renewableMax, 0);
      expect(o.rentals, isEmpty);
      expect(o.hasRentals, isFalse);
      expect(o.needsSelection, isFalse);
    });

    test('续租新到期 = 原到期 + days', () {
      final r = rental(1, DateTime.utc(2026, 10, 31));
      expect(r.renewedExpiry(30), DateTime.utc(2026, 11, 30));
    });
  });

  group('PetSlotPurchaseStatus', () {
    test('PAID 带 expiresAt；ORPHAN_PAID / OVERPAID 归「已到账、交人工」', () {
      final paid = PetSlotPurchaseStatus.fromJson({'status': 'PAID', 'expiresAt': '2026-11-12T05:00:00Z'});
      expect(paid.isPaid, isTrue);
      expect(paid.expiresAt, DateTime.utc(2026, 11, 12, 5));
      for (final s in ['ORPHAN_PAID', 'OVERPAID']) {
        final st = PetSlotPurchaseStatus.fromJson({'status': s});
        expect(st.isPaid, isFalse);
        expect(st.isPaidNeedsSupport, isTrue, reason: s);
      }
      expect(PetSlotPurchaseStatus.fromJson({'status': 'FAILED'}).isPaidNeedsSupport, isFalse);
    });
  });

  group('名额格（AC1.2）', () {
    test('D1：默认 + 等级各一、两只正常 → Bawaan / Level + 三格「Bisa disewa」', () {
      final cells = PetSlotCell.build(overview(petCount: 2), [pet(1, 'Orenji'), pet(2, 'Kopi')]);
      expect(cells, hasLength(5));
      expect(cells.map((c) => c.kind), [
        PetSlotCellKind.pet,
        PetSlotCellKind.pet,
        PetSlotCellKind.available,
        PetSlotCellKind.available,
        PetSlotCellKind.available,
      ]);
      expect(cells.take(2).map((c) => c.source), ['default', 'level']);
    });

    test('D3：名额 2、宠物 3（第 1 只只读）→ 正常在前占名额，只读排到名额外', () {
      final cells = PetSlotCell.build(overview(petCount: 3, readOnlyCount: 1),
          [pet(1, 'Boba', readOnly: true), pet(2, 'Orenji'), pet(3, 'Kopi')]);
      expect(cells.take(3).map((c) => c.pet!.name), ['Orenji', 'Kopi', 'Boba']);
      expect(cells[2].source, isNull, reason: '超出名额的只读宠物格没有来源');
      expect(cells.skip(3).every((c) => c.kind == PetSlotCellKind.available), isTrue);
    });

    test('D4：名额 5、宠物 4 → 第 5 格「Kosong」带来源 rent', () {
      final o = overview(
          levelSlots: 2,
          petCount: 4,
          rentals: [rental(1, DateTime.utc(2026, 10, 31)), rental(2, DateTime.utc(2026, 10, 16))]);
      final cells = PetSlotCell.build(o, [pet(1, 'A'), pet(2, 'B'), pet(3, 'C'), pet(4, 'D')]);
      expect(cells.map((c) => c.source), ['default', 'level', 'level', 'rent', 'rent']);
      expect(cells.last.kind, PetSlotCellKind.empty);
    });
  });

  group('续租默认勾选（AC3.2）', () {
    test('全部生效中月租，但最多 renewableMax 个', () {
      final o = overview(levelSlots: 2, petCount: 3, renewableMax: 2, rentals: [
        rental(1, DateTime.utc(2026, 10, 31)),
        rental(2, DateTime.utc(2026, 11, 3)),
      ]);
      expect(PetSlotRenewSelection.defaultFor(o).tokens, {'r1', 'r2'});
      final limited = overview(levelSlots: 3, petCount: 3, renewableMax: 1, rentals: [
        rental(1, DateTime.utc(2026, 10, 31)),
      ]);
      final sel = PetSlotRenewSelection.defaultFor(
          PetSlotOverview.fromJson({'renewableMax': 1, 'rentals': [
            {'token': 'a', 'index': 1, 'expiresAt': '2026-10-31T00:00:00Z'},
            {'token': 'b', 'index': 2, 'expiresAt': '2026-11-01T00:00:00Z'},
          ]}));
      expect(limited.rentals, hasLength(1));
      expect(sel.tokens, {'a'}, reason: '升级拿到永久名额 → 默认少勾一个');
      expect(sel.canCheck('b'), isFalse, reason: '超过上限的行可见、不可勾');
      sel.toggle('a');
      expect(sel.tokens, isEmpty);
      expect(sel.canCheck('b'), isTrue);
    });
  });

  group('杂项', () {
    test('Idempotency-Key：随机、不含用户信息、≤ 80 字符', () {
      final a = newPetSlotIdempotencyKey(Random(1));
      final b = newPetSlotIdempotencyKey(Random(2));
      expect(a, startsWith('petslot-'));
      expect(a.length, lessThanOrEqualTo(80));
      expect(a, isNot(b));
      expect(RegExp(r'^petslot-[0-9a-f]{32}$').hasMatch(newPetSlotIdempotencyKey()), isTrue);
    });

    test('ProblemDetail 扩展成员（pet-read-only 的 petId）', () {
      final pd = ProblemDetail.fromJson({
        'type': 'https://petgo/errors/pet-read-only',
        'status': 409,
        'traceId': 't',
        'petId': 42,
      })!;
      expect(pd.typeSlug, kPetReadOnlySlug);
      expect(pd.extensions, {'petId': 42});
      expect(ProblemDetail.fromJson({'type': 'x'})!.extensions, isEmpty);
    });

    test('价格格式 Rp5.000', () => expect(petSlotPrice(5000), 'Rp5.000'));
  });
}
