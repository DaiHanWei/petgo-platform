import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:date_picker_plus/date_picker_plus.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../pet_slot/presentation/pet_readonly_block.dart';
import '../../../core/theme/colors.dart';
import '../../auth/domain/auth_state.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
import '../../../shared/widgets/confirm_sheet.dart';
import '../data/health_record_repository.dart';
import '../data/current_pet_provider.dart';
import '../data/milestone_repository.dart';
import '../data/pet_list_repository.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
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

  /// 🔒 体重（kg），Story 6.1。**PII 邻近的健康数据 —— 禁止写入日志或埋点属性**（NFR-5）。
  /// 可留空：建档与编辑都不设必填，设必填会挡住既有转化。
  final _weightController = TextEditingController();
  DateTime? _birthday;
  String? _avatarUrl;
  String? _petType; // F6：创建后不可改，编辑页置灰只读展示，不随 PATCH 提交
  // KELAMIN（性别）：MALE/FEMALE，null = 未填。V1.1.6 Story 1.1 起**真正落库**
  // （此前是纯前端占位：能选能显示，但不提交不持久化）。
  // ⚠️ 无「清空」路径：PATCH 语义是「传 null = 不改动」，故选了就改不回未填 —— 已知且接受。
  String? _sex;
  bool _uploading = false;
  bool _submitting = false;
  bool _deleting = false;
  bool _prefilled = false;

  /// 正在编辑的那只（V1.4.0 multi-pet Story 1.5）：保存 / 删除都只作用于它（`?petId=`）。
  int? _petId;

  @override
  void dispose() {
    _nameController.dispose();
    _breedController.dispose();
    _introController.dispose();
    _weightController.dispose();
    super.dispose();
  }

  void _prefill(PetProfile p) {
    if (_prefilled) return;
    _prefilled = true;
    _petId = p.id;
    _nameController.text = p.name;
    _breedController.text = p.breed ?? '';
    _introController.text = p.intro ?? '';
    // null（没填过）与 0 是两回事 —— 空串表示没填，不要预填成 "0"
    _weightController.text = p.weightKg == null ? '' : _trimZero(p.weightKg!);
    _birthday = p.birthday;
    _avatarUrl = p.avatarUrl;
    _petType = p.petType;
    _sex = p.sex; // 漏这行 = 存住了但重进页面又变回「请选择」
  }

  /// 15.0 → "15"；15.5 → "15.5"。别让用户每次进来都看到一个多余的小数点。
  static String _trimZero(double v) =>
      v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  bool get _canSubmit => _nameController.text.trim().isNotEmpty && !_submitting && !_uploading;

  /// 换宠物头像 —— 选图 → 公开桶直传 → 回填 _avatarUrl（保存时 PATCH）。
  ///
  /// 内容审核 cm-5（D-CM2 / 方案 §3.4，有意权衡）：头像更换后**立即对所有人可见，不加「审核中」标签/遮挡**——
  /// 后端「先放行、后异步图像审核」，可见窗口期为本版本有意取舍（异步 + 举报兜底，不做「先审后显」）。
  /// 判违规则后端重置为平台默认头像常量并推 AVATAR_RESET 通知，前端照常渲染。切勿在此新增审核态 UI。
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
    // V1.4.0 multi-pet Story 2.4 · AC5.5：正在编辑的那只是只读 → 前端先拦（不发请求），弹 E1。
    final editing = _editingPetIfLoaded();
    if (editing != null && editing.isReadOnly) {
      showPetReadonlyBlockSheet(context, ref, editing);
      return;
    }
    setState(() => _submitting = true);
    try {
      await ref.read(profileRepositoryProvider).update(
            petId: _petId,
            name: _nameController.text.trim(),
            avatarUrl: _avatarUrl,
            breed: _emptyToNull(_breedController.text),
            birthday: _birthday,
            sex: _sex,
            intro: _emptyToNull(_introController.text),
            // 🔒 体重：留空 = 不改动（部分更新语义），不会把已填的值清掉
            weightKg: double.tryParse(_weightController.text.trim()),
          );
      ref.invalidate(petProfileProvider);
      // V1.4.0 multi-pet：名字 / 头像也显示在宠物列表（我的页 / Diary 切换器）里。
      ref.invalidate(petListProvider);
      if (mounted) context.go('/profile');
    } catch (e) {
      if (!mounted) return;
      // 后端兜底 409 `pet-read-only`（AC5.6）→ 统一弹 E1，不报保存失败。
      final pd = e is DioException ? ProblemDetail.fromDioException(e) : null;
      if (pd != null && handlePetReadOnly(context, ref, pd)) return;
      _toast(l10n.petProfileSaveFailed);
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  /// 正在编辑的那只在宠物列表里的项（列表未加载 → null，放行由后端兜底）。
  PetListItem? _editingPetIfLoaded() {
    final id = _petId;
    if (id == null || !ref.exists(petListProvider)) return null;
    for (final p in ref.read(petListProvider).value ?? const <PetListItem>[]) {
      if (p.id == id) return p;
    }
    return null;
  }

  /// 删除档案（bug 20260702-237 / 决策 F18）：二次确认 → DELETE /pet-profiles/me →
  /// 失效档案 provider → 回档案 Tab（无档案落空态，可重建或切换宠物状态）。
  Future<void> _confirmDelete() async {
    final l10n = AppLocalizations.of(context);
    final petName = _nameController.text.trim();
    // V1.4.0 multi-pet（Story 1.5 · C2）：删的不是最后一只 → 新正文（其他宠物不受影响、名额可再用）；
    // 删最后一只 → 沿用原正文。
    final isLast = _isLastPet();
    // 二次确认改用底部抽屉（P-02 统一样式，替换原中间 AlertDialog；bug 20260707）。
    final confirmed = await showConfirmSheet(
      context,
      title: l10n.petProfileDeleteConfirmTitle(petName),
      message: isLast
          ? l10n.petProfileDeleteConfirmBody(petName)
          : l10n.petDeleteConfirmBodyOther(petName),
      confirmLabel: l10n.petProfileDeleteConfirmYes,
      cancelLabel: l10n.commonCancel,
      icon: Icons.delete_outline_rounded,
      danger: true,
      dangerColor: AppColors.danger,
      confirmKey: const ValueKey('petProfileDeleteConfirm'),
    );
    if (!confirmed) return;
    await _deleteProfile(isLast: isLast, petName: petName);
  }

  /// 是不是最后一只：宠物列表还没取到 / 取不到时按「最后一只」处理（= 改版前的单宠口径，最保守）。
  bool _isLastPet() => (ref.read(petListProvider).value?.length ?? 1) <= 1;

  Future<void> _deleteProfile({required bool isLast, required String petName}) async {
    final l10n = AppLocalizations.of(context);
    setState(() => _deleting = true);
    try {
      await ref.read(profileRepositoryProvider).deleteMyProfile(petId: _petId);
      // V1.4.0 multi-pet（C4）：当前宠物回落 —— 清掉记忆，effectivePetIdProvider 自动落到默认宠物
      // （最早创建的正常宠物）；宠物列表少一只。
      ref.read(currentPetIdProvider.notifier).clear();
      ref.invalidate(petListProvider);
      ref.invalidate(petProfileProvider);
      // 删除成功须同步清除成长日记的宠物维度缓存（时间线/统计/日历/日详情/里程碑），
      // 否则删后重建新档案，成长日记仍显示上一只宠物的缓存内容、要手动刷新才消失。
      // （family provider 整族失效：calendarMonth 按月、dayDetail 按日。）
      ref.invalidate(timelineFirstPageProvider);
      ref.invalidate(archiveStatsProvider);
      ref.invalidate(calendarMonthProvider);
      ref.invalidate(dayDetailProvider);
      ref.invalidate(milestoneListProvider);
      // bug 20260730-421：健康记录缓存同样要清（healthListProvider 非 autoDispose，
      // 漏失效 → 删档重建后健康页仍显示上一只宠物的记录；后端级联删无漏，纯前端缓存问题）。
      ref.invalidate(healthListProvider);
      // PR#34 finding #12：auth 里的 hasPetProfile 只在建档时置 true、删档必须对称置 false——
      // 否则本会话内发布页「无 preset 默认类型」仍读到 true，默认落 Diary 且 chip 可点，
      // 用户写完点分享才被「先建档」拒绝（与建档页 applyProfile(hasPetProfile: true) 成对）。
      // V1.4.0 multi-pet：只有删的是最后一只才置 false（还剩宠物就仍是「已建档」）。
      final authProfile = ref.read(authControllerProvider).profile;
      if (isLast && authProfile != null && authProfile.hasPetProfile) {
        ref
            .read(authControllerProvider.notifier)
            .applyProfile(authProfile.copyWith(hasPetProfile: false));
      }
      if (!mounted) return;
      // 跳转前先拿根 Overlay：go 之后本页即销毁，context 不能再用。
      final overlay = Overlay.maybeOf(context, rootOverlay: true);
      context.go('/profile');
      // C4：非最后一只 → 回 Diary Tab（已切到剩余宠物）+ Toast；C6 删最后一只 → 现状，无 Toast。
      if (!isLast && overlay != null) {
        showAppToastOnOverlay(overlay, l10n.petDeleteSuccess(petName));
      }
    } catch (_) {
      if (mounted) _toast(l10n.petProfileDeleteFailed);
    } finally {
      if (mounted) setState(() => _deleting = false);
    }
  }

  void _toast(String msg) {
    showAppToast(context, msg, duration: const Duration(seconds: 3));
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
        // 稿 C1：标题左对齐（L2 还原度，原居中）。
        centerTitle: false,
        titleSpacing: 0,
        leading: IconButton(
          icon: const Icon(Icons.chevron_left_rounded, size: 28, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/profile'),
        ),
        // 标题随宠物名（原型 P-32「Edit Profil {名}」）；加载中回退通用标题。
        title: Text(
          (loadedName != null && loadedName.isNotEmpty)
              ? l10n.petProfileEditTitleNamed(loadedName)
              : l10n.petProfileEditTitle,
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
                                // 按物种占位（同 PetInfoCard 口径；原先恒为 🐱，狗也顶着猫脸，L2 第二轮 P3-6）。
                                ? Text(
                                    switch (_petType) { 'CAT' => '🐱', 'DOG' => '🐶', _ => '🐾' },
                                    style: const TextStyle(fontSize: 38))
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
            // 稿 C1：字数「4/20」在框内右侧。
            decoration: _inputDeco(counter: '${_nameController.text.characters.length}/20'),
          ),
          const SizedBox(height: 16),
          // JENIS HEWAN（F6 编辑态锁定）：灰底锁定字段，「🔒 Tidak bisa diubah」红字在框内右侧（稿 C1）。
          _sectionLabel(l10n.petProfileTypeLabel),
          const SizedBox(height: 6),
          SpeciesField(
              key: const ValueKey('petProfileEditTypeReadonly'),
              petType: _petType,
              locked: true,
              lockedHint: l10n.petTypeLockedHint),
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
          // Story 6.1：体重。🔴 这是 FR-107 精准推荐与 FR-109 粮量预估的唯一输入 ——
          //    但它【可跳过】：设必填会挡住既有编辑转化。
          _sectionLabel(l10n.petProfileWeightLabel),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditWeightField'),
            controller: _weightController,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: _inputDeco(),
          ),
          const SizedBox(height: 4),
          Text(l10n.petProfileWeightHint,
              key: const ValueKey('petProfileEditWeightHint'),
              style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
          const SizedBox(height: 16),
          _sectionLabel(l10n.petProfileBioLabel),
          const SizedBox(height: 6),
          TextField(
            key: const ValueKey('petProfileEditIntroField'),
            controller: _introController,
            maxLength: _kBioMax,
            // 稿 C1：单行高起步、随内容长高；字数「18/200」在框内右侧。
            minLines: 1,
            maxLines: 4,
            onChanged: (_) => setState(() {}),
            decoration:
                _inputDeco(counter: '${_introController.text.characters.length}/$_kBioMax'),
          ),
          const SizedBox(height: AppSpacing.lg),
          // 危险区：删除档案（bug 20260702-237 / 决策 F18）。保存按钮在右上角 AppBar（原型 P-32）。
          // 真删除：后端 DELETE /pet-profiles/me 级联删派生数据 + 名片失效 + 清理个人图，保留 UGC；
          // petStatus 不变，删后回档案 Tab 落空态可重建或切换宠物状态（闭合被困死问题）。
          TextButton.icon(
            key: const ValueKey('petProfileDeleteButton'),
            onPressed: _deleting ? null : _confirmDelete,
            // 稿 `.delbtn`：橙红 #E5604D（= AppColors.danger），非 Pop Art 玫红。
            style: TextButton.styleFrom(
              foregroundColor: AppColors.danger,
              padding: const EdgeInsets.symmetric(vertical: 12),
            ),
            icon: _deleting
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.danger))
                : const Icon(Icons.delete_outline, size: 20),
            label: Text(l10n.petProfileDeleteButton(_nameController.text.trim()),
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
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
            // 稿 `.fl`：标签一律大写（arb 里「Berat badan (kg)」未大写，L2 还原度）。
            TextSpan(text: text.toUpperCase()),
            if (required) const TextSpan(text: ' *'),
          ],
        ),
      );

  InputDecoration _inputDeco({String? counter}) => InputDecoration(
        isDense: true,
        // 用 suffixIcon 而非 suffixText：后者在未聚焦且为空时不显示，稿里计数常显。
        suffixIcon: counter == null
            ? null
            : Padding(
                padding: const EdgeInsets.only(right: 14),
                child: Text(counter, style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ),
        suffixIconConstraints: const BoxConstraints(minWidth: 0, minHeight: 0),
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

  /// KELAMIN 选择字段（原型 pet-edit：边框 + 下拉箭头）。未填时显示占位文案。
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

  Future<void> _pickBirthday() async {
    final now = DateTime.now();
    // bug 20260623-047：统一 date_picker_plus 干净日历风格（点某天即选中并关闭）。
    final picked = await showDatePickerDialog(
      context: context,
      minDate: DateTime(now.year - 40),
      maxDate: now,
      selectedDate: _birthday,
      displayedDate: _birthday ?? now,
    );
    if (picked != null && mounted) setState(() => _birthday = picked);
  }
}
