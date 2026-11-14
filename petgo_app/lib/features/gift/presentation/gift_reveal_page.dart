import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rive/rive.dart' as rive;

import '../../../core/rive/rive_runtime.dart';
import '../../../core/theme/colors.dart';
import '../../../l10n/app_localizations.dart';
import '../../../shared/widgets/app_toast.dart';
import '../data/gift_repository.dart';
import '../domain/gift_order_detail.dart';
import 'gift_order_actions.dart' show reportGiftMessage;
import 'gift_page.dart' show giftSenderAvatar;
import 'gift_send_ceremony.dart' show kGiftBoxClosedAsset;
import 'gift_style.dart' show giftIcon;

/// 拆礼物 Rive（V1.5.0 gift-pack Story 2-5 · D-14）：画板 `GiftReveal`、状态机 `GiftSM`、VM `GiftVM`（触发器 `open`）。
const String kReceiveGiftRive = 'assets/rive/receive_gift.riv';

/// 设计师补 `revealDone` 事件前的兜底：点开后 2.5 秒进终态（D-14）。
const Duration giftRevealFallback = Duration(milliseconds: 2500);

/// 汇总页每页格数（G5）。
const int giftRevealPageSize = 9;

/// 动画区构建器（可注入替身：测试 / Rive 不可用）。[open] 由 false 变 true 时触发 `GiftVM.open`；
/// [onDone] = `revealDone` 事件；[onError] = 加载失败（页面直接进终态）。
typedef GiftRevealRiveBuilder = Widget Function(
    BuildContext context, ValueListenable<bool> open, VoidCallback onDone, void Function(Object error) onError);

/// 测试钩子：非空时替换默认 Rive 动画区（同 2-3 `giftSendRiveBuilderOverride`）。
@visibleForTesting
GiftRevealRiveBuilder? giftRevealRiveBuilderOverride;

/// 把 [items] 按 [giftRevealPageSize] 切页（G4 / G5）。
List<List<T>> giftRevealPages<T>(List<T> items) => [
      for (var i = 0; i < items.length; i += giftRevealPageSize)
        items.sublist(i, i + giftRevealPageSize > items.length ? items.length : i + giftRevealPageSize),
    ];

/// 拆礼物页（G1 ~ G6）：[orderTokens] 单个拆（G1 → G2 / G3），[all] 批量拆（G1 → G4 / G5 → G6）。
///
/// pop 结果：`true` = 调过 open 接口（调用方刷新列表，以服务端为准）；`false` / null = 未拆就关闭。
class GiftRevealPage extends ConsumerStatefulWidget {
  const GiftRevealPage({
    super.key,
    required this.petId,
    this.orderTokens = const [],
    this.all = false,
    this.riveBuilder,
    this.fallback = giftRevealFallback,
  });

  final int petId;
  final List<String> orderTokens;
  final bool all;
  final GiftRevealRiveBuilder? riveBuilder;
  final Duration fallback;

  bool get batch => all || orderTokens.length > 1;

  @override
  ConsumerState<GiftRevealPage> createState() => _GiftRevealPageState();
}

enum _Stage { idle, opening, result }

class _GiftRevealPageState extends ConsumerState<GiftRevealPage> {
  final ValueNotifier<bool> _open = ValueNotifier(false);
  _Stage _stage = _Stage.idle;
  bool _animDone = false;
  bool _requested = false;
  GiftOpenResult? _result;
  bool _failed = false;
  bool _animFailed = false;
  Timer? _fallbackTimer;

  /// 批量：-1 = 汇总页（G4 / G5），≥ 0 = 逐条祝福语第 n 条（G6）。
  int _messageIndex = -1;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // 减弱动态效果：直接终态（仍调 open 接口，AC1.6）
      if (MediaQuery.of(context).disableAnimations) _skipToResult();
    });
  }

  @override
  void dispose() {
    _fallbackTimer?.cancel();
    _open.dispose();
    super.dispose();
  }

  /// 点礼盒：只触发一次（连点 / `TapBox` 先到为准，AC1.2）。
  void _tapBox() {
    if (_requested) return;
    _requestOpen();
    _open.value = true;
    setState(() => _stage = _Stage.opening);
    _fallbackTimer = Timer(widget.fallback, _onAnimDone);
  }

  void _skipToResult() {
    _requestOpen();
    _fallbackTimer?.cancel();
    setState(() {
      _animDone = true;
      _stage = _Stage.result;
    });
    // 请求已先失败（如点开后 riv 才加载失败）：补提示（2-5 复审 #4）
    if (_failed) _toastFailed();
  }

  void _onAnimDone() {
    // 只认点开后的结束（待机循环若发出 revealDone 不算，2-5 复审 #1）
    if (!mounted || _animDone || _stage != _Stage.opening) return;
    _fallbackTimer?.cancel();
    setState(() {
      _animDone = true;
      _stage = _Stage.result;
    });
    if (_failed) _toastFailed();
  }

  void _onAnimError(Object e) {
    if (!mounted || _animFailed) return;
    _animFailed = true;
    _skipToResult();
  }

  Future<void> _requestOpen() async {
    if (_requested) return;
    _requested = true;
    try {
      final r = await ref
          .read(giftRepositoryProvider)
          .openPetGiftOrders(widget.petId, orderTokens: widget.orderTokens, all: widget.all);
      if (!mounted) return;
      setState(() => _result = r);
    } catch (_) {
      // 网络与解析错误同样处理，避免无限转圈（2-5 复审 #2）
      if (!mounted) return;
      setState(() => _failed = true);
      // 动画照播：结束后再提示（AC1.2）；已到终态则立即提示。
      if (_animDone) _toastFailed();
    }
  }

  void _toastFailed() {
    if (!mounted) return;
    showAppToast(context, AppLocalizations.of(context).giftOpenFailed);
  }

  void _close() => Navigator.of(context).pop(_requested);

  List<GiftOrderDetail> get _withMessage => [
        for (final i in _result?.items ?? const <GiftOrderDetail>[])
          if (i.message != null) i,
      ];

  void _next() {
    final msgs = _withMessage;
    if (_messageIndex + 1 < msgs.length) {
      setState(() => _messageIndex++);
    } else {
      _close();
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final showResult = _stage == _Stage.result;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _close();
      },
      child: Scaffold(
        backgroundColor: const Color(0xFFF7F1EA),
        body: Stack(fit: StackFit.expand, children: [
          // 动画区：点礼盒 = 点整块舞台（Rive 自身不吃手势）。
          GestureDetector(
            key: const ValueKey('giftRevealStage'),
            behavior: HitTestBehavior.opaque,
            onTap: _stage == _Stage.idle ? _tapBox : null,
            child: _animFailed ? const _StaticBox() : _stageWidget(context),
          ),
          if (showResult) Positioned.fill(child: _resultLayer(context, l10n)),
          if (_stage == _Stage.idle)
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 48),
                  child: Text(l10n.giftRevealTapToOpen,
                      key: const ValueKey('giftRevealTapHint'),
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: AppColors.ink)),
                ),
              ),
            ),
          if (!showResult)
            Positioned(
              left: 8,
              top: 0,
              child: SafeArea(
                child: _CircleButton(
                  key: const ValueKey('giftRevealClose'),
                  icon: Icons.close_rounded,
                  onTap: _close,
                ),
              ),
            ),
        ]),
      ),
    );
  }

  Widget _stageWidget(BuildContext context) {
    final builder = widget.riveBuilder ?? giftRevealRiveBuilderOverride;
    if (builder != null) return builder(context, _open, _onAnimDone, _onAnimError);
    return _ReceiveGiftRive(open: _open, onDone: _onAnimDone, onError: _onAnimError);
  }

  Widget _resultLayer(BuildContext context, AppLocalizations l10n) {
    final result = _result;
    final Widget body;
    if (result == null || result.items.isEmpty) {
      // 失败 / 空响应（如别处已拆完）：只留「Lanjut」（2-5 复审 #3）
      body = _failed || result != null
          ? _ResultFrame(primaryLabel: l10n.onboardingContinue, onPrimary: _close, child: const SizedBox.shrink())
          : const Center(child: CircularProgressIndicator(key: ValueKey('giftRevealLoading')));
    } else if (!widget.batch && result.items.isNotEmpty) {
      final item = result.items.first;
      body = _MessageView(item: item, primaryLabel: l10n.onboardingContinue, onPrimary: _close);
    } else if (_messageIndex >= 0) {
      final msgs = _withMessage;
      final last = _messageIndex == msgs.length - 1;
      body = _MessageView(
        key: ValueKey('giftRevealMessage-$_messageIndex'),
        item: msgs[_messageIndex],
        primaryLabel: last ? l10n.onboardingContinue : l10n.giftRevealNext,
        onPrimary: _next,
        dots: (count: msgs.length, index: _messageIndex),
      );
    } else {
      final hasMessages = _withMessage.isNotEmpty;
      body = _SummaryView(
        result: result,
        primaryLabel: hasMessages ? l10n.giftRevealNext : l10n.onboardingContinue,
        onPrimary: hasMessages ? () => setState(() => _messageIndex = 0) : _close,
      );
    }
    return AnimatedSwitcher(duration: const Duration(milliseconds: 250), child: body);
  }
}

/// 结果层公共骨架：压淡底 + 内容 + 底部 EXP / 主按钮 / 次按钮。
class _ResultFrame extends StatelessWidget {
  const _ResultFrame({
    super.key,
    required this.child,
    required this.primaryLabel,
    required this.onPrimary,
    this.exp = 0,
    this.secondary,
    this.top,
    this.dots,
  });

  final Widget child;
  final String primaryLabel;
  final VoidCallback onPrimary;
  final int exp;
  final Widget? secondary;
  final Widget? top;
  final ({int count, int index})? dots;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ColoredBox(
      color: Colors.white.withValues(alpha: 0.72),
      child: SafeArea(
        child: Column(children: [
          ?top,
          Expanded(child: child),
          if (dots != null && dots!.count > 1) ...[
            _Dots(count: dots!.count, index: dots!.index, keyPrefix: 'giftRevealProgressDot'),
            const SizedBox(height: 12),
          ],
          if (exp > 0) ...[
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              const Icon(Icons.arrow_upward_rounded, size: 16, color: AppColors.mint),
              const SizedBox(width: 4),
              Text(l10n.giftYouGotExp(exp),
                  key: const ValueKey('giftRevealExp'),
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
            ]),
            const SizedBox(height: 12),
          ],
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: SizedBox(
              width: double.infinity,
              height: 48,
              child: FilledButton(
                key: const ValueKey('giftRevealPrimary'),
                style: FilledButton.styleFrom(
                  backgroundColor: AppColors.mint,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                ),
                onPressed: onPrimary,
                child: Text(primaryLabel, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
              ),
            ),
          ),
          if (secondary != null) secondary! else const SizedBox(height: 16),
        ]),
      ),
    );
  }
}

/// 单笔（G2 / G3 / G6）：送礼人头像 + 标题、礼物、祝福语、EXP、Lanjut / Berikutnya、Lihat profil、「⋯」。
class _MessageView extends ConsumerWidget {
  const _MessageView({
    super.key,
    required this.item,
    required this.primaryLabel,
    required this.onPrimary,
    this.dots,
  });

  final GiftOrderDetail item;
  final String primaryLabel;
  final VoidCallback onPrimary;
  final ({int count, int index})? dots;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final s = item.sender;
    final name = s.deleted ? l10n.feedDeletedUser : (s.nickname ?? '');
    final message = item.message;
    final userId = s.userId;
    return _ResultFrame(
      exp: item.ownerExp ?? 0,
      primaryLabel: primaryLabel,
      onPrimary: onPrimary,
      dots: dots,
      top: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 4, 0),
        child: Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          giftSenderAvatar(s.brief, size: 32),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              l10n.giftRevealTitle(name, item.quantity, item.giftNameFor(locale), item.petName),
              key: const ValueKey('giftRevealTitle'),
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: AppColors.ink),
            ),
          ),
          if (item.canReport)
            PopupMenuButton<int>(
              key: const ValueKey('giftRevealMore'),
              icon: const Icon(Icons.more_horiz_rounded, color: AppColors.ink),
              onSelected: (_) => reportGiftMessage(context, ref, orderToken: item.orderToken),
              itemBuilder: (_) => [
                PopupMenuItem<int>(
                  key: const ValueKey('giftRevealReport'),
                  value: 0,
                  child: Text(l10n.giftReportMessage),
                ),
              ],
            )
          else
            const SizedBox(width: 12),
        ]),
      ),
      secondary: !s.deleted && userId != null
          ? TextButton(
              key: const ValueKey('giftRevealSenderProfile'),
              style: TextButton.styleFrom(minimumSize: const Size(44, 44)),
              onPressed: () => context.push('/users/$userId'),
              child: Text(l10n.giftViewSenderProfile(name),
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: AppColors.mint)),
            )
          : null,
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 16),
        child: Column(children: [
          const SizedBox(height: 24),
          giftIcon(item.giftIconUrl, size: 120),
          if (message != null) ...[
            const SizedBox(height: 20),
            Text(message,
                key: const ValueKey('giftRevealMessage'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 15, height: 1.45, color: AppColors.ink)),
          ] else if (item.messageHidden) ...[
            const SizedBox(height: 20),
            Text(l10n.giftMessageHidden,
                key: const ValueKey('giftRevealMessageHidden'),
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 14, height: 1.45, color: AppColors.muted)),
          ],
        ]),
      ),
    );
  }
}

/// 批量汇总（G4 / G5）：「Kamu menerima {n} hadiah」+ 每页 9 格 + 圆点。
class _SummaryView extends StatefulWidget {
  const _SummaryView({required this.result, required this.primaryLabel, required this.onPrimary});

  final GiftOpenResult result;
  final String primaryLabel;
  final VoidCallback onPrimary;

  @override
  State<_SummaryView> createState() => _SummaryViewState();
}

class _SummaryViewState extends State<_SummaryView> {
  int _page = 0;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final locale = Localizations.localeOf(context);
    final pages = giftRevealPages(widget.result.items);
    return _ResultFrame(
      key: const ValueKey('giftRevealSummary'),
      exp: widget.result.totalOwnerExp,
      primaryLabel: widget.primaryLabel,
      onPrimary: widget.onPrimary,
      top: Padding(
        padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
        child: Text(l10n.giftRevealReceivedCount(widget.result.items.length),
            key: const ValueKey('giftRevealSummaryTitle'),
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: AppColors.ink)),
      ),
      child: Column(children: [
        Expanded(
          child: PageView.builder(
            key: const ValueKey('giftRevealPages'),
            itemCount: pages.length,
            onPageChanged: (i) => setState(() => _page = i),
            itemBuilder: (_, p) => GridView.count(
              crossAxisCount: 3,
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              mainAxisSpacing: 12,
              crossAxisSpacing: 10,
              childAspectRatio: 0.78,
              physics: const NeverScrollableScrollPhysics(),
              children: [
                for (final item in pages[p])
                  Column(key: ValueKey('giftRevealCell-${item.orderToken}'), children: [
                    SizedBox(
                      width: 64,
                      height: 64,
                      child: Stack(clipBehavior: Clip.none, children: [
                        Center(child: giftIcon(item.giftIconUrl, size: 56)),
                        Positioned(right: -4, bottom: -4, child: giftSenderAvatar(item.sender.brief, size: 22)),
                      ]),
                    ),
                    const SizedBox(height: 6),
                    Text(l10n.giftRevealQtyName(item.quantity, item.giftNameFor(locale)),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.ink)),
                    Text(
                        l10n.giftReceivedFrom(
                            item.sender.deleted ? l10n.feedDeletedUser : (item.sender.nickname ?? '')),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        textAlign: TextAlign.center,
                        style: const TextStyle(fontSize: 11, color: AppColors.muted)),
                  ]),
              ],
            ),
          ),
        ),
        if (pages.length > 1) ...[
          _Dots(count: pages.length, index: _page, keyPrefix: 'giftRevealPageDot'),
          const SizedBox(height: 12),
        ],
      ]),
    );
  }
}

class _Dots extends StatelessWidget {
  const _Dots({required this.count, required this.index, required this.keyPrefix});

  final int count;
  final int index;
  final String keyPrefix;

  @override
  Widget build(BuildContext context) => Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < count; i++)
            Container(
              key: ValueKey('$keyPrefix-$i'),
              width: i == index ? 16 : 6,
              height: 6,
              margin: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: i == index ? AppColors.mint : AppColors.line,
                borderRadius: BorderRadius.circular(3),
              ),
            ),
        ],
      );
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({super.key, required this.icon, required this.onTap});

  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        child: InkResponse(
          onTap: onTap,
          radius: 24,
          child: SizedBox(
            width: 44,
            height: 44,
            child: Center(
              child: Container(
                width: 36,
                height: 36,
                decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
                child: Icon(icon, size: 20, color: AppColors.ink),
              ),
            ),
          ),
        ),
      );
}

/// Rive 不可用 / 减弱动态效果时的静态礼盒。
class _StaticBox extends StatelessWidget {
  const _StaticBox();

  @override
  Widget build(BuildContext context) =>
      Center(child: Image.asset(kGiftBoxClosedAsset, width: 160, key: const ValueKey('giftRevealStaticBox')));
}

/// 默认 Rive 动画区（接法同 2-3 `_SendGiftRive` / `stamp_ceremony.dart`）。
/// 礼物图 `GiftVM.giftImage` 现文件没有（小熊为矢量），本版不传；设计师补属性后再接。
class _ReceiveGiftRive extends StatefulWidget {
  const _ReceiveGiftRive({required this.open, required this.onDone, required this.onError});

  final ValueListenable<bool> open;
  final VoidCallback onDone;
  final void Function(Object error) onError;

  @override
  State<_ReceiveGiftRive> createState() => _ReceiveGiftRiveState();
}

class _ReceiveGiftRiveState extends State<_ReceiveGiftRive> {
  static const _artboard = 'GiftReveal';
  static const _stateMachine = 'GiftSM';

  rive.File? _file;
  rive.RiveWidgetController? _controller;
  rive.ViewModelInstance? _vm;
  bool _fired = false;

  @override
  void initState() {
    super.initState();
    widget.open.addListener(_maybeOpen);
    if (!RiveRuntime.available) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onError(StateError('Rive runtime unavailable'));
      });
      return;
    }
    _load();
  }

  Future<void> _load() async {
    rive.File? file;
    rive.RiveWidgetController? controller;
    rive.ViewModelInstance? vm;
    try {
      file = await rive.File.asset(kReceiveGiftRive, riveFactory: rive.Factory.rive);
      if (file == null) throw StateError('could not decode $kReceiveGiftRive');
      controller = rive.RiveWidgetController(
        file,
        artboardSelector: rive.ArtboardSelector.byName(_artboard),
        stateMachineSelector: rive.StateMachineSelector.byName(_stateMachine),
      );
      vm = controller.dataBind(rive.DataBind.auto());
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
      _maybeOpen();
    } catch (e) {
      debugPrint('GiftReveal: $e');
      vm?.dispose();
      controller?.dispose();
      file?.dispose();
      if (mounted) widget.onError(e);
    }
  }

  /// 点礼盒后触发一次 `GiftVM.open`（加载慢时在就绪后补触发）。
  void _maybeOpen() {
    if (_fired || !widget.open.value) return;
    final vm = _vm;
    if (vm == null) return;
    _fired = true;
    final t = vm.trigger('open');
    t?.trigger();
    t?.dispose();
  }

  void _onEvent(rive.Event event) {
    if (event.name == 'revealDone') widget.onDone();
  }

  @override
  void dispose() {
    widget.open.removeListener(_maybeOpen);
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
