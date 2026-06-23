import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../core/theme/typography.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/utils/image_processor.dart';
import '../../../shared/utils/media_permission.dart';
import '../../../shared/widgets/dashed_rect.dart';
import '../../media/domain/media_upload_use_case.dart';
import '../data/triage_repository.dart';
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
  static const int _maxSymptomChars = 500; // 原型 ai-upload：「/ 500」
  final TextEditingController _symptomController = TextEditingController();

  @override
  void initState() {
    super.initState();
    // 每次进入上传页都从空闲态重开：清上次结果 + 草稿，避免「重进分诊直接看到旧结果」。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      ref.read(triageResultProvider.notifier).reset();
      ref.read(triageUploadProvider.notifier).reset();
    });
    // Debug 截图钩子（仅 debug + flag）：自动提交一次，直达分诊结果态。
    // 配 DEV_REAL_LOGIN + DEV_TRIAGE_SYMPTOM=<真症状>，自动注入症状并提交，
    // 真打后端 + live Gemini，落真实结果页（红/黄/绿）。
    // 生产/测试不编译进逻辑（flag 默认空）。
    if (kDebugMode && const bool.fromEnvironment('DEV_TRIAGE_AUTO')) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        const devSymptom = String.fromEnvironment('DEV_TRIAGE_SYMPTOM');
        if (devSymptom.isNotEmpty) {
          ref.read(triageUploadProvider.notifier).setSymptom(devSymptom);
        }
        _submit();
      });
    }
  }

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
    final state = ref.watch(triageResultProvider);
    final phase = state.phase;
    // 绿/黄结果页自带彩色 header（含返回），隐藏顶栏避免双返回 + 多余「AI Diagnosis」。
    // 红色（沉浸层 + 摘要）与 表单/等待/降级态 仍保留顶栏返回。
    final greenYellowResult = phase == TriagePhase.done &&
        state.result?.dangerLevel != null &&
        state.result!.dangerLevel != DangerLevel.red;

    return Scaffold(
      backgroundColor: AppColors.base,
      appBar: greenYellowResult
          ? null
          : AppBar(
              backgroundColor: AppColors.base,
              centerTitle: true,
              title: Text(l10n.triageUploadTitle,
                  style: const TextStyle(
                      fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink)),
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
              Text('⚡ ${l10n.triageSpeedBannerTitle}',
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Colors.white)),
              const SizedBox(height: 4),
              Text(l10n.triageSpeedBannerBody,
                  style: TextStyle(fontSize: 12, height: 1.5, color: Colors.white.withValues(alpha: 0.85))),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        // FOTO GEJALA (MAKS. 3) 大写 label + 3 列方格。
        Text(l10n.triagePhotoSectionLabel(kTriageMaxImages),
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
        Text(l10n.triageSymptomSectionLabel,
            style: AppTypography.micro.copyWith(
                color: AppColors.ink2, fontWeight: FontWeight.w700, letterSpacing: 0.5)),
        const SizedBox(height: AppSpacing.sm),
        // 原型：紫边框 1.5 + 圆角13 + 紫色光晕阴影(0 0 0 3px rgba(132,94,201,.07))；字数在框外右下。
        Container(
          decoration: BoxDecoration(
            color: AppColors.card,
            borderRadius: BorderRadius.circular(13),
            border: Border.all(color: AppColors.mint, width: 1.5),
            boxShadow: [
              BoxShadow(
                  color: AppColors.mint.withValues(alpha: 0.07),
                  blurRadius: 0,
                  spreadRadius: 3),
            ],
          ),
          padding: const EdgeInsets.all(14),
          child: TextField(
            key: const ValueKey('triageSymptomField'),
            controller: _symptomController,
            minLines: 4,
            maxLines: 6,
            maxLength: _maxSymptomChars,
            onChanged: (v) => ref.read(triageUploadProvider.notifier).setSymptom(v),
            style: const TextStyle(fontSize: 13, color: AppColors.ink, height: 1.65),
            decoration: InputDecoration(
              isCollapsed: true,
              hintText: l10n.triageSymptomHint,
              hintStyle: const TextStyle(color: AppColors.muted, fontSize: 13, height: 1.65),
              border: InputBorder.none,
              counterText: '',
            ),
          ),
        ),
        const SizedBox(height: 6),
        // 原型 charcount：框外右对齐「已用 / 总数」，弱灰。
        Align(
          alignment: Alignment.centerRight,
          child: Text('${_symptomController.text.length} / $_maxSymptomChars',
              style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
        ),
        const SizedBox(height: AppSpacing.md),
        // 原型免责承载位：灰底盒(bg-muted #EFEDF3) + ⚠️ + 弱灰字(tertiary)。
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: const Color(0xFFEFEDF3),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('⚠️', style: TextStyle(fontSize: 13)),
              const SizedBox(width: 8),
              Expanded(
                child: Text(l10n.triageDisclaimer,
                    style: const TextStyle(
                        fontSize: 11, height: 1.5, color: AppColors.textTertiary)),
              ),
            ],
          ),
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
            child: Text('${l10n.triageAnalyzeNow} →',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ),
        // 注：原型 ai-upload 按钮下方无文案，移除原「请先描述症状/加图」提示。
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
              // 原型 pcell-add：2px 虚线 #C2B0EC 边框 + 紫浅底 + ＋ + 「Tambah」。
              child: CustomPaint(
                painter: DashedRRectPainter(
                    color: AppColors.dashedViolet, radius: 12, dash: 5, gap: 4),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: AppColors.cream2,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: <Widget>[
                      const Icon(Icons.add, size: 22, color: AppColors.mint),
                      const SizedBox(height: 3),
                      Text(l10n.tabAdd,
                          style: AppTypography.micro.copyWith(color: AppColors.mint)),
                    ],
                  ),
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
