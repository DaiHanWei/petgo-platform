import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/core/router/app_router.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';

/// V1.1.6 Story 2.3 · L0：访客路由的门控与边界（AD-2 Rule 6 · AD-3 Rule 4）。
void main() {
  group('AC5 访客路由必须对未登录开放', () {
    /// 🛡 同一个分享链接在浏览器里无需登录即可看完整 Diary。
    /// App 内若要求登录，只会把用户推回浏览器 —— 那正是这条链路要避免的。
    test('未登录访问 /pet/<token> 不被门控改写', () {
      expect(redirectWouldRewrite(const AuthState.guest(), '/pet/SHARE-TOK'), isFalse);
      expect(
        redirectWouldRewrite(const AuthState.guest(), '/pet/SHARE-TOK/day?date=2026-05-12'),
        isFalse,
      );
    });

    /// 🛡 反向护栏：作者态的档案子页仍然受控。
    ///
    /// 这条防的是「为了放行访客路由，顺手把 /profile 前缀整个开了」——
    /// 那会把建档、编辑、里程碑等页面一起对游客敞开。
    test('作者态受控子页照旧受控（放行访客路由不得波及它们）', () {
      for (final loc in ['/profile/edit', '/profile/id-card', '/profile/health', '/publish']) {
        expect(redirectWouldRewrite(const AuthState.guest(), loc), isTrue, reason: '$loc 应仍受控');
      }
    });

    /// 🛡 访客路由**不得**挪到 `/profile/` 之下。
    ///
    /// 门控是**前缀匹配、安全默认是拦**：一旦路径变成 `/profile/pet/:token`，
    /// 它会自动受控，未登录访客立刻被弹去登录 —— 而这个错误只有真机点链接才发现得了。
    test('访客路由路径不落在任何受控前缀下', () {
      const visitorPath = '/pet/SHARE-TOK';
      for (final controlled in ['/profile', '/triage', '/me', '/consult', '/notifications', '/publish']) {
        expect(visitorPath.startsWith('$controlled/'), isFalse);
        expect(visitorPath == controlled, isFalse);
      }
    });
  });

  group('AC4 不把链接边界扩散到全局路由（AD-3 Rule 4）', () {
    /// 🛡 内容详情页是**全局路由**，公开内容对谁都是公开的。
    ///
    /// 若为访客场景在那里加一层「这条是否属于某个已授权宠物」的校验，
    /// 等于把「分享链接的边界」搬进了全局路由 —— 从此每个进详情页的人都要过这道判定，
    /// 而这道判定一旦写错，影响的是**所有**内容，不只是访客。
    ///
    /// 私密内容在访客视图里**就地拦住**（点不开 + 给提示），根本到不了详情页。
    test('内容详情页没有出现任何 token / 访客相关校验', () {
      final src = File('lib/features/content/presentation/content_detail_page.dart')
          .readAsStringSync();
      for (final forbidden in ['visitorToken', 'ArchiveScope', 'sharedPet', 'cardToken']) {
        expect(src.contains(forbidden), isFalse,
            reason: '内容详情页出现了「$forbidden」—— AD-3 Rule 4 禁止在此为访客场景加二次校验层');
      }
    });
  });
}
