/// 宠物品种精选清单（按物种联动）。
///
/// ⚠️ 后端 `breed` 仍是自由文本 `VARCHAR(60)`（无枚举）；下拉仅约束常见输入，
/// 「Lainnya（其他）」由 UI 单独追加走手填——值仍按自由 String 提交。清单用印尼语显示名，
/// 来源 2026-06-25 运营提供完整名单，见 CROSS-STORY-DECISIONS。「Lainnya」不入此清单。
const List<String> kCatBreeds = [
  'Kucing Kampung',
  'Kucing Anggora',
  'Kucing Persia',
  'British Shorthair',
  'Scottish Fold',
  'Kucing Siam',
  'Maine Coon',
  'Ragdoll',
  'American Shorthair',
  'Kucing Bengal',
  'Birman',
  'Burmese',
  'Devon Rex',
  'Exotic Shorthair',
  'Kucing Abyssinian',
  'Kucing Sphinx',
  'Norwegian Forest Cat',
  'Ocicat',
  'Oriental Shorthair',
  'Russian Blue',
  'Tonkinese',
  'Turkish Angora',
];

const List<String> kDogBreeds = [
  'Anjing Kampung',
  'Pomeranian',
  'Chihuahua',
  'Shih Tzu',
  'Pudel / Poodle',
  'Golden Retriever',
  'Siberian Husky',
  'Labrador Retriever',
  'Maltese',
  'Chow Chow',
  'Akita',
  'Anjing Gembala Jerman',
  'Basenji',
  'Beagle',
  'Border Collie',
  'Boxer',
  'Bulldog',
  'Corgi',
  'Dachshund',
  'Dobermann',
  'French Bulldog',
  'Great Dane',
  'Jack Russell Terrier',
  'Jindo',
  'Miniature Schnauzer',
  'Pekingese',
  'Pitbull Terrier',
  'Rottweiler',
  'Samoyed',
  'Shiba Inu',
  'Spitz',
  'Thai Bangkaew',
  'Thai Ridgeback',
  'Weimaraner',
  'Yorkshire Terrier',
];

/// 按物种返回品种清单；`OTHER`/未选 → 空清单（UI 走纯手填）。
List<String> breedsForPetType(String? petType) => switch (petType) {
      'CAT' => kCatBreeds,
      'DOG' => kDogBreeds,
      _ => const <String>[],
    };
