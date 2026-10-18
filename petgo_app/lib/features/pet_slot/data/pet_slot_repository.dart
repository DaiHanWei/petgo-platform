import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_paths.dart';
import '../../../core/network/dio_client.dart';

/// 宠物名额摘要（V1.4.0 multi-pet Story 1.4 最小版 · `GET /api/v1/pet-slots/me`）。
///
/// ⚠️ Story 2.4 在**同一模型**上扩展（只追加字段），不另起一个。
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

class PetSlotRepository {
  PetSlotRepository(this.dio);

  final Dio dio;

  Future<PetSlotOverview> fetchMine() async {
    final resp = await dio.get<Map<String, dynamic>>(ApiPaths.petSlotsMe);
    return PetSlotOverview.fromJson(resp.data ?? const {});
  }
}

final Provider<PetSlotRepository> petSlotRepositoryProvider =
    Provider<PetSlotRepository>((ref) => PetSlotRepository(ref.read(dioProvider)));

/// 名额摘要。**只在点「+」时取**（`startAddPet` 先 invalidate 再读，不预取，Story 1.5 待拍板 2）；
/// 建档页据它推 `pet_added.slot_source`（建档**前**的口径）。失败不重试：失败直接进建档页，由后端 409 兜底。
final FutureProvider<PetSlotOverview> petSlotOverviewProvider = FutureProvider<PetSlotOverview>(
  (ref) => ref.read(petSlotRepositoryProvider).fetchMine(),
  retry: (_, _) => null,
);
