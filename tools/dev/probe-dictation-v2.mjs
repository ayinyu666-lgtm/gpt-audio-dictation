/*
 * 新会话听写按钮拓扑探测（v2）：录音期间每 500ms 轮询一次，
 * 抓 composer 按钮集、输入框文本长度、以及含「新聊天/上限/limit」的元素及其祖先文本链。
 * 该浏览器无麦克风，只能验证按钮拓扑，不能验证真实转写文本。
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

const SNAP = `(() => {
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  let area = pm;
  for (let i = 0; i < 6 && area; i++) {
    if (area.querySelectorAll && area.querySelectorAll('button').length >= 2) break;
    area = area.parentElement;
  }
  const btns = area ? Array.from(area.querySelectorAll('button,[role=button]'))
    .map((e) => (e.getAttribute('aria-label') || e.innerText || '').trim()).filter(Boolean) : [];
  return { btns, pmLen: pm ? (pm.innerText || '').trim().length : -1, href: location.href };
})()`;

const BANNER = `(() => {
  const kw = ['新聊天','上限','已达','limit','Limit','上下文','length','Length','过长','开一个新','weiter','Weiter'];
  const out = [];
  for (const el of document.querySelectorAll('body *')) {
    if (el.children.length > 6) continue;
    const t = (el.innerText || '').trim();
    if (!t || t.length > 300) continue;
    if (!kw.some((k) => t.includes(k))) continue;
    const label = el.getAttribute('aria-label') || '';
    if (!label && !/上限|已达|limit|Limit|上下文|过长/.test(t)) continue;
    const chain = [];
    let n = el;
    for (let i = 0; i < 4 && n; i++, n = n.parentElement) {
      const ct = (n.innerText || '').trim().slice(0, 120);
      if (ct) chain.push(ct);
    }
    out.push({ tag: el.tagName, role: el.getAttribute('role'), label, text: t.slice(0, 120), chain });
  }
  const seen = new Set();
  return out.filter((o) => { const k = o.label + '|' + o.text; if (seen.has(k)) return false; seen.add(k); return true; });
})()`;

console.log('原页面:', page.title, page.url);
await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
console.log('导航后:', JSON.stringify(await ev('({href: location.href, t: document.title})')));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));

for (let i = 1; i <= 12; i++) {
  await wait(500);
  const s = await ev(SNAP);
  console.log(`  t=${(i * 0.5).toFixed(1)}s  pmLen=${s.pmLen}  按钮=[${s.btns.join(' | ')}]`);
}

console.log('\n>>> 横幅候选：');
console.log(JSON.stringify(await ev(BANNER), null, 1));

console.log('\n>>> 点「停止听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="停止听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await wait(1500);
console.log('停止后:', JSON.stringify(await ev(SNAP)));
await wait(2000);
console.log('停止+3.5s:', JSON.stringify(await ev(SNAP)));

console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
process.exit(0);
