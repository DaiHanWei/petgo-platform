import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter_test/flutter_test.dart';
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
    Future<void> Function()? prepareSession,
  }) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: SplashPage(onComplete: onComplete, prepareSession: prepareSession),
      ),
    ));
    // 让 didChangeDependencies → 异步 prefs 决策 → setState(_decided) 落地。
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  testWidgets('播完整动效，就绪后过场（快网：会话恢复早于动效结束）', (tester) async {
    var done = false;
    await pumpSplash(tester,
        onComplete: () => done = true,
        prepareSession: () => Future<void>.delayed(const Duration(milliseconds: 900)));

    expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
    expect(done, isFalse); // 动效进行中，过场未触发

    await tester.pump(const Duration(milliseconds: 1800)); // 越过 animatedHold(1720ms)
    expect(done, isTrue);

    await tester.pumpWidget(const SizedBox()); // 触发 dispose，避免 ticker 残留
  });

  group('Story 7.2 · 整体替换旧效果的下线断言', () {
    testWidgets('AC5：版本号与常驻 spinner 均已移除', (tester) async {
      await pumpSplash(tester, onComplete: () {}, prepareSession: () => Future<void>.value());

      // 版本号（改前硬编码 'v 1.0.0'，而 pubspec 已是 1.1.0+7 —— 缺陷 B-6）
      expect(find.textContaining('v 1.0.0'), findsNothing);
      expect(find.textContaining(RegExp(r'^v\s')), findsNothing);
      // 常驻 spinner：不反映真实进度，属假反馈（设计侧挑战 X-4）
      expect(find.byType(CircularProgressIndicator), findsNothing);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC0/AC5：尺寸相对屏宽 —— 换屏宽后 mark 与字标等比跟随', (tester) async {
      // ⚠️ 原实现是 `tester.getSize(find.byType(SplashPage)).width` —— 那是**整页宽**，
      // 于是 `expect(await markWidthAt(390), 390)` 恒真，把 mark 改回硬编码 `width: 108`
      // 也照样绿（code-review 2026-08-04）。现在真去量 mark 与字标自己的渲染宽度。
      //
      // 量的是**终态**（reduce-motion 把 `_master` 钉在 1.0）：首帧宽度按决策 D2 是与原生对齐的
      // 绝对值 [SplashPage.markHandoffWidth]，只有终态才是「相对屏宽」的那个契约。
      Future<(double mark, double wordmark)> sizesAt(double screenW) async {
        tester.view.physicalSize = Size(screenW, 2400);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.reset);
        await pumpSplash(tester,
            onComplete: () {},
            disableAnimations: true,
            prepareSession: () => Future<void>.value());
        // mark 用 SVG 本体量（Stack 里第一个 SVG —— glow 是 Container 不是 SVG）；
        // 字标用容器 key 量：它在资产异步载入完成前就存在，断言因此不依赖时序。
        final markW = tester.getSize(find.byType(SvgPicture).first).width;
        final wordmarkW = tester.getSize(find.byKey(SplashPage.wordmarkBoxKey)).width;
        await tester.pumpWidget(const SizedBox());
        return (markW, wordmarkW);
      }

      for (final screenW in const <double>[390, 1080]) {
        final (markW, wordmarkW) = await sizesAt(screenW);
        expect(markW, closeTo(screenW * SplashPage.markWidthRatio, 0.5),
            reason: 'mark 终态宽度应为屏宽 ×${SplashPage.markWidthRatio}（NFR-17：不写死绝对像素）');
        expect(wordmarkW, closeTo(screenW * SplashPage.wordmarkWidthRatio, 0.5),
            reason: '字标宽度应为屏宽 ×${SplashPage.wordmarkWidthRatio}');
      }
      // 比例常量本身即契约，写死绝对像素会破坏它（NFR-17）
      expect(SplashPage.markWidthRatio, 0.42);
      expect(SplashPage.wordmarkWidthRatio, 0.60);
      expect(SplashPage.glowWidthRatio, 0.667);
      expect(SplashPage.taglineWidthRatio, 0.70);
    });

    // 决策 D2（code-review 2026-08-04）：首帧必须与原生启动屏**同尺寸**交接。
    // 原生三端可视 mark 都是固定 192 逻辑像素（288 画布 × 2/3），而 Flutter 侧原先直接用
    // 屏宽 42% —— 两者只在屏宽 457dp 时相等（iPhone SE 差 30%），交接必跳尺寸。
    testWidgets('AC0：首帧 mark 宽度 = 原生的 192，与屏宽无关', (tester) async {
      for (final screenW in const <double>[320, 411, 1080]) {
        tester.view.physicalSize = Size(screenW, 866);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.reset);

        final never = Completer<void>();
        addTearDown(() { if (!never.isCompleted) never.complete(); });
        await pumpSplash(tester, onComplete: () {}, prepareSession: () => never.future);
        // pumpSplash 已推进 50ms，B1 已走了一小段 ⇒ 宽度已从 192 往相对尺寸插值。
        // 故断言「首帧一侧」用 t≈0 的上界：宽度必须落在 [min(192,相对), max(192,相对)] 内，
        // 且在 320dp 这种窄屏上必须**明显大于**相对尺寸（192 vs 134）。
        final markW = tester.getSize(find.byType(SvgPicture).first).width;
        final relative = screenW * SplashPage.markWidthRatio;
        final lo = relative < SplashPage.markHandoffWidth ? relative : SplashPage.markHandoffWidth;
        final hi = relative < SplashPage.markHandoffWidth ? SplashPage.markHandoffWidth : relative;
        expect(markW, inInclusiveRange(lo - 0.5, hi + 0.5),
            reason: '${screenW.toInt()}dp：mark 宽度应在「原生 192」与「相对 $relative」之间插值');
        if (screenW == 320) {
          expect(markW, greaterThan(relative + 20),
              reason: '窄屏上首帧应贴近原生的 192，而不是相对尺寸的 134 —— 否则交接跳尺寸');
        }
        await tester.pumpWidget(const SizedBox());
      }
      expect(SplashPage.markHandoffWidth, 192);
    });

    // 🔴 code-review 2026-08-04：首帧 mark 的垂直居中原先用「宽度的一半」抵扣，而 mark 是
    // 宽扁的（宽高比 1.21）⇒ 整块上移 `(w−h)/2 = 0.087w`，411dp 机型即 **15dp**。
    // 「首帧与原生那一帧同位」正是本 Epic 存在的理由，偏 15dp 等于交接处跳一下。
    testWidgets('AC0：mark 的视觉中心精确落在动效线上（居中抵扣必须用高度而非宽度）', (tester) async {
      tester.view.physicalSize = const Size(411, 866);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);

      final never = Completer<void>();
      addTearDown(() { if (!never.isCompleted) never.complete(); });
      // reduce-motion 把 `_master` 钉在 1.0 ⇒ B1 的 t 恒为 1 ⇒ mark 应当正中在**设计位 43%**。
      // 用终态而非首帧来断言是为了确定性：首帧之后动画立刻推进，按 t=0 断言会随 pump 时长漂移。
      await pumpSplash(tester,
          onComplete: () {}, disableAnimations: true, prepareSession: () => never.future);

      final rect = tester.getRect(find.byType(SvgPicture).first);
      final expected = 866 * SplashPage.centerYRatio;
      // 若把抵扣写回 `w / 2`，中心会上移 (w−h)/2 = 0.087w ≈ 15dp（411dp 机型）⇒ 这条会红。
      expect(rect.center.dy, closeTo(expected, 1.0),
          reason: 'mark 视觉中心应落在 43% 线（${expected.toStringAsFixed(1)}），'
              '实际 ${rect.center.dy.toStringAsFixed(1)}。偏约 15dp 通常意味着居中用了宽度的一半，'
              '而 mark 是宽扁的（宽高比 ${SplashPage.markAspect.toStringAsFixed(3)}）');
      // 宽高比契约：抵扣量取决于它，换资产必须同步改 markAspect
      expect(rect.width / rect.height, closeTo(SplashPage.markAspect, 0.01),
          reason: '渲染宽高比与 markAspect 不符 —— 资产 viewBox 变了但常量没跟着改');

      await tester.pumpWidget(const SizedBox());
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
      await pumpSplash(tester, onComplete: () {}, prepareSession: () => Future<void>.value());

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
      await pumpSplash(tester, onComplete: () {}, prepareSession: () => Future<void>.value());

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


    testWidgets('AC3：取消「当天只播一次」门控 —— 连续两次冷启动都播动效', (tester) async {
      // 决策 C-3。改前读 prefs 的 splashLastShownDate 判断当天是否已播；该键与其
      // getter/setter 已随本 Story 从 AppPrefs 整体删除（AC3 要求不留死代码）。
      for (var i = 0; i < 2; i++) {
        var done = false;
        await pumpSplash(tester,
            onComplete: () => done = true,
            prepareSession: () => Future<void>.delayed(const Duration(milliseconds: 900)));
        // 若门控仍在，第二次会走 staticHold(1400) 直落终态；此处按 animatedHold(1720) 断言
        await tester.pump(const Duration(milliseconds: 1500));
        expect(done, isFalse, reason: '第 ${i + 1} 次：1.5s 时不应已过场（说明走的是 1720ms 动画档）');
        await tester.pump(const Duration(milliseconds: 300));
        expect(done, isTrue, reason: '第 ${i + 1} 次：越过 1720ms 后应过场');
        await tester.pumpWidget(const SizedBox());
      }
    });

    testWidgets('AC3/缺陷 B-7：首帧无空窗 —— 不再等 prefs 才渲染内容', (tester) async {
      // 改前 `_decided` 标志要等 AppPrefs.create()（超时 300ms）返回后才渲染 mark。
      // 现在 prefs 已不是启动依赖，**第一帧就该有内容**。
      await tester.pumpWidget(MaterialApp(
        locale: const Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const SplashPage(),
      ));
      await tester.pump(); // 只推进一帧，不给任何异步机会
      expect(find.byType(SvgPicture), findsWidgets,
          reason: '首帧就应有 mark/字标，不得等 prefs（缺陷 B-7）');
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC6：快网路径全程无任何等待指示，且不闪现', (tester) async {
      // 阈值设计的全部意义：快网用户永远看不到进度线与慢网提示。
      // 最容易实现错的是「先显示再隐藏」—— 那样会看到一次闪现，故逐帧检查。
      var done = false;
      await pumpSplash(tester,
          onComplete: () => done = true,
          prepareSession: () => Future<void>.delayed(const Duration(milliseconds: 900)));

      // 注：提示的 Text 节点**恒在树中**（靠 opacity 显隐，以保证无布局位移 AC4），
      // 故不能用 findsNothing 断言"没出现"—— 要看 opacity。
      for (var t = 0; t < 2200; t += 100) {
        await tester.pump(const Duration(milliseconds: 100));
        for (final o in tester.widgetList<AnimatedOpacity>(find.byType(AnimatedOpacity))) {
          expect(o.opacity, 0, reason: 't=${t}ms 处等待指示的 opacity 不为 0（疑似闪现）');
        }
      }
      expect(done, isTrue);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC4/AC5：慢网路径按 1860 / 2290 / 5000 三个时点依次发生', (tester) async {
      var done = false;
      // 永不完成的恢复 → 必然走到 5s 兜底。
      // 用 Completer 而非 Future.delayed：后者会留下悬挂定时器，测试结束时 flutter_test 报错。
      final never = Completer<void>();
      addTearDown(() { if (!never.isCompleted) never.complete(); });
      await pumpSplash(tester, onComplete: () => done = true, prepareSession: () => never.future);

      Iterable<double> opacities() =>
          tester.widgetList<AnimatedOpacity>(find.byType(AnimatedOpacity)).map((e) => e.opacity);

      // 1500ms：动效未播完，两者都不该出现
      await tester.pump(const Duration(milliseconds: 1450));
      expect(opacities().every((o) => o == 0), isTrue, reason: '1.5s 时不应有任何等待指示');

      // 1900ms：越过 progressLineAt(1860) → 进度线出现，提示仍无
      await tester.pump(const Duration(milliseconds: 450));
      expect(opacities().where((o) => o == 1).length, 1, reason: '1.9s 时应只有进度线可见');

      // 2350ms：越过 slowHintAt(2290) → 提示也淡入
      await tester.pump(const Duration(milliseconds: 450));
      expect(opacities().where((o) => o == 1).length, 2, reason: '2.35s 时进度线与提示都应可见');

      // 未就绪时不得提前过场
      expect(done, isFalse, reason: '会话未就绪，不应在 5s 兜底前过场');

      // 5100ms：越过 readyDeadline(5000) → 兜底放行
      await tester.pump(const Duration(milliseconds: 2800));
      expect(done, isTrue, reason: '到 5s 兜底应无条件放行（决策 D-2）');

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC5：慢网提示可读时间约 2.7s（5000 − 2290），且三时点由动效时长派生', (tester) async {
      expect(SplashPage.progressLineAt, const Duration(milliseconds: 1860));
      expect(SplashPage.slowHintAt, const Duration(milliseconds: 2290));
      expect(SplashPage.readyDeadline, const Duration(milliseconds: 5000));
      final readable = SplashPage.readyDeadline - SplashPage.slowHintAt;
      expect(readable.inMilliseconds, 2710);
      // 三时点必须晚于动效结束，否则「出现即信息」的语义不成立
      expect(SplashPage.progressLineAt.inMilliseconds,
          greaterThan(SplashPage.animatedTotal.inMilliseconds));
    });

    testWidgets('AC4：等待指示的容器高度恒定 —— 出现/消失不引起布局位移', (tester) async {
      var done = false;
      final never = Completer<void>();
      addTearDown(() { if (!never.isCompleted) never.complete(); });
      await pumpSplash(tester, onComplete: () => done = true, prepareSession: () => never.future);

      // 容器是 ConstrainedBox(minHeight:)（code-review 2026-08-04 由写死的 height 改来，
      // 见下一条溢出用例）。「无位移」靠的是提示 Text 恒在树中 ⇒ 高度与是否可见无关。
      final slot = find.byWidgetPredicate((w) =>
          w is ConstrainedBox &&
          w.constraints.minHeight == SplashPage.bottomSlotHeight);
      final before = tester.getRect(slot.first);
      await tester.pump(const Duration(milliseconds: 2400)); // 两个指示都已出现
      final after = tester.getRect(slot.first);
      expect(after, before, reason: '等待指示出现后底部容器位置/尺寸不得变化（AC4）');
      expect(done, isFalse);
      await tester.pump(const Duration(milliseconds: 2800)); // 越过 5s 兜底，清掉 splash 的定时器
      await tester.pumpWidget(const SizedBox());
    });

    // 🔴 code-review 2026-08-04：底部等待槽原先是写死的 `SizedBox(height: 56)`，
    // 56 按「12.5px × 行高 1.4 的单行」算出，没给系统字号放大（NFR-13 支持到 1.3）
    // 和窄屏换行留余量。实测溢出：360dp + 印尼语 + **默认字号**就溢出 14px。
    // 而且提示 Text 恒在树中 ⇒ **快网用户从没见过这条提示，也一样溢出**。
    // 这一组用例把「窄屏 × 三档字号」全部钉住，别再退回固定高度。
    for (final w in const <double>[360, 411]) {
      for (final scale in const <double>[1.0, 1.15, 1.3]) {
        testWidgets('AC4：${w.toInt()}dp × 字号 $scale 下等待槽不溢出（含印尼语最长文案）',
            (tester) async {
          tester.view.physicalSize = Size(w, 866);
          tester.view.devicePixelRatio = 1.0;
          addTearDown(tester.view.reset);

          final never = Completer<void>();
          addTearDown(() { if (!never.isCompleted) never.complete(); });
          await tester.pumpWidget(MaterialApp(
            locale: const Locale('id'), // 印尼语提示最长
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: MediaQuery(
              data: MediaQueryData(textScaler: TextScaler.linear(scale)),
              child: SplashPage(onComplete: () {}, prepareSession: () => never.future),
            ),
          ));
          await tester.pump();
          // 推进到慢网提示已出现（2290ms）—— 溢出与否与可见性无关，但显示态更直观
          await tester.pump(const Duration(milliseconds: 2400));

          expect(tester.takeException(), isNull,
              reason: '${w.toInt()}dp × $scale 下底部等待槽溢出了（RenderFlex overflow）');

          await tester.pumpWidget(const SizedBox());
        });
      }
    }

    testWidgets('AC8：未改落地分流与 onComplete 契约 —— 仅调用一次', (tester) async {
      var calls = 0;
      await pumpSplash(tester,
          onComplete: () => calls++,
          prepareSession: () => Future<void>.value());
      await tester.pump(const Duration(milliseconds: 6000)); // 越过 hold 与 5s 兜底
      expect(calls, 1, reason: 'onComplete 只能被调用一次（就绪与兜底两条路径不得重复触发）');
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('AC7：reduce-motion 直落终态，不播入场', (tester) async {
      var done = false;
      await pumpSplash(tester,
          onComplete: () => done = true,
          disableAnimations: true,
          prepareSession: () => Future<void>.value());

      expect(find.textContaining('Komunitas Pecinta Hewan Peliharaan'), findsOneWidget);
      // 走 staticHold(1400) 而非 animatedHold(1720)
      await tester.pump(const Duration(milliseconds: 1500));
      expect(done, isTrue);

      await tester.pumpWidget(const SizedBox());
    });
  });
}
