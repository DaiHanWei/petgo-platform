import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/theme/shop_tokens.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_buttons.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_controls.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_decor.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_pressable.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_countdown.dart';
import 'package:tailtopia/features/shop/presentation/widgets/shop_surface.dart';

/// 电商共享组件层的行为护栏（V1.4.0 第 1 批）。
///
/// 只测**会静默退化**的部分：纯配色/圆角这类改了一眼就能看出来的不测，
/// 测的是倒计时算法、步进器边界、触控区尺寸、灰缝厚度这些「错了也照样跑」的地方。
void main() {
  Widget host(Widget child) => MaterialApp(home: Scaffold(body: Center(child: child)));

  group('倒计时格式化', () {
    test('不足 1 小时用 mm:ss', () {
      expect(formatCountdown(const Duration(minutes: 58, seconds: 12)), '58:12');
      expect(formatCountdown(const Duration(seconds: 9)), '00:09');
    });

    test('满 1 小时进位到 h:mm:ss —— 59:59 与 1:00:00 的分界', () {
      expect(formatCountdown(const Duration(minutes: 59, seconds: 59)), '59:59');
      expect(formatCountdown(const Duration(hours: 1)), '1:00:00');
    });

    test('秒数向上取整 —— 60 分钟窗口刚打开不能显示 59:59', () {
      // 截断实现会让支付窗口「一加载就少一分钟」。
      expect(formatCountdown(const Duration(minutes: 59, seconds: 59, milliseconds: 900)),
          '1:00:00');
      expect(formatCountdown(const Duration(milliseconds: 1)), '00:01');
    });

    test('已过期与负数都归零，不出现负号', () {
      // 服务端时间戳早于设备时钟时会算出负值。显示 `-03:21` 是最糟的一种错。
      expect(formatCountdown(Duration.zero), '00:00');
      expect(formatCountdown(const Duration(seconds: -201)), '00:00');
    });
  });

  group('倒计时组件', () {
    // 可控时钟：pump 推的是 fake async，推不动 DateTime.now()，
    // 故用注入的 now 来表达「真实世界过去了多久」。
    late DateTime clock;
    DateTime now() => clock;

    setUp(() => clock = DateTime.utc(2026, 8, 19, 12));

    testWidgets('按到期时刻渲染', (tester) async {
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.add(const Duration(minutes: 2, seconds: 30)),
        style: ShopText.countdownInline,
        now: now,
      )));
      expect(find.text('02:30'), findsOneWidget);
    });

    testWidgets('每次都用 expiresAt − now 重算，不本地累加', (tester) async {
      // 🔴 这条是本组件存在的理由。模拟「切后台 5 分钟」：真实时间走了 5 分钟，
      //    而定时器只被节流到跑了 1 次 tick。累加实现会显示 09:59（只减了 1 秒），
      //    重算实现显示 05:00。
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.add(const Duration(minutes: 10)),
        style: ShopText.countdownInline,
        now: now,
      )));
      expect(find.text('10:00'), findsOneWidget);

      clock = clock.add(const Duration(minutes: 5));
      await tester.pump(const Duration(seconds: 1));

      expect(find.text('05:00'), findsOneWidget,
          reason: '显示 09:59 说明退化成了本地累加 —— 后台待久了倒计时就会慢一大截');
    });

    testWidgets('回到前台立即重算，不等下一个 tick', (tester) async {
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.add(const Duration(minutes: 10)),
        style: ShopText.countdownInline,
        now: now,
      )));
      clock = clock.add(const Duration(minutes: 3));

      // 只发生命周期事件，不推进 fake async 时钟（即：不给定时器 tick 的机会）
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();

      expect(find.text('07:00'), findsOneWidget,
          reason: '不监听 resumed 的话，回到前台最多会显示 1 秒的陈旧值');
    });

    testWidgets('用等宽字族渲染 —— 换成比例字体会让倒计时逐秒抖动', (tester) async {
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.add(const Duration(minutes: 5)),
        style: ShopText.countdownHero,
        now: now,
      )));
      final text = tester.widget<Text>(find.byType(Text));
      expect(text.style?.fontFamily, 'IBMPlexMono');
    });

    testWidgets('归零回调只触发一次', (tester) async {
      var fired = 0;
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.add(const Duration(seconds: 2)),
        style: ShopText.countdownInline,
        now: now,
        onExpired: () => fired++,
      )));

      clock = clock.add(const Duration(seconds: 2));
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();

      expect(fired, 1, reason: '过期后每秒重复回调会让页面反复重建');
    });

    testWidgets('首帧已过期 → 立即回调且显示 00:00', (tester) async {
      var fired = 0;
      await tester.pumpWidget(host(ShopCountdown(
        expiresAt: clock.subtract(const Duration(minutes: 5)),
        style: ShopText.countdownInline,
        now: now,
        onExpired: () => fired++,
      )));
      expect(find.text('00:00'), findsOneWidget);
      await tester.pump();
      expect(fired, 1);
    });
  });

  group('步进器边界', () {
    testWidgets('触顶只禁用 +，− 仍可点', (tester) async {
      int? got;
      await tester.pumpWidget(host(
        ShopStepper(value: 5, min: 1, max: 5, onChanged: (v) => got = v),
      ));

      await tester.tap(find.byKey(const ValueKey('stepperInc')));
      expect(got, isNull, reason: '已达库存上限，+ 必须无效');

      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      expect(got, 4, reason: '触顶时 − 依然要能点 —— 禁用整个控件是错的');
    });

    testWidgets('触底只禁用 −', (tester) async {
      int? got;
      await tester.pumpWidget(host(
        ShopStepper(value: 1, min: 1, max: 9, onChanged: (v) => got = v),
      ));
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      expect(got, isNull);
      await tester.tap(find.byKey(const ValueKey('stepperInc')));
      expect(got, 2);
    });

    testWidgets('两个按钮的命中区都撑到 44×44 —— 视觉 22px 直接点不中', (tester) async {
      int? got;
      await tester.pumpWidget(host(
        ShopStepper(value: 3, min: 1, max: 9, onChanged: (v) => got = v),
      ));
      // 命中区由 [ShopPressable] 承担（2026-08-27 由本文件私有的 _TapTarget 提升而来），
      // 因此直接量它 —— 比「最近的一个 SizedBox」精确，换实现也不会误报。
      for (final k in ['stepperDec', 'stepperInc']) {
        final target = find.ancestor(
          of: find.byKey(ValueKey(k)),
          matching: find.byType(ShopPressable),
        );
        final box = tester.getSize(target.first);
        expect(box.width, greaterThanOrEqualTo(44), reason: '$k 命中区宽度不足');
        expect(box.height, greaterThanOrEqualTo(44), reason: '$k 命中区高度不足');
      }
      // 光有尺寸不够：撑出来的那圈必须真的可点（HitTestBehavior.opaque），
      // 否则 44 的盒子里只有中间 22 能响应，等于白撑。
      final inc = find.ancestor(
        of: find.byKey(const ValueKey('stepperInc')),
        matching: find.byType(ShopPressable),
      );
      final r = tester.getRect(inc.first);
      await tester.tapAt(Offset(r.left + 2, r.top + 2));
      expect(got, 4, reason: '命中区边角点不中 —— 撑大的区域没有参与命中测试');
    });

    testWidgets('🔴 触底 + onRemove ⇒ − 变删除图标，点击走删除而不是减一', (tester) async {
      // 2026-08-21 默认变体翻到 v2 后，v2 购物车的有效行**没有任何删除入口** ——
      // 减到 1 就到底。这条锁住补回来的行为。
      int? changed;
      var removed = 0;
      await tester.pumpWidget(host(ShopStepper(
        value: 1,
        min: 1,
        max: 9,
        onChanged: (v) => changed = v,
        onRemove: () => removed++,
      )));

      expect(find.byIcon(Icons.delete_outline), findsOneWidget,
          reason: '图标必须跟着换 —— 否则用户点了「−」整行却消失，会以为自己点错了');
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      expect(removed, 1);
      expect(changed, isNull, reason: '触底时点 − 是删除，不是把数量减成 0');
    });

    testWidgets('不给 onRemove 时保持原行为：触底禁用 −', (tester) async {
      // 退货申请页就是这一支：那里的数量是「退几件」，退 0 件没有意义。
      int? changed;
      await tester.pumpWidget(host(
        ShopStepper(value: 1, min: 1, max: 9, onChanged: (v) => changed = v),
      ));
      expect(find.byIcon(Icons.delete_outline), findsNothing);
      await tester.tap(find.byKey(const ValueKey('stepperDec')));
      expect(changed, isNull);
    });
  });

  group('按钮的请求进行中态', () {
    testWidgets('🔴 loading 时转圈而不是置灰，且不可点', (tester) async {
      // 此前各页的做法是把 variant 切成 disabled —— 屏幕上和「这个商品卖完了」
      // 完全一样，在 `Bayar` 上会让用户重复点击。
      var taps = 0;
      await tester.pumpWidget(host(ShopButton(
        label: 'Bayar',
        variant: ShopButtonVariant.pay,
        loading: true,
        onTap: () => taps++,
      )));
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      await tester.tap(find.byType(ShopButton), warnIfMissed: false);
      expect(taps, 0, reason: 'loading 期间必须吃掉点击');
    });

    testWidgets('loading 不改变按钮尺寸 —— 否则底部条会跳一下', (tester) async {
      await tester.pumpWidget(host(const ShopButton(
          label: 'Bayar Rp 154.000', variant: ShopButtonVariant.pay)));
      final idle = tester.getSize(find.byType(ShopButton));

      await tester.pumpWidget(host(const ShopButton(
          label: 'Bayar Rp 154.000', variant: ShopButtonVariant.pay, loading: true)));
      expect(tester.getSize(find.byType(ShopButton)), idle);
    });
  });

  group('计数角标', () {
    testWidgets('🔴 不是正圆 —— BoxShape.circle 只按最短边画圆，99+ 会溢出', (tester) async {
      await tester.pumpWidget(host(const ShopCountBadge(count: 120)));
      final d = tester.widget<Container>(find.byType(Container)).decoration! as BoxDecoration;
      expect(d.shape, BoxShape.rectangle);
      expect(d.borderRadius, isNotNull, reason: '要用胶囊圆角，一位数时仍是正圆、多位数横向伸长');
      expect(find.text('99+'), findsOneWidget);
      // 文字必须画得进背景块里。
      final box = tester.getSize(find.byType(Container));
      final label = tester.getSize(find.text('99+'));
      expect(box.width, greaterThanOrEqualTo(label.width));
    });
  });

  group('勾选框', () {
    testWidgets('失效分组的勾选框是浅底空块，不是灰色的勾', (tester) async {
      // 设计稿：失效商品「勾选框改为空块（不可选）」。渲染成灰勾会读作「已选中但锁定」，
      // 与「不可选」是两个意思。
      await tester.pumpWidget(host(
        ShopCheckbox(value: true, enabled: false, onChanged: (_) {}),
      ));
      expect(find.byIcon(Icons.check), findsNothing);
    });

    testWidgets('不可点时不回调', (tester) async {
      var tapped = false;
      await tester.pumpWidget(host(
        ShopCheckbox(value: false, enabled: false, onChanged: (_) => tapped = true),
      ));
      await tester.tap(find.byType(ShopCheckbox));
      expect(tapped, isFalse);
    });
  });

  group('开关', () {
    testWidgets('静默期开关常亮且点不动 —— 这是把产品底线写进界面', (tester) async {
      var changed = false;
      await tester.pumpWidget(host(
        ShopSwitch(value: false, alwaysOn: true, onChanged: (_) => changed = true),
      ));
      await tester.tap(find.byType(ShopSwitch));
      expect(changed, isFalse, reason: '「可点但点了没反应」是 bug 的样子，不是规则的样子');
      expect(find.byType(Opacity), findsOneWidget, reason: '不可关态须半透明呈现');
    });
  });

  group('墨底顶栏', () {
    // 🔴 真机上发现的：深链直达购物车时没有返回箭头，标题被屏幕左边缘切掉半个字。
    //    根因是 `titleSpacing: 0` —— 那个值只在左边确实有箭头时才对。
    testWidgets('无返回箭头时标题让出屏边距，不贴死左边缘', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(appBar: ShopAppBar(title: 'Keranjang (2)')),
      ));
      await tester.pumpAndSettle();

      final left = tester.getTopLeft(find.text('Keranjang (2)')).dx;
      expect(left, greaterThanOrEqualTo(kShopScreenEdge),
          reason: '标题贴死到 x=0 会被屏幕边缘切掉');
    });

    testWidgets('有返回箭头时标题紧跟箭头（不额外留白）', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          appBar: ShopAppBar(
            title: 'Detail',
            leading: const Icon(Icons.arrow_back_ios_new),
          ),
        ),
      ));
      await tester.pumpAndSettle();

      final left = tester.getTopLeft(find.text('Detail')).dx;
      // 箭头本身已占掉左侧空间，标题不该再退一个屏边距
      expect(left, lessThan(kShopScreenEdge * 2 + 40));
    });
  });

  group('灰缝', () {
    testWidgets('ShopSection 底部让出 3px 灰缝，且无圆角无阴影', (tester) async {
      await tester.pumpWidget(host(const ShopSection(child: Text('x'))));
      final box = tester.widget<DecoratedBox>(
        find.descendant(of: find.byType(ShopSection), matching: find.byType(DecoratedBox)).first,
      );
      final d = box.decoration as BoxDecoration;
      expect(d.border!.bottom.width, kShopGutter);
      expect(d.border!.bottom.color, ShopColors.bg,
          reason: '灰缝必须与页面底色同色 —— 它是「露出底色」，不是一条边框线');
      expect(d.borderRadius, isNull, reason: '加圆角会把灰缝设计退化成卡片列表');
      expect(d.boxShadow, isNull, reason: '设计稿明令产品 UI 内不使用阴影');
    });
  });
}
