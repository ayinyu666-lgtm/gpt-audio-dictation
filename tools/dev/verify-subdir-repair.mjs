/*
 * 验证 panel.js 的 repairSettings 能修掉历史脏值。
 * 从真实 panel.js 里抠出 sanitizeSubDir / repairSettings / DEFAULTS 三段跑一遍。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, '..', '..', 'extension', 'panel.js'), 'utf8');

// 抠出需要的片段
function slice(startMarker, endMarker) {
  const a = src.indexOf(startMarker);
  if (a < 0) throw new Error('找不到起点：' + startMarker);
  const b = src.indexOf(endMarker, a);
  if (b < 0) throw new Error('找不到终点：' + endMarker);
  return src.slice(a, b + endMarker.length);
}

const defaultsBlock = slice('const DEFAULTS = {', '};');
const sanitizeBlock = slice('const sanitizeSubDir =', '.trim();');
const repairBlock = slice('const SAFE_SUBDIR =', '\n  }');

const code = [
  defaultsBlock,
  sanitizeBlock,
  repairBlock,
  'return { DEFAULTS, sanitizeSubDir, repairSettings };'
].join('\n');

// eslint-disable-next-line no-new-func
const factory = new Function(code);
const { DEFAULTS, repairSettings } = factory();

const cases = [
  {
    name: '被提示词污染的脏值（本次事故现场）',
    input: { saveSubDir: '原始录音回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。', newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: '原始录音'
  },
  {
    name: '正常值不该被动',
    input: { saveSubDir: '原始录音', newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: '原始录音'
  },
  {
    name: '含路径分隔符 -> 折成短横',
    input: { saveSubDir: 'a/b\\c', newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: 'a-b-c'
  },
  {
    name: '空串保持空（用户想直接放课程目录）',
    input: { saveSubDir: '', newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: '原始录音'
  },
  {
    name: '超大目录名回落默认',
    input: { saveSubDir: 'x'.repeat(60), newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: '原始录音'
  },
  {
    name: '用户自定义的短名字保留',
    input: { saveSubDir: '课堂', newSessionPrompt: DEFAULTS.newSessionPrompt },
    want: '课堂'
  }
];

let pass = 0;
for (const c of cases) {
  const got = repairSettings(c.input).saveSubDir;
  const ok = got === c.want;
  if (ok) pass += 1;
  console.log((ok ? 'PASS  ' : 'FAIL  ') + c.name + '  -> ' + JSON.stringify(got) + (ok ? '' : '（期望 ' + JSON.stringify(c.want) + '）'));
}
console.log('合计 ' + cases.length + ' 项，通过 ' + pass + ' 项');
process.exit(pass === cases.length ? 0 : 1);
