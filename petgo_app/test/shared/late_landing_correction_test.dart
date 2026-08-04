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

    test('AC6 埋点口径：沿用既有 T-1 事件名，新增两个区分属性（源码级锁定）', () {
      // 超时兜底态与真实游客态**都上报 user_state=guest**，混在一起则「兜底发生率」无法
      // 统计（PRD OQ-23 的口径要求）。故：兜底那次带 restore_timeout、纠正那次带 corrected_from。
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      // 事件名不得新造 —— 否则 Story 6.1 已交付的看板口径会断
      expect(src.contains("'app_launch_landed_on_tab'"), isTrue);
      expect(
        RegExp(r"Analytics\.capture\('app_launch_landed_on_tab'").allMatches(src).length,
        2,
        reason: '应恰好两处上报：兜底落地 + 纠正后落地',
      );
      expect(src.contains("'restore_timeout': true"), isTrue, reason: '兜底那次的区分标记');
      expect(src.contains("'corrected_from': from"), isTrue, reason: '纠正那次的区分标记');
      // 属性名 snake_case（CLAUDE.md 命名映射链）
      for (final k in ['restore_timeout', 'corrected_from', 'user_state']) {
        expect(k, matches(RegExp(r'^[a-z][a-z0-9_]*$')));
      }
      // 不得引入新的埋点 SDK / 不得绕过门面直调 SDK。
      // 注：`PosthogObserver` 是 Story 6.1 既有的自动页面追踪 observer，属既有实现，不在此列。
      expect(src.contains('Posthog()'), isFalse,
          reason: '上报一律走 Analytics 门面，不得在路由层直调 PostHog SDK');
    });

    test('🔴 ⑦ 安全红线：兽医角色隔离守卫仍在，未被 FR-91 替代（源码级锁定）', () {
      // 那是**用户/兽医路由互斥的安全边界**，继承「安全规则只升不降不可绕过」。
      // FR-91 新增机制时容易产生「都统一走迟到纠正就行了」的想法 —— 不行，必须留作双保险。
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      expect(src.contains('if (auth.isVet)'), isTrue, reason: '兽医隔离守卫不得移除');
      expect(src.contains("return '/vet/workbench'"), isTrue);
      expect(src.contains("if (isVetRoute && loc != '/vet/login') return '/home';"), isTrue,
          reason: '反向隔离（非兽医不进 vet 路由）同样不得移除');
      // 门控「默认受控 + 精确例外」的安全默认也不得被本 Story 动到（NFR-3）
      expect(src.contains('!auth.isLoggedIn && controlled'), isTrue);
    });

    test('AC9 边界：未把 .timeout 改成可取消（7-4 的迟到纠正正建立在该行为之上）', () {
      final src = File('lib/core/router/app_router.dart').readAsStringSync();
      // 仍是「只停止等待、不取消底层请求」的 timeout；若换成可取消的实现，恢复晚到就不会
      // 写回 AuthState，迟到纠正将永远不触发。
      expect(src.contains('.timeout('), isTrue);
      expect(src.contains('onTimeout: () => timedOut = true'), isTrue,
          reason: '超时只做标记、不取消 —— 这是 FR-91 成立的前提');
    });

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
