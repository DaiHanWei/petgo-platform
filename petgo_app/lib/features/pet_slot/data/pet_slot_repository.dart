import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';
import '../../keepsake/domain/keepsake_purchase_result.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/domain/id_card.dart';
import '../domain/pet_slot_purchase.dart';

/// 宠物名额摘要（V1.4.0 multi-pet Story 1.4 最小版 · `GET /api/v1/pet-slots/me`）。
///
/// ⚠️ Story 2.4 在**同一模型**上扩展（只追加字段，与后端 2.2 在末尾追加的 `readOnlyCount` / `shortBy` /
/// `renewableMax` / `rentals[]` 一一对应），不另起一个。追加字段都有默认值，1.5 的构造调用零改动。
class PetSlotOverview {
  const PetSlotOverview({
    required this.total,
    required this.cap,
    required this.defaultSlots,
    required this.levelSlots,
    required this.rentalSlots,
    required this.petCount,
    required this.rentable,
    required this.canRent,
    this.nextLevelSlot,
    required this.unitPrice,
    required this.days,
    this.readOnlyCount = 0,
    this.shortBy = 0,
    this.renewableMax = 0,
    this.rentals = const [],
  });

  /// 名额（默认 + 等级 + 月租）。
  final int total;

  /// 只数硬上限（5）。
  final int cap;
  final int defaultSlots;
  final int levelSlots;
  final int rentalSlots;

  /// 已建只数。
  final int petCount;

  /// 还能租几个。
  final int rentable;
  final bool canRent;

  /// 下一个解锁名额的等级；null = 没有了。
  final int? nextLevelSlot;

  /// 月租单价（IDR，后端配置，**不写死**）。
  final int unitPrice;

  /// 一次月租天数。
  final int days;

  /// 只读宠物只数（Story 2.2 追加）。
  final int readOnlyCount;

  /// 缺的名额数 = max(0, petCount − total)（D-16「Slot kamu kurang {n}」；只读后新租的默认数量）。
  final int shortBy;

  /// 续租默认勾选上限 = max(0, 5 − 1 − levelSlots)（D11；后端下发，App 不自算）。
  final int renewableMax;

  /// 生效中的月租名额（`starts_at, id` 升序，index 从 1）。
  final List<PetSlotRental> rentals;

  /// 有生效中的月租名额 → 显示「Slot sewa」小节与「Perpanjang」次按钮。
  bool get hasRentals => rentals.isNotEmpty;

  /// 有只读宠物（或宠物数超出名额）→ Hewanku 标题右侧「Ubah pilihan ›」。
  bool get needsSelection => readOnlyCount > 0 || petCount > total;

  /// 已达只数上限 → A4b。
  bool get atCap => petCount >= cap;

  /// 名额已用满（未到上限）→ A4。
  bool get isFull => petCount >= total;

  factory PetSlotOverview.fromJson(Map<String, dynamic> json) {
    int i(String k, [int fallback = 0]) => (json[k] as num?)?.toInt() ?? fallback;
    return PetSlotOverview(
      total: i('total', 1),
      cap: i('cap', 5),
      defaultSlots: i('defaultSlots', 1),
      levelSlots: i('levelSlots'),
      rentalSlots: i('rentalSlots'),
      petCount: i('petCount'),
      rentable: i('rentable'),
      canRent: json['canRent'] as bool? ?? false,
      nextLevelSlot: (json['nextLevelSlot'] as num?)?.toInt(),
      unitPrice: i('unitPrice'),
      days: i('days', 30),
      readOnlyCount: i('readOnlyCount'),
      shortBy: i('shortBy'),
      renewableMax: i('renewableMax'),
      rentals: [
        for (final r in (json['rentals'] as List<dynamic>? ?? const []))
          if (r is Map) PetSlotRental.fromJson(r.cast<String, dynamic>()),
      ],
    );
  }
}

/// 建档 409 `pet-slot-full` 的扩展成员（Story 1.4 `PetSlotFullException`）：A4 抽屉直接用，不必再请求别的接口。
///
/// ⚠️ 只取数字 / 布尔扩展成员，**不渲染后端显示串**（NFR7）。
class PetSlotFullInfo {
  const PetSlotFullInfo({
    required this.petCount,
    required this.slotTotal,
    required this.unitPrice,
    this.canRent = false,
    this.nextLevelSlot,
  });

  final int petCount;
  final int slotTotal;
  final int unitPrice;
  final bool canRent;
  final int? nextLevelSlot;

  factory PetSlotFullInfo.fromOverview(PetSlotOverview o) => PetSlotFullInfo(
        petCount: o.petCount,
        slotTotal: o.total,
        unitPrice: o.unitPrice,
        canRent: o.canRent,
        nextLevelSlot: o.nextLevelSlot,
      );

  /// 从 409 ProblemDetail 响应体取扩展成员；缺字段按 0 / false。
  factory PetSlotFullInfo.fromProblemBody(Object? body) {
    final map = body is Map ? body.cast<String, dynamic>() : const <String, dynamic>{};
    int i(String k) => (map[k] as num?)?.toInt() ?? 0;
    return PetSlotFullInfo(
      petCount: i('petCount'),
      slotTotal: i('slotTotal'),
      unitPrice: i('unitPrice'),
      canRent: map['canRent'] as bool? ?? false,
      nextLevelSlot: (map['nextLevelSlot'] as num?)?.toInt(),
    );
  }
}

/// ProblemDetail type 末段（与后端 `ErrorTypes.PET_SLOT_FULL` / `PET_LIMIT_REACHED` 对齐）。
const String kPetSlotFullSlug = 'pet-slot-full';
const String kPetLimitReachedSlug = 'pet-limit-reached';

/// Story 2.1 / 2.2 的 409 type 末段（App 按 slug 分流，不渲染 `detail`）。
const String kPetReadOnlySlug = 'pet-read-only';
const String kPetSlotRentUnavailableSlug = 'pet-slot-rent-unavailable';
const String kPetSlotRenewInvalidSlug = 'pet-slot-renew-invalid';

/// 资金下单的 `Idempotency-Key`（后端缺失 → 422；长度上限 80）：随机 128 bit，不带任何用户信息。
/// 每次点主按钮生成一次；同一次点击内的重试沿用同一个键。
String newPetSlotIdempotencyKey([Random? random]) {
  final r = random ?? Random.secure();
  final b = StringBuffer('petslot-');
  for (var i = 0; i < 16; i++) {
    b.write(r.nextInt(256).toRadixString(16).padLeft(2, '0'));
  }
  return b.toString();
}

class PetSlotRepository {
  PetSlotRepository(this.dio);

  final Dio dio;

  Future<PetSlotOverview> fetchMine() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petSlotsMe);
    return PetSlotOverview.fromJson(resp.data ?? const {});
  }

  /// 新租 [quantity] 个（Story 2.2 · `POST /pet-slots/me/rentals`）。响应与 `KeepsakePurchaseResponse` 同形。
  Future<KeepsakePurchaseResult> rent({
    required int quantity,
    required HdPayChannel channel,
    required String idempotencyKey,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.petSlotRentals,
      data: {'quantity': quantity, 'channel': channel.wire},
      options: Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }

  /// 续租勾选的名额（`POST /pet-slots/me/renewals`，一次付款）。
  Future<KeepsakePurchaseResult> renew({
    required List<String> rentalTokens,
    required HdPayChannel channel,
    required String idempotencyKey,
  }) async {
    final resp = await dio.post<Map<String, dynamic>>(
      ApiPaths.petSlotRenewals,
      data: {'rentalTokens': rentalTokens, 'channel': channel.wire},
      options: Options(headers: <String, dynamic>{'Idempotency-Key': idempotencyKey}),
    );
    return KeepsakePurchaseResult.fromJson(resp.data ?? const {});
  }

  /// 购买单状态（QRIS `pollPaid` 轮询 / PawCoin 成交后取 `expiresAt`）。
  Future<PetSlotPurchaseStatus> purchaseStatus(String purchaseToken) async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petSlotPurchase(purchaseToken));
    return PetSlotPurchaseStatus.fromJson(resp.data ?? const {});
  }

  /// 改选保持正常的宠物（Story 2.1 · `PUT /pet-profiles/me/active-selection`）；返回最新宠物列表。
  Future<List<PetListItem>> changeSelection(List<int> petIds) async {
    final resp = await dio.put<List<dynamic>>(ApiPaths.petActiveSelection, data: {'petIds': petIds});
    return [
      for (final e in resp.data ?? const [])
        if (e is Map) PetListItem.fromJson(e.cast<String, dynamic>()),
    ];
  }
}

final Provider<PetSlotRepository> petSlotRepositoryProvider =
    Provider<PetSlotRepository>((ref) => PetSlotRepository(ref.read(dioProvider)));

/// 名额摘要。点「+」时取（`startAddPet` 先 invalidate 再读，不预取，Story 1.5 待拍板 2）；
/// 建档页据它推 `pet_added.slot_source`（建档**前**的口径）。失败不重试：失败直接进建档页，由后端 409 兜底。
/// Story 2.4：名额管理页 watch 它（进页 / 租 / 续 / 改选成功后 invalidate；失败 → D15 整页重试）。
final FutureProvider<PetSlotOverview> petSlotOverviewProvider = FutureProvider<PetSlotOverview>(
  (ref) => ref.read(petSlotRepositoryProvider).fetchMine(),
  retry: (_, _) => null,
);
