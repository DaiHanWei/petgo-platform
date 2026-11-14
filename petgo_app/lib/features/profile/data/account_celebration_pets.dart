import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/celebration_pet.dart';
import 'pet_list_repository.dart';

/// 多宠庆祝页（V1.4.2 social-topic Story 2.3 · UI B2）宠物行最多显示几只。
const int kCelebrationPetsMax = 5;

/// 账号级里程碑庆祝前取宠物行数据：账号下 ACTIVE 宠物（`status != READ_ONLY`），按列表顺序（= 建档顺序，
/// 后端 `PetResolver.listOwned` 按 `created_at, id` 升序），取前 [kCelebrationPetsMax] 只。
///
/// 经 [ProviderContainer] 读（调用方 `ProviderScope.containerOf(context, listen: false)`）：庆祝层是 root
/// navigator 上的 dialog，依赖在弹出前取好，不在庆祝 widget 内 watch。读失败 / 超过 [timeout] → 空表 → 庆祝页退化为单宠（B1）。
Future<List<CelebrationPet>> readAccountCelebrationPets(
  ProviderContainer c, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  try {
    final pets = await c.read(petListProvider.future).timeout(timeout);
    return [
      for (final p in pets.where((p) => !p.isReadOnly).take(kCelebrationPetsMax))
        CelebrationPet(name: p.name, avatarUrl: p.avatarUrl, petType: p.petType, id: p.id, species: p.species),
    ];
  } catch (_) {
    return const [];
  }
}
