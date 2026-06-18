import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
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
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/profile'),
        ),
        title: const Text('Ubah Profil Hewan',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
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
        padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
        children: [
          // 实心圆头像 + 紫相机角标（编辑态有图；点击换图）。
          Center(
            child: GestureDetector(
              key: const ValueKey('petProfileEditAvatar'),
              onTap: _uploading ? null : _pickAvatar,
              child: SizedBox(
                width: 96,
                height: 96,
                child: Stack(
                  children: [
                    Center(
                      child: CircleAvatar(
                        radius: 44,
                        backgroundColor: AppColors.cream2,
                        backgroundImage: AppImage.provider(_avatarUrl),
                        child: _uploading
                            ? const CircularProgressIndicator()
                            : (_avatarUrl == null
                                ? const Text('🐱', style: TextStyle(fontSize: 38))
                                : null),
                      ),
                    ),
                    Positioned(
                      right: 4,
                      bottom: 4,
                      child: Container(
                        width: 30,
                        height: 30,
                        decoration: const BoxDecoration(
                            shape: BoxShape.circle, color: AppColors.mint),
                        child: const Icon(Icons.photo_camera_rounded,
                            size: 16, color: AppColors.onAccent),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          _sectionLabel('NAMA HEWAN', required: true),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditNameField'),
            controller: _nameController,
            maxLength: 20,
            onChanged: (_) => setState(() {}),
            decoration: _inputDeco(),
          ),
          const SizedBox(height: 16),
          // 宠物类型（F6）：创建后不可改 → 置灰只读展示，不随 PATCH 提交。
          _petTypeReadonly(l10n),
          const SizedBox(height: 16),
          _sectionLabel('RAS'),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditBreedField'),
            controller: _breedController,
            maxLength: 60,
            decoration: _inputDeco(),
          ),
          const SizedBox(height: 16),
          _sectionLabel('TANGGAL LAHIR'),
          const SizedBox(height: 6),
          InkWell(
            key: const ValueKey('petProfileEditBirthdayTile'),
            onTap: _pickBirthday,
            borderRadius: BorderRadius.circular(12),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 15),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.line, width: 1.5),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      _birthday == null ? l10n.petProfileBirthdayPick : _formatBirthday(_birthday!),
                      style: TextStyle(
                          fontSize: 15,
                          color: _birthday == null ? AppColors.muted : AppColors.ink),
                    ),
                  ),
                  const Icon(Icons.calendar_today_outlined, size: 18, color: AppColors.muted),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          _sectionLabel('BIO (OPSIONAL)'),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditIntroField'),
            controller: _introController,
            maxLength: 30,
            maxLines: 3,
            decoration: _inputDeco(),
          ),
          const SizedBox(height: AppSpacing.lg),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              key: const ValueKey('petProfileEditSubmit'),
              onPressed: _canSubmit ? _submit : null,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                disabledBackgroundColor: AppColors.line,
                padding: const EdgeInsets.symmetric(vertical: 15),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: _submitting
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(l10n.commonSave,
                      style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _sectionLabel(String text, {bool required = false}) => RichText(
        text: TextSpan(
          style: const TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.5,
              color: AppColors.ink2),
          children: [
            TextSpan(text: text),
            if (required) const TextSpan(text: ' *', style: TextStyle(color: AppColors.popRed)),
          ],
        ),
      );

  InputDecoration _inputDeco() => InputDecoration(
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        counterText: '',
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.line, width: 1.5),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
        ),
      );

  static String _formatBirthday(DateTime d) {
    const months = [
      '', 'Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni',
      'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember',
    ];
    return '${d.day} ${months[d.month]} ${d.year}';
  }

  /// 宠物类型只读区（F6）：展示既有类型，三枚 emoji chip 全置灰不可点（onSelected null），不参与提交。
  Widget _petTypeReadonly(AppLocalizations l10n) {
    final labels = {
      'CAT': '🐱 ${l10n.petTypeCat}',
      'DOG': '🐶 ${l10n.petTypeDog}',
      'OTHER': '🐾 ${l10n.petTypeOther}',
    };
    return Column(
      key: const ValueKey('petProfileEditTypeReadonly'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _sectionLabel('JENIS HEWAN'),
            const SizedBox(width: 6),
            const Icon(Icons.lock_outline, size: 12, color: AppColors.muted),
            const SizedBox(width: 3),
            Flexible(
              child: Text(l10n.petTypeLockedHint,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(color: AppColors.muted, fontSize: 10)),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: AppSpacing.sm,
          children: [
            for (final e in labels.entries)
              ChoiceChip(
                key: ValueKey('petTypeReadonly_${e.key}'),
                label: Text(e.value),
                selected: _petType == e.key,
                showCheckmark: false,
                labelStyle: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: _petType == e.key ? AppColors.mint700 : AppColors.muted),
                selectedColor: AppColors.cream2,
                backgroundColor: AppColors.card,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(10),
                  side: BorderSide(
                      color: _petType == e.key ? AppColors.dashedViolet : AppColors.line,
                      width: 1.5),
                ),
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
