import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../domain/content_type.dart';
import 'publish_compose_page.dart';

/// 发布深链着陆页（Story 6.1 · FR-40）。
///
/// 承接 `PET_BIRTHDAY` 推送深链（`/publish?preset=growth-calendar`）：首帧打开统一发布 sheet
/// （可预选类型），发布/关闭后回首页。发布本身仍走既有 [PublishComposePage] sheet（不另起全屏页）。
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
    // 里程碑「去发布」场景：由 compose 在弹完庆祝后自行跳回里程碑列表，这里不抢着回首页。
    if (mounted && widget.milestoneCode == null) {
      context.go('/home'); // 关闭发布后回首页，避免停留空着陆页
    }
  }

  @override
  Widget build(BuildContext context) => const Scaffold(body: SizedBox.shrink());
}
