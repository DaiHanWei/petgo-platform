import 'ts_text.dart';

/// 弹窗文案（V1.3.2 Story 2.2 · 内容设计 §2.3 / §2.6），EN / ID 逐字照搬。
typedef TsDialogCopy = ({TsText title, TsText body, TsText confirm, TsText cancel});

/// 挽留弹窗（§2.3）：结果页锁态区点返回、结果未解锁时弹一次。`confirm` = 去解锁，`cancel` = 再看看。
const TsDialogCopy kTsRetentionDialog = (
  title: (en: "Wait a sec.", id: "Yakin keluar?"),
  body: (
    en: "Unlock it and {pet}'s personality badge goes on its profile — visible to everyone who visits. Full reading included.",
    id: "Kalau di-unlock, badge kepribadian {pet} bisa dipasang di profilnya — kelihatan sama semua yang mampir. Analisis lengkapnya juga kebuka.",
  ),
  // 「Unlock」两语同词（内容设计原文如此），机检 en != id 对这一条豁免。
  confirm: (en: "Unlock", id: "Unlock"),
  cancel: (en: "Not now", id: "Nanti aja"),
);

/// 重测确认（§2.6 完整版正文；标题 / 按钮按 Story 2.2 翻译清单 #18）。
const TsDialogCopy kTsRetakeDialog = (
  title: (en: "Retake the test?", id: "Tes ulang?"),
  body: (
    en: "Retaking creates a new result, and **the new one needs to be unlocked again**. What you've already unlocked stays — you can always go back to it.",
    id: "Tes ulang bikin hasil baru, dan **hasil barunya perlu di-unlock lagi**. Yang udah kamu unlock tetap tersimpan, bisa dibuka kapan aja.",
  ),
  confirm: (en: "Retake", id: "Tes Ulang"),
  cancel: (en: "Cancel", id: "Batal"),
);
