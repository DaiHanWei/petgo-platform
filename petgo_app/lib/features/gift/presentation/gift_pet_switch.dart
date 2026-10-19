import 'dart:async';

import 'package:flutter_riverpod/misc.dart' show ProviderListenable;

import '../../profile/data/current_pet_provider.dart';
import '../../profile/data/pet_list_repository.dart';

final RegExp _giftPageLink = RegExp(r'^/pets/(\d+)/gifts(?:[/?#]|$)');

/// 收礼深链里的宠物 id（`/pets/{petId}/gifts[?wall=expanded]`）；不是收礼页深链 → null。
int? giftDeepLinkPetId(String location) {
  final m = _giftPageLink.firstMatch(location);
  return m == null ? null : int.tryParse(m.group(1)!);
}

/// 收礼通知 / 推送进 Diary Tab 之前先切宠物（V1.4.0 gift Story 4.1 · AC6.4）。
///
/// 底座 Diary Tab 显示的是「当前宠物」，不一定是被送礼的那只：该 petId 在自己的宠物列表里 →
/// [CurrentPetIdNotifier.select]；不在（已删 / 已转手 / 列表取不到 / 游客）→ 不切，照原逻辑叠礼物页。
/// 当前宠物只经 `currentPetIdProvider` 切换（② §5「App 状态单一来源」）。
///
/// [read] 传 `ref.read`（`Ref` / `WidgetRef` 皆可）。列表最多等 [timeout]，取不到就放弃切换。
Future<void> selectGiftPetIfOwned(
  T Function<T>(ProviderListenable<T> provider) read,
  String location, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  final petId = giftDeepLinkPetId(location);
  if (petId == null) return;
  try {
    final pets = await read(petListProvider.future).timeout(timeout);
    if (!pets.any((p) => p.id == petId)) return;
    await read(currentPetIdProvider.notifier).select(petId);
  } catch (_) {
    // 列表取不到：不切宠物，礼物页照常按深链 petId 打开。
  }
}
