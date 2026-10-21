/*
 * 本地落盘服务（接力用）。零依赖，只监听 127.0.0.1。
 *
 * 为什么需要它：浏览器扩展写不了任意本地路径，所以扩展只负责把文本 POST 过来，
 * 写文件这件事交给这个本地脚本。它不在运行时，扩展那侧也只是发不出去而已，
 * 录音/发送主链完全不受影响。
 *
 * 文件布局：
 *   <rootDir>\<分类>\<日期 星期 时间>.md
 * 文件名模板可配（config.json 的 fileNamePattern），时间取"这节课第一次发送的时刻"，
 * 由扩展在每次落盘时一起带过来（sessionStart），所以本地服务中途重启也不会换文件。
 *
 * 用法：双击 start-writer.bat，或者双击 install-autostart.bat 让它开机自己起。
 */
import http from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync, existsSync, readdirSync, statSync, accessSync, constants, renameSync, unlinkSync, truncateSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(HERE, 'config.json');
const LOG_DIR = path.join(HERE, 'logs');
const TELEMETRY_FILE = path.join(LOG_DIR, 'telemetry.jsonl');
const SERVICE_VERSION = '1.3.0';
const STARTED_AT = new Date().toISOString();

// 工具自身的运行状态（不含任何 ChatGPT 会话内容），用来排查"为什么不发送"
let telemetryLatest = null;
const telemetryRecent = [];
let telemetryWrites = 0;

function recordTelemetry(p) {
  // 扩展侧为了省流量，大多数时候只发状态不带日志；把上一次的日志补上，
  // 这样 GET /telemetry 的 latest 永远有日志可看
  if (p && (!Array.isArray(p.logs) || p.logs.length === 0) && telemetryLatest && Array.isArray(telemetryLatest.logs)) {
    p.logs = telemetryLatest.logs;
    p.logsCarriedForward = true;
  }
  telemetryLatest = p;
  telemetryRecent.push(p);
  if (telemetryRecent.length > 40) telemetryRecent.shift();
  try {
    mkdirSync(LOG_DIR, { recursive: true });
    appendFileSync(TELEMETRY_FILE, JSON.stringify(p) + '\n', 'utf8');
    telemetryWrites += 1;
    // 每 200 次（约 33 分钟）清一次，只留最近 500 条，避免文件无限长
    if (telemetryWrites % 200 === 0) {
      const lines = readFileSync(TELEMETRY_FILE, 'utf8').trim().split('\n');
      if (lines.length > 600) {
        writeFileSync(TELEMETRY_FILE, lines.slice(-500).join('\n') + '\n', 'utf8');
      }
    }
  } catch { /* 遥测写不进去不影响主功能 */ }
}

const DEFAULTS = {
  port: 8799,
  rootDir: '',
  course: '',
  // 课程目录下的子目录（例如 原始录音）；留空则直接放课程目录下
  subDir: '',
  // 课程名 → 真实目录名 的映射。面板上的课程名和硬盘上的文件夹名不一致时用它对接，
  // 有映射就写进映射的目录，没映射才按课程名建目录。
  courseDirs: {},
  courses: [],
  fileNamePattern: '{date} {weekday} {time}',
  includeGerman: true,
  includeChinese: true,
  writeSessionHeader: true
};

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

function expand(p) {
  return String(p || '')
    .replace(/%USERPROFILE%/gi, os.homedir())
    .replace(/^~/, os.homedir());
}

function isUsableRootDir(value) {
  const p = expand(value).trim();
  // Accept both separators. Windows folder dialogs return paths such as C:\\Users\\….
  const isAbsoluteWindows = /^[A-Za-z]:[\\/]/.test(p) || /^\\\\[^\\/]+[\\/][^\\/]+/.test(p);
  if (Array.from(p).some((ch) => { const code = ch.charCodeAt(0); return code < 32 || code === 127; })) return false;
  return isAbsoluteWindows && !/[\*?<>|"\0]/.test(p);
}

const PERMANENT_FS_ERRORS = new Set([
  'EACCES', 'EPERM', 'ENOSPC', 'EROFS', 'ENAMETOOLONG', 'ENOTDIR', 'EISDIR', 'EINVAL'
]);

function isRetryableFsError(error) {
  const code = String(error && error.code || '').toUpperCase();
  return !PERMANENT_FS_ERRORS.has(code);
}

function checkRootDir(value, probe = false) {
  const root = expand(value).trim();
  if (!isUsableRootDir(root)) {
    return { ok: false, error: '请选择 Windows 本地绝对路径', retryable: false };
  }
  try {
    mkdirSync(root, { recursive: true });
    if (!statSync(root).isDirectory()) {
      return { ok: false, error: '落盘根目录不是文件夹', retryable: false };
    }
    accessSync(root, constants.W_OK);
    if (probe) {
      const probeFile = path.join(root, '.gad-write-probe-' + process.pid + '-' + Date.now() + '.tmp');
      writeFileSync(probeFile, 'gpt-auto-dictation write probe\n', { encoding: 'utf8', flag: 'wx' });
      unlinkSync(probeFile);
    }
    return { ok: true, rootDir: root };
  } catch (e) {
    return {
      ok: false,
      error: '落盘目录不可写：' + String(e.message || e),
      code: e.code || '',
      retryable: isRetryableFsError(e)
    };
  }
}

function chooseRootDir() {
  return new Promise((resolve) => {
    const script = [
      'Add-Type -AssemblyName System.Windows.Forms',
      '$utf8 = New-Object System.Text.UTF8Encoding $false',
      '[Console]::OutputEncoding = $utf8',
      '$d = New-Object System.Windows.Forms.FolderBrowserDialog',
      '$d.Description = "选择 GPT 音频转录归档的根目录"',
      '$d.ShowNewFolderButton = $true',
      'if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Write($d.SelectedPath) }'
    ].join('; ');
    execFile('powershell.exe', ['-NoProfile', '-STA', '-Command', script], { windowsHide: false, timeout: 120000 }, (error, stdout) => {
      if (error) return resolve({ ok: false, canceled: true });
      const selected = String(stdout || '').trim();
      resolve(selected ? { ok: true, rootDir: selected } : { ok: false, canceled: true });
    });
  });
}

let rootPickerPromise = null;
async function ensureRootSelected(force = false) {
  if (!force && config.rootDir) return { ok: true, rootDir: config.rootDir };
  if (!rootPickerPromise) {
    rootPickerPromise = chooseRootDir()
      .then((picked) => {
        if (!picked.ok) return picked;
        const checked = checkRootDir(picked.rootDir, true);
        if (!checked.ok) return checked;
        const next = { ...config, rootDir: checked.rootDir };
        const saved = saveConfig(next);
        if (!saved.ok) return saved;
        config = next;
        log('落盘根目录 → ' + config.rootDir);
        return { ok: true, rootDir: config.rootDir };
      })
      .finally(() => { rootPickerPromise = null; });
  }
  return rootPickerPromise;
}

function legacyRootCandidates() {
  const home = os.homedir();
  const bases = [
    path.join(home, 'Desktop'),
    path.join(home, 'Documents')
  ];
  const candidates = [];
  for (const base of bases) {
    try {
      for (const entry of readdirSync(base, { withFileTypes: true })) {
        if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
        candidates.push(path.join(base, entry.name));
        if (candidates.length >= 40) break;
      }
    } catch { /* base directory may not exist */ }
    if (candidates.length >= 40) break;
  }
  return candidates.filter((root, index) => candidates.indexOf(root) === index && existsSync(root)).map((root) => {
    let files = 0;
    let latest = 0;
    const walk = (dir, depth) => {
      if (depth > 2 || files >= 500) return;
      let entries = [];
      try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, depth + 1);
        else if (/\.(md|txt)$/i.test(entry.name)) {
          files += 1;
          try { latest = Math.max(latest, statSync(full).mtimeMs); } catch { /* ignore */ }
        }
        if (files >= 500) return;
      }
    };
    walk(root, 0);
    return { rootDir: root, files, latest: latest ? new Date(latest).toISOString() : null };
  }).filter((item) => item.files > 0)
    .sort((a, b) => String(b.latest || '').localeCompare(String(a.latest || '')))
    .slice(0, 12);
}

function loadConfig() {
  let cfg = { ...DEFAULTS };
  try {
    const source = existsSync(CONFIG_PATH) ? CONFIG_PATH : (existsSync(CONFIG_PATH + '.bak') ? CONFIG_PATH + '.bak' : '');
    if (source) cfg = { ...cfg, ...JSON.parse(readFileSync(source, 'utf8')) };
  } catch (e) {
    try {
      cfg = { ...cfg, ...JSON.parse(readFileSync(CONFIG_PATH + '.bak', 'utf8')) };
      log('主配置损坏，已从备份恢复：' + e.message);
    } catch (backupError) {
      log('配置和备份都无法读取，用默认值：' + String(backupError.message || backupError));
    }
  }
  cfg.port = Number(cfg.port) || DEFAULTS.port;
  cfg.rootDir = cfg.rootDir ? expand(cfg.rootDir) : '';
  if (!Array.isArray(cfg.courses)) cfg.courses = [];
  // courseDirs 只接受"字符串 → 非空字符串"的键值对，其它形状一律丢弃，避免脏配置影响落盘
  const cleanedDirs = {};
  if (cfg.courseDirs && typeof cfg.courseDirs === 'object' && !Array.isArray(cfg.courseDirs)) {
    for (const [from, to] of Object.entries(cfg.courseDirs)) {
      const key = String(from || '').trim();
      const value = String(to == null ? '' : to).trim();
      if (key && value) cleanedDirs[key] = value;
    }
  }
  cfg.courseDirs = cleanedDirs;
  if (typeof cfg.subDir !== 'string') cfg.subDir = DEFAULTS.subDir;
  if (!cfg.fileNamePattern) cfg.fileNamePattern = DEFAULTS.fileNamePattern;
  return cfg;
}

function saveConfig(cfg) {
  const temp = CONFIG_PATH + '.tmp-' + process.pid + '-' + Date.now();
  const backup = CONFIG_PATH + '.bak';
  try {
    writeFileSync(temp, JSON.stringify(cfg, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' });
    JSON.parse(readFileSync(temp, 'utf8'));
    if (existsSync(CONFIG_PATH)) {
      if (existsSync(backup)) unlinkSync(backup);
      try { renameSync(CONFIG_PATH, backup); } catch (e) {
        unlinkSync(temp);
        throw new Error('无法备份旧配置：' + String(e.message || e));
      }
    }
    try {
      renameSync(temp, CONFIG_PATH);
    } catch (e) {
      try { if (!existsSync(CONFIG_PATH) && existsSync(backup)) renameSync(backup, CONFIG_PATH); } catch { /* 保留备份供下次恢复 */ }
      throw new Error('无法替换配置文件：' + String(e.message || e));
    }
    return { ok: true };
  } catch (e) {
    log('配置写回失败：' + e.message);
    try { if (existsSync(temp)) unlinkSync(temp); } catch { /* ignore */ }
    return { ok: false, error: '配置写回失败：' + String(e.message || e), retryable: true };
  }
}

const stamp = () => new Date().toTimeString().slice(0, 8);
function log(msg) {
  console.log(`[${stamp()}] ${msg}`);
}

const pad = (n) => String(n).padStart(2, '0');
const dayString = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const timeString = (d) => `${pad(d.getHours())}-${pad(d.getMinutes())}`;
const clockString = (d) => `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;

function legacySafeName(s) {
  // Windows 文件名不允许 \ / : * ? " < > | ，顺便把连续空格收掉、限长
  return String(s || '').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().slice(0, 80);
}

function safeName(s) {
  let out = legacySafeName(s).replace(/[\x00-\x1f\x7f]/g, '_').replace(/[. ]+$/g, '').trim();
  if (!out || out === '.' || out === '..') return '_';
  if (/^(?:con|prn|aux|nul|clock\$|com[1-9]|lpt[1-9])(?:\..*)?$/i.test(out)) out = '_' + out;
  return out;
}

/** 把模板里的占位符换成真实值。未知占位符原样保留，方便用户自己发现写错了。 */
function formatName(pattern, ctx) {
  const map = {
    '{date}': ctx.date,
    '{time}': ctx.time,
    '{weekday}': ctx.weekday,
    '{course}': ctx.course,
    '{year}': String(ctx.when.getFullYear()),
    '{month}': pad(ctx.when.getMonth() + 1),
    '{day}': pad(ctx.when.getDate())
  };
  let out = String(pattern);
  for (const [k, v] of Object.entries(map)) out = out.split(k).join(v);
  return safeName(out) || ctx.date;
}

/** 路径类错误：调用方应当按 4xx 拒绝，而不是重试。 */
function invalidPathError(message) {
  const e = new Error(message);
  e.code = 'invalid_path';
  e.retryable = false;
  return e;
}

/**
 * 把外部传进来的一个目录片段收敛成"单层安全目录名"，不合法就返回空串。
 * 与 safeName 的区别：这里改用分隔符判定为非法（拒绝），而不是替换成下划线，
 * 因为子文件夹名是外部输入，宁可明确报错也不要悄悄写到别处。
 */
function sanitizeDirSegment(value) {
  const raw = String(value == null ? '' : value);
  if (!raw.trim()) return '';
  if (/[\\/]/.test(raw)) return '';
  if (/[\x00-\x1f\x7f]/.test(raw)) return '';
  if (raw.trim() === '.' || raw.trim() === '..') return '';
  const out = safeName(raw);
  if (!out || out === '.' || out === '..' || /[\\/]/.test(out)) return '';
  return out;
}

/** 课程名 → 目录名：先查 courseDirs 映射，没命中才退回课程名本身。 */
function resolveCourseDirName(cfg, courseName) {
  const raw = String(courseName || '');
  const dirs = cfg.courseDirs && typeof cfg.courseDirs === 'object' ? cfg.courseDirs : {};
  const mapped = Object.prototype.hasOwnProperty.call(dirs, raw) ? String(dirs[raw] || '').trim() : '';
  if (mapped) {
    const clean = sanitizeDirSegment(mapped);
    if (!clean) {
      throw invalidPathError('课程目录映射无效（' + raw + ' → ' + mapped + '）：不能包含路径分隔符，也不能是 . 或 ..');
    }
    return clean;
  }
  const fallback = safeName(raw);
  if (!fallback || fallback === '.' || fallback === '..') return '_';
  return fallback;
}

/** 决定这次写到哪个文件，并确保目录存在。 */
function resolveTarget(cfg, course, when, subDirValue) {
  const rawCourse = String(course || cfg.course || '未分类');
  // 目录名走映射；文件头里的显示名仍然是原始课程名
  const courseDirName = resolveCourseDirName(cfg, rawCourse);
  const displayName = safeName(rawCourse) || courseDirName;
  const root = path.resolve(expand(cfg.rootDir));
  // subDirValue: undefined = 调用方没传，用 config 的默认值；'' = 明确不要子文件夹
  const rawSub = subDirValue === undefined ? String(cfg.subDir || '') : String(subDirValue || '');
  let sub = '';
  if (rawSub.trim()) {
    sub = sanitizeDirSegment(rawSub);
    if (!sub) throw invalidPathError('子文件夹名无效：不能包含路径分隔符，也不能是 . 或 ..');
  }
  const dir = sub ? path.resolve(root, courseDirName, sub) : path.resolve(root, courseDirName);
  if (dir !== root && !dir.startsWith(root + path.sep)) throw invalidPathError('分类目录超出落盘根目录');
  mkdirSync(dir, { recursive: true });
  const ctx = {
    when,
    date: dayString(when),
    time: timeString(when),
    weekday: WEEKDAYS[when.getDay()],
    course: displayName
  };
  return path.join(dir, formatName(cfg.fileNamePattern, ctx) + '.md');
}

/** 新文件才写的文件头：让文件自己说明归档来源、分类和开始时间。 */
function renderHeader(course, when, url) {
  const lines = [];
  lines.push(`# ${safeName(course || '未分类')}`);
  lines.push('');
  lines.push(`- 日期：${dayString(when)}（${WEEKDAYS[when.getDay()]}）`);
  lines.push(`- 开始时间：${clockString(when)}`);
  if (url) lines.push(`- 会话：${url}`);
  lines.push('- 来源：GPT Audio Dictation（ChatGPT 网页音频转录与本地归档）');
  lines.push('');
  lines.push('---');
  lines.push('');
  lines.push('');
  return lines.join('\n');
}

function renderEntry(payload, cfg) {
  const at = payload.at ? new Date(payload.at) : new Date();
  const dur = Number(payload.durationSec);
  const sp = Number(payload.speechSec);
  // 扩展发过来的字段名是 index，老版本可能叫 segmentIndex；两个都认
  const idx = payload.segmentIndex != null ? payload.segmentIndex : (payload.index != null ? payload.index : '-');
  const stat = Number.isFinite(dur)
    ? `（录 ${dur.toFixed(1)}s / 说话 ${Number.isFinite(sp) ? sp.toFixed(1) : '?'}s）`
    : '';
  const out = [];
  out.push(`## ${clockString(at)}　第 ${idx} 段${stat}`);
  out.push('');
  if (cfg.includeGerman && payload.user) {
    out.push('**原始转录**');
    out.push('');
    out.push(payload.user.trim());
    out.push('');
  }
  if (cfg.includeChinese && payload.assistant) {
    out.push('**GPT 回答 / 处理结果**');
    out.push('');
    out.push(payload.assistant.trim());
    out.push('');
  }
  out.push('---');
  out.push('');
  out.push('');
  return out.join('\n');
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'access-control-allow-origin': '*',
    'access-control-allow-headers': 'content-type',
    'cache-control': 'no-store'
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let d = '';
    req.on('data', (c) => { d += c; if (d.length > 4_000_000) req.destroy(); });
    req.on('end', () => resolve(d));
    req.on('error', () => resolve(''));
  });
}

/** 只在 marker 独占一行时命中，避免正文里偶然包含同样字符串被误判成回执。 */
function exactMarkerIndex(content, marker, from = 0) {
  if (!marker) return -1;
  let at = Math.max(0, from);
  while (at <= content.length) {
    const found = content.indexOf(marker, at);
    if (found < 0) return -1;
    const beforeOk = found === 0 || content[found - 1] === '\n' || content[found - 1] === '\r';
    const after = found + marker.length;
    const afterOk = after === content.length || content[after] === '\n' || content[after] === '\r';
    if (beforeOk && afterOk) return found;
    at = found + 1;
  }
  return -1;
}

function lastExactMarkerIndex(content, marker) {
  let found = exactMarkerIndex(content, marker, 0);
  let last = found;
  while (found >= 0) {
    found = exactMarkerIndex(content, marker, found + 1);
    if (found >= 0) last = found;
  }
  return last;
}

function hasAnySaveMarker(content, from = 0) {
  const tail = String(content || '').slice(Math.max(0, from));
  return tail.split(/\r?\n/).some((line) =>
    /^<!-- gad-save-(?:start|end):[^>\r\n]+ -->$/.test(line.trim()));
}

function hasDigestMarker(content, saveId, from = 0) {
  if (!saveId) return false;
  const prefix = '<!-- gad-save-digest:' + saveId + ':';
  return String(content || '').slice(Math.max(0, from)).split(/\r?\n/).some((line) => {
    const value = line.trim();
    return value.startsWith(prefix) && value.endsWith(' -->');
  });
}

function normalizeSegmentText(value) {
  return String(value || '').replace(/\r\n/g, '\n').trim();
}

let config = loadConfig();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'OPTIONS') return json(res, 204, {});

  try {
    if (req.method === 'GET' && url.pathname === '/ping') {
      return json(res, 200, {
        ok: true,
        service: 'gpt-auto-dictation-writer',
        version: SERVICE_VERSION,
        startedAt: STARTED_AT,
        port: config.port,
        rootDir: config.rootDir,
        rootSelected: Boolean(config.rootDir),
        course: config.course,
        courses: config.courses,
        courseDirs: config.courseDirs,
        subDir: config.subDir,
        fileNamePattern: config.fileNamePattern
      });
    }

    if (req.method === 'POST' && url.pathname === '/choose-root') {
      const picked = await ensureRootSelected(true);
      if (!picked.ok) return json(res, picked.canceled ? 200 : 400, picked);
      if (!isUsableRootDir(picked.rootDir)) return json(res, 400, { ok: false, error: '请选择 Windows 本地文件夹' });
      return json(res, 200, { ok: true, rootDir: config.rootDir });
    }

    if (req.method === 'GET' && url.pathname === '/root-candidates') {
      return json(res, 200, { ok: true, current: config.rootDir, candidates: legacyRootCandidates() });
    }

    if (req.method === 'POST' && url.pathname === '/set-root') {
      const body = JSON.parse((await readBody(req)) || '{}');
      const checked = checkRootDir(body.rootDir, true);
      if (!checked.ok) return json(res, 400, checked);
      const next = { ...config, rootDir: checked.rootDir };
      const saved = saveConfig(next);
      if (!saved.ok) return json(res, 500, saved);
      config = next;
      return json(res, 200, { ok: true, rootDir: config.rootDir });
    }

    if (req.method === 'GET' && url.pathname === '/courses') {
      return json(res, 200, {
        ok: true,
        courses: config.courses,
        course: config.course,
        rootDir: config.rootDir,
        courseDirs: config.courseDirs,
        subDir: config.subDir,
        fileNamePattern: config.fileNamePattern
      });
    }

    if (req.method === 'POST' && url.pathname === '/set-course') {
      const body = JSON.parse((await readBody(req)) || '{}');
      if (body.course) {
        const next = { ...config, course: String(body.course), courses: [...config.courses] };
        if (!next.courses.includes(next.course)) next.courses.push(next.course);
        const saved = saveConfig(next);
        if (!saved.ok) return json(res, 500, saved);
        config = next;
        log('当前分类 → ' + config.course);
      }
      return json(res, 200, { ok: true, course: config.course, courses: config.courses });
    }

    if (req.method === 'POST' && url.pathname === '/telemetry') {
      const p = JSON.parse((await readBody(req)) || '{}');
      recordTelemetry(p);
      return json(res, 200, { ok: true });
    }

    if (req.method === 'GET' && url.pathname === '/telemetry') {
      return json(res, 200, { ok: true, latest: telemetryLatest, recent: telemetryRecent.slice(-20) });
    }

    if (req.method === 'POST' && url.pathname === '/save') {
      const p = JSON.parse((await readBody(req)) || '{}');
      const user = (p.user || '').trim();
      const assistant = (p.assistant || '').trim();
      if (!user && !assistant) return json(res, 200, { ok: true, skipped: 'empty' });

      // 分类在扩展入队时已冻结；空分类明确失败，不能偷偷改用 writer 当前分类。
      const course = String(p.course || '').trim();
      if (!course) {
        return json(res, 400, {
          ok: false,
          error: '保存任务没有分类名；请在扩展面板选择或新建分类后重试',
          code: 'missing_course',
          retryable: false
        });
      }
      if (!config.rootDir) {
        return json(res, 409, {
          ok: false,
          error: '请先运行 choose-save-folder.bat 选择落盘根目录',
          retryable: false
        });
      }
      // 子文件夹：body 里带 subDir 就以它为准；没带这个字段才退回 config 的默认值。
      // 显式传空字符串 = 明确不要子文件夹，直接放课程目录下。
      let subDirValue;
      if (Object.prototype.hasOwnProperty.call(p, 'subDir')) {
        const rawSub = p.subDir == null ? '' : String(p.subDir);
        if (rawSub.trim()) {
          const cleanSub = sanitizeDirSegment(rawSub);
          if (!cleanSub) {
            return json(res, 400, {
              ok: false,
              error: '子文件夹名无效：不能包含路径分隔符，也不能是 . 或 ..',
              code: 'invalid_subdir',
              retryable: false
            });
          }
          subDirValue = cleanSub;
        } else {
          subDirValue = '';
        }
      }
      const rootCheck = checkRootDir(config.rootDir, false);
      if (!rootCheck.ok) return json(res, 409, rootCheck);
      // 文件名取"这节课第一次发送的时刻"，由扩展带过来；没有就用本次写入时刻
      const when = p.sessionStart ? new Date(p.sessionStart) : (p.at ? new Date(p.at) : new Date());
      const file = resolveTarget(config, course, Number.isFinite(when.getTime()) ? when : new Date(), subDirValue);
      const saveId = String(p.saveId || '').trim();
      if (saveId && !/^[a-zA-Z0-9._:-]{1,160}$/.test(saveId)) {
        return json(res, 400, { ok: false, error: 'saveId 格式无效', retryable: false });
      }
      const startMarker = saveId ? '<!-- gad-save-start:' + saveId + ' -->' : '';
      const endMarker = saveId ? '<!-- gad-save-end:' + saveId + ' -->' : '';
      const digest = saveId
        ? createHash('sha256').update(user).update('\0').update(assistant).digest('hex')
        : '';
      const digestMarker = saveId ? '<!-- gad-save-digest:' + saveId + ':' + digest + ' -->' : '';
      const entry = renderEntry(p, config);
      const existing = existsSync(file) ? readFileSync(file, 'utf8') : '';
      if (endMarker && exactMarkerIndex(existing, endMarker) >= 0) {
        if (hasDigestMarker(existing, saveId) && exactMarkerIndex(existing, digestMarker) < 0) {
          return json(res, 409, {
            ok: false,
            error: '同一保存编号收到了不同回答；为防覆盖，已保留新任务，请检查文件后处理',
            retryable: false,
            code: 'save_content_changed',
            saveId
          });
        }
        if (!hasDigestMarker(existing, saveId)) {
          const endAt = lastExactMarkerIndex(existing, endMarker);
          const startAt = lastExactMarkerIndex(existing, startMarker);
          const legacySegment = startAt >= 0 && endAt > startAt
            ? existing.slice(startAt, endAt + endMarker.length)
            : '';
          const legacyHead = startAt === 0 && config.writeSessionHeader !== false
            ? renderHeader(course, when, p.url)
            : '';
          const expectedLegacy = startMarker + '\n' + legacyHead + entry + endMarker;
          if (!legacySegment || normalizeSegmentText(legacySegment) !== normalizeSegmentText(expectedLegacy)) {
            return json(res, 409, {
              ok: false,
              error: '旧文件中的同一保存编号内容与本次不同；为防覆盖，已保留新任务，请检查文件后处理',
              retryable: false,
              code: 'save_content_changed',
              saveId
            });
          }
        }
        return json(res, 200, { ok: true, file, created: false, duplicate: true, saveId });
      }
      let beforeSize = existsSync(file) ? statSync(file).size : 0;
      let previousChars = existing.length;
      if (startMarker) {
        const partialAt = lastExactMarkerIndex(existing, startMarker);
        if (partialAt >= 0) {
          if (hasAnySaveMarker(existing, partialAt + startMarker.length)) {
            return json(res, 409, {
              ok: false,
              error: '检测到保存顺序冲突：旧段后已有其他带标记内容，未截断文件',
              retryable: false,
              code: 'save_order_conflict',
              saveId
            });
          }
          truncateSync(file, Buffer.byteLength(existing.slice(0, partialAt), 'utf8'));
          beforeSize = statSync(file).size;
          previousChars = existing.slice(0, partialAt).length;
        }
      }
      const fresh = beforeSize === 0;
      const head = (fresh && config.writeSessionHeader !== false)
        ? renderHeader(course, when, p.url)
        : (fresh ? '' : '');
      const content = (startMarker ? startMarker + '\n' : '') +
        (digestMarker ? digestMarker + '\n' : '') +
        head + entry +
        (endMarker ? endMarker + '\n' : '');
      try {
        appendFileSync(file, content, 'utf8');
        const written = readFileSync(file, 'utf8');
        const endAt = endMarker ? lastExactMarkerIndex(written, endMarker) : -1;
        const verified = endMarker
          ? endAt >= previousChars
          : statSync(file).size >= beforeSize + Buffer.byteLength(content, 'utf8');
        if (!verified) {
          throw new Error('写入后校验失败');
        }
      } catch (e) {
        try {
          if (beforeSize === 0) unlinkSync(file);
          else truncateSync(file, beforeSize);
        } catch (rollbackError) {
          const wrapped = new Error(
            String(e.message || e) +
            '；回滚部分写入也失败：' +
            String(rollbackError.message || rollbackError)
          );
          wrapped.code = e.code || rollbackError.code || '';
          throw wrapped;
        }
        throw e;
      }

      log(`已落盘 → ${path.basename(file)}（原始转录 ${user.length} 字 / GPT 回答 ${assistant.length} 字）`);
      return json(res, 200, { ok: true, file, created: fresh, duplicate: false, saveId });
    }

    return json(res, 404, { ok: false, error: 'not found' });
  } catch (e) {
    log('请求出错：' + e.message);
    // 路径类错误是输入问题，明确按 400 拒绝，不告诉扩展"可以重试"
    const badPath = e && e.code === 'invalid_path';
    return json(res, badPath ? 400 : 500, {
      ok: false,
      error: String(e.message || e),
      code: e.code || '',
      retryable: badPath ? false : (e && e.retryable !== undefined ? e.retryable : isRetryableFsError(e))
    });
  }
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    log(`端口 ${config.port} 已被占用——大概率是本服务已经在跑了，不用重复启动。`);
    process.exit(0);
  }
  log('服务启动失败：' + e.message);
  process.exit(1);
});

server.listen(config.port, '127.0.0.1', () => {
  log(`本地落盘服务已启动（v${SERVICE_VERSION}）`);
  log(`  监听       http://127.0.0.1:${config.port}`);
  log(`  落盘根目录 ${config.rootDir}`);
  log(`  子文件夹   ${config.subDir || '(不建子文件夹，直接放课程目录)'}`);
  log(`  课程映射   ${Object.keys(config.courseDirs || {}).length} 条`);
  log(`  文件名模板 ${config.fileNamePattern}`);
  log(`  当前分类   ${config.course || '(未选，落盘时按扩展面板中的分类)'}`);
  log('  保持这个窗口开着即可；关掉它就停止落盘，但不影响扩展正常工作。');
});
