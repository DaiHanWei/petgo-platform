import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tailtopia/features/profile/domain/pet_header_info.dart';
import 'package:tailtopia/features/profile/presentation/widgets/pet_info_card.dart';
import 'package:tailtopia/l10n/app_localizations.dart';
import 'package:tailtopia/shared/media/image_lightbox.dart';

/// L0：宠物 Diary 主页头像可点开大图（修复线，同用户主页 V1.4.2 做法）；无头像（emoji 占位）不可点。
void main() {
  Future<void> pump(WidgetTester tester, {String? avatarUrl}) async {
    await tester.pumpWidget(MaterialApp(
      locale: const Locale('id'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: PetInfoCard(profile: PetHeaderInfo(name: 'Mochi', petType: 'CAT', avatarUrl: avatarUrl)),
      ),
    ));
    await tester.pump();
  }

  testWidgets('有头像 → 头像可点，点开全屏灯箱', (tester) async {
    await pump(tester, avatarUrl: 'https://example.com/a.jpg');
    expect(find.byKey(const ValueKey('petAvatarTap')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('petAvatarTap')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(ImageLightbox), findsOneWidget);
  });

  testWidgets('无头像 → 不可点（emoji 占位不开灯箱）', (tester) async {
    await pump(tester);
    expect(find.byKey(const ValueKey('petAvatarTap')), findsNothing);
  });
}
