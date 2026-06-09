import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/network/problem_detail.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/shadows.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../profile/data/profile_repository.dart';
import '../../profile/domain/pet_profile.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/design/btn3d.dart';
import '../../../shared/widgets/design/emoji_avatar.dart';
import '../../me/data/my_posts_repository.dart';
import '../data/content_repository.dart';
import '../domain/content_type.dart';
import '../domain/publish_controller.dart';
import 'feed_controller.dart';

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

/// 统一发布 Compose 全屏 bottom sheet（Story 2.3 · PetGo Prototype 换肤）。
/// 类型标签（Cerita / Momen / Edukasi）→ 作者+关联宠物 → 文字 → 图片 → 发布。
class PublishComposePage extends ConsumerStatefulWidget {
  const PublishComposePage({super.key, this.preset, this.presetEventDate});

  /// 预选发布类型（如生日深链预选成长日历，Story 6.1 FR-40 / 灰选建档返回，AC7）；为空时默认 daily。
  final ContentType? preset;

  /// 成长日历事件日期默认值（F9）：从「＋」进取今天、从日历格子进（2.4）取该格子日期；为空取今天。
  final DateTime? presetEventDate;

  /// 以全屏 bottom sheet 形式打开（供「＋」入口 / 深链着陆页 / 灰选建档返回调用）。
  static Future<void> open(BuildContext context,
      {ContentType? preset, DateTime? presetEventDate}) {
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
        child: PublishComposePage(preset: preset, presetEventDate: presetEventDate),
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
    final bytes = await ref
        .read(mediaUploadUseCaseProvider)
        .pickAndProcess(source: source, context: context);
    if (bytes != null) controller.addImage(bytes);
  }

  Future<void> _publish(PublishController controller, AppLocalizations l10n) async {
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
    int? id;
    try {
      id = await controller.publish(idempotencyKey: key, petId: petId);
    } on DioException catch (e) {
      if (!mounted) return;
      // AC8（F10）：审核拦截 422——文字/图片区分提示，停留编辑页保留输入（改后可重提）。
      final slug = ProblemDetail.fromDioException(e)?.typeSlug;
      switch (slug) {
        case 'content-text-blocked':
          _toast(l10n.publishTextBlocked);
        case 'content-image-blocked':
          _toast(l10n.publishImageBlocked);
        default:
          _toast(l10n.publishFailed);
      }
      return; // 不关闭 sheet，内存草稿保留
    }
    if (!mounted) return;
    if (id != null) {
      ref.invalidate(feedProvider);
      ref.invalidate(myPostsProvider);
      Navigator.of(context).pop();
    } else if (controller.hasFailed) {
      _toast(l10n.publishFailed);
    }
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
            context.go('/profile/create?origin=graySelectPublish');
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
                  TextButton(
                    key: const ValueKey('publishClose'),
                    onPressed: () => Navigator.of(context).pop(),
                    style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(48, 36)),
                    child: const Text('Batal',
                        style: TextStyle(
                            color: AppColors.muted, fontSize: 15, fontWeight: FontWeight.w700)),
                  ),
                  const Expanded(
                    child: Text('Buat Postingan',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
                  ),
                  Btn3d(
                    key: const ValueKey('publishSubmit'),
                    onPressed: controller.canPublish ? () => _publish(controller, l10n) : null,
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    fontSize: 14,
                    borderRadius: 12,
                    child: controller.publishing
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : const Text('Bagikan'),
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
              _segments(controller, l10n),
              const SizedBox(height: 16),
              _authorRow(controller),
              if (growthSelected && _linkedPet == null) ...[
                const SizedBox(height: 12),
                _growthNotice(),
              ],
              if (growthSelected) ...[
                const SizedBox(height: 12),
                _eventDateRow(controller, l10n),
              ],
              const SizedBox(height: 12),
              // —— 文字 ——
              TextField(
                key: const ValueKey('publishText'),
                controller: _textController,
                maxLines: 5,
                maxLength: kMaxPostTextLength,
                onChanged: controller.setText,
                style: const TextStyle(fontSize: 15.5, color: AppColors.ink, height: 1.5),
                decoration: InputDecoration(
                  hintText: _hint(controller),
                  hintStyle: const TextStyle(color: AppColors.muted, fontSize: 15.5),
                  filled: true,
                  fillColor: AppColors.card,
                  contentPadding: const EdgeInsets.all(15),
                  counterText: l10n.publishRemainingChars(controller.remainingChars),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(16),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
              const SizedBox(height: 12),
              _imageRow(controller),
              const SizedBox(height: 14),
              // Kamera（相机）：拍照上传，与「Tambah foto」相册同走 pickAndProcess（仅 source 不同）。
              // 注：Lokasi（定位）chip 已移除——V1 PRD 无定位功能，不留死按钮。
              Row(children: [
                _SmallChip(
                  key: const ValueKey('publishCameraChip'),
                  icon: Icons.photo_camera_outlined,
                  label: 'Kamera',
                  onTap: () => _addImage(controller, source: MediaSource.camera),
                ),
              ]),
              if (controller.hasFailed) ...[
                const SizedBox(height: 12),
                TextButton.icon(
                  key: const ValueKey('publishRetry'),
                  icon: const Icon(Icons.refresh, color: AppColors.mint700),
                  label: Text(l10n.publishRetry, style: const TextStyle(color: AppColors.mint700)),
                  onPressed: () => controller.retryFailed(),
                ),
              ],
              const SizedBox(height: 8),
              Text(l10n.publishPublicNotice,
                  style: const TextStyle(color: AppColors.textTertiary, fontSize: 12)),
            ],
          ),
        ),
      ],
    );
  }

  String _hint(PublishController c) {
    switch (c.type) {
      case ContentType.growthMoment:
        return 'Tulis satu kalimat manis tentang anabul...';
      case ContentType.knowledge:
        return 'Bagikan tips merawat anabul...';
      default:
        return 'Apa yang terjadi hari ini?';
    }
  }

  /// 类型标签：三个 emoji 立体 tab（日常 / 快乐时刻 / 科普）。
  Widget _segments(PublishController controller, AppLocalizations l10n) {
    return Row(
      children: [
        Expanded(
            child: _segTab('💬', l10n.publishSegmentDaily, ContentType.daily, controller,
                'seg_${ContentType.daily.wire}')),
        const SizedBox(width: 8),
        Expanded(child: _growthTab(l10n, controller)),
        const SizedBox(width: 8),
        Expanded(
            child: _segTab('📖', l10n.publishSegmentKnowledge, ContentType.knowledge, controller,
                'seg_${ContentType.knowledge.wire}')),
      ],
    );
  }

  Widget _segTab(String emoji, String label, ContentType type, PublishController controller,
      String key) {
    final on = controller.type == type;
    return _TabButton(
      keyValue: key,
      emoji: emoji,
      label: label,
      selected: on,
      enabled: true,
      onTap: () => controller.setType(type),
    );
  }

  Widget _growthTab(AppLocalizations l10n, PublishController controller) {
    final enabled = _hasPetProfile;
    final on = controller.type == ContentType.growthMoment;
    return _TabButton(
      keyValue: 'seg_GROWTH_MOMENT',
      emoji: '🌈',
      label: l10n.publishSegmentGrowth,
      selected: on,
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

  /// 作者头像 + 名字 + 关联宠物胶囊。
  Widget _authorRow(PublishController controller) {
    final growth = controller.type == ContentType.growthMoment;
    final linked = growth && _linkedPet != null;
    final petName = _linkedPet?.name ?? 'anabul';
    return Row(
      children: [
        const EmojiAvatar(emoji: '🧑', size: 38, tone: AppColors.cream2),
        const SizedBox(width: 10),
        const Text('Aurel', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
        const Spacer(),
        if (growth)
          Container(
            padding: const EdgeInsets.fromLTRB(6, 6, 10, 6),
            decoration: BoxDecoration(
              color: linked ? AppColors.mintTint : AppColors.card,
              borderRadius: BorderRadius.circular(999),
              boxShadow: AppShadows.sm,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.pets_rounded,
                    size: 16, color: linked ? AppColors.mint : AppColors.muted),
                const SizedBox(width: 6),
                Text(linked ? petName : 'Tag anabul',
                    style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: linked ? AppColors.mint700 : AppColors.muted)),
              ],
            ),
          ),
      ],
    );
  }

  /// 成长日历事件日期字段（F9）：禁选未来，默认今天/格子日期。点击开日期选择器。
  Widget _eventDateRow(PublishController controller, AppLocalizations l10n) {
    final date = controller.eventDate ?? DateTime.now();
    final label = '${date.year}-${date.month.toString().padLeft(2, '0')}'
        '-${date.day.toString().padLeft(2, '0')}';
    return GestureDetector(
      key: const ValueKey('publishEventDate'),
      behavior: HitTestBehavior.opaque,
      onTap: () => _pickEventDate(controller),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(14),
          boxShadow: AppShadows.sm,
        ),
        child: Row(
          children: [
            const Icon(Icons.event_rounded, size: 18, color: AppColors.mint700),
            const SizedBox(width: 10),
            Text(l10n.publishEventDate,
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
            const Spacer(),
            Text(label,
                key: const ValueKey('publishEventDateValue'),
                style: const TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint700)),
            const SizedBox(width: 6),
            const Icon(Icons.chevron_right_rounded, size: 18, color: AppColors.muted),
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

  Widget _growthNotice() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration:
          BoxDecoration(color: AppColors.goldTint, borderRadius: BorderRadius.circular(12)),
      child: const Row(
        children: [
          Text('⚠️'),
          SizedBox(width: 8),
          Expanded(
            child: Text('Momen Bahagia wajib di-tag ke anabul agar masuk ke Paspor.',
                style: TextStyle(fontSize: 12.5, color: Color(0xFF8A6A12), fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }

  Widget _imageRow(PublishController controller) {
    if (controller.items.isEmpty) {
      return Btn3d(
        key: const ValueKey('publishAddImage'),
        variant: Btn3dVariant.soft,
        expand: true,
        onPressed: () => _addImage(controller),
        padding: const EdgeInsets.all(16),
        child: const Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.image_outlined, size: 20, color: AppColors.mint700),
            SizedBox(width: 8),
            Text('Tambah foto', style: TextStyle(fontSize: 14.5)),
          ],
        ),
      );
    }
    return SizedBox(
      height: 88,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          for (int i = 0; i < controller.items.length; i++) _thumb(controller, i),
          if (controller.items.length < kMaxImages)
            GestureDetector(
              key: const ValueKey('publishAddImage'),
              onTap: () => _addImage(controller),
              child: Container(
                width: 80,
                height: 80,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.card,
                  borderRadius: BorderRadius.circular(16),
                  boxShadow: AppShadows.sm,
                ),
                child: const Icon(Icons.add_a_photo_outlined, color: AppColors.mint700),
              ),
            ),
        ],
      ),
    );
  }

  Widget _thumb(PublishController controller, int index) {
    final item = controller.items[index];
    return Padding(
      padding: const EdgeInsets.only(right: 10),
      child: Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: Image.memory(item.bytes, width: 80, height: 80, fit: BoxFit.cover),
          ),
          if (item.status == ImageUploadStatus.uploading)
            const Positioned.fill(
                child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
          if (item.status == ImageUploadStatus.failed)
            const Positioned(right: 4, top: 4, child: Icon(Icons.error, color: Colors.red, size: 18)),
          Positioned(
            right: 2,
            top: 2,
            child: GestureDetector(
              onTap: () => controller.removeImage(index),
              child: Container(
                decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle),
                child: const Icon(Icons.close, size: 16, color: Colors.white),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 类型标签按钮（emoji + 文字，选中=薄荷立体，灰置=暗淡）。
class _TabButton extends StatelessWidget {
  const _TabButton({
    required this.keyValue,
    required this.emoji,
    required this.label,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final String keyValue;
  final String emoji;
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
          padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 6),
          decoration: BoxDecoration(
            color: selected ? AppColors.mint : AppColors.card,
            borderRadius: BorderRadius.circular(14),
            boxShadow: [
              BoxShadow(
                  color: selected ? AppColors.mint600 : AppColors.line,
                  offset: const Offset(0, 3),
                  blurRadius: 0),
            ],
          ),
          child: Column(
            children: [
              Text(emoji, style: const TextStyle(fontSize: 18)),
              const SizedBox(height: 4),
              Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w800,
                      color: selected ? Colors.white : AppColors.ink2)),
            ],
          ),
        ),
      ),
    );
  }
}

class _SmallChip extends StatelessWidget {
  const _SmallChip({required this.icon, required this.label, this.onTap, super.key});

  final IconData icon;
  final String label;

  /// 可选点击回调；为空时为纯展示 chip（不可点）。
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final chip = Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(999),
        boxShadow: AppShadows.sm,
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: AppColors.muted),
          const SizedBox(width: 6),
          Text(label,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: AppColors.ink2)),
        ],
      ),
    );
    if (onTap == null) return chip;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: chip,
    );
  }
}
