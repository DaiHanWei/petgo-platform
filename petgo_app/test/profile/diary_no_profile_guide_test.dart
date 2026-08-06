import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/content/data/feed_repository.dart';
import 'package:tailtopia/features/content/presentation/feed_tab_row.dart';
import 'package:tailtopia/features/content/presentation/home_page.dart';
import 'package:tailtopia/features/profile/data/profile_repository.dart';
import 'package:tailtopia/features/profile/data/timeline_repository.dart';
import 'package:tailtopia/features/profile/domain/archive_stats.dart';
import 'package:tailtopia/features/profile/domain/pet_profile.dart';
import 'package:tailtopia/features/profile/domain/share_service.dart';
import 'package:tailtopia/features/profile/domain/timeline_item.dart';
import 'package:tailtopia/features/profile/presentation/diary_guest_page.dart';
import 'package:tailtopia/features/profile/presentation/growth_archive_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';

import '../support/fake_feed_repository.dart';

/// Story 2.3 · L0：状态 A 未建档的建档引导态 + B/C 零改动 + FR-0H 首页提示条整条废止。
///
/// FR-0H 那条断言是**下线核对**：不是「不显示」，而是代码、持久化、埋点、顶部预留区全部不存在。
/// 用源码级扫描守门 —— 只断言 UI 不可见的话，日后有人把组件加回来照样过。
class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

AuthState _auth({required String petStatus, bool hasPetProfile = false}) => AuthState(
      status: AuthStatus.authenticated,
      role: 'USER',
      profile: UserProfile(petStatus: petStatus, hasPetProfile: hasPetProfile),
    );

Widget _wrapDiary({required AuthState auth, PetProfile? profile}) => ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(() => _TestAuthController(auth)),
        petProfileProvider.overrideWith((ref) async => profile),
        timelineFirstPageProvider.overrideWith((ref) async => const TimelinePage(items: [])),
        archiveStatsProvider.overrideWith((ref) async => const ArchiveStats(
            happyMomentCount: 0, consultCount: 0, milestoneCompleted: 0, milestoneTotal: 30)),
        shareFabAnimatedShownProvider.overrideWith((ref) async => true),
      ],
      child: const MaterialApp(
        locale: Locale('id'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: GrowthArchivePage(),
      ),
    );

void main() {
  group('AC1 状态 A 未建档 → 建档引导态（A2 稿）', () {
    testWidgets('标题 + 副文案 + 建档 CTA + 改状态逃生入口，且不含游客种草内容', (tester) async {
      await tester.pumpWidget(_wrapDiary(auth: _auth(petStatus: 'HAS_PET'), profile: null));
      await tester.pumpAndSettle();

      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.text(l10n.growthArchiveEmptyTitle), findsOneWidget);
      // A2 稿的一句副文案（说明「为什么先建档」）——本 Story 补齐，此前缺失
      expect(find.text(l10n.growthArchiveEmptyBody), findsOneWidget);
      expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
      // 删档后不被困在状态 A（bug 20260702-237）
      expect(find.byKey(const ValueKey('growthChangeStatusButton')), findsOneWidget);

      // AC1：不得对已登录用户重复种草 —— 游客引导态的任何痕迹都不应出现
      expect(find.byType(DiaryGuestPage), findsNothing);
      expect(find.byKey(const ValueKey('diaryDemoStrip')), findsNothing);
      expect(find.byKey(const ValueKey('diaryGuestPrimaryCta')), findsNothing);
    });
  });

  group('AC2 状态 B/C 零改动（UX-DR6 回归基准）', () {
    for (final status in const ['PLANNING', 'ENTHUSIAST']) {
      testWidgets('$status 进 Diary → 既有「有宠专属」页，不引导建档', (tester) async {
        await tester.pumpWidget(_wrapDiary(auth: _auth(petStatus: status)));
        await tester.pumpAndSettle();

        final l10n = await AppLocalizations.delegate.load(const Locale('id'));
        expect(find.text(l10n.growthArchiveNonOwnerTitle), findsOneWidget);
        expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
        // 不引导建档：既无建档 CTA，也无建档引导的标题/副文案
        expect(find.byKey(const ValueKey('growthCreateButton')), findsNothing);
        expect(find.text(l10n.growthArchiveEmptyBody), findsNothing);
        expect(find.byType(DiaryGuestPage), findsNothing);
      });
    }
  });

  group('AC3 FR-0H 首页提示条整条废止（下线核对）', () {
    testWidgets('Discovery 顶部只有分类 Tab，无建档提示条、无预留空隙', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: [
          authControllerProvider
              .overrideWith(() => _TestAuthController(_auth(petStatus: 'HAS_PET'))),
          feedRepositoryProvider.overrideWithValue(FakeFeedRepository()),
        ],
        child: const MaterialApp(
          locale: Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: HomePage(),
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.byType(FeedTabRow), findsOneWidget);
      // 提示条文案（V1.0.0 FR-0H）不应再出现在任何位置
      final l10n = await AppLocalizations.delegate.load(const Locale('id'));
      expect(find.text(l10n.growthArchiveEmptyTitle), findsNothing);
      expect(find.byKey(const ValueKey('growthCreateButton')), findsNothing);
    });

    test('源码级：提示条组件、状态机、持久化键、启动注水全部不存在', () {
      // 「整条废止」不是「保留代码但不显示」——文件与符号都不许留。
      for (final path in const [
        'lib/shared/widgets/profile_prompt_bar.dart',
        'lib/features/profile/domain/profile_prompt_state.dart',
        'lib/features/profile/domain/profile_prompt_controller.dart',
      ]) {
        expect(File(path).existsSync(), isFalse, reason: '$path 应已删除（FR-0H 整条废止）');
      }

      // 三个持久化键（前 3 次重启计数 / 永久关闭 / 档案已完成）随之下线
      final prefs = File('lib/core/storage/prefs.dart').readAsStringSync();
      for (final key in const [
        'kProfilePromptRestartCount',
        'kProfilePromptDismissedPermanently',
        'kPetProfileCompleted',
      ]) {
        expect(prefs.contains(key), isFalse, reason: 'prefs 仍留着 $key');
      }

      // 启动期注水与首页接线均已摘除
      expect(File('lib/main.dart').readAsStringSync().contains('ProfilePrompt'), isFalse);
      final home = File('lib/features/content/presentation/home_page.dart').readAsStringSync();
      expect(home.contains('ProfilePromptBar'), isFalse);
      expect(home.contains('shouldShowProfilePrompt'), isFalse);
      expect(home.contains('showPrompt'), isFalse);
    });

    test('埋点：全仓无该提示条的曝光/点击/关闭事件（不留恒为 0 的指标）', () {
      final hits = <String>[];
      for (final f in Directory('lib').listSync(recursive: true).whereType<File>()) {
        if (!f.path.endsWith('.dart')) continue;
        final src = f.readAsStringSync();
        // 埋点上报一律走 Analytics.capture；扫「事件名里带 profile_prompt」的调用
        if (src.contains('profile_prompt') || src.contains('profilePrompt')) {
          hits.add(f.path);
        }
      }
      expect(hits, isEmpty, reason: '仍有文件引用 FR-0H 提示条: $hits');
    });
  });
}
