import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:date_picker_plus/date_picker_plus.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/app_image.dart';
import '../../pet_slot/data/pet_slot_repository.dart';
import '../../pet_slot/presentation/add_pet_flow.dart';
import '../data/current_pet_provider.dart';
import '../data/health_event_repository.dart';
import '../data/pet_list_repository.dart';
import '../data/profile_repository.dart';
import '../data/timeline_repository.dart';
import '../domain/pending_archive.dart';
import '../domain/profile_created_flow.dart';
import 'widgets/pet_form_fields.dart';

/// 宠物档案创建表单（Story 2.2 · F1）。
///
/// V1.4.0 multi-pet（Story 1.5 · AC4.5）：**去掉**「已有档案直达」守卫（F3）—— 多宠物下有档案也能再建一只；
/// 名额 / 上限由「+」入口先判（`startAddPet`），建档 409 `pet-slot-full` / `pet-limit-reached` 兜底弹 A4 / A4b。
/// 头像复用 Story 2.1 媒体工具；名字 ≤20 / 介绍 ≤30 实时计数；服务端为权威校验。
class PetProfileCreatePage extends ConsumerStatefulWidget {
  const PetProfileCreatePage({super.key, this.slotOverview, this.addFlow = false});

  /// 路由 query 里表示「从添加宠物流程（`startAddPet` 的「+」）进来」的键：`/profile/create?add=1`。
  static const String addFlowQuery = 'add';

  /// 是否从「+」添加流程进来（code review 2026-10-18 #3）。
  ///
  /// - true：多宠物新流程 —— 已有宠物也渲染表单（名额已由「+」入口先判，409 兜底）；
  /// - false（**存量入口**：深链默认 push、游客页 / 示例页 / Toko 建档按钮、Diary 失败兜底…）：
  ///   保留改版前的「已有档案直达」守卫（F3），已有宠物 → 跳 Diary Tab，不给一张注定 409 的空表单。
  final bool addFlow;

  /// 「+」入口取到的名额摘要（建档**前**的口径，经路由 extra 带进来）；直接进本页（引导卡等）为 null。
  /// 只用于推 `pet_added.slot_source` / `pet_index`。
  final PetSlotOverview? slotOverview;

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
  // 名字/简介触达上限各给一次 toast（maxLength 静默硬截断本身无反馈，参照评论 bug 20260702-218）。
  bool _nameLimitToasted = false;
  bool _bioLimitToasted = false;

  static const int _nameMaxLen = 20;
  static const int _bioMaxLen = 30;

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
    // species 为受控枚举（CAT/DOG/OTHER），供归因分发（AppsFlyer P1）；不传宠物名等自由文本。
    Analytics.capture('pet_profile_create_submitted', {'species': petType});
    setState(() => _submitting = true);
    // 建档**前**取只数（建完列表会被失效重取）。
    final petIndex = _petIndexAfterCreate();
    try {
      final created = await ref.read(profileRepositoryProvider).create(
            petType: petType,
            name: name,
            birthday: birthday,
            avatarUrl: _avatarUrl,
            breed: _emptyToNull(_breedController.text),
            sex: _sex,
            intro: _emptyToNull(_introController.text),
            idempotencyKey: 'create-${DateTime.now().microsecondsSinceEpoch}',
          );
      // V1.4.0 multi-pet（Story 1.5 · AC4.6）：新建的这只成为当前宠物；列表 / 名额摘要失效。
      _reportPetAdded(petIndex);
      ref.read(currentPetIdProvider.notifier).select(created.id);
      ref.invalidate(petListProvider);
      ref.invalidate(petSlotOverviewProvider);
      ref.invalidate(petProfileProvider);
      _markProfileCreated();
      if (!mounted) return;
      // 建档来源（路由 query `?origin=`）：FR-0G 正常建档 → 庆祝页（AC4/F15）；
      // FR-16 问诊存档 / FR-12 灰选发布 → 跳过庆祝页，直接回原流程（Story 2.5/2.3 接管）。
      // 🔴 必须在任何 go/push 之前置位（bug 20260826）：转场动画期间本页仍会 build。
      setState(() => _leavingAfterCreate = true);
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
      final slug = pd?.typeSlug;
      if (slug == kPetSlotFullSlug) {
        // V1.4.0 multi-pet（AC4.5）：名额已满 → A4（只用扩展成员里的数字，不渲染后端显示串）。
        // 不 await：抽屉开着时提交按钮要先复位（finally），不能一直转圈。
        if (mounted) {
          showPetSlotFullSheet(context, PetSlotFullInfo.fromProblemBody(e.response?.data),
              source: 'add_pet');
        }
      } else if (slug == kPetLimitReachedSlug) {
        // 已达 5 只上限 → A4b。
        if (mounted) showPetLimitSheet(context);
      } else if (slug == 'profile-exists' || pd?.status == 409) {
        // 并发双开窗：已存在 → 同步登录态后提示并直达档案（避免提示条残留）。
        _markProfileCreated();
        setState(() => _leavingAfterCreate = true);
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

  /// 建档成功后回填本地登录态：`auth.profile.hasPetProfile = true`。
  ///
  /// 这是「是否已建档」的权威源之一（Diary 分支判定、发布页宠物绑定等多处读它）；
  /// 不回填就要等下次冷启动重拉 `/me` 才更新。
  /// （V1.1.2 Story 2.3：原先这里还要关闭 FR-0H 首页提示条，该提示条已整条废止。）
  /// 性别（bug 20260827，选填）。取值 MALE / FEMALE，与编辑页一致。
  String? _sex;

  /// 建档成功、已经在往下一屏走了（bug 20260826）。置位后 [build] **不再走「已有档案直达」**：
  /// `_submit` 跳转前会 invalidate 档案，重拉到的正是刚建好的那只；转场动画期间本页仍在树上 build，
  /// 不挡住就会把刚跳过去的庆祝页顶掉。（仅存量入口有守卫，见 [PetProfileCreatePage.addFlow]。）
  bool _leavingAfterCreate = false;

  /// 建档后的宠物只数（V1.4.0 multi-pet `pet_added.pet_index`）：优先用「+」入口带来的名额摘要，
  /// 否则用已加载的宠物列表；都没有 → 视为第 1 只。
  int _petIndexAfterCreate() {
    final before = widget.slotOverview?.petCount ?? ref.read(petListProvider).value?.length ?? 0;
    return before + 1;
  }

  /// `pet_added {pet_index, slot_source}`（PRD §4）：slot_source 按建档**前**的名额摘要推 ——
  /// 第 1 只 → default；`pet_index ≤ defaultSlots + levelSlots` → level；否则 rent；取不到摘要 → default。
  void _reportPetAdded(int petIndex) {
    final o = widget.slotOverview;
    final String slotSource;
    if (o == null || petIndex <= 1) {
      slotSource = 'default';
    } else if (petIndex <= o.defaultSlots + o.levelSlots) {
      slotSource = 'level';
    } else {
      slotSource = 'rent';
    }
    Analytics.capture('pet_added', {'pet_index': petIndex, 'slot_source': slotSource});
  }

  void _markProfileCreated() {
    final profile = ref.read(authControllerProvider).profile;
    if (profile != null && !profile.hasPetProfile) {
      ref
          .read(authControllerProvider.notifier)
          .applyProfile(profile.copyWith(hasPetProfile: true));
    }
  }

  void _toast(String msg) {
    if (!mounted) return;
    showAppToast(context, msg, duration: const Duration(seconds: 3));
  }

  /// 输入触达 maxLength 时给一次 toast；退回上限下复位。用字素(characters)计数，
  /// 与 maxLength 的截断口径一致。返回新的「已提示」标志。
  bool _maybeLimitToast(String value, int maxLen, bool toasted, String msg) {
    final atLimit = value.characters.length >= maxLen;
    if (atLimit && !toasted) {
      showAppToast(context, msg);
      return true;
    }
    if (!atLimit && toasted) return false;
    return toasted;
  }

  static String? _emptyToNull(String s) => s.trim().isEmpty ? null : s.trim();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 订阅宠物列表只为建档成功时算 `pet_added.pet_index`（不影响渲染）。
    ref.watch(petListProvider);
    // V1.4.0 multi-pet（AC4.5）：「+」添加流程不再「已有档案直达」—— 直接渲染表单。
    if (widget.addFlow) return _form(context, l10n);
    // 存量入口：保留改版前的「已有档案直达」守卫（F3，code review 2026-10-18 #3）。
    final existing = ref.watch(petProfileProvider);
    return existing.when(
      loading: () => const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (err, stack) => _form(context, l10n), // 查询失败时仍允许创建（服务端兜底）
      data: (profile) {
        if (profile != null && !_leavingAfterCreate) {
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
          icon: const Icon(Icons.chevron_left_rounded, size: 28, color: AppColors.ink),
          onPressed: () => context.canPop() ? context.pop() : context.go('/home'),
        ),
        title: Text(l10n.petProfileCreateTitle),
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
                maxLength: _nameMaxLen,
                onChanged: (v) {
                  _nameLimitToasted = _maybeLimitToast(
                      v, _nameMaxLen, _nameLimitToasted, l10n.petProfileNameLimitReached);
                  setState(() {});
                },
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
            // 🔴 性别（bug 20260827）：此前**只有编辑页有这个字段**，建档页没有 ——
            //    于是新用户建完档，身份证卡上「Jenis Kelamin」永远是「-」，
            //    除非他自己再去编辑一次。而那张卡是身份证功能的门面。
            // ⚠️ 选填（标签不带 *）：与体重同一理由 —— 建档转化在漏斗上比字段完整度重要，
            //    多一个必填项就多一处流失。
            // 🛡 选择器与编辑页**同一形态、同一取值**（MALE / FEMALE）：
            //    两边长得不一样会让用户以为是两个不同的东西。
            _sectionLabel(l10n.petProfileSexLabel),
            const SizedBox(height: 6),
            _sexField(l10n),
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
                maxLength: _bioMaxLen,
                maxLines: 3,
                onChanged: (v) {
                  _bioLimitToasted = _maybeLimitToast(
                      v, _bioMaxLen, _bioLimitToasted, l10n.petProfileBioLimitReached);
                },
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

  /// 性别选择字段（与编辑页同一形态）。未选时显示占位文案。
  Widget _sexField(AppLocalizations l10n) {
    final label = switch (_sex) {
      'MALE' => l10n.petProfileSexMale,
      'FEMALE' => l10n.petProfileSexFemale,
      _ => l10n.petProfileSexPick,
    };
    return InkWell(
      key: const ValueKey('petProfileSexTile'),
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
    if (picked != null && mounted) setState(() => _sex = picked);
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
    // 完整年月日 date picker（R2/AC3）。bug 20260623-047：换成 date_picker_plus 干净日历风格
    // （紫主题继承 App colorScheme；点某天即选中并关闭，无 OK 按钮）。
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
