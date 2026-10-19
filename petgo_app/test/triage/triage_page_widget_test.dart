import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tailtopia/features/auth/domain/auth_state.dart';
import 'package:tailtopia/features/auth/domain/login_response.dart';
import 'package:tailtopia/features/consult/data/consult_repository.dart';
import 'package:tailtopia/features/consult/domain/consult_history_item.dart';
import 'package:tailtopia/features/consult/domain/consult_session.dart';
import 'package:tailtopia/features/profile/data/pet_list_repository.dart';
import 'package:tailtopia/features/triage/presentation/triage_page.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/widgets/login_hard_dialog.dart';

Future<void> _pump(WidgetTester tester) async {
  final container = ProviderContainer();
  addTearDown(container.dispose);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(
      locale: Locale('id'), // 文案已迁 arb：固定印尼语以断言 'Cek Gejala' 等 id 文案
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: TriagePage(),
    ),
  ));
  // 在线脉冲点为常驻动画，pumpAndSettle 不会收敛——用固定帧推进。
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  testWidgets('AC1: 双入口卡平级（AI 分诊 + 兽医咨询，TailTopia Prototype 换肤）', (tester) async {
    await _pump(tester);
    expect(find.byKey(const ValueKey('triageEntryAI')), findsOneWidget);
    expect(find.byKey(const ValueKey('triageEntryVet')), findsOneWidget);
    // 双入口标题 + CTA（换肤后印尼语文案）。
    expect(find.text('Cek Gejala'), findsOneWidget);
    expect(find.text('Chat Dokter Hewan'), findsOneWidget);
    expect(find.text('Mulai Cek'), findsOneWidget);
    expect(find.text('Mulai konsultasi'), findsOneWidget);
  });

  testWidgets('AC1/FR-0C: 游客点兽医咨询入口触发强登录引导（Story 5.8 接入 5.3 发起）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryVet')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  testWidgets('AC1/FR-0C: 游客点 AI 入口触发强登录引导（不进入上传页）', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(LoginHardDialog), findsOneWidget);
  });

  // ---- V1.4.0 multi-pet Story 4.1 · AC7.2（H1 / H3）：「Mulai Cek」先选宠物 ----
  group('Story 4.1 H1 选宠物', () {
    Future<void> pumpLoggedIn(WidgetTester tester, List<PetListItem> pets) async {
      SharedPreferences.setMockInitialValues({});
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (c, s) => const TriagePage()),
        GoRoute(path: '/triage/upload', builder: (c, s) => Scaffold(body: Text('upload:${s.uri.query}'))),
      ]);
      final container = ProviderContainer(overrides: [
        authControllerProvider.overrideWith(() => _LoggedIn()),
        consultRepositoryProvider.overrideWithValue(_QuietConsultRepo()),
        petListProvider.overrideWith((ref) async => pets),
      ]);
      addTearDown(container.dispose);
      await tester.pumpWidget(UncontrolledProviderScope(
        container: container,
        child: MaterialApp.router(
          routerConfig: router,
          locale: const Locale('id'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
    }

    Future<void> frames(WidgetTester tester) async {
      for (var i = 0; i < 6; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    const two = [PetListItem(id: 7, name: 'Momo', isDefault: true), PetListItem(id: 8, name: 'Kopi')];

    testWidgets('≥2 只：点 Mulai Cek 先弹抽屉；取消不跳转', (tester) async {
      await pumpLoggedIn(tester, two);
      await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
      await frames(tester);
      expect(find.text('Cek gejala untuk siapa?'), findsOneWidget);
      await tester.tapAt(const Offset(10, 10));
      await frames(tester);
      expect(find.textContaining('upload:'), findsNothing);
    });

    testWidgets('≥2 只：选中 Kopi → Lanjut → /triage/upload?petId=8', (tester) async {
      await pumpLoggedIn(tester, two);
      await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
      await frames(tester);
      await tester.tap(find.byKey(const ValueKey('petPickerRow-8')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('petPickerConfirm')));
      await frames(tester);
      expect(find.text('upload:petId=8'), findsOneWidget);
    });

    testWidgets('1 只：不弹（H3），直接进上传页并带那只的 petId（P-8）', (tester) async {
      await pumpLoggedIn(tester, const [PetListItem(id: 7, name: 'Momo')]);
      await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
      await frames(tester);
      expect(find.text('Cek gejala untuk siapa?'), findsNothing);
      expect(find.text('upload:petId=7'), findsOneWidget);
    });

    testWidgets('0 只：不弹，进上传页不带 petId（现状）', (tester) async {
      await pumpLoggedIn(tester, const []);
      await tester.tap(find.byKey(const ValueKey('triageEntryAI')));
      await frames(tester);
      expect(find.text('upload:'), findsOneWidget);
    });
  });
}

class _LoggedIn extends AuthController {
  @override
  AuthState build() => const AuthState(status: AuthStatus.authenticated, profile: UserProfile());
}

/// 登录态 TriagePage 的安静替身：无进行中会话 / 无补弹 / 空历史 / 无兽医在线。
class _QuietConsultRepo extends ConsultRepository {
  _QuietConsultRepo() : super(dio: Dio());

  @override
  Future<ConsultSession?> active() async => null;

  @override
  Future<ConsultSession?> pendingRating() async => null;

  @override
  Future<ConsultHistoryPage> history({String? cursor, int limit = 20}) async =>
      const ConsultHistoryPage(items: <ConsultHistoryItem>[], hasMore: false);

  @override
  Future<ConsultAvailability> availability() async => const ConsultAvailability(vetOnline: false);
}
