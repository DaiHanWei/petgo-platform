// 改写自交付包 stamp_ceremony.dart（V1.3.4 签到盖章动画）。
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:rive/rive.dart' as rive;

import '../../../../core/rive/rive_runtime.dart';

class StampCeremony extends StatefulWidget {
  const StampCeremony({
    super.key,
    required this.stampPng,
    this.asset = 'assets/rive/tailtopia_stamp.riv',
    this.confettiSeed = 0,
    this.reduceMotion,
    this.onImpact,
    this.onInkDone,
    this.onComplete,
    this.onError,
  });

  /// The stamp face: PNG bytes, 512x512, transparent background, colour already applied.
  final Uint8List stampPng;

  /// Where the .riv is bundled (declare it under `flutter: assets:` in pubspec.yaml).
  final String asset;

  /// 0 = a different confetti burst every time; any other value repeats the same burst.
  final double confettiSeed;

  /// Skip straight to the final frame. Defaults to the platform's reduce-motion setting
  /// (MediaQuery.disableAnimations).
  final bool? reduceMotion;

  /// The stamp hits the paper (~1.02s): play the thud and the haptic here.
  final VoidCallback? onImpact;

  /// The imprint has fully appeared (~1.58s): a good moment to fade in the place name.
  final VoidCallback? onInkDone;

  /// Finished and resting on the final frame (~2.36s, or right after a skip).
  final VoidCallback? onComplete;

  /// Loading failed. The widget then renders nothing; carry on as if [onComplete] fired.
  final void Function(Object error)? onError;

  @override
  State<StampCeremony> createState() => _StampCeremonyState();
}

class _StampCeremonyState extends State<StampCeremony> {
  static const _artboard = 'StampCeremony';
  static const _stateMachine = 'Ceremony SM';

  rive.File? _file;
  rive.RiveWidgetController? _controller;
  rive.ViewModelInstance? _vm;
  bool _started = false;

  @override
  void initState() {
    super.initState();
    if (!RiveRuntime.available) {
      // Rive 原生库没起来：不尝试加载，等首帧后通知调用方走兜底（避免 initState 内同步回调 setState）。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onError?.call(StateError('Rive runtime unavailable'));
      });
      return;
    }
    _load();
  }

  Future<void> _load() async {
    try {
      final file = await rive.File.asset(
        widget.asset,
        riveFactory: rive.Factory.rive,
        // stamp_face is a referenced placeholder that is not shipped; the real face arrives
        // through the stampImage property below, so tell the runtime not to look for it.
        assetLoader: (asset, bytes) => asset is rive.ImageAsset && asset.name == 'stamp_face',
      );
      if (file == null) throw StateError('could not decode ${widget.asset}');
      if (!mounted) {
        file.dispose();
        return;
      }

      // Select the artboard by name: a watermarked build also carries Rive's watermark artboards.
      final controller = rive.RiveWidgetController(
        file,
        artboardSelector: rive.ArtboardSelector.byName(_artboard),
        stateMachineSelector: rive.StateMachineSelector.byName(_stateMachine),
      );
      final vm = controller.dataBind(rive.DataBind.auto());
      controller.stateMachine.addEventListener(_onEvent);

      final face = await rive.Factory.rive.decodeImage(widget.stampPng);
      if (face == null) throw StateError('could not decode the stamp PNG');
      vm.image('stampImage')?.value = face;
      vm.number('confettiSeed')?.value = widget.confettiSeed;

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
      _go();
    } catch (e) {
      debugPrint('StampCeremony: $e');
      widget.onError?.call(e);
    }
  }

  // Fire start (or skip) once, after the image is set: the stamp must be in place before it lands.
  void _go() {
    if (_started || _vm == null) return;
    _started = true;
    final reduce = widget.reduceMotion ?? MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    _vm!.trigger(reduce ? 'skip' : 'start')?.trigger();
  }

  /// Plays the ceremony again from the start (it must have finished first).
  void replay() => _vm?.trigger('start')?.trigger();

  void _onEvent(rive.Event event) {
    switch (event.name) {
      case 'stampImpact':
        widget.onImpact?.call();
      case 'inkDone':
        widget.onInkDone?.call();
      case 'complete':
        widget.onComplete?.call();
    }
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
        fit: rive.Fit.fitWidth,
        alignment: Alignment.center,
        hitTestBehavior: rive.RiveHitTestBehavior.none,
      ),
    );
  }
}
