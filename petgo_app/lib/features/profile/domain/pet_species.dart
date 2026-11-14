/// 宠物细分类型清单（V1.5.0 pet-species Story 3.1 · 架构 D-16 · PRD §3.1 / 附录 A / 附录 B）。
///
/// - [PetSpeciesCode]：27 值，`wire` 与后端 `PetSpecies` **同名同序**（`pet_species_backend_sync_test.dart` 守）。
/// - [kSpeciesTypes]：类型宫格 15 项（顺序固定，「其他」最后）；界面名走 l10n，表里的 `nameId` / `nameEn`
///   只给搜索与卡面用。
/// - 品种存库值 = 选项全文（如「Pudel / Poodle」）；「/」两边都作搜索别名。
/// - 「其他」下按物种分组（[kOtherSpeciesGroups]）；每组末尾另有「`<titleId> lainnya`」项，选它存库品种 = `titleId`。
/// - 匹配 / 手填解析见本文件下半部；**建档 / 编辑 / 身份证三处共用**，不许各写一份。
library;

/// 细分类型（与后端 `com.tailtopia.profile.domain.PetSpecies` 同名同序；只往后加）。
enum PetSpeciesCode {
  cat('CAT'),
  dog('DOG'),
  rabbit('RABBIT'),
  hamster('HAMSTER'),
  bird('BIRD'),
  chicken('CHICKEN'),
  fish('FISH'),
  lizard('LIZARD'),
  turtle('TURTLE'),
  sugarGlider('SUGAR_GLIDER'),
  snake('SNAKE'),
  duck('DUCK'),
  frog('FROG'),
  guineaPig('GUINEA_PIG'),
  civet('CIVET'),
  hedgehog('HEDGEHOG'),
  hermitCrab('HERMIT_CRAB'),
  otter('OTTER'),
  sheep('SHEEP'),
  cow('COW'),
  spider('SPIDER'),
  squirrel('SQUIRREL'),
  goat('GOAT'),
  crab('CRAB'),
  insect('INSECT'),
  mouse('MOUSE'),
  other('OTHER');

  const PetSpeciesCode(this.wire);

  /// 线上值（与后端枚举名一致）。
  final String wire;

  /// 线上值 → 枚举（区分大小写）；null / 不认识 → null。
  static PetSpeciesCode? fromWire(String? wire) {
    if (wire == null) return null;
    for (final c in values) {
      if (c.wire == wire) return c;
    }
    return null;
  }

  /// 「其他」组的 12 个物种（显式列出，不按序号区间推：以后在 OTHER 之后追加独立类型不会被误归「其他」）。
  static const Set<PetSpeciesCode> _otherGroup = {
    civet, hedgehog, hermitCrab, otter, sheep, cow, spider, squirrel, goat, crab, insect, mouse,
  };

  /// 是否「其他」组下的具体物种（CIVET … MOUSE；不含 OTHER 本身）。
  bool get isOtherGroupMember => _otherGroup.contains(this);

  /// 宫格上对应的那一项：「其他」组物种与 OTHER → OTHER，其余是自己。「是否改了类型」只比较它。
  PetSpeciesCode get pickerType =>
      this == PetSpeciesCode.other || isOtherGroupMember ? PetSpeciesCode.other : this;

  /// 大类线上值（CAT / DOG / OTHER）。业务逻辑（健康提醒、推荐分池……）继续按它走。
  String get groupWire => switch (this) {
        PetSpeciesCode.cat => 'CAT',
        PetSpeciesCode.dog => 'DOG',
        _ => 'OTHER',
      };
}

/// 一个品种选项：显示全文，`/` 两边各段都作搜索别名。
class BreedOption {
  const BreedOption(this.label);

  /// 显示与存库的全文（如「Pudel / Poodle」）。
  final String label;

  /// 按 `/` 切开的每段（trim 后非空）；「Catfish / lele/patin」→ Catfish、lele、patin。
  List<String> get aliases =>
      label.split('/').map((s) => s.trim()).where((s) => s.isNotEmpty).toList(growable: false);
}

/// 类型宫格上的一项。
class SpeciesType {
  const SpeciesType({
    required this.code,
    required this.emoji,
    required this.nameId,
    required this.nameEn,
    this.breeds = const [],
  });

  final PetSpeciesCode code;
  final String emoji;

  /// 印尼语名（搜索候选 / 卡面）。界面名走 l10n。
  final String nameId;

  /// 英语名（搜索候选）。
  final String nameEn;

  /// 该类型的品种（「其他」为空，按 [kOtherSpeciesGroups] 分组）。
  final List<BreedOption> breeds;
}

/// 「其他」下的一个物种分组。
class SpeciesGroup {
  const SpeciesGroup({
    required this.code,
    required this.titleId,
    required this.titleEn,
    required this.breeds,
  });

  final PetSpeciesCode code;

  /// 印尼语组名（也是「`<titleId> lainnya`」选项的存库品种值）。
  final String titleId;
  final String titleEn;

  /// 预设品种（不含末尾的「`<titleId> lainnya`」项；该项由界面按 l10n 追加）。
  final List<BreedOption> breeds;
}

List<BreedOption> _b(List<String> labels) => [for (final l in labels) BreedOption(l)];

/// 类型宫格 15 项（PRD 3.1 表，顺序固定，「其他」最后）。
final List<SpeciesType> kSpeciesTypes = List.unmodifiable([
  SpeciesType(code: PetSpeciesCode.cat, emoji: '🐱', nameId: 'Kucing', nameEn: 'Cat', breeds: _b(const [
    'Kucing Kampung', 'Kucing Anggora', 'Kucing Persia', 'British Shorthair', 'Kucing Siam', 'Ragdoll',
    'Maine Coon', 'Himalaya', 'Scottish Fold', 'Kucing Bengal', 'Turkish Angora', 'American Shorthair',
    'Munchkin', 'Exotic Shorthair', 'Russian Blue', 'Norwegian Forest Cat', 'British Longhair', 'Ocicat',
    'Siberian', 'Birman', 'Khao Manee', 'Kucing Sphinx', 'Oriental Shorthair', 'Snowshoe', 'Turkish Van',
    'Burmese', 'Japanese Bobtail / Bobtail', 'Li Hua / Dragon Li', 'Scottish Straight', 'Manx', 'Bombay',
    'Chartreux', 'Devon Rex', 'Kinkalow', 'Kucing Abyssinian', 'Minuet / Napoleon', 'Raas / Kucing Raas',
    'Tonkinese', 'Campuran / mix',
  ])),
  SpeciesType(code: PetSpeciesCode.dog, emoji: '🐶', nameId: 'Anjing', nameEn: 'Dog', breeds: _b(const [
    'Anjing Kampung', 'Pudel / Poodle', 'Pomeranian', 'Shih Tzu', 'Golden Retriever', 'Chihuahua',
    'Siberian Husky', 'Maltese', 'Corgi', 'Maltipoo', 'Anjing Gembala Jerman', 'Kintamani / Anjing Bali',
    'Dachshund', 'Pitbull Terrier', 'Labrador Retriever', 'Beagle', 'Bulldog', 'Bichon Frise', 'Rottweiler',
    'French Bulldog', 'Samoyed', 'Chow Chow', 'Pug', 'Alaskan Malamute', 'Jack Russell Terrier', 'Shih-poo',
    'Yorkshire Terrier', 'Akita', 'Shiba Inu', 'Pekingese', 'Dobermann', 'Miniature Schnauzer', 'Spitz',
    'American Bully', 'Basenji', 'Basset Hound', 'Jindo', 'Miniature Pinscher', 'Border Collie', 'Boxer',
    'Thai Ridgeback', 'Great Dane', 'Campuran / mix',
  ])),
  SpeciesType(code: PetSpeciesCode.rabbit, emoji: '🐰', nameId: 'Kelinci', nameEn: 'Rabbit', breeds: _b(const [
    'Netherland Dwarf', 'Holland Lop', 'Lokal', 'Lionhead', 'New Zealand', 'Angora', 'Dutch', 'Rex',
    'Fuzzy Lop', 'Flemish Giant', 'Mini Rex', 'Bligon', 'English Spot', 'Californian', 'Mini Lop',
    'Florida White', 'Dwarf Hotot', 'Jersey Wooly', 'Campuran / mix',
  ])),
  SpeciesType(code: PetSpeciesCode.hamster, emoji: '🐹', nameId: 'Hamster', nameEn: 'Hamster', breeds: _b(const [
    'Syrian', 'Winter White', 'Campbell', 'Dwarf', 'Roborovski', 'Campuran / mix',
  ])),
  SpeciesType(code: PetSpeciesCode.bird, emoji: '🐦', nameId: 'Burung', nameEn: 'Bird', breeds: _b(const [
    'Pigeon / merpati', 'Lovebird', 'Songbird', 'Canary / kenari', 'Kutilang', 'Cockatiel', 'Budgie / parkit',
    'Jalak', 'Owl', 'Murai Batu', 'Shrike / cendet', 'Pleci', 'Conure', 'Parrot', 'Cucak Ranting', 'Perkutut',
    'Kacer / Magpie Robin',
  ])),
  SpeciesType(code: PetSpeciesCode.chicken, emoji: '🐔', nameId: 'Ayam', nameEn: 'Chicken', breeds: _b(const [
    'Bangkok', 'Kampung', 'Broiler', 'Pakhoy', 'Mangon', 'Kate', 'Shamo', 'Layer / petelur', 'Pelung',
    'Serama', 'Sebright', 'Ayam Hutan', 'Cemani', 'Ayam Jawa', 'Campuran / mix',
  ])),
  SpeciesType(code: PetSpeciesCode.fish, emoji: '🐟', nameId: 'Ikan', nameEn: 'Fish', breeds: _b(const [
    'Betta / cupang', 'Channa', 'Goldfish', 'Catfish / lele/patin', 'Tilapia / mujair', 'Louhan', 'Oscar',
    'Koi', 'Molly', 'Arowana / arwana', 'Guppy',
  ])),
  SpeciesType(code: PetSpeciesCode.lizard, emoji: '🦎', nameId: 'Kadal', nameEn: 'Lizard', breeds: _b(const [
    'Leopard Gecko', 'Iguana', 'Water Monitor / salvator', 'Calotes versicolor', 'House gecko / tokek',
  ])),
  SpeciesType(code: PetSpeciesCode.turtle, emoji: '🐢', nameId: 'Kura-kura', nameEn: 'Turtle', breeds: _b(const [
    'Sulcata', 'Red-eared Slider / Brazil', 'Amboina box turtle', 'African tortoise', "Hermann's tortoise",
  ])),
  SpeciesType(
      code: PetSpeciesCode.sugarGlider, emoji: '🐾', nameId: 'Sugar Glider', nameEn: 'Sugar Glider',
      breeds: _b(const ['Classic Grey', 'Mozaik', 'Platinum', 'Leucistic', 'White Face', 'Creamino'])),
  SpeciesType(code: PetSpeciesCode.snake, emoji: '🐍', nameId: 'Ular', nameEn: 'Snake', breeds: _b(const [
    'Ball Python', 'Reticulated Python', 'Corn Snake', 'Kingsnake', "Wagler's Pit Viper", 'Cobra',
  ])),
  SpeciesType(code: PetSpeciesCode.duck, emoji: '🦆', nameId: 'Bebek/Entok', nameEn: 'Duck', breeds: _b(const [
    'Muscovy / entok', 'Peking', 'Lokal', 'Mojosari', 'Call Duck',
  ])),
  SpeciesType(code: PetSpeciesCode.frog, emoji: '🐸', nameId: 'Katak', nameEn: 'Frog', breeds: _b(const [
    'Pacman Frog', 'American Bullfrog', "Budgett's Frog", 'Fire-bellied Toad', 'Poison Dart Frog', 'Tomato Frog',
    "White's Tree Frog",
  ])),
  SpeciesType(code: PetSpeciesCode.guineaPig, emoji: '🐾', nameId: 'Marmut', nameEn: 'Guinea Pig', breeds: _b(const [
    'American', 'Abyssinian', 'Peruvian', 'Teddy', 'Silkie / Sheltie',
  ])),
  const SpeciesType(code: PetSpeciesCode.other, emoji: '🐾', nameId: 'Lainnya', nameEn: 'Other'),
]);

/// 「其他」的 12 个物种分组（PRD 附录 B 顺序）。
final List<SpeciesGroup> kOtherSpeciesGroups = List.unmodifiable([
  SpeciesGroup(code: PetSpeciesCode.civet, titleId: 'Musang', titleEn: 'Civet',
      breeds: _b(const ['Musang Pandan', 'Musang Bulan', 'Musang Akar'])),
  SpeciesGroup(code: PetSpeciesCode.hedgehog, titleId: 'Landak Mini', titleEn: 'Mini hedgehog',
      breeds: _b(const ['African Pygmy / landak mini'])),
  SpeciesGroup(code: PetSpeciesCode.hermitCrab, titleId: 'Kelomang', titleEn: 'Hermit crab',
      breeds: _b(const ['Coenobita'])),
  SpeciesGroup(code: PetSpeciesCode.otter, titleId: 'Otter', titleEn: 'Otter',
      breeds: _b(const ['Small-clawed Otter'])),
  SpeciesGroup(code: PetSpeciesCode.sheep, titleId: 'Domba', titleEn: 'Sheep',
      breeds: _b(const ['Merino', 'Garut', 'Gibas'])),
  SpeciesGroup(code: PetSpeciesCode.cow, titleId: 'Sapi', titleEn: 'Cow', breeds: _b(const ['Simmental', 'Bali'])),
  SpeciesGroup(code: PetSpeciesCode.spider, titleId: 'Laba-laba/Tarantula', titleEn: 'Spider/Tarantula',
      breeds: _b(const ['Tarantula', 'Jumping Spider'])),
  SpeciesGroup(code: PetSpeciesCode.squirrel, titleId: 'Tupai', titleEn: 'Squirrel',
      breeds: _b(const ['Plantain squirrel / kelapa'])),
  SpeciesGroup(code: PetSpeciesCode.goat, titleId: 'Kambing', titleEn: 'Goat',
      breeds: _b(const ['Etawa', 'Boer', 'Kacang'])),
  SpeciesGroup(code: PetSpeciesCode.crab, titleId: 'Kepiting/Lobster', titleEn: 'Crab/Crayfish',
      breeds: _b(const ['Land crab', 'Crayfish'])),
  SpeciesGroup(code: PetSpeciesCode.insect, titleId: 'Serangga', titleEn: 'Insect',
      breeds: _b(const ['Ant', 'Jaliteng cricket / jangkrik'])),
  SpeciesGroup(code: PetSpeciesCode.mouse, titleId: 'Tikus & Dormouse', titleEn: 'Mouse & dormouse',
      breeds: _b(const ['African Pygmy Dormouse', 'Fancy Mouse', 'Fancy Rat'])),
]);

/// 宫格上对应的那一项（CIVET → 「其他」）。
SpeciesType typeOf(PetSpeciesCode code) {
  final picker = code.pickerType;
  return kSpeciesTypes.firstWhere((t) => t.code == picker);
}

/// 「其他」组物种所在的分组；其余 → null。
SpeciesGroup? groupOf(PetSpeciesCode code) {
  for (final g in kOtherSpeciesGroups) {
    if (g.code == code) return g;
  }
  return null;
}

/// 品种清单：独立类型 = 自己的清单；「其他」组物种 = 该组预设品种；OTHER = 空（分组由抽屉处理）。
List<BreedOption> breedsFor(PetSpeciesCode code) {
  if (code == PetSpeciesCode.other) return const [];
  final g = groupOf(code);
  if (g != null) return g.breeds;
  return typeOf(code).breeds;
}

/// 品种是否预设选项（逐字相等）；「其他」组还认组名本身（「Musang lainnya」存的就是「Musang」）。
/// OTHER 时在全部 12 组里找。不在清单里的 → 手填（Thai Bangkaew、Weimaraner 等旧值同样按手填显示）。
bool isPresetBreed(PetSpeciesCode code, String breed) {
  if (code == PetSpeciesCode.other) {
    return kOtherSpeciesGroups.any((g) => g.titleId == breed || g.breeds.any((b) => b.label == breed));
  }
  final g = groupOf(code);
  if (g != null && g.titleId == breed) return true;
  return breedsFor(code).any((b) => b.label == breed);
}

// ---------------------------------------------------------------------------------------------------------------
// 匹配（D-16）：去变音符号 + 小写 + 包含。自写映射表，不引第三方包。
// ---------------------------------------------------------------------------------------------------------------

const Map<String, String> _kFold = {
  'à': 'a', 'á': 'a', 'â': 'a', 'ã': 'a', 'ä': 'a', 'å': 'a', 'ā': 'a', 'ă': 'a', 'ą': 'a',
  'À': 'A', 'Á': 'A', 'Â': 'A', 'Ã': 'A', 'Ä': 'A', 'Å': 'A', 'Ā': 'A', 'Ă': 'A', 'Ą': 'A',
  'ç': 'c', 'ć': 'c', 'č': 'c', 'Ç': 'C', 'Ć': 'C', 'Č': 'C',
  'è': 'e', 'é': 'e', 'ê': 'e', 'ë': 'e', 'ē': 'e', 'ė': 'e', 'ę': 'e', 'ě': 'e',
  'È': 'E', 'É': 'E', 'Ê': 'E', 'Ë': 'E', 'Ē': 'E', 'Ė': 'E', 'Ę': 'E', 'Ě': 'E',
  'ì': 'i', 'í': 'i', 'î': 'i', 'ï': 'i', 'ī': 'i', 'į': 'i',
  'Ì': 'I', 'Í': 'I', 'Î': 'I', 'Ï': 'I', 'Ī': 'I', 'Į': 'I',
  'ñ': 'n', 'ń': 'n', 'ň': 'n', 'Ñ': 'N', 'Ń': 'N', 'Ň': 'N',
  'ò': 'o', 'ó': 'o', 'ô': 'o', 'õ': 'o', 'ö': 'o', 'ø': 'o', 'ō': 'o', 'ő': 'o',
  'Ò': 'O', 'Ó': 'O', 'Ô': 'O', 'Õ': 'O', 'Ö': 'O', 'Ø': 'O', 'Ō': 'O', 'Ő': 'O',
  'ù': 'u', 'ú': 'u', 'û': 'u', 'ü': 'u', 'ū': 'u', 'ů': 'u', 'ű': 'u', 'ų': 'u',
  'Ù': 'U', 'Ú': 'U', 'Û': 'U', 'Ü': 'U', 'Ū': 'U', 'Ů': 'U', 'Ű': 'U', 'Ų': 'U',
  'ý': 'y', 'ÿ': 'y', 'Ý': 'Y', 'Ÿ': 'Y',
  'ś': 's', 'š': 's', 'ş': 's', 'Ś': 'S', 'Š': 'S', 'Ş': 'S',
  'ź': 'z', 'ż': 'z', 'ž': 'z', 'Ź': 'Z', 'Ż': 'Z', 'Ž': 'Z',
  'ł': 'l', 'Ł': 'L', 'ğ': 'g', 'Ğ': 'G',
};

/// 去变音符号（逐字符一对一映射，长度不变，供高亮定位）。
String foldDiacritics(String s) {
  final out = StringBuffer();
  for (final ch in s.split('')) {
    out.write(_kFold[ch] ?? ch);
  }
  return out.toString();
}

/// 规范化：trim + 去变音 + 小写。
String normalizeQuery(String s) => foldDiacritics(s.trim()).toLowerCase();

/// 任一候选包含 query（规范化后）；空 query → true。
bool speciesQueryMatches(String query, Iterable<String> candidates) {
  final q = normalizeQuery(query);
  if (q.isEmpty) return true;
  return candidates.any((c) => normalizeQuery(c).contains(q));
}

/// 品种的匹配候选：全文 + 全部别名。
Iterable<String> breedCandidates(BreedOption b) => [b.label, ...b.aliases];

/// 命中段（半开区间 [start, end)，基于原文下标）。
class MatchRange {
  const MatchRange(this.start, this.end);

  final int start;
  final int end;

  @override
  bool operator ==(Object other) => other is MatchRange && other.start == start && other.end == end;

  @override
  int get hashCode => Object.hash(start, end);

  @override
  String toString() => 'MatchRange($start, $end)';
}

/// 原文中所有命中 query 的段（不重叠，从左到右）；空 query / 无命中 → 空。
List<MatchRange> highlightRanges(String text, String query) {
  final q = normalizeQuery(query);
  if (q.isEmpty) return const [];
  final hay = foldDiacritics(text).toLowerCase();
  if (hay.length != text.length) return const [];
  final out = <MatchRange>[];
  var from = 0;
  while (true) {
    final i = hay.indexOf(q, from);
    if (i < 0) break;
    out.add(MatchRange(i, i + q.length));
    from = i + q.length;
  }
  return out;
}

/// 手填品种落到哪个细分类型（D-16 · 待拍板 Q-9 默认）：当前类型在「其他」下时，手填文本与某组任一品种全文 / 别名 /
/// 组名 /「`<组名> lainnya`」规范化后**完全相等** → 该组物种；否则 OTHER。非「其他」类型 → 原值。
PetSpeciesCode resolveCustomBreed(PetSpeciesCode currentType, String text) {
  if (currentType.pickerType != PetSpeciesCode.other) return currentType;
  final t = normalizeQuery(text);
  if (t.isEmpty) return PetSpeciesCode.other;
  for (final g in kOtherSpeciesGroups) {
    final cands = <String>[
      g.titleId,
      '${g.titleId} lainnya',
      // 英语界面看到的是 titleEn 与「Other {group}」，手填同样认。
      g.titleEn,
      'Other ${g.titleEn}',
      for (final b in g.breeds) ...breedCandidates(b),
    ];
    if (cands.any((c) => normalizeQuery(c) == t)) return g.code;
  }
  return PetSpeciesCode.other;
}
