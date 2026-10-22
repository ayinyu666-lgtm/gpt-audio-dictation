/*
 * 隔离验证：落盘开关与子文件夹清洗。
 *
 * 复现今天的事故：
 *   1. saveSubDir 被提示词全文污染（含 '/'）→ writer 每段都判 invalid_subdir
 *   2. saveEnabled 被存成 false 且带 savePreferenceSet=true → 永久停在"落盘未开"
 *
 * 跑法：node tools/dev/verify-save-settings.mjs
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const panelSrc = readFileSync(join(here, '..', '..', 'extension', 'panel.js'), 'utf8');
const hookSrc = readFileSync(join(here, '..', '..', 'extension', 'page-hook.js'), 'utf8');

function slice(src, startMarker, endMarker) {
  const a = src.indexOf(startMarker);
  if (a < 0) throw new Error('找不到起点：' + startMarker);
  const b = src.indexOf(endMarker, a);
  if (b < 0) throw new Error('找不到终点：' + endMarker);
  return src.slice(a, b + endMarker.length);
}

// —— 1. 面板侧：DEFAULTS + sanitizeSubDir + repairSettings ——
const panelCode = [
  slice(panelSrc, 'const DEFAULTS = {', '};'),
  slice(panelSrc, 'const sanitizeSubDir =', '.trim();'),
  slice(panelSrc, 'const SAFE_SUBDIR =', '\n  }'),
  'return { DEFAULTS, sanitizeSubDir, repairSettings };'
].join('\n');
const { DEFAULTS, sanitizeSubDir, repairSettings } = new Function(panelCode)();

// —— 2. 页面侧：cleanSubDir ——
const hookCode = [
  slice(hookSrc, 'function cleanSubDir(v) {', '\n  }'),
  'return { cleanSubDir };'
].join('\n');
const { cleanSubDir } = new Function(hookCode)();

const PROMPT = DEFAULTS.newSessionPrompt;
const POLLUTED = '原始录音回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。';

const checks = [
  // 面板装载：脏值必须回默认
  ['面板 装载脏 subDir → 回默认', repairSettings({ saveSubDir: POLLUTED, newSessionPrompt: PROMPT }).saveSubDir === '原始录音'],
  ['面板 装载正常 subDir 不动', repairSettings({ saveSubDir: '原始录音', newSessionPrompt: PROMPT }).saveSubDir === '原始录音'],
  ['面板 自定义短名保留', repairSettings({ saveSubDir: '课堂', newSessionPrompt: PROMPT }).saveSubDir === '课堂'],
  ['面板 空值回默认', repairSettings({ saveSubDir: '', newSessionPrompt: PROMPT }).saveSubDir === '原始录音'],
  // 清洗函数本身
  ['清洗 去掉路径分隔符', !sanitizeSubDir('a/b\\c').includes('/') && !sanitizeSubDir('a/b\\c').includes('\\')],
  ['清洗 保留正常中文', sanitizeSubDir('原始录音') === '原始录音'],
  // 页面侧：发给 writer 的 subDir 永不含分隔符
  ['页面 脏值发出去前被折掉分隔符', !/[/\\]/.test(cleanSubDir(POLLUTED))],
  ['页面 正常值原样发送', cleanSubDir('原始录音') === '原始录音'],
  ['页面 空值保持空（= 不要子文件夹）', cleanSubDir('') === ''],
  ['面板 落盘未开可一键开启', panelSrc.includes('已通过小条打开落盘')],
  // 页面侧迁移规则不能被坏值绕过
  ['页面 缺偏好标记的 false 会被纠正', hookSrc.includes('patch.savePreferenceSet !== true && patch.saveEnabled === false')]
];

let pass = 0;
for (const [name, ok] of checks) {
  if (ok) pass += 1;
  console.log((ok ? 'PASS  ' : 'FAIL  ') + name);
}
console.log(`合计 ${checks.length} 项，通过 ${pass} 项`);
process.exit(pass === checks.length ? 0 : 1);
