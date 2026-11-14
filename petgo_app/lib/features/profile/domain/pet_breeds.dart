/// 宠物品种清单（V1.5.0 pet-species Story 3.1 起只是兼容入口：清单本体在 `pet_species.dart`）。
///
/// ⚠️ 后端 `breed` 仍是自由文本 `VARCHAR(60)`（无枚举）；存库值 = 选项全文。猫 22 → 39、狗 35 → 43（PRD 附录 A），
/// 旧清单的 Thai Bangkaew、Weimaraner 不在新清单——已选的存量宠物按手填值显示（PRD 3.1）。
library;

import 'pet_species.dart';

export 'pet_species.dart';

final Map<PetSpeciesCode, List<String>> _labelCache = {};

List<String> _labels(PetSpeciesCode code) =>
    _labelCache.putIfAbsent(code, () => List.unmodifiable(breedsFor(code).map((b) => b.label)));

/// 猫品种（新清单全文，39）。
final List<String> kCatBreeds = _labels(PetSpeciesCode.cat);

/// 狗品种（新清单全文，43）。
final List<String> kDogBreeds = _labels(PetSpeciesCode.dog);

/// 按物种返回品种清单（旧签名保留：别的线新增的调用点照常编译）。
/// 先按细分类型线上值解析（RABBIT 等），不认识再按大类；`OTHER` / 未选 → 空清单。
List<String> breedsForPetType(String? petType) {
  final code = PetSpeciesCode.fromWire(petType);
  if (code == null || code == PetSpeciesCode.other) return const <String>[];
  return _labels(code);
}
