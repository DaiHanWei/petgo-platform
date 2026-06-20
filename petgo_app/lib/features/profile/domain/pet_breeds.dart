/// 宠物品种精选清单（按物种联动）。
///
/// ⚠️ 后端 `breed` 仍是自由文本 `VARCHAR(60)`（无枚举）；下拉仅约束常见输入，
/// 「Lainnya（其他）」走手填——值仍按自由 String 提交。来源 2026-06-18 UI 保真
/// （P-30/P-32 RAS 下拉），见 CROSS-STORY-DECISIONS。清单为印尼常见品种，可按运营调整。
const List<String> kCatBreeds = [
  'Domestik',
  'Persia',
  'Anggora',
  'Maine Coon',
  'Siam',
  'British Shorthair',
  'Bengal',
  'Ragdoll',
  'Sphynx',
];

const List<String> kDogBreeds = [
  'Kampung',
  'Golden Retriever',
  'Labrador',
  'Poodle',
  'Pomeranian',
  'Shih Tzu',
  'Bulldog',
  'Chihuahua',
  'Husky',
];

/// 按物种返回品种清单；`OTHER`/未选 → 空清单（UI 走纯手填）。
List<String> breedsForPetType(String? petType) => switch (petType) {
      'CAT' => kCatBreeds,
      'DOG' => kDogBreeds,
      _ => const <String>[],
    };
