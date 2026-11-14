import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_breeds.dart';

/// L0：V1.5.0 pet-species Story 3.1 · AC1 / AC7.3 —— 清单、条数、辅助函数、旧入口兼容。
void main() {
  test('27 个 code，wire 唯一，pickerType / groupWire / isOtherGroupMember', () {
    expect(PetSpeciesCode.values, hasLength(27));
    expect(PetSpeciesCode.values.map((e) => e.wire).toSet(), hasLength(27));
    expect(PetSpeciesCode.fromWire('RABBIT'), PetSpeciesCode.rabbit);
    expect(PetSpeciesCode.fromWire('rabbit'), isNull); // 区分大小写
    expect(PetSpeciesCode.fromWire(null), isNull);
    expect(PetSpeciesCode.civet.pickerType, PetSpeciesCode.other);
    expect(PetSpeciesCode.mouse.pickerType, PetSpeciesCode.other);
    expect(PetSpeciesCode.guineaPig.pickerType, PetSpeciesCode.guineaPig);
    expect(PetSpeciesCode.cat.groupWire, 'CAT');
    expect(PetSpeciesCode.rabbit.groupWire, 'OTHER');
    expect(PetSpeciesCode.civet.isOtherGroupMember, isTrue);
    expect(PetSpeciesCode.other.isOtherGroupMember, isFalse);
  });

  test('宫格 15 项，顺序固定，OTHER 最后', () {
    expect(kSpeciesTypes.map((t) => t.code.wire).toList(), [
      'CAT', 'DOG', 'RABBIT', 'HAMSTER', 'BIRD', 'CHICKEN', 'FISH', 'LIZARD', 'TURTLE', 'SUGAR_GLIDER', 'SNAKE',
      'DUCK', 'FROG', 'GUINEA_PIG', 'OTHER',
    ]);
    expect(kSpeciesTypes.map((t) => t.emoji).toList(),
        ['🐱', '🐶', '🐰', '🐹', '🐦', '🐔', '🐟', '🦎', '🐢', '🐾', '🐍', '🦆', '🐸', '🐾', '🐾']);
  });

  test('品种条数照附录 A / B', () {
    final counts = {
      for (final t in kSpeciesTypes) t.code: t.breeds.length,
    };
    expect(counts, {
      PetSpeciesCode.cat: 39, PetSpeciesCode.dog: 43, PetSpeciesCode.rabbit: 19, PetSpeciesCode.hamster: 6,
      PetSpeciesCode.bird: 17, PetSpeciesCode.chicken: 15, PetSpeciesCode.fish: 11, PetSpeciesCode.lizard: 5,
      PetSpeciesCode.turtle: 5, PetSpeciesCode.sugarGlider: 6, PetSpeciesCode.snake: 6, PetSpeciesCode.duck: 5,
      PetSpeciesCode.frog: 7, PetSpeciesCode.guineaPig: 5, PetSpeciesCode.other: 0,
    });
    expect(kOtherSpeciesGroups.map((g) => g.code.wire).toList(), [
      'CIVET', 'HEDGEHOG', 'HERMIT_CRAB', 'OTTER', 'SHEEP', 'COW', 'SPIDER', 'SQUIRREL', 'GOAT', 'CRAB', 'INSECT',
      'MOUSE',
    ]);
    expect(kOtherSpeciesGroups.map((g) => g.breeds.length).toList(), [3, 1, 1, 1, 3, 2, 2, 1, 3, 2, 2, 3]);
    expect(kCatBreeds.last, 'Campuran / mix');
    expect(kDogBreeds, contains('Pudel / Poodle'));
    expect(kDogBreeds, isNot(contains('Weimaraner')));
    expect(kDogBreeds, isNot(contains('Thai Bangkaew')));
  });

  test('别名按 / 切开', () {
    final catfish = breedsFor(PetSpeciesCode.fish).firstWhere((b) => b.label.startsWith('Catfish'));
    expect(catfish.aliases, ['Catfish', 'lele', 'patin']);
    expect(const BreedOption('Pudel / Poodle').aliases, ['Pudel', 'Poodle']);
  });

  test('typeOf / groupOf / breedsFor / isPresetBreed', () {
    expect(typeOf(PetSpeciesCode.civet).code, PetSpeciesCode.other);
    expect(groupOf(PetSpeciesCode.civet)!.titleId, 'Musang');
    expect(groupOf(PetSpeciesCode.rabbit), isNull);
    expect(breedsFor(PetSpeciesCode.other), isEmpty);
    expect(breedsFor(PetSpeciesCode.civet).map((b) => b.label), ['Musang Pandan', 'Musang Bulan', 'Musang Akar']);
    expect(isPresetBreed(PetSpeciesCode.dog, 'Pudel / Poodle'), isTrue);
    expect(isPresetBreed(PetSpeciesCode.dog, 'Weimaraner'), isFalse); // 旧值按手填
    expect(isPresetBreed(PetSpeciesCode.civet, 'Musang'), isTrue); // 「Musang lainnya」存的组名
    expect(isPresetBreed(PetSpeciesCode.other, 'Musang Pandan'), isTrue);
    expect(isPresetBreed(PetSpeciesCode.other, 'Kelinci lokal'), isFalse);
  });

  test('旧入口 breedsForPetType 兼容', () {
    expect(breedsForPetType('CAT'), hasLength(39));
    expect(breedsForPetType('DOG'), hasLength(43));
    expect(breedsForPetType('OTHER'), isEmpty);
    expect(breedsForPetType(null), isEmpty);
    expect(breedsForPetType('RABBIT'), hasLength(19));
  });
}
