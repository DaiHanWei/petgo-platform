import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/onboarding/presentation/splash_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// 启动屏回归（V1.1.2 Story 7.2 · 方案 B「写名字」整体重做后）。
///
/// 与改版前的差异（**改断言而非绕过**，Story 7.2 AC9）：
/// - 版本号已整体移除（AC5 / 缺陷 B-6）→ 删除 `SplashPage.version` 断言（该常量已不存在）
/// - 入场总时长 4320ms → **1540ms**，`animatedHold` 4500 → **1720ms**（AC4）
/// - 常驻 spinner 已移除（AC5）；条件出现的进度线属 Story 7.3
///
/// 注入 onComplete 以免依赖 GoRouter；含无限光晕动画，故用 pump(Duration) 而非 pumpAndSettle。
void main() {
  Future<void> pumpSplash(
    WidgetTester tester, {
    required VoidCallback onComplete,
    bool disableAnimations = false,
  }) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: SplashPage(onComplete: onComplete),
      ),
    ));
    // 让 didChangeDependencies → 异步 prefs 决策 → setState(_decided) 落地。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  testWidgets('当天首开：播完整动效，渲染标语，~1.72s 后过场', (tester) async {
    SharedPreferences.setMockInitialValues({}); // 无记录 → 首开 → 动画
    var done = false;
    await pumpSplash(tester, onComplete: () => done = true);

    expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
    expect(done, isFalse); // 动效进行中，过场未触发

    await tester.pump(const Duration(milliseconds: 1800)); // 越过 animatedHold(1720ms)
    expect(done, isTrue);

    await tester.pumpWidget(const SizedBox()); // 触发 dispose，避免 ticker 残留
  });

  testWidgets('当天已播过：静止终态，~1.4s 后过场', (tester) async {
    final n = DateTime.now();
    SharedPreferences.setMockInitialValues(
        {'petgo.splash_last_shown_date': '${n.year}-${n.month}-${n.day}'});
    var done = false;
    await pumpSplash(tester, onComplete: () => done = true);

    expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
    expect(done, isFalse);

    await tester.pump(const Duration(milliseconds: 1500)); // 越过 staticHold(1400ms)
    expect(done, isTrue);

    await tester.pumpWidget(const SizedBox());
  });

  group('Story 7.2 · 整体替换旧效果的下线断言', () {
    testWidgets('AC5：版本号与常驻 spinner 均已移除', (tester) async {
      SharedPreferences.setMockInitialValues({});
      await pumpSplash(tester, onComplete: () {});

      // 版本号（改前硬编码 'v 1.0.0'，而 pubspec 已是 1.1.0+7 —— 缺陷 B-6）
      expect(find.textContaining('v 1.0.0'), findsNothing);
      expect(find.textContaining(RegExp(r'^v\s')), findsNothing);
      // 常驻 spinner：不反映真实进度，属假反馈（设计侧挑战 X-4）
      expect(find.byType(CircularProgressIndicator), findsNothing);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC0/AC5：尺寸相对屏宽 —— 换屏宽后 mark 与字标等比跟随', (tester) async {
      SharedPreferences.setMockInitialValues({});

      Future<double> markWidthAt(double screenW) async {
        tester.view.physicalSize = Size(screenW, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.reset);
        await pumpSplash(tester, onComplete: () {});
        // 首帧（B1 起点）mark 可见；取其渲染宽度
        final w = tester.getSize(find.byType(SplashPage)).width;
        await tester.pumpWidget(const SizedBox());
        return w;
      }

      expect(await markWidthAt(390), 390);
      expect(await markWidthAt(1080), 1080);
      // 比例常量本身即契约，写死绝对像素会破坏它（NFR-17）
      expect(SplashPage.markWidthRatio, 0.42);
      expect(SplashPage.wordmarkWidthRatio, 0.60);
      expect(SplashPage.glowWidthRatio, 0.667);
      expect(SplashPage.taglineWidthRatio, 0.70);
    });

    testWidgets('AC4：入场总时长收敛为 1540ms，不超 1.8s 上限（NFR-13）', (tester) async {
      expect(SplashPage.animatedTotal, const Duration(milliseconds: 1540));
      expect(SplashPage.animatedTotal.inMilliseconds, lessThanOrEqualTo(1800));
      // hold 随之收敛（改前 4500ms 是配 4320ms 动效的）
      expect(SplashPage.animatedHold, const Duration(milliseconds: 1720));
    });

    testWidgets('AC0：首帧交接位为 50%，设计位 43% —— 两者不可混为一谈', (tester) async {
      // Android 12+ 系统 splash 图标不可定位、只能落 50% 正中；
      // 「50%→43%」的抬升是 B1 拍的动作，省掉会在交接处跳位。
      expect(SplashPage.handoffYRatio, 0.50);
      expect(SplashPage.centerYRatio, 0.43);
    });


    testWidgets('🐛 回归：动效播完后内容仍在（Stack 不得因非定位子元素塌成 0×0）', (tester) async {
      // 2026-08-04 真机空屏缺陷的守卫测试。
      // 成因：_markHandoff 在终态 t>=1 时返回 SizedBox.shrink() —— 这是**非定位**子元素，
      // Stack 一旦出现非定位子元素就会把自身尺寸收缩为其最大尺寸（0×0），导致所有兄弟元素
      // 一起消失。旧测试只 pump 60ms（t<1，仍返回 Positioned）恰好躲过，故 L0 全绿而真机全黑。
      // 本测试**必须 pump 越过 animatedTotal**，否则失去意义。
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.625;
      addTearDown(tester.view.reset);
      SharedPreferences.setMockInitialValues({});
      await pumpSplash(tester, onComplete: () {});

      // 越过入场总时长（1540ms），进入终态
      await tester.pump(const Duration(milliseconds: 1600));

      final svgs = find.byType(SvgPicture);
      expect(svgs, findsWidgets, reason: '终态仍应有字标的 9 拍 SVG 节点');
      // 关键断言：终态下每个 SVG 仍有非零尺寸。Stack 若塌成 0×0，这里会全为 0。
      for (final e in svgs.evaluate()) {
        final box = e.findRenderObject() as RenderBox;
        expect(box.size.width, greaterThan(0),
            reason: '终态下 SVG 宽度为 0 → Stack 很可能已塌陷（勿在 Stack 里返回非定位子元素）');
        expect(box.size.height, greaterThan(0));
      }
      // 标语同样应有可见尺寸
      expect(tester.getSize(find.byType(Text).first).width, greaterThan(0));

      await tester.pumpWidget(const SizedBox());
    });


    testWidgets('AC6：标语用 Fraunces + SOFT/WONK 两轴，9 项排版参数按 C-9 落定', (tester) async {
      SharedPreferences.setMockInitialValues({});
      await pumpSplash(tester, onComplete: () {});

      final style = tester.widget<Text>(find.byType(Text).first).style!;
      expect(style.fontFamily, 'Fraunces', reason: 'splash 只依赖 Fraunces 一款字体（NFR-16）');
      expect(style.fontSize, 16);           // 12.5 → 16
      expect(style.height, 1.5);            // 1.65 → 1.5（1.65 在 16px 下两行断开）
      expect(style.letterSpacing, 0);       // .15 → 0
      expect(style.fontWeight, FontWeight.w500);
      // wght/opsz 已烘死进字体，故 fontVariations 只剩 SOFT/WONK 两轴（见 pubspec 说明）
      final axes = {for (final v in style.fontVariations!) v.axis: v.value};
      expect(axes, {'SOFT': 100.0, 'WONK': 1.0});
      expect(style.color!.a, closeTo(0.68, 0.01)); // 白 62% → 68%

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC6/缺陷 B-9：两语言标语均无硬编码换行，靠 70% 屏宽自动换行', (tester) async {
      for (final loc in ['id', 'en']) {
        SharedPreferences.setMockInitialValues({});
        await tester.pumpWidget(MaterialApp(
          locale: Locale(loc),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const SplashPage(),
        ));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 50));
        final txt = tester.widget<Text>(find.byType(Text).first).data!;
        expect(txt.contains(r'\n'), isFalse, reason: '$loc 标语不得含硬编码换行（缺陷 B-9）');
        expect(txt, isNot(contains('\n')), reason: '$loc 标语不得含真实换行符');
        await tester.pumpWidget(const SizedBox());
      }
    });

    testWidgets('AC7：reduce-motion 直落终态，不播入场', (tester) async {
      SharedPreferences.setMockInitialValues({}); // 首开，但 reduce-motion 应压过
      var done = false;
      await pumpSplash(tester, onComplete: () => done = true, disableAnimations: true);

      expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
      // 走 staticHold(1400) 而非 animatedHold(1720)
      await tester.pump(const Duration(milliseconds: 1500));
      expect(done, isTrue);

      await tester.pumpWidget(const SizedBox());
    });
  });
}
