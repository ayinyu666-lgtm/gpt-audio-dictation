import { connectBrowser } from './browser-client.mjs';
import { exportConversation } from './local-export.mjs';

// Explicit selection avoids guessing a course from an automatically generated title.
const [id, course, outputDir] = process.argv.slice(2);
if (!/^[a-f0-9-]{36}$/i.test(id || '') || !course || !outputDir) {
  console.error('用法：node export-from-browser.mjs <会话ID> <课程> <绝对目标目录>');
  process.exit(1);
}
const browser = await connectBrowser({ port: 9224 });
try {
  const payload = await browser.get('/backend-api/conversation/' + id);
  console.log(JSON.stringify(exportConversation({ payload, course, outputDir }), null, 2));
} finally {
  browser.close();
}
