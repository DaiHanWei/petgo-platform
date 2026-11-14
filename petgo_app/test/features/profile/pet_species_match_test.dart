import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_species.dart';

/// L0：V1.5.0 pet-species Story 3.1 · AC2（PRD 3.2 AC1）—— 去变音 + 小写 + 包含、别名、组标题、高亮、手填解析。
void main() {
  List<String> search(PetSpeciesCode code, String q) =>
      breedsFor(code).where((b) => speciesQueryMatches(q, breedCandidates(b))).map((b) => b.label).toList();

  test('狗搜 poo / pudel / POODLE', () {
    expect(search(PetSpeciesCode.dog, 'poo'), containsAll(['Pudel / Poodle', 'Maltipoo', 'Shih-poo']));
    expect(search(PetSpeciesCode.dog, 'pudel'), contains('Pudel / Poodle'));
    expect(search(PetSpeciesCode.dog, 'POODLE'), contains('Pudel / Poodle'));
  });

  test('鱼搜 cupang 命中别名；空 query 全命中', () {
    expect(search(PetSpeciesCode.fish, 'cupang'), ['Betta / cupang']);
    expect(search(PetSpeciesCode.fish, '  '), hasLength(11));
  });

  test('「其他」搜 musang 命中整组标题', () {
    final hits = kOtherSpeciesGroups
        .where((g) => speciesQueryMatches('musang', [g.titleId, g.titleEn]))
        .map((g) => g.code)
        .toList();
    expect(hits, [PetSpeciesCode.civet]);
  });

  test('变音符号等价', () {
    expect(foldDiacritics('Café Élan ñ ł ğ'), 'Cafe Elan n l g');
    expect(speciesQueryMatches('é', ['Cafe']), isTrue);
    expect(speciesQueryMatches('e', ['Café']), isTrue);
    expect(normalizeQuery('  PÜDEL '), 'pudel');
  });

  test('高亮段基于原文下标', () {
    expect(highlightRanges('Pudel / Poodle', 'poo'), [const MatchRange(8, 11)]);
    expect(highlightRanges('Café', 'fe'), [const MatchRange(2, 4)]);
    expect(highlightRanges('abcabc', 'bc'), [const MatchRange(1, 3), const MatchRange(4, 6)]);
    expect(highlightRanges('x', ''), isEmpty);
  });

  test('手填解析（Q-9）', () {
    expect(resolveCustomBreed(PetSpeciesCode.other, 'Musang Pandan'), PetSpeciesCode.civet);
    expect(resolveCustomBreed(PetSpeciesCode.other, ' musang lainnya '), PetSpeciesCode.civet);
    expect(resolveCustomBreed(PetSpeciesCode.other, 'kelapa'), PetSpeciesCode.squirrel); // 别名
    expect(resolveCustomBreed(PetSpeciesCode.other, 'Other Civet'), PetSpeciesCode.civet); // 英语界面
    expect(resolveCustomBreed(PetSpeciesCode.other, 'civet'), PetSpeciesCode.civet);
    expect(resolveCustomBreed(PetSpeciesCode.other, 'kelinci lokal'), PetSpeciesCode.other);
    expect(resolveCustomBreed(PetSpeciesCode.civet, 'Otter'), PetSpeciesCode.otter);
    expect(resolveCustomBreed(PetSpeciesCode.dog, 'Musang Pandan'), PetSpeciesCode.dog); // 非「其他」原样
  });
}
