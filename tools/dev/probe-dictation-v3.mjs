/*
 * v3：验证新链「点听写 → 点发送」的真实行为。
 * 该浏览器无麦克风（pmLen 恒 0），只验证按钮状态机与是否产生请求，不验证转写文本。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const port = get('port', '9224');

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
if (!page) { console.error('没找到 ChatGPT 页面'); process.exit(1); }

const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
async function ev(expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

// 页面内 fetch 拦截，记录 conversation 请求
await ev(`(() => {
  window.__reqs = [];
  const f = window.fetch;
  window.fetch = function (...a) {
    try {
      const u = typeof a[0] === 'string' ? a[0] : (a[0] && a[0].url) || '';
      if (u.includes('/backend-api/conversation')) {
        window.__reqs.push({ m: (a[1] && a[1].method) || 'GET', u: u.slice(0, 110) });
      }
    } catch (e) { /* ignore */ }
    return f.apply(this, a);
  };
  return true;
})()`);

const SNAP = `(() => {
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  let area = pm;
  for (let i = 0; i < 6 && area; i++) {
    if (area.querySelectorAll && area.querySelectorAll('button').length >= 2) break;
    area = area.parentElement;
  }
  const btns = area ? Array.from(area.querySelectorAll('button,[role=button]')).map((e) => ({
    l: (e.getAttribute('aria-label') || e.innerText || '').trim(),
    d: !!e.disabled || e.getAttribute('aria-disabled') === 'true' || e.getAttribute('data-disabled') === 'true',
  })) : [];
  return { btns, pmLen: pm ? (pm.innerText || '').trim().length : -1, href: location.href, turns: document.querySelectorAll('[class*="MarkdownRoot"]').length };
})()`;
const fmt = (s) => 'href=' + s.href.replace('https://chatgpt.com', '') + ' pmLen=' + s.pmLen + ' turns=' + s.turns + ' 按钮=[' + s.btns.map((b) => b.l + (b.d ? '(禁)' : '')).join(' | ') + ']';

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
console.log('新会话: ' + fmt(await ev(SNAP)));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await wait(3000);
console.log('录音中: ' + fmt(await ev(SNAP)));

console.log('\n>>> 点「发送」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
for (const ms of [600, 1200, 2500, 4000]) {
  await wait(ms);
  console.log('  +' + ms + ': ' + fmt(await ev(SNAP)));
}

console.log('\n>>> conversation 请求: ' + JSON.stringify(await ev('window.__reqs || []')));
console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
console.log('最终: ' + fmt(await ev(SNAP)));
process.exit(0);
