import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/media_permission.dart';
import '../data/profile_repository.dart';
import '../domain/pet_profile.dart';

/// 宠物档案编辑页（Story 2.8）。复用 2.2 创建表单结构，预填既有值；部分更新 PATCH。
/// 两入口（档案 Tab 信息卡「编辑」/「我的」Tab）经 go_router 复用本页（/profile/edit）。
class PetProfileEditPage extends ConsumerStatefulWidget {
  const PetProfileEditPage({super.key});

  @override
  ConsumerState<PetProfileEditPage> createState() => _PetProfileEditPageState();
}

class _PetProfileEditPageState extends ConsumerState<PetProfileEditPage> {
  final _nameController = TextEditingController();
  final _breedController = TextEditingController();
  final _introController = TextEditingController();
  DateTime? _birthday;
  String? _avatarUrl;
  String? _petType; // F6：创建后不可改，编辑页置灰只读展示，不随 PATCH 提交
  bool _uploading = false;
  bool _submitting = false;
  bool _prefilled = false;

  @override
  void dispose() {
    _nameController.dispose();
    _breedController.dispose();
    _introController.dispose();
    super.dispose();
  }

  void _prefill(PetProfile p) {
    if (_prefilled) return;
    _prefilled = true;
    _nameController.text = p.name;
    _breedController.text = p.breed ?? '';
    _introController.text = p.intro ?? '';
    _birthday = p.birthday;
    _avatarUrl = p.avatarUrl;
    _petType = p.petType;
  }

  bool get _canSubmit => _nameController.text.trim().isNotEmpty && !_submitting && !_uploading;

  Future<void> _pickAvatar() async {
    setState(() => _uploading = true);
    try {
      final result = await ref.read(mediaUploadUseCaseProvider).pickAndUploadOne(
            scope: MediaScope.public,
            source: MediaSource.gallery,
            context: context,
          );
      if (result?.publicUrl != null) setState(() => _avatarUrl = result!.publicUrl);
    } catch (_) {
      if (mounted) _toast(AppLocalizations.of(context).mediaUploadFailed);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _submitting = true);
    try {
      await ref.read(profileRepositoryProvider).update(
            name: _nameController.text.trim(),
            avatarUrl: _avatarUrl,
            breed: _emptyToNull(_breedController.text),
            birthday: _birthday,
            intro: _emptyToNull(_introController.text),
          );
      ref.invalidate(petProfileProvider);
      if (mounted) context.go('/profile');
    } catch (_) {
      if (mounted) _toast(l10n.petProfileSaveFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 3)));
  }

  static String? _emptyToNull(String s) => s.trim().isEmpty ? null : s.trim();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final profileAsync = ref.watch(petProfileProvider);

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.petProfileEditTitle), backgroundColor: AppColors.base),
      body: profileAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (err, stack) => Center(child: Text(l10n.petProfileSaveFailed)),
        data: (profile) {
          if (profile == null) {
            // 无档案不可编辑 → 回档案 Tab（空态会引导创建）。
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted) context.go('/profile');
            });
            return const SizedBox.shrink();
          }
          _prefill(profile);
          return _form(l10n);
        },
      ),
    );
  }

  Widget _form(AppLocalizations l10n) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(AppSpacing.xl),
        children: [
          Center(
            child: GestureDetector(
              key: const ValueKey('petProfileEditAvatar'),
              onTap: _uploading ? null : _pickAvatar,
              child: CircleAvatar(
                radius: 44,
                backgroundColor: AppColors.surface,
                backgroundImage: _avatarUrl == null ? null : NetworkImage(_avatarUrl!),
                child: _uploading
                    ? const CircularProgressIndicator()
                    : (_avatarUrl == null
                        ? const Icon(Icons.add_a_photo_outlined, color: AppColors.textTertiary)
                        : null),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          // 宠物类型（F6）：创建后不可改 → 置灰只读展示，不随 PATCH 提交。
          _petTypeReadonly(l10n),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const ValueKey('petProfileEditNameField'),
            controller: _nameController,
            maxLength: 20,
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(labelText: l10n.petProfileName),
          ),
          TextField(
            key: const ValueKey('petProfileEditBreedField'),
            controller: _breedController,
            maxLength: 60,
            decoration: InputDecoration(labelText: l10n.petProfileBreed),
          ),
          ListTile(
            key: const ValueKey('petProfileEditBirthdayTile'),
            contentPadding: EdgeInsets.zero,
            title: Text(l10n.petProfileBirthday),
            subtitle: Text(_birthday == null
                ? l10n.petProfileBirthdayPick
                : '${_birthday!.year}-${_birthday!.month}-${_birthday!.day}'),
            trailing: const Icon(Icons.calendar_today_outlined),
            onTap: _pickBirthday,
          ),
          TextField(
            key: const ValueKey('petProfileEditIntroField'),
            controller: _introController,
            maxLength: 30,
            decoration: InputDecoration(labelText: l10n.petProfileIntro),
          ),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(
            key: const ValueKey('petProfileEditSubmit'),
            onPressed: _canSubmit ? _submit : null,
            child: _submitting
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(l10n.commonSave),
          ),
        ],
      ),
    );
  }

  /// 宠物类型只读区（F6）：展示既有类型，三枚 chip 全置灰不可点（onSelected null），不参与提交。
  Widget _petTypeReadonly(AppLocalizations l10n) {
    final labels = {'CAT': l10n.petTypeCat, 'DOG': l10n.petTypeDog, 'OTHER': l10n.petTypeOther};
    return Column(
      key: const ValueKey('petProfileEditTypeReadonly'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(l10n.petTypeLabel, style: const TextStyle(color: AppColors.textTertiary, fontSize: 12)),
            const SizedBox(width: 6),
            const Icon(Icons.lock_outline, size: 13, color: AppColors.textTertiary),
            const SizedBox(width: 4),
            Text(l10n.petTypeLockedHint,
                style: const TextStyle(color: AppColors.textTertiary, fontSize: 12)),
          ],
        ),
        const SizedBox(height: AppSpacing.sm),
        Wrap(
          spacing: AppSpacing.sm,
          children: [
            for (final e in labels.entries)
              ChoiceChip(
                key: ValueKey('petTypeReadonly_${e.key}'),
                label: Text(e.value),
                selected: _petType == e.key,
                onSelected: null, // 置灰只读：不可改
              ),
          ],
        ),
      ],
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
}
