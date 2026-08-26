/// 电商板块的**双 UI 并存**开关（V1.4.0，2026-08-19 用户要求：保留两套 UI 做对比）。
///
/// ## 为什么不是「改完就替换」
///
/// 原计划是逐屏替换旧实现。改成并存后有两个实打实的好处：
///
/// 1. **旧测试一条不用改**。`test/shop/` 有 4218 行断言，其中大量是 `find.text` 与结构
///    断言，直接绑在旧版式上。默认变体保持 [ShopUiVariant.v1] ⇒ 那些用例继续跑旧页面、
///    继续绿；新版式写新用例。这比「改一屏红一片、再逐条判断该不该改断言」可靠得多 ——
///    后者最大的风险是把**真回归**误当成「版式变了很正常」给改掉。
/// 2. **同数据对比**。两套 UI 走同一条路由、同一批 provider，差异只在渲染层，
///    因此看到的差别就是设计差别，不掺数据差别。
///
/// ## 用法
///
/// 默认变体可由构建期注入：`flutter run --dart-define=SHOP_UI=v1` 回退旧版式。
/// 运行期用 [shopUiVariantProvider] 切换（debug 构建里 Toko 顶栏有切换入口）。
///
/// 🔴 **两套实现都要能独立跑通**。不要为了少写代码让 v2 去 `extends` v1 的 State ——
/// 那样任何一侧改动都会牵动另一侧，对比就失去意义了。
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

enum ShopUiVariant {
  /// V1.4.0 首发版式（Story 1.6–5.8 逐个 story 实现的那套）。
  v1,

  /// 2026-08 电商设计稿版式（`design_handoff_ecommerce/`）。
  v2;

  /// 是否为设计稿版式。
  bool get isV2 => this == ShopUiVariant.v2;
}

/// 构建期覆盖。`--dart-define=SHOP_UI=v1` → 回退到首发版式。
///
/// ⚠️ 取值只认 `v1` 一个字面量，其余（含拼错、空值）一律落到 [ShopUiVariant.v2]。
/// 2026-08-21 默认值翻转后，这条规则的方向也跟着翻：**拼错的 flag 不会把人静默送回旧版式**。
const String _kShopUiDefine = String.fromEnvironment('SHOP_UI');

ShopUiVariant get _defaultVariant =>
    _kShopUiDefine == 'v1' ? ShopUiVariant.v1 : ShopUiVariant.v2;

/// 当前生效的电商 UI 变体。
///
/// 🔴 默认 [ShopUiVariant.v2]（2026-08-21 产品指定翻转，原为 v1）。
///
/// 原默认 v1 的理由是「新版式尚未通过人工验收，忘记指定的构建应拿到已验收那套」。
/// 现在验收对象本身就是 v2 —— 继续默认 v1 会让每个包都得记着传 flag，反而更容易出错。
/// v1 仍完整保留、可经 `--dart-define=SHOP_UI=v1` 回退，两套实现依旧各自独立。
///
/// ⚠️ 用 [Notifier] 而非 `StateProvider` —— 后者在 Riverpod 3 已被移除
/// （同 `shop_repository.dart` 的说明）。
class ShopUiVariantNotifier extends Notifier<ShopUiVariant> {
  @override
  ShopUiVariant build() => _defaultVariant;

  void toggle() =>
      state = state.isV2 ? ShopUiVariant.v1 : ShopUiVariant.v2;

  void set(ShopUiVariant v) => state = v;
}

final NotifierProvider<ShopUiVariantNotifier, ShopUiVariant> shopUiVariantProvider =
    NotifierProvider<ShopUiVariantNotifier, ShopUiVariant>(ShopUiVariantNotifier.new);

/// 按当前变体二选一渲染。
///
/// 路由表里用它包住两套页面实现，这样 **URL、深链、导航栈、受控路由名单全都不变** ——
/// 切换 UI 不该动路由结构，否则对比的就不只是 UI 了。
class ShopUiSwitch extends ConsumerWidget {
  const ShopUiSwitch({super.key, required this.v1, required this.v2});

  final WidgetBuilder v1;
  final WidgetBuilder v2;

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      ref.watch(shopUiVariantProvider).isV2 ? v2(context) : v1(context);
}

/// Debug 构建里的变体切换入口（挂在两套 Toko 顶栏上）。release 构建返回 [SizedBox.shrink]。
///
/// 🔒 **必须靠 [kDebugMode] 而不是靠「没人会点」**：一个能改变整套结算界面的开关
/// 出现在正式包里，是能被用户误触的功能缺陷，不是隐藏彩蛋。
///
/// 🔴 <b>渲染成图标而非文字，这一条不是审美选择</b>：v1 的
/// `toko_page_test.dart` 有一条断言「`Kategori` 之前不得出现除 `Toko` 以外的任何
/// [Text]」——它守的是「区域①② 无数据时整区不渲染、不留空标题」。
/// 在顶栏塞一个写着 `v1` 的文字会让那条用例变红，而那**不是**真回归，
/// 是护栏被开发工具误伤。用 [Icon] 就绕开了，且完全不削弱那条护栏。
class ShopUiVariantToggle extends ConsumerWidget {
  const ShopUiVariantToggle({super.key, this.color});

  /// 图标色。放在墨底顶栏上时传白色。
  final Color? color;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (!kDebugMode) return const SizedBox.shrink();
    final variant = ref.watch(shopUiVariantProvider);
    return Semantics(
      button: true,
      // 语义标签用 Semantics 而非 Tooltip：Tooltip 在展开时会插入 Text。
      label: variant.isV2 ? 'switch to shop UI v1' : 'switch to shop UI v2',
      child: GestureDetector(
        key: const ValueKey('shopUiVariantToggle'),
        behavior: HitTestBehavior.opaque,
        onTap: () => ref.read(shopUiVariantProvider.notifier).toggle(),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          child: Icon(
            Icons.compare_arrows,
            size: 20,
            color: color ?? const Color(0xFF8A8398),
          ),
        ),
      ),
    );
  }
}
