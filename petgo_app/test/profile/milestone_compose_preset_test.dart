import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/celebration_pet.dart';
import 'package:tailtopia/features/profile/domain/milestone.dart';
import 'package:tailtopia/features/profile/domain/milestone_compose_preset.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.2 social-topic Story 3.1 · AC4（PRD §3.6 交互 / §7）：里程碑来源发布页预填正文与预选宠物。
void main() {
  MilestoneItem ms(String code) => MilestoneItem(
      code: code, title: code, level: MilestoneLevel.s, trigger: MilestoneTrigger.systemAuto, completed: true);

  const three = [
    CelebrationPet(id: 11, name: 'Orenji', petType: 'CAT'),
    CelebrationPet(id: 12, name: 'Kopi', petType: 'DOG'),
    CelebrationPet(id: 13, name: 'Boba', petType: 'OTHER'),
  ];

  for (final lang in ['id', 'en']) {
    final locale = Locale(lang);
    final isId = lang == 'id';

    test('[$lang] 单宠里程碑：预选这只 + 单宠句式；有账号宠物也不扩', () async {
      final l10n = await AppLocalizations.delegate.load(locale);
      final r = milestoneComposePreset(
          item: ms('C-S1'), petId: 7, petName: 'Momo', accountPets: three, l10n: l10n, locale: locale);
      expect(r.petIds, [7]);
      expect(
          r.text,
          isId
              ? 'Momo baru membuka tonggak "Profil dibuat"! 🎉'
              : 'Momo just unlocked the "Profile created" milestone! 🎉');
      expect(r.text.contains('#'), isFalse);
    });

    test('[$lang] 账号级 + 3 只 ACTIVE：预选全部 + Kami / We 句式', () async {
      final l10n = await AppLocalizations.delegate.load(locale);
      final r = milestoneComposePreset(
          item: ms('C-S19'), petId: 11, petName: 'Orenji', accountPets: three, l10n: l10n, locale: locale);
      expect(r.petIds, [11, 12, 13]);
      expect(r.text, startsWith(isId ? 'Kami baru membuka tonggak "' : 'We just unlocked the "'));
      expect(r.text, endsWith(isId ? '"! 🎉' : '" milestone! 🎉'));
      expect(r.text.contains('#'), isFalse);
    });

    test('[$lang] 账号级只 1 只 ACTIVE：单宠句式，用那只的名字', () async {
      final l10n = await AppLocalizations.delegate.load(locale);
      final r = milestoneComposePreset(
          item: ms('G-S13'),
          petId: 5,
          petName: 'Old',
          accountPets: const [CelebrationPet(id: 21, name: 'Kiki')],
          l10n: l10n,
          locale: locale);
      expect(r.petIds, [21]);
      expect(r.text, startsWith(isId ? 'Kiki baru membuka tonggak "' : 'Kiki just unlocked the "'));
      expect(r.text.contains('#'), isFalse);
    });
  }

  test('账号级但账号宠物读失败（空表）→ 退化为这只 + 单宠句式', () async {
    final l10n = await AppLocalizations.delegate.load(const Locale('en'));
    final r = milestoneComposePreset(
        item: ms('D-S20'), petId: 3, petName: 'Bobby', accountPets: const [], l10n: l10n, locale: const Locale('en'));
    expect(r.petIds, [3]);
    expect(r.text, startsWith('Bobby just unlocked the "'));
  });
}
