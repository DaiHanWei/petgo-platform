// 按对话语言生成追加的系统提示（spec §6 S6、D3、D8）。
// 说明书（work/CLAUDE.md）不再规定回答语言，语言只由这里按 lang 指定。

export const LANG_DIRECTIVES = Object.freeze({
  zh: {
    language: '请全程使用简体中文回答，包括表头和统计口径。',
    scopeHeading: '统计口径',
  },
  en: {
    language: 'Answer entirely in English, including table headers and the scope section.',
    scopeHeading: 'Scope',
  },
  id: {
    language: 'Jawab seluruhnya dalam Bahasa Indonesia, termasuk judul tabel dan bagian cakupan data.',
    scopeHeading: 'Cakupan data',
  },
});

export function buildSystemPrompt(lang, exportDir) {
  if (!Object.hasOwn(LANG_DIRECTIVES, lang)) throw new Error('不支持的语言');
  const d = LANG_DIRECTIVES[lang];
  const exportRule = exportDir
    ? `If the user asks for an export or the detail rows exceed 50, write the file(s) only into ${exportDir} (CSV, UTF-8 with header row; or XLSX). Do not write files anywhere else, and mention the file name in the answer.`
    : 'Do not create any files.';
  return [
    d.language,
    exportRule,
    `Every answer MUST end with a section titled "${d.scopeHeading}" that states the data scope: time range and timezone, filters and excluded accounts, the tables or logs used, and the definition of each metric.`,
    'Follow the output format in CLAUDE.md: Markdown report, conclusion first, then tables, then the scope section. Do not output raw HTML or images.',
  ].join('\n');
}
