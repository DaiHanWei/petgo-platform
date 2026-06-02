import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/media_permission.dart';
import '../data/profile_repository.dart';

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
  bool _uploading = false;
  bool _submitting = false;

  @override
  void dispose() {
    _nameController.dispose();
    _breedController.dispose();
    _introController.dispose();
    super.dispose();
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
    if (name.isEmpty) {
      _toast(l10n.petProfileNameRequired);
      return;
    }
    setState(() => _submitting = true);
    try {
      await ref.read(profileRepositoryProvider).create(
            name: name,
            avatarUrl: _avatarUrl,
            breed: _emptyToNull(_breedController.text),
            birthday: _birthday,
            intro: _emptyToNull(_introController.text),
            idempotencyKey: 'create-${DateTime.now().microsecondsSinceEpoch}',
          );
      ref.invalidate(petProfileProvider);
      if (mounted) context.go('/profile');
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
            TextField(
              key: const ValueKey('petProfileNameField'),
              controller: _nameController,
              maxLength: 20,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                labelText: l10n.petProfileName,
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
              title: Text(l10n.petProfileBirthday),
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
