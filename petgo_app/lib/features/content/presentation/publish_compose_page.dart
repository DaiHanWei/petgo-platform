import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/media/media_scope.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../auth/domain/auth_state.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../../../shared/utils/media_permission.dart';
import '../data/content_repository.dart';
import '../domain/content_type.dart';
import '../domain/publish_controller.dart';

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

/// 统一发布 Compose 全屏 bottom sheet（Story 2.3 · UX-DR16）。
/// 单页内含类型 Segment → 图片区 → 文字区 → 发布按钮，无独立页面跳转。
class PublishComposePage extends ConsumerStatefulWidget {
  const PublishComposePage({super.key});

  /// 以全屏 bottom sheet 形式打开（供「＋」入口调用）。
  static Future<void> open(BuildContext context) {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: AppColors.base,
      builder: (_) => const FractionallySizedBox(
        heightFactor: 0.95,
        child: PublishComposePage(),
      ),
    );
  }

  @override
  ConsumerState<PublishComposePage> createState() => _PublishComposePageState();
}

class _PublishComposePageState extends ConsumerState<PublishComposePage> {
  final _textController = TextEditingController();

  @override
  void dispose() {
    _textController.dispose();
    super.dispose();
  }

  bool get _hasPetProfile {
    final profile = ref.read(authControllerProvider).profile;
    return profile?.hasPetProfile ?? false;
  }

  Future<void> _addImage(PublishController controller) async {
    final bytes = await ref
        .read(mediaUploadUseCaseProvider)
        .pickAndProcess(source: MediaSource.gallery, context: context);
    if (bytes != null) controller.addImage(bytes);
  }

  Future<void> _publish(PublishController controller, AppLocalizations l10n) async {
    final key = 'post-${DateTime.now().microsecondsSinceEpoch}';
    final id = await controller.publish(idempotencyKey: key);
    if (!mounted) return;
    if (id != null) {
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
    // B/C 或无档案：悬浮提示 + 跳创建入口。
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(
        content: Text(l10n.publishGrowthNeedsProfile),
        action: SnackBarAction(
          label: l10n.publishCreateProfile,
          onPressed: () {
            Navigator.of(context).pop();
            context.go('/profile/create');
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
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text(l10n.publishComposeTitle, style: Theme.of(context).textTheme.titleLarge),
              const Spacer(),
              IconButton(
                key: const ValueKey('publishClose'),
                icon: const Icon(Icons.close),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          _segments(controller, l10n),
          const SizedBox(height: AppSpacing.md),
          Expanded(
            child: ListView(
              children: [
                _imageRow(controller),
                const SizedBox(height: AppSpacing.md),
                TextField(
                  key: const ValueKey('publishText'),
                  controller: _textController,
                  maxLines: 6,
                  maxLength: kMaxPostTextLength,
                  onChanged: controller.setText,
                  decoration: InputDecoration(
                    hintText: l10n.publishTextHint,
                    border: const OutlineInputBorder(),
                    counterText: l10n.publishRemainingChars(controller.remainingChars),
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                Text(
                  l10n.publishPublicNotice,
                  style: TextStyle(color: AppColors.textTertiary, fontSize: 12),
                ),
              ],
            ),
          ),
          if (controller.hasFailed)
            TextButton.icon(
              key: const ValueKey('publishRetry'),
              icon: const Icon(Icons.refresh),
              label: Text(l10n.publishRetry),
              onPressed: () => controller.retryFailed(),
            ),
          FilledButton(
            key: const ValueKey('publishSubmit'),
            onPressed: controller.canPublish ? () => _publish(controller, l10n) : null,
            child: controller.publishing
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(l10n.publishButton),
          ),
        ],
      ),
    );
  }

  Widget _segments(PublishController controller, AppLocalizations l10n) {
    final growthEnabled = _hasPetProfile;
    return Wrap(
      spacing: AppSpacing.sm,
      children: [
        _chip(l10n.publishSegmentDaily, ContentType.daily, controller),
        _growthChip(l10n, controller, growthEnabled),
        _chip(l10n.publishSegmentKnowledge, ContentType.knowledge, controller),
      ],
    );
  }

  Widget _chip(String label, ContentType type, PublishController controller) {
    return ChoiceChip(
      key: ValueKey('seg_${type.wire}'),
      label: Text(label),
      selected: controller.type == type,
      onSelected: (_) => controller.setType(type),
    );
  }

  Widget _growthChip(AppLocalizations l10n, PublishController controller, bool enabled) {
    return ChoiceChip(
      key: const ValueKey('seg_GROWTH_MOMENT'),
      label: Text(l10n.publishSegmentGrowth),
      selected: controller.type == ContentType.growthMoment,
      // B/C 或无档案：灰置不可选；点击 → 悬浮提示 + 跳创建。
      onSelected: enabled
          ? (_) => controller.setType(ContentType.growthMoment)
          : (_) => _onGrowthBlocked(l10n),
      disabledColor: AppColors.surface,
      labelStyle: enabled ? null : TextStyle(color: AppColors.textTertiary),
    );
  }

  Widget _imageRow(PublishController controller) {
    return SizedBox(
      height: 72,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          for (int i = 0; i < controller.items.length; i++) _thumb(controller, i),
          if (controller.items.length < kMaxImages)
            Padding(
              padding: const EdgeInsets.only(right: AppSpacing.sm),
              child: OutlinedButton(
                key: const ValueKey('publishAddImage'),
                onPressed: () => _addImage(controller),
                child: const Icon(Icons.add_a_photo_outlined),
              ),
            ),
        ],
      ),
    );
  }

  Widget _thumb(PublishController controller, int index) {
    final item = controller.items[index];
    return Padding(
      padding: const EdgeInsets.only(right: AppSpacing.sm),
      child: Stack(
        children: [
          Container(
            width: 64,
            height: 64,
            color: AppColors.surface,
            child: Image.memory(item.bytes, fit: BoxFit.cover),
          ),
          if (item.status == ImageUploadStatus.uploading)
            const Positioned.fill(child: Center(child: CircularProgressIndicator(strokeWidth: 2))),
          if (item.status == ImageUploadStatus.failed)
            const Positioned(right: 0, top: 0, child: Icon(Icons.error, color: Colors.red, size: 18)),
          Positioned(
            right: 0,
            bottom: 0,
            child: GestureDetector(
              onTap: () => controller.removeImage(index),
              child: const Icon(Icons.cancel, size: 18, color: AppColors.textTertiary),
            ),
          ),
        ],
      ),
    );
  }
}
