import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/analytics/analytics.dart';
import '../../../core/theme/colors.dart';
import '../../../core/theme/spacing.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/card_render/card_canvas.dart';
import '../../../shared/card_render/card_export.dart';
import '../../../shared/card_render/card_frame.dart';
import '../../../shared/card_render/card_render_pipeline.dart';
import '../data/profile_repository.dart';
import '../domain/age_card_quips.dart';
import '../domain/human_age.dart';
import 'widgets/age_card_template.dart';

/// 年龄卡入口（V1.3.0 批次 A · Story 5.2 · FR-65）。
///
/// 路由 `/profile/pet-insights/age-card`（Story 5.1 已把它落在受控前缀下）。
/// 进来先分岔：**狗要先选体型档**（第 3 年起的斜率取决于它），猫直接进预览。
class AgeCardPage extends ConsumerWidget {
  const AgeCardPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final async = ref.watch(petProfileProvider);
    return async.when(
      loading: () => const Scaffold(body: Center(child: CircularProgressIndicator())),
      error: (_, _) => Scaffold(
        appBar: AppBar(title: Text(l10n.ageCardPreviewTitle)),
        body: Center(child: Text(l10n.detailNetworkError)),
      ),
      data: (profile) {
        // 聚合页已经按物种把入口置灰了，走到这里的只可能是猫狗 + 有生日的档案。
        // 兜底仍留着：档案接口失败/被改坏时不该白屏。
        final birthday = profile?.birthday;
        if (profile == null || birthday == null) {
          return Scaffold(
            appBar: AppBar(title: Text(l10n.ageCardPreviewTitle)),
            body: Center(child: Text(l10n.detailNetworkError)),
          );
        }
        final isDog = profile.petType == 'DOG';
        if (isDog) {
          return _SizePickerPage(
            petName: profile.name,
            onPicked: (size) => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => AgeCardPreviewPage(
                petName: profile.name,
                avatarUrl: profile.avatarUrl,
                birthday: birthday,
                isDog: true,
                size: size,
              ),
            )),
          );
        }
        return AgeCardPreviewPage(
          petName: profile.name,
          avatarUrl: profile.avatarUrl,
          birthday: birthday,
          isDog: false,
        );
      },
    );
  }
}

/// 狗的体型四选一（AC1）。
///
/// 🔴 **所选档位不落档案字段、不持久化、不校验**（AD-A18.2）——
/// 只在本次生成中有效。用户这次选中型、下次选大型是他自己的事；
/// 档案里不该因此多出一个说不清的字段。
class _SizePickerPage extends StatelessWidget {
  const _SizePickerPage({required this.petName, required this.onPicked});

  final String petName;
  final void Function(DogSizeClass) onPicked;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.ageCardPreviewTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            Text(
              l10n.ageCardSizeQuestion(petName),
              style: const TextStyle(
                  fontSize: 18, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
            const SizedBox(height: AppSpacing.lg),
            for (final size in DogSizeClass.values) ...[
              _SizeTile(
                size: size,
                label: dogSizeLabel(l10n, size),
                // 档位名旁标注体重区间（AC1）—— 不标的话「中型」全凭感觉，
                // 而不同人对「中型狗」的理解能差一倍。
                range: dogSizeRange(l10n, size),
                onTap: () => onPicked(size),
              ),
              const SizedBox(height: AppSpacing.sm),
            ],
          ],
        ),
      ),
    );
  }
}

/// 体型档名（本地化）。
String dogSizeLabel(AppLocalizations l10n, DogSizeClass size) => switch (size) {
      DogSizeClass.small => l10n.ageCardSizeSmall,
      DogSizeClass.medium => l10n.ageCardSizeMedium,
      DogSizeClass.large => l10n.ageCardSizeLarge,
      DogSizeClass.xlarge => l10n.ageCardSizeXlarge,
    };

/// 体重区间标注（本地化）。
String dogSizeRange(AppLocalizations l10n, DogSizeClass size) {
  String kg(double v) => v.toStringAsFixed(0);
  if (size.minKg == null) return l10n.ageCardSizeUnderKg(kg(size.maxKg!));
  if (size.maxKg == null) return l10n.ageCardSizeOverKg(kg(size.minKg!));
  return l10n.ageCardSizeBetweenKg(kg(size.minKg!), kg(size.maxKg!));
}

class _SizeTile extends StatelessWidget {
  const _SizeTile({
    required this.size,
    required this.label,
    required this.range,
    required this.onTap,
  });

  final DogSizeClass size;
  final String label;
  final String range;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(14),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('ageCardSize_${size.wire}'),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Row(
              children: [
                Expanded(
                  child: Text(label,
                      style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: AppColors.ink)),
                ),
                Text(range,
                    style: const TextStyle(fontSize: 13, color: AppColors.textTertiary)),
              ],
            ),
          ),
        ),
      );
}

/// 年龄卡预览（AC4）：AppBar + 画布区 + 底部吸底分享按钮。
///
/// 结构与画布切换**照搬内容分享卡那一屏**（`share_card_preview_page.dart`），
/// 出图走同一套 `shared/card_render/` 基建 —— 不另起一套（AC4）。
///
/// ⚠️ **必须有这一屏**：出图靠截屏式导出，卡面得先真的画在屏幕上才能截。
/// 藏在屏幕外 offstage 是不画的，`toImage` 会拿到空图。
class AgeCardPreviewPage extends StatefulWidget {
  const AgeCardPreviewPage({
    super.key,
    required this.petName,
    required this.birthday,
    required this.isDog,
    this.avatarUrl,
    this.size,
    this.today,
    this.quipRandom,
  });

  final String petName;
  final String? avatarUrl;
  final DateTime birthday;
  final bool isDog;

  /// 狗的体型档；猫为 null。
  final DogSizeClass? size;

  /// 测试缝：固定「当日」。生产路径不传 → 取设备本地时区的今天（AD-A28.1）。
  final DateTime? today;

  /// 测试缝：固定随机源，让「取了哪一条文案」可断言。
  final Random? quipRandom;

  /// 出图测试缝（同分享卡那屏）：`toImage` 是真实引擎异步操作，
  /// 在 widget test 的 fake-async 时钟里永远不会完成。
  @visibleForTesting
  static Future<Uint8List?> Function(CardCanvas canvas)? captureForTest;

  @override
  State<AgeCardPreviewPage> createState() => _AgeCardPreviewPageState();
}

class _AgeCardPreviewPageState extends State<AgeCardPreviewPage> {
  final GlobalKey _boundaryKey = GlobalKey();

  /// 默认 9:16（Instagram Stories 是这个功能的主场景）。
  CardCanvas _canvas = CardCanvas.story;
  bool _busy = false;

  late final HumanAgeResult _age = resolveHumanAge(
    birthday: widget.birthday,
    // 🔴 「当日」取**设备本地时区**（AD-A28.1）：娱乐工具、不涉资金，
    // 用户看到的应当是他自己日历上的今天。
    // ⚠️ 与 Story 5.3 的 WIB 不是一回事，不得互相套用 —— 那边是资金口径。
    today: widget.today ?? DateTime.now(),
    isDog: widget.isDog,
    size: widget.size,
  );

  /// 🔴 **每次进页面取一次，之后不再变**：放进 build 的话每帧都换一句，
  /// 用户还没看完就跳字了。"每次生成随机" 指的是每次**打开**，不是每一帧。
  late final AgeCardQuip _quip = pickQuip(_age.stage, random: widget.quipRandom);

  /// 埋点公共属性（AD-A26.4 值域）。
  Map<String, Object> _eventProps() => {
        'species': widget.isDog ? 'dog' : 'cat',
        if (widget.size != null) 'size_class': widget.size!.wire,
        'canvas': _canvas == CardCanvas.square ? 'square' : 'story',
      };

  Future<void> _shareIt() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _busy = true);
    try {
      final capture = AgeCardPreviewPage.captureForTest;
      final bytes = capture != null
          ? await capture(_canvas)
          : await CardRenderPipeline.capture(boundaryKey: _boundaryKey, canvas: _canvas);
      if (!mounted) return;
      if (bytes == null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(l10n.shareCardExportError)));
        return;
      }
      // 出图成功即上报（AC9）。
      Analytics.capture('age_card_generated', _eventProps());

      final box = context.findRenderObject() as RenderBox?;
      final origin = box != null ? box.localToGlobal(Offset.zero) & box.size : null;
      await CardExport.showSheet(
        context,
        bytes: bytes,
        name: 'tailtopia_age_card',
        shareOrigin: origin,
        // 🔴 分享**只在系统面板回调成功后**才报，取消不报 ——
        // 报在出图那刻等于"看一眼就退出也算分享"，这个数只会高估且无法事后修正。
        onShared: (channel) =>
            Analytics.capture('age_card_shared', {..._eventProps(), 'channel': channel}),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      backgroundColor: AppColors.cream2,
      appBar: AppBar(title: Text(l10n.ageCardPreviewTitle)),
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: SegmentedButton<CardCanvas>(
                key: const ValueKey('ageCardRatioToggle'),
                segments: const [
                  ButtonSegment(value: CardCanvas.story, label: Text('9:16')),
                  ButtonSegment(value: CardCanvas.square, label: Text('1:1')),
                ],
                selected: {_canvas},
                onSelectionChanged: (s) => setState(() => _canvas = s.first),
              ),
            ),
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 48),
                  child: CardFrame(
                    // key 随画布变：换尺寸时强制重建，别让 State 复用旧画布的布局。
                    key: ValueKey(_canvas),
                    boundaryKey: _boundaryKey,
                    canvas: _canvas,
                    // 🔴 **不加水印**：水印只属 KTP / 护照那类付费保护场景。
                    child: AgeCardTemplate(
                      canvas: _canvas,
                      petName: widget.petName,
                      avatarUrl: widget.avatarUrl,
                      age: _age,
                      quip: _quip(l10n, widget.petName),
                      sizeLabel: widget.size == null
                          ? null
                          : '${dogSizeLabel(l10n, widget.size!)} · '
                              '${dogSizeRange(l10n, widget.size!)}',
                    ),
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  key: const ValueKey('ageCardShareCta'),
                  onPressed: _busy ? null : _shareIt,
                  child: Text(l10n.ageCardShareCta),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
