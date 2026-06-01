import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:petgo/app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // V1：锁定竖屏（portrait-only）。
  SystemChrome.setPreferredOrientations(const [
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const ProviderScope(child: PetGoApp()));
}
