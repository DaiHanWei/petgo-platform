import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/colors.dart';
import '../../l10n/app_localizations.dart';

/// 红色**全屏沉浸**强提醒（Story 4.5，FR-3/UX-DR7 · 决策 #5 还原 ai-result-red.html）。
/// 生命安全支柱最直接用户面。
///
/// 🔒 不可协商（安全语义只升不降）：① 0–5s 锁定（CTA 禁用 + 倒计时，背景/拖拽/返回键均不可关闭）；
/// ② 解锁后**单一「我已知晓」按钮**关闭、返回结果页；
/// ③ 全程**零兽医 / 零变现引流 / 零地图导航 / 零医院推荐**（F3 · 去导航化）；
/// ④ assertive 打断式播报 + ⚠️ 非颜色单一。
///
/// 视觉：上半屏红底 #F0425A + breathGlow 呼吸 ⚠️ 圆 + 主文案；下半屏白卡浮起
/// （症状摘要红浅底 + 3 步骤圆 badge + 倒计时锁 CTA）。
/// bug 20260715-290：整页一个滚动，红头随内容上滑收缩为紧凑红底栏（⚠️ + 标题，始终可见），
/// 白卡随之占满其余空间；「我已知晓」固定底部。
class RedAlertOverlay extends ConsumerStatefulWidget {
  const RedAlertOverlay({
    super.key,
    required this.title,
    required this.onAcknowledge,
    this.symptom,
    this.emergencySteps = const <String>[],
    this.emergencyAvoid = const <String>[],
    this.lockSeconds = 5,
  });

  /// 已本地化主标题（含宠物名，如「请立即带 Momo 去宠物医院就诊」）。
  final String title;

  /// 解锁后点击「我已知晓」的回调（由宿主关闭全屏）。
  final VoidCallback onAcknowledge;

  /// 症状摘要（上游经 sanitize；为空时不渲染症状框，不臆造）。
  final String? symptom;

  /// 红色态对症「现在该做」院前应急步骤（AI 产出）；为空时回退通用步骤。
  final List<String> emergencySteps;

  /// 红色态对症「切勿」禁忌（AI 产出）；为空时不渲染该区块。
  final List<String> emergencyAvoid;

  /// 锁定秒数（默认 5；测试可注入更短）。
  final int lockSeconds;

  @override
  ConsumerState<RedAlertOverlay> createState() => _RedAlertOverlayState();
}

class _RedAlertOverlayState extends ConsumerState<RedAlertOverlay>
    with SingleTickerProviderStateMixin {
  late int _remaining = widget.lockSeconds;
  Timer? _timer;
  late final AnimationController _breath;

  bool get _locked => _remaining > 0;

  @override
  void initState() {
    super.initState();
    _breath = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat(reverse: true);
    _timer = Timer.periodic(const Duration(seconds: 1), (t) {
      if (_remaining <= 0) {
        t.cancel();
        return;
      }
      setState(() => _remaining--);
      if (_remaining <= 0) t.cancel();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _breath.dispose();
    super.dispose();
  }

  /// 对症步骤优先（AI 产出）；安全层升红 / AI 失败 / 未产出 → 回退通用兜底步骤（永远有保底内容）。
  List<String> _resolveSteps(AppLocalizations l10n) {
    if (widget.emergencySteps.isNotEmpty) {
      return widget.emergencySteps;
    }
    return <String>[
      l10n.triageRedStep1,
      l10n.triageRedStep2,
      l10n.triageRedStep3,
    ];
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    // 🔒 5s 内拦截系统返回键（锁定不可绕过）；解锁后唯一出口为「我已知晓」按钮，故恒不可 pop。
    // showGeneralDialog 的 pageBuilder 不自带 Material 祖先 → Text 落到调试 DefaultTextStyle 会出黄下划线；
    // 透明 Material 提供正确 DefaultTextStyle（不绘制底色，红底仍由内层 Container 负责）。
    return Material(
      type: MaterialType.transparency,
      child: PopScope(
        canPop: false,
        child: Semantics(
          container: true,
          liveRegion: true, // assertive 打断式播报
          label: '${widget.title}. ${l10n.triageRedSubtext}',
          child: Container(
            width: double.infinity,
            color: AppColors.triageRed, // 全屏红底（#F0425A）
            child: SafeArea(
              // bug 20260715-290：整页**一个滚动**。红头是可收缩头部 —— 展开约占半屏（与改前视觉一致），
              // 随白卡内容上滑而收缩；收到底**保留一条紧凑红底栏（⚠️ + 标题）钉在顶部**，
              // 红色警示绝不完全消失（安全语义只升不降）。「我已知晓」固定在底部，不随内容滚动。
              child: LayoutBuilder(
                builder: (context, constraints) => Column(
                  children: <Widget>[
                    Expanded(
                      child: CustomScrollView(
                        key: const ValueKey('triageRedScroll'),
                        // 安全界面不要回弹：iOS 回弹会把白卡整块拽离底部、露出一截红底，读起来像页面坏了。
                        physics: const ClampingScrollPhysics(),
                        slivers: <Widget>[
                          SliverPersistentHeader(
                            pinned: true,
                            delegate: _RedHeaderDelegate(
                              // 展开高度 = 整屏一半（改前上下两个 Expanded 各占一半）。
                              expandedHeight: constraints.maxHeight / 2,
                              title: widget.title,
                              subtext: l10n.triageRedSubtext,
                              breath: _breath,
                            ),
                          ),
                          SliverFillRemaining(
                            hasScrollBody: false,
                            child: _whiteCard(l10n),
                          ),
                        ],
                      ),
                    ),
                    _ackBar(l10n),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 白卡：症状 / 立即步骤 / 切勿。随整页滚动，内容少时也铺满红头以下的剩余空间。
  Widget _whiteCard(AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(
          top: Radius.circular(24),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(22, 24, 22, 12),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          // 症状摘要（红浅底，仅有数据时渲染）。
          if (widget.symptom != null && widget.symptom!.trim().isNotEmpty) ...[
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: 16,
                vertical: 13,
              ),
              decoration: BoxDecoration(
                color: AppColors.coralTint,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l10n.triageRedSymptomHeader.toUpperCase(),
                    style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.4,
                      color: AppColors.healthEventText,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    widget.symptom!.trim(),
                    style: const TextStyle(
                      fontSize: 13,
                      height: 1.6,
                      color: AppColors.ink,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
          ],
          // 立即步骤：AI 对症「现在该做」，缺省回退通用步骤。
          Text(
            l10n.triageRedStepsHeader.toUpperCase(),
            style: const TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.ink,
            ),
          ),
          const SizedBox(height: 6),
          // 应急仅为就医前辅助，绝不替代立即就医（防紧迫性被稀释）。
          Text(
            l10n.triageRedPreCareNote,
            style: const TextStyle(
              fontSize: 11,
              height: 1.5,
              color: AppColors.muted,
            ),
          ),
          const SizedBox(height: 10),
          for (final (int i, String step) in _resolveSteps(l10n).indexed) ...[
            if (i > 0) const SizedBox(height: 8),
            _StepLine(n: '${i + 1}', text: step),
          ],
          // 切勿区（仅当 AI 给出对症禁忌；急症「别做错」常是救命关键）。
          if (widget.emergencyAvoid.isNotEmpty) ...[
            const SizedBox(height: 18),
            Text(
              l10n.triageRedAvoidHeader.toUpperCase(),
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: AppColors.triageRed,
              ),
            ),
            const SizedBox(height: 10),
            for (final (int i, String item) in widget.emergencyAvoid.indexed) ...[
              if (i > 0) const SizedBox(height: 8),
              _AvoidLine(text: item),
            ],
          ],
        ],
      ),
    );
  }

  /// 倒计时锁 CTA（解锁后单一「我已知晓」）。固定在底部，不随内容滚动。
  Widget _ackBar(AppLocalizations l10n) {
    return Container(
      width: double.infinity,
      color: AppColors.surface,
      padding: const EdgeInsets.fromLTRB(22, 10, 22, 28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          FilledButton(
            key: const ValueKey('triageRedAcknowledge'),
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.triageRed,
              foregroundColor: Colors.white,
              disabledBackgroundColor: AppColors.triageRed.withValues(alpha: 0.45),
              disabledForegroundColor: Colors.white,
              minimumSize: const Size.fromHeight(50),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
              textStyle: const TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w700,
              ),
            ),
            onPressed: _locked ? null : widget.onAcknowledge,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(l10n.triageRedAcknowledge),
                if (_locked) ...[
                  const SizedBox(width: 6),
                  Text(
                    '($_remaining)',
                    key: const ValueKey('triageRedCountdown'),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 8),
          Text(
            l10n.triageRedCtaHint,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 11,
              color: AppColors.muted,
            ),
          ),
        ],
      ),
    );
  }
}

/// 可收缩红头（bug 20260715-290）。
///
/// 展开态 = 改前的上半屏（呼吸 ⚠️ 圆 + 主标题 + 副文案）；随滚动收缩，
/// 收到 [_compactHeight] 时换成**紧凑红底栏**（小 ⚠️ + 标题），pinned 始终可见。
///
/// - 两态之间交叉淡入淡出；**完全展开时只建展开态、完全收起时只建紧凑态** ——
///   同屏不会出现两份标题（读屏也不会念两遍）。
/// - 展开态用 `FittedBox(scaleDown)`：小屏 / 大字号下内容比半屏还高时等比缩小，**绝不裁掉标题**。
class _RedHeaderDelegate extends SliverPersistentHeaderDelegate {
  _RedHeaderDelegate({
    required this.expandedHeight,
    required this.title,
    required this.subtext,
    required this.breath,
  });

  /// 紧凑红底栏高度：两行标题 + 1.3 倍字号上限（NFR-13）也放得下。
  static const double _compactHeight = 72;

  final double expandedHeight;
  final String title;
  final String subtext;
  final Animation<double> breath;

  @override
  double get minExtent => _compactHeight;

  @override
  double get maxExtent =>
      expandedHeight > _compactHeight ? expandedHeight : _compactHeight;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    final range = maxExtent - minExtent;
    final t = range <= 0 ? 1.0 : (shrinkOffset / range).clamp(0.0, 1.0);
    // 展开态在前 70% 行程里淡出；紧凑态在后 50% 行程里淡入 —— 中段交叠，不留「两者都不在」的空档。
    final double expandedOpacity = (1 - t / 0.7).clamp(0.0, 1.0);
    final double compactOpacity = ((t - 0.5) / 0.5).clamp(0.0, 1.0);
    return Container(
      color: AppColors.triageRed,
      child: Stack(
        fit: StackFit.expand,
        children: <Widget>[
          if (expandedOpacity > 0)
            Opacity(
              opacity: expandedOpacity,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(24, 32, 24, 24),
                child: LayoutBuilder(
                  builder: (context, c) => Center(
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      child: SizedBox(
                        width: c.maxWidth,
                        child: _expanded(),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          if (compactOpacity > 0)
            Opacity(
              opacity: compactOpacity,
              child: _compact(),
            ),
        ],
      ),
    );
  }

  /// 展开态：与改前上半屏逐项一致（88 呼吸圆 / 24 号主标题 / 14 号副文案）。
  Widget _expanded() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        AnimatedBuilder(
          animation: breath,
          builder: (context, child) => Container(
            width: 88,
            height: 88,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Colors.white.withValues(
                alpha: 0.28 + 0.27 * breath.value,
              ),
            ),
            child: child,
          ),
          child: const Icon(
            Icons.warning_amber_rounded,
            key: ValueKey('triageRedIcon'),
            color: Colors.white,
            size: 44,
          ),
        ),
        const SizedBox(height: 20),
        Text(
          title,
          style: const TextStyle(
            fontSize: 24,
            height: 1.3,
            fontWeight: FontWeight.w700,
            color: Colors.white,
          ),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 10),
        Text(
          subtext,
          style: TextStyle(
            fontSize: 14,
            height: 1.6,
            color: Colors.white.withValues(alpha: 0.9),
          ),
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  /// 紧凑态：⚠️ + 标题，一条红底栏。⚠️ 保留 —— 警示不能只靠颜色（非颜色单一）。
  Widget _compact() {
    return Padding(
      key: const ValueKey('triageRedCompactBar'),
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Row(
        children: <Widget>[
          const Icon(
            Icons.warning_amber_rounded,
            key: ValueKey('triageRedCompactIcon'),
            color: Colors.white,
            size: 26,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              title,
              key: const ValueKey('triageRedCompactTitle'),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontSize: 16,
                height: 1.3,
                fontWeight: FontWeight.w700,
                color: Colors.white,
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  bool shouldRebuild(_RedHeaderDelegate old) =>
      old.expandedHeight != expandedHeight ||
      old.title != title ||
      old.subtext != subtext ||
      old.breath != breath;
}

/// 立即步骤行（原型 LANGKAH SEKARANG）：红圆数字 badge + 步骤文案。
class _StepLine extends StatelessWidget {
  const _StepLine({required this.n, required this.text});

  final String n;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 22,
          height: 22,
          margin: const EdgeInsets.only(top: 1),
          alignment: Alignment.center,
          decoration: const BoxDecoration(
            color: AppColors.triageRed,
            shape: BoxShape.circle,
          ),
          child: Text(
            n,
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: Colors.white,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 13,
              height: 1.5,
              color: AppColors.ink,
            ),
          ),
        ),
      ],
    );
  }
}

/// 「切勿」禁忌行（红 ✕ badge + 文案）：急症下做了会加重伤害的动作。
class _AvoidLine extends StatelessWidget {
  const _AvoidLine({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 22,
          height: 22,
          margin: const EdgeInsets.only(top: 1),
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: AppColors.triageRed.withValues(alpha: 0.12),
            shape: BoxShape.circle,
          ),
          child: const Icon(
            Icons.close_rounded,
            size: 14,
            color: AppColors.triageRed,
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontSize: 13,
              height: 1.5,
              color: AppColors.ink,
            ),
          ),
        ),
      ],
    );
  }
}
