/*
 * writer 端到端验证：用 Node 发请求（shell 会把中文编码搞坏），
 * 覆盖"正常 subDir"和"被污染 subDir"两种输入。
 *
 * 这个探针会真的让 writer 写文件，所以用独立的 __probe__ 课程目录，跑完自己删掉。
 */
import fs from 'node:fs';
import path from 'node:path';

const BASE = 'http://127.0.0.1:8799';
const PROBE_COURSE = '__probe__';

async function save(body) {
  const r = await fetch(`${BASE}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body)
  });
  return { status: r.status, json: await r.json().catch(() => null) };
}

async function writerRoot() {
  try {
    const r = await fetch(`${BASE}/ping`);
    const j = await r.json();
    return j.rootDir || '';
  } catch { return ''; }
}

const cases = [
  {
    name: '正常 subDir=原始录音',
    body: { course: PROBE_COURSE, subDir: '原始录音', user: 'probe-u', assistant: 'probe-a', saveId: 'probe-ok-' + Date.now() },
    wantOk: true
  },
  {
    name: '被提示词污染的 subDir（含 /，必须被拒绝）',
    body: { course: PROBE_COURSE, subDir: '原始录音回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。', user: 'probe-u', assistant: 'probe-a', saveId: 'probe-bad-' + Date.now() },
    wantOk: false
  }
];

let pass = 0;
for (const c of cases) {
  const r = await save(c.body);
  const ok = c.wantOk ? !!(r.json && r.json.ok) : !!(r.json && r.json.ok === false);
  if (ok) pass += 1;
  console.log((ok ? 'PASS  ' : 'FAIL  ') + c.name);
  console.log('       ' + JSON.stringify(r.json));
}

// 清理：只删我们自己建的 __probe__ 课程目录
const root = await writerRoot();
if (root) {
  const dir = path.join(root, PROBE_COURSE);
  if (fs.existsSync(dir)) {
    fs.rmSync(dir, { recursive: true, force: true });
    console.log('已清理探针目录：' + dir);
  }
}

console.log(`合计 ${cases.length} 项，通过 ${pass} 项`);
process.exit(pass === cases.length ? 0 : 1);
