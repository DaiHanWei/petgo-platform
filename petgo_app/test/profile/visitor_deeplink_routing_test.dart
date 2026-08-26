import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/app.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/domain/visitor_profile.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/features/profile/presentation/visitor_archive_view.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.1.6 Story 2.4 · L0/widget：名片深链落点分流（AD-2 Rule 2/3/6）。
///
/// 改之前，点名片深链落的是 Diary Tab 根、**token 连解析都没有** ——
/// 未登录的人看到给游客做的示例成长本，已登录有宠的人看到自己家的宠物。
/// 两种都不是被分享的那一只。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

const String _sharedToken = 'SHARED-TOK';
const String _myToken = 'MY-OWN-TOK';

const AuthState _guest = AuthState.guest();

const AuthState _loggedInOwner = AuthState(
  status: AuthStatus.authenticated,
  role: 'USER',
  profile: UserProfile(petStatus: 'HAS_PET'),
);

PetProfile _myPet({required String cardToken}) =>
    PetProfile(id: 1, name: 'Momo', cardToken: cardToken, petType: 'CAT');

const VisitorProfile _sharedPet = VisitorProfile(
  name: 'Mochi',
  petType: 'CAT',
  ownerNickname: 'Rina',
);

/// 档案拉取计数器：未登录路径下必须为 0（无令牌 → 401 → 弹全局强登录窗）。
Widget _wrap({
  required AuthState auth,
  PetProfile? myProfile,
  void Function()? onProfileFetch,
}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      petProfileProvider.overrideWith((ref) async {
        onProfileFetch?.call();
        return myProfile;
      }),
      // 作者态数据（作者分支渲染需要）
      timelineFirstPageProvider.overrideWith((ref) async => const TimelinePage(items: [])),
      archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
      // 访客态数据
      visitorProfileProvider(_sharedToken).overrideWith((ref) async => _sharedPet),
      visitorStatsProvider(_sharedToken).overrideWith((ref) async => const ArchiveStats(
          happyMomentCount: 1, consultCount: 0, milestoneCompleted: 1, milestoneTotal: 31)),
      visitorTimelineProvider(_sharedToken)
          .overrideWith((ref) async => const TimelinePage(items: [])),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(visitorToken: _sharedToken),
    ),
  );
}

void main() {
  group('AC1/AC2 深链映射：token 必须活着到达落点', () {
    test('带 token → /pet/<token>；这正是本 story 修的那个缺陷', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/abc123')), '/pet/abc123');
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/SHARE-TOK')), '/pet/SHARE-TOK');
    });

    test('没有 token → 退回 Diary 根（没有 token 就没有可展示的宠物）', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://card')), '/profile');
      expect(deepLinkToLocation(Uri.parse('tailtopia://card/')), '/profile');
    });

    /// 🛡 token 只从 path 取，query 不参与 —— 少一个可被构造的入口。
    test('query 不能顶替 path 里的 token', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://card?token=abc')), '/profile');
    });

    test('其它 host 行为不变', () {
      expect(deepLinkToLocation(Uri.parse('tailtopia://open')), '/home');
      expect(deepLinkToLocation(Uri.parse('https://example.com/card/x')), isNull);
    });
  });

  group('AC1/AC3/AC4 三种身份的分流', () {
    testWidgets('未登录 → 访客只读态，且**不去拉自己的档案**', (tester) async {
      var fetches = 0;
      await tester.pumpWidget(_wrap(auth: _guest, onProfileFetch: () => fetches++));
      await tester.pumpAndSettle();

      expect(find.byType(VisitorArchiveView), findsOneWidget);
      // 🛡 这条是 AC3 的真实防线：没有令牌的档案请求会 401，
      // 而 401 会弹出全局强登录窗，正好把「不要求登录」打破。
      expect(fetches, 0,
          reason: '未登录时不该拉取自己的档案 —— 会 401 并弹全局强登录窗，把用户挡在门外');
    });

    testWidgets('已登录非作者 → 同样的访客只读态（AD-2 Rule 2）', (tester) async {
      await tester.pumpWidget(_wrap(
        auth: _loggedInOwner,
        myProfile: _myPet(cardToken: _myToken), // 自己的 token 与分享的不是同一个
      ));
      await tester.pumpAndSettle();

      expect(find.byType(VisitorArchiveView), findsOneWidget);
      expect(find.byKey(const ValueKey('editProfileButton')), findsNothing,
          reason: '已登录访客不该拿到管理入口');
    });

    /// 🛡 AC4 的正面用例：作者点到自己分享出去的链接（比如从自己的聊天记录里翻出来）。
    ///
    /// 落**自己的档案页**、保留全部管理入口。判错的方向有两个，后者严重得多：
    /// 作者丢管理入口（难用），或**访客拿到管理入口**（越权）。
    testWidgets('作者本人 → 落自己的档案页，管理入口俱在（AD-2 Rule 3）', (tester) async {
      await tester.pumpWidget(_wrap(
        auth: _loggedInOwner,
        myProfile: _myPet(cardToken: _sharedToken), // 这个 token 就是我自己的
      ));
      await tester.pumpAndSettle();

      expect(find.byType(VisitorArchiveView), findsNothing,
          reason: '作者本人不该落只读态');
      expect(find.byKey(const ValueKey('editProfileButton')), findsOneWidget,
          reason: '作者的管理入口必须保留');
      expect(find.byKey(const ValueKey('diaryIdCardButton')), findsOneWidget);
      expect(find.byKey(const ValueKey('diaryHealthEntry')), findsOneWidget);
    });
  });

  group('AC4 判定收在单一入口（AD-15）', () {
    test('作者识别：token 与自己的 cardToken 相同才算作者', () {
      expect(
        resolveDiaryUserState(
          isLoggedIn: true,
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          visitorToken: 'T1',
          myCardToken: 'T1',
        ),
        DiaryUserState.ownerWithProfile,
      );
      expect(
        resolveDiaryUserState(
          isLoggedIn: true,
          petStatus: 'HAS_PET',
          hasPetProfile: true,
          visitorToken: 'T1',
          myCardToken: 'T2',
        ),
        DiaryUserState.visitor,
      );
    });

    /// ⚠️ 自己的 token 还没加载出来时**按访客处理**，加载完自然切换。
    ///
    /// 反过来（先拦一道 loading）会让所有访客白等一次网络往返 —— 而访客里绝大多数不是作者。
    test('自己的 token 未知（未登录 / 档案未加载）→ 按访客处理', () {
      for (final my in <String?>[null, '']) {
        expect(
          resolveDiaryUserState(
            isLoggedIn: my != null,
            petStatus: 'HAS_PET',
            hasPetProfile: false,
            visitorToken: 'T1',
            myCardToken: my,
          ),
          DiaryUserState.visitor,
        );
      }
    });
  });
}
