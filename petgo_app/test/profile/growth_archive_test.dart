import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:petgo/features/auth/domain/auth_state.dart';
import 'package:petgo/features/auth/domain/login_response.dart';
import 'package:petgo/features/profile/data/profile_repository.dart';
import 'package:petgo/features/profile/data/timeline_repository.dart';
import 'package:petgo/features/profile/domain/pet_profile.dart';
import 'package:petgo/features/profile/domain/share_service.dart';
import 'package:petgo/features/profile/domain/timeline_item.dart';
import 'package:petgo/features/profile/presentation/growth_archive_page.dart';
import 'package:petgo/l10n/app_localizations.dart';

class _TestAuthController extends AuthController {
  _TestAuthController(this._initial);
  final AuthState _initial;
  @override
  AuthState build() => _initial;
}

AuthState _authA() => const AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: 'A', hasPetProfile: true),
    );

AuthState _authB() => const AuthState(
      status: AuthStatus.authenticated,
      profile: UserProfile(petStatus: 'B'),
    );

Widget _wrap({required AuthState auth, PetProfile? profile, TimelinePage? page}) {
  return ProviderScope(
    overrides: [
      authControllerProvider.overrideWith(() => _TestAuthController(auth)),
      petProfileProvider.overrideWith((ref) async => profile),
      timelineFirstPageProvider.overrideWith(
        (ref) async => page ?? const TimelinePage(items: []),
      ),
      shareFabAnimatedShownProvider.overrideWith((ref) async => true),
    ],
    child: const MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: GrowthArchivePage(),
    ),
  );
}

void main() {
  testWidgets('状态 B/C → 非有宠态 + 修改状态入口（AC3）', (tester) async {
    await tester.pumpWidget(_wrap(auth: _authB()));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('changeStatusButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
    // 2.7 AC3：B/C 无名片可分享 → 无分享 FAB
    expect(find.byKey(const ValueKey('shareFab')), findsNothing);
  });

  testWidgets('状态 A 无档案 → 空状态「立即创建」（AC2）', (tester) async {
    await tester.pumpWidget(_wrap(auth: _authA(), profile: null));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('growthCreateButton')), findsOneWidget);
    expect(find.byKey(const ValueKey('petInfoCard')), findsNothing);
  });

  testWidgets('状态 A 有档案 → 信息卡 + 空时间线（AC1）', (tester) async {
    const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T', breed: 'Shiba');
    await tester.pumpWidget(_wrap(auth: _authA(), profile: profile));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('petInfoCard')), findsOneWidget);
    expect(find.text('Momo'), findsOneWidget);
    // 2.7 AC1：A + 有档案 → 渲染分享 FAB
    expect(find.byKey(const ValueKey('shareFab')), findsOneWidget);
  });

  testWidgets('有档案 + 含健康事件条目 → 渲染健康事件样式（AC1 前向兼容 J4）', (tester) async {
    const profile = PetProfile(id: 1, name: 'Momo', cardToken: 'T');
    final page = TimelinePage(items: [
      TimelineItem(
        kind: TimelineKind.healthEvent,
        date: DateTime.parse('2026-06-03T10:00:00Z'),
        aiLevel: 'YELLOW',
        symptomSummary: '咳嗽',
      ),
    ]);
    await tester.pumpWidget(_wrap(auth: _authA(), profile: profile, page: page));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('healthEventTile')), findsOneWidget);
    expect(find.text('YELLOW'), findsOneWidget);
  });
}
