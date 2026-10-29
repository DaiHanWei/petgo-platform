import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:tailtopia/core/router/route_intent.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_guide_controller.dart';
import 'package:tailtopia/features/content/data/detail_repository.dart';
import 'package:tailtopia/features/content/domain/comment.dart';
import 'package:tailtopia/features/content/domain/content_detail.dart';
import 'package:tailtopia/features/content/domain/content_pet_ref.dart';
import 'package:tailtopia/features/content/domain/content_tag.dart';
import 'package:tailtopia/features/content/presentation/content_detail_page.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_avatar_stack.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_names.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

/// V1.4.0 multi-pet Story 3.3 · AC4：详情页关联宠物胶囊（F6 有图 / F6b 无图 / F6c ≥ 3 只「+N」）。
void main() {
  const orenji = ContentPetRef(petId: 11, name: 'Orenji', petType: 'CAT');
  const kopi = ContentPetRef(petId: 12, name: 'Kopi', petType: 'DOG');
  const mochi = ContentPetRef(petId: 13, name: 'Mochi');
  const bubu = ContentPetRef(petId: 14, name: 'Bubu');

  group('模型：ContentDetail.pets', () {
    Map<String, dynamic> wire([Object? pets]) => {
          'id': 1,
          'authorId': 7,
          'type': 'GROWTH_MOMENT',
          'createdAt': '2026-10-18T00:00:00Z',
          'pets': ?pets,
        };

    test('解析 3.1 的 pets 键（petId / name / avatarUrl / petType），保持下发顺序', () {
      final d = ContentDetail.fromJson(wire([
        {'petId': 3, 'name': 'Orenji', 'avatarUrl': 'https://cdn/a.jpg', 'petType': 'CAT'},
        {'petId': 1, 'name': 'Kopi', 'avatarUrl': null, 'petType': 'DOG'},
      ]));
      expect(d.pets.map((p) => p.petId), [3, 1]);
      expect(d.pets.first.name, 'Orenji');
      expect(d.pets.first.avatarUrl, 'https://cdn/a.jpg');
      expect(d.pets.last.avatarUrl, isNull);
      expect(d.pets.last.petType, 'DOG');
    });

    test('缺省 / 非数组 / 坏元素 → 空表（不崩）', () {
      expect(ContentDetail.fromJson(wire()).pets, isEmpty);
      expect(ContentDetail.fromJson(wire('x')).pets, isEmpty);
      expect(ContentDetail.fromJson(wire([{'name': 'no id'}, 3])).pets, isEmpty);
    });

    test('withFollowEpoch 保留 pets', () {
      final d = ContentDetail.fromJson(wire([
        {'petId': 3, 'name': 'Orenji'},
      ]));
      expect(d.withFollowEpoch(2).pets.single.petId, 3);
    });
  });

  group('formatPetNames（AC4.6 / AC1.4 共用）', () {
    late AppLocalizations id;
    late AppLocalizations en;
    setUpAll(() async {
      id = await AppLocalizations.delegate.load(const Locale('id'));
      en = await AppLocalizations.delegate.load(const Locale('en'));
    });

    test('1 / 2 / ≥3 只（有图 / 无图句式）', () {
      expect(formatPetNames(id, ['Orenji']), 'Orenji');
      expect(formatPetNames(id, ['Orenji', 'Kopi']), 'Orenji & Kopi');
      expect(formatPetNames(id, ['Orenji', 'Kopi', 'Mochi']), 'Orenji dkk.');
      expect(formatPetNames(id, ['Orenji'], withPrefix: true), 'bersama Orenji');
      expect(formatPetNames(id, ['Orenji', 'Kopi'], withPrefix: true), 'bersama Orenji & Kopi');
      expect(formatPetNames(id, ['Orenji', 'Kopi', 'Mochi'], withPrefix: true), 'bersama Orenji dkk.');
      expect(formatPetNames(en, ['Orenji', 'Kopi', 'Mochi']), 'Orenji and others');
      expect(formatPetNames(en, const []), '');
    });
  });

  ContentDetail detail({
    String type = 'GROWTH_MOMENT',
    List<ContentPetRef> pets = const [],
    List<String> images = const [],
    List<ContentTag> tags = const [],
  }) =>
      ContentDetail(
        id: 5,
        authorId: 7,
        authorDeleted: false,
        authorNickname: 'Alice',
        type: type,
        body: 'Main bareng',
        likeCount: 0,
        commentCount: 0,
        liked: false,
        isAuthor: false,
        createdAt: DateTime.utc(2026, 10, 18),
        imageUrls: images,
        decorationTags: tags,
        pets: pets,
      );

  Future<_Guide> pump(WidgetTester tester, ContentDetail d, {bool loggedIn = true}) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final guide = _Guide();
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (c, s) => const ContentDetailPage(postId: 5)),
      GoRoute(path: '/pets/:petId', builder: (c, s) => Scaffold(body: Text('visitor ${s.uri}'))),
    ]);
    addTearDown(router.dispose);
    final container = ProviderContainer(overrides: [
      detailRepositoryProvider.overrideWithValue(_Repo(d)),
      authControllerProvider.overrideWith(() => _Auth(loggedIn
          ? const AuthState(status: AuthStatus.authenticated, role: 'USER')
          : const AuthState.guest())),
      loginGuideControllerProvider.overrideWithValue(guide),
    ]);
    addTearDown(container.dispose);
    await tester.pumpWidget(UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        routerConfig: router,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('id'),
      ),
    ));
    await tester.pumpAndSettle();
    return guide;
  }

  const dark = ValueKey('detailLinkedPetsDark');
  const light = ValueKey('detailLinkedPetsLight');
  // 本地 file: 图（不存在 → errorBuilder 占位）：避免测试里发真实网络请求。
  const img = ['file:/nonexistent/a.jpg', 'file:/nonexistent/b.jpg'];

  int avatarCount(WidgetTester tester) => find
      .descendant(of: find.byType(PetAvatarStack), matching: find.byWidgetPredicate((w) {
        final k = w.key;
        return k is ValueKey<String> && k.value.startsWith('petAvatarStackItem-');
      }))
      .evaluate()
      .length;

  group('F6 有图：首图左下深色胶囊', () {
    testWidgets('1 只：「Orenji」+ 1 个头像；Moment 同数据不显示', (tester) async {
      await pump(tester, detail(pets: [orenji], images: img));
      expect(find.byKey(dark), findsOneWidget);
      expect(find.byKey(light), findsNothing);
      expect(find.descendant(of: find.byKey(dark), matching: find.text('Orenji')), findsOneWidget);
      expect(avatarCount(tester), 1);
    });

    testWidgets('2 只：「Orenji & Kopi」+ 2 个头像', (tester) async {
      await pump(tester, detail(pets: [orenji, kopi], images: img));
      expect(find.descendant(of: find.byKey(dark), matching: find.text('Orenji & Kopi')), findsOneWidget);
      expect(avatarCount(tester), 2);
      expect(find.byKey(const ValueKey('petAvatarStackMore')), findsNothing);
    });

    testWidgets('3 只：「Orenji dkk.」+ 3 个头像（不出 +N）', (tester) async {
      await pump(tester, detail(pets: [orenji, kopi, mochi], images: img));
      expect(find.descendant(of: find.byKey(dark), matching: find.text('Orenji dkk.')), findsOneWidget);
      expect(avatarCount(tester), 3);
      expect(find.byKey(const ValueKey('petAvatarStackMore')), findsNothing);
    });

    testWidgets('F6c 4 只：2 个头像 +「+2」', (tester) async {
      await pump(tester, detail(pets: [orenji, kopi, mochi, bubu], images: img));
      expect(avatarCount(tester), 2);
      expect(find.byKey(const ValueKey('petAvatarStackMore')), findsOneWidget);
      expect(find.text('+2'), findsOneWidget);
    });

    testWidgets('胶囊在轮播外层 Stack（滑动时固定）且位于首图左下 12 / 12', (tester) async {
      await pump(tester, detail(pets: [orenji], images: img));
      expect(find.ancestor(of: find.byKey(dark), matching: find.byType(PageView)), findsNothing);
      final capsule = tester.getRect(find.byKey(dark));
      final pageView = tester.getRect(find.byType(PageView));
      expect(capsule.left - pageView.left, 12);
      expect(pageView.bottom - capsule.bottom, 12);
    });

    testWidgets('有装饰标签：两者都在，装饰标签在胶囊正上方（同左边距、不重叠）', (tester) async {
      await pump(tester,
          detail(pets: [orenji, kopi], images: img, tags: const [ContentTag(code: 'X', name: 'Lucu', icon: 'x', description: '')]));
      final capsule = tester.getRect(find.byKey(dark));
      final tag = tester.getRect(find.byKey(const ValueKey('detailDecorationTagOverlay')));
      expect(tag.left, capsule.left);
      expect(tag.bottom, lessThanOrEqualTo(capsule.top - 8 + 0.01));
    });

    testWidgets('无关联宠物：装饰标签位置不变（回归保护）', (tester) async {
      await pump(tester, detail(images: img, tags: const [ContentTag(code: 'X', name: 'Lucu', icon: 'x', description: '')]));
      expect(find.byKey(dark), findsNothing);
      final tag = tester.getRect(find.byKey(const ValueKey('detailDecorationTagOverlay')));
      final pageView = tester.getRect(find.byType(PageView));
      expect(pageView.bottom - tag.bottom, 8); // AppSpacing.sm
    });
  });

  group('F6b 无图：正文之后、分隔线之前的浅色胶囊', () {
    testWidgets('「bersama Orenji & Kopi」浅色胶囊，位于正文之后、评论标题之前', (tester) async {
      await pump(tester, detail(pets: [orenji, kopi]));
      expect(find.byKey(light), findsOneWidget);
      expect(find.byKey(dark), findsNothing);
      expect(find.descendant(of: find.byKey(light), matching: find.text('bersama Orenji & Kopi', findRichText: true)),
          findsOneWidget);
      final body = tester.getRect(find.text('Main bareng'));
      final capsule = tester.getRect(find.byKey(light));
      final comments = tester.getRect(find.byKey(const ValueKey('detailCommentsTitle')));
      expect(capsule.top, greaterThan(body.bottom));
      expect(capsule.bottom, lessThan(comments.top));
    });

    testWidgets('1 只无图：「bersama Orenji」', (tester) async {
      await pump(tester, detail(pets: [orenji]));
      expect(find.descendant(of: find.byKey(light), matching: find.text('bersama Orenji', findRichText: true)),
          findsOneWidget);
    });

    testWidgets('Moment（即使带 pets）/ 无关联 Diary → 不显示', (tester) async {
      await pump(tester, detail(type: 'DAILY', pets: [orenji]));
      expect(find.byKey(light), findsNothing);
      expect(find.byKey(dark), findsNothing);
    });

    testWidgets('无关联 Diary → 不显示', (tester) async {
      await pump(tester, detail());
      expect(find.byKey(light), findsNothing);
    });
  });

  group('AC4.7 点击', () {
    testWidgets('1 只 → 直接进访客视图 /pets/{id}?from=content_detail（不弹抽屉）', (tester) async {
      await pump(tester, detail(pets: [orenji]));
      await tester.tap(find.byKey(light));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('detailLinkedPetsSheet')), findsNothing);
      expect(find.text('visitor /pets/11?from=content_detail'), findsOneWidget);
    });

    testWidgets('多只 → 先弹抽屉列出全部，点某行进该宠物访客视图', (tester) async {
      await pump(tester, detail(pets: [orenji, kopi, mochi, bubu], images: img));
      await tester.tap(find.byKey(dark));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('detailLinkedPetsSheet')), findsOneWidget);
      for (final id in [11, 12, 13, 14]) {
        expect(find.byKey(ValueKey('detailLinkedPetRow-$id')), findsOneWidget);
      }
      expect(find.text('Kucing'), findsOneWidget); // 副标题物种（petTypeCat）
      await tester.tap(find.byKey(const ValueKey('detailLinkedPetRow-12')));
      await tester.pumpAndSettle();
      expect(find.text('visitor /pets/12?from=content_detail'), findsOneWidget);
    });

    testWidgets('游客：点胶囊触发登录门控，不跳转', (tester) async {
      final guide = await pump(tester, detail(pets: [orenji]), loggedIn: false);
      await tester.tap(find.byKey(light));
      await tester.pumpAndSettle();
      expect(guide.hardDialogs, 1);
      expect(find.textContaining('visitor /pets'), findsNothing);
    });

    testWidgets('Semantics：button + 名字串 label', (tester) async {
      final handle = tester.ensureSemantics();
      await pump(tester, detail(pets: [orenji, kopi]));
      expect(find.bySemanticsLabel('Orenji & Kopi'), findsOneWidget);
      handle.dispose();
    });
  });

  test('源码：Feed 卡片不加关联宠物；详情作者行不加宠物行', () {
    final feed = Directory('lib/features/content/presentation')
        .listSync()
        .whereType<File>()
        .where((f) => f.path.contains('feed'))
        .map((f) => f.readAsStringSync())
        .join('\n');
    expect(feed.contains('ContentLinkedPetsCapsule'), isFalse);
    final src = File('lib/features/content/presentation/content_detail_page.dart').readAsStringSync();
    final authorRow = src.substring(src.indexOf('Widget _authorRow('), src.indexOf('void _showMoreSheet('));
    expect(authorRow.contains('ContentLinkedPetsCapsule'), isFalse);
  });
}

class _Repo implements DetailRepository {
  _Repo(this.d);

  final ContentDetail d;

  @override
  Future<ContentDetail> getDetail(int id) async => d;

  @override
  Future<CommentPage> getComments(int postId, {String? cursor}) async =>
      const CommentPage(items: [], nextCursor: null, hasMore: false);

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

class _Auth extends AuthController {
  _Auth(this._initial);
  final AuthState _initial;

  @override
  AuthState build() => _initial;
}

class _Guide extends LoginGuideController {
  _Guide() : super(() async => null);
  int hardDialogs = 0;

  @override
  Future<void> showHardDialog(BuildContext context,
      {RouteIntent? pendingAction, String entrySource = 'other'}) async {
    hardDialogs++;
  }
}
