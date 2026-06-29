import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
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
import '../../profile/data/milestone_repository.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/data/timeline_repository.dart';
import '../../profile/domain/milestone.dart';
import '../../profile/domain/milestone_titles.dart';
import '../../profile/domain/pet_profile.dart';
import '../../profile/domain/share_service.dart';
import '../../profile/presentation/widgets/milestone_celebration.dart';
import '../../../shared/utils/date_format.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../../shared/utils/media_permission.dart';
import '../../me/data/my_posts_repository.dart';
import '../data/content_repository.dart';
import '../domain/content_type.dart';
import '../domain/publish_controller.dart';
import 'feed_controller.dart';
import 'publish_result_page.dart';

/// 发布控制器 provider（autoDispose：sheet 关闭即 dispose，清空内存草稿，NFR-10 无持久草稿）。
final publishControllerProvider = Provider.autoDispose<PublishController>((ref) {
  final useCase = ref.read(mediaUploadUseCaseProvider);
  final controller = PublishController(
    repository: ref.read(contentRepositoryProvider),
    uploadOne: (bytes) async {
      final result = await useCase.uploadBytes(scope: MediaScope.public, bytes: bytes);
      return result.publicUrl!;
    },
  );
  ref.onDispose(controller.dispose);
  return controller;
});

/// 统一发布 Compose 全屏 bottom sheet（Story 2.3 · TailTopia Prototype 换肤）。
/// 类型标签（Cerita / Momen / Edukasi）→ 作者+关联宠物 → 文字 → 图片 → 发布。
class PublishComposePage extends ConsumerStatefulWidget {
  const PublishComposePage(
      {super.key, this.preset, this.presetEventDate, this.milestoneCode});

  /// 预选发布类型（如生日深链预选成长日历，Story 6.1 FR-40 / 灰选建档返回，AC7）；为空时默认 daily。
  final ContentType? preset;

  /// 成长日历事件日期默认值（F9）：从「＋」进取今天、从日历格子进（2.4）取该格子日期；为空取今天。
  final DateTime? presetEventDate;

  /// 里程碑「去发布」来源 code（Story 8.4）：发布成功且仍为成长日历类型 → 以新内容 id 自动打卡完成。
  final String? milestoneCode;

  /// 以全屏 bottom sheet 形式打开（供「＋」入口 / 深链着陆页 / 灰选建档返回 / 里程碑去发布调用）。
  static Future<void> open(BuildContext context,
      {ContentType? preset, DateTime? presetEventDate, String? milestoneCode}) {
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
            preset: preset, presetEventDate: presetEventDate, milestoneCode: milestoneCode),
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
    // 深链/灰选预选类型（如生日 / 成长日历）：首帧后设控制器类型，与手动选 tab 等价。
    final preset = widget.preset;
    if (preset != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        final c = ref.read(publishControllerProvider);
        c.setType(preset);
        if (preset == ContentType.growthMoment) {
          c.setEventDate(widget.presetEventDate ?? DateTime.now()); // F9 默认事件日期
          _ensurePetLoaded();
        }
      });
    }
  }

  /// 成长日历绑定的宠物档案（V1 单账号单宠物）。选「成长日历」时拉取，发布时带其 id。
  PetProfile? _linkedPet;

  /// 提交期间的「审核中」覆盖层（P-39b）：覆盖编辑表单，提交结束后跳成功/被拒页。
  bool _reviewing = false;
  PublishResultArgs? _reviewingArgs;

  /// 选图/处理中（拍照返回 → 解码压缩剥 EXIF）：网格显占位 loading，避免「拍完没反应」。
  bool _addingImage = false;

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }

  bool get _hasPetProfile {
    final profile = ref.read(authControllerProvider).profile;
    return profile?.hasPetProfile ?? false;
  }

  /// 懒加载当前用户的宠物档案（成长日历发帖必带 pet_id，Story 2.3 AC2）。
  Future<void> _ensurePetLoaded() async {
    if (_linkedPet != null) return;
    final pet = await ref.read(profileRepositoryProvider).getMyProfile();
    if (mounted) setState(() => _linkedPet = pet);
  }

  Future<void> _addImage(PublishController controller,
      {MediaSource source = MediaSource.gallery}) async {
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
    // 即选即传：item 入列即以 pending/uploading 态渲染（自带 loading 盖层）；上传期间发布按钮置灰。
    if (bytes != null && controller.addImage(bytes)) {
      await controller.uploadAll();
    }
  }

  Future<void> _publish(PublishController controller, AppLocalizations l10n) async {
    Analytics.capture('content_publish_submitted', {'type': controller.type.name});
    // 成长日历必带 pet_id（否则后端 422）；V1 单宠物 → 取当前用户唯一档案。
    if (controller.type == ContentType.growthMoment) {
      await _ensurePetLoaded();
      if (!mounted) return;
      if (_linkedPet == null) {
        _onGrowthBlocked(l10n); // 无档案兜底（理论上已被 segment 灰置拦住）
        return;
      }
    }
    final key = 'post-${DateTime.now().microsecondsSinceEpoch}';
    final petId = controller.type == ContentType.growthMoment ? _linkedPet?.id : null;
    final args = _buildArgs(controller, l10n);
    // 进入「审核中」覆盖层（P-39b）。
    setState(() {
      _reviewing = true;
      _reviewingArgs = args;
    });
    final started = DateTime.now();
    int? id;
    String? blockSlug; // content-text-blocked / content-image-blocked（F10 审核拦截）
    try {
      id = await controller.publish(idempotencyKey: key, petId: petId);
    } on DioException catch (e) {
      final slug = ProblemDetail.fromDioException(e)?.typeSlug;
      if (slug == 'content-text-blocked' || slug == 'content-image-blocked') {
        blockSlug = slug;
      }
    }
    // 「审核中」至少展示 ~900ms，避免一闪而过（faithful verifying affordance）。
    final elapsed = DateTime.now().difference(started);
    if (elapsed < const Duration(milliseconds: 900)) {
      await Future<void>.delayed(const Duration(milliseconds: 900) - elapsed);
    }
    if (!mounted) return;

    if (id != null) {
      ref.invalidate(feedProvider);
      ref.invalidate(myPostsProvider);
      // 成长日历发帖 → 同步刷新成长档案时间线与统计，避免需手动下拉刷新才显示（F9）。
      if (controller.type == ContentType.growthMoment) {
        ref.invalidate(timelineFirstPageProvider);
        ref.invalidate(archiveStatsProvider);
      }
      // 里程碑「去发布」回填（Story 8.4）：仍为成长日历类型 → 以新内容 id 自动打卡完成（best-effort）。
      MilestoneItem? completedMilestone;
      if (widget.milestoneCode != null && controller.type == ContentType.growthMoment) {
        try {
          completedMilestone =
              await ref.read(milestoneRepositoryProvider).checkIn(widget.milestoneCode!, id);
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
        final listData = ref.read(milestoneListProvider).asData?.value;
        final petName = listData?.petName ?? '';
        final collection = listData == null
            ? const <MilestoneItem>[]
            : [
                for (final g in listData.groups)
                  for (final it in g.items) it.code == done.code ? done : it,
              ];
        final shareText = l10n.milestoneShareText(localizedMilestoneTitle(done.code, locale));
        final router = GoRouter.maybeOf(context);
        await showMilestoneCelebration(
          context,
          done,
          petName: petName,
          collection: collection,
          onShare: () => ref.read(shareServiceProvider)(shareText),
          // onSeeAll 省略：庆祝关闭后统一在下方先关 sheet 再跳列表（否则 sheet 挡住跳转）。
        );
        if (!mounted) return;
        // 里程碑路径：庆祝即成功反馈 → 关闭发布 sheet → 跳回里程碑列表（不叠加通用「发布成功」页）。
        Navigator.of(context).pop();
        router?.go(DeepLinkRoutes.milestoneList);
        return;
      }
      Navigator.of(context).pop(); // 关闭发布 sheet
      context.push('/publish/done', extra: args); // → P-39 发布成功
    } else if (blockSlug != null) {
      // AC8（F10）：审核拦截 → P-39c 内容被拒（整页 + 拒因）。
      final reasons = <String>[
        if (blockSlug == 'content-text-blocked') l10n.publishRejectedReasonText,
        if (blockSlug == 'content-image-blocked') l10n.publishRejectedReasonImage,
      ];
      Navigator.of(context).pop();
      context.push('/publish/rejected', extra: args.withReasons(reasons)); // → P-39c 被拒
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
  PublishResultArgs _buildArgs(PublishController controller, AppLocalizations l10n) {
    final text = controller.text.trim();
    final excerpt = text.length > 48 ? '${text.substring(0, 48)}…' : text;
    return PublishResultArgs(
      excerpt: excerpt,
      typeLabel: _typeLabel(controller.type, l10n),
      photoCount: controller.items.length,
    );
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 3)));
  }

  void _onGrowthBlocked(AppLocalizations l10n) {
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(
        content: Text(l10n.publishGrowthNeedsProfile),
        action: SnackBarAction(
          label: l10n.publishCreateProfile,
          onPressed: () {
            // AC7（F15）：B/C 灰选成长日历 → 关闭发布页，跳建档（带 origin），
            // 建档完成跳过庆祝页直接回发布页预选成长日历（pet_profile_create_page 接管）。
            Navigator.of(context).pop();
            context.push('/profile/create?origin=graySelectPublish');
          },
        ),
      ));
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

  Widget _body(BuildContext context, AppLocalizations l10n, PublishController controller) {
    // 提交期间：整屏「审核中」覆盖层（P-39b），覆盖编辑表单。
    if (_reviewing && _reviewingArgs != null) {
      return PublishReviewingView(args: _reviewingArgs!);
    }
    final growthSelected = controller.type == ContentType.growthMoment;
    return Column(
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
                decoration:
                    BoxDecoration(color: AppColors.line, borderRadius: BorderRadius.circular(3)),
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
                        decoration:
                            const BoxDecoration(color: Color(0xFFEFEDF3), shape: BoxShape.circle),
                        child: const Icon(Icons.close_rounded, size: 18, color: AppColors.ink2),
                      ),
                    ),
                  ),
                  Expanded(
                    child: Text(l10n.publishComposeTitle,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                  ),
                  // 原型 pubbtn：扁平紫钮（启用 #845EC9）/ off=中性灰 #B6B6B6。
                  FilledButton(
                    key: const ValueKey('publishSubmit'),
                    onPressed: controller.canPublish ? () => _publish(controller, l10n) : null,
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.mint,
                      disabledBackgroundColor: const Color(0xFFB6B6B6),
                      foregroundColor: AppColors.onAccent,
                      // 原型 .pubbtn 始终白字（.off 只改底色不改字色）。
                      disabledForegroundColor: AppColors.onAccent,
                      // 原型 pubbtn 启用：紫色柔阴影 0 4px 12px rgba(132,94,201,.30)；off 无阴影。
                      elevation: controller.canPublish ? 4 : 0,
                      shadowColor: AppColors.mint.withValues(alpha: 0.30),
                      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 9),
                      minimumSize: const Size(0, 0),
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                      textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700),
                    ),
                    child: controller.publishing
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : Text(l10n.publishButton),
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
                _petTargetRow(l10n),
              ],
              const SizedBox(height: 16),
              _imageRow(controller, l10n),
              const SizedBox(height: 14),
              // —— 文字 ——（原型 textarea-wrap：1.5px #E6E6E6 外框 + 圆角11 + 内部右下字数）
              Container(
                decoration: BoxDecoration(
                  color: AppColors.card,
                  borderRadius: BorderRadius.circular(11),
                  border: Border.all(color: AppColors.line, width: 1.5),
                ),
                padding: const EdgeInsets.all(11),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextField(
                      key: const ValueKey('publishText'),
                      controller: _textController,
                      minLines: 3,
                      maxLines: 6,
                      maxLength: kMaxPostTextLength,
                      onChanged: controller.setText,
                      style: const TextStyle(fontSize: 14, color: AppColors.ink, height: 1.6),
                      decoration: InputDecoration(
                        isCollapsed: true,
                        hintText: _hint(controller, l10n),
                        hintStyle: const TextStyle(color: AppColors.textTertiary, fontSize: 14, height: 1.6),
                        border: InputBorder.none,
                        counterText: '', // 隐藏默认计数器，改放容器内右下
                      ),
                    ),
                    const SizedBox(height: 7),
                    // 原型 charcount：框内右下「已用 / 总数」，弱色 11px。
                    Text('${_textController.text.length} / $kMaxPostTextLength',
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
                  ],
                ),
              ),
              if (growthSelected) ...[
                const SizedBox(height: 12),
                _eventDateRow(controller, l10n),
              ],
              if (controller.hasFailed) ...[
                const SizedBox(height: 12),
                TextButton.icon(
                  key: const ValueKey('publishRetry'),
                  icon: const Icon(Icons.refresh, color: AppColors.mint700),
                  label: Text(l10n.publishRetry, style: const TextStyle(color: AppColors.mint700)),
                  onPressed: () => controller.retryFailed(),
                ),
              ],
              const SizedBox(height: 16),
              // 原型底部：居中公开提示。
              Center(
                child: Text(l10n.publishPublicNotice,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                        color: AppColors.textTertiary, fontSize: 12, height: 1.6)),
              ),
            ],
          ),
        ),
      ],
    );
  }

  String _hint(PublishController c, AppLocalizations l10n) {
    switch (c.type) {
      case ContentType.growthMoment:
        // 原型 fake-ta：「Ceritakan momen spesial {宠物名}...」。
        return l10n.publishHintGrowth(_linkedPet?.name ?? l10n.publishPetFallback);
      case ContentType.knowledge:
        return l10n.publishHintKnowledge;
      default:
        return l10n.publishHintDaily;
    }
  }

  /// 类型标签：原型横向 pill chips（紫底圆角 9999 选中态，#E6E6E6 边框未选）。
  Widget _segments(PublishController controller, AppLocalizations l10n) {
    return SizedBox(
      height: 34,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          // 原型 typechips 顺序：Cerita Harian / Tips & Info / Momen Bahagia🌟（成长末位）。
          _segChip(l10n.publishSegmentDaily, ContentType.daily, controller,
              'seg_${ContentType.daily.wire}'),
          const SizedBox(width: 7),
          _segChip(l10n.publishSegmentKnowledge, ContentType.knowledge, controller,
              'seg_${ContentType.knowledge.wire}'),
          const SizedBox(width: 7),
          _growthChip(l10n, controller),
        ],
      ),
    );
  }

  Widget _segChip(String label, ContentType type, PublishController controller, String key) {
    return _TypeChip(
      keyValue: key,
      label: label,
      selected: controller.type == type,
      enabled: true,
      onTap: () => controller.setType(type),
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
              controller.setType(ContentType.growthMoment);
              if (controller.eventDate == null) {
                controller.setEventDate(widget.presetEventDate ?? DateTime.now()); // F9 默认
              }
              _ensurePetLoaded();
            }
          : () => _onGrowthBlocked(l10n),
    );
  }

  /// 关联对象（原型 petsel，仅成长）：紫浅底行「Untuk: 🐾 {pet} (wajib diisi)」。
  Widget _petTargetRow(AppLocalizations l10n) {
    final petName = _linkedPet?.name ?? l10n.publishPetFallback;
    final emoji = switch (_linkedPet?.petType) {
      'CAT' => '🐱',
      'DOG' => '🐶',
      _ => '🐾',
    };
    return Container(
      key: const ValueKey('publishPetTarget'),
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.mintTint2, // 原型 petsel 紫浅底 #F8F6FF
        borderRadius: BorderRadius.circular(11),
      ),
      child: Row(
        children: [
          Text(l10n.publishForLabel,
              style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
          const SizedBox(width: 6),
          Text('$emoji $petName',
              style: const TextStyle(
                  fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.mint)),
        ],
      ),
    );
  }

  /// 图片来源选择（原型「Tambah」单入口 → 相机 / 相册）。
  Future<void> _pickImageSource(PublishController controller, AppLocalizations l10n) async {
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
              leading: const Icon(Icons.photo_camera_outlined, color: AppColors.mint),
              title: Text(l10n.publishCamera),
              onTap: () => Navigator.of(ctx).pop(MediaSource.camera),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined, color: AppColors.mint),
              title: Text(l10n.publishGallery),
              onTap: () => Navigator.of(ctx).pop(MediaSource.gallery),
            ),
          ],
        ),
      ),
    );
    if (source != null) await _addImage(controller, source: source);
  }

  /// 成长日历事件日期字段（F9）：禁选未来，默认今天/格子日期。点击开日期选择器。
  Widget _eventDateRow(PublishController controller, AppLocalizations l10n) {
    final date = controller.eventDate ?? DateTime.now();
    // 原型 daterow：「Tanggal momen: 15 Jun 2026」（本地化月份）+ chevron。
    return GestureDetector(
      key: const ValueKey('publishEventDate'),
      behavior: HitTestBehavior.opaque,
      onTap: () => _pickEventDate(controller),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.mintTint2, // 原型紫浅底 #F8F6FF
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.lineViolet, width: 1.5),
        ),
        child: Row(
          children: [
            const Icon(Icons.event_rounded, size: 18, color: AppColors.mint),
            const SizedBox(width: 10),
            Expanded(
              child: Text.rich(
                TextSpan(
                  text: '${l10n.publishEventDate}: ',
                  style: const TextStyle(fontSize: 13, color: AppColors.ink),
                  children: [
                    TextSpan(
                      text: formatDayMonthYear(context, date),
                      style: const TextStyle(
                          fontWeight: FontWeight.w700, color: AppColors.mint),
                    ),
                  ],
                ),
              ),
            ),
            const Icon(Icons.chevron_right_rounded, size: 18, color: AppColors.mint700),
          ],
        ),
      ),
    );
  }

  Future<void> _pickEventDate(PublishController controller) async {
    final today = DateTime.now();
    final initial = controller.eventDate ?? today;
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(today.year - 30),
      lastDate: DateTime(today.year, today.month, today.day), // 禁选未来（F9）
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
        Text(l10n.publishPhotoLabel,
            style: const TextStyle(
                fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.textSecondary)),
        const SizedBox(height: 8),
        GridView.count(
          crossAxisCount: 3,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 5,
          crossAxisSpacing: 5,
          children: [
            if (showAdd) _addCell(controller, l10n),
            if (_addingImage) _processingCell(),
            for (int i = 0; i < items.length; i++) _thumb(controller, i),
          ],
        ),
      ],
    );
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
        child: CircularProgressIndicator(strokeWidth: 2.5, color: AppColors.mint),
      ),
    );
  }

  /// 虚线添加格（pcell-add）：2px 虚线紫描边 + 「＋」+ Tambah。
  Widget _addCell(PublishController controller, AppLocalizations l10n) {
    return GestureDetector(
      key: const ValueKey('publishAddImage'),
      // 处理中禁用，避免连点触发多次 _addImage 提前撤占位/闪烁。
      onTap: _addingImage ? null : () => _pickImageSource(controller, l10n),
      child: CustomPaint(
        painter: DashedRRectPainter(color: AppColors.dashedViolet, radius: 9, dash: 5, gap: 4),
        child: Container(
          decoration: BoxDecoration(
            color: AppColors.cream2,
            borderRadius: BorderRadius.circular(9),
          ),
          alignment: Alignment.center,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.add_rounded, size: 22, color: AppColors.mint),
              const SizedBox(height: 3),
              Text(l10n.tabAdd,
                  style: const TextStyle(
                      fontSize: 10, fontWeight: FontWeight.w500, color: AppColors.mint)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _thumb(PublishController controller, int index) {
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
                  child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                ),
              ),
            ),
          ),
        if (item.status == ImageUploadStatus.failed)
          const Positioned(
              right: 4, top: 4, child: Icon(Icons.error, color: Colors.red, size: 18)),
        Positioned(
          right: 3,
          top: 3,
          child: GestureDetector(
            onTap: () => controller.removeImage(index),
            child: Container(
              decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle),
              child: const Icon(Icons.close, size: 16, color: Colors.white),
            ),
          ),
        ),
      ],
    );
  }
}

/// 类型 pill chip（原型 tchip）：选中=紫底白字；未选=白底 #E6E6E6 边框；灰置=暗淡。
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
                color: selected ? AppColors.mint : AppColors.line, width: 1.5),
          ),
          child: Text(label,
              maxLines: 1,
              style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: selected ? Colors.white : AppColors.ink2)),
        ),
      ),
    );
  }
}

