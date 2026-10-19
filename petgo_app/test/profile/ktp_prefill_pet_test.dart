import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/profile/domain/ktp_prefill_pet.dart';

/// L0 纯函数（V1.4.0 multi-pet Story 4.2 · AC1 · decision-log I2）：KTP 建卡默认预填哪只。
void main() {
  // 列表按 created_at ASC, id ASC（后端口径）；isDefault = 最早的 ACTIVE（DefaultPetPolicy）。
  const a = PetListItem(id: 10, name: 'A', isDefault: true);
  const b = PetListItem(id: 11, name: 'B');
  const ro = PetListItem(id: 9, name: 'RO', status: PetListItem.statusReadOnly);

  test('当前宠物 ACTIVE → 它', () {
    expect(resolveKtpPrefillPetId(currentPetId: 11, pets: const [ro, a, b]), 11);
  });

  test('当前宠物只读 → 最早创建的 ACTIVE（跳过排在前面的只读）', () {
    expect(resolveKtpPrefillPetId(currentPetId: 9, pets: const [ro, a, b]), 10);
  });

  test('当前宠物 id 不在列表（已删 / 换号）→ 最早 ACTIVE', () {
    expect(resolveKtpPrefillPetId(currentPetId: 99, pets: const [ro, a, b]), 10);
  });

  test('没选过当前宠物（null）→ Diary 回落的默认宠物 = 最早 ACTIVE', () {
    expect(resolveKtpPrefillPetId(currentPetId: null, pets: const [ro, a, b]), 10);
  });

  test('空列表 → null', () {
    expect(resolveKtpPrefillPetId(currentPetId: 11, pets: const []), isNull);
  });

  test('列表无 ACTIVE（数据异常）→ null', () {
    expect(resolveKtpPrefillPetId(currentPetId: 9, pets: const [ro]), isNull);
  });
}
