/// D11 续租抽屉的默认勾选（V1.4.0 multi-pet Story 4.4 · AC3，纯函数）。
library;

import 'pet_slot_purchase.dart';

/// 默认勾选哪些月租名额（返回 token，按 [rentals] 原顺序，即「Slot sewa {n}」顺序）。
///
/// 默认勾选数 = `min(生效中月租数, renewableMax, max(0, 正常宠物数 − (defaultSlots + levelSlots)))`，
/// 从**最早到期**的开始勾；其余不勾（用户可手动再勾）。效果：升级拿到永久名额后，下次续租默认少勾一个。
/// `renewableMax` / `defaultSlots` / `levelSlots` 一律取 `GET /pet-slots/me` 下发值，App 不自算门槛。
/// 正常宠物数 = 已建只数 − 只读只数（只读的宠物本就不靠这些名额保持正常）。
List<String> defaultRenewSelection({
  required List<PetSlotRental> rentals,
  required int renewableMax,
  required int defaultSlots,
  required int levelSlots,
  required int petCount,
  int readOnlyCount = 0,
}) {
  final normalPets = petCount - readOnlyCount;
  var n = normalPets - (defaultSlots + levelSlots);
  if (n < 0) n = 0;
  if (n > renewableMax) n = renewableMax;
  if (n > rentals.length) n = rentals.length;
  if (n <= 0) return const [];
  // 稳定排序：同一到期时刻保持原顺序。
  final indexed = [for (var i = 0; i < rentals.length; i++) (i, rentals[i])];
  indexed.sort((a, b) {
    final c = a.$2.expiresAt.compareTo(b.$2.expiresAt);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  final picked = {for (final e in indexed.take(n)) e.$2.token};
  return [for (final r in rentals) if (picked.contains(r.token)) r.token];
}
