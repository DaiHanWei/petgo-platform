import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/rounded.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/image_processor.dart';
import '../../../shared/utils/media_permission.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../domain/triage_result_controller.dart';
import '../domain/triage_result_state.dart';
import '../domain/triage_upload_controller.dart';
import '../domain/triage_upload_state.dart';
import 'triage_result_view.dart';

/// AI 分诊上传页（Story 4.3 · F2/F3/F4/F5）。图文输入 + STS 直传私密桶 + 提交 → 等待 spinner /
/// 超时 / 异常降级（重提交复用上次内容不重传 + 软引导兽医）+ 结果交棒占位（4.4/4.5）。
class TriageUploadPage extends ConsumerStatefulWidget {
  const TriageUploadPage({super.key});

  @override
  ConsumerState<TriageUploadPage> createState() => _TriageUploadPageState();
}

class _TriageUploadPageState extends ConsumerState<TriageUploadPage> {
  static const int _maxSymptomChars = 2000;
  final TextEditingController _symptomController = TextEditingController();

  @override
  void dispose() {
    _symptomController.dispose();
    super.dispose();
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _addImage(MediaSource source) async {
    final l10n = AppLocalizations.of(context);
    try {
      final bytes = await ref
          .read(mediaUploadUseCaseProvider)
          .pickAndProcess(source: source, context: context);
      if (bytes == null) return; // 取消或权限被拒（已弹引导）
      ref.read(triageUploadProvider.notifier).addImage(bytes);
    } on ImageProcessingException {
      _toast(l10n.mediaImageTooLarge);
    }
  }

  Future<void> _pickSource() async {
    final l10n = AppLocalizations.of(context);
    final source = await showModalBottomSheet<MediaSource>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            ListTile(
              key: const ValueKey('triagePickCamera'),
              leading: const Icon(Icons.photo_camera_outlined),
              title: Text(l10n.triagePhotoFromCamera),
              onTap: () => Navigator.pop(ctx, MediaSource.camera),
            ),
            ListTile(
              key: const ValueKey('triagePickGallery'),
              leading: const Icon(Icons.photo_library_outlined),
              title: Text(l10n.triagePhotoFromGallery),
              onTap: () => Navigator.pop(ctx, MediaSource.gallery),
            ),
          ],
        ),
      ),
    );
    if (source != null) {
      await _addImage(source);
    }
  }

  Future<void> _submit() async {
    final l10n = AppLocalizations.of(context);
    try {
      await ref.read(triageUploadProvider.notifier).submit();
    } catch (_) {
      _toast(l10n.mediaUploadFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final phase = ref.watch(triageResultProvider).phase;

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: AppBar(title: Text(l10n.triageEntryAiTitle)),
      body: switch (phase) {
        TriagePhase.submitting || TriagePhase.polling => _WaitingView(message: l10n.triageAnalyzing),
        TriagePhase.timedOut => _DegradedView(
            valueKey: 'triageTimeout',
            title: l10n.triageTimeoutTitle,
            body: l10n.triageTimeoutBody,
            primaryLabel: l10n.triageResubmit,
            onPrimary: _submit,
          ),
        TriagePhase.failed || TriagePhase.error => _DegradedView(
            valueKey: 'triageError',
            title: l10n.triageErrorTitle,
            body: l10n.triageErrorBody,
            primaryLabel: l10n.triageResubmit,
            onPrimary: _submit,
            softGuideLabel: l10n.triageContactVet, // 软引导兽医（占位，Epic 5 接通）
          ),
        TriagePhase.done => _buildResult(),
        TriagePhase.idle => _buildForm(l10n),
      },
    );
  }

  Widget _buildResult() {
    final state = ref.watch(triageResultProvider);
    final result = state.result;
    if (result == null) {
      return const SizedBox.shrink();
    }
    // 绿/黄走结果三态卡；红色交棒 4.5（TriageResultView 内对 RED 占位，不软化）。
    return TriageResultView(result: result, triageId: state.triageId);
  }

  Widget _buildForm(AppLocalizations l10n) {
    final draft = ref.watch(triageUploadProvider);
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.screenEdge),
      children: <Widget>[
        // 设计稿 S12：先图后文——图片上传在上、症状描述在下。
        Text(l10n.triagePhotoLimit, style: AppTypography.caption),
        const SizedBox(height: AppSpacing.sm),
        _ImageRow(
          images: draft.images,
          onAdd: _pickSource,
          onRemove: (i) => ref.read(triageUploadProvider.notifier).removeImageAt(i),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text(l10n.triageSymptomLabel, style: AppTypography.title),
        const SizedBox(height: AppSpacing.sm),
        TextField(
          key: const ValueKey('triageSymptomField'),
          controller: _symptomController,
          maxLines: 5,
          maxLength: _maxSymptomChars,
          onChanged: (v) => ref.read(triageUploadProvider.notifier).setSymptom(v),
          decoration: InputDecoration(
            hintText: l10n.triageSymptomHint,
            border: const OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: AppSpacing.xl),
        FilledButton(
          key: const ValueKey('triageSubmit'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.accentConsult),
          onPressed: draft.canSubmit ? _submit : null,
          child: Text(l10n.triageSubmit),
        ),
        if (!draft.canSubmit) ...<Widget>[
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.triageNeedInput,
              style: AppTypography.caption, textAlign: TextAlign.center),
        ],
        const SizedBox(height: AppSpacing.lg),
        // 免责声明前置（NFR-9）：小号字不干扰主流程。
        Text(l10n.triageDisclaimer, style: AppTypography.disclaimer),
      ],
    );
  }
}

class _ImageRow extends StatelessWidget {
  const _ImageRow({required this.images, required this.onAdd, required this.onRemove});

  final List<TriageDraftImage> images;
  final VoidCallback onAdd;
  final void Function(int) onRemove;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return SizedBox(
      height: 80,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: <Widget>[
          for (int i = 0; i < images.length; i++)
            Padding(
              padding: const EdgeInsets.only(right: AppSpacing.sm),
              child: Stack(
                children: <Widget>[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(AppRounded.sm),
                    child: Image.memory(images[i].bytes,
                        width: 72, height: 72, fit: BoxFit.cover),
                  ),
                  Positioned(
                    right: 0,
                    top: 0,
                    child: GestureDetector(
                      key: ValueKey('triageRemoveImage$i'),
                      onTap: () => onRemove(i),
                      child: const CircleAvatar(
                        radius: 11,
                        backgroundColor: Colors.black54,
                        child: Icon(Icons.close, size: 14, color: Colors.white),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          if (images.length < kTriageMaxImages)
            OutlinedButton(
              key: const ValueKey('triageAddImage'),
              onPressed: onAdd,
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: <Widget>[
                  const Icon(Icons.add_a_photo_outlined, size: 20),
                  Text(l10n.triageAddPhoto, style: AppTypography.micro),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _WaitingView extends StatelessWidget {
  const _WaitingView({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      key: const ValueKey('triageWaiting'),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          const CircularProgressIndicator(color: AppColors.accentConsult),
          const SizedBox(height: AppSpacing.lg),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
            child: Text(message, style: AppTypography.body, textAlign: TextAlign.center),
          ),
        ],
      ),
    );
  }
}

class _DegradedView extends StatelessWidget {
  const _DegradedView({
    required this.valueKey,
    required this.title,
    required this.body,
    required this.primaryLabel,
    required this.onPrimary,
    this.softGuideLabel,
  });

  final String valueKey;
  final String title;
  final String body;
  final String primaryLabel;
  final VoidCallback onPrimary;
  final String? softGuideLabel;

  @override
  Widget build(BuildContext context) {
    return Center(
      key: ValueKey(valueKey),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Text(title, style: AppTypography.title, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.sm),
            Text(body, style: AppTypography.caption, textAlign: TextAlign.center),
            const SizedBox(height: AppSpacing.xl),
            FilledButton(
              key: const ValueKey('triageResubmit'),
              style: FilledButton.styleFrom(backgroundColor: AppColors.accentConsult),
              onPressed: onPrimary,
              child: Text(primaryLabel),
            ),
            if (softGuideLabel != null) ...<Widget>[
              const SizedBox(height: AppSpacing.sm),
              TextButton(
                key: const ValueKey('triageContactVet'),
                onPressed: () {}, // 软引导兽医占位（Epic 5 接通在线兽医）
                child: Text(softGuideLabel!),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
