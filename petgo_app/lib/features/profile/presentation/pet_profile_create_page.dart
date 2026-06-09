import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/media_permission.dart';
import '../data/profile_repository.dart';
import '../domain/profile_created_flow.dart';

/// 宠物档案创建表单（Story 2.2 · F1）。
///
/// 含「已有档案直达」守卫（F3）：进入即读 [petProfileProvider]，已存在 → 跳档案 Tab，不渲染表单。
/// 头像复用 Story 2.1 媒体工具；名字 ≤20 / 介绍 ≤30 实时计数；服务端为权威校验。
class PetProfileCreatePage extends ConsumerStatefulWidget {
  const PetProfileCreatePage({super.key});

  @override
  ConsumerState<PetProfileCreatePage> createState() => _PetProfileCreatePageState();
}

class _PetProfileCreatePageState extends ConsumerState<PetProfileCreatePage> {
  final _nameController = TextEditingController();
  final _breedController = TextEditingController();
  final _introController = TextEditingController();
  DateTime? _birthday;
  String? _avatarUrl;
  String? _petType; // F6 必选：CAT/DOG/OTHER
  bool _uploading = false;
  bool _submitting = false;

  @override
  void dispose() {
    _nameController.dispose();
    _breedController.dispose();
    _introController.dispose();
    super.dispose();
  }

  // 必填（F6 + R2/AC3）：类型 + 名字 + 生日（完整年月日）齐全方可提交。
  bool get _canSubmit =>
      _petType != null &&
      _nameController.text.trim().isNotEmpty &&
      _birthday != null &&
      !_submitting &&
      !_uploading;

  Future<void> _pickAvatar() async {
    setState(() => _uploading = true);
    try {
      final result = await ref.read(mediaUploadUseCaseProvider).pickAndUploadOne(
            scope: MediaScope.public,
            source: MediaSource.gallery,
            context: context,
          );
      if (result?.publicUrl != null) {
        setState(() => _avatarUrl = result!.publicUrl);
      }
    } catch (_) {
      if (mounted) _toast(AppLocalizations.of(context).mediaUploadFailed);
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    final name = _nameController.text.trim();
    final petType = _petType;
    final birthday = _birthday;
    // 必填守卫（与 _canSubmit 一致；按钮已禁用，此处双保险）：类型/名字/生日齐全。
    if (petType == null || name.isEmpty || birthday == null) {
      _toast(l10n.petProfileNameRequired);
      return;
    }
    setState(() => _submitting = true);
    try {
      final created = await ref.read(profileRepositoryProvider).create(
            petType: petType,
            name: name,
            birthday: birthday,
            avatarUrl: _avatarUrl,
            breed: _emptyToNull(_breedController.text),
            intro: _emptyToNull(_introController.text),
            idempotencyKey: 'create-${DateTime.now().microsecondsSinceEpoch}',
          );
      ref.invalidate(petProfileProvider);
      if (!mounted) return;
      // 建档来源（路由 query `?origin=`）：FR-0G 正常建档 → 庆祝页（AC4/F15）；
      // FR-16 问诊存档 / FR-12 灰选发布 → 跳过庆祝页，直接回原流程（Story 2.5/2.3 接管）。
      final origin =
          buildOriginFromName(GoRouterState.of(context).uri.queryParameters['origin']);
      if (showsBuildCelebration(origin)) {
        context.go('/profile/created', extra: created);
      } else {
        context.go('/profile');
      }
    } on DioException catch (e) {
      final pd = ProblemDetail.fromDioException(e);
      if (pd?.typeSlug == 'profile-exists' || pd?.status == 409) {
        // 并发双开窗：已存在 → 提示并直达档案。
        _toast(l10n.petProfileExists);
        if (mounted) context.go('/profile');
      } else {
        _toast(l10n.petProfileCreateFailed);
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 3)));
  }

  static String? _emptyToNull(String s) => s.trim().isEmpty ? null : s.trim();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final existing = ref.watch(petProfileProvider);

    return existing.when(
      loading: () => const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (err, stack) => _form(context, l10n), // 查询失败时仍允许创建（服务端单宠物兜底）
      data: (profile) {
        if (profile != null) {
          // 已有档案直达（F3）：跳档案 Tab，不渲染表单。
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) context.go('/profile');
          });
          return const Scaffold(body: Center(child: CircularProgressIndicator()));
        }
        return _form(context, l10n);
      },
    );
  }

  Widget _form(BuildContext context, AppLocalizations l10n) {
    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.petProfileCreateTitle), backgroundColor: AppColors.base),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.xl),
          children: [
            Center(
              child: GestureDetector(
                key: const ValueKey('petProfileAvatar'),
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
            // 宠物类型（F6 必选，创建后不可改）
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: Text('${l10n.petTypeLabel} *', style: AppTypography.caption),
            ),
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.sm,
              children: [
                _petTypeChip('CAT', l10n.petTypeCat),
                _petTypeChip('DOG', l10n.petTypeDog),
                _petTypeChip('OTHER', l10n.petTypeOther),
              ],
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const ValueKey('petProfileNameField'),
              controller: _nameController,
              maxLength: 20,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: '${l10n.petProfileName} *',
                hintText: l10n.petProfileNameHint,
              ),
            ),
            TextField(
              key: const ValueKey('petProfileBreedField'),
              controller: _breedController,
              maxLength: 60,
              decoration: InputDecoration(labelText: l10n.petProfileBreed),
            ),
            ListTile(
              key: const ValueKey('petProfileBirthdayTile'),
              contentPadding: EdgeInsets.zero,
              title: Text('${l10n.petProfileBirthday} *'),
              subtitle: Text(_birthday == null
                  ? l10n.petProfileBirthdayPick
                  : '${_birthday!.year}-${_birthday!.month}-${_birthday!.day}'),
              trailing: const Icon(Icons.calendar_today_outlined),
              onTap: _pickBirthday,
            ),
            TextField(
              key: const ValueKey('petProfileIntroField'),
              controller: _introController,
              maxLength: 30,
              decoration: InputDecoration(labelText: l10n.petProfileIntro),
            ),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              key: const ValueKey('petProfileSubmit'),
              onPressed: _canSubmit ? _submit : null,
              child: _submitting
                  ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : Text(l10n.petProfileSubmit),
            ),
          ],
        ),
      ),
    );
  }

  Widget _petTypeChip(String value, String label) {
    return ChoiceChip(
      key: ValueKey('petType_$value'),
      label: Text(label),
      selected: _petType == value,
      onSelected: (_) => setState(() => _petType = value),
    );
  }

  Future<void> _pickBirthday() async {
    final now = DateTime.now();
    // 完整年月日 date picker（R2/AC3）：不提供只月日模式，产出完整 date。
    final picked = await showDatePicker(
      context: context,
      initialDate: _birthday ?? now,
      firstDate: DateTime(now.year - 40),
      lastDate: now,
    );
    if (picked != null) setState(() => _birthday = picked);
  }
}
