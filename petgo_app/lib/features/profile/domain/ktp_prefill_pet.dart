import '../data/current_pet_provider.dart';
import '../data/pet_list_repository.dart';

/// KTP 建卡页默认预填哪只宠物（V1.4.0 multi-pet Story 4.2 · AC1 · decision-log I2）。
///
/// - ① Diary Tab 当前宠物 ACTIVE → 它。「当前宠物」与 Diary / 4.1 问诊同一口径：
///   [currentPetId]（用户选过的那只）经 [resolveEffectivePetId] 回落（失效 / 未选 → 默认宠物）；
/// - ② 当前宠物只读（或 id 已失效且回落到的也只读）→ [pets] 中**最早创建**的 ACTIVE 宠物
///   （列表已按 `created_at ASC, id ASC`，取第一只 ACTIVE）；
/// - ③ 没有宠物 / 没有 ACTIVE 宠物（数据异常）→ null（不预填、不显示宠物行）。
///
/// AD-8：KTP 创建只列正常宠物（卡不挂宠物），只读宠物永远不会被选中。
int? resolveKtpPrefillPetId({required int? currentPetId, required List<PetListItem> pets}) {
  final current = resolveEffectivePetId(pets, currentPetId);
  for (final p in pets) {
    if (p.id == current && !p.isReadOnly) return p.id;
  }
  for (final p in pets) {
    if (!p.isReadOnly) return p.id;
  }
  return null;
}
