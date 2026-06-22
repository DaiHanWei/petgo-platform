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
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
import '../data/health_event_repository.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/pending_archive.dart';
import '../domain/profile_created_flow.dart';
import 'widgets/pet_form_fields.dart';

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
      } else if (origin == BuildOrigin.graySelectPublish) {
        // AC7（F15）：灰选成长日历触发的建档完成 → 跳过庆祝页，回发布着陆页预选成长日历续发
        // （复用 /publish 着陆页首帧开 sheet 的稳健路径，不从已失效的建档页 context 直接开 sheet）。
        context.go('/publish?preset=growth-calendar');
      } else if (origin == BuildOrigin.triageArchive) {
        // Story 2.5 AC3/AC4：FR-16 问诊存档触发的建档完成 → 跳过庆祝页，用同一 sourceRef
        // 回灌挂起的存档意图（recordDecision ARCHIVED，幂等键兜底），再回成长档案查看。
        // 注：红色态语义为「返回结果页」——结果页属 Epic 4 瞬态路由，此处回档案为近似（条目已落库可见）。
        await _backfillPendingArchive(created.id);
        if (mounted) context.go('/profile');
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

  /// AC3/AC4：建档成功后回灌挂起的存档意图（同 sourceRef，幂等）。无挂起则无操作（放弃建档无副作用）。
  Future<void> _backfillPendingArchive(int newPetId) async {
    final pending = ref.read(pendingArchiveProvider);
    if (pending == null) return;
    try {
      await ref.read(healthEventRepositoryProvider).recordDecision(
            sourceType: pending.sourceType,
            sourceRef: pending.sourceRef,
            petId: newPetId,
            decision: ArchiveDecision.archived,
            symptomSummary: pending.symptomSummary,
            aiLevel: pending.aiLevel,
            adviceSummary: pending.adviceSummary,
            imImageRefs: pending.imImageRefs,
          );
    } catch (_) {
      // 回灌失败不阻断建档完成；幂等键允许后续重试不重存。
    } finally {
      ref.read(pendingArchiveProvider.notifier).clear(); // 消费后清空
      ref.invalidate(timelineFirstPageProvider);
      ref.invalidate(archiveStatsProvider);
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
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/home'),
        ),
        title: Text(l10n.petProfileCreateTitle,
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: [
            // 虚线圆头像 + 相机角标 + Upload Foto（pet-create.html）。
            Center(child: _avatarPicker(l10n)),
            const SizedBox(height: AppSpacing.lg),
            // NAMA HEWAN *
            _sectionLabel(l10n.petProfileNameLabel, required: true),
            const SizedBox(height: 6),
            _field(
              child: TextField(
                key: const ValueKey('petProfileNameField'),
                controller: _nameController,
                maxLength: 20,
                onChanged: (_) => setState(() {}),
                decoration: _inputDeco(hint: l10n.petProfileNameHint),
              ),
            ),
            const SizedBox(height: 16),
            // JENIS HEWAN * (Tidak bisa diubah setelah dibuat)
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                _sectionLabel(l10n.petProfileTypeLabel, required: true),
                const SizedBox(width: 6),
                Flexible(
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 1),
                    child: Text(l10n.petProfileTypeImmutableHint,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 9.5, fontWeight: FontWeight.w600, color: AppColors.popRed)),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // JENIS HEWAN：下拉按枚举选（原型 P-30）。
            SpeciesField(
              petType: _petType,
              onChanged: (t) => setState(() {
                _petType = t;
                _breedController.clear(); // 物种切换 → 品种清单变，清空已选 RAS
              }),
            ),
            const SizedBox(height: 16),
            // RAS：按物种精选清单下拉 + Lainnya 手填（原型 P-30）。
            _sectionLabel(l10n.petProfileBreedLabel),
            const SizedBox(height: 6),
            BreedField(
              petType: _petType,
              controller: _breedController,
              onChanged: () => setState(() {}),
            ),
            const SizedBox(height: 16),
            // TANGGAL LAHIR *
            _sectionLabel(l10n.petProfileBirthdayLabel, required: true),
            const SizedBox(height: 6),
            InkWell(
              key: const ValueKey('petProfileBirthdayTile'),
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
            // BIO (OPSIONAL)
            _sectionLabel(l10n.petProfileBioLabel),
            const SizedBox(height: 6),
            _field(
              child: TextField(
                key: const ValueKey('petProfileIntroField'),
                controller: _introController,
                maxLength: 30,
                maxLines: 3,
                decoration: _inputDeco(hint: l10n.petProfileBioHint),
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const ValueKey('petProfileSubmit'),
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
                    : Text(l10n.petProfileCreateSubmit,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 虚线圆头像 + 紫色相机角标 + 「Upload Foto」紫链（pet-create.html）。
  Widget _avatarPicker(AppLocalizations l10n) {
    return GestureDetector(
      key: const ValueKey('petProfileAvatar'),
      onTap: _uploading ? null : _pickAvatar,
      child: Column(
        children: [
          SizedBox(
            width: 96,
            height: 96,
            child: CustomPaint(
              painter: _DashedCirclePainter(color: AppColors.dashedViolet),
              child: Stack(
                children: [
                  Center(
                    child: _avatarUrl == null
                        ? (_uploading
                            ? const CircularProgressIndicator()
                            : const Text('🐱', style: TextStyle(fontSize: 38)))
                        : ClipOval(
                            child: AppImage.widget(_avatarUrl!,
                                width: 88, height: 88, fit: BoxFit.cover, thumbWidth: 280)),
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
          const SizedBox(height: 8),
          Text(l10n.petProfileUploadPhoto,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.mint)),
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
            if (required)
              const TextSpan(text: ' *', style: TextStyle(color: AppColors.popRed)),
          ],
        ),
      );

  /// 紫边框圆角输入容器（包 TextField，去掉其默认下划线）。
  Widget _field({required Widget child}) => child;

  InputDecoration _inputDeco({String? hint}) => InputDecoration(
        hintText: hint,
        hintStyle: const TextStyle(color: AppColors.muted, fontSize: 14),
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

/// 虚线圆边框画笔（pet-create.html 头像 dashed ring）。
class _DashedCirclePainter extends CustomPainter {
  _DashedCirclePainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width / 2 - 1;
    const dashCount = 36;
    const gapRatio = 0.45;
    final sweep = (2 * 3.1415926 / dashCount) * (1 - gapRatio);
    final step = 2 * 3.1415926 / dashCount;
    for (var i = 0; i < dashCount; i++) {
      final start = step * i;
      canvas.drawArc(
        Rect.fromCircle(center: center, radius: radius),
        start,
        sweep,
        false,
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_DashedCirclePainter oldDelegate) => oldDelegate.color != color;
}
