/*
 * v4：验证新流程下麦克风钩子是否还成立 —— 在导航后立刻 patch getUserMedia，
 * 记录调用次数/参数/AudioContext 状态，然后点听写看是否触发。
 */
import { CDP } from './cdp.mjs';

const args = process.argv.slice(2);
const get = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && args[i + 1] ? args[i + 1] : d; };
const port = get('port', '9224');

const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && (t.url || '').includes('chatgpt.com'));
const cdp = await CDP.connect(page.webSocketDebuggerUrl);
await cdp.send('Page.enable');
await cdp.send('Runtime.enable');
async function ev(expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result.value;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

await cdp.send('Page.navigate', { url: 'https://chatgpt.com/' });
await wait(4000);

// 导航后页面脚本环境是新的，此时再 patch
console.log('patch: ' + JSON.stringify(await ev(`(() => {
  window.__gum = [];
  const md = navigator.mediaDevices;
  if (!md || !md.getUserMedia) return { ok: false, why: 'no mediaDevices' };
  const orig = md.getUserMedia.bind(md);
  md.getUserMedia = function (c) {
    window.__gum.push({ t: Date.now(), keys: c ? Object.keys(c) : null, audio: !!(c && c.audio) });
    return orig(c).then((s) => {
      window.__gum[window.__gum.length - 1].tracks = s.getAudioTracks().map((x) => ({ label: x.label, state: x.readyState }));
      return s;
    }, (e) => { window.__gum[window.__gum.length - 1].err = String(e && e.name); throw e; });
  };
  window.__acPatch = new Set();
  const AC = window.AudioContext || window.webkitAudioContext;
  if (AC) {
    const oc = AC.prototype.createMediaStreamSource;
    AC.prototype.createMediaStreamSource = function (s) {
      window.__acPatch.add('createMediaStreamSource');
      return oc.call(this, s);
    };
    const oa = AC.prototype.createAnalyser;
    AC.prototype.createAnalyser = function () { window.__acPatch.add('createAnalyser'); return oa.call(this); };
  }
  return { ok: true };
})()`)));

console.log('\n>>> 点「听写」');
console.log(JSON.stringify(await ev(`(() => { const b=document.querySelector('button[aria-label="听写"]'); if(!b) return {ok:false}; b.click(); return {ok:true}; })()`)));
await wait(4000);
console.log('getUserMedia 调用: ' + JSON.stringify(await ev('window.__gum || []')));
console.log('AudioContext 用法: ' + JSON.stringify(await ev('Array.from(window.__acPatch||[])')));
console.log('pm 文本: ' + JSON.stringify(await ev(`(() => { const p=document.querySelector('div.ProseMirror[contenteditable="true"]'); return p?(p.innerText||'').trim().slice(0,120):''; })()`)));
console.log('波形容器: ' + JSON.stringify(await ev(`(() => {
  const c = Array.from(document.querySelectorAll('canvas')).map((x) => ({ w: x.width, h: x.height, cls: (typeof x.className==='string'?x.className:'').slice(0,40) }));
  return { canvas: c.length, list: c.slice(0, 5) };
})()`)));

console.log('\n>>> 收尾');
console.log(JSON.stringify(await ev(`(() => { for (const l of ['取消听写','停止听写','取消转录']) { const b=document.querySelector('button[aria-label="'+l+'"]'); if(b){b.click();return {clicked:l};} } return {none:true}; })()`)));
process.exit(0);
