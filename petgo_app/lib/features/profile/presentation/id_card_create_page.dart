import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/date_format.dart';
import '../data/id_card_repository.dart';
import '../data/profile_repository.dart';
import '../domain/id_card.dart';
import 'widgets/pet_form_fields.dart';

/// 独立建卡器（Story 6-7，决策④）：把当前填写的信息冻结成一张新卡快照，与档案解耦。
/// 默认从当前档案预填但全部可改；提交建卡 → 进该卡详情。
class IdCardCreatePage extends ConsumerStatefulWidget {
  const IdCardCreatePage({super.key});

  @override
  ConsumerState<IdCardCreatePage> createState() => _IdCardCreatePageState();
}

class _IdCardCreatePageState extends ConsumerState<IdCardCreatePage> {
  final _name = TextEditingController();
  final _breed = TextEditingController();
  final _intro = TextEditingController();
  String? _petType;
  DateTime? _birthday;
  String? _avatarUrl;
  String _cardType = 'KTP'; // 卡种（Story 6-8）：KTP/PASSPORT/STUDENT。
  bool _prefilled = false;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    // 默认预填当前档案值（可改）。拉取失败则留空白，用户从零填。
    ref.read(petProfileProvider.future).then((p) {
      if (!mounted || _prefilled || p == null) return;
      setState(() {
        _name.text = p.name;
        _petType = p.petType;
        _breed.text = p.breed ?? '';
        _birthday = p.birthday;
        _intro.text = p.intro ?? '';
        _avatarUrl = p.avatarUrl;
        _prefilled = true;
      });
    }).catchError((_) {});
  }

  @override
  void dispose() {
    _name.dispose();
    _breed.dispose();
    _intro.dispose();
    super.dispose();
  }

  bool get _canSubmit => _name.text.trim().isNotEmpty && !_submitting;

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    FocusScope.of(context).unfocus();
    setState(() => _submitting = true);
    try {
      final card = await ref.read(idCardRepositoryProvider).createCard(CreateIdCardRequest(
            name: _name.text.trim(),
            cardType: _cardType,
            petType: _petType,
            breed: _breed.text.trim().isEmpty ? null : _breed.text.trim(),
            birthday: _birthday,
            avatarUrl: _avatarUrl,
            intro: _intro.text.trim().isEmpty ? null : _intro.text.trim(),
          ));
      if (!mounted) return;
      ref.invalidate(idCardListProvider);
      context.pushReplacement('/profile/id-cards/${card.id}');
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(l10n.idCardCreateError)));
        setState(() => _submitting = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.idCardCreateTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
          children: [
            Text(l10n.idCardCreateHint,
                style: const TextStyle(fontSize: 13, color: AppColors.ink2, height: 1.5)),
            const SizedBox(height: 20),
            // 卡种选择（Story 6-8）：KTP / Passport / Student。
            _label(l10n.idCardTypeLabel),
            const SizedBox(height: 6),
            SizedBox(
              width: double.infinity,
              child: SegmentedButton<String>(
                key: const ValueKey('idCardTypeSelector'),
                segments: <ButtonSegment<String>>[
                  ButtonSegment(value: 'KTP', label: Text(l10n.idCardTypeKtp)),
                  ButtonSegment(value: 'PASSPORT', label: Text(l10n.idCardTypePassport)),
                  ButtonSegment(value: 'STUDENT', label: Text(l10n.idCardTypeStudent)),
                ],
                selected: <String>{_cardType},
                showSelectedIcon: false,
                onSelectionChanged: (s) => setState(() => _cardType = s.first),
              ),
            ),
            const SizedBox(height: 16),
            _label(l10n.petProfileNameLabel),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('idCardCreateName'),
              controller: _name,
              maxLength: 60,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                hintText: l10n.petProfileNameHint,
                border: const OutlineInputBorder(),
                counterText: '',
              ),
            ),
            const SizedBox(height: 16),
            _label(l10n.petTypeLabel),
            const SizedBox(height: 6),
            SpeciesField(
              petType: _petType,
              onChanged: (t) => setState(() {
                _petType = t;
                _breed.clear();
              }),
            ),
            const SizedBox(height: 16),
            _label(l10n.petProfileBreedLabel),
            const SizedBox(height: 6),
            BreedField(petType: _petType, controller: _breed, onChanged: () => setState(() {})),
            const SizedBox(height: 16),
            _label(l10n.petProfileBirthdayLabel),
            const SizedBox(height: 6),
            InkWell(
              key: const ValueKey('idCardCreateBirthday'),
              onTap: _pickBirthday,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
                decoration: BoxDecoration(
                  border: Border.all(color: AppColors.line),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.cake_outlined, size: 18, color: AppColors.muted),
                    const SizedBox(width: 8),
                    Text(
                      _birthday == null
                          ? l10n.petProfileBirthdayPick
                          : formatBirthday(context, _birthday!),
                      style: TextStyle(color: _birthday == null ? AppColors.muted : AppColors.ink),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            _label(l10n.petProfileBioLabel),
            const SizedBox(height: 6),
            TextField(
              key: const ValueKey('idCardCreateIntro'),
              controller: _intro,
              maxLength: 30,
              decoration: InputDecoration(
                hintText: l10n.petProfileIntro,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 24),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('idCardCreateSubmit'),
                style: FilledButton.styleFrom(
                    backgroundColor: AppColors.mint, padding: const EdgeInsets.symmetric(vertical: 14)),
                onPressed: _canSubmit ? _submit : null,
                child: _submitting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                    : Text(l10n.idCardCreateSubmit,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickBirthday() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthday ?? now,
      firstDate: DateTime(now.year - 40),
      lastDate: now,
    );
    if (picked != null) setState(() => _birthday = picked);
  }

  Widget _label(String text) => Text(text,
      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink2));
}
