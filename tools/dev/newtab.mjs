/*
 * 开一个新标签页（测试用，走浏览器级 CDP）。
 * 用法： node newtab.mjs <port> <url>
 */
import { CDP } from './cdp.mjs';

const port = process.argv[2];
const url = process.argv[3];
const ver = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
const cdp = await CDP.connect(ver.webSocketDebuggerUrl);
try {
  const r = await cdp.send('Target.createTarget', { url });
  console.log('created', r.targetId);
} finally {
  cdp.close();
}
