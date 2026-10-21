/*
 * 真实代码验证：从 panel.js 里抠出 store 对象，用 mock 的 chrome.storage 跑一遍
 * 旧存档升级路径。验证"1.2.1 存下的 autoStart:true 不会让 1.3.x 自动开录"。
 *
 * 用法：node tools/dev/verify-autostart-migration.mjs
 * 结果：全部 PASS 退出码 0，否则 1。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, '..', '..', 'extension', 'panel.js'), 'utf8');

const start = src.indexOf('  const store = {');
if (start < 0) throw new Error('找不到 store 定义');
let depth = 0;
let end = -1;
for (let i = src.indexOf('{', start); i < src.length; i += 1) {
  if (src[i] === '{') depth += 1;
  else if (src[i] === '}') {
    depth -= 1;
    if (depth === 0) { end = i + 1; break; }
  }
}
const block = src.slice(start, end) + ';';
if (!block.includes('autoStartPreferenceSet')) throw new Error('store 里没有 autoStartPreferenceSet，改动没落到文件里');

const DEFAULTS = { autoStart: false, autoStartPreferenceSet: false, autoJumpNewSession: true, saveEnabled: true, savePreferenceSet: false, saveCourse: '' };

function makeStore(saved) {
  const chrome = { storage: { local: { get: async () => ({ gad: saved }), set: async () => {} } } };
  const localStorage = { getItem: () => JSON.stringify(saved), setItem: () => {} };
  const factory = new Function('DEFAULTS', 'ISOLATED', 'chrome', 'localStorage', block + ' return store;');
  return factory(DEFAULTS, true, chrome, localStorage);
}

const cases = [
  ['1.2.1 老存档（autoStart:true，无标记）', { autoStart: true, saveCourse: 'X' }, false],
  ['1.3.x 用户亲手开过（标记 true）', { autoStart: true, autoStartPreferenceSet: true }, true],
  ['1.3.x 用户亲手关过（标记 true + false）', { autoStart: false, autoStartPreferenceSet: true }, false],
  ['全新安装（空存档）', {}, false]
];

let failed = 0;
for (const [name, saved, expect] of cases) {
  const store = makeStore(saved);
  const got = await store.load();
  const ok = got.autoStart === expect;
  if (!ok) failed += 1;
  console.log((ok ? 'PASS' : 'FAIL') + '  ' + name + '  → autoStart=' + got.autoStart + '（期望 ' + expect + '）');
}
process.exit(failed ? 1 : 0);
