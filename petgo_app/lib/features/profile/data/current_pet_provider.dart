import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/storage/prefs.dart';
import 'pet_list_repository.dart';

/// 当前宠物（V1.4.0 multi-pet Story 1.5 · AD-14 / §5「App 状态单一来源」）。
///
/// 🔴 页面**只读** [effectivePetIdProvider]，不自存 petId；切换只经 [CurrentPetIdNotifier.select]。
/// 本 notifier 只记「用户上次选了谁」（本机偏好 `petgo.currentPetId`），
/// 它可能已失效（宠物被删、换了账号）—— 回落由 [effectivePetIdProvider] 统一做。
final NotifierProvider<CurrentPetIdNotifier, int?> currentPetIdProvider =
    NotifierProvider<CurrentPetIdNotifier, int?>(CurrentPetIdNotifier.new);

class CurrentPetIdNotifier extends Notifier<int?> {
  static const String prefKey = 'petgo.currentPetId';

  /// 记下这个宠物 id 是**哪个账号**选的（code review 2026-10-18 #1）：冷启动恢复会话也会走
  /// 「换账号」那条失效链，不能一律清掉；只有偏好里的主人与当前账号不同才清。
  static const String ownerPrefKey = 'petgo.currentPetOwner';

  /// 偏好里「未设」的取值。`AppPrefs` 没有删除键的方法（Story 1.5 不改该类），
  /// 宠物 id / 用户 id 恒为正数，故写 0 表示「未设」。
  static const int _unset = 0;

  /// 用户在本次进程里显式选过 / 清过 / 已按账号绑定 —— 之后到的偏好恢复结果不得覆盖它。
  bool _touched = false;

  /// 用户在本次进程里亲手 select / clear 的次数（[bindUser] 异步比对期间据此让路）。
  int _userActs = 0;

  /// 本进程已绑定的账号；null = 还没绑定过（冷启动）。
  int? _ownerId;

  @override
  int? build() {
    _restore();
    return null;
  }

  Future<void> _restore() async {
    try {
      final saved = (await AppPrefs.create()).getInt(prefKey, fallback: _unset);
      if (_touched) return;
      final id = saved > 0 ? saved : null;
      if (id != state) state = id;
    } catch (_) {
      // 偏好读不到：保持「未设」→ 回落默认宠物，不影响使用。
    }
  }

  /// 当前登录账号变了（`resetUserScopedCaches` 调；含冷启动恢复会话）。
  ///
  /// - 本进程已绑定过另一个账号（直接换号 / 登出后换人登录）→ **同步**清掉，不让上个账号的宠物 id 多活一帧；
  /// - 冷启动首次绑定 → 读偏好比对主人：同一账号 → 保留（记住上次查看，AC3.6）；不同 / 未知 → 清掉。
  Future<void> bindUser(int userId) async {
    if (_ownerId == userId) return;
    final known = _ownerId;
    _ownerId = userId;
    _touched = true;
    final actsBefore = _userActs;
    if (known != null) {
      if (state != null) state = null;
    }
    try {
      final prefs = await AppPrefs.create();
      if (_ownerId != userId) return; // 期间又换了号：以后来的为准
      final storedOwner = prefs.getInt(ownerPrefKey, fallback: _unset);
      final storedPet = prefs.getInt(prefKey, fallback: _unset);
      final sameOwner = known == null && storedOwner == userId && storedPet > 0;
      if (_userActs == actsBefore) {
        final next = sameOwner ? storedPet : null;
        if (state != next) state = next;
      }
      if (!sameOwner && _userActs == actsBefore) await prefs.setInt(prefKey, _unset);
      await prefs.setInt(ownerPrefKey, userId);
    } catch (_) {
      // 偏好读写失败：同步那一步已清过（换号场景）；冷启动保持未设 → 回落默认宠物。
    }
  }

  /// 切到 [petId]（「我的」页头像 / Diary 切换器 / 建档成功）。
  Future<void> select(int petId) async {
    _touched = true;
    _userActs++;
    if (state != petId) state = petId;
    try {
      final prefs = await AppPrefs.create();
      await prefs.setInt(prefKey, petId);
      final owner = _ownerId;
      if (owner != null) await prefs.setInt(ownerPrefKey, owner);
    } catch (_) {
      // 存不下只影响「下次进来记不记得」，本次照常切换。
    }
  }

  /// 清掉（删除当前宠物）→ [effectivePetIdProvider] 回落默认宠物。
  Future<void> clear() async {
    _touched = true;
    _userActs++;
    if (state != null) state = null;
    try {
      await (await AppPrefs.create()).setInt(prefKey, _unset);
    } catch (_) {
      // 清不掉的代价：下次冷启动读到一个已失效的 id → 照样回落默认宠物。
    }
  }
}

/// 回落规则（纯函数，[effectivePetIdProvider] 与需要同步判定的界面共用一份）：
/// [current] 在列表里 → 它；否则 → `isDefault` 的那只（没有则第一只）；列表为空 → null。
int? resolveEffectivePetId(List<PetListItem> pets, int? current) {
  if (pets.isEmpty) return null;
  if (current != null && pets.any((p) => p.id == current)) return current;
  for (final p in pets) {
    if (p.isDefault) return p.id;
  }
  return pets.first.id;
}

/// 实际生效的当前宠物 id（AD-14）。**页面一律只读它**。
final FutureProvider<int?> effectivePetIdProvider = FutureProvider<int?>((ref) async {
  final current = ref.watch(currentPetIdProvider);
  final pets = await ref.watch(petListProvider.future);
  return resolveEffectivePetId(pets, current);
});

/// 请求用的当前宠物 id：同 [effectivePetIdProvider]，但宠物列表取不到时给 null
/// （= 不带 `?petId=`，后端落默认宠物，与 `petProfileProvider` 的回退同口径）。
///
/// 给「带 petId 的请求」用（护照 / 登机牌 / Tailsonality / 身份证旧单卡 / 分享奖励上报等）——
/// 列表接口一时失败不该让这些请求整个失败。**判「有没有宠物」不要用它**（null 有两种含义）。
final FutureProvider<int?> requestPetIdProvider = FutureProvider<int?>((ref) async {
  try {
    return await ref.watch(effectivePetIdProvider.future);
  } catch (_) {
    // code review 2026-10-18 #5：列表取不到时，用户若明确选过某只，就带它（后端校验归属，
    // 不是本人的 → 404），**不**悄悄落到默认宠物上；没选过 → null（默认宠物，与旧版同口径）。
    return ref.read(currentPetIdProvider);
  }
});

/// 命令式调用点（点按钮 / 分享回调 / 提交）取请求用的当前宠物 id。
///
/// 当前宠物还没被任何界面建立过（[effectivePetIdProvider] 不存在）→ 直接 null（= 不带 `?petId=`，
/// 后端落默认宠物），**不为此额外发一次列表请求**；否则等 [requestPetIdProvider] 解析完。
Future<int?> readRequestPetId(WidgetRef ref) async {
  if (!ref.exists(effectivePetIdProvider)) return ref.read(currentPetIdProvider);
  return ref.read(requestPetIdProvider.future);
}

/// 同步窥视已解析的当前宠物 id（不触发请求、不等待）。
///
/// 已解析且不在重取中 → 它；否则（未建立 / 还在取 / 失败 / 换账号重取中）→ 用户选过的那只（可能 null =
/// 默认宠物）。给必须**同步**发出的 best-effort 上报用（如里程碑庆祝回报）。
int? peekEffectivePetId(Ref ref) {
  if (ref.exists(effectivePetIdProvider)) {
    final eff = ref.read(effectivePetIdProvider);
    if (!eff.isLoading && !eff.hasError && eff.hasValue) return eff.value;
  }
  return ref.read(currentPetIdProvider);
}

/// 界面用的当前宠物 id（code review 2026-10-18 #4）：**换账号重取中**（宠物列表因账号变化而 reload）
/// 视为「还没定」—— 不沿用上个账号的宠物 id 去发按宠物的请求、也不渲染上个账号的数据。
AsyncValue<int?> watchEffectivePetId(WidgetRef ref) {
  if (ref.watch(petListProvider.select((a) => a.isReloading))) return const AsyncLoading<int?>();
  return ref.watch(effectivePetIdProvider);
}

