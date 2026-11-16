/*
 * 会话归档系统（archive-import.js）
 *
 * 职责：
 *   1. 从 ChatGPT 侧栏 DOM 读取会话列表（id + 标题 + 日期线索）
 *   2. 解析两种命名约定，按日期范围过滤，按会话 id 去重
 *   3. 读取会话内容：优先登录态接口（完整分支），失败时退回当前页面可见 DOM
 *   4. 完整性判定：不完整的内容必须标注，默认拒绝保存
 *   5. 写盘：File System Access API 目录句柄优先，退回单 ZIP 下载
 *   6. 在面板里挂载「归档」区块
 *
 * 设计说明见 docs/ARCHIVE-SYSTEM.md。本文件不发送、不删除、不重命名任何会话。
 */
(() => {
  'use strict';

  const ARCHIVE_VERSION = '1.0.0';

  /* ============================ 基础工具 ============================ */

  const pad2 = (n) => String(n).padStart(2, '0');

  function formatDate(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }

  function formatDateTime(d) {
    return formatDate(d) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function makeLocalDate(y, m, d, hh, mm) {
    const year = Number(y);
    const month = Number(m);
    const day = Number(d);
    const hour = hh == null ? 0 : Number(hh);
    const minute = mm == null ? 0 : Number(mm);
    const date = new Date(year, month - 1, day, hour, minute, 0, 0);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 ||
        date.getDate() !== day || date.getHours() !== hour || date.getMinutes() !== minute) {
      return null;
    }
    return date;
  }

  function daysInMonth(year, month) {
    return new Date(Number(year), Number(month), 0).getDate();
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /* ============================ 命名与日期解析 ============================ */

  /*
   * GAD 标题：GAD｜课程简称｜YYYY-MM-DD HH-mm｜课次码-分会话号
   * 全角/半角竖线都认；时间分隔符 - 或 : 都认。
   * 严格整串匹配：标题里混进别的字样就不认，避免把普通对话误判成课程会话。
   */
  const GAD_TITLE_RE = /^GAD\s*[｜|]\s*([^｜|\r\n]+?)\s*[｜|]\s*(\d{4})-(\d{2})-(\d{2})[ T](\d{2})[-:](\d{2})\s*[｜|]\s*([A-Za-z0-9]+)\s*-\s*(\d{2,})\s*$/;

  /** 解析 GAD 课程标题；不是这个格式返回 null。 */
  function parseGadTitle(title) {
    const match = GAD_TITLE_RE.exec(String(title == null ? '' : title).trim());
    if (!match) return null;
    const [, course, year, month, day, hour, minute, classId, part] = match;
    if (!course.trim() || /^0+$/.test(part)) return null;
    const date = makeLocalDate(year, month, day, hour, minute);
    if (!date) return null;
    return {
      kind: 'gad',
      course: course.trim(),
      date: formatDate(date),
      time: pad2(hour) + ':' + pad2(minute),
      at: formatDateTime(date),
      precision: 'minute',
      classId,
      part,
      source: 'title'
    };
  }

  /** 兼容旧原型的字段形状（课程名 / 本地开始时间 / 课次码 / 分会话号）。 */
  function parseTitle(title) {
    const parsed = parseGadTitle(title);
    if (!parsed) return null;
    return {
      course: parsed.course,
      localStart: parsed.date + ' ' + parsed.time,
      classId: parsed.classId,
      part: parsed.part
    };
  }

  /*
   * 会话内的命名消息：本会话命名为：YYYY-MM-DD-HH:MM
   * 只要文本里出现就算，前后可以有别的内容。
   */
  const NAMING_RE = /本会话命名为\s*[:：]\s*(\d{4})-(\d{2})-(\d{2})[\s\-_T]*(\d{1,2})[:：\-](\d{2})/;

  function parseNamingMessage(text) {
    const match = NAMING_RE.exec(String(text == null ? '' : text));
    if (!match) return null;
    const date = makeLocalDate(match[1], match[2], match[3], match[4], match[5]);
    if (!date) return null;
    return {
      kind: 'naming',
      date: formatDate(date),
      time: pad2(match[4]) + ':' + pad2(match[5]),
      at: formatDateTime(date),
      precision: 'minute',
      source: 'naming'
    };
  }

  const WEEKDAY_ZH = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const EN_MONTHS = {
    january: 1, february: 2, march: 3, april: 4, may: 5, june: 6,
    july: 7, august: 8, september: 9, october: 10, november: 11, december: 12,
    jan: 1, feb: 2, mar: 3, apr: 4, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12
  };

  /**
   * 宽松日期解析：侧栏 <time datetime>、分组标题、标题里的裸日期都走这里。
   * 返回 { date, time, precision: 'minute'|'day'|'month', source? }。
   */
  function parseLooseDate(value, now) {
    const raw = String(value == null ? '' : value).trim();
    if (!raw) return null;
    const today = now instanceof Date ? new Date(now.getTime()) : new Date();
    let m;

    // ISO：2026-10-30 / 2026-10-30T09:00:00Z / 2026-10-30 09:00
    m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/.exec(raw);
    if (m) {
      const date = makeLocalDate(m[1], m[2], m[3], m[4] || 0, m[5] || 0);
      if (!date) return null;
      return {
        date: formatDate(date),
        time: m[4] ? pad2(m[4]) + ':' + pad2(m[5]) : null,
        precision: m[4] ? 'minute' : 'day'
      };
    }

    // 年月精度：2026-10 / 2026年10月
    m = /^(\d{4})-(\d{2})$/.exec(raw);
    if (m) {
      const month = Number(m[2]);
      if (month < 1 || month > 12) return null;
      return { date: m[1] + '-' + pad2(month) + '-01', time: null, precision: 'month' };
    }
    m = /^(\d{4})\s*年\s*(\d{1,2})\s*月(?:(\d{1,2})\s*日)?/.exec(raw);
    if (m) {
      const month = Number(m[2]);
      if (month < 1 || month > 12) return null;
      if (m[3]) {
        const date = makeLocalDate(m[1], month, m[3], 0, 0);
        if (!date) return null;
        return { date: formatDate(date), time: null, precision: 'day' };
      }
      return { date: m[1] + '-' + pad2(month) + '-01', time: null, precision: 'month' };
    }

    // 中文相对日期
    if (/^今天/.test(raw)) return { date: formatDate(today), time: null, precision: 'day' };
    if (/^昨天/.test(raw)) {
      const d = new Date(today.getTime());
      d.setDate(d.getDate() - 1);
      return { date: formatDate(d), time: null, precision: 'day' };
    }
    if (/^前天/.test(raw)) {
      const d = new Date(today.getTime());
      d.setDate(d.getDate() - 2);
      return { date: formatDate(d), time: null, precision: 'day' };
    }

    // 英文月份：October 2026 / Oct 30, 2026
    const en = /^(?:(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})|(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{4}))$/i.exec(raw);
    if (en) {
      if (en[1]) {
        const month = EN_MONTHS[en[1].toLowerCase()];
        const date = makeLocalDate(en[3], month, en[2], 0, 0);
        if (!date) return null;
        return { date: formatDate(date), time: null, precision: 'day' };
      }
      const month = EN_MONTHS[en[4].toLowerCase()];
      return { date: en[5] + '-' + pad2(month) + '-01', time: null, precision: 'month' };
    }

    return null;
  }

  /** 侧栏分组标题（今天/昨天/2026年10月/October 2026）判断。 */
  function isDateGroupLabel(text) {
    const s = String(text == null ? '' : text).trim();
    if (!s || s.length > 40) return false;
    return /^(今天|昨天|前天|更早|本周|上周)$/.test(s) ||
      /^\d{4}\s*年\s*\d{1,2}\s*月$/.test(s) ||
      /^\d{4}-\d{2}$/.test(s) ||
      /^(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+\d{4}$/i.test(s) ||
      /^[A-Z][a-z]+ \d{4}$/.test(s);
  }

  /** 从一条侧栏原始记录里选出最可信的日期线索（不含会话正文；正文由 applyNamingDate 补）。 */
  function resolveDateInfo(item, now) {
    const title = String(item && item.title || '');
    const gad = parseGadTitle(title);
    if (gad) return gad;
    const naming = parseNamingMessage(title);
    if (naming) return naming;
    const fromTitle = parseLooseDate(title, now);
    if (fromTitle) return { ...fromTitle, source: 'title-date' };
    // 外部已经解析好的日期（例如从真实归档文件的文件头/文件名读出来的）直接采用
    const explicitDay = parseDay(item && item.date);
    if (explicitDay) {
      const explicitTime = /^\d{2}:\d{2}$/.test(String(item && item.time || '')) ? String(item.time) : null;
      return {
        date: explicitDay,
        time: explicitTime,
        precision: explicitTime ? 'minute' : 'day',
        source: item && item.dateSource ? String(item.dateSource) : 'explicit'
      };
    }
    const fromTime = parseLooseDate(item && item.dateHint, now);
    if (fromTime) return { ...fromTime, source: 'sidebar-time' };
    const fromGroup = parseLooseDate(item && item.groupLabel, now);
    if (fromGroup) return { ...fromGroup, source: 'group-label' };
    return null;
  }

  /* ============================ 会话 id ============================ */

  /**
   * 会话 id 的唯一来源：URL /c/<id>。
   * 只认 ChatGPT 的 https 地址；分享页 /share/ 不算。
   */
  function conversationId(href) {
    try {
      const url = new URL(String(href || ''), 'https://chatgpt.com');
      if (url.protocol !== 'https:') return '';
      if (url.hostname !== 'chatgpt.com' && url.hostname !== 'chat.openai.com') return '';
      const match = /^\/c\/([A-Za-z0-9_-]+)\/?$/.exec(url.pathname);
      return match ? match[1] : '';
    } catch {
      return '';
    }
  }

  /* ============================ 列表 -> 记录 ============================ */

  /**
   * 把侧栏原始条目整理成归档记录。
   * 纯函数，输入输出都是 JSON，方便无浏览器测试。
   */
  function buildRecords(rawItems, opts) {
    const options = opts || {};
    const now = options.now instanceof Date ? options.now : new Date();
    const records = [];
    for (const item of Array.isArray(rawItems) ? rawItems : []) {
      if (!item) continue;
      const id = item.id || conversationId(item.href);
      if (!id) continue;
      const title = String(item.title == null ? '' : item.title).replace(/\s+/g, ' ').trim();
      const info = resolveDateInfo({ ...item, id, title }, now);
      const gad = parseGadTitle(title);
      records.push({
        id,
        href: item.href || ('https://chatgpt.com/c/' + id),
        title: title || '(无标题会话)',
        course: gad ? gad.course : '',
        classId: gad ? gad.classId : '',
        part: gad ? gad.part : '',
        date: info ? info.date : '',
        time: info ? info.time : '',
        precision: info ? info.precision : '',
        dateSource: info ? info.source : 'unknown',
        dateUnknown: !info,
        dateRaw: info ? (info.at || info.date) : (item.dateHint || item.groupLabel || ''),
        groupLabel: String(item.groupLabel || ''),
        checked: false
      });
    }
    const deduped = dedupeById(records);
    // 标题通用性检测：挂到每条记录上，UI 可据此提示"标题不可用于判日期"
    const generic = detectGenericTitles(deduped.map((r) => r.title));
    return deduped.map((record) => ({ ...record, titleUsable: !generic.generic, titleQuality: generic }));
  }

  const DATE_SOURCE_RANK = {
    title: 3,
    gad: 3,
    naming: 2,
    'title-date': 2,
    'sidebar-time': 1,
    'group-label': 1,
    unknown: 0
  };

  /**
   * 按会话 id 去重。同一个 id 的多条记录合并：
   * 日期来源更可信的优先，其它字段缺了就从另一条补。
   */
  function dedupeById(records) {
    const byId = new Map();
    const order = [];
    for (const record of Array.isArray(records) ? records : []) {
      if (!record || !record.id) continue;
      const existing = byId.get(record.id);
      if (!existing) {
        byId.set(record.id, { ...record });
        order.push(record.id);
        continue;
      }
      const newRank = DATE_SOURCE_RANK[record.dateSource] != null ? DATE_SOURCE_RANK[record.dateSource] : 0;
      const oldRank = DATE_SOURCE_RANK[existing.dateSource] != null ? DATE_SOURCE_RANK[existing.dateSource] : 0;
      const winner = newRank > oldRank ? record : existing;
      const loser = newRank > oldRank ? existing : record;
      const merged = { ...loser, ...winner };
      merged.checked = !!(winner.checked || loser.checked);
      if (!merged.course && loser.course) merged.course = loser.course;
      if (!merged.classId && loser.classId) merged.classId = loser.classId;
      if (!merged.part && loser.part) merged.part = loser.part;
      byId.set(record.id, merged);
    }
    return order.map((id) => byId.get(id));
  }

  /** 把 YYYY-MM-DD 校验成 {ok, date}。 */
  function parseDay(value) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value == null ? '' : value).trim());
    if (!m) return null;
    const date = makeLocalDate(m[1], m[2], m[3], 0, 0);
    return date ? formatDate(date) : null;
  }

  /** 起止颠倒时自动交换；无效返回 null。 */
  function normalizeRange(start, end) {
    const a = parseDay(start);
    const b = parseDay(end);
    if (!a || !b) return null;
    return a <= b ? { start: a, end: b } : { start: b, end: a };
  }

  function recordInRange(record, range) {
    if (!record || !record.date) return false;
    if (record.precision === 'month') {
      const year = record.date.slice(0, 4);
      const month = record.date.slice(5, 7);
      const monthStart = year + '-' + month + '-01';
      const monthEnd = year + '-' + month + '-' + pad2(daysInMonth(year, month));
      return range.start <= monthStart && monthEnd <= range.end;
    }
    return range.start <= record.date && record.date <= range.end;
  }

  /**
   * 按日期范围过滤。返回三段，面板能明确告诉用户"匹配多少、范围外多少、日期未知多少"。
   */
  function filterByDateRange(records, range, opts) {
    const options = opts || {};
    const normalized = range && range.start && range.end
      ? normalizeRange(range.start, range.end)
      : null;
    if (!normalized) throw new Error('日期范围无效');
    const matched = [];
    const excluded = [];
    const unknown = [];
    for (const record of Array.isArray(records) ? records : []) {
      if (!record || !record.date) {
        unknown.push(record);
        continue;
      }
      if (recordInRange(record, normalized)) matched.push(record);
      else excluded.push(record);
    }
    if (options.includeUnknown) matched.push(...unknown);
    return {
      range: normalized,
      matched,
      excluded,
      unknown,
      counts: {
        matched: matched.length,
        excluded: excluded.length,
        unknown: unknown.length,
        total: (Array.isArray(records) ? records.length : 0)
      }
    };
  }

  /* ============================ 旧格式兼容（gad-save-*） ============================ */

  const WEEKDAY_ZH_FULL = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  /** 旧 writer 文件名：`YYYY-MM-DD 周X HH-mm.md`。time 接受 HH:MM 或 HH:MM:SS。 */
  function buildLegacyFileName(record) {
    const d = parseDay(record && record.date);
    if (!d) return '';
    const date = new Date(d + 'T00:00:00');
    const weekday = WEEKDAY_ZH_FULL[date.getDay()];
    const raw = String(record.time || '');
    const m = /^(\d{2}):(\d{2})(?::\d{2})?$/.exec(raw);
    const time = m ? m[1] + '-' + m[2] : '00-00';
    return d + ' ' + weekday + ' ' + time + '.md';
  }

  /**
   * 用旧 writer 格式渲染一个会话（与 writer.mjs:renderHeader/renderEntry 对齐）。
   * 用途：目标目录已有旧格式归档时保持格式一致。
   */
  function renderLegacyMarkdown(record, conversation, opts) {
    const options = opts || {};
    void options;
    const conv = conversation || {};
    const messages = Array.isArray(conv.messages) ? conv.messages : [];
    // 旧格式的「开始时间」是 HH:MM:SS；record.time 可能只到分钟，也可能已带秒（从旧文件解析回来时）
    const rawTime = String((record && record.time) || '00:00');
    const timeWithSeconds = /^\d{2}:\d{2}:\d{2}$/.test(rawTime) ? rawTime : rawTime + ':00';
    const when = parseDay(record && record.date)
      ? new Date(record.date + 'T' + timeWithSeconds)
      : new Date();
    const pad = pad2;
    const clock = (d) => pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    const dayString = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    const lines = [];
    lines.push('# ' + safeName((record && record.course) || (record && record.title) || '未分类'));
    lines.push('');
    lines.push('- 日期：' + dayString(when) + '（' + WEEKDAY_ZH_FULL[when.getDay()] + '）');
    lines.push('- 开始时间：' + clock(when));
    if (record && record.href) lines.push('- 会话：' + record.href);
    lines.push('- 来源：GPT Audio Dictation（ChatGPT 网页音频转录与本地归档）');
    lines.push('');
    lines.push('---');
    lines.push('');
    lines.push('');
    let round = 0;
    let user = '';
    let answer = '';
    const flush = () => {
      if (!user && !answer) return;
      round += 1;
      lines.push('## ' + clock(when) + '　第 ' + round + ' 段');
      lines.push('');
      if (user) {
        lines.push('**原始转录**');
        lines.push('');
        lines.push(user);
        lines.push('');
      }
      if (answer) {
        lines.push('**GPT 回答 / 处理结果**');
        lines.push('');
        lines.push(answer);
        lines.push('');
      }
      lines.push('---');
      lines.push('');
      lines.push('');
      user = '';
      answer = '';
    };
    for (const message of messages) {
      if (!message) continue;
      if (message.role === 'user') { flush(); user = message.text; answer = ''; }
      else if (message.role === 'assistant') answer += (answer ? '\n\n' : '') + message.text;
    }
    flush();
    return lines.join('\n');
  }

  /**
   * 检测已有归档是否使用旧格式。
   * entries 可以是文件名数组，或 [{name}] / [{fileName}]。
   * 只要有一个文件名符合 `YYYY-MM-DD 周X HH-mm.md` 就判定为旧格式。
   */
  function detectLegacyFormat(entries) {
    const list = Array.isArray(entries) ? entries : [];
    for (const entry of list) {
      const name = typeof entry === 'string' ? entry : (entry && (entry.name || entry.fileName)) || '';
      if (/^\d{4}-\d{2}-\d{2} 周[日一二三四五六] \d{2}-\d{2}\.md$/.test(String(name))) return true;
    }
    return false;
  }

  /**
   * 从已有归档文本里收集会话 id（旧格式 `- 会话：<url>` 行）。
   * 用于去重：同一个会话重跑时不应再写一份。
   */
  function collectExistingConversationIds(entries) {
    const ids = new Set();
    for (const entry of Array.isArray(entries) ? entries : []) {
      const text = typeof entry === 'string' ? entry : (entry && (entry.text || entry.content)) || '';
      if (!text) continue;
      const re = /^- 会话：\s*(https:\/\/chatgpt\.com\/c\/[A-Za-z0-9_-]+)\s*$/gm;
      let m;
      while ((m = re.exec(text))) {
        const id = conversationId(m[1]);
        if (id) ids.add(id);
      }
    }
    return ids;
  }

  /* ============================ 文件名 ============================ */

  const WINDOWS_BAD = /[\\/:*?"<>|]/g;

  /** 单段文件名清洗，规则与 writer 保持一致。 */
  function safeName(value, maxLen) {
    let out = String(value == null ? '' : value)
      .replace(WINDOWS_BAD, '_')
      .replace(/[\x00-\x1f\x7f]/g, '_')
      .replace(/\s+/g, ' ')
      .trim()
      .replace(/[. ]+$/g, '');
    if (!out || out === '.' || out === '..') out = '_';
    if (/^(?:con|prn|aux|nul|clock\$|com[1-9]|lpt[1-9])(?:\..*)?$/i.test(out)) out = '_' + out;
    const limit = Number(maxLen) > 0 ? Number(maxLen) : 120;
    if (out.length > limit) out = out.slice(0, limit).replace(/[. ]+$/g, '').trim() || '_';
    return out;
  }

  /**
   * 归档文件名：
   *   GAD 会话 -> `YYYY-MM-DD HH-mm 课程 课次码-分会话号 [id短码].md`
   *   普通会话 -> `YYYY-MM-DD 标题 [id短码].md`
   * id 短码取前 10 位；同批重名时由 uniqueFileNames 追加 (2)、(3) 兜底。
   */
  function buildFileName(record, opts) {
    const options = opts || {};
    const shortId = options.idShort || String(record && record.id || '').slice(0, 10) || 'unknown';
    const suffix = ' [' + shortId + ']';
    const datePart = record && record.date ? record.date : (options.unknownDateLabel || '日期未知');
    const timePart = record && record.time ? ' ' + record.time.replace(':', '-') : '';
    let core = '';
    if (record && record.course) {
      core = [record.course, [record.classId, record.part].filter(Boolean).join('-')].filter(Boolean).join(' ');
    } else {
      core = String(record && record.title || '未命名会话')
        .replace(/^GAD\s*[｜|]\s*/, '')
        .replace(/\s+/g, ' ')
        .trim();
    }
    // 先截"可读部分"，再拼 id 后缀：后缀是身份标记，不能被长度上限切掉。
    const prefix = safeName(datePart + timePart + ' ' + core, 120 - suffix.length - 3).replace(/\.md$/i, '');
    const base = safeName(prefix + suffix, 124).replace(/\.md$/i, '');
    return base + '.md';
  }

  /** 同一批里重名时追加 (2)、(3)…；用于 ZIP 和目录写入。 */
  function uniqueFileNames(records) {
    const used = new Set();
    const list = Array.isArray(records) ? records : [];
    /*
     * 先解决「id 短码前缀相同」的问题：多条会话 id 前 10 位一样时，
     * 自动加长短码到能区分（最多用完整 id）。避免只靠 (2)(3) 后缀，
     * 那样文件名看不出是哪条会话。
     */
    const shortOf = list.map((record) => String((record && record.id) || '').slice(0, 10) || 'unknown');
    const counts = new Map();
    for (const s of shortOf) counts.set(s, (counts.get(s) || 0) + 1);
    const disambiguated = list.map((record, index) => {
      const id = String((record && record.id) || '');
      const short = shortOf[index];
      if ((counts.get(short) || 0) <= 1) return record;
      // 前缀撞了：逐步加长，直到同批里唯一（最多到完整 id）
      for (let len = 11; len <= id.length; len += 1) {
        const candidate = id.slice(0, len);
        const clash = list.some((other, j) => j !== index &&
          String((other && other.id) || '').slice(0, len) === candidate);
        if (!clash) return { ...record, _idShort: candidate };
      }
      return { ...record, _idShort: id };
    });
    return disambiguated.map((record) => {
      const base = buildFileName(record, { idShort: record._idShort });
      let name = base;
      if (!used.has(name)) { used.add(name); return name; }
      const stem = name.replace(/\.md$/i, '');
      let index = 2;
      while (used.has(stem + ' (' + index + ').md')) index += 1;
      name = stem + ' (' + index + ').md';
      used.add(name);
      return name;
    });
  }

  /* ============================ 完整性 ============================ */

  /**
   * 判定一份会话内容是否完整。
   * 返回 { level, reasons, counts }：
   *   full        接口读取且所有证据都表明完整
   *   visible_only 只拿到页面 DOM 当前渲染的内容
   *   truncated   接口读取但发现缺失/未完成
   *   empty       没有消息
   */
  function assessCompleteness(conversation) {
    const conv = conversation || {};
    const messages = Array.isArray(conv.messages) ? conv.messages : [];
    const counts = {
      user: messages.filter((m) => m && m.role === 'user').length,
      assistant: messages.filter((m) => m && m.role === 'assistant').length,
      total: messages.length
    };
    if (!messages.length) {
      return { level: 'empty', reasons: ['没有读取到任何消息'], counts };
    }
    if (conv.source === 'dom') {
      return {
        level: 'visible_only',
        reasons: ['内容来自当前页面 DOM，只包含浏览器当前渲染的轮次；较早的消息可能已从页面移除'],
        counts
      };
    }
    const reasons = [];
    if (conv.isComplete === false) reasons.push('会话接口标记该会话尚未完成');
    if (Number.isFinite(conv.declaredTotal) && conv.declaredTotal > messages.length) {
      reasons.push('接口报告应有 ' + conv.declaredTotal + ' 条消息，实际只取到 ' + messages.length + ' 条');
    }
    const unfinished = messages.filter((m) => m && m.role === 'assistant' && m.status && m.status !== 'finished_successfully');
    if (unfinished.length) reasons.push('有 ' + unfinished.length + ' 条回答状态不是 finished_successfully');
    if (conv.linearized === false) reasons.push('会话没有 current_node，只能按时间排序，无法证明分支完整');
    return reasons.length
      ? { level: 'truncated', reasons, counts }
      : { level: 'full', reasons: [], counts };
  }

  /** 默认拒绝保存不完整内容；用户显式勾选"允许"才放行（文件里会带警告）。 */
  function checkSaveGate(assessment, allowIncomplete) {
    const level = assessment && assessment.level;
    if (level === 'full') return { ok: true };
    if (level === 'empty') return { ok: false, code: 'content_empty', message: '没有读取到消息，未保存' };
    if (level === 'visible_only') {
      if (!allowIncomplete) {
        return {
          ok: false,
          code: 'content_visible_only',
          message: '只拿到页面可见内容，可能缺早期轮次；默认拒绝保存。勾选"允许保存不完整内容"后可保存，文件会标注不完整。'
        };
      }
      return { ok: true, warning: '内容不完整（仅页面可见部分），已在文件中标注' };
    }
    if (level === 'truncated') {
      if (!allowIncomplete) {
        return {
          ok: false,
          code: 'content_truncated',
          message: '会话内容看起来不完整；默认拒绝保存。勾选"允许保存不完整内容"后可保存，文件会标注不完整。'
        };
      }
      return { ok: true, warning: '内容不完整（接口显示缺失），已在文件中标注' };
    }
    return { ok: false, code: 'content_unknown', message: '无法判断内容完整性，未保存' };
  }

  /* ============================ 会话接口读取 ============================ */

  /** 把 /backend-api/conversation/<id> 的返回整理成统一结构（纯函数，可测试）。 */
  function normalizeConversationPayload(payload, id) {
    const j = payload || {};
    const mapping = j.mapping && typeof j.mapping === 'object' ? j.mapping : {};
    const messages = [];
    let linearized = true;
    let node = j.current_node;
    const chain = [];
    const seen = new Set();
    while (node && mapping[node] && !seen.has(node)) {
      seen.add(node);
      const item = mapping[node];
      if (item && item.message) chain.push(item.message);
      node = item && item.parent;
    }
    if (!chain.length) {
      linearized = false;
      for (const item of Object.values(mapping)) {
        if (item && item.message) chain.push(item.message);
      }
      chain.sort((a, b) => (Number(a.create_time) || 0) - (Number(b.create_time) || 0));
    } else {
      chain.reverse();
    }
    for (const message of chain) {
      const role = message && message.author && message.author.role;
      if (role !== 'user' && role !== 'assistant') continue;
      const parts = message.content && Array.isArray(message.content.parts) ? message.content.parts : [];
      let text = parts.filter((part) => typeof part === 'string').join('\n').trim();
      if (!text && message.content && typeof message.content.text === 'string') text = message.content.text.trim();
      if (!text) continue;
      messages.push({
        role,
        text,
        status: message.status || '',
        messageId: message.id || '',
        createTime: Number(message.create_time) || null
      });
    }
    return {
      id: String(id || j.conversation_id || ''),
      title: String(j.title || ''),
      messages,
      source: 'api',
      isComplete: j.is_complete !== false,
      declaredTotal: Number.isFinite(Number(j.message_count)) ? Number(j.message_count) : null,
      linearized
    };
  }

  async function fetchConversation(id, opts) {
    const options = opts || {};
    if (typeof fetch !== 'function') throw new Error('fetch_unavailable');
    const origin = options.origin || (typeof location !== 'undefined' ? location.origin : 'https://chatgpt.com');
    const sessionRes = await fetch(origin + '/api/auth/session', { credentials: 'include' });
    if (!sessionRes.ok) throw new Error('session_http_' + sessionRes.status);
    const session = await sessionRes.json().catch(() => ({}));
    if (!session || !session.accessToken) throw new Error('no_access_token');
    const res = await fetch(origin + '/backend-api/conversation/' + encodeURIComponent(id), {
      credentials: 'include',
      headers: { authorization: 'Bearer ' + session.accessToken }
    });
    if (!res.ok) throw new Error('conversation_http_' + res.status);
    const json = await res.json();
    return normalizeConversationPayload(json, id);
  }

  /* ============================ 页面 DOM 读取 ============================ */

  function cleanNodeText(node) {
    if (!node) return '';
    try {
      const clone = node.cloneNode(true);
      clone.querySelectorAll('button, [role="button"], svg').forEach((item) => item.remove());
      return String(clone.innerText || clone.textContent || '').trim();
    } catch {
      return String(node.innerText || node.textContent || '').trim();
    }
  }

  /** 从当前页面 DOM 抓可见消息（兜底路径，必然标注 visible_only）。 */
  function scrapeVisibleConversation(doc) {
    const page = doc || (typeof document !== 'undefined' ? document : null);
    if (!page) return null;
    const href = page.location && page.location.href ? page.location.href : (typeof location !== 'undefined' ? location.href : '');
    const id = conversationId(href);
    const messages = [];
    const roleNodes = Array.from(page.querySelectorAll('[data-message-author-role]'));
    for (const node of roleNodes) {
      const role = node.getAttribute('data-message-author-role');
      if (role !== 'user' && role !== 'assistant') continue;
      const text = cleanNodeText(node);
      if (!text) continue;
      const messageId = node.getAttribute('data-message-id') || '';
      messages.push({ role, text, status: '', messageId, createTime: null });
    }
    if (!messages.length) {
      // 旧版 ChatGPT 的类名兜底：用户消息 + Markdown 回答
      const users = Array.from(page.querySelectorAll('[class~="group/user-message"]'));
      const answers = Array.from(page.querySelectorAll('[class*="MarkdownRoot"]'));
      for (const node of users) {
        const text = cleanNodeText(node);
        if (text) messages.push({ role: 'user', text, status: '', messageId: '', createTime: null });
      }
      for (const node of answers) {
        const text = cleanNodeText(node);
        if (text) messages.push({ role: 'assistant', text, status: '', messageId: '', createTime: null });
      }
    }
    const title = String((page.title || '').replace(/\s*[|｜-]\s*ChatGPT\s*$/i, '')).trim();
    return { id, title, messages, source: 'dom', isComplete: false, declaredTotal: null };
  }

  /**
   * 当前打开会话里可见的命名消息（`本会话命名为：YYYY-MM-DD-HH:MM`）。
   * 只有在用户正开着那个会话时才可能读到；读不到返回 null。
   */
  function readVisibleNamingMessage(doc) {
    const page = doc || (typeof document !== 'undefined' ? document : null);
    if (!page) return null;
    const href = page.location && page.location.href ? page.location.href : (typeof location !== 'undefined' ? location.href : '');
    const id = conversationId(href);
    if (!id) return null;
    const nodes = Array.from(page.querySelectorAll('[data-message-author-role="user"]'))
      .concat(Array.from(page.querySelectorAll('[class~="group/user-message"]')));
    for (const node of nodes.slice(0, 5)) {
      const parsed = parseNamingMessage(cleanNodeText(node));
      if (parsed) return { ...parsed, conversationId: id };
    }
    return null;
  }

  /** 从侧栏抓原始条目：id、标题、<time> 日期线索、最近的分组标题。 */
  function readSidebarItems(doc) {
    const page = doc || (typeof document !== 'undefined' ? document : null);
    if (!page) return [];
    const anchors = Array.from(page.querySelectorAll('a[href*="/c/"]'));
    const items = [];
    for (const anchor of anchors) {
      const href = anchor.href || anchor.getAttribute('href') || '';
      const id = conversationId(href);
      if (!id) continue;
      const title = String(
        anchor.getAttribute('aria-label') ||
        anchor.getAttribute('title') ||
        anchor.textContent ||
        ''
      ).replace(/\s+/g, ' ').trim();

      /*
       * <time> 只认「属于这条会话自己的」：优先 anchor 内部；
       * 再看 anchor 的父容器——但父容器必须只包含这一个会话链接，
       * 否则会误借上一条会话的 <time>（真实页面踩过）。
       */
      let dateHint = '';
      try {
        const own = anchor.querySelector('time[datetime]');
        if (own) {
          dateHint = own.getAttribute('datetime') || own.getAttribute('title') || '';
        } else {
          const parent = anchor.parentElement;
          if (parent) {
            const linksInParent = parent.querySelectorAll ? parent.querySelectorAll('a[href*="/c/"]') : [];
            if (linksInParent && linksInParent.length === 1) {
              const near = parent.querySelector('time[datetime]');
              if (near) dateHint = near.getAttribute('datetime') || near.getAttribute('title') || '';
            }
          }
        }
      } catch { /* 忽略 */ }

      /*
       * 分组标题只认「紧邻的前置兄弟节点」。不再向上跨层查找：
       * 跨层会把外层分组（如 2026年10月）错误套到本不属于该组的条目上。
       * 真实 ChatGPT 侧栏里，分组标题与它下面的会话链接是同一层兄弟。
       */
      let groupLabel = '';
      try {
        let sibling = anchor.previousElementSibling;
        while (sibling) {
          const text = String(sibling.textContent || '').replace(/\s+/g, ' ').trim();
          if (isDateGroupLabel(text)) { groupLabel = text; break; }
          // 遇到另一条会话链接就停止：说明已经越过了本组的起点
          if (sibling.querySelector && sibling.querySelector('a[href*="/c/"]')) break;
          sibling = sibling.previousElementSibling;
        }
        if (!groupLabel && anchor.parentElement) {
          // 条目若包在单条容器里，允许看该容器自身的紧邻前置兄弟
          const parent = anchor.parentElement;
          const linksInParent = parent.querySelectorAll ? parent.querySelectorAll('a[href*="/c/"]') : [];
          if (!linksInParent || linksInParent.length === 1) {
            let ps = parent.previousElementSibling;
            while (ps) {
              const text = String(ps.textContent || '').replace(/\s+/g, ' ').trim();
              if (isDateGroupLabel(text)) { groupLabel = text; break; }
              if (ps.querySelector && ps.querySelector('a[href*="/c/"]')) break;
              ps = ps.previousElementSibling;
            }
          }
        }
      } catch { /* 忽略 */ }

      items.push({ id, href, title, dateHint, groupLabel });
    }
    return items;
  }

  /** 找侧栏的滚动容器，用于触发懒加载。 */
  function findSidebarScroller(doc) {
    const page = doc || (typeof document !== 'undefined' ? document : null);
    if (!page) return null;
    const anchors = Array.from(page.querySelectorAll('a[href*="/c/"]'));
    if (!anchors.length) return null;
    let node = anchors[0];
    for (let depth = 0; depth < 6 && node; depth += 1) {
      if (node.scrollHeight > node.clientHeight + 20) return node;
      node = node.parentElement;
    }
    return null;
  }

  /**
   * 读取侧栏会话列表。侧栏是懒加载的，这里向下滚动最多 rounds 轮触发加载，
   * 结束后把滚动位置还原，不改变用户看到的页面状态。
   */
  async function collectSidebarRecords(doc, opts) {
    const options = opts || {};
    const rounds = Number.isFinite(options.rounds) ? options.rounds : 12;
    const waitMs = Number.isFinite(options.waitMs) ? options.waitMs : 300;
    const page = doc || (typeof document !== 'undefined' ? document : null);
    if (!page) return [];
    const collected = new Map();
    const collect = () => {
      for (const item of readSidebarItems(page)) {
        const previous = collected.get(item.id);
        if (!previous) collected.set(item.id, item);
        else if (!previous.dateHint && item.dateHint) collected.set(item.id, { ...previous, dateHint: item.dateHint });
      }
      if (typeof options.onCount === 'function') options.onCount(collected.size);
    };
    collect();
    const scroller = findSidebarScroller(page);
    if (scroller && rounds > 0) {
      const originalTop = scroller.scrollTop;
      try {
        for (let i = 0; i < rounds; i += 1) {
          const before = scroller.scrollTop;
          scroller.scrollTop = before + Math.max(120, scroller.clientHeight * 0.85);
          await sleep(waitMs);
          collect();
          if (scroller.scrollTop === before) break;
        }
      } finally {
        try { scroller.scrollTop = originalTop; } catch { /* 忽略 */ }
      }
    }
    return Array.from(collected.values());
  }

  /* ============================ Markdown ============================ */

  const LEVEL_LABEL = {
    full: '完整',
    visible_only: '不完整（仅页面可见部分）',
    truncated: '不完整（接口显示有缺失）',
    empty: '空'
  };

  const SOURCE_LABEL = {
    api: '登录态接口（当前分支）',
    dom: '当前页面可见 DOM',
    unavailable: '不可用'
  };

  /** 生成一个会话的 Markdown。 */
  function renderMarkdown(record, conversation, opts) {
    const options = opts || {};
    const conv = conversation || {};
    const assessment = options.assessment || assessCompleteness(conv);
    const messages = Array.isArray(conv.messages) ? conv.messages : [];
    const title = record && record.course ? record.course : (record && record.title) || conv.title || '未命名会话';
    const isFull = assessment.level === 'full';
    const lines = [];
    lines.push('# ' + title);
    lines.push('');
    if (record) {
      lines.push('- 标题：' + (record.title || conv.title || '(无标题会话)'));
      lines.push('- 会话 ID：' + record.id);
      lines.push('- 会话链接：' + (record.href || ('https://chatgpt.com/c/' + record.id)));
      lines.push('- 检测日期：' + (record.date || '未知') + (record.time ? ' ' + record.time : '') +
        (record.dateSource && record.dateSource !== 'unknown' ? '（来源：' + record.dateSource + '）' : ''));
      if (record.namingConflict) {
        const c = record.namingConflict;
        lines.push('- 日期冲突：标题/侧栏为 ' + (c.previousDate || '未知') +
          (c.previousTime ? ' ' + c.previousTime : '') +
          '，会话内命名消息为 ' + c.namingDate + (c.namingTime ? ' ' + c.namingTime : '') +
          '；按「命名消息优先」采用后者');
      }
    }
    lines.push('- 捕获时间：' + formatDateTime(options.now instanceof Date ? options.now : new Date()));
    lines.push('- 捕获方式：' + (SOURCE_LABEL[conv.source] || conv.source || '未知'));
    lines.push('- 完整性：' + (isFull ? '完整' : '可能截断') + '（' + (LEVEL_LABEL[assessment.level] || assessment.level) + '）');
    lines.push('- 消息数：用户 ' + assessment.counts.user + ' 条 / 助手 ' + assessment.counts.assistant + ' 条');
    if (Array.isArray(assessment.reasons) && assessment.reasons.length) {
      lines.push('- 完整性说明：' + assessment.reasons.join('；'));
    }
    lines.push('- 导出工具：GPT Audio Dictation 归档 v' + ARCHIVE_VERSION);
    lines.push('');
    if (assessment.level !== 'full') {
      lines.push('> ⚠ **内容不完整**：' +
        (assessment.level === 'visible_only'
          ? '本文件来自浏览器当前可见的页面内容，较早的对话轮次可能已被页面移除。不能当作完整会话使用。'
          : '读取到的消息存在缺失或未完成状态，本文件可能不完整。'));
      lines.push('');
    }
    lines.push('---');
    lines.push('');

    let round = 0;
    let pendingUser = '';
    let pendingAssistant = [];
    const flush = () => {
      if (!pendingUser && !pendingAssistant.length) return;
      round += 1;
      lines.push('## 第 ' + round + ' 轮');
      lines.push('');
      if (pendingUser) {
        lines.push('**用户**');
        lines.push('');
        lines.push(pendingUser);
        lines.push('');
      }
      if (pendingAssistant.length) {
        lines.push('**ChatGPT**');
        lines.push('');
        lines.push(pendingAssistant.join('\n\n'));
        lines.push('');
      }
      lines.push('---');
      lines.push('');
      pendingUser = '';
      pendingAssistant = [];
    };
    for (const message of messages) {
      if (message.role === 'user') {
        flush();
        pendingUser = message.text;
      } else if (message.role === 'assistant') {
        pendingAssistant.push(message.text);
      }
    }
    flush();
    if (!messages.length) {
      lines.push('（没有读取到任何消息）');
      lines.push('');
    }
    return lines.join('\n');
  }

  /* ============================ ZIP（store，无压缩） ============================ */

  const CRC_TABLE = (() => {
    const table = new Uint32Array(256);
    for (let n = 0; n < 256; n += 1) {
      let c = n;
      for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[n] = c >>> 0;
    }
    return table;
  })();

  function crc32(bytes) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < bytes.length; i += 1) c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function dosDateTime(date) {
    const d = date instanceof Date ? date : new Date();
    const year = Math.max(1980, d.getFullYear());
    return {
      date: ((year - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate(),
      time: (d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2)
    };
  }

  function concatBytes(parts) {
    const total = parts.reduce((sum, part) => sum + part.length, 0);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const part of parts) { out.set(part, offset); offset += part.length; }
    return out;
  }

  /**
   * 生成一个未压缩 ZIP（store 模式）。输入 [{name, text}]，返回 Uint8Array。
   * 不依赖任何第三方库，浏览器和 Node 都能跑。
   */
  function buildZip(entries, opts) {
    const options = opts || {};
    const encoder = new TextEncoder();
    const now = options.now instanceof Date ? options.now : new Date();
    const stamp = dosDateTime(now);
    const locals = [];
    const centrals = [];
    let offset = 0;
    let count = 0;
    for (const entry of Array.isArray(entries) ? entries : []) {
      if (!entry || !entry.name) continue;
      const nameBytes = encoder.encode(String(entry.name));
      const data = entry.data instanceof Uint8Array ? entry.data : encoder.encode(String(entry.text == null ? '' : entry.text));
      const crc = crc32(data);
      const local = new Uint8Array(30 + nameBytes.length);
      const lv = new DataView(local.buffer);
      lv.setUint32(0, 0x04034b50, true);
      lv.setUint16(4, 20, true);
      lv.setUint16(6, 0x0800, true);
      lv.setUint16(8, 0, true);
      lv.setUint16(10, stamp.time, true);
      lv.setUint16(12, stamp.date, true);
      lv.setUint32(14, crc, true);
      lv.setUint32(18, data.length, true);
      lv.setUint32(22, data.length, true);
      lv.setUint16(26, nameBytes.length, true);
      lv.setUint16(28, 0, true);
      local.set(nameBytes, 30);
      locals.push(local, data);

      const central = new Uint8Array(46 + nameBytes.length);
      const cv = new DataView(central.buffer);
      cv.setUint32(0, 0x02014b50, true);
      cv.setUint16(4, 20, true);
      cv.setUint16(6, 20, true);
      cv.setUint16(8, 0x0800, true);
      cv.setUint16(10, 0, true);
      cv.setUint16(12, stamp.time, true);
      cv.setUint16(14, stamp.date, true);
      cv.setUint32(16, crc, true);
      cv.setUint32(20, data.length, true);
      cv.setUint32(24, data.length, true);
      cv.setUint16(28, nameBytes.length, true);
      cv.setUint16(30, 0, true);
      cv.setUint16(32, 0, true);
      cv.setUint16(34, 0, true);
      cv.setUint16(36, 0, true);
      cv.setUint32(38, 0, true);
      cv.setUint32(42, offset, true);
      central.set(nameBytes, 46);
      centrals.push(central);

      offset += local.length + data.length;
      count += 1;
    }
    const centralSize = centrals.reduce((sum, part) => sum + part.length, 0);
    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(4, 0, true);
    ev.setUint16(6, 0, true);
    ev.setUint16(8, count, true);
    ev.setUint16(10, count, true);
    ev.setUint32(12, centralSize, true);
    ev.setUint32(16, offset, true);
    ev.setUint16(20, 0, true);
    return concatBytes([...locals, ...centrals, end]);
  }

  /* ============================ 写盘 ============================ */

  function zipNameNow() {
    const d = new Date();
    return '会话归档 ' + formatDate(d) + ' ' + pad2(d.getHours()) + '-' + pad2(d.getMinutes()) + '-' + pad2(d.getSeconds()) + '.zip';
  }

  function downloadBlob(blob, name) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.style.display = 'none';
    (document.body || document.documentElement).appendChild(anchor);
    anchor.click();
    setTimeout(() => {
      try { anchor.remove(); } catch { /* 忽略 */ }
      try { URL.revokeObjectURL(url); } catch { /* 忽略 */ }
    }, 3000);
  }

  /**
   * 保存文件列表。
   *
   * 架构决定（按项目负责人指示）：目录选择器在 content script 里可能被页面策略拦截，
   * 所以一律交给扩展自己的上下文执行：
   *   主路径：chrome.runtime.sendMessage → background.js → chrome.downloads.download()
   *           写到「下载目录/课程归档/<归档日期>/<文件>.md」，用 conflictAction:'uniquify' 防覆盖。
   *   兜底：扩展上下文不可用时，把整批打成一个 ZIP，用页面内 <a download> 触发下载。
   *
   * files: [{name, text}]
   */
  async function saveArchive(files, opts) {
    const options = opts || {};
    if (!Array.isArray(files) || !files.length) throw new Error('没有可保存的文件');
    const folder = String(options.folder || '课程归档').trim() || '课程归档';
    const dateFolder = String(options.dateFolder || formatDate(options.now instanceof Date ? options.now : new Date()));
    const runtime = typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.id;
    if (runtime && typeof chrome.runtime.sendMessage === 'function') {
      const res = await new Promise((resolve) => {
        try {
          chrome.runtime.sendMessage({
            type: 'archiveDownload',
            folder,
            dateFolder,
            files: files.map((file) => ({ name: file.name, text: file.text }))
          }, (response) => {
            if (chrome.runtime.lastError) resolve({ ok: false, error: chrome.runtime.lastError.message });
            else resolve(response || { ok: false, error: '后台没有返回结果' });
          });
        } catch (e) {
          resolve({ ok: false, error: String(e.message || e) });
        }
      });
      if (!res.ok) throw new Error('写盘失败：' + (res.error || '未知错误'));
      return {
        mode: 'downloads',
        dirName: res.dir || (folder + '/' + dateFolder + '/'),
        files: Array.isArray(res.files) ? res.files : files.map((file) => file.name)
      };
    }

    const zip = buildZip(files, { now: options.now });
    const name = options.zipName || zipNameNow();
    downloadBlob(new Blob([zip], { type: 'application/zip' }), name);
    return { mode: 'zip', zipName: name, files: files.map((file) => file.name) };
  }

  /* ============================ 归档总流程 ============================ */

  /**
   * 逐个读取会话并生成文件。
   * 返回 { files, errors, skipped }，每个会话都有明确结果，不静默丢。
   */
  /**
   * 用会话正文里的命名消息校正日期。
   *
   * 裁决规则（明确写死，避免两处证据打架时含糊）：
   *   1. 命名消息是用户亲手写进那个会话的标签，属于"会话自己的身份声明"，
   *      比侧栏标题（可能被 ChatGPT 自动生成或被截断）更可信 → **命名消息优先**。
   *   2. 但它只覆盖"日期"，不覆盖会话 id：id 永远来自 URL /c/<id>。
   *   3. 覆盖后把原值记进 namingConflict，供文件头和面板显示，不静默吞掉冲突。
   *   4. 没有命名消息时保持原判（标题 > 侧栏 time > 分组）。
   */
  /**
   * 从会话正文里提取命名日期。
   *
   * 真实 ChatGPT 侧栏里所有标题都可能显示成同一句通用文字（例如「回忆翻译要求」），
   * 标题完全不可用于判日期。可靠来源只有**带明确命名标记的消息**：
   *   - 用户第一条消息 `本会话命名为：YYYY-MM-DD-HH:MM`
   *   - 助手第一条回复复述/确认这个时间戳
   *
   * 安全约束（2026-11-02 审计后收紧）：
   *   1. **只认 `本会话命名为：` 这个精确字符串**。绝不从普通消息里抓裸日期。
   *      否则「今天几号？」→「今天是 2026-11-02」会被误当成会话日期（会写错文件）。
   *   2. 最多读前 maxMessages 条（默认 2）就停，不扫全量。
   *   3. 找不到命名消息 → 返回 null，由调用方降级为 `date_unknown`，而不是猜日期。
   *   4. **DOM 兜底路径**：页面上只渲染最近若干条时，「可见的第一条」可能是会话中间的
   *      某条。此时即使它含命名字符串也不够可信——必须由调用方传入
   *      `frontIsAuthoritative:false`，本函数会拒用它并返回 null（宁可未知，不可错判）。
   */
  function extractNamingFromFront(conversation, opts) {
    const options = opts || {};
    const maxMessages = Number.isFinite(options.maxMessages) ? options.maxMessages : 2;
    // DOM 可见内容不保证从会话真正的第一条开始
    if (conversation && conversation.source === 'dom' && options.frontIsAuthoritative !== true) {
      return null;
    }
    const messages = Array.isArray(conversation && conversation.messages) ? conversation.messages : [];
    let scanned = 0;
    for (const message of messages.slice(0, maxMessages)) {
      if (!message || (message.role !== 'user' && message.role !== 'assistant')) continue;
      scanned += 1;
      // 唯一允许的来源：消息里明确出现 `本会话命名为：`
      const parsed = parseNamingMessage(message.text);
      if (parsed) return { ...parsed, foundIn: message.role, scannedCount: scanned };
    }
    return null;
  }

  /**
   * 用会话正文里的命名消息校正日期。
   *
   * 裁决优先级（2026-11-02 按真实侧栏情况重写）：
   *   1. **首条消息里的命名时间戳**（用户第一句 / 助手第一句复述）——最高优先，
   *      因为标题在真实侧栏里可能是完全通用的文字。
   *   2. 标题里带日期（GAD 格式或裸日期）。
   *   3. 侧栏相对日期分组（今天/昨天/2026年10月）。
   *   4. 都没有 → `date_unknown`，单独列出，不静默归入或排除。
   * 会话 id 永远来自 URL，不受覆盖影响。
   */
  function applyNamingDate(record, conversation, opts) {
    if (!record || !conversation) return record;
    const naming = extractNamingFromFront(conversation, opts);
    if (!naming) return { ...record, dateUnknown: !record.date };
    const previous = {
      date: record.date || '',
      time: record.time || '',
      dateSource: record.dateSource || 'unknown'
    };
    const changed = previous.date !== naming.date || previous.time !== (naming.time || '');
    return {
      ...record,
      date: naming.date,
      time: naming.time || '',
      precision: naming.precision || 'minute',
      dateSource: 'naming',
      dateUnknown: false,
      namingFoundIn: naming.foundIn || '',
      namingConflict: changed
        ? { previousDate: previous.date, previousTime: previous.time, previousSource: previous.dateSource, namingDate: naming.date, namingTime: naming.time }
        : null
    };
  }

  /**
   * 通用标题检测。
   *
   * 真实 ChatGPT 侧栏里所有标题可能都是同一句「回忆翻译要求」。判定规则：
   *   - 最常见标题占比 > threshold（默认 70%），或
   *   - 不同标题数 <= 2 且样本 >= 5，或
   *   - **任一个标题重复出现 >= 2 次**（哪怕是混合场景，重复标题也不能拿来判日期）
   * 第三种是最保守的触发条件：只要看到重复，就认为标题不可靠，
   * 宁可多读几次首条消息，也不能把日期判错。
   */
  function detectGenericTitles(titles, opts) {
    const options = opts || {};
    const threshold = Number.isFinite(options.threshold) ? options.threshold : 0.7;
    const list = (Array.isArray(titles) ? titles : [])
      .map((t) => String(t == null ? '' : t).replace(/\s+/g, ' ').trim())
      .filter(Boolean);
    if (list.length < 2) {
      return { generic: false, topTitle: list[0] || '', topRatio: 0, distinct: list.length, total: list.length, repeated: 0 };
    }
    const counts = new Map();
    for (const title of list) counts.set(title, (counts.get(title) || 0) + 1);
    let topTitle = '';
    let topCount = 0;
    for (const [title, count] of counts) {
      if (count > topCount) { topTitle = title; topCount = count; }
    }
    const topRatio = topCount / list.length;
    const fewDistinct = counts.size <= 2 && list.length >= 5;
    const repeated = topCount >= 2;
    return {
      generic: topRatio > threshold || fewDistinct || repeated,
      topTitle,
      topRatio: Math.round(topRatio * 100) / 100,
      distinct: counts.size,
      total: list.length,
      repeated
    };
  }

  /**
   * 批量补读命名消息：对每个候选读**最前面 1-2 条消息**确定日期。
   *
   * 成本：每个候选一次 `/backend-api/conversation/<id>` 请求。真实侧栏标题
   * 普遍不可用时，这一步是必需的。用 maxLookups 限制单次上限（默认 60），
   * 超出则保留原判并在结果里说明。
   */
  async function enrichRecordsWithNaming(records, range, opts) {
    const options = opts || {};
    const maxLookups = Number.isFinite(options.maxLookups) ? options.maxLookups : 60;
    const maxMessages = Number.isFinite(options.maxMessages) ? options.maxMessages : 2;
    const normalized = range && range.start && range.end ? normalizeRange(range.start, range.end) : null;
    if (!normalized) throw new Error('日期范围无效');
    const list = Array.isArray(records) ? records : [];
    const generic = detectGenericTitles(list.map((r) => r.title));
    const output = [];
    let attempted = 0;
    let resolved = 0;
    let truncated = 0;
    for (let i = 0; i < list.length; i += 1) {
      const record = list[i];
      /*
       * 有通用标题问题时，不能再靠"当前日期"省请求——因为当前日期本身就不可信。
       * 此时对全部候选都读首条消息。标题正常时才走省请求路径。
       */
      const inRangeByCurrent = record.date ? recordInRange(record, normalized) : false;
      const needsLookup = generic.generic || !record.date || inRangeByCurrent;
      if (!needsLookup) { output.push(record); continue; }
      if (attempted >= maxLookups) { truncated += 1; output.push(record); continue; }
      let conversation = null;
      try {
        conversation = await fetchConversation(record.id, { ...options, maxMessages });
      } catch { /* 读不到就保持原判 */ }
      attempted += 1;
      if (conversation && conversation.messages && conversation.messages.length) {
        const next = applyNamingDate(record, conversation, { now: options.now, maxMessages });
        if (next.dateSource === 'naming') resolved += 1;
        output.push(next);
      } else {
        output.push(record);
      }
      if (typeof options.onProgress === 'function') {
        options.onProgress({ index: i, total: list.length, record });
      }
    }
    return { records: output, attempted, resolved, truncated, genericTitles: generic };
  }

  /**
   * 纯函数版：用已拿到的 bodies（id -> 会话对象）补命名日期。
   * 测试和无网络场景用这个，行为与 enrichRecordsWithNaming 一致。
   */
  function applyNamingDatesFromBodies(records, bodiesById, opts) {
    const map = bodiesById || {};
    return (Array.isArray(records) ? records : []).map((record) =>
      map[record.id] ? applyNamingDate(record, map[record.id], opts) : record);
  }

  async function archiveRecords(records, opts) {
    const options = opts || {};
    const list = Array.isArray(records) ? records : [];
    const files = [];
    const errors = [];
    const skipped = [];
    /*
     * 格式兼容：目标目录已有旧 writer 格式归档时，沿用旧格式，避免同一个课程目录
     * 出现两种命名/头部风格。existingEntries 由面板传入（文件名 + 已读文本）。
     */
    const existingEntries = Array.isArray(options.existingEntries) ? options.existingEntries : [];
    const legacyMode = options.legacyFormat === true || detectLegacyFormat(existingEntries);
    const existingIds = collectExistingConversationIds(existingEntries);
    for (let i = 0; i < list.length; i += 1) {
      const record = list[i];
      if (existingIds.has(record.id)) {
        skipped.push({
          id: record.id,
          title: record.title,
          code: 'already_archived',
          message: '该会话已在目标目录归档过（按会话 id 识别），本次跳过以免重复'
        });
        continue;
      }
      const notify = (phase, extra) => {
        if (typeof options.onProgress === 'function') {
          options.onProgress({ index: i, total: list.length, record, phase, ...(extra || {}) });
        }
      };
      notify('reading');
      let conversation = null;
      let apiError = '';
      try {
        conversation = await fetchConversation(record.id, options);
      } catch (e) {
        apiError = String(e && e.message || e);
      }
      if (!conversation || !conversation.messages || !conversation.messages.length) {
        const dom = scrapeVisibleConversation(options.document);
        if (dom && dom.id === record.id && dom.messages.length) {
          conversation = { ...dom, fallbackReason: apiError || 'api_empty' };
        }
      }
      if (!conversation || !conversation.messages || !conversation.messages.length) {
        errors.push({
          id: record.id,
          title: record.title,
          code: 'conversation_unreadable',
          message: apiError
            ? '读取失败：' + apiError + '；且该会话不是当前打开的页面，无法用页面内容兜底'
            : '没有读取到消息（该会话不是当前打开的页面）'
        });
        notify('error');
        continue;
      }
      const assessment = assessCompleteness(conversation);
      const gate = checkSaveGate(assessment, !!options.allowIncomplete);
      if (!gate.ok) {
        skipped.push({ id: record.id, title: record.title, code: gate.code, message: gate.message });
        notify('skipped');
        continue;
      }
      /*
       * 内容已经读到了，顺手用会话正文里的命名消息校正日期（命名消息优先）。
       * 注意：若是 DOM 兜底内容，frontIsAuthoritative=false —— 页面只渲染最近若干条时，
       * 「可见第一条」可能不是会话真正的第一条，不能据此定日期。
       */
      const resolvedRecord = applyNamingDate(record, conversation, {
        now: options.now,
        frontIsAuthoritative: conversation.source !== 'dom'
      });
      const text = legacyMode
        ? renderLegacyMarkdown(resolvedRecord, conversation, { now: options.now })
        : renderMarkdown(resolvedRecord, conversation, { assessment, now: options.now });
      files.push({ name: '', text, record: resolvedRecord, assessment, warning: gate.warning || '', format: legacyMode ? 'legacy' : 'new' });
      notify('done');
    }
    // 文件名必须在"确定哪些会话真的产出文件"之后再分配：
    // 否则中间有会话被跳过时，后面的文件名会和记录错位。
    const names = legacyMode
      ? legacyUniqueNames(files.map((file) => file.record))
      : uniqueFileNames(files.map((file) => file.record));
    files.forEach((file, index) => { file.name = names[index]; });
    return { files, errors, skipped, legacyFormat: legacyMode };
  }

  /** 旧格式批内重名处理：`YYYY-MM-DD 周X HH-mm (2).md`。 */
  function legacyUniqueNames(records) {
    const used = new Set();
    return (Array.isArray(records) ? records : []).map((record) => {
      let name = buildLegacyFileName(record) || buildFileName(record);
      if (!used.has(name)) { used.add(name); return name; }
      const stem = name.replace(/\.md$/i, '');
      let index = 2;
      while (used.has(stem + ' (' + index + ').md')) index += 1;
      name = stem + ' (' + index + ').md';
      used.add(name);
      return name;
    });
  }

  /**
   * 向后台询问「课程归档」目录里已有哪些 .md（用于旧格式检测与去重）。
   * 后台只能看到浏览器下载记录，看不到磁盘目录；因此这里取最近下载过、
   * 文件名符合归档命名的条目。取不到时返回空数组，不影响保存。
   */
  async function listExistingArchiveEntries() {
    const runtime = typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.id;
    if (!runtime || typeof chrome.runtime.sendMessage !== 'function') return { entries: [], source: 'none' };
    const res = await new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage({ type: 'archiveListExisting' }, (response) => {
          if (chrome.runtime.lastError) resolve({ ok: false, error: chrome.runtime.lastError.message });
          else resolve(response || { ok: false });
        });
      } catch (e) { resolve({ ok: false, error: String(e.message || e) }); }
    });
    if (!res || !res.ok || !Array.isArray(res.files)) return { entries: [], source: 'none' };
    return { entries: res.files, source: 'downloads-history' };
  }

  /* ============================ 面板 UI ============================ */

  function uiEl(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = text;
    return node;
  }

  function defaultRange() {
    const end = new Date();
    const start = new Date(end.getTime());
    start.setDate(start.getDate() - 7);
    return { start: formatDate(start), end: formatDate(end) };
  }

  /**
   * 在面板 body 里挂载「归档」区块。
   * 返回一个控制器，便于面板调试和测试。
   */
  function mountSection(body, opts) {
    const options = opts || {};
    const initial = defaultRange();
    const state = { records: [], filtered: null, busy: false };

    const sec = uiEl('div', 'sec');
    sec.append(uiEl('h4', '', '归档'));

    const rangeRow = uiEl('div', 'arcRange');
    const startInput = uiEl('input');
    startInput.type = 'date';
    startInput.value = initial.start;
    startInput.title = '开始日期（含）';
    const endInput = uiEl('input');
    endInput.type = 'date';
    endInput.value = initial.end;
    endInput.title = '结束日期（含）';
    rangeRow.append(uiEl('span', 'arcRangeLabel', '从'), startInput, uiEl('span', 'arcRangeLabel', '到'), endInput);

    const actionRow = uiEl('div', 'row');
    const btnSearch = uiEl('button', 'primary', '检索会话');
    const btnSelectAll = uiEl('button', '', '全选');
    actionRow.append(btnSearch, btnSelectAll);

    const hint = uiEl('div', 'hint', '读取侧栏里的会话名和日期，不会发送或修改任何会话。');
    const countLine = uiEl('div', 'hint', '');
    const list = uiEl('div', 'arcList');
    const empty = uiEl('div', 'hint', '还没有检索结果');
    list.appendChild(empty);

    const optionRow = uiEl('div', 'arcOptions');
    const chkAllowIncomplete = uiEl('input');
    chkAllowIncomplete.type = 'checkbox';
    const chkUnknown = uiEl('input');
    chkUnknown.type = 'checkbox';
    optionRow.append(
      uiEl('label', 'arcCheck', '允许保存不完整内容（会标注）'),
      chkAllowIncomplete,
      uiEl('label', 'arcCheck', '包含日期未知'),
      chkUnknown
    );

    const saveRow = uiEl('div', 'row');
    const btnSave = uiEl('button', 'primary', '保存到文件夹');
    const btnClear = uiEl('button', '', '清空');
    saveRow.append(btnSave, btnClear);
    const status = uiEl('div', 'hint', '');
    status.style.whiteSpace = 'pre-line';

    sec.append(rangeRow, actionRow, hint, countLine, list, optionRow, saveRow, status);

    const readRange = () => {
      const normalized = normalizeRange(startInput.value, endInput.value);
      if (!normalized) return null;
      startInput.value = normalized.start;
      endInput.value = normalized.end;
      return normalized;
    };

    const setStatus = (text) => { status.textContent = text; };

    const renderList = () => {
      list.innerHTML = '';
      if (!state.records.length) {
        list.appendChild(uiEl('div', 'hint', '没有符合范围的会话'));
        return;
      }
      for (const record of state.records) {
        const row = uiEl('div', 'arcRow');
        const box = uiEl('input');
        box.type = 'checkbox';
        box.checked = !!record.checked;
        box.onchange = () => { record.checked = box.checked; };
        const main = uiEl('div', 'arcMain');
        const titleLine = uiEl('div', 'arcTitle', record.title);
        const metaParts = [];
        metaParts.push(record.date ? (record.date + (record.time ? ' ' + record.time : '')) : '日期未知');
        if (record.precision === 'month') metaParts.push('月精度');
        if (record.course) metaParts.push(record.course + (record.part ? ' · 第' + record.part + '次' : ''));
        metaParts.push('id ' + record.id.slice(-8));
        const meta = uiEl('div', 'arcMeta', metaParts.join('　'));
        main.append(titleLine, meta);
        if (record.dateSource && record.dateSource !== 'unknown') {
          main.appendChild(uiEl('span', 'arcBadge', record.dateSource));
        }
        row.append(box, main);
        list.appendChild(row);
      }
    };

    const renderCounts = (result) => {
      if (!result) { countLine.textContent = ''; return; }
      countLine.textContent = '匹配 ' + result.counts.matched + ' 个 · 范围外 ' + result.counts.excluded +
        ' 个 · 日期未知 ' + result.counts.unknown + ' 个';
    };

    btnSearch.onclick = async () => {
      if (state.busy) return;
      const range = readRange();
      if (!range) { setStatus('日期范围无效，请检查开始/结束日期。'); return; }
      state.busy = true;
      btnSearch.disabled = true;
      setStatus('正在读取侧栏会话列表…');
      try {
        const raw = await collectSidebarRecords(document, {
          onCount: (n) => setStatus('正在读取侧栏会话列表…已加载 ' + n + ' 条')
        });
        const built = buildRecords(raw, { now: new Date() });
        // 当前打开会话的正文命名消息（若用户正开着它）
        const naming = readVisibleNamingMessage(document);
        if (naming) {
          const target = built.find((record) => record.id === naming.conversationId);
          if (target) {
            target.date = naming.date;
            target.time = naming.time;
            target.precision = naming.precision;
            target.dateSource = 'naming';
            target.dateUnknown = false;
          }
        }

        /*
         * 关键一步：真实侧栏标题可能全是同一句通用文字，标题判不出日期。
         * 这时必须逐个打开候选会话、只读最前面 1-2 条消息来拿命名时间戳。
         * 有网络请求，所以给进度提示和单次上限。
         */
        setStatus('正在逐个读取会话首条消息以确定日期…（标题可能不可用）');
        const enriched = await enrichRecordsWithNaming(built, range, {
          maxLookups: 80,
          maxMessages: 2,
          now: new Date(),
          onProgress: (p) => setStatus('正在读取 ' + (p.index + 1) + '/' + p.total + ' 个会话的首条消息…（标题可能不可用）')
        });

        state.genericTitles = enriched.genericTitles;
        state.allRecords = enriched.records;
        const result = filterByDateRange(enriched.records, range, { includeUnknown: chkUnknown.checked });
        state.filtered = result;
        state.records = result.matched.map((record) => ({ ...record, checked: false }));
        renderList();
        renderCounts(result);
        const bits = [];
        if (enriched.genericTitles && enriched.genericTitles.generic) {
          bits.push('检测到标题重复（如「' + enriched.genericTitles.topTitle + '」占 ' +
            Math.round(enriched.genericTitles.topRatio * 100) + '%），已改用会话首条消息判日期');
        }
        bits.push('侧栏 ' + raw.length + ' 条，按日期范围筛出 ' + state.records.length + ' 条');
        if (enriched.resolved) bits.push(enriched.resolved + ' 个日期来自首条消息');
        if (enriched.truncated) bits.push(enriched.truncated + ' 个超出单次读取上限，日期未定');
        bits.push('勾选后点“保存到文件夹”。');
        setStatus(bits.join('；'));
      } catch (e) {
        setStatus('检索失败：' + String(e && e.message || e));
      } finally {
        state.busy = false;
        btnSearch.disabled = false;
      }
    };

    chkUnknown.onchange = () => {
      if (!state.allRecords) return;
      const range = readRange();
      if (!range) return;
      const result = filterByDateRange(state.allRecords, range, { includeUnknown: chkUnknown.checked });
      state.filtered = result;
      state.records = result.matched.map((record) => ({ ...record, checked: false }));
      renderList();
      renderCounts(result);
    };

    btnSelectAll.onclick = () => {
      const allChecked = state.records.length > 0 && state.records.every((record) => record.checked);
      state.records.forEach((record) => { record.checked = !allChecked; });
      renderList();
    };

    btnClear.onclick = () => {
      state.records = [];
      state.allRecords = null;
      state.filtered = null;
      list.innerHTML = '';
      list.appendChild(uiEl('div', 'hint', '还没有检索结果'));
      countLine.textContent = '';
      setStatus('');
    };

    btnSave.onclick = async () => {
      if (state.busy) return;
      const selected = state.records.filter((record) => record.checked);
      if (!selected.length) { setStatus('先勾选要保存的会话。'); return; }
      state.busy = true;
      btnSave.disabled = true;
      setStatus('正在读取 ' + selected.length + ' 个会话…');
      try {
        // 先问后台：目标目录里已有哪些归档文件？用于判断旧格式 + 按会话 id 去重。
        const existing = await listExistingArchiveEntries();
        const result = await archiveRecords(selected, {
          allowIncomplete: chkAllowIncomplete.checked,
          existingEntries: existing.entries,
          onProgress: (p) => {
            if (p.phase === 'reading') setStatus('正在读取第 ' + (p.index + 1) + '/' + p.total + ' 个：' + p.record.title);
          }
        });
        const lines = [];
        if (result.files.length) {
          setStatus('正在写入 ' + result.files.length + ' 个文件…');
          const range = readRange();
          const saved = await saveArchive(result.files, {
            folder: '课程归档',
            dateFolder: range ? range.start + '_到_' + range.end : undefined,
            onProgress: (p) => setStatus('正在写入 ' + p.index + '/' + p.total + '：' + p.name)
          });
          if (saved.mode === 'downloads') {
            lines.push('已保存 ' + saved.files.length + ' 个 Markdown 到：' + saved.dirName);
            lines.push('位置在浏览器下载目录下（下载/课程归档/…），可在 chrome://downloads 查看。');
          } else {
            lines.push('已下载 ZIP：' + saved.zipName + '（扩展后台不可用时的兜底，需自行解压）');
          }
        }
        if (result.skipped.length) {
          lines.push('未保存 ' + result.skipped.length + ' 个：' +
            result.skipped.map((item) => item.title + '（' + item.message + '）').join('；'));
        }
        if (result.errors.length) {
          lines.push('读取失败 ' + result.errors.length + ' 个：' +
            result.errors.map((item) => item.title + '（' + item.message + '）').join('；'));
        }
        if (!lines.length) lines.push('没有生成任何文件。');
        if (result.files.some((file) => file.assessment.level !== 'full')) {
          lines.push('注意：本次有文件带“内容不完整”警告，文件头已写明。');
        }
        setStatus(lines.join('\n'));
        // 保存成功的条目打勾取消，避免重复保存
        for (const record of state.records) record.checked = false;
        renderList();
      } catch (e) {
        setStatus('保存失败：' + String(e && e.message || e));
      } finally {
        state.busy = false;
        btnSave.disabled = false;
      }
    };

    if (body && body.appendChild) body.appendChild(sec);
    return {
      section: sec,
      state,
      search: () => btnSearch.onclick(),
      save: () => btnSave.onclick(),
      setRecords: (records) => { state.records = records; renderList(); }
    };
  }

  /* ============================ 导出 ============================ */

  const api = {
    version: ARCHIVE_VERSION,
    // 解析
    parseGadTitle,
    parseTitle,
    parseNamingMessage,
    parseLooseDate,
    isDateGroupLabel,
    resolveDateInfo,
    conversationId,
    // 列表
    buildRecords,
    dedupeById,
    normalizeRange,
    filterByDateRange,
    // 文件名
    safeName,
    buildFileName,
    uniqueFileNames,
    // 旧格式兼容
    buildLegacyFileName,
    renderLegacyMarkdown,
    detectLegacyFormat,
    collectExistingConversationIds,
    legacyUniqueNames,
    listExistingArchiveEntries,
    // 内容
    normalizeConversationPayload,
    applyNamingDate,
    applyNamingDatesFromBodies,
    enrichRecordsWithNaming,
    extractNamingFromFront,
    detectGenericTitles,
    assessCompleteness,
    checkSaveGate,
    renderMarkdown,
    // 写盘
    buildZip,
    saveArchive,
    // 总流程
    archiveRecords,
    // 页面读取
    readSidebarItems,
    collectSidebarRecords,
    readVisibleNamingMessage,
    scrapeVisibleConversation,
    fetchConversation,
    // UI
    mountSection
  };

  globalThis.GadArchive = Object.freeze(api);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
