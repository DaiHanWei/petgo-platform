import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
import '../data/profile_repository.dart';
import '../domain/pet_profile.dart';
import 'widgets/pet_form_fields.dart';

/// 宠物档案编辑页（Story 2.8）。复用 2.2 创建表单结构，预填既有值；部分更新 PATCH。
/// 两入口（档案 Tab 信息卡「编辑」/「我的」Tab）经 go_router 复用本页（/profile/edit）。
class PetProfileEditPage extends ConsumerStatefulWidget {
  const PetProfileEditPage({super.key});

  @override
  ConsumerState<PetProfileEditPage> createState() => _PetProfileEditPageState();
}

class _PetProfileEditPageState extends ConsumerState<PetProfileEditPage> {
  /// BIO 字数上限（原型 pet-edit：「42 / 200」）。
  static const int _kBioMax = 200;

  final _nameController = TextEditingController();
  final _breedController = TextEditingController();
  final _introController = TextEditingController();
  DateTime? _birthday;
  String? _avatarUrl;
  String? _petType; // F6：创建后不可改，编辑页置灰只读展示，不随 PATCH 提交
  // KELAMIN（性别）：MALE/FEMALE。⚠️ 仅前端占位——后端 PetProfile 暂无 sex 字段，不随 PATCH 提交、不持久化。
  String? _sex;
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
    // 提前预填（在 AppBar 求值 _canSubmit 之前），否则右上「Simpan」首帧因 name 空而禁用。
    final loaded = profileAsync.asData?.value;
    if (loaded != null) _prefill(loaded);
    final loadedName = loaded?.name;

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/profile'),
        ),
        // 标题随宠物名（原型 P-32「Edit Profil {名}」）；加载中回退通用标题。
        title: Text(
          (loadedName != null && loadedName.isNotEmpty)
              ? l10n.petProfileEditTitleNamed(loadedName)
              : l10n.petProfileEditTitle,
          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink),
        ),
        // 保存按钮在右上角（原型 P-32，区别于创建页底部按钮）。
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: FilledButton(
              key: const ValueKey('petProfileEditSubmit'),
              onPressed: _canSubmit ? _submit : null,
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                foregroundColor: AppColors.onAccent,
                disabledBackgroundColor: AppColors.line,
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(9)),
              ),
              child: _submitting
                  ? const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : Text(l10n.commonSave,
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
            ),
          ),
        ],
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
          return _form(context, l10n);
        },
      ),
    );
  }

  Widget _form(BuildContext context, AppLocalizations l10n) {
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
                        backgroundImage: AppImage.provider(_avatarUrl, thumbWidth: 240),
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
          _sectionLabel(l10n.petProfileNameLabel, required: true),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditNameField'),
            controller: _nameController,
            maxLength: 20,
            onChanged: (_) => setState(() {}),
            decoration: _inputDeco(),
          ),
          const SizedBox(height: 16),
          // JENIS HEWAN（F6 编辑态锁定）：标签左 + 红「锁 + tidak bisa diubah」右 + 灰底锁定下拉。
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              _sectionLabel(l10n.petProfileTypeLabel),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.lock_outline, size: 11, color: AppColors.popRed),
                  const SizedBox(width: 4),
                  Text(l10n.petTypeLockedHint,
                      style: const TextStyle(
                          color: AppColors.popRed, fontSize: 10, fontWeight: FontWeight.w600)),
                ],
              ),
            ],
          ),
          const SizedBox(height: 6),
          SpeciesField(
              key: const ValueKey('petProfileEditTypeReadonly'), petType: _petType, locked: true),
          const SizedBox(height: 16),
          // RAS（品种）+ KELAMIN（性别）两列（原型 pet-edit）。
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _sectionLabel(l10n.petProfileBreedLabel),
                    const SizedBox(height: 6),
                    BreedField(
                      petType: _petType,
                      controller: _breedController,
                      onChanged: () => setState(() {}),
                      fieldKey: const ValueKey('petProfileEditBreedField'),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _sectionLabel(l10n.petProfileSexLabel),
                    const SizedBox(height: 6),
                    _sexField(l10n),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          _sectionLabel(l10n.petProfileBirthdayLabel),
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
                      _birthday == null ? l10n.petProfileBirthdayPick : formatBirthday(context, _birthday!),
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
          _sectionLabel(l10n.petProfileBioLabel),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditIntroField'),
            controller: _introController,
            maxLength: _kBioMax,
            maxLines: 3,
            onChanged: (_) => setState(() {}),
            decoration: _inputDeco(),
          ),
          // BIO 字数计数（原型 pet-edit：右下「x / 200」）。
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: Text('${_introController.text.characters.length} / $_kBioMax',
                style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
          ),
          const SizedBox(height: AppSpacing.sm),
          // 保存按钮在右上角 AppBar（原型 P-32），此处不再放底部按钮。
          // 危险区：删除档案（原型 pet-edit）。⚠️ 仅前端 UI + 二次确认；DELETE 端点待后端（D1/D2 级联/匿名化）。
          const Divider(color: Color(0xFFF3F3F3), thickness: 1, height: 32),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              key: const ValueKey('petProfileDeleteButton'),
              onPressed: _submitting ? null : () => _confirmDelete(l10n),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.popRed,
                side: const BorderSide(color: Color(0xFFFDE7EB), width: 1.5),
                padding: const EdgeInsets.symmetric(vertical: 13),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              ),
              child: Text('🗑 ${l10n.petProfileDeleteButton(_nameController.text.trim())}',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
            ),
          ),
          const SizedBox(height: 8),
          Center(
            child: Text(l10n.petProfileDeleteIrreversible,
                style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
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

  /// KELAMIN 选择字段（原型 pet-edit：边框 + 下拉箭头）。⚠️ 占位：选了不持久化。
  Widget _sexField(AppLocalizations l10n) {
    final label = switch (_sex) {
      'MALE' => l10n.petProfileSexMale,
      'FEMALE' => l10n.petProfileSexFemale,
      _ => l10n.petProfileSexPick,
    };
    return InkWell(
      key: const ValueKey('petProfileEditSexTile'),
      onTap: _pickSex,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.line, width: 1.5),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 14, color: _sex == null ? AppColors.muted : AppColors.ink)),
            ),
            const Icon(Icons.keyboard_arrow_down, size: 18, color: AppColors.muted),
          ],
        ),
      ),
    );
  }

  Future<void> _pickSex() async {
    final l10n = AppLocalizations.of(context);
    final picked = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              key: const ValueKey('petSexMaleOption'),
              title: Text(l10n.petProfileSexMale),
              onTap: () => Navigator.of(ctx).pop('MALE'),
            ),
            ListTile(
              key: const ValueKey('petSexFemaleOption'),
              title: Text(l10n.petProfileSexFemale),
              onTap: () => Navigator.of(ctx).pop('FEMALE'),
            ),
          ],
        ),
      ),
    );
    if (picked != null) setState(() => _sex = picked);
  }

  /// 删除档案二次确认（原型 pet-edit）。⚠️ 端点待后端：当前确认后仅占位提示，不真正删除。
  Future<void> _confirmDelete(AppLocalizations l10n) async {
    final name = _nameController.text.trim();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.petProfileDeleteConfirmTitle),
        content: Text(l10n.petProfileDeleteConfirmBody(name)),
        actions: [
          TextButton(
            key: const ValueKey('petProfileDeleteCancel'),
            onPressed: () => Navigator.of(ctx).pop(false),
            child: Text(l10n.commonCancel),
          ),
          FilledButton(
            key: const ValueKey('petProfileDeleteConfirmYes'),
            onPressed: () => Navigator.of(ctx).pop(true),
            style: FilledButton.styleFrom(backgroundColor: AppColors.popRed),
            child: Text(l10n.petProfileDeleteConfirmYes),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    // TODO(backend): 接 DELETE /pet-profiles/me（级联删除/匿名化按 D1/D2）。当前仅占位提示。
    _toast(l10n.placeholderComingSoon);
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
