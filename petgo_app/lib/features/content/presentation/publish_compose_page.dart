import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:date_picker_plus/date_picker_plus.dart';
import '../../../shared/widgets/app_toast.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/router/deep_link_routes.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../profile/data/current_pet_provider.dart';
import '../../profile/data/milestone_repository.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/milestone.dart';
import '../../profile/domain/milestone_share.dart';
import '../../profile/domain/milestone_titles.dart';
import '../../profile/domain/pet_profile.dart';
import '../../profile/data/milestone_celebration_reporter.dart';
import '../../profile/presentation/widgets/milestone_celebration.dart';
import '../../../shared/media/image_lightbox.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/widgets/keyboard_safe_area.dart';
import '../../../shared/utils/media_permission.dart';
import '../../me/data/me_counts_provider.dart';
import '../../me/data/my_posts_repository.dart';
import '../../mention/data/mention_candidate_repository.dart';
import '../../mention/domain/mention_context.dart';
import '../../mention/domain/mention_draft.dart';
import '../../mention/presentation/mention_picker.dart';
import '../data/content_repository.dart';
import '../domain/content_type.dart';
import '../domain/publish_checkin_place.dart';
import '../domain/image_exposure.dart';
import '../domain/publish_controller.dart';
import 'publish_exposure_page.dart';
import 'feed_controller.dart';
import 'publish_result_page.dart';
import '../../profile/data/pet_list_repository.dart';
import '../../profile/presentation/widgets/pet_names.dart';
import '../../pet_slot/data/pet_slot_repository.dart' show kPetReadOnlySlug;
import '../../../shared/widgets/app_image.dart';

/// 发布控制器 provider（autoDispose：sheet 关闭即 dispose，清空内存草稿，NFR-10 无持久草稿）。
final publishControllerProvider = Provider.autoDispose<PublishController>((
  ref,
) {
  final useCase = ref.read(mediaUploadUseCaseProvider);
  final controller = PublishController(
    repository: ref.read(contentRepositoryProvider),
    uploadOne: (bytes) async {
      final result = await useCase.uploadBytes(
        scope: MediaScope.public,
        bytes: bytes,
      );
      return result.publicUrl!;
    },
  );
  ref.onDispose(controller.dispose);
  return controller;
});

/// 统一发布 Compose 全屏 bottom sheet（Story 2.3 · TailTopia Prototype 换肤）。
/// 类型标签（Cerita / Momen / Edukasi）→ 作者+关联宠物 → 文字 → 图片 → 发布。
class PublishComposePage extends ConsumerStatefulWidget {
  const PublishComposePage({
    super.key,
    this.preset,
    this.presetEventDate,
    this.milestoneCode,
    this.placeCheckinToken,
    this.placeCheckinPlace,
    this.initialText,
    this.initialImages,
    this.initialPetIds,
  });

  /// 预选发布类型（如生日深链预选成长日历，Story 6.1 FR-40 / 灰选建档返回，AC7）；为空时默认 daily。
  final ContentType? preset;

  /// 成长日历事件日期默认值（F9）：从「＋」进取今天、从日历格子进（2.4）取该格子日期；为空取今天。
  final DateTime? presetEventDate;

  /// 里程碑「去发布」来源 code（Story 8.4）：发布成功且仍为成长日历类型 → 以新内容 id 自动打卡完成。
  final String? milestoneCode;

  /// 打卡后顺手发帖（V1.3.2 Story 1.5 · AD-10 / AD-11）：关联的打卡 token，随发布请求**一次写入**
  /// （不是发布后回填 —— 丢关联会让 Diary 把同一件事拆成两条）。任何帖子类型都带。
  final String? placeCheckinToken;

  /// 场所条展示用（名 / 类型 / token 供埋点）；**不进请求体**。
  final PublishCheckinPlace? placeCheckinPlace;

  /// 预填正文（V1.3.2 Story 4.4「Pamer di postingan」）：超 [kMaxPostTextLength] 截断。
  final String? initialText;

  /// 预填图片（已是 JPEG 字节；V1.3.2 Story 4.4）：**不经相册、不申请权限**，直接入图片区并开始上传。
  /// 超 [kMaxImages] 丢弃。关闭发帖页即随 autoDispose 清空（无草稿，NFR-10）。
  final List<Uint8List>? initialImages;

  /// Diary 默认预选的宠物（V1.4.0 multi-pet Story 3.3 · AC1.7）：打卡成功页「Rekam Momen Ini」= 本次打卡的宠物（G3）。
  /// 预选时过滤掉已不在可选列表（ACTIVE）里的 id。null = 不预选（只 1 只正常宠物时仍自动选中，F4）。
  final List<int>? initialPetIds;

  /// 以全屏 bottom sheet 形式打开（供「＋」入口 / 深链着陆页 / 灰选建档返回 / 里程碑去发布调用）。
  static Future<void> open(
    BuildContext context, {
    ContentType? preset,
    DateTime? presetEventDate,
    String? milestoneCode,
    String? placeCheckinToken,
    PublishCheckinPlace? placeCheckinPlace,
    String? initialText,
    List<Uint8List>? initialImages,
    List<int>? initialPetIds,
  }) {
    // AC2 的第 5 个底栏位（code-review 2026-08-04 决策 D1）：发布页是 modal sheet 而非
    // `PageRoute`，PostHog 的 `defaultPostHogRouteFilter` 只跟踪 `PageRoute` → observer 收不到，
    // 所以和 4 个 Tab 根页一样手工上报，屏名沿用同一套 `<产品叫法>_page` 命名。
    // ⚠️ 它不是 Tab 根页，看板上按「底栏 5 个位置的曝光」统计时才把它算进来。
    Analytics.screen('publish_page');
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.cream,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (_) => FractionallySizedBox(
        heightFactor: 0.95,
        child: PublishComposePage(
          preset: preset,
          presetEventDate: presetEventDate,
          milestoneCode: milestoneCode,
          placeCheckinToken: placeCheckinToken,
          placeCheckinPlace: placeCheckinPlace,
          initialText: initialText,
          initialImages: initialImages,
          initialPetIds: initialPetIds,
        ),
      ),
    );
  }

  @override
  ConsumerState<PublishComposePage> createState() => _PublishComposePageState();
}

class _PublishComposePageState extends ConsumerState<PublishComposePage> {
  final _textController = TextEditingController();

  @override
  void initState() {
    super.initState();
    // 默认类型判定的**唯一口径**（Story 4.2 · AC3/AC6）：
    //   ① 调用方给了 preset（深链 / 日历某天 / Diary Tab 语境）→ 用它；
    //   ② 否则**有宠物档案 → Diary**、无档案 → Moment（Diary 此时是灰置的，选不了）。
    //
    // ⚠️ 合并结论：`app_shell.dart _onAddPressed` **只负责传 preset**（它知道用户当时在哪个 Tab），
    // 「有无档案 → 默认哪个类型」的判定**只在本页做**。此前两处各判一次，会出现
    // 「shell 预选 Growth、本页又落回 Daily」这类互相覆盖。别把判定加回 shell。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final c = ref.read(publishControllerProvider);
      // bug 20260819：Diary preset 也要过档案闸——「＋」入口（shell）有 canGrowth 守卫，但深链 /
      // 日历格 / 里程碑「去发布」等入口（publish_landing_page / growth_archive_page）直传 preset。
      // 无档案（含已删——删档已对称置 false，PR#34 #12）时 Diary 会被预选中：chip 灰置却处于选中态，
      // 用户写完提交才被「先建档」拒。明确无档案 → Diary preset 视同无 preset，回落 Moment。
      // 仅在「auth profile 在且 hasPetProfile=false」时拦：profile 为 null（游客/极端时序）不误伤
      // 灰选建档返回流（AC7）——真实链路建档成功已 applyProfile(hasPetProfile: true)。
      final authProfile = ref.read(authControllerProvider).profile;
      final bool noArchive = authProfile != null && !authProfile.hasPetProfile;
      final ContentType? preset =
          (widget.preset == ContentType.growthMoment && noArchive) ? null : widget.preset;
      final ContentType initial =
          preset ?? (_hasPetProfile ? ContentType.growthMoment : ContentType.daily);
      c.setType(initial);
      if (initial == ContentType.growthMoment) {
        c.setEventDate(widget.presetEventDate ?? DateTime.now()); // F9 默认事件日期
      }
      _applyPrefill(c);
    });
  }

  /// 预填（V1.3.2 Story 4.4）：**在定好类型之后**做。文字框与 controller 必须同步写，
  /// 否则字数计与发布内容会分叉；图片直接入图片区并走既有上传进度（不 await）。
  void _applyPrefill(PublishController c) {
    final text = widget.initialText;
    if (text != null && text.isNotEmpty) {
      final clipped = text.characters.take(kMaxPostTextLength).toString();
      _textController.text = clipped;
      c.setText(clipped);
    }
    final images = widget.initialImages;
    if (images != null && images.isNotEmpty) {
      for (final bytes in images) {
        if (!c.addImage(bytes)) break; // 超 kMaxImages 丢弃
      }
      unawaited(c.uploadAll());
    }
  }

  /// 成功页分享用的宠物档案（只取 `cardToken` / 名字，best-effort）：Diary = **第一只关联宠物**（F3，
  /// 与后端 `content_posts.pet_id` 同口径）；Moment / Tips = 当前宠物（改造前口径）。
  /// V1.4.0 multi-pet Story 3.3：Diary 关联哪些宠物改由 [PublishController.petIds] 承载，不再取它。
  PetProfile? _linkedPet;

  /// 宠物 chips 的默认选中只做一次（AC1.7）：宠物列表首次拿到数据时。
  bool _petsInitialized = false;

  /// 用户亲手点过 chip（code review：里程碑预选要异步取当前宠物，期间用户的点选不得被覆盖）。
  bool _userTouchedPets = false;

  /// 里程碑「去发布」所属的宠物（= 进页时 Diary Tab 的当前宠物）；回填打卡与庆祝都按它。
  int? _milestonePetId;

  /// 提交期间的「审核中」覆盖层（P-39b）：覆盖编辑表单，提交结束后跳成功/被拒页。
  bool _reviewing = false;
  PublishResultArgs? _reviewingArgs;

  /// 选图/处理中（拍照返回 → 解码压缩剥 EXIF）：网格显占位 loading，避免「拍完没反应」。
  bool _addingImage = false;

  /// 拖拽重排的**仅 UI 层**预览态（V1.3.0 Story 4.1 · U2「其余格实时让位」）。
  ///
  /// 🔴 这里只放「正在拖哪张、悬在第几位」两个下标，**不是第二份顺序**：
  /// 预览顺序每次 build 由 controller.items + 这两个下标现算，松手才调
  /// `reorderImage` 写回 controller —— 顺序的唯一事实源仍是 controller（AC3）。
  int? _dragFrom;
  int? _hoverIndex;

  /// 网格的锚点：拖拽途中把指针的全局坐标换算成「悬在第几格」。
  final GlobalKey _gridKey = GlobalKey();

  /// 光标前正在打的那个 `@查询词`；非 null 即 @ 浮层可见
  /// （V1.3.0 batch-b1 Story 3.2 · AC1）。
  ///
  /// ⚠️ 只有"现在该不该弹"这一个瞬时状态放在页面上；**已插入的 @ 绑定住在
  /// `PublishController.mentions`**（与正文同生同死，见那里的注释）。
  MentionQuery? _mentionQuery;

  /// 每次输入重算 @ 触发态（AC1：输入「@」即弹）。
  void _syncMentionQuery() {
    final next = MentionDraft.queryAt(_textController.text, _textController.selection.baseOffset);
    if (next?.start != _mentionQuery?.start || next?.keyword != _mentionQuery?.keyword) {
      setState(() => _mentionQuery = next);
    }
  }

  /// 选中候选人：正文里插 `@昵称`，控制器里记 userId（AC4）。
  void _onMentionSelected(PublishController controller, MentionCandidate candidate) {
    final query = _mentionQuery;
    if (query == null) return;
    final l10n = AppLocalizations.of(context);
    // 🔴 插入是直接写 controller.value，**绕过 maxLength 的格式化器** ——
    // 不先自己拦一次，1000 字的正文插一个长昵称就超了上限，而这一页的表现是
    // 「发布按钮悄悄变灰」，用户完全不知道为什么。
    if (MentionDraft.textAfterInsert(_textController.text, query, candidate.nickname)
            .characters
            .length >
        kMaxPostTextLength) {
      showAppToast(context, l10n.publishTextLimitReached);
      setState(() => _mentionQuery = null);
      return;
    }
    final inserted = controller.mentions
        .insert(_textController.text, query, candidate.userId, candidate.nickname);
    if (inserted == null) {
      // AC5：达上限不能再插入，并给出提示。
      showAppToast(context, l10n.mentionLimitReached);
      setState(() => _mentionQuery = null);
      return;
    }
    _textController.value = TextEditingValue(
      text: inserted.text,
      selection: TextSelection.collapsed(offset: inserted.cursor),
    );
    controller.setText(inserted.text);
    // Story 3.5 AC4：**真的插进去了**才报（被上限 / 字数拦住的那两条 return 都在上面）。
    // ⚠️ 字面量写法是给埋点守卫看的，见 MentionContext 的类注释。
    Analytics.capture('mention_inserted', {'context': MentionContext.post.wire});
    setState(() => _mentionQuery = null);
  }

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }

  bool get _hasPetProfile {
    final profile = ref.read(authControllerProvider).profile;
    return profile?.hasPetProfile ?? false;
  }

  /// 懒加载分享用的宠物档案（成功页「Bagikan buku tumbuh kembang {petName}」要 `cardToken`）。
  ///
  /// [petId] 给了 → 取这只（Diary 的第一只关联宠物，F3）；不给 → 当前宠物（Moment / Tips，改造前口径；
  /// 允许为取当前宠物发一次列表请求，冷启动直接进发布页时它可能还没被任何界面建立）。
  Future<void> _ensurePetLoaded({int? petId}) async {
    if (_linkedPet != null && (petId == null || _linkedPet!.id == petId)) return;
    final id = petId ?? await ref.read(requestPetIdProvider.future);
    final pet = await ref.read(profileRepositoryProvider).getMyProfile(petId: id);
    if (mounted) setState(() => _linkedPet = pet);
  }

  /// 宠物 chips 的可选列表：只 ACTIVE（只读不出现，AC1.1），按创建顺序（列表本身即创建顺序）。
  static List<PetListItem> _activePets(List<PetListItem> pets) =>
      [for (final p in pets) if (!p.isReadOnly) p];

  /// 宠物列表到了 → 默认选中（只做一次）/ 已选里有变只读或已删的 → 剔掉（AC1.7 / AC3.2）。
  ///
  /// 在 post-frame 里调：改控制器会 notify，不能在 build 中途做。
  Future<void> _syncPetSelection(PublishController c, List<PetListItem> pets) async {
    final active = _activePets(pets);
    final activeIds = {for (final p in active) p.id};
    if (!_petsInitialized) {
      _petsInitialized = true;
      final want = <int>{};
      if (widget.initialPetIds != null) {
        want.addAll(widget.initialPetIds!);
      } else if (widget.milestoneCode != null) {
        // 里程碑属于 Diary Tab 当前宠物（后端会校验帖子关联了它）；它若只读则不预选。
        int? current;
        try {
          current = await ref.read(effectivePetIdProvider.future);
        } catch (_) {
          current = null; // 取不到当前宠物：不预选，用户自己点
        }
        if (!mounted) return;
        _milestonePetId = current;
        if (current != null) want.add(current);
      }
      // code review：等当前宠物期间用户已经点过 chip → 以用户为准，不再套默认预选。
      if (_userTouchedPets) return;
      var selected = [for (final p in active) if (want.contains(p.id)) p.id];
      // F4：只有 1 只正常宠物 → 自动选中（仍是可点的 chip，可取消）。
      if (selected.isEmpty && active.length == 1) selected = [active.first.id];
      c.setPetIds(selected);
      return;
    }
    final kept = [for (final id in c.petIds) if (activeIds.contains(id)) id];
    if (kept.length != c.petIds.length) c.setPetIds(kept);
  }

  /// 点 chip：选中 / 取消；结果按创建顺序存（不按点选先后）。
  void _togglePet(PublishController c, List<PetListItem> active, int id) {
    _userTouchedPets = true;
    final set = {...c.petIds};
    if (!set.remove(id)) set.add(id);
    c.setPetIds([for (final p in active) if (set.contains(p.id)) p.id]);
  }

  /// 已选宠物的名字（按创建顺序）—— 只用于渲染。
  List<String> _selectedPetNames(PublishController c) {
    final pets = ref.read(petListProvider).asData?.value ?? const <PetListItem>[];
    final byId = {for (final p in pets) p.id: p.name};
    return [for (final id in c.petIds) ?byId[id]];
  }

  Future<void> _addImage(
    PublishController controller, {
    MediaSource source = MediaSource.gallery,
  }) async {
    setState(() => _addingImage = true);
    Uint8List? bytes;
    try {
      bytes = await ref
          .read(mediaUploadUseCaseProvider)
          .pickAndProcess(source: source, context: context);
    } finally {
      // 处理结束即撤占位：成功则下方 item 入列接管显示，失败/取消则恢复添加格。
      if (mounted) setState(() => _addingImage = false);
    }
    if (bytes == null) return;
    // 只有超出容差区间的图才会进露出区域页；区间内的图用户完全感知不到这一步存在。
    // 字节原样放行（不裁不重编码），超界图只多带一个露出位置 offset。
    final prepared = await prepareBatchExposure([bytes], ask: _askExposure);
    if (prepared == null || prepared.isEmpty) return; // 用户在露出区域页 ✕ / 返回 → 取消本次上传
    final p = prepared.first;
    // 即选即传：item 入列即以 pending/uploading 态渲染（自带 loading 盖层）；上传期间发布按钮置灰。
    if (controller.addImage(p.bytes, cropOffset: p.cropOffset)) {
      await controller.uploadAll();
    }
  }

  /// 打开露出区域页（V1.3.4 Story 2.3），整批超界图同页处理；只在有超界图时被调用。
  Future<ExposureResult?> _askExposure(List<ExposureImage> overflow) {
    if (!mounted) return Future<ExposureResult?>.value();
    return Navigator.of(context).push<ExposureResult>(MaterialPageRoute<ExposureResult>(
      builder: (_) => PublishExposurePage(images: overflow),
    ));
  }

  Future<void> _addGalleryImages(PublishController controller) async {
    final remaining = kMaxImages - controller.items.length;
    if (remaining <= 0) return;
    setState(() => _addingImage = true);
    List<Uint8List> images;
    try {
      images = await ref
          .read(mediaUploadUseCaseProvider)
          .pickMultiAndProcess(limit: remaining, context: context);
    } finally {
      if (mounted) setState(() => _addingImage = false);
    }
    if (images.isEmpty) return;
    // 🛡 整批超界图一次进露出区域页；区间内的图原样入列、offset 为 null。
    final prepared = await prepareBatchExposure(images, ask: _askExposure);
    if (prepared == null) return; // 用户在露出区域页 ✕ / 返回 → 整批取消（区间内的也不加）
    for (final p in prepared) {
      controller.addImage(p.bytes, cropOffset: p.cropOffset);
    }
    await controller.uploadAll();
  }

  Future<void> _publish(
    PublishController controller,
    AppLocalizations l10n,
  ) async {
    Analytics.capture('content_publish_submitted', {
      'type': controller.type.name,
    });
    // V1.4.0 multi-pet Story 3.3：Diary 必带至少 1 只宠物（按钮已按 canPublish 置灰，这里只是兜底）。
    final growth = controller.type == ContentType.growthMoment;
    if (growth && controller.petIds.isEmpty) return;
    final key = 'post-${DateTime.now().microsecondsSinceEpoch}';
    // 分享飞轮（留存手册抓手 2）：成功页要**当场**给可分享的成长册链接，
    // 所以这里 best-effort 把档案取上来拿 cardToken。Diary 取**第一只关联宠物**（F3）；
    // Moment / Tips 取当前宠物（改造前口径）。失败/无档案不阻断发布——代价只是成功页少一个分享按钮。
    if (growth || _hasPetProfile) {
      try {
        await _ensurePetLoaded(petId: growth ? controller.petIds.first : null);
      } catch (_) {
        // 静默：分享按钮是加分项，不能让它挡住发布。
      }
      if (!mounted) return;
    }
    final args = _buildArgs(controller, l10n);
    // 进入「审核中」覆盖层（P-39b）。
    setState(() {
      _reviewing = true;
      _reviewingArgs = args;
    });
    final started = DateTime.now();
    int? id;
    String? blockSlug; // content-text-blocked / content-image-blocked（F10 审核拦截）
    int? readOnlyPetId; // 409 pet-read-only：选中的宠物在提交瞬间变只读（AC3.2）
    var readOnly = false;
    try {
      id = await controller.publish(
          idempotencyKey: key, placeCheckinToken: widget.placeCheckinToken);
    } on DioException catch (e) {
      final pd = ProblemDetail.fromDioException(e);
      final slug = pd?.typeSlug;
      if (slug == 'content-text-blocked' || slug == 'content-image-blocked') {
        blockSlug = slug;
      } else if (slug == kPetReadOnlySlug) {
        readOnly = true;
        readOnlyPetId = (pd?.extensions['petId'] as num?)?.toInt();
      }
    }
    // 「审核中」至少展示 ~900ms，避免一闪而过（faithful verifying affordance）。
    final elapsed = DateTime.now().difference(started);
    if (elapsed < const Duration(milliseconds: 900)) {
      await Future<void>.delayed(const Duration(milliseconds: 900) - elapsed);
    }
    if (!mounted) return;

    if (id != null) {
      // V1.4.0 multi-pet Story 3.3 · AC7（PRD §4）：Diary 发布成功报 `diary_published`，属性只有关联宠物数；
      // 🛡 不带宠物名 / petId / 正文。Moment / Tips 不报。
      if (controller.type == ContentType.growthMoment) {
        Analytics.capture('diary_published', {'pet_count': controller.petIds.length});
      }
      // V1.3.2 Story 1.5 · AC6：打卡后顺手发帖成功。place_id 键装 token 值（沿用 place_detail_viewed 约定）；
      // 🛡 不带宠物名 / 正文 / 坐标。
      final checkinPlace = widget.placeCheckinPlace;
      if (widget.placeCheckinToken != null && checkinPlace != null) {
        Analytics.capture('place_checkin_post_created', {
          'place_id': checkinPlace.placeToken,
          'place_type': ?checkinPlace.placeType?.api,
        });
      }
      ref.invalidate(feedProvider);
      ref.invalidate(myPostsProvider);
      ref.invalidate(meCountsProvider); // V1.3.4：我的页四格帖子数随发帖变化
      // 成长日历发帖 → 同步刷新成长档案时间线与统计，避免需手动下拉刷新才显示（F9）。
      // V1.4.0 multi-pet Story 3.3 · AC3.4：一条 Diary 进**每只关联宠物**的时间线 / 统计 / 日历 ——
      // 按「整组失效重取」（AD-14），整族失效即覆盖每只关联宠物的 petId 键。
      if (controller.type == ContentType.growthMoment) {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
        ref.invalidate(calendarMonthProvider);
        ref.invalidate(dayDetailProvider);
        ref.invalidate(petListProvider); // V1.4.0 multi-pet：「我的」页 diary 角标取宠物列表的 diaryCount
      }
      // 里程碑「去发布」回填（Story 8.4）：仍为成长日历类型 → 以新内容 id 自动打卡完成（best-effort）。
      MilestoneItem? completedMilestone;
      // code review：里程碑属于进页时的当前宠物；用户若把它取消勾选，这条 Diary 就没关联它 ——
      // 后端会拒（静默失败）。保守口径：只在它仍在所选里时回填，否则不回填（用户可回里程碑页手动关联）。
      final milestonePet = _milestonePetId;
      if (widget.milestoneCode != null &&
          controller.type == ContentType.growthMoment &&
          // 当前宠物没取到（null）→ 按改造前口径不带 petId（后端默认宠物）。
          (milestonePet == null || controller.petIds.contains(milestonePet))) {
        try {
          completedMilestone = await ref
              .read(milestoneRepositoryProvider)
              .checkIn(widget.milestoneCode!, id, petId: milestonePet);
        } catch (_) {
          // 回填失败静默：用户可回里程碑页手动「已打卡」关联该内容。
        }
        ref.invalidate(milestoneListProvider);
      }
      if (!mounted) return;
      // 回填成功 → 先弹 P-35 解锁庆祝（与「去打卡」路径一致，修复「去发布」无完成弹框）。
      if (completedMilestone != null) {
        final done = completedMilestone;
        final locale = Localizations.localeOf(context);
        final listPetId = _milestonePetId;
        final listData =
            listPetId == null ? null : ref.read(milestoneListProvider(listPetId)).asData?.value;
        final petName = listData?.petName ?? '';
        final collection = listData == null
            ? const <MilestoneItem>[]
            : [
                for (final g in listData.groups)
                  for (final it in g.items) it.code == done.code ? done : it,
              ];
        final shareText = l10n.milestoneShareText(
          localizedMilestoneTitle(done.code, locale),
        );
        final router = GoRouter.maybeOf(context);
        // 弹出前先记本地：下面那个列表页可能在庆祝期间被重拉并自行补弹。
        markMilestonesCelebrating(ref, [done.code]);
        await showMilestoneCelebration(
          context,
          done,
          petName: petName,
          path: MilestoneCelebrationPath.instant,
          collection: collection,
          onShare: () => shareMilestoneWithLink(
            ref,
            item: done,
            locale: locale,
            petName: petName,
            shareText: shareText,
            collection: collection,
            petId: listPetId, // 与打卡同一只（进页时的当前宠物），不随之后切换的当前宠物走
          ),
          // onSeeAll 省略：庆祝关闭后统一在下方先关 sheet 再跳列表（否则 sheet 挡住跳转）。
        );
        // 回报本次庆祝（best-effort，不 await）——「去发布」也是一次真实庆祝，不报的话
        // 这条会在下面跳到的列表页被当成"未庆祝"再弹一遍。
        reportMilestoneCelebrated(ref, [done.code]);
        if (!mounted) return;
        // 里程碑路径：庆祝即成功反馈 → 关闭发布 sheet → 跳回里程碑列表（不叠加通用「发布成功」页）。
        Navigator.of(context).pop();
        // 🔴 把「刚庆祝过」的 code 随导航带过去（AD-A2.3c）。上面那次回报是**异步**的，
        //    列表页极可能在它落库前就读到 celebratedAt == null → 两秒内连弹两次同一条。
        //    抑制必须落在客户端、**不依赖回报是否已落库**；也不得把回报改成同步来绕开
        //    （那会让发布流程卡在一个可失败的写上）。
        // push 而非 go：保留发布前页作前驱，里程碑页 canPop→iOS 边缘侧滑可返回（修 20260701-190）。
        // pushReplacement：本 sheet 开在 /publish 空白着陆页之上，普通 push 会把着陆页压在
        // 里程碑列表下面，返回时再白屏一次（bug 20260922-520）。替换掉着陆页，前驱仍是发布前页。
        router?.pushReplacement(DeepLinkRoutes.milestoneList,
            extra: <String>{done.code});
        return;
      }
      Navigator.of(context).pop(); // 关闭发布 sheet
      // bug 20261012-586：带上新帖 id，成功页「去 Feed 看」据此定位。
      context.push('/publish/done', extra: args.withPostId(id)); // → P-39 发布成功
    } else if (blockSlug != null) {
      // AC8（F10）：审核拦截 → P-39c 内容被拒（整页 + 拒因）。
      final reasons = <String>[
        if (blockSlug == 'content-text-blocked') l10n.publishRejectedReasonText,
        if (blockSlug == 'content-image-blocked')
          l10n.publishRejectedReasonImage,
      ];
      Navigator.of(context).pop();
      context.push(
        '/publish/rejected',
        extra: args.withReasons(reasons),
      ); // → P-39c 被拒
    } else if (readOnly) {
      // AC3.2：选中的宠物在提交瞬间变只读（409 pet-read-only）→ 复用 2.4 的只读提示文案 + 刷新宠物列表；
      // 列表回来后 [_syncPetSelection] 把它从 chips 里去掉（只读不出现）。不进成功页、草稿保留。
      final pets = ref.read(petListProvider).asData?.value ?? const <PetListItem>[];
      final name = pets.where((p) => p.id == readOnlyPetId).map((p) => p.name).firstOrNull ?? '';
      if (readOnlyPetId != null) {
        controller.setPetIds([for (final x in controller.petIds) if (x != readOnlyPetId) x]);
      }
      ref.invalidate(petListProvider);
      setState(() => _reviewing = false);
      _toast(l10n.petReadonlyBlockTitle(name));
    } else {
      // 其它失败（网络/图片上传）：退出审核中覆盖层，留在编辑页保留草稿，提示重试。
      setState(() => _reviewing = false);
      _toast(l10n.publishFailed);
    }
  }

  /// 类型展示文案（与发布 segment 一致）。
  String _typeLabel(ContentType t, AppLocalizations l10n) => switch (t) {
    ContentType.daily => l10n.publishSegmentDaily,
    ContentType.growthMoment => l10n.publishSegmentGrowth,
    ContentType.knowledge => l10n.publishSegmentKnowledge,
  };

  /// 构建结果三屏预览参数（文本截断 + 类型 + 图片数 + 宠物 emoji）。
  PublishResultArgs _buildArgs(
    PublishController controller,
    AppLocalizations l10n,
  ) {
    final text = controller.text.trim();
    final excerpt = text.length > 48 ? '${text.substring(0, 48)}…' : text;
    return PublishResultArgs(
      excerpt: excerpt,
      typeLabel: _typeLabel(controller.type, l10n),
      photoCount: controller.items.length,
      // 私密保存（Diary 关掉同步开关）→ 成功页走私密文案/CTA（PR#34 finding #11）。
      isPrivate: !controller.isSharing,
      // 抓手 2：有档案才有成长册可分享；为空时成功页不渲染分享 CTA。
      cardToken: _linkedPet?.cardToken,
      petName: _linkedPet?.name,
    );
  }

  void _toast(String msg) {
    showAppToast(context, msg, duration: const Duration(seconds: 3));
  }

  void _onGrowthBlocked(AppLocalizations l10n) {
    showAppToast(context, l10n.publishGrowthNeedsProfile);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final controller = ref.watch(publishControllerProvider);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) => _body(context, l10n, controller),
    );
  }

  Widget _body(
    BuildContext context,
    AppLocalizations l10n,
    PublishController controller,
  ) {
    // 提交期间：整屏「审核中」覆盖层（P-39b），覆盖编辑表单。
    if (_reviewing && _reviewingArgs != null) {
      return PublishReviewingView(args: _reviewingArgs!);
    }
    final growthSelected = controller.type == ContentType.growthMoment;
    // 键盘弹出时把整个内容区上推键盘高度（固定高度 sheet 走标准件 KeyboardInset，带 120ms 动画 +
    // 纳入 keyboard_safe_area 契约测试，bug 20260721-321），使滚动视口底边落在键盘之上 →
    // 被键盘遮住的输入框被判定为「视口外」，EditableText 自动 ensureVisible 滚上来。
    return KeyboardInset(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // —— 顶部：把手 + Batal / 标题 / Bagikan ——
          Container(
            padding: const EdgeInsets.fromLTRB(20, 10, 20, 8),
            child: Column(
              children: [
                Container(
                  width: 40,
                  height: 5,
                  margin: const EdgeInsets.only(bottom: 14),
                  decoration: BoxDecoration(
                    color: AppColors.line,
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
                Row(
                  children: [
                    // 原型 closebtn：34px 圆钮 + × 图标。
                    Semantics(
                      button: true,
                      label: l10n.publishCancel,
                      child: InkWell(
                        key: const ValueKey('publishClose'),
                        onTap: () => Navigator.of(context).pop(),
                        customBorder: const CircleBorder(),
                        child: Container(
                          width: 34,
                          height: 34,
                          alignment: Alignment.center,
                          // 原型 closebtn：中性浅灰底 bg-muted #EFEDF3。
                          decoration: const BoxDecoration(
                            color: Color(0xFFEFEDF3),
                            shape: BoxShape.circle,
                          ),
                          child: const Icon(
                            Icons.close_rounded,
                            size: 18,
                            color: AppColors.ink2,
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: Text(
                        l10n.publishComposeTitle,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    // 原型 pubbtn：扁平紫钮（启用 #845EC9）/ off=中性灰 #B6B6B6。
                    FilledButton(
                      key: const ValueKey('publishSubmit'),
                      onPressed: controller.canPublish
                          ? () => _publish(controller, l10n)
                          : null,
                      style: FilledButton.styleFrom(
                        backgroundColor: AppColors.mint,
                        disabledBackgroundColor: const Color(0xFFB6B6B6),
                        foregroundColor: AppColors.onAccent,
                        // 原型 .pubbtn 始终白字（.off 只改底色不改字色）。
                        disabledForegroundColor: AppColors.onAccent,
                        // 原型 pubbtn 启用：紫色柔阴影 0 4px 12px rgba(132,94,201,.30)；off 无阴影。
                        elevation: controller.canPublish ? 4 : 0,
                        shadowColor: AppColors.mint.withValues(alpha: 0.30),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 15,
                          vertical: 9,
                        ),
                        minimumSize: const Size(0, 0),
                        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                        textStyle: const TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      child: controller.publishing
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          // AC2：同步开关关 → 主按钮由「分享」变「保存」（PRD 未提，UI 稿 P2 独有，
                          // 靠 widget test 防回归）。
                          : Text(controller.isSharing
                              ? l10n.publishButton
                              : l10n.publishButtonSave),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 36),
              children: [
                // 原型 p-create 顺序：类型 chips → petsel(成长) → 照片 → 文字 → 事件日期 → 公开提示。
                _segments(controller, l10n),
                if (growthSelected) ...[
                  const SizedBox(height: 14),
                  _petChipsRow(controller, l10n),
                  _syncSwitch(controller, l10n),
                ] else ...[
                  const SizedBox(height: 12),
                  _publicHint(l10n),
                ],
                const SizedBox(height: 16),
                _imageRow(controller, l10n),
                const SizedBox(height: 14),
                // —— 文字 ——（V1.4.0 稿 F1 `.ptxt / .pcount`：**无外框**，正文直接铺在页面上，右下弱色字数；
                // L2 第二轮起取代原型 textarea-wrap 的 1.5px 外框。Diary 时下方紧跟带上分隔线的事件日期行）
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      TextField(
                        key: const ValueKey('publishText'),
                        controller: _textController,
                        minLines: 3,
                        maxLines: 6,
                        maxLength: kMaxPostTextLength,
                        onChanged: (v) {
                          controller.setText(v);
                          // Story 3.2 AC1：输入「@」弹出用户选择器浮层。
                          _syncMentionQuery();
                        },
                        style: const TextStyle(
                          fontSize: 14,
                          color: AppColors.ink,
                          height: 1.6,
                        ),
                        decoration: InputDecoration(
                          isCollapsed: true,
                          hintText: _hint(controller, l10n),
                          hintStyle: const TextStyle(
                            color: AppColors.textTertiary,
                            fontSize: 14,
                            height: 1.6,
                          ),
                          border: InputBorder.none,
                          counterText: '', // 隐藏默认计数器，改放容器内右下
                        ),
                      ),
                      // AC1：输入「@」弹出用户选择器浮层，紧贴正文框下沿展开。
                      if (_mentionQuery != null) ...[
                        const SizedBox(height: 7),
                        MentionPicker(
                          keyword: _mentionQuery!.keyword,
                          onSelected: (c) => _onMentionSelected(controller, c),
                        ),
                      ],
                      const SizedBox(height: 7),
                      // 原型 charcount：框内右下「已用 / 总数」，弱色 11px。
                      Text(
                        '${_textController.text.length} / $kMaxPostTextLength',
                        textAlign: TextAlign.right,
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ),
                // V1.3.2 Story 1.5 · AC3：打卡场所条（只读、不可删、不可点）—— 说明「这条帖子会挂在这次打卡上」。
                // 切类型不影响（AD-10 任何类型均可）；普通入口不出。
                if (widget.placeCheckinToken != null && widget.placeCheckinPlace != null) ...[
                  const SizedBox(height: 12),
                  _CheckinPlaceStrip(place: widget.placeCheckinPlace!),
                ],
                if (growthSelected) ...[
                  const SizedBox(height: 6),
                  _eventDateRow(controller, l10n),
                ],
                if (controller.hasFailed) ...[
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      TextButton.icon(
                        key: const ValueKey('publishRetry'),
                        icon: const Icon(Icons.refresh, color: AppColors.mint700),
                        label: Text(
                          l10n.publishRetry,
                          style: const TextStyle(color: AppColors.mint700),
                        ),
                        onPressed: () => controller.retryFailed(),
                      ),
                      // V1.3.0 Story 4.1 · AC5：重试沿用同一份顺序，**不重新读表单状态** ——
                      // 想改顺序就得从这里整体取消回到可编辑态。
                      // 没有这个出口的话，一次部分失败会把顺序永久锁死。
                      if (!controller.canReorder)
                        TextButton(
                          key: const ValueKey('publishCancelUpload'),
                          onPressed: controller.cancelUploadSession,
                          child: Text(
                            l10n.publishCancelUpload,
                            style: const TextStyle(color: AppColors.textSecondary),
                          ),
                        ),
                    ],
                  ),
                ],
                const SizedBox(height: 16),
                // 原型底部：居中可见性提示，**跟随实际可见范围**（2026-08-05 用户实机反馈）。
                // 此前写死「所有人可见」：Diary 关掉同步开关后，这句与开关副标题「没人看得到」
                // 在同屏互相打脸。判定统一走 [PublishController.isSharing]（Moment/Tips 恒公开，
                // Diary 看开关）—— ⚠️ 别在这里另写一遍类型判断，两处判定迟早走歧。
                Center(
                  child: Text(
                    controller.isSharing ? l10n.publishPublicNotice : l10n.publishPrivateNotice,
                    key: const ValueKey('publishVisibilityNotice'),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: AppColors.textTertiary,
                      fontSize: 12,
                      height: 1.6,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  String _hint(PublishController c, AppLocalizations l10n) {
    switch (c.type) {
      case ContentType.growthMoment:
        // V1.4.0 multi-pet Story 3.3：F1 未选 → 「Tulis catatan Diary...」；F2 / F4 已选 → 「…{拼接名}...」。
        final names = _selectedPetNames(c);
        return names.isEmpty
            ? l10n.diaryPetsComposeHint
            : l10n.publishHintGrowth(formatPetNames(l10n, names));
      case ContentType.knowledge:
        return l10n.publishHintKnowledge;
      default:
        return l10n.publishHintDaily;
    }
  }

  /// 类型标签：原型横向 pill chips（紫底圆角 9999 选中态，#E6E6E6 边框未选）。
  /// T-8 `publish_page_content_type_selected`（Story 6.1）：类型选择的实际分布 + 是否等于默认值
  /// —— 用来验证「默认 Diary」这个改动到底改变了多少发布构成。
  void _reportTypeSelected(ContentType type) {
    final defaultType = _hasPetProfile ? ContentType.growthMoment : ContentType.daily;
    Analytics.capture('publish_page_content_type_selected', {
      'type': type.wire,
      'is_default': type == defaultType,
      'has_pet_profile': _hasPetProfile,
    });
  }

  Widget _segments(PublishController controller, AppLocalizations l10n) {
    return SizedBox(
      height: 34,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          // Story 4.2 · AC5：顺序改为 **Diary / Moment / Tips**，与默认选中项及 FR-83 的术语一致
          // （原 V1.0.0 顺序是 Cerita Harian / Tips / 成长末位）。
          _growthChip(l10n, controller),
          const SizedBox(width: 7),
          _segChip(
            l10n.publishSegmentDaily,
            ContentType.daily,
            controller,
            'seg_${ContentType.daily.wire}',
          ),
          const SizedBox(width: 7),
          _segChip(
            l10n.publishSegmentKnowledge,
            ContentType.knowledge,
            controller,
            'seg_${ContentType.knowledge.wire}',
          ),
        ],
      ),
    );
  }

  Widget _segChip(
    String label,
    ContentType type,
    PublishController controller,
    String key,
  ) {
    return _TypeChip(
      keyValue: key,
      label: label,
      selected: controller.type == type,
      enabled: true,
      onTap: () {
        _reportTypeSelected(type);
        controller.setType(type);
      },
    );
  }

  Widget _growthChip(AppLocalizations l10n, PublishController controller) {
    final enabled = _hasPetProfile;
    return _TypeChip(
      keyValue: 'seg_GROWTH_MOMENT',
      label: '${l10n.publishSegmentGrowth} 🌟',
      selected: controller.type == ContentType.growthMoment,
      enabled: enabled,
      onTap: enabled
          ? () {
              _reportTypeSelected(ContentType.growthMoment);
              controller.setType(ContentType.growthMoment);
              if (controller.eventDate == null) {
                controller.setEventDate(
                  widget.presetEventDate ?? DateTime.now(),
                ); // F9 默认
              }
            }
          : () => _onGrowthBlocked(l10n),
    );
  }

  /// 「同步到 Moment」开关（Story 4.2 · AC1/AC2 · UI 稿 P1/P2）。**仅 Diary 渲染，默认开启**。
  ///
  /// 关掉 = 这条只进自己的成长档案（`visibility=PRIVATE`），不进 Socialy / 话题聚合 / 任何公开位。
  /// ⚠️ 发布后**不可更改**（FR-83 AC7）：本页是唯一的设置点，详情页 / 我的发布 / 编辑流程都不得加
  /// 「转为私密」入口 —— 让内容不再被他人看到的唯一途径是删除该条。
  Widget _syncSwitch(PublishController controller, AppLocalizations l10n) {
    final on = controller.syncToMoment;
    // V1.4.0 稿 F1 `.syncr`：白底 + 1px #F0F0F0 描边、r12、内边距 9/12（L2 第二轮起取代淡紫填充底）。
    return Container(
      key: const ValueKey('publishSyncSwitch'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.line2),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.publishSyncTitle,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink)),
                const SizedBox(height: 2),
                Text(on ? l10n.publishSyncSub : l10n.publishSyncOffSub,
                    style: const TextStyle(fontSize: 11, height: 1.3, color: AppColors.ink2)),
              ],
            ),
          ),
          const SizedBox(width: 8),
          // 稿 `.sw` 38×22 小开关：Material Switch 等比缩到 0.75（布局占位不变）。
          Transform.scale(
            scale: 0.75,
            child: Switch(
              key: const ValueKey('publishSyncToggle'),
              value: on,
              activeThumbColor: AppColors.onAccent,
              activeTrackColor: AppColors.mint,
              onChanged: (value) {
                // T-9 publish_page_sync_to_moment_toggled：**本版本最关键的产品假设验证** ——
                // 到底有多少人真的想把 Diary 只留给自己。属性只记开关值，不记内容。
                Analytics.capture('publish_page_sync_to_moment_toggled', {'enabled': value});
                controller.setSyncToMoment(value);
              },
            ),
          ),
        ],
      ),
    );
  }

  /// Moment / Tips 的公开提示（AC4）：这两类本就公开进 Feed，**不渲染同步开关**。
  Widget _publicHint(AppLocalizations l10n) => Row(
        key: const ValueKey('publishPublicHint'),
        children: [
          const Icon(Icons.public, size: 14, color: AppColors.muted),
          const SizedBox(width: 6),
          Expanded(
            child: Text(l10n.publishPublicHint,
                style: const TextStyle(fontSize: 11.5, color: AppColors.muted)),
          ),
        ],
      );

  /// 关联宠物 chips 行（V1.4.0 multi-pet Story 3.3 · AC1 · UI 稿 F1 / F2 / F4 `.forrow` / `.pchip`）。
  ///
  /// 位置不变（类型 chips 之后、同步开关之前）；可选 = 宠物列表里 ACTIVE 的（只读不出现），按创建顺序。
  /// 加载中 → 同高占位；失败 → 行内「重试」（不阻塞 Moment / Tips）；无宠物 → 不出这一行（维持现状）。
  Widget _petChipsRow(PublishController controller, AppLocalizations l10n) {
    final petsAsync = ref.watch(petListProvider);
    final pets = petsAsync.asData?.value;
    if (pets != null) {
      final needsSync = !_petsInitialized ||
          controller.petIds.any((id) => !pets.any((p) => p.id == id && !p.isReadOnly));
      if (needsSync) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _syncPetSelection(controller, pets);
        });
      }
    }
    Widget row(List<Widget> children, {Key? key}) => Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Container(
            key: key,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.mintTint2, // 原型 forrow 紫浅底
              borderRadius: BorderRadius.circular(11),
            ),
            child: Wrap(
              spacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Text(
                  l10n.publishForLabel,
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textSecondary,
                  ),
                ),
                ...children,
              ],
            ),
          ),
        );
    if (pets == null) {
      if (petsAsync.hasError && !petsAsync.isLoading) {
        return row([
          ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 44),
            child: TextButton(
              key: const ValueKey('publishPetChipsRetry'),
              onPressed: () => ref.invalidate(petListProvider),
              style: TextButton.styleFrom(
                foregroundColor: AppColors.mint,
                padding: const EdgeInsets.symmetric(horizontal: 4),
                textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
              ),
              child: Text(l10n.commonRetry),
            ),
          ),
        ], key: const ValueKey('publishPetChips'));
      }
      // 加载中：与 chips 行同高的占位（不闪「无宠物」）。
      return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Container(
          key: const ValueKey('publishPetChipsLoading'),
          height: 60,
          decoration: BoxDecoration(
            color: AppColors.mintTint2,
            borderRadius: BorderRadius.circular(11),
          ),
        ),
      );
    }
    final active = _activePets(pets);
    if (active.isEmpty) return const SizedBox.shrink(); // 无宠物：维持现状（Diary 段灰置 + 建档提示）
    final selected = controller.petIds.toSet();
    return row([
      for (final p in active)
        _PetChip(
          key: ValueKey('publishPetChip-${p.id}'),
          pet: p,
          selected: selected.contains(p.id),
          onTap: () => _togglePet(controller, active, p.id),
        ),
    ], key: const ValueKey('publishPetChips'));
  }

  /// 图片来源选择（原型「Tambah」单入口 → 相机 / 相册）。
  Future<void> _pickImageSource(
    PublishController controller,
    AppLocalizations l10n,
  ) async {
    final source = await showModalBottomSheet<MediaSource>(
      context: context,
      backgroundColor: AppColors.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              key: const ValueKey('publishPickCamera'),
              leading: const Icon(
                Icons.photo_camera_outlined,
                color: AppColors.mint,
              ),
              title: Text(l10n.publishCamera),
              onTap: () => Navigator.of(ctx).pop(MediaSource.camera),
            ),
            ListTile(
              key: const ValueKey('publishPickGallery'),
              leading: const Icon(
                Icons.photo_library_outlined,
                color: AppColors.mint,
              ),
              title: Text(l10n.publishGallery),
              onTap: () => Navigator.of(ctx).pop(MediaSource.gallery),
            ),
          ],
        ),
      ),
    );
    // 图片来源选择（2026-08-20 用户要求）：相机与相册是两条成本完全不同的路 ——
    // 相册是「我已经有照片了」，相机是「我现在为发帖专门拍一张」。后者发布意愿更强，
    // 但也更容易在拍摄那一步流失。一个事件 + source 属性（不是两个事件），
    // 与同页 publish_page_content_type_selected 形状一致，看板可直接对比占比。
    //
    // ⚠️ 报在这里（sheet 已返回选择、真正去拍/去选**之前**）：它记的是「用户选了哪条路」。
    //    挪到取图成功之后会变成「成功率」事件，分母口径就不是这个了 ——
    //    而"选了相机却没拍成"恰恰是想观察的流失。
    // ⚠️ source == null（点遮罩关掉 sheet）**不报**：否则分母会混进"打开又关掉"的人。
    if (source != null) {
      await Analytics.capture(
        'publish_page_image_source_selected',
        {'source': source.name},
      );
    }
    if (source == MediaSource.gallery) {
      await _addGalleryImages(controller);
    } else if (source != null) {
      await _addImage(controller, source: source);
    }
    // 选图/拍照返回后不自动弹键盘：系统会把焦点还给此前持焦的文本框（Android 从相册
    // Activity 回来尤甚），这里显式收掉——键盘只在用户自己点输入框时出现。
    if (mounted) FocusManager.instance.primaryFocus?.unfocus();
  }

  /// 成长日历事件日期字段（F9）：禁选未来，默认今天/格子日期。点击开日期选择器。
  Widget _eventDateRow(PublishController controller, AppLocalizations l10n) {
    final date = controller.eventDate ?? DateTime.now();
    // V1.4.0 稿 F1 `.pdate`：上分隔线 1px #F0F0F0 + 上下 10 留白，一行弱色字「Tanggal kejadian: 13 Okt 2026」；
    // 整行仍可点开日历（L2 第二轮起取代原型 daterow 的紫底描边框 + 图标 + chevron）。
    return GestureDetector(
      key: const ValueKey('publishEventDate'),
      behavior: HitTestBehavior.opaque,
      onTap: () => _pickEventDate(controller),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: const BoxDecoration(
          border: Border(top: BorderSide(color: AppColors.line2)),
        ),
        child: Text.rich(
          TextSpan(
            text: '${l10n.publishEventDate}: ',
            style: const TextStyle(fontSize: 12.5, color: AppColors.ink2),
            children: [
              TextSpan(text: formatDayMonthYear(context, date)),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickEventDate(PublishController controller) async {
    final today = DateTime.now();
    // bug 20260623-047：统一 date_picker_plus 干净日历风格（点某天即选中并关闭）。
    final picked = await showDatePickerDialog(
      context: context,
      minDate: DateTime(today.year - 30),
      maxDate: DateTime(today.year, today.month, today.day), // 禁选未来（F9）
      selectedDate: controller.eventDate,
      displayedDate: controller.eventDate ?? today,
    );
    if (picked != null) controller.setEventDate(picked);
  }

  /// 照片区：原型 3 列网格，首格为虚线「Tambah」添加格，其后为缩略图（带上传态/删除）。
  Widget _imageRow(PublishController controller, AppLocalizations l10n) {
    final items = controller.items;
    final showAdd = items.length < kMaxImages;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          l10n.publishPhotoLabel,
          style: const TextStyle(
            fontSize: 11,
            fontWeight: FontWeight.w600,
            color: AppColors.textSecondary,
          ),
        ),
        const SizedBox(height: 8),
        // 用 LayoutBuilder 量一格多宽：拖起来那张要按真实格子尺寸放大显示，
        // 写死一个数会在不同屏宽上飘。
        LayoutBuilder(
          builder: (context, box) {
            const double gap = 5;
            final double cell = (box.maxWidth - gap * 2) / 3;
            // 缩略图前面的固定格（添加格 / 处理中占位格）数：缩略图从这之后排。
            final int lead = (showAdd ? 1 : 0) + (_addingImage ? 1 : 0);
            final int slots = lead + items.length;
            final int rows = (slots + 2) ~/ 3;
            final double height = rows == 0 ? 0 : rows * cell + (rows - 1) * gap;
            Offset slotAt(int slot) =>
                Offset((slot % 3) * (cell + gap), (slot ~/ 3) * (cell + gap));
            final List<int> preview = _previewOrder(controller);
            // 不用 GridView：GridView 换序是瞬移，做不出「其余格滑过去让位」。
            // 手排 Stack + AnimatedPositioned，按 item 身份打 key，位置一变就补间过去。
            final grid = SizedBox(
              key: _gridKey,
              height: height,
              child: Stack(
                children: [
                  if (showAdd)
                    Positioned(
                      left: 0,
                      top: 0,
                      width: cell,
                      height: cell,
                      child: _addCell(controller, l10n),
                    ),
                  if (_addingImage)
                    Positioned(
                      left: slotAt(showAdd ? 1 : 0).dx,
                      top: slotAt(showAdd ? 1 : 0).dy,
                      width: cell,
                      height: cell,
                      child: _processingCell(),
                    ),
                  for (int p = 0; p < preview.length; p++)
                    AnimatedPositioned(
                      key: ObjectKey(items[preview[p]]),
                      duration: const Duration(milliseconds: 180),
                      curve: Curves.easeOutCubic,
                      left: slotAt(lead + p).dx,
                      top: slotAt(lead + p).dy,
                      width: cell,
                      height: cell,
                      child: _thumb(controller, preview[p], l10n, cell),
                    ),
                ],
              ),
            );
            if (!controller.canReorder) return grid;
            // 整个网格一个 DragTarget：悬停位置按指针坐标算，不靠「悬在哪一格上」——
            // 格子在让位动画里会从指针下滑走，按格子判定会来回抖。
            return DragTarget<int>(
              // 🔴 无条件接：Draggable 构造拖影时就做了第一次命中判定，那时 onDragStarted
              // 还没回调、_dragFrom 仍是 null —— 在这里判它，整次拖拽都会被拒收、松手不落。
              onWillAcceptWithDetails: (_) => true,
              onMove: (d) => _updateHover(d.offset, lead, cell, gap, items.length),
              // 拖出网格：预览复原（空位回到原处），此时松手也不落 controller。
              onLeave: (_) {
                if (_hoverIndex != null) setState(() => _hoverIndex = null);
              },
              onAcceptWithDetails: (d) {
                final int? to = _hoverIndex;
                _clearDragPreview();
                // 🔴 松手这一刻才写 controller —— 顺序的唯一事实源（AC3）。
                if (to != null) controller.reorderImage(d.data, to);
              },
              builder: (context, candidate, rejected) => grid,
            );
          },
        ),
      ],
    );
  }

  /// 当前是否处于有效拖拽中。拖拽途中若因上传开始而失去重排资格，Draggable 会被整个撤掉、
  /// 其 onDragEnd 不再回调 —— 所以这里现场判一次，不信任残留的下标。
  bool _dragActive(PublishController controller) {
    final int? from = _dragFrom;
    return from != null && controller.canReorder && from < controller.items.length;
  }

  /// 预览顺序：controller 的原下标序列，按「把 _dragFrom 挪到 _hoverIndex」现算。
  /// 🔴 只是投影，不回写、不缓存。
  List<int> _previewOrder(PublishController controller) {
    final int n = controller.items.length;
    final order = List<int>.generate(n, (i) => i);
    final int? from = _dragFrom;
    final int? to = _hoverIndex;
    if (!_dragActive(controller) || from == null || to == null || to == from) return order;
    order.removeAt(from);
    order.insert(to.clamp(0, n - 1), from);
    return order;
  }

  /// 指针（pointerDragAnchorStrategy 下 details.offset 即指针全局坐标）→ 悬停位。
  void _updateHover(Offset global, int lead, double cell, double gap, int count) {
    final box = _gridKey.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.hasSize || count == 0) return;
    final Offset local = box.globalToLocal(global);
    final int col = (local.dx / (cell + gap)).floor().clamp(0, 2);
    final int row = (local.dy / (cell + gap)).floor().clamp(0, 99);
    // 落在添加格上就当第 0 位，落在末尾空白处就当最后一位。
    final int pos = (row * 3 + col - lead).clamp(0, count - 1);
    if (pos != _hoverIndex) setState(() => _hoverIndex = pos);
  }

  void _clearDragPreview() {
    if (_dragFrom == null && _hoverIndex == null) return;
    setState(() {
      _dragFrom = null;
      _hoverIndex = null;
    });
  }

  /// 选图/处理中的占位格：拍照返回后到缩略图出现前显示，给「正在加图」的即时反馈。
  Widget _processingCell() {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.cream2,
        borderRadius: BorderRadius.circular(9),
      ),
      alignment: Alignment.center,
      child: const SizedBox(
        width: 22,
        height: 22,
        child: CircularProgressIndicator(
          strokeWidth: 2.5,
          color: AppColors.mint,
        ),
      ),
    );
  }

  /// 虚线添加格（pcell-add）：2px 虚线紫描边 + 「＋」+ Tambah。
  Widget _addCell(PublishController controller, AppLocalizations l10n) {
    // U2：拖动途中只留虚线格，藏起「＋」与文字 —— 手指正在排序，它此刻不是可点的入口。
    final bool dragging = _dragActive(controller);
    // a11y：自成一个按钮节点。不包的话空网格时「Tambah」会和上方字段标题合并成一个节点。
    return Semantics(
      button: true,
      container: true,
      label: l10n.tabAdd,
      excludeSemantics: true,
      child: GestureDetector(
        key: const ValueKey('publishAddImage'),
        // 处理中禁用，避免连点触发多次 _addImage 提前撤占位/闪烁。
        onTap: _addingImage ? null : () => _pickImageSource(controller, l10n),
        // V1.4.0 稿 F1 `.imgs i.add`：白底 + 1.5px 灰虚线、r10，「＋ / Tambah」弱色（L2 第二轮起取代淡紫底紫虚线）。
        child: CustomPaint(
          painter: DashedRRectPainter(
            color: AppColors.line,
            radius: 10,
            dash: 5,
            gap: 4,
          ),
          child: Container(
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(10),
            ),
            alignment: Alignment.center,
            child: dragging
                ? null
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.add_rounded, size: 22, color: AppColors.muted),
                      const SizedBox(height: 3),
                      Text(
                        l10n.tabAdd,
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w500,
                          color: AppColors.muted,
                        ),
                      ),
                    ],
                  ),
          ),
        ),
      ),
    );
  }

  /// 发帖页看大图（V1.3.4 Story 2.5 · FR-139 · AD-9）。只看不改；完整原图，不套露出窗口。
  void _openPreview(PublishController controller, int index) {
    // 另一根手指正在拖格子时不开灯箱（否则灯箱盖在拖影上）
    if (_dragFrom != null) return;
    final images = [for (final item in controller.items) item.bytes];
    if (index < 0 || index >= images.length) return;
    ImageLightbox.openMemory(
      context,
      images: images,
      initialIndex: index,
      heroTagPrefix: 'publish_compose',
      source: 'publish_compose',
    );
  }

  /// 一格照片。V1.3.0 Story 4.1 起可长按拖拽重排（AC1），首格常驻「封面」角标（AC2）。
  /// V1.3.4 Story 2.5 起单击看大图（FR-139）——上传中 / 失败 / 锁定期一样能点开。
  Widget _thumb(
      PublishController controller, int index, AppLocalizations l10n, double cell) {
    // 拖动途中所有格都藏起 ✕：手指正在排序，误触到删除就是丢图。
    // 点击只包格子本体（两条返回路径共用）；✕ 的识别器更深，点 ✕ 时它先胜出，不会冒泡开灯箱。
    // ⚠️ 不加 Hero：拖拽 feedback 复用 _thumbContent，同 tag Hero 会出现两份而抛异常。
    final cellContent = GestureDetector(
      key: ValueKey('publishThumb_$index'),
      behavior: HitTestBehavior.opaque,
      onTap: () => _openPreview(controller, index),
      child: _thumbContent(controller, index, l10n,
          showRemove: !_dragActive(controller)),
    );
    // 🔴 AC4：上传会话里**整个拖拽入口不挂**（不是挂上去再判空）——
    // 挂着的话手指还是能把那张图抬起来，只是放下没反应，看着像卡了。
    if (!controller.canReorder) return cellContent;

    return LongPressDraggable<int>(
      data: index,
      dragAnchorStrategy: pointerDragAnchorStrategy,
      onDragStarted: () => setState(() {
        _dragFrom = index;
        _hoverIndex = index;
      }),
      // 放下（DragTarget 已先行处理）/ 拖出网格取消，都在这里把预览清掉。
      onDragEnd: (_) => _clearDragPreview(),
      // 抬起：放大 1.08 + 微倾 + 阴影（AC1 · U2）。内容复用格子本身 —— 首图带「封面」角标，
      // 看得出「我正拖着封面」—— 只是不带 ✕。
      // Material 包一层，免得 Overlay 里没有默认文字样式。
      feedback: Material(
        color: Colors.transparent,
        child: Transform.translate(
          offset: Offset(-cell / 2, -cell / 2),
          child: Transform.rotate(
            angle: -0.035,
            child: Transform.scale(
              scale: 1.08,
              child: Container(
                width: cell,
                height: cell,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(9),
                  boxShadow: [
                    // 稿值（U2）：0 10px 24px rgba(30,20,60,.35)。
                    const BoxShadow(
                      color: Color(0x591E143C),
                      blurRadius: 24,
                      offset: Offset(0, 10),
                    ),
                  ],
                ),
                child: _thumbContent(controller, index, l10n, showRemove: false),
              ),
            ),
          ),
        ),
      ),
      // 原位是**空位**而不是淡影：其余格已按预览顺序让开，
      // 这个空位本身就是「松手会落在这里」的提示。
      childWhenDragging: const SizedBox.expand(),
      child: cellContent,
    );
  }

  Widget _thumbContent(
      PublishController controller, int index, AppLocalizations l10n,
      {bool showRemove = true}) {
    final item = controller.items[index];
    return Stack(
      fit: StackFit.expand,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(9),
          // cacheWidth：按缩略尺寸解码，避免对整张大图解出全分辨率 bitmap（数十 MB×多张 → ANR）。
          child: Image.memory(item.bytes, fit: BoxFit.cover, cacheWidth: 400),
        ),
        // 上传中（含刚加入的 pending 瞬态）：整格盖半透明遮罩 + 居中 spinner。
        if (item.status == ImageUploadStatus.uploading ||
            item.status == ImageUploadStatus.pending)
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.38),
                borderRadius: BorderRadius.circular(9),
              ),
              child: const Center(
                child: SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(
                    strokeWidth: 2.5,
                    color: Colors.white,
                  ),
                ),
              ),
            ),
          ),
        // 失败标记：样式取 UI 稿 B5（#F0425A 描边 ⚠ + 白色圆底）。位置放左下角——
        // 右上角留给删除 ✕、左上角是首格「封面」角标，三者互不遮挡（D-14，2026-10-14 产品改）。
        if (item.status == ImageUploadStatus.failed)
          Positioned(
            left: 4,
            bottom: 4,
            child: Container(
              key: ValueKey('publishFailedMark_$index'),
              decoration: const BoxDecoration(
                color: Colors.white,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.error_outline,
                  color: Color(0xFFF0425A), size: 18),
            ),
          ),
        // AC2：首格常驻「封面」角标。它只是顺序的**投影** ——
        // 不另设「哪张是封面」的字段，否则迟早出现「界面第一张是 A、封面却是 B」。
        // 重排后角标自然跟着新的第一张走，因为它本来就只认下标 0。
        if (index == 0)
          // 稿值（U1）：top/left 4、padding 1×6、圆角 5、9px/700、rgba(0,0,0,.5)。
          Positioned(
            left: 4,
            top: 4,
            child: Container(
              key: const ValueKey('publishCoverBadge'),
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.5),
                borderRadius: BorderRadius.circular(5),
              ),
              child: Text(
                l10n.publishCoverBadge,
                style: const TextStyle(
                    fontSize: 9, fontWeight: FontWeight.w700, color: Colors.white),
              ),
            ),
          ),
        // 稿值（U1）：16 圆、rgba(0,0,0,.4)、✕ 图标 10。
        if (showRemove)
          Positioned(
            right: 3,
            top: 3,
            child: GestureDetector(
              key: ValueKey('publishRemoveImage_$index'),
              onTap: () => controller.removeImage(index),
              child: Container(
                width: 16,
                height: 16,
                alignment: Alignment.center,
                decoration: const BoxDecoration(
                  color: Colors.black38,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.close, size: 10, color: Colors.white),
              ),
            ),
          ),
      ],
    );
  }
}

/// 类型 pill chip（原型 tchip）：选中=紫底白字；未选=白底 #E6E6E6 边框；灰置=暗淡。
/// 发布页宠物 chip（UI 稿 `.pchip`）：胶囊、白底、1.5 描边 `#DCD2F7`、内边距 3 / 10 / 3 / 3、头像 22 + 名字 12 / w600；
/// 选中：描边与文字 `#845EC9` + 名字后「✓」（w800）。热区 ≥ 44 高（外包 `ConstrainedBox`，视觉尺寸不变）。
class _PetChip extends StatelessWidget {
  const _PetChip({super.key, required this.pet, required this.selected, required this.onTap});

  final PetListItem pet;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.mint : AppColors.ink2;
    final hasAvatar = pet.avatarUrl != null && pet.avatarUrl!.isNotEmpty;
    return Semantics(
      button: true,
      selected: selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 44),
          child: Align(
            widthFactor: 1,
            child: Container(
              padding: const EdgeInsets.fromLTRB(3, 3, 10, 3),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: selected ? AppColors.mint : AppColors.lineViolet, width: 1.5),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  CircleAvatar(
                    radius: 11,
                    backgroundColor: AppColors.mintTint,
                    backgroundImage: AppImage.provider(pet.avatarUrl, thumbWidth: 66),
                    child: hasAvatar ? null : const Icon(Icons.pets, size: 12, color: AppColors.mint500),
                  ),
                  const SizedBox(width: 5),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 140),
                    child: Text(
                      pet.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: color),
                    ),
                  ),
                  if (selected)
                    Padding(
                      padding: const EdgeInsets.only(left: 1),
                      child: Text('✓',
                          key: const ValueKey('publishPetChipCheck'),
                          style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, color: color)),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _TypeChip extends StatelessWidget {
  const _TypeChip({
    required this.keyValue,
    required this.label,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final String keyValue;
  final String label;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: GestureDetector(
        key: ValueKey(keyValue),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 13),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: selected ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(
              color: selected ? AppColors.mint : AppColors.line,
              width: 1.5,
            ),
          ),
          child: Text(
            label,
            maxLines: 1,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: selected ? Colors.white : AppColors.ink2,
            ),
          ),
        ),
      ),
    );
  }
}

/// 发帖页的只读打卡场所条（V1.3.2 Story 1.5 · UI 稿 C6）。线性定位图标 + 场所名；不用 emoji 当图标。
class _CheckinPlaceStrip extends StatelessWidget {
  const _CheckinPlaceStrip({required this.place});

  final PublishCheckinPlace place;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Semantics(
      label: l10n.publishCheckinPlaceLabel(place.placeName),
      excludeSemantics: true,
      child: Container(
        key: const ValueKey('publishCheckinPlaceStrip'),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.mintTint,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            const Icon(Icons.place_outlined, size: 18, color: AppColors.mint),
            const SizedBox(width: 8),
            Expanded(
              child: Text(place.placeName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.ink)),
            ),
          ],
        ),
      ),
    );
  }
}
