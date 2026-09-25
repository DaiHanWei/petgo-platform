import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/deep_link_routes.dart';
import '../domain/content_type.dart';
import 'publish_compose_page.dart';

/// 发布深链着陆页（Story 6.1 · FR-40）。
///
/// 承接 `PET_BIRTHDAY` 推送深链（`/publish?preset=growth-calendar`）：首帧打开统一发布 sheet
/// （可预选类型），关闭后回上一页（无上一页才回首页）。发布本身仍走既有 [PublishComposePage] sheet（不另起全屏页）。
class PublishLandingPage extends ConsumerStatefulWidget {
  const PublishLandingPage(
      {super.key, this.preset, this.presetEventDate, this.milestoneCode});

  /// 预选发布类型（如生日深链预选成长日历）；为空时与「＋」入口行为一致。
  final ContentType? preset;

  /// 成长日历事件日期默认值（F9）：日历无记录格「+」跳发布预填该日（Story 2.4 AC6）。
  final DateTime? presetEventDate;

  /// 里程碑「去发布」回填（Story 8.4）：携里程碑 code，发布成功后自动打卡并弹庆祝。
  final String? milestoneCode;

  @override
  ConsumerState<PublishLandingPage> createState() => _PublishLandingPageState();
}

class _PublishLandingPageState extends ConsumerState<PublishLandingPage> {
  bool _opened = false;

  /// 发布 sheet 已关闭（无论发布成功、失败还是 ✕ 放弃）。
  bool _sheetClosed = false;

  /// 已发起离开导航，防重入（build 可能因 isCurrent 变化被多次触发）。
  bool _leaving = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _open());
  }

  Future<void> _open() async {
    if (_opened || !mounted) return;
    _opened = true;
    await PublishComposePage.open(context,
        preset: widget.preset,
        presetEventDate: widget.presetEventDate,
        milestoneCode: widget.milestoneCode);
    if (!mounted) return;
    // 所有入口统一走「重新成为栈顶才离开」（bug 20260924-564）：
    // 原先非里程碑路径在这里无条件 go('/home') 清栈 —— 从 Diary 日历「+」进来放弃发布，
    // 用户被送到 Social 而不是回日历；发布成功时 compose 刚 push 的成功页也会被这一 go 冲掉。
    // 里程碑「去发布」场景（bug 20260922-520）：成功路径由 compose 弹完庆祝后用
    // pushReplacement 把本着陆页换成里程碑列表；✕ 放弃 / 被拒页返回等路径没人导航，
    // 空白着陆页会留在栈顶 → 白屏。交给 build 在本页「重新成为栈顶」时离开。
    setState(() => _sheetClosed = true);
  }

  /// 本页仍是当前路由（sheet 已关、上面没有别的页）→ 回上一页；无上一页（冷启深链 / go 进来）
  /// → 兜底：带里程碑 code 落里程碑列表，其余落首页（bug 20260924-564）。
  /// 放在帧后执行并再核一次 isCurrent：成功路径的 pushReplacement 在下一帧才生效，
  /// 若在 sheet 刚关的同一帧里判断，会把本该被替换掉的着陆页误判为「停在栈顶」而多 pop 一页。
  void _leaveIfStranded() {
    if (_leaving) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _leaving) return;
      if (ModalRoute.of(context)?.isCurrent != true) return;
      _leaving = true;
      if (context.canPop()) {
        context.pop();
      } else {
        context.go(widget.milestoneCode != null
            ? DeepLinkRoutes.milestoneList
            : '/home');
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    // ModalRoute.of 建立依赖：本页重新成为栈顶（sheet 关闭 / 上层结果页返回）时会重建到这里。
    final isCurrent = ModalRoute.of(context)?.isCurrent ?? false;
    // 成功页 / 被拒页压在本页之上时 isCurrent=false → 不动；从它们返回、本页重新露出来时再离开。
    if (_sheetClosed && isCurrent) {
      _leaveIfStranded();
    }
    return const Scaffold(body: SizedBox.shrink());
  }
}
