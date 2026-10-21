/*
 * v6：在导航前用 addScriptToEvaluateOnNewDocument 注入 getUserMedia 补丁（等于扩展的 document_start 时机），
 * 然后开新会话点听写，看 ChatGPT 是否仍走 navigator.mediaDevices.getUserMedia，
 * 以及是否新建 AudioContext / createMediaStreamSource / createAnalyser（波形那套是否还在）。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const i = args.indexOf('--port');
const port = i >= 0 ? args[i + 1] : '9224';

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
  window.__ac = [];
  const patch = () => {
    const md = navigator.mediaDevices;
    if (md && typeof md.getUserMedia === 'function' && !md.__patched) {
      const orig = md.getUserMedia.bind(md);
      md.getUserMedia = function (c) {
        const rec = { t: Date.now(), audio: !!(c && c.audio), keys: c ? Object.keys(c) : null };
        window.__gum.push(rec);
        return orig(c).then((s) => {
          rec.ok = true;
          rec.tracks = s.getAudioTracks().map((x) => (x.label || '(无标签)') + '/' + x.readyState);
          rec.id = s.id;
          return s;
        }, (e) => { rec.ok = false; rec.err = String(e && e.name); throw e; });
      };
      md.__patched = true;
    }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (AC && !AC.__patched) {
      const oc = AC.prototype.createMediaStreamSource;
      AC.prototype.createMediaStreamSource = function (s) { window.__ac.push('createMediaStreamSource'); return oc.call(this, s); };
      const oa = AC.prototype.createAnalyser;
      AC.prototype.createAnalyser = function () { window.__ac.push('createAnalyser'); return oa.call(this); };
      AC.__patched = true;
    }
  };
  patch();
  // 有些页面会替换 mediaDevices 对象，用 defineProperty 兜一次
  try {
    let cur = navigator.mediaDevices;
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      get() { if (cur && !cur.__patched) patch(); return cur; },
      set(v) { cur = v; if (cur && !cur.__patched) patch(); }
    });
  } catch (e) { /* ignore */ }
  return true;
})()`;

await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PATCH });
await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(5000);
console.log('注入生效: ' + JSON.stringify(await ev('({ has: !!window.__gum, patched: !!(navigator.mediaDevices && navigator.mediaDevices.__patched) })')));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await wait(4000);
console.log('getUserMedia: ' + JSON.stringify(await ev('window.__gum || []'), null, 1));
console.log('AudioContext: ' + JSON.stringify(await ev('window.__ac || []')));
console.log('pm: ' + JSON.stringify(await ev(`(() => { const p=document.querySelector('div.ProseMirror[contenteditable="true"]'); return p?(p.innerText||'').trim().slice(0,150):''; })()`)));

console.log('\n>>> 点「发送」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="发送"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await wait(3000);
console.log('发送后 getUserMedia: ' + JSON.stringify(await ev('window.__gum || []')));
console.log('href: ' + JSON.stringify(await ev('location.href')));
console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
process.exit(0);
