/*
 * v10：连续两轮完整循环，验证
 *  1) 每轮是否都新建 getUserMedia 流（决定麦克风钩子要不要重挂）
 *  2) 点发送后 composer 多久清空、URL 何时变、turn 何时出现（收口判据）
 *  3) 清空前再补点一次「发送」会不会造成重复发送
 */
import { CDP } from './cdp.mjs';

const port = '9224';
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
async function ev(e) {
  const r = await cdp.send('Runtime.evaluate', { expression: e, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const PATCH = `(() => {
  window.__gum = [];
  const patch = () => {
    const md = navigator.mediaDevices;
    if (md && typeof md.getUserMedia === 'function' && !md.__patched) {
      const orig = md.getUserMedia.bind(md);
      md.getUserMedia = function (c) {
        const rec = { t: Date.now(), audio: !!(c && c.audio) };
        window.__gum.push(rec);
        return orig(c).then((s) => { rec.ok = true; rec.id = s.id; rec.tracks = s.getTracks().map(x=>x.readyState).join(','); return s; },
          (e) => { rec.ok = false; rec.err = String(e && e.name); throw e; });
      };
      md.__patched = true;
    }
  };
  patch();
  try {
    let cur = navigator.mediaDevices;
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true,
      get() { if (cur && !cur.__patched) patch(); return cur; },
      set(v) { cur = v; if (cur && !cur.__patched) patch(); } });
  } catch (e) {}
  return true;
})()`;

await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PATCH });
await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);

const SNAP = `(() => {
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  return {
    href: location.href.replace('https://chatgpt.com', ''),
    pm: pm ? (pm.innerText || '').trim() : '',
    userTurns: document.querySelectorAll('[class~="group/user-message"]').length,
    ansTurns: document.querySelectorAll('[class*="MarkdownRoot"]').length,
    hasDictate: !!document.querySelector('button[aria-label="听写"]'),
    hasStopDictate: !!document.querySelector('button[aria-label="停止听写"]'),
    hasSend: !!document.querySelector('button[aria-label="发送"]'),
    hasFinishing: !!document.querySelector('button[aria-label="正在完成听写"]'),
    hasStop: !!document.querySelector('button[aria-label="停止"]'),
    gum: (window.__gum || []).length,
    gumIds: (window.__gum || []).map(g => g.id || g.err)
  };
})()`;
const fmt = (s) => `href=${s.href} user=${s.userTurns} ans=${s.ansTurns} pm=${JSON.stringify(s.pm.slice(0,25))} [听写${s.hasDictate?'✓':'×'} 停录${s.hasStopDictate?'✓':'×'} 发送${s.hasSend?'✓':'×'} 完成中${s.hasFinishing?'✓':'×'} 停止${s.hasStop?'✓':'×'}] gum=${s.gum} ids=${JSON.stringify(s.gumIds)}`;

async function cycle(n) {
  console.log(`\n===== 第 ${n} 轮`);
  console.log('  起: ' + fmt(await ev(SNAP)));
  await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(b) b.click(); return 1; })()`);
  await wait(6000);
  console.log('  录6s: ' + fmt(await ev(SNAP)));
  console.log('  >>> 点发送');
  await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(b) b.click(); return 1; })()`);
  for (let i = 1; i <= 12; i++) {
    await wait(500);
    console.log(`   +${(i * 0.5).toFixed(1)}s ` + fmt(await ev(SNAP)));
  }
}

await cycle(1);
console.log('\n--- 等 GPT 答完 ---');
await wait(12000);
await cycle(2);
process.exit(0);
