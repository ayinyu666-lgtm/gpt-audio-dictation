/*
 * 只读探测：当前 ChatGPT 页面里与「听写 / 录音 / 发送」相关的 DOM 实况。
 * 用法：node tools/dev/probe-dictation.mjs [--port 9224] [--out file.json]
 * 不点击、不改页面。
 */
import { writeFileSync } from 'node:fs';
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const port = get('port', '9224');
const out = get('out', '');

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
if (!page) { console.error('没找到 ChatGPT 页面'); process.exit(1); }
console.log('页面:', page.title, page.url);

const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Runtime.enable');
async function ev(expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}

const res = await ev(`(() => {
  const q = (sel) => Array.from(document.querySelectorAll(sel));
  const info = (el) => ({
    tag: el.tagName,
    aria: el.getAttribute('aria-label') || '',
    testid: el.getAttribute('data-testid') || '',
    text: (el.innerText || el.textContent || '').trim().slice(0, 60),
    cls: (typeof el.className === 'string' ? el.className : '').split(' ').slice(0, 6).join(' '),
    disabled: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
  });

  // 1) 所有带 aria-label 的按钮（找听写/转录/发送相关）
  const buttons = q('button,[role=button]').map(info);
  const kw = ['听写','转录','发送','停止','取消','录音','语音','麦克风','dictat','transcri','send','stop','cancel','record','voice','mic','audio'];
  const relevant = buttons.filter((b) => {
    const s = (b.aria + ' ' + b.text + ' ' + b.testid + ' ' + b.cls).toLowerCase();
    return kw.some((k) => s.includes(k));
  });

  // 2) composer 结构
  const composer = document.querySelector('div.ProseMirror[contenteditable="true"]')
    || document.querySelector('[contenteditable="true"]');
  let composerInfo = null;
  if (composer) {
    const parent = composer.closest('form') || composer.parentElement;
    composerInfo = {
      tag: composer.tagName,
      id: composer.id || '',
      cls: (typeof composer.className === 'string' ? composer.className : '').slice(0, 80),
      parentTag: parent ? parent.tagName : '',
      parentCls: parent && typeof parent.className === 'string' ? parent.className.slice(0, 80) : '',
      siblingButtons: parent ? Array.from(parent.querySelectorAll('button,[role=button]')).map(info) : [],
    };
  }

  // 3) 页脚 / 底部区域里所有按钮（听写控件常驻这里）
  const footers = q('footer, [class*="footer"], [class*="composer"] , [class*="bottom"]');
  const footerButtons = [];
  const seen = new Set();
  for (const f of footers) {
    for (const b of f.querySelectorAll('button,[role=button]')) {
      if (seen.has(b)) continue; seen.add(b);
      footerButtons.push(info(b));
    }
  }

  // 4) 所有 data-testid
  const testids = Array.from(new Set(q('[data-testid]').map((e) => e.getAttribute('data-testid'))));

  // 5) 是否有 canvas / 波形
  const canvases = q('canvas').map((c) => ({ w: c.width, h: c.height, cls: (typeof c.className === 'string' ? c.className : '').slice(0, 60) }));

  return {
    url: location.href,
    relevantButtons: relevant,
    composerInfo,
    footerButtons: footerButtons.slice(0, 60),
    testids,
    canvases,
    buttonTotal: buttons.length,
  };
})()`);

const json = JSON.stringify(res, null, 2);
if (out) { writeFileSync(out, json, 'utf8'); console.log('已写出', out); }
console.log('');
console.log('按钮总数:', res.buttonTotal);
console.log('');
console.log('=== 听写/录音相关按钮 ===');
res.relevantButtons.forEach((b) => console.log(' ', JSON.stringify(b)));
console.log('');
console.log('=== composer 同级按钮 ===');
(res.composerInfo?.siblingButtons || []).forEach((b) => console.log(' ', JSON.stringify(b)));
console.log('');
console.log('=== 全部 data-testid ===');
console.log(res.testids.join(', '));
console.log('');
console.log('=== canvas ===', JSON.stringify(res.canvases));
process.exit(0);
