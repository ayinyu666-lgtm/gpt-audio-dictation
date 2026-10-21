/*
 * 在同一个已登录页面里开一个新会话（导航到根路径），然后探测听写按钮链。
 * 用法：node tools/dev/probe-newchat.mjs [--port 9224]
 * 注意：会把该页面导航到 / 新会话，会话内容在云端不会丢。
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

console.log('原页面:', page.title, page.url);
await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await new Promise((r) => setTimeout(r, 5000));

const SNAP = `(() => {
  const inf = (el) => ({
    a: el.getAttribute('aria-label') || '',
    t: (el.innerText || el.textContent || '').trim().slice(0, 40),
    testid: el.getAttribute('data-testid') || '',
    d: !!el.disabled || el.getAttribute('aria-disabled') === 'true',
    cls: (typeof el.className === 'string' ? el.className : '').split(' ').slice(0,4).join(' '),
  });
  const pm = document.querySelector('div.ProseMirror[contenteditable="true"]');
  let area = pm;
  for (let i = 0; i < 6 && area; i++) {
    if (area.querySelectorAll && area.querySelectorAll('button').length >= 2) break;
    area = area.parentElement;
  }
  const compBtns = area ? Array.from(area.querySelectorAll('button,[role=button]')).map(inf) : [];
  const kw = ['听写','转录','发送','停止','取消','录音','语音','麦克风','dictat','transcri','send','stop','cancel','record','voice','mic','audio'];
  const seen = new Set(); const rel = [];
  for (const b of document.querySelectorAll('button,[role=button]')) {
    const i = inf(b);
    if (!kw.some((k) => (i.a + ' ' + i.t + ' ' + i.testid).toLowerCase().includes(k))) continue;
    const key = i.a + '|' + i.t + '|' + i.testid;
    if (seen.has(key)) continue; seen.add(key); rel.push(i);
  }
  return { url: location.href, title: document.title, compBtns, rel, canvas: document.querySelectorAll('canvas').length };
})()`;

const s0 = await ev(SNAP);
console.log('\n新会话:', s0.title, s0.url);
console.log('composer 区按钮:');
s0.compBtns.forEach((b) => console.log('  ', JSON.stringify(b)));
console.log('全局相关按钮:');
s0.rel.forEach((b) => console.log('  ', JSON.stringify(b)));

if (!s0.rel.some((b) => b.a === '听写')) {
  console.log('\n!! 新会话里没有 aria-label=听写 的按钮');
  process.exit(0);
}

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b = document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await new Promise((r) => setTimeout(r, 1500));
const s1 = await ev(SNAP);
console.log('录音中 composer 区按钮:');
s1.compBtns.forEach((b) => console.log('  ', JSON.stringify(b)));
console.log('录音中全局相关按钮:');
s1.rel.forEach((b) => console.log('  ', JSON.stringify(b)));
console.log('canvas=', s1.canvas);

console.log('\n>>> 点「停止听写」');
console.log(JSON.stringify(await ev(`(() => { const b = document.querySelector('button[aria-label="停止听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await new Promise((r) => setTimeout(r, 1200));
const s2 = await ev(SNAP);
console.log('停止后 composer 区按钮:');
s2.compBtns.forEach((b) => console.log('  ', JSON.stringify(b)));

console.log('\n>>> 点「转录并发送」');
console.log(JSON.stringify(await ev(`(() => { const b = document.querySelector('button[aria-label="转录并发送"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await new Promise((r) => setTimeout(r, 3000));
const s3 = await ev(SNAP);
console.log('发送后 composer 区按钮:');
s3.compBtns.forEach((b) => console.log('  ', JSON.stringify(b)));
console.log('pm 文本:', JSON.stringify(await ev(`(() => { const p=document.querySelector('div.ProseMirror[contenteditable="true"]'); return p? (p.innerText||'').trim().slice(0,80):''; })()`)));

console.log('\n>>> 收尾（取消残留）');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
process.exit(0);
