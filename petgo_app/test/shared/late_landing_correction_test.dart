import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/user_state.dart';

/// FR-91「超时兜底的迟到纠正」判定（V1.1.2 Story 7.4）。
///
/// 背景：`ensureRestored()` 的 `.timeout()` **只停止等待、不取消底层请求** —— 恢复完成后仍会
/// 写回 `AuthState`。所以超时兜底送去的落地页**是会变化的中间态，不是终态**。兽医能自愈
/// （redirect 的角色隔离守卫收口），但已登录的 A·未建档与 B/C 不能：门控条件是
/// `!auth.isLoggedIn && controlled`，已登录不命中，redirect 里也没有任何分支会把已登录用户
/// 从 Diary 移到 Discovery。本机制补的正是这一环。
void main() {
  group('FR-91 · 七条硬约束', () {
    test('①④ 未武装 / 额度已用完 → 不纠正', () {
      expect(
        resolveLateCorrection(armed: null, currentPath: '/profile', recomputedTarget: '/home'),
        LateCorrectionOutcome.notArmed,
      );
    });

    test('🔴 ③/AC4 安全攸关：用户已自行离开兜底落地页 → 一律不纠正', () {
      // 「把正在浏览的人拽走」比「落错页」严重得多 —— 本条优先级**高于**「纠正到正确页」。
      // 即便重判结果与当前位置不同（即"本该纠正"），只要人已经走了就必须放手。
      expect(
        resolveLateCorrection(
          armed: '/profile', // 兜底送去 Diary
          currentPath: '/triage', // 用户已自己切到问诊
          recomputedTarget: '/home', // 本该纠正到 Discovery
        ),
        LateCorrectionOutcome.userMovedOn,
        reason: '用户已离开时必须放弃纠正，不得因"本该纠正"而拽人',
      );
    });

    test('⑥ 恢复失败（确实是游客）→ 重判结果不变 → 不产生跳转', () {
      expect(
        resolveLateCorrection(
          armed: '/profile',
          currentPath: '/profile',
          recomputedTarget: '/profile', // 游客重判仍是 Diary
        ),
        LateCorrectionOutcome.targetUnchanged,
        reason: '真游客不应因本机制被打扰',
      );
    });

    test('主诉求：B/C 慢网超时 → 最终落 Discovery（不停在「有宠专属」拒绝页）', () {
      // 超时时被当作游客处理 ⇒ 兜底落 /profile（Diary 渲染 FR-80 游客种草页，因
      // resolveDiaryUserState 的输入是 isLoggedIn）。恢复回来后真实身份是 B/C。
      for (final s in [AppUserState.planning, AppUserState.enthusiast]) {
        expect(
          resolveLateCorrection(
            armed: '/profile',
            currentPath: '/profile',
            recomputedTarget: s.landingLocation,
          ),
          LateCorrectionOutcome.correct,
        );
        expect(s.landingLocation, '/home');
      }
    });

    test('A·未建档 慢网超时 → 最终落 Discovery（矩阵已于 Story 7.4 订正）', () {
      expect(
        resolveLateCorrection(
          armed: '/profile',
          currentPath: '/profile',
          recomputedTarget: AppUserState.ownerWithoutProfile.landingLocation,
        ),
        LateCorrectionOutcome.correct,
      );
      expect(AppUserState.ownerWithoutProfile.landingLocation, '/home');
    });

    test('兽医 慢网超时 → 纠正到工作台（与角色隔离守卫结果一致，互为双保险）', () {
      // ⑦ 守卫本身原样保留、未被本机制替代：两者算出同一目标，且 consume() 保证只跳一次。
      expect(
        resolveLateCorrection(
          armed: '/profile',
          currentPath: '/profile',
          recomputedTarget: AppUserState.vet.landingLocation,
        ),
        LateCorrectionOutcome.correct,
      );
      expect(AppUserState.vet.landingLocation, '/vet/workbench');
    });

    test('游客 / A·已建档 超时 → 位置本就正确 → 不跳（无感知）', () {
      for (final s in [AppUserState.guest, AppUserState.ownerWithProfile]) {
        expect(
          resolveLateCorrection(
            armed: '/profile',
            currentPath: '/profile',
            recomputedTarget: s.landingLocation,
          ),
          LateCorrectionOutcome.targetUnchanged,
        );
      }
    });

    test('🔴 AC4 工程注意：判定只看路径 —— 同页开弹层/进详情再返回不算"离开"', () {
      // 若用「路由栈深度」判定，同页内 push/pop 会让栈深变化而被误判成"已离开"，
      // 从而放弃本该做的纠正。故判定入参只有路径，没有栈深度这类间接信号。
      // 这里以"路径未变即视为未离开"来锁定该口径。
      expect(
        resolveLateCorrection(
          armed: '/profile',
          currentPath: '/profile', // 栈深可能已变，但路径没变 ⇒ 人没走
          recomputedTarget: '/home',
        ),
        LateCorrectionOutcome.correct,
      );
    });

    test('④ 只纠正一次：consume 后额度用尽，此后本次冷启动内不再干预', () {
      final lc = LateLandingCorrection();
      lc.arm('/profile');
      expect(lc.armed, '/profile');
      lc.consume();
      expect(lc.armed, isNull, reason: '额度已用完');
      lc.arm('/profile'); // 再次武装应无效
      expect(lc.armed, isNull, reason: 'consume 后不得再被武装（防与其它跳转逻辑互相拉扯）');
    });

    test('disarm 只放弃本次、不消耗额度（用户离开/结果不变时用它，非 consume）', () {
      final lc = LateLandingCorrection();
      lc.arm('/profile');
      lc.disarm();
      expect(lc.armed, isNull);
      lc.arm('/home'); // 未 consume ⇒ 仍可武装
      expect(lc.armed, '/home');
    });

    test('AC6 埋点口径：属性名 snake_case', () {
      // ⚠️ 事件名与两个区分属性的**实际上报**已改为行为级断言，见
      // `test/shared/splash_landing_budget_test.dart`：兜底那次带 `restore_timeout: true`、
      // 纠正那次带 `corrected_from`，且两次都用既有事件名 `app_launch_landed_on_tab`。
      //
      // 原先这里是源码 grep：`src.contains("'corrected_from': from")` 逐字匹配一行代码，
      // 以及 `allMatches(...).length == 2` 数源码里出现几次。两者都守的是写法而不是行为 ——
      // code-review 2026-08-04 把 `corrected_from` 的取值从路径原文改成产品名（与同一次上报的
      // `tab` 对齐）之后，行为更正确了，这条却因为字面量变了而变红。
      for (final k in ['restore_timeout', 'corrected_from', 'user_state']) {
        expect(k, matches(RegExp(r'^[a-z][a-z0-9_]*$')));
      }
      // 不得引入新的埋点 SDK / 不得绕过门面直调 SDK。
      // 注：`PosthogObserver` 是 Story 6.1 既有的自动页面追踪 observer，属既有实现，不在此列。
      // 这一条**保留源码断言**：它守的是「不出现某个写法」，而不是某段行为 ——
      // 反例天生只能从源码上判定，行为测试看不见「有人偷偷直调了 SDK」。
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      expect(src.contains('Posthog()'), isFalse,
          reason: '上报一律走 Analytics 门面，不得在路由层直调 PostHog SDK');
    });

    test('🔴 ⑦ 安全红线：兽医角色隔离守卫仍在，未被 FR-91 替代（源码级锁定）', () {
      // 那是**用户/兽医路由互斥的安全边界**，继承「安全规则只升不降不可绕过」。
      // FR-91 新增机制时容易产生「都统一走迟到纠正就行了」的想法 —— 不行，必须留作双保险。
      // ⚠️ 断言**分要素匹配**，不逐字匹配整行（code-review 2026-08-04）：原先其中一条写的是
      // `src.contains("if (isVetRoute && loc != '/vet/login') return '/home';")` ——
      // 一次 `dart format` 折行就会变红，而行为一个字都没变；反过来它也拦不住语义被改坏
      // 但格式恰好没动的情况。真正的行为覆盖在 `test/auth/story_1_5_gating_test.dart`
      // 与 `test/shared/splash_landing_budget_test.dart`（兽医慢网兜底 → 收口工作台）。
      // 这里只留「关键要素还在源码里」这一层粗筛，作为「被整段删掉」的最后一道提醒。
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      // 去掉换行与连续空白，使断言对格式化不敏感
      final flat = src.replaceAll(RegExp(r'\s+'), ' ');
      expect(flat.contains('if (auth.isVet)'), isTrue, reason: '兽医隔离守卫不得移除');
      expect(flat.contains("return '/vet/workbench'"), isTrue);
      for (final piece in const ['isVetRoute', "loc != '/vet/login'", "return '/home'"]) {
        expect(flat.contains(piece), isTrue,
            reason: '反向隔离（非兽医不进 vet 路由）的要素 `$piece` 不得移除');
      }
      // 门控「默认受控 + 精确例外」的安全默认也不得被本 Story 动到（NFR-3）
      expect(flat.contains('!auth.isLoggedIn && controlled'), isTrue);
    });

    // AC9 边界「不得把等待改成可取消」的锁定已移到**行为级**测试：
    // `test/shared/splash_landing_budget_test.dart` 的「恢复晚到仍写回 → 迟到纠正照常触发」。
    //
    // 原先这里断言的是 `app_router.dart` 源码里出现 `.timeout(` 与
    // `onTimeout: () => timedOut = true` 两个**字面量** —— 那守的是实现细节而不是约束本身：
    // code-review 2026-08-04 把「二次等待」整段删掉（等待预算收归 splash 单一时间源）后，
    // 约束依然成立（恢复未被取消、晚到仍写回、纠正照常触发），这条却因为字面量消失而变红。
    // 反过来，只要有人保留字面量却改成可取消的实现，它又拦不住。故改用行为断言。

    test('六态全覆盖：每一态都有确定的判定结果，无遗漏', () {
      for (final s in AppUserState.values) {
        final o = resolveLateCorrection(
          armed: '/profile',
          currentPath: '/profile',
          recomputedTarget: s.landingLocation,
        );
        expect(
          o,
          anyOf(LateCorrectionOutcome.correct, LateCorrectionOutcome.targetUnchanged),
          reason: '$s 的判定结果应确定',
        );
      }
    });
  });
}
