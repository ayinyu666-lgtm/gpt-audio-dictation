#!/usr/bin/env node
/*
 * writer 落盘路径自测（临时实例，不碰真实配置）。
 *
 * 为什么这么做：resolveTarget 的目录解析涉及"课程名→目录名映射""子文件夹覆盖"
 * 和"路径安全"，这几条一错就会把转录写到意想不到的地方，所以用一个临时根目录
 * 加临时配置起一个独立实例，真发 HTTP 请求、真看落盘结果。
 *
 * 用法：node tools/dev/verify-writer.mjs
 * 退出码：全部用例通过 0，有失败 1。跑完自动清理临时目录。
 */
import http from 'node:http';
import net from 'node:net';
import path from 'node:path';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import {
  mkdtempSync, mkdirSync, writeFileSync, copyFileSync, readFileSync,
  existsSync, readdirSync, statSync, rmSync
} from 'node:fs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC_WRITER = path.resolve(HERE, '..', '..', 'writer', 'writer.mjs');
const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
const pad = (n) => String(n).padStart(2, '0');

/* ── 与 writer.mjs 对齐的命名逻辑（只用于算期望值）── */
function legacySafeName(s) {
  return String(s || '').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().slice(0, 80);
}
function safeName(s) {
  let out = legacySafeName(s).replace(/[\x00-\x1f\x7f]/g, '_').replace(/[. ]+$/g, '').trim();
  if (!out || out === '.' || out === '..') return '_';
  if (/^(?:con|prn|aux|nul|clock\$|com[1-9]|lpt[1-9])(?:\..*)?$/i.test(out)) out = '_' + out;
  return out;
}
function expectedFileName(when, course, pattern = '{date} {weekday} {time}') {
  const map = {
    '{date}': `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}`,
    '{time}': `${pad(when.getHours())}-${pad(when.getMinutes())}`,
    '{weekday}': WEEKDAYS[when.getDay()],
    '{course}': safeName(course),
    '{year}': String(when.getFullYear()),
    '{month}': pad(when.getMonth() + 1),
    '{day}': pad(when.getDate())
  };
  let out = String(pattern);
  for (const [k, v] of Object.entries(map)) out = out.split(k).join(v);
  return safeName(out) || map['{date}'];
}

/* ── 极简 HTTP 客户端（零依赖，不依赖 Node 18 的 fetch）── */
function request(port, method, pathname, body) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), 'utf8');
    const req = http.request({
      host: '127.0.0.1',
      port,
      method,
      path: pathname,
      headers: payload ? { 'content-type': 'application/json', 'content-length': payload.length } : {}
    }, (res) => {
      let d = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { d += c; });
      res.on('end', () => {
        let parsed = null;
        try { parsed = JSON.parse(d); } catch { parsed = null; }
        resolve({ status: res.statusCode, body: parsed, raw: d });
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

function portFree(port) {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.once('error', () => resolve(false));
    s.once('listening', () => s.close(() => resolve(true)));
    s.listen(port, '127.0.0.1');
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForPing(port, timeoutMs = 10000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    try {
      const r = await request(port, 'GET', '/ping');
      if (r.status === 200 && r.body && r.body.ok) return r.body;
    } catch { /* 还没起来 */ }
    await sleep(120);
  }
  return null;
}

function tree(root, prefix = '', depth = 0) {
  const lines = [];
  let entries = [];
  try { entries = readdirSync(root, { withFileTypes: true }); } catch { return lines; }
  entries.sort((a, b) => (a.isDirectory() === b.isDirectory() ? a.name.localeCompare(b.name) : (a.isDirectory() ? -1 : 1)));
  entries.forEach((entry, i) => {
    const last = i === entries.length - 1;
    lines.push(prefix + (last ? '└─ ' : '├─ ') + entry.name + (entry.isDirectory() ? '/' : ''));
    if (entry.isDirectory() && depth < 4) {
      lines.push(...tree(path.join(root, entry.name), prefix + (last ? '   ' : '│  '), depth + 1));
    }
  });
  return lines;
}

const results = [];
function assert(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
}

async function main() {
  if (!existsSync(SRC_WRITER)) throw new Error('找不到 writer.mjs：' + SRC_WRITER);

  // 临时目录里放一份 writer.mjs 副本 + 独立 config.json，这样连配置都不会碰真实的
  const tmpBase = mkdtempSync(path.join(os.tmpdir(), 'gad-verify-'));
  const appDir = path.join(tmpBase, 'app');
  const saveRoot = path.join(tmpBase, '冬季学期');
  mkdirSync(appDir, { recursive: true });
  mkdirSync(saveRoot, { recursive: true });
  copyFileSync(SRC_WRITER, path.join(appDir, 'writer.mjs'));

  let port = 8899;
  while (!(await portFree(port))) port += 1;

  const courseDirs = {
    '回收-舒伯特': '回收技术-舒伯特',
    '系统技术-马丁': '系统控制',
    '系统技术-大作业': '系统大作业',
    '回收-希望': '回收技术-希望'
  };
  const tempConfig = {
    port,
    rootDir: saveRoot,
    course: '',
    subDir: '原始录音',
    courseDirs,
    courses: ['回收-舒伯特', 'AVEVA-舒伯特', '系统技术-马丁', '系统技术-大作业', '回收-希望', '绿色-Nitsche', '系统控制-Liebscher'],
    fileNamePattern: '{date} {weekday} {time}',
    includeGerman: true,
    includeChinese: true,
    writeSessionHeader: true
  };
  writeFileSync(path.join(appDir, 'config.json'), JSON.stringify(tempConfig, null, 2) + '\n', 'utf8');

  console.log(`临时根目录 ${saveRoot}`);
  console.log(`临时实例端口 ${port}\n`);

  const child = spawn(process.execPath, [path.join(appDir, 'writer.mjs')], {
    cwd: appDir,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let childLog = '';
  child.stdout.on('data', (c) => { childLog += c; });
  child.stderr.on('data', (c) => { childLog += c; });

  try {
    const ping = await waitForPing(port);
    if (!ping) throw new Error('临时 writer 没起来。日志：\n' + childLog);
    assert('实例启动 /ping 返回 rootDir = 临时根目录', path.resolve(ping.rootDir) === path.resolve(saveRoot), ping.rootDir);
    assert('/ping 带 courseDirs', ping.courseDirs && ping.courseDirs['回收-舒伯特'] === '回收技术-舒伯特', JSON.stringify(ping.courseDirs));

    const base = Date.now();
    let seq = 0;
    const stamp = () => new Date(base + (seq++) * 60000);

    async function save(course, extra, when) {
      const at = when || stamp();
      const body = {
        course,
        user: '原文内容 ' + course,
        assistant: '处理结果 ' + course,
        saveId: 'verify-' + seq + '-' + Math.random().toString(36).slice(2, 8),
        sessionStart: at.toISOString(),
        at: at.toISOString(),
        url: 'https://chatgpt.com/c/verify',
        ...extra
      };
      const r = await request(port, 'POST', '/save', body);
      return { r, at };
    }

    const rel = (...parts) => path.join(saveRoot, ...parts);

    /* 用例 1：有映射 → 落到映射后的目录 */
    {
      const when = stamp();
      const { r } = await save('回收-舒伯特', {}, when);
      const want = rel('回收技术-舒伯特', '原始录音', expectedFileName(when, '回收-舒伯特') + '.md');
      assert('1 课程名有映射 → 落到映射目录（并沿用 config 子文件夹）',
        r.status === 200 && r.body && r.body.file && path.resolve(r.body.file) === path.resolve(want) && existsSync(want),
        r.body && r.body.file);
      const head = existsSync(want) ? readFileSync(want, 'utf8') : '';
      const headLines = head.split(/\r?\n/).map((l) => l.trim());
      assert('1b 文件头仍显示原始课程名', headLines.includes('# 回收-舒伯特'),
        headLines.find((l) => l.startsWith('# ')) || '(没有 # 标题行)');
    }

    /* 用例 2：无映射 → 落到同名目录 */
    {
      const when = stamp();
      const { r } = await save('绿色-Nitsche', {}, when);
      const want = rel('绿色-Nitsche', '原始录音', expectedFileName(when, '绿色-Nitsche') + '.md');
      assert('2 课程名无映射 → 落到同名目录',
        r.status === 200 && path.resolve(r.body.file) === path.resolve(want) && existsSync(want),
        r.body && r.body.file);
    }

    /* 用例 3：body 传 subDir → 用 body 的值（映射课程 + 自定义子目录） */
    {
      const when = stamp();
      const { r } = await save('回收-舒伯特', { subDir: '测试子目录' }, when);
      const want = rel('回收技术-舒伯特', '测试子目录', expectedFileName(when, '回收-舒伯特') + '.md');
      assert('3 body 传 subDir → 用 body 的值',
        r.status === 200 && path.resolve(r.body.file) === path.resolve(want) && existsSync(want),
        r.body && r.body.file);
    }

    /* 用例 4：body 不传 subDir → 用 config 的 subDir */
    {
      const when = stamp();
      const { r } = await save('AVEVA-舒伯特', {}, when);
      const want = rel('AVEVA-舒伯特', '原始录音', expectedFileName(when, 'AVEVA-舒伯特') + '.md');
      assert('4 body 不传 subDir → 用 config 的 原始录音',
        r.status === 200 && path.resolve(r.body.file) === path.resolve(want) && existsSync(want),
        r.body && r.body.file);
    }

    /* 用例 5：body 传空字符串 → 直接放课程目录下 */
    {
      const when = stamp();
      const { r } = await save('AVEVA-舒伯特', { subDir: '' }, when);
      const want = rel('AVEVA-舒伯特', expectedFileName(when, 'AVEVA-舒伯特') + '.md');
      assert('5 body 传空字符串 → 直接放课程目录下',
        r.status === 200 && path.resolve(r.body.file) === path.resolve(want) && existsSync(want),
        r.body && r.body.file);
    }

    /* 用例 6：恶意/非法子文件夹 → 4xx 且不写文件 */
    {
      const before = tree(saveRoot).length;
      const bads = ['../恶意', '..\\恶意', 'a/b', '..', '.', 'C:\\绝对路径'];
      let allRejected = true;
      const detail = [];
      for (const bad of bads) {
        const { r } = await save('绿色-Nitsche', { subDir: bad }, stamp());
        const rejected = r.status >= 400 && r.status < 500;
        if (!rejected) allRejected = false;
        detail.push(`${JSON.stringify(bad)}→${r.status}`);
      }
      const escaped = existsSync(rel('恶意')) || existsSync(path.join(tmpBase, '恶意'));
      assert('6 非法 subDir 全部被 4xx 拒绝且未写出文件',
        allRejected && !escaped && tree(saveRoot).length === before,
        detail.join(' '));
    }

    /* 用例 7：目录不存在 → 递归自动创建（映射到尚不存在的 系统控制 / 系统大作业） */
    {
      const when = stamp();
      const { r } = await save('系统技术-马丁', {}, when);
      const deep = rel('系统控制', '原始录音');
      const want = path.join(deep, expectedFileName(when, '系统技术-马丁') + '.md');
      const ok = r.status === 200 && existsSync(want) && statSync(deep).isDirectory();
      assert('7 目标目录不存在 → 递归创建后写入', ok, r.body && r.body.file);

      const when2 = stamp();
      const { r: r2 } = await save('系统技术-大作业', { subDir: '二级/../x' }, when2);
      assert('7b 二级路径片段同样被拒绝', r2.status === 400, `status ${r2.status}`);
    }

    console.log('\n生成目录树：');
    console.log(path.basename(saveRoot) + '/');
    console.log(tree(saveRoot).join('\n'));
  } finally {
    try { child.kill(); } catch { /* ignore */ }
    await sleep(300);
    try { rmSync(tmpBase, { recursive: true, force: true }); } catch (e) {
      console.log('清理临时目录失败（可手动删）：' + tmpBase + ' — ' + e.message);
    }
  }

  const failed = results.filter((r) => !r.ok);
  console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length} 项，失败 ${failed.length} 项`);
  if (failed.length) {
    for (const f of failed) console.log('  FAIL ' + f.name);
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error('自测脚本异常：' + (e && e.stack || e));
  process.exitCode = 1;
});
