import 'dart:async';
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
import '../../auth/domain/auth_state.dart';
import '../../pawcoin/presentation/pawcoin_controller.dart';
import '../data/age_card_reward_repository.dart';
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
        // 角色切图按物种分三套。入口虽然只放猫狗进来，档案的 petType 本来就是三值 ——
        // 兜底落 other 而不是硬当成猫，免得将来放开入口时卡面默默画错物种。
        final species = switch (profile.petType) {
          'DOG' => AgeCardSpecies.dog,
          'CAT' => AgeCardSpecies.cat,
          _ => AgeCardSpecies.other,
        };
        if (isDog) {
          return _SizePickerPage(
            petName: profile.name,
            onPicked: (size) => Navigator.of(context).push(MaterialPageRoute<void>(
              builder: (_) => AgeCardPreviewPage(
                petName: profile.name,
                avatarUrl: profile.avatarUrl,
                birthday: birthday,
                isDog: true,
                species: species,
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
          species: species,
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
class _SizePickerPage extends StatefulWidget {
  const _SizePickerPage({required this.petName, required this.onPicked});

  final String petName;
  final void Function(DogSizeClass) onPicked;

  @override
  State<_SizePickerPage> createState() => _SizePickerPageState();
}

/// UI 稿 P4：**先选、再点「Lanjutkan」确认**（两步），不是点一下档位就直接跳走 ——
/// 直接跳走的话手滑点错一档，就得退回来重选（2026-09-21 对稿修正）。
class _SizePickerPageState extends State<_SizePickerPage> {
  DogSizeClass? _selected;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final selected = _selected;
    return Scaffold(
      backgroundColor: AppColors.cream,
      appBar: AppBar(title: Text(l10n.ageCardSizePickerTitle)),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            Text(
              l10n.ageCardSizeQuestion(widget.petName),
              style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.ink2),
            ),
            const SizedBox(height: AppSpacing.lg),
            for (final size in DogSizeClass.values) ...[
              _SizeTile(
                size: size,
                label: dogSizeLabel(l10n, size),
                // 档位名下标注体重区间（AC1）—— 不标的话「中型」全凭感觉，
                // 而不同人对「中型狗」的理解能差一倍。
                range: dogSizeRange(l10n, size),
                selected: size == selected,
                onTap: () => setState(() => _selected = size),
              ),
              const SizedBox(height: AppSpacing.sm),
            ],
          ],
        ),
      ),
      bottomNavigationBar: Container(
        decoration: const BoxDecoration(
          color: AppColors.card,
          border: Border(top: BorderSide(color: AppColors.line2)),
        ),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.md),
            child: FilledButton(
              key: const ValueKey('ageCardSizeContinue'),
              // 没选之前禁用：点了没反应却不知道缺什么，比按钮灰着更糟。
              onPressed: selected == null ? null : () => widget.onPicked(selected),
              child: Text(l10n.ageCardSizeContinue),
            ),
          ),
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
    required this.selected,
    required this.onTap,
  });

  final DogSizeClass size;
  final String label;
  final String range;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(color: Color(0x0D2B2A27), offset: Offset(0, 2), blurRadius: 8),
          ],
        ),
        child: Material(
          color: AppColors.card,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
            side: selected
                ? const BorderSide(color: AppColors.mint, width: 1.5)
                : BorderSide.none,
          ),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: ValueKey('ageCardSize_${size.wire}'),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.lg, vertical: AppSpacing.md),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(label,
                            style: const TextStyle(
                                fontSize: 13.5,
                                fontWeight: FontWeight.w600,
                                color: AppColors.ink)),
                        const SizedBox(height: 2),
                        Text(range,
                            style: const TextStyle(fontSize: 11, color: AppColors.textTertiary)),
                      ],
                    ),
                  ),
                  // 单选圆点：未选 2px 灰边，选中 6px 品牌紫边（UI 稿 P4）。
                  Container(
                    key: ValueKey('ageCardSizeMark_${size.wire}'),
                    width: 20,
                    height: 20,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: selected ? AppColors.mint : AppColors.line,
                        width: selected ? 6 : 2,
                      ),
                    ),
                  ),
                ],
              ),
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
class AgeCardPreviewPage extends ConsumerStatefulWidget {
  const AgeCardPreviewPage({
    super.key,
    required this.petName,
    required this.birthday,
    required this.isDog,
    this.species,
    this.avatarUrl,
    this.size,
    this.today,
    this.initialCanvas = CardCanvas.story,
  });

  final String petName;
  final String? avatarUrl;
  final DateTime birthday;
  final bool isDog;

  /// 角色切图与信息胶囊用的物种。不传 ⇒ 按 [isDog] 退化成猫/狗两值
  /// （老调用点与测试不必逐个改）。
  final AgeCardSpecies? species;

  /// 狗的体型档；猫为 null。
  final DogSizeClass? size;

  /// 测试缝：固定「当日」。生产路径不传 → 取设备本地时区的今天（AD-A28.1）。
  final DateTime? today;

  /// 初始画布。**画布切换器已按 2026-09-23 拍板隐藏**（设计稿只出了 9:16），
  /// 1:1 的模板、导出管线、埋点值域 `square` 一律保留 —— 见下方 build 里的说明。
  /// 生产路径不传 → 9:16；1:1 目前只有测试会显式传进来。
  final CardCanvas initialCanvas;

  /// 出图测试缝（同分享卡那屏）：`toImage` 是真实引擎异步操作，
  /// 在 widget test 的 fake-async 时钟里永远不会完成。
  @visibleForTesting
  static Future<Uint8List?> Function(CardCanvas canvas)? captureForTest;

  @override
  ConsumerState<AgeCardPreviewPage> createState() => _AgeCardPreviewPageState();
}

class _AgeCardPreviewPageState extends ConsumerState<AgeCardPreviewPage> {
  final GlobalKey _boundaryKey = GlobalKey();

  /// 默认 9:16（Instagram Stories 是这个功能的主场景）。
  ///
  /// 切换器藏起来后这个字段在本类里不再被重新赋值，但**不要改成 final** ——
  /// 1:1 稿到位、切换器放开时它就要恢复可变（见 build 里注释掉的 SegmentedButton）。
  // ignore: prefer_final_fields
  late CardCanvas _canvas = widget.initialCanvas;
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

  /// 趣味文案：一段一句、与该段角色图配对（2026-09-23 产品拍板取消随机池，见 quipFor）。

  /// 分享成功后试着领奖（Story 5.3）。
  ///
  /// 🛡 **失败一律当作没发**：分享本身已经成功，绝不因为领奖这一步报错给用户。
  /// 🛡 发了才提示，没发**静默** —— 不告知原因（告知会诱导「攒着别分享」或「月初集中刷满」）。
  Future<void> _claimReward() async {
    int coins = 0;
    try {
      coins = await ref
          .read(ageCardRewardRepositoryProvider)
          // 幂等键 = 本次预览会话 + 这一次分享动作。重复上报同一次分享不会重复发。
          .reportShareForReward(_shareIdempotencyKey);
    } catch (_) {
      coins = 0;
    }
    if (!mounted || coins <= 0) return;
    // 余额变了：失效 PawCoin 缓存，免得 Toko / 商品详情页仍显示旧余额（code review #10，与身份证高清购买后的做法一致）
    ref.invalidate(pawCoinProvider);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(AppLocalizations.of(context).ageCardRewardToast(coins))),
    );
  }

  /// 一次分享动作一个幂等键。每次点分享重新生成 —— 同一张卡分享两次是两次行为，
  /// 是否都发由服务端的日上限说了算，不该被客户端的键顶掉。
  /// 一次分享动作一个幂等键（见 [_shareIt]）。
  String _shareIdempotencyKey = '';

  /// 卡面上的 Pawrent 名 = 当前登录用户昵称。
  ///
  /// 🔴 **拿不到就返回 null，卡上整行不显示** —— 不兜底成邮箱（PII，这张图会发给陌生人）、
  /// 也不填「Pawrent」这类占位（用户会以为自己的名字没存上）。
  /// 游客态走不到这一屏（受控前缀），但 profile 仍可能为空（冷启动恢复未完成）。
  String? get _pawrentName {
    final profile = ref.watch(authControllerProvider).profile;
    final name = profile?.nickname?.trim();
    if (name != null && name.isNotEmpty) return name;
    final display = profile?.displayName?.trim();
    return display == null || display.isEmpty ? null : display;
  }

  /// 埋点公共属性（AD-A26.4 值域）。
  Map<String, Object> _eventProps() => {
        'species': widget.isDog ? 'dog' : 'cat',
        if (widget.size != null) 'size_class': widget.size!.wire,
        'canvas': _canvas == CardCanvas.square ? 'square' : 'story',
      };

  Future<void> _shareIt() async {
    final l10n = AppLocalizations.of(context);
    setState(() => _busy = true);
    // 一次分享动作一个幂等键（服务端会再拼上 userId 作为全局唯一键）。
    _shareIdempotencyKey = 'age-card-${DateTime.now().microsecondsSinceEpoch}';
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
        // 领奖（Story 5.3）挂在同一个回调上，理由相同。
        onShared: (channel) {
          Analytics.capture('age_card_shared', {..._eventProps(), 'channel': channel});
          unawaited(_claimReward());
        },
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
            // 🔴 画布切换器**只是藏起来，基建一概不动**（2026-09-23 拍板）：设计稿这一版
            // 只出了 9:16 的角色与背景素材，1:1 没有稿、硬缩会把角色裁掉。
            // 保留的东西：[CardCanvas.square]、`AgeCardTemplate` 的等比排版、导出管线、
            // 埋点 `canvas` 的 `square` 值域（AD-A26.4）、story 5-2 AC4/AC7 的两档断言。
            // ⚠️ 设计补上 1:1 稿后，**把这段注释换回下面这个 SegmentedButton 即可**，
            //    不要因为「界面上看不见」就去删 square 那一路代码。
            // SegmentedButton<CardCanvas>(
            //   key: const ValueKey('ageCardRatioToggle'),
            //   segments: const [
            //     ButtonSegment(value: CardCanvas.story, label: Text('9:16')),
            //     ButtonSegment(value: CardCanvas.square, label: Text('1:1')),
            //   ],
            //   selected: {_canvas},
            //   onSelectionChanged: (s) => setState(() => _canvas = s.first),
            // ),
            const SizedBox(height: AppSpacing.md),
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
                      quip: quipFor(l10n, _age.stage),
                      species: widget.species ??
                          (widget.isDog ? AgeCardSpecies.dog : AgeCardSpecies.cat),
                      // 胶囊上只印**体重区间**（设计稿：`Anjing · 2 thn 3 bln · 9–23 kg`），
                      // 不印档位名 —— 档位名（Sedang/Besar）在选择页已经问过一次，
                      // 印在要发出去的卡上既占宽度又没有信息量。
                      sizeLabel: widget.size == null
                          ? null
                          : dogSizeRange(l10n, widget.size!),
                      pawrentName: _pawrentName,
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
