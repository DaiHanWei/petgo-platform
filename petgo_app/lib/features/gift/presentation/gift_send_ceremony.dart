import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:rive/rive.dart' as rive;

import '../../../core/rive/rive_runtime.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_image.dart';

/// 礼盒（合盖）图（V1.5.0 gift-pack · D-14）：正式图到了同名覆盖即可。
const String kGiftBoxClosedAsset = 'assets/gift/gift_box_closed.webp';

/// 送出动画 Rive（画板 `GiftSend`、状态机 `SendSM`；礼物图数据绑定 `Gift.giftImage` / 资源名 `plushy-image`）。
const String kSendGiftRive = 'assets/rive/send_gift.riv';

/// 送出动画兜底：设计师补 `sendDone` 事件前一律靠它（现文件约 5 秒播完）。
const Duration giftSendCeremonyFallback = Duration(milliseconds: 5500);

/// 送出动画兜底的硬上限（从进页起算）：Rive 一直没就绪也不会卡住。
const Duration giftSendCeremonyHardCap = Duration(seconds: 10);

/// 送出动画区域的构建器（测试注入替身，不渲染 Rive；替身视为进页即就绪）。[onDone] 由动画结束事件调用；[onError] 加载失败。
typedef GiftSendRiveBuilder = Widget Function(
    BuildContext context, String? giftIconUrl, VoidCallback onDone, void Function(Object error) onError);

/// 送出动画（E1 / E2）→ 送达页（E3）（V1.5.0 gift-pack Story 2-3 · D-14）。全屏白底。
///
/// - 结束：Rive 事件 `sendDone` 或 [giftSendCeremonyFallback] 兜底（以真实时间算，切后台回来照常到点），先到先走；
///   「Lewati」任意时刻直接到 E3。
/// - 减弱动态效果 / Rive 加载失败 → 直接 E3。
/// - E3「Lanjut」→ pop（回到进入送礼前的页面由调用方保证：本页以 push 进入，且前面的送礼页已收起）。
class GiftSendCeremonyPage extends StatefulWidget {
  const GiftSendCeremonyPage({
    super.key,
    required this.petName,
    this.petAvatarUrl,
    this.giftIconUrl,
    this.senderExp = 0,
    this.riveBuilder,
    this.fallback = giftSendCeremonyFallback,
  });

  final String petName;
  final String? petAvatarUrl;
  final String? giftIconUrl;

  /// 本单送礼人 EXP（0 → 不显示 EXP 行）。
  final int senderExp;
  final GiftSendRiveBuilder? riveBuilder;
  final Duration fallback;

  @override
  State<GiftSendCeremonyPage> createState() => _GiftSendCeremonyPageState();
}

class _GiftSendCeremonyPageState extends State<GiftSendCeremonyPage> with WidgetsBindingObserver {
  bool _done = false;
  bool _decided = false;
  Timer? _timer;
  DateTime? _deadline;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_decided) return;
    _decided = true;
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    if (reduce) {
      _done = true;
      return;
    }
    WidgetsBinding.instance.addObserver(this);
    if (widget.riveBuilder != null) {
      _armFallback();
    } else {
      // 真 Rive：兜底从「动画就绪开始播」起算（code review #1：图下载 + 解码可能要几秒，不能从进页算），
      // 另设进页起算的硬上限，Rive 一直没就绪也不卡住。
      _hardCap = Timer(giftSendCeremonyHardCap, _finish);
    }
  }

  Timer? _hardCap;

  /// 开始 5.5 秒兜底计时（只开一次）。切后台回来按真实时间补判（[didChangeAppLifecycleState]）。
  void _armFallback() {
    if (_deadline != null || _done) return;
    _deadline = DateTime.now().add(widget.fallback);
    _timer = Timer(widget.fallback, _finish);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      final d = _deadline;
      if (d != null && !DateTime.now().isBefore(d)) _finish();
    }
  }

  void _finish() {
    if (!mounted || _done) return;
    _timer?.cancel();
    _hardCap?.cancel();
    setState(() => _done = true);
  }

  @override
  void dispose() {
    _timer?.cancel();
    _hardCap?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return PopScope(
      canPop: _done,
      child: Scaffold(
        backgroundColor: Colors.white,
        body: SafeArea(
          child: _done ? _delivered(l10n) : _playing(l10n),
        ),
      ),
    );
  }

  Widget _playing(AppLocalizations l10n) {
    final builder = widget.riveBuilder ?? _defaultRive;
    return Stack(children: [
      Positioned.fill(
        key: const ValueKey('giftSendCeremonyPlaying'),
        child: builder(context, widget.giftIconUrl, _finish, (_) => _finish()),
      ),
      Positioned(
        left: 0,
        right: 0,
        bottom: 12,
        child: Center(
          child: TextButton(
            key: const ValueKey('giftSendCeremonySkip'),
            style: TextButton.styleFrom(minimumSize: const Size(88, 44), foregroundColor: AppColors.mint),
            onPressed: _finish,
            child: Text(l10n.archivePromptSkip, style: const TextStyle(fontWeight: FontWeight.w600)),
          ),
        ),
      ),
    ]);
  }

  Widget _defaultRive(BuildContext context, String? iconUrl, VoidCallback onDone, void Function(Object) onError) =>
      _SendGiftRive(giftIconUrl: iconUrl, onDone: onDone, onError: onError, onReady: _armFallback);

  Widget _delivered(AppLocalizations l10n) {
    const sentinel = '\u0000';
    final parts = l10n.giftDeliveredTitle(sentinel).split(sentinel);
    return Column(
      key: const ValueKey('giftDeliveredPage'),
      children: [
        const Spacer(),
        SizedBox(
          width: 150,
          height: 150,
          child: Stack(clipBehavior: Clip.none, children: [
            CircleAvatar(
              radius: 64,
              backgroundColor: AppColors.mintTint,
              backgroundImage: AppImage.provider(widget.petAvatarUrl, thumbWidth: 256),
            ),
            Positioned(
              right: -6,
              bottom: -6,
              child: Image.asset(kGiftBoxClosedAsset,
                  key: const ValueKey('giftDeliveredBox'),
                  width: 64,
                  errorBuilder: (_, _, _) => const Icon(Icons.card_giftcard, size: 48, color: AppColors.mint)),
            ),
          ]),
        ),
        const SizedBox(height: 24),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text.rich(
            TextSpan(children: [
              TextSpan(text: parts.first, style: const TextStyle(fontSize: 15, color: AppColors.muted)),
              if (parts.length > 1) ...[
                TextSpan(
                    text: widget.petName,
                    style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: AppColors.mint)),
                TextSpan(text: parts.sublist(1).join(), style: const TextStyle(fontSize: 15, color: AppColors.muted)),
              ],
            ]),
            key: const ValueKey('giftDeliveredTitle'),
            textAlign: TextAlign.center,
          ),
        ),
        if (widget.senderExp > 0) ...[
          const SizedBox(height: 12),
          Text(l10n.giftYouGotExp(widget.senderExp),
              key: const ValueKey('giftDeliveredExp'),
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
        ],
        const Spacer(),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          child: SizedBox(
            width: double.infinity,
            height: 48,
            child: FilledButton(
              key: const ValueKey('giftDeliveredContinue'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.mint,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
              ),
              onPressed: () => Navigator.of(context).pop(),
              child: Text(l10n.onboardingContinue, style: const TextStyle(fontWeight: FontWeight.w700)),
            ),
          ),
        ),
      ],
    );
  }
}

/// Rive 送出动画（接法照 `stamp_ceremony.dart`）：数据绑定 `giftImage`，退回 `assetLoader` 按 `plushy-image` 换图；
/// 监听 `sendDone`。礼物图下载失败 → 用文件自带默认图，不阻塞。
class _SendGiftRive extends StatefulWidget {
  const _SendGiftRive({required this.giftIconUrl, required this.onDone, required this.onError, required this.onReady});

  final String? giftIconUrl;
  final VoidCallback onDone;

  /// 动画就绪开始播（兜底计时由此起算）。
  final VoidCallback onReady;
  final void Function(Object error) onError;

  @override
  State<_SendGiftRive> createState() => _SendGiftRiveState();
}

class _SendGiftRiveState extends State<_SendGiftRive> {
  static const _artboard = 'GiftSend';
  static const _stateMachine = 'SendSM';
  static const _imageAssetName = 'plushy-image';

  rive.File? _file;
  rive.RiveWidgetController? _controller;
  rive.ViewModelInstance? _vm;

  @override
  void initState() {
    super.initState();
    if (!RiveRuntime.available) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onError(StateError('Rive runtime unavailable'));
      });
      return;
    }
    _load();
  }

  Future<rive.RenderImage?> _giftImage() async {
    final url = widget.giftIconUrl;
    if (url == null || url.isEmpty) return null;
    try {
      // 取 OSS 1000 宽缩放版（动画槽位按 1000×1000 设计，原图可能更大；非 OSS 地址原样）。
      final sized = AppImage.ossResized(url, width: 1000);
      final data = await NetworkAssetBundle(Uri.parse(sized)).load(sized).timeout(const Duration(seconds: 3));
      return await rive.Factory.rive.decodeImage(data.buffer.asUint8List());
    } catch (e) {
      debugPrint('GiftSend: gift image unavailable: $e');
      return null;
    }
  }

  Future<void> _load() async {
    rive.File? file;
    rive.RiveWidgetController? controller;
    rive.ViewModelInstance? vm;
    rive.RenderImage? image;
    try {
      image = await _giftImage();
      // 跳过 / 离开发生在下载期间：不再解码 .riv（code review #3）
      if (!mounted) {
        image?.dispose();
        return;
      }
      final loaded = image;
      file = await rive.File.asset(
        kSendGiftRive,
        riveFactory: rive.Factory.rive,
        assetLoader: (asset, bytes) {
          if (loaded != null && asset is rive.ImageAsset && asset.name == _imageAssetName) {
            return asset.renderImage(loaded);
          }
          return false;
        },
      );
      if (file == null) throw StateError('could not decode $kSendGiftRive');
      controller = rive.RiveWidgetController(
        file,
        artboardSelector: rive.ArtboardSelector.byName(_artboard),
        stateMachineSelector: rive.StateMachineSelector.byName(_stateMachine),
      );
      vm = controller.dataBind(rive.DataBind.auto());
      if (image != null) {
        final prop = vm.image('giftImage');
        prop?.value = image;
        prop?.dispose();
      }
      // 资源与 VM 各自持有引用（rive_native ref_rcp）：交出后立即释放本地引用（code review #3）
      image?.dispose();
      image = null;
      controller.stateMachine.addEventListener(_onEvent);
      if (!mounted) {
        controller.stateMachine.removeEventListener(_onEvent);
        vm.dispose();
        controller.dispose();
        file.dispose();
        return;
      }
      setState(() {
        _file = file;
        _controller = controller;
        _vm = vm;
      });
      widget.onReady();
    } catch (e) {
      debugPrint('GiftSend: $e');
      image?.dispose();
      vm?.dispose();
      controller?.dispose();
      file?.dispose();
      if (mounted) widget.onError(e);
    }
  }

  void _onEvent(rive.Event event) {
    if (event.name == 'sendDone') widget.onDone();
  }

  @override
  void dispose() {
    _controller?.stateMachine.removeEventListener(_onEvent);
    _vm?.dispose();
    _controller?.dispose();
    _file?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    if (controller == null) return const SizedBox.expand();
    return IgnorePointer(
      child: rive.RiveWidget(
        controller: controller,
        fit: rive.Fit.cover,
        alignment: Alignment.center,
        hitTestBehavior: rive.RiveHitTestBehavior.none,
      ),
    );
  }
}
