import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
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
      appBar: AppBar(
        backgroundColor: AppColors.base,
        centerTitle: true,
        title: const Text('Diagnosa AI',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
      ),
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
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
      children: <Widget>[
        // 紫渐变速度横幅（ai-upload.html：⚡ Hasil dalam 15 detik）。
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            gradient: const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [AppColors.mint, AppColors.mint500],
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('⚡ Hasil dalam 15 detik',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white)),
              const SizedBox(height: 4),
              Text('Upload foto + cerita gejala → evaluasi instan hijau / kuning / merah.',
                  style: TextStyle(fontSize: 12, height: 1.5, color: Colors.white.withValues(alpha: 0.85))),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        // FOTO GEJALA (MAKS. 3) 大写 label + 3 列方格。
        Text('FOTO GEJALA (MAKS. $kTriageMaxImages)',
            style: AppTypography.micro.copyWith(
                color: AppColors.ink2, fontWeight: FontWeight.w700, letterSpacing: 0.5)),
        const SizedBox(height: AppSpacing.sm),
        _ImageGrid(
          images: draft.images,
          onAdd: _pickSource,
          onRemove: (i) => ref.read(triageUploadProvider.notifier).removeImageAt(i),
        ),
        const SizedBox(height: AppSpacing.lg),
        // CERITAKAN GEJALA 大写 label + 紫边框文本框。
        Text('CERITAKAN GEJALA',
            style: AppTypography.micro.copyWith(
                color: AppColors.ink2, fontWeight: FontWeight.w700, letterSpacing: 0.5)),
        const SizedBox(height: AppSpacing.sm),
        TextField(
          key: const ValueKey('triageSymptomField'),
          controller: _symptomController,
          maxLines: 5,
          maxLength: _maxSymptomChars,
          onChanged: (v) => ref.read(triageUploadProvider.notifier).setSymptom(v),
          decoration: InputDecoration(
            hintText: l10n.triageSymptomHint,
            hintStyle: const TextStyle(color: AppColors.muted),
            contentPadding: const EdgeInsets.all(14),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: AppColors.line, width: 1.5),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
              borderSide: const BorderSide(color: AppColors.mint, width: 1.5),
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        // 黄字免责（⚠️ Hasil AI bersifat referensi...）。
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('⚠️ ', style: TextStyle(fontSize: 12)),
            Expanded(
              child: Text(l10n.triageDisclaimer,
                  style: const TextStyle(fontSize: 11, height: 1.5, color: AppColors.tipsBadgeText)),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            key: const ValueKey('triageSubmit'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.mint,
              foregroundColor: AppColors.onAccent,
              disabledBackgroundColor: AppColors.line,
              padding: const EdgeInsets.symmetric(vertical: 15),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
            ),
            onPressed: draft.canSubmit ? _submit : null,
            child: const Text('Analisis Sekarang →',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ),
        if (!draft.canSubmit) ...<Widget>[
          const SizedBox(height: AppSpacing.sm),
          Text(l10n.triageNeedInput,
              style: AppTypography.caption, textAlign: TextAlign.center),
        ],
      ],
    );
  }
}

/// 症状图片 3 列方格（ai-upload.html FOTO GEJALA）：已选图带 ✗ 删除角标 + 虚线 Tambah 格。
class _ImageGrid extends StatelessWidget {
  const _ImageGrid({required this.images, required this.onAdd, required this.onRemove});

  final List<TriageDraftImage> images;
  final VoidCallback onAdd;
  final void Function(int) onRemove;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final cells = <Widget>[
      for (int i = 0; i < images.length; i++)
        Expanded(
          child: AspectRatio(
            aspectRatio: 1,
            child: Stack(
              children: <Widget>[
                Positioned.fill(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: Image.memory(images[i].bytes, fit: BoxFit.cover),
                  ),
                ),
                Positioned(
                  right: 5,
                  top: 5,
                  child: GestureDetector(
                    key: ValueKey('triageRemoveImage$i'),
                    onTap: () => onRemove(i),
                    child: const CircleAvatar(
                      radius: 11,
                      backgroundColor: AppColors.popRed,
                      child: Icon(Icons.close, size: 14, color: Colors.white),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      if (images.length < kTriageMaxImages)
        Expanded(
          child: AspectRatio(
            aspectRatio: 1,
            child: GestureDetector(
              key: const ValueKey('triageAddImage'),
              onTap: onAdd,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: AppColors.cream2,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: AppColors.dashedViolet, width: 1.5),
                ),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: <Widget>[
                    const Icon(Icons.add, size: 22, color: AppColors.mint),
                    const SizedBox(height: 3),
                    Text(l10n.triageAddPhoto,
                        style: AppTypography.micro.copyWith(color: AppColors.mint)),
                  ],
                ),
              ),
            ),
          ),
        ),
    ];
    // 补齐到 3 列（占位空格保持等宽方格对齐）。
    while (cells.length < kTriageMaxImages) {
      cells.add(const Expanded(child: SizedBox()));
    }
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (int i = 0; i < cells.length; i++) ...[
          if (i > 0) const SizedBox(width: 10),
          cells[i],
        ],
      ],
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
