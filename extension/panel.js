/*
 * GPT Audio Dictation —— 界面侧（ISOLATED world）
 *
 * 只做三件事：画一个悬浮面板、存参数、和页面侧通信。
 * 面板特性：
 *   - Shadow DOM 隔离样式，不会被 ChatGPT 的 CSS 影响，也不影响它
 *   - 默认是收起的小条，不挡视线；点开才是完整面板
 *   - 可拖动、位置记忆、窗口缩放自动夹回可视区
 *   - 自动避让底部输入框：只要和 composer 重叠就自动往上挪，
 *     保证「麦克风 / 转录并发送 / 发送」这些按钮永远点得到
 */
(() => {
  if (window.__gadPanel) return;
  window.__gadPanel = true;

  const ISOLATED = typeof chrome !== 'undefined' && chrome.runtime && chrome.runtime.id;

  /* ----------------------------- 参数存取 ----------------------------- */

  const DEFAULTS = {
    preset: 'normal',
    maxSegmentSec: 300,
    minSpeechSec: 4,
    minSegmentSec: 6,
    pauseFloorMs: 0,
    pauseCeilMs: 0,
    silenceThreshold: 0,
    bias: 0,
    floorFactor: 2.5,
    loudCap: 0.45,
    smooth: 0.08,
    settleAfterSendMs: 400,
    autoStart: false,
    // 只有用户亲手点过"自动开始"开关，才记为 true。
    // 旧版默认是开，任何人只要动过任意一个设置，就会把 autoStart:true 一起存进去；
    // 光改 DEFAULTS 会被已存的值盖掉，升级后照样自己开录。用这个标记判断"用户真的选过"。
    autoStartPreferenceSet: false,
    autoJumpNewSession: true,
    autoInjectPrompt: true,
    newSessionPrompt: '回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。',
    saveSubDir: '原始录音',
    autoOpenTab: false,
    finishOnStop: true,
    saveEnabled: true,
    savePreferenceSet: false,
    installerPrompted: false,
    saveCourse: '',
    language: 'zh-CN',
    uiLanguage: 'zh-CN',
    firstRunDone: false,
    guideOpen: true,
    showPanel: true,
    mini: true,
    pos: null
  };

  const store = {
    async load() {
      if (ISOLATED) {
        try {
          const r = await chrome.storage.local.get('gad');
          const saved = (r && r.gad) ? r.gad : {};
          // Older installs stored the old default false without a first-run marker.
          // Turn saving on once for the guided setup; later explicit off choices persist.
          const explicitSaveChoice = saved.savePreferenceSet === true;
          // 旧版默认自动开始，凡是存过设置的人都被动带上了 autoStart:true。
          // 没有 autoStartPreferenceSet 标记的，一律按"没选过"处理，回到默认的停。
          const explicitAutoStart = saved.autoStartPreferenceSet === true;
          return {
            ...DEFAULTS,
            ...saved,
            saveEnabled: explicitSaveChoice ? !!saved.saveEnabled : true,
            autoStart: explicitAutoStart ? !!saved.autoStart : false
          };
        } catch { return { ...DEFAULTS }; }
      }
      try {
        const saved = JSON.parse(localStorage.getItem('gad.settings') || '{}');
        const explicitSaveChoice = saved.savePreferenceSet === true;
        const explicitAutoStart = saved.autoStartPreferenceSet === true;
        return {
          ...DEFAULTS,
          ...saved,
          saveEnabled: explicitSaveChoice ? !!saved.saveEnabled : true,
          autoStart: explicitAutoStart ? !!saved.autoStart : false
        };
      }
      catch { return { ...DEFAULTS }; }
    },
    async save(v) {
      if (ISOLATED) {
        try { await chrome.storage.local.set({ gad: v }); return { ok: true }; }
        catch (e) { return { ok: false, error: String(e.message || e) }; }
      }
      try { localStorage.setItem('gad.settings', JSON.stringify(v)); return { ok: true }; }
      catch (e) { return { ok: false, error: String(e.message || e) }; }
    }
  };

  let settings = { ...DEFAULTS };
  let status = { state: 'off', stateLabel: '连接中', running: false, rms: 0, threshold: 0.012 };
  let gotStatus = false;
  const logs = [];
  const SAVE_QUEUE_KEY = 'gadSaveQueue';
  const SAVE_QUEUE_ITEM_PREFIX = 'gadSaveJob:';
  const SAVE_RETRY_BASE_MS = 2000;
  const SAVE_RETRY_MAX_MS = 60000;
  let saveOutbox = [];
  let pageSaveState = { waitingIds: [], blockedIds: [] };
  let lastPersistedOutboxIds = new Set();
  let saveQueueLoaded = false;
  let savePumpBusy = false;
  let lastSaveError = '';
  let savePersistChain = Promise.resolve();

  const saveSettings = async () => {
    const result = await store.save(settings);
    if (result && result.ok === false) {
      lastSaveError = '设置保存失败：' + result.error;
      log_local('warn', lastSaveError);
    }
    return result;
  };
  const send = (type, payload) => {
    try { window.postMessage({ __gad: true, dir: 'main', type, payload }, location.origin); } catch { /* 忽略 */ }
  };
  async function callWriter(path, options = {}) {
    // Content scripts cannot reliably fetch loopback services (CORS / Private Network
    // Access may reject the request before the writer can return its real error).
    // Route all writer calls through the extension service worker instead.
    const routes = {
      '/choose-root': { type: 'writerChooseRoot', timeoutMs: options.timeoutMs || 125000 },
      '/root-candidates': { type: 'writerRootCandidates', timeoutMs: options.timeoutMs || 8000 },
      '/set-root': { type: 'writerSetRoot', timeoutMs: options.timeoutMs || 8000 },
      '/save': { type: 'relaySave', timeoutMs: options.timeoutMs || 25000 }
    };
    const route = routes[path];
    if (!route) throw new Error('不支持的本地服务请求：' + path);
    return new Promise((resolve, reject) => {
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(new Error('本地服务请求超时：' + path));
      }, route.timeoutMs);
      try {
        chrome.runtime.sendMessage({ type: route.type, payload: options.body, rootDir: options.body && options.body.rootDir }, (res) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
          else if (!res) reject(new Error('扩展后台未返回本地服务结果'));
          else resolve(res);
        });
      } catch (e) {
        clearTimeout(timer);
        reject(e);
      }
    });
  }

  async function loadSaveOutbox() {
    if (!ISOLATED) { saveQueueLoaded = true; return; }
    try {
      const r = await chrome.storage.local.get(null);
      const list = [];
      if (r && Array.isArray(r[SAVE_QUEUE_KEY])) list.push(...r[SAVE_QUEUE_KEY]);
      for (const [key, value] of Object.entries(r || {})) {
        if (key.startsWith(SAVE_QUEUE_ITEM_PREFIX) && value && typeof value === 'object') {
          list.push(value);
        }
      }
      const inMemory = saveOutbox.slice();
      const merged = [...list, ...inMemory];
      const byId = new Map();
      for (const raw of merged) {
        if (!raw || !raw.payload || !raw.payload.assistant) continue;
        const id = String(raw.saveId || saveIdFor(raw.payload));
        const job = {
          ...raw,
          saveId: id,
          attempts: Number(raw.attempts) || 0,
          nextAt: Number(raw.nextAt) || 0,
          blocked: raw.blocked === true,
          lastError: String(raw.lastError || ''),
          updatedAt: Number(raw.updatedAt) || Number(raw.queuedAt) || 0
        };
        const previous = byId.get(id);
        if (!previous) {
          byId.set(id, job);
          continue;
        }
        const sameAnswer = String(previous.payload.assistant || '') === String(job.payload.assistant || '');
        if (!sameAnswer) {
          const winner = job.updatedAt >= previous.updatedAt ? job : previous;
          winner.blocked = true;
          winner.lastError = '同一保存编号存在不同回答；已暂停，避免静默覆盖';
          byId.set(id, winner);
        } else if (job.updatedAt >= previous.updatedAt) {
          byId.set(id, job);
        }
      }
      saveOutbox = Array.from(byId.values());
      lastPersistedOutboxIds = new Set(saveOutbox.map((job) => job.saveId));
    } catch (e) {
      lastSaveError = '待保存队列读取失败：' + String(e.message || e);
      log_local('warn', lastSaveError);
    }
    saveQueueLoaded = true;
  }

  function persistSaveOutbox() {
    if (!ISOLATED) return;
    let persisted = true;
    savePersistChain = savePersistChain.then(async () => {
      try {
        const snapshot = saveOutbox.slice();
        const currentIds = new Set(snapshot.map((job) => job.saveId));
        const values = {};
        for (const job of snapshot) values[SAVE_QUEUE_ITEM_PREFIX + job.saveId] = job;
        await chrome.storage.local.set(values);
        const staleKeys = Array.from(lastPersistedOutboxIds)
          .filter((id) => !currentIds.has(id))
          .map((id) => SAVE_QUEUE_ITEM_PREFIX + id);
        if (staleKeys.length) await chrome.storage.local.remove(staleKeys);
        lastPersistedOutboxIds = currentIds;
        await chrome.storage.local.remove(SAVE_QUEUE_KEY);
      } catch (e) {
        persisted = false;
        lastSaveError = '待保存队列写入失败：' + String(e.message || e);
        log_local('warn', lastSaveError);
      }
    });
    return savePersistChain.then(() => persisted);
  }

  function saveIdFor(payload) {
    if (payload && payload.saveId) return String(payload.saveId);
    const seed = [payload && payload.url, payload && payload.sessionStart, payload && payload.index, payload && payload.at].join('|');
    return 's-' + seed.replace(/[^a-zA-Z0-9._:-]/g, '_').slice(0, 140);
  }

  async function enqueueSave(payload) {
    if (!payload || !payload.assistant) return;
    const saveId = saveIdFor(payload);
    let job = saveOutbox.find((item) => item.saveId === saveId);
    if (!job) {
      job = {
        saveId,
        payload: { ...payload, saveId },
        attempts: 0,
        nextAt: 0,
        blocked: false,
        lastError: '',
        queuedAt: Date.now(),
        updatedAt: Date.now()
      };
      saveOutbox.push(job);
      lastSaveError = '';
    } else {
      const oldAssistant = String(job.payload && job.payload.assistant || '');
      const newAssistant = String(payload.assistant || '');
      if (oldAssistant && newAssistant && newAssistant !== oldAssistant) {
        job.blocked = true;
        job.lastError = '同一保存编号收到了不同回答；已暂停，避免静默覆盖';
        job.updatedAt = Date.now();
        const persisted = await persistSaveOutbox();
        send('saveResult', {
          saveId,
          ok: false,
          error: persisted ? job.lastError : job.lastError + '；队列写入也失败',
          retryable: false,
          blocked: true
        });
        render();
        return;
      }
      if (newAssistant.length > oldAssistant.length) {
        job.payload = {
          ...job.payload,
          ...payload,
          course: payload.course || (job.payload && job.payload.course) || '',
          saveId
        };
        if (!job.blocked) {
          job.attempts = 0;
          job.nextAt = 0;
        }
        job.lastError = '';
        job.updatedAt = Date.now();
      }
    }
    const persisted = await persistSaveOutbox();
    if (!persisted) {
      send('saveResult', { saveId, ok: false, error: lastSaveError || '待保存队列暂时无法写入扩展存储', retryable: true });
      render();
      return;
    }
    render();
    pumpSaveQueue();
  }

  async function pumpSaveQueue() {
    if (!ISOLATED || !saveQueueLoaded || savePumpBusy || !saveOutbox.length) return;
    const job = saveOutbox.find((item) => !item.blocked);
    if (!job) { render(); return; }
    const waitMs = Math.max(0, Number(job.nextAt) - Date.now());
    if (waitMs > 0) { setTimeout(pumpSaveQueue, Math.min(waitMs, SAVE_RETRY_MAX_MS)); return; }
    savePumpBusy = true;
    let response = null;
    try {
      response = await callWriter('/save', { method: 'POST', body: job.payload, timeoutMs: 15000 });
      if (!response || !response.ok) {
        const error = new Error((response && response.error) || '本地落盘服务未确认写入');
        error.retryable = !response || response.retryable !== false;
        error.status = response && response.status;
        error.code = response && response.code;
        throw error;
      }
      saveOutbox = saveOutbox.filter((item) => item.saveId !== job.saveId);
      const persisted = await persistSaveOutbox();
      if (!persisted) {
        job.blocked = false;
        job.nextAt = Date.now() + SAVE_RETRY_BASE_MS;
        job.lastError = '回答已经写入，但待保存队列记录暂时无法清除';
        saveOutbox.push(job);
        throw new Error(job.lastError);
      }
      lastSaveError = '';
      lastSaveOkAt = Date.now();
      send('saveResult', { saveId: job.saveId, ok: true, file: response.file, duplicate: !!response.duplicate });
      log_local('ok', (response.duplicate ? '已确认原有落盘' : '已落盘') + ' → ' + (response.file || ''));
    } catch (e) {
      if (e && (e.retryable === false || Number(e.status) === 400)) {
        job.blocked = true;
        job.lastError = String(e.message || e);
        job.updatedAt = Date.now();
        await persistSaveOutbox();
        lastSaveError = '落盘失败（不可重试）：' + String(e.message || e);
        send('saveResult', { saveId: job.saveId, ok: false, error: lastSaveError, retryable: false, blocked: true });
        log_local('error', lastSaveError);
        savePumpBusy = false;
        render();
        if (saveOutbox.some((item) => !item.blocked)) setTimeout(pumpSaveQueue, 50);
        return;
      }
      job.attempts = Math.min(20, (Number(job.attempts) || 0) + 1);
      job.nextAt = Date.now() + Math.min(SAVE_RETRY_MAX_MS, SAVE_RETRY_BASE_MS * (2 ** Math.min(job.attempts - 1, 5)));
      lastSaveError = '落盘待重试（第 ' + job.attempts + ' 次）：' + String(e.message || e);
      job.lastError = lastSaveError;
      job.updatedAt = Date.now();
      await persistSaveOutbox();
      send('saveResult', { saveId: job.saveId, ok: false, error: lastSaveError, retryable: true });
      log_local('warn', lastSaveError);
    } finally {
      savePumpBusy = false;
      render();
      if (saveOutbox.some((item) => !item.blocked)) setTimeout(pumpSaveQueue, 50);
    }
  }

  /* ----------------------------- 阈值镜像 ----------------------------- */
  // 与页面侧保持同一套换算，这样拖滑块时数字立刻变，不用等回包。
  const lerp = (a, b, t) => a + (b - a) * t;
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  /* ----------------------------- 面板骨架 ----------------------------- */

  const CSS = `
    :host { all: initial; }
    * { box-sizing: border-box; }
    .root {
      position: fixed; z-index: 2147483000;
      top: 72px; right: 16px;
      width: min(348px, calc(100vw - 24px));
      max-height: min(620px, calc(100vh - 120px));
      display: flex; flex-direction: column;
      background: #14171d; color: #e8ebf0;
      border: 1px solid #2b3140; border-radius: 12px;
      box-shadow: 0 12px 34px rgba(0,0,0,.48);
      font: 13px/1.5 "Microsoft YaHei UI","Segoe UI",system-ui,-apple-system,sans-serif;
      overflow: hidden; pointer-events: auto;
    }
    .root.mini { width: auto; max-width: min(300px, calc(100vw - 24px)); }
    .head {
      display: flex; align-items: center; gap: 7px;
      padding: 8px 10px; background: #1a1e26; border-bottom: 1px solid #2b3140;
      cursor: grab; user-select: none; flex: none;
    }
    .head.dragging { cursor: grabbing; }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: #6b7280; flex: none; }
    .dot.recording { background: #3ddc84; box-shadow: 0 0 0 3px rgba(61,220,132,.18); }
    .dot.finalizing { background: #4f8cff; box-shadow: 0 0 0 3px rgba(79,140,255,.18); }
    .dot.idle, .dot.waiting { background: #ffb020; }
    .dot.error, .dot.off { background: #ff5d5d; }
    .name { font-weight: 600; font-size: 12.5px; white-space: nowrap; }
    .state { font-size: 12px; color: #97a0b0; white-space: nowrap; }
    .grow { flex: 1 1 auto; min-width: 0; }
    .iconbtn {
      all: unset; cursor: pointer; width: 20px; height: 20px; line-height: 18px;
      text-align: center; border-radius: 5px; color: #8b93a3; font-size: 14px; flex: none;
    }
    .iconbtn:hover { background: #2a3140; color: #e8ebf0; }
    .body { padding: 10px; overflow: auto; display: flex; flex-direction: column; gap: 9px; }
    .root.mini .body { display: none; }
    .minirow { display: none; align-items: center; gap: 7px; padding: 0 10px 8px; }
    .root.mini .minirow { display: flex; }
    .chip { font-size: 11px; white-space: nowrap; color: #7d8698; }
    .chip.on { color: #3ddc84; }
    .chip.off { color: #ff5d5d; }
    .chip.warn { color: #ffb020; }
    .minimeter { flex: 1 1 auto; height: 5px; background: #0b0e13; border-radius: 99px; overflow: hidden; min-width: 54px; position: relative; }
    .minimeter i { display: block; height: 100%; width: 0; background: linear-gradient(90deg,#2563eb,#3ddc84); }
    button {
      all: unset; cursor: pointer; text-align: center; padding: 7px 9px; border-radius: 8px;
      background: #232936; border: 1px solid #303748; color: #e8ebf0; font-size: 12.5px;
    }
    button:hover { background: #2b3242; }
    .chipbutton { padding: 0; border: 0; background: transparent; font-size: 11px; white-space: nowrap; }
    .chipbutton:hover { background: transparent; }
    button:disabled { opacity: .38; cursor: default; }
    button.primary { background: #1d4ed8; border-color: #1d4ed8; font-weight: 600; }
    button.primary:hover { background: #2563eb; }
    button.tiny { padding: 3px 8px; font-size: 11.5px; border-radius: 6px; }
    .row { display: flex; gap: 7px; }
    .row > button { flex: 1 1 0; }
    .meter { position: relative; height: 18px; background: #0b0e13; border: 1px solid #2b3140; border-radius: 6px; overflow: hidden; }
    .meter .fill { position: absolute; inset: 0 auto 0 0; width: 0; background: linear-gradient(90deg,#1f6feb,#3ddc84); transition: width .08s linear; }
    .meter .thr { position: absolute; top: 0; bottom: 0; width: 2px; background: #ffb020; }
    .meter .seg { position: absolute; top: 0; bottom: 0; right: 0; width: 0; background: rgba(255,93,93,.2); border-left: 1px solid rgba(255,93,93,.5); }
    .sub { display: flex; justify-content: space-between; font-size: 11px; color: #7d8698; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 10px; font-size: 11.5px; }
    .grid div { display: flex; justify-content: space-between; gap: 6px; }
    .grid b { font-weight: 500; color: #7d8698; }
    .grid span { font-variant-numeric: tabular-nums; }
    .why { font-size: 11.5px; color: #ffb020; min-height: 15px; }
    .sec { border: 1px solid #2b3140; border-radius: 9px; background: #171b22; padding: 8px 9px; display: flex; flex-direction: column; gap: 7px; }
    .sec h4 { margin: 0; font-size: 11.5px; color: #b9c1cf; font-weight: 600; letter-spacing: .3px; }
    .stateRow { display: flex; align-items: center; gap: 7px; font-size: 12px; }
    .dot2 { width: 8px; height: 8px; border-radius: 50%; background: #6b7280; flex: none; }
    .dot2.on { background: #3ddc84; box-shadow: 0 0 0 3px rgba(61,220,132,.15); }
    .dot2.off { background: #ff5d5d; }
    details { border: 1px solid #2b3140; border-radius: 8px; background: #171b22; }
    summary { padding: 7px 9px; cursor: pointer; font-size: 12px; color: #b9c1cf; }
    details[open] summary { border-bottom: 1px solid #2b3140; }
    .fields { padding: 9px; display: flex; flex-direction: column; gap: 8px; }
    .field { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 11.5px; }
    .field label { color: #97a0b0; }
    .field input[type=number], .field select, .field input[type=text] {
      all: unset; width: 74px; text-align: right; padding: 3px 6px; border-radius: 6px;
      background: #0e1116; border: 1px solid #2b3140; color: #e8ebf0; font-size: 11.5px;
    }
    .field select, .field input[type=text] { width: 118px; text-align: left; }
    input[type=range] { all: unset; width: 100%; height: 18px; cursor: pointer; }
    input[type=range]::-webkit-slider-runnable-track { height: 4px; background: #2b3140; border-radius: 99px; }
    input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 14px; height: 14px; margin-top: -5px; border-radius: 50%; background: #4f8cff; }
    .hint { font-size: 11px; color: #7d8698; }
    .segs { max-height: 108px; overflow: auto; font-size: 11.5px; }
    .seg { display: flex; justify-content: space-between; gap: 8px; padding: 3px 0; border-bottom: 1px dashed #232833; }
    .seg:last-child { border-bottom: 0; }
    .seg .ok { color: #3ddc84; } .seg .bad { color: #ffb020; }
    .seg .m { color: #7d8698; white-space: nowrap; }
    .logs { max-height: 116px; overflow: auto; font: 11px/1.5 Consolas,"Cascadia Mono",monospace; background: #0b0e13; border: 1px solid #2b3140; border-radius: 7px; padding: 6px; }
    .log { display: flex; gap: 6px; }
    .log .t { color: #59616f; flex: none; }
    .log .m { color: #c3cbd8; word-break: break-word; }
    .log.ok .m { color: #3ddc84; } .log.warn .m { color: #ffb020; } .log.error .m { color: #ff5d5d; }
      `;

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  const host = document.createElement('div');
  host.id = 'gad-host';
  host.style.cssText = 'all: initial; position: fixed; inset: 0; pointer-events: none; z-index: 2147483000;';
  const shadow = host.attachShadow({ mode: 'open' });
  const style = document.createElement('style');
  style.textContent = CSS;
  shadow.appendChild(style);

  const root = el('div', 'root mini');
  shadow.appendChild(root);

  const head = el('div', 'head');
  const dot = el('span', 'dot off');
  const name = el('span', 'name', '自动转录');
  const stateText = el('span', 'state', '待命');
  const grow = el('span', 'grow');
  const btnMini = el('button', 'iconbtn', '–');
  btnMini.title = '收起 / 展开';
  const btnClose = el('button', 'iconbtn', '×');
  btnClose.title = '隐藏面板（点扩展图标可再打开）';
  head.append(dot, name, stateText, grow, btnMini, btnClose);

  const minirow = el('div', 'minirow');
  const miniMeter = el('div', 'minimeter');
  const miniFill = el('i');
  miniMeter.appendChild(miniFill);
  const chipSave = el('button', 'chip chipbutton', '落盘?');
  chipSave.title = '本地落盘服务状态 + 落盘开关';
  const chipAgg = el('span', 'chip', '激 -');
  chipAgg.title = '当前生效的发送激进程度';
  const btnMiniToggle = el('button', 'tiny', '开始');
  minirow.append(chipSave, chipAgg, miniMeter, btnMiniToggle);

  const body = el('div', 'body');

  // 操作
  const rowActions = el('div', 'row');
  const btnStart = el('button', 'primary', '开始');
  const btnStop = el('button', '', '停止');
  rowActions.append(btnStart, btnStop);

  const setupRow = el('div', 'sec');
  const setupTitle = el('div', 'stateRow');
  const setupDot = el('span', 'dot2 off');
  const setupLabel = el('span', '', '首次使用：选择保存文件夹');
  setupTitle.append(setupDot, setupLabel);
  const setupText = el('div', 'hint', '点击后会打开 Windows 原生文件夹选择器。');
  const btnSetupRoot = el('button', 'primary', '选择保存文件夹');
  setupRow.append(setupTitle, setupText, btnSetupRoot);

  // 电平
  const meterWrap = el('div');
  const meter = el('div', 'meter');
  const meterFill = el('div', 'fill');
  const meterThr = el('div', 'thr');
  const meterSeg = el('div', 'seg');
  meter.append(meterFill, meterThr, meterSeg);
  const sub = el('div', 'sub');
  const subL = el('span', '', '电平 0.000');
  const subR = el('span', '', '阈值 0.012');
  sub.append(subL, subR);
  meterWrap.append(meter, sub);

  // 段信息
  const grid = el('div', 'grid');
  const cells = {};
  const cell = (key, label, val) => {
    const d = el('div');
    d.append(el('b', '', label), el('span', '', val));
    grid.appendChild(d);
    cells[key] = d.lastChild;
  };
  cell('seg', '本段', '0.0s');
  cell('speech', '说话', '0.0s');
  cell('silence', '静音', '0.0s');
  cell('need', '门槛', '2.5s');
  cell('max', '上限', '300s');
  cell('count', '已发', '0 段');
  const why = el('div', 'why', '');

  // 参数
  const adv = el('details');
  const sum = el('summary', '', '高级参数');
  const fields = el('div', 'fields');

  // —— 当前分类 + 偏置（偏置叠在工具学到的自动基准上）——
  const courseSel = document.createElement('select');
  courseSel.style.maxWidth = '124px';
  const btnNewCourse = el('button', 'tiny', '新建');
  const courseRow = el('div', 'field');
  courseRow.append(el('label', '', '③ 当前课程'), courseSel, btnNewCourse);

  const newCourseRow = el('div', 'field');
  const newCourseInput = document.createElement('input');
  newCourseInput.type = 'text';
  newCourseInput.placeholder = '分类名';
  const btnNewOk = el('button', 'tiny', '建立');
  const btnNewCancel = el('button', 'tiny', '取消');
  newCourseRow.append(el('label', '', '新分类'), newCourseInput, btnNewOk, btnNewCancel);
  newCourseRow.style.display = 'none';

  const range = document.createElement('input');
  range.type = 'range';
  range.min = '-3';
  range.max = '3';
  range.step = '0.5';
  const biasRow = el('div', 'field');
  biasRow.append(el('label', '', '你的偏置'), range);

  const fAgg = el('div');
  fAgg.append(el('div', 'hint', '偏置：在工具自己判断的基础上再快 / 再慢一点（−3 更保守，+3 更激进）'));
  const aggCombo = el('div', 'hint', '自动基准 — + 你的偏置 — = 生效 —');
  const aggHint = el('div', 'hint', '');
  const btnResetBase = el('button', 'tiny', '重置自动基准');
  const resetRow = el('div', 'field');
  resetRow.append(el('label', '', '自动基准'), btnResetBase);
  fAgg.append(biasRow, aggCombo, aggHint, resetRow);

  const field = (labelText, input) => {
    const d = el('div', 'field');
    d.append(el('label', '', labelText), input);
    return d;
  };

  const presetSel = document.createElement('select');
  presetSel.innerHTML = '<option value="normal">正式模式</option><option value="test">快速测试</option>';

  const numMax = numInput(20, 540);
  const numMinSpeech = numInput(1, 60);
  const numMinSeg = numInput(2, 120);
  const numFloor = numInput(0, 5000);
  const numThr = numInput(0, 1, 0.001);
  const numSettle = numInput(0, 5000);
  const chkAuto = checkbox();

  function numInput(min, max, step = 0.5) {
    const i = document.createElement('input');
    i.type = 'number';
    i.min = String(min);
    i.max = String(max);
    i.step = String(step);
    return i;
  }
  function checkbox() {
    const i = document.createElement('input');
    i.type = 'checkbox';
    i.style.cssText = 'all:unset;width:14px;height:14px;border:1px solid #3a4356;border-radius:4px;background:#0e1116;cursor:pointer;';
    return i;
  }

  const btnCal = el('button', 'tiny', '按当前环境校准');
  const calRow = el('div', 'field');
  calRow.append(el('label', '', '校准'), btnCal);

  // —— 落盘（接力给本地服务）——
  const chkAutoTab = checkbox();
  const chkSave = checkbox();
  const chkAutoJump = checkbox();
  const chkAutoInject = checkbox();
  const saveTarget = el('div', 'hint', '');
  saveTarget.style.wordBreak = 'break-all';
  const btnPing = el('button', 'tiny', '重新检测');
  const saveDot = el('span', 'dot2');
  const saveStateText = el('span', '', '本地落盘服务：未检测');
  const saveStateRow = el('div', 'stateRow');
  saveStateRow.append(saveDot, saveStateText);
  const saveStatus = el('div', 'hint', '');
  const btnRetryBlocked = el('button', 'tiny', '重试失败段落');
  btnRetryBlocked.style.display = 'none';
  const btnClearBlocked = el('button', 'tiny', '清空失败记录');
  btnClearBlocked.style.display = 'none';
  const retryCourseSel = document.createElement('select');
  retryCourseSel.style.maxWidth = '160px';
  const retryCourseRow = el('div', 'field');
  retryCourseRow.append(el('label', '', '失败段落分类'), retryCourseSel);
  retryCourseRow.style.display = 'none';
  const pingRow = el('div', 'field');
  pingRow.append(el('label', '', '服务'), btnPing);
  const btnChooseRoot = el('button', 'tiny', '选择文件夹');
  const rootRow = el('div', 'field');
  rootRow.append(el('label', '', '① 落盘根目录'), btnChooseRoot);
  const subDirInput = document.createElement('input');
  subDirInput.type = 'text';
  subDirInput.placeholder = '留空 = 直接放课程目录';
  subDirInput.style.width = '132px';
  const subDirRow = el('div', 'field');
  subDirRow.append(el('label', '', '② 子文件夹'), subDirInput);
  const btnFindRoots = el('button', 'tiny', '发现已有目录');
  const rootCandidates = document.createElement('select');
  rootCandidates.style.maxWidth = '210px';
  const legacyRootRow = el('div', 'field');
  legacyRootRow.append(el('label', '', '已有目录'), rootCandidates, btnFindRoots);
  // 重装 / 换电脑后找回旧归档用的控件，日常不需要，收进折叠区
  const advRestore = el('details');
  advRestore.append(el('summary', '', '高级 / 恢复（找回旧归档目录）'));
  const advRestoreBody = el('div', 'fields');
  advRestoreBody.append(
    el('div', 'hint', '重装扩展或换电脑后，用这里找回之前已归档的目录；日常使用不需要动它。'),
    legacyRootRow
  );
  advRestore.append(advRestoreBody);
  const langSel = document.createElement('select');
  langSel.innerHTML = '<option value="zh-CN">中文</option><option value="de-DE">Deutsch</option><option value="en-US">English</option>';
  const langRow = el('div', 'field');
  const langLabel = el('label', '', '目标语言');
  langRow.append(langLabel, langSel);
  const uiSel = document.createElement('select');
  uiSel.innerHTML = '<option value="zh-CN">中文</option><option value="de-DE">Deutsch</option><option value="en-US">English</option>';
  const uiRow = el('div', 'field');
  const uiLabel = el('label', '', '界面语言');
  uiRow.append(uiLabel, uiSel);
  const promptBox = el('textarea');
  promptBox.rows = 5;
  promptBox.placeholder = '留空 = 新会话不自动发任何提示词';
  promptBox.style.cssText = 'width:100%;resize:vertical;padding:7px;border:1px solid #2b3140;border-radius:7px;background:#0e1116;color:#e8ebf0;font:11px/1.45 Consolas,monospace;';
  const promptHint = el('div', 'hint', '新会话提示词（进入新会话时自动发出，提醒 ChatGPT 回忆你记忆中的任务要求）');
  promptHint.style.whiteSpace = 'pre-line';
  const btnCopyPrompt = el('button', 'tiny', '复制提示词');
  const btnInjectPrompt = el('button', 'tiny', '立即注入');
  btnInjectPrompt.title = '立刻把这段提示词发到当前会话';
  const btnResetPrompt = el('button', 'tiny', '恢复默认');
  const promptRow = el('div', 'row');
  promptRow.append(btnCopyPrompt, btnInjectPrompt, btnResetPrompt);
  const btnOpenChat = el('button', 'tiny', '打开 GPT 新会话');
  const guideToggle = el('button', 'tiny', '收起首次使用教程');
  const guide = el('div', 'hint', '');
  guide.style.whiteSpace = 'pre-line';

  // —— 静音判定（自适应，且这几个系数都可调）——
  const numFloorFactor = numInput(1, 10, 0.5);
  const numLoudCap = numInput(0.1, 1, 0.05);
  const numSmooth = numInput(0.02, 0.5, 0.01);
  const noiseLive = el('div', 'hint', '');

  const secPolicy = el('div', 'sec');
  secPolicy.append(el('h4', '', '自动分段与偏置'), fAgg);

  const secNoise = el('div', 'sec');
  secNoise.append(
    el('h4', '', '静音判定（自适应，系数可调）'),
    noiseLive,
    field('阈值 = 本底 ×', numFloorFactor),
    field('且不高于 大声 ×', numLoudCap),
    field('平滑（越小越稳）', numSmooth),
    field('手动阈值（0=自适应）', numThr),
    calRow
  );

  const secSave = el('div', 'sec');
  secSave.append(
    el('h4', '', '自动落盘'),
    saveStateRow,
    saveStatus,
    btnRetryBlocked,
    btnClearBlocked,
    retryCourseRow,
    field('归档 GPT 回答', chkSave),
    el('div', 'hint', '落盘位置按 ① → ② → ③ 选一遍就行：'),
    rootRow,
    subDirRow,
    courseRow,
    newCourseRow,
    saveTarget,
    pingRow,
    langRow,
    uiRow,
    advRestore,
    guideToggle,
    guide,
    btnOpenChat
  );

  const secAuto = el('div', 'sec');
  secAuto.append(
    el('h4', '', '提示词与自动化'),
    field('自动开始录音', chkAuto),
    field('上下文满时自动跳转新会话', chkAutoJump),
    field('新会话自动注入提示词', chkAutoInject),
    promptHint,
    promptBox,
    promptRow
  );

  fields.append(
    field('预设', presetSel),
    field('每段硬上限（秒）', numMax),
    field('最短说话（秒）', numMinSpeech),
    field('最短一段（秒）', numMinSeg),
    field('停顿下限（毫秒，0=自动）', numFloor),
    field('发送后等待（毫秒）', numSettle),
    field('打开浏览器就自动开 ChatGPT', chkAutoTab)
  );
  adv.append(sum, fields);

  // 记录
  const segsBox = el('div', 'segs');
  const logsBox = el('div', 'logs');

  body.append(
    setupRow, rowActions, meterWrap, grid, why,
    secPolicy, secNoise, secSave, secAuto,
    adv,
    el('div', 'hint', '分段记录'), segsBox,
    el('div', 'hint', '日志'), logsBox
  );

  root.append(head, minirow, body);
  (document.body || document.documentElement).appendChild(host);

  /* ----------------------------- 拖动与避让 ----------------------------- */

  let pos = null;

  function applyPos() {
    if (!pos) {
      root.style.left = 'auto';
      root.style.top = '72px';
      root.style.right = '16px';
      return;
    }
    root.style.right = 'auto';
    root.style.left = pos.x + 'px';
    root.style.top = pos.y + 'px';
  }

  function clampPos() {
    if (!pos) return;
    const r = root.getBoundingClientRect();
    const maxX = Math.max(4, window.innerWidth - r.width - 4);
    const maxY = Math.max(4, window.innerHeight - r.height - 4);
    pos.x = clamp(pos.x, 4, maxX);
    pos.y = clamp(pos.y, 4, maxY);
  }

  /** 只要和底部输入框重叠，就自动往上让开——保证麦克风/发送按钮永远点得到。 */
  function keepClearOfComposer() {
    const composer = document.querySelector('form[data-composer-placement]')
      || document.querySelector('[data-chatgpt-composer]')
      || document.querySelector('form');
    if (!composer) return;
    const cr = composer.getBoundingClientRect();
    if (!cr.height) return;
    const r = root.getBoundingClientRect();
    const overlaps = r.left < cr.right && r.right > cr.left && r.top < cr.bottom && r.bottom > cr.top;
    if (!overlaps) return;

    const curTop = r.top;
    const wantBottom = cr.top - 10;
    const newTop = Math.max(8, wantBottom - r.height);
    if (newTop >= curTop - 1) return;
    pos = { x: pos ? pos.x : Math.max(4, window.innerWidth - r.width - 16), y: newTop };
    applyPos();
    saveSettings();
  }

  const keepClearSoon = (() => {
    let t = 0;
    return () => { clearTimeout(t); t = setTimeout(() => { clampPos(); keepClearOfComposer(); }, 120); };
  })();

  // 点小条或标题栏空白处即可收起/展开：那个小小的减号太不显眼，
  // 用户会以为根本没有可调参数
  let draggedFar = false;
  const toggleExpand = (e) => {
    if (draggedFar) { draggedFar = false; return; }
    if (e.target.closest('button')) return;
    settings.mini = !settings.mini;
    root.classList.toggle('mini', settings.mini);
    saveSettings();
    keepClearSoon();
  };
  head.addEventListener('click', toggleExpand);
  minirow.addEventListener('click', toggleExpand);

  head.addEventListener('pointerdown', (e) => {
    if (e.target.closest('button')) return;
    head.classList.add('dragging');
    const r = root.getBoundingClientRect();
    const offX = e.clientX - r.left;
    const offY = e.clientY - r.top;
    root.style.right = 'auto';
    root.style.left = r.left + 'px';
    root.style.top = r.top + 'px';
    const startX = e.clientX, startY = e.clientY;
    const move = (ev) => {
      if (Math.abs(ev.clientX - startX) + Math.abs(ev.clientY - startY) > 5) draggedFar = true;
      pos = {
        x: clamp(ev.clientX - offX, 4, window.innerWidth - r.width - 4),
        y: clamp(ev.clientY - offY, 4, window.innerHeight - 60)
      };
      applyPos();
    };
    const up = () => {
      head.classList.remove('dragging');
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', up);
      keepClearSoon();
      saveSettings();
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', up);
    e.preventDefault();
  });

  window.addEventListener('resize', keepClearSoon);
  try {
    new ResizeObserver(keepClearSoon).observe(document.body || document.documentElement);
  } catch { /* 忽略 */ }

  /* ----------------------------- 渲染 ----------------------------- */

  const fmt = (ms) => (ms >= 60000 ? (ms / 60000).toFixed(1) + 'min' : (ms / 1000).toFixed(1) + 's');

  function render() {
    const st = status || {};
    dot.className = 'dot ' + (st.state || 'off');
    stateText.textContent = st.stateLabel || '—';
    btnStart.disabled = !!st.running;
    btnStop.disabled = !st.running;
    btnMiniToggle.textContent = st.running ? '停止' : '开始';

    const rms = st.rms || 0;
    const thr = st.threshold || 0.012;
    // 刻度跟着实际声音走，否则底噪一大条子就被压成一条线、看着像不动
    const scale = Math.max(0.05, thr * 4, (st.loud || 0) * 1.2, rms * 1.15);
    meterFill.style.width = Math.min(100, (rms / scale) * 100) + '%';
    meterThr.style.left = Math.min(99, (thr / scale) * 100) + '%';
    miniFill.style.width = Math.min(100, (rms / scale) * 100) + '%';

    // 小条上的两个实用指标：落盘到底活着没有、当前激进程度是多少
    const effAgg = (st.tuning && st.tuning.effectiveAgg != null)
      ? Number(st.tuning.effectiveAgg)
      : clamp(((st.tuning && st.tuning.autoBase) != null ? Number(st.tuning.autoBase) : 4) + (Number(settings.bias) || 0), 1, 10);
    chipAgg.textContent = '激 ' + effAgg.toFixed(1);
    const outboxBlocked = saveOutbox.filter((job) => job.blocked);
    const blockedIds = new Set(outboxBlocked.map((job) => job.saveId));
    const pageOnlyBlocked = (pageSaveState.blockedIds || []).filter((id) => !blockedIds.has(id));
    const blockedSaveCount = blockedIds.size + pageOnlyBlocked.length;
    const pageWaitingCount = (pageSaveState.waitingIds || []).length;
    const needsRetryCourse = outboxBlocked.some((job) => !job.payload || !job.payload.course) ||
      pageOnlyBlocked.length > 0;
    if (blockedSaveCount) {
      chipSave.textContent = '落盘失败 ' + blockedSaveCount; chipSave.className = 'chip off';
    } else if (!saveOnline) {
      chipSave.textContent = '落盘离线'; chipSave.className = 'chip off';
    } else if (!settings.saveEnabled) {
      chipSave.textContent = '落盘未开'; chipSave.className = 'chip warn';
    } else if (pageWaitingCount) {
      chipSave.textContent = '待保存 ' + pageWaitingCount; chipSave.className = 'chip warn';
    } else if (!lastSaveOkAt) {
      chipSave.textContent = '落盘就绪'; chipSave.className = 'chip on';
    } else {
      // 显示"距上次落盘多久"——比"20 秒内闪过一次"有用得多，
      // 因为一轮转录本来就要几十秒，短闪的标记根本看不出落盘到底在不在工作。
      const mins = (Date.now() - lastSaveOkAt) / 60000;
      chipSave.textContent = mins < 1 ? '刚落盘'
        : '落盘 ' + (mins < 60 ? Math.round(mins) + '分钟前' : Math.round(mins / 60) + '小时前');
      chipSave.className = 'chip ' + (mins < 5 ? 'on' : (mins < 15 ? 'warn' : 'off'));
    }
    if (blockedSaveCount) {
      const blockedError = outboxBlocked.find((job) => job.lastError);
      saveStatus.textContent = lastSaveError && /缺少分类/.test(lastSaveError)
        ? lastSaveError
        : blockedSaveCount + ' 段落盘失败，内容仍保留。' +
          (blockedError ? '原因：' + blockedError.lastError + '。' : '') +
          '修复原因后点击“重试失败段落”。';
    } else if (saveOutbox.length) {
      saveStatus.textContent = lastSaveError || ('待保存 ' + saveOutbox.length + ' 段，服务恢复后自动重试');
    } else if (pageWaitingCount) {
      saveStatus.textContent = '页面待保存 ' + pageWaitingCount +
        ' 段；正在等待对应回答，或需要切回原来的 ChatGPT 会话';
    } else if (lastSaveError) {
      saveStatus.textContent = lastSaveError;
    }
    btnRetryBlocked.style.display = blockedSaveCount ? '' : 'none';
  if (btnClearBlocked) btnClearBlocked.style.display = blockedSaveCount ? '' : 'none';
    btnRetryBlocked.disabled = !blockedSaveCount;
    retryCourseRow.style.display = needsRetryCourse ? '' : 'none';
    if (needsRetryCourse) {
      const names = [...new Set([
        ...(courseStore.list || []).map((item) => item.name),
        settings.saveCourse
      ].filter(Boolean))];
      const signature = names.join('\n');
      if (retryCourseSel.dataset.options !== signature) {
        const selected = retryCourseSel.value;
        retryCourseSel.innerHTML = '<option value="">选择分类</option>' +
          names.map((name) =>
            '<option>' + String(name).replace(/[<>&]/g, '') + '</option>').join('');
        if (names.includes(selected)) retryCourseSel.value = selected;
        retryCourseSel.dataset.options = signature;
      }
    }
    subL.textContent = '电平 ' + rms.toFixed(3) + (st.peak ? ' 峰' + st.peak.toFixed(2) : '') +
      (st.micReady ? '' : '（未挂上麦克风）');
    subR.textContent = '阈值 ' + thr.toFixed(3) +
      (st.floor != null ? '　本底 ' + Number(st.floor).toFixed(3) : '') +
      (st.loud != null ? '　大声 ' + Number(st.loud).toFixed(3) : '');

    const maxMs = (st.tuning && st.tuning.maxSegmentMs) || 300000;
    const segPct = Math.min(100, ((st.segMs || 0) / maxMs) * 100);
    meterSeg.style.width = Math.max(0, 100 - segPct) + '%';

    cells.seg.textContent = fmt(st.segMs || 0);
    cells.speech.textContent = fmt(st.speechMs || 0);
    cells.silence.textContent = fmt(st.silenceMs || 0);
    cells.need.textContent = ((st.needPauseMs || 0) / 1000).toFixed(1) + 's';
    cells.max.textContent = Math.round(maxMs / 1000) + 's';
    cells.count.textContent = (st.sentCount || 0) + ' 段';

    const bits = [];
    if (st.state === 'recording') {
      bits.push('再录 ' + fmt(Math.max(0, maxMs - (st.segMs || 0))) + ' 到兜底上限');
      if (st.lastReason) bits.push('上一段：' + st.lastReason);
    } else if (st.lastReason) {
      bits.push('最近收口：' + st.lastReason);
    }
    if (st.sampleDt > 400) bits.push('标签页在后台，采样降到 ' + st.sampleDt + 'ms（精度略降，仍安全）');
    why.textContent = bits.join('　|　');

    // 静音判定的实时读数：一眼看出阈值跟没跟上环境
    if (st.floor != null && st.loud != null) {
      const manual = Number(settings.silenceThreshold) > 0;
      noiseLive.textContent = (manual ? '手动阈值 ' : '自适应：') +
        '本底 ' + Number(st.floor).toFixed(4) +
        '　大声 ' + Number(st.loud).toFixed(3) +
        '　→ 阈值 ' + Number(st.threshold || 0).toFixed(4) +
        (manual ? '' : '（= 本底×' + settings.floorFactor + '，且 ≤ 大声×' + settings.loudCap + '）');
    } else {
      noiseLive.textContent = '还没开始录音，还没有本底读数';
    }

    const segs = st.segments || [];
    segsBox.innerHTML = '';
    if (!segs.length) {
      segsBox.append(el('div', 'hint', '还没有发送记录'));
    } else {
      for (const s of segs) {
        const row = el('div', 'seg');
        const left = el('span', s.sent ? 'ok' : 'bad', '#' + s.index + ' ' + (s.sent ? '已发送' : '未发出'));
        const right = el('span', 'm', '录' + s.durationSec + 's/说' + s.speechSec + 's');
        row.append(left, right);
        segsBox.appendChild(row);
      }
    }

    const html = logs.slice(-80).map((l) =>
      '<div class="log ' + l.level + '"><span class="t">' + l.t + '</span><span class="m">' +
      String(l.message).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c])) +
      '</span></div>').join('');
    if (logsBox.dataset.n !== String(logs.length)) {
      const atBottom = logsBox.scrollTop + logsBox.clientHeight >= logsBox.scrollHeight - 20;
      logsBox.innerHTML = html;
      if (atBottom) logsBox.scrollTop = logsBox.scrollHeight;
      logsBox.dataset.n = String(logs.length);
    }

    renderParams();
  }

  /** 示例文件名：本地服务按「{date} {weekday} {time}.md」命名，这里照同一个格式给个样例。 */
  function exampleFileName(d) {
    const x = d || new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const wd = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][x.getDay()];
    return x.getFullYear() + '-' + pad(x.getMonth() + 1) + '-' + pad(x.getDate()) +
      ' ' + wd + ' ' + pad(x.getHours()) + '-' + pad(x.getMinutes()) + '.md';
  }

  /** 真实落盘路径 = 根目录 \ 课程 \ 子文件夹（子文件夹留空则直接放课程目录下）。 */
  function savePathPreview() {
    if (!writerRoot) return { dir: '', file: '' };
    const trimSep = (s) => String(s == null ? '' : s).trim().replace(/^[\\/]+/, '').replace(/[\\/]+$/, '');
    const parts = [String(writerRoot).replace(/[\\/]+$/, '')];
    parts.push(trimSep(courseStore.active || settings.saveCourse) || '未分类');
    const sub = trimSep(settings.saveSubDir);
    if (sub) parts.push(sub);
    const dir = parts.join('\\');
    return { dir, file: dir + '\\' + exampleFileName() };
  }

  function renderParams() {
    const t = (status && status.tuning) || null;
    const autoBase = (t && t.autoBase != null) ? Number(t.autoBase) : null;
    const bias = clamp(Number(settings.bias) || 0, -3, 3);
    const eff = t ? Number(t.effectiveAgg) : clamp((autoBase == null ? 4 : autoBase) + bias, 1, 10);

    aggCombo.textContent = '自动基准 ' + (autoBase == null ? '—（还没学够，先用 4）' : autoBase.toFixed(1)) +
      ' ＋ 你的偏置 ' + (bias >= 0 ? '+' : '') + bias.toFixed(1) + ' ＝ 生效 ' + eff.toFixed(1);

    if (t) {
      aggHint.textContent = '刚开录要求停顿 ' + (t.maxPauseMs / 1000).toFixed(1) + 's；说到 ' + t.rampStartSec +
        's 后逐步降到 ' + (t.minPauseMs / 1000).toFixed(1) + 's' +
        (t.ultimateMs < t.minPauseMs ? '，再继续压到 ' + (t.ultimateMs / 1000).toFixed(1) + 's' : '') +
        '。停顿依据：' + (t.learned
          ? '按这门课量到的节奏（p50 ' + t.pauseP50 + 'ms / p90 ' + t.pauseP90 + 'ms，样本 ' + t.pauseN + ' 次）'
          : '还没量够停顿（' + t.pauseN + '/12 次），先用默认值');
    }

    range.value = String(bias);
    presetSel.value = settings.preset;
    numMax.value = String(settings.maxSegmentSec);
    numMinSpeech.value = String(settings.minSpeechSec);
    numMinSeg.value = String(settings.minSegmentSec);
    numFloor.value = String(settings.pauseFloorMs);
    numThr.value = String(settings.silenceThreshold);
    numFloorFactor.value = String(settings.floorFactor);
    numLoudCap.value = String(settings.loudCap);
    numSmooth.value = String(settings.smooth);
    numSettle.value = String(settings.settleAfterSendMs);
    const paint = (box, on) => { if (box) box.style.background = on ? '#1d4ed8' : '#0e1116'; };
    chkAuto.checked = !!settings.autoStart;
    chkAuto.style.background = settings.autoStart ? '#1d4ed8' : '#0e1116';
    chkAutoJump.checked = settings.autoJumpNewSession !== false;
    paint(chkAutoJump, chkAutoJump.checked);
    chkAutoInject.checked = settings.autoInjectPrompt !== false;
    paint(chkAutoInject, chkAutoInject.checked);
    chkAutoTab.checked = !!settings.autoOpenTab;
    chkAutoTab.style.background = settings.autoOpenTab ? '#1d4ed8' : '#0e1116';
    chkSave.checked = !!settings.saveEnabled;
    chkSave.style.background = settings.saveEnabled ? '#1d4ed8' : '#0e1116';
    langSel.value = settings.language || 'zh-CN';
    uiSel.value = settings.uiLanguage || 'zh-CN';
    refreshPrompt();
    applyUiLanguage();
    guide.style.display = settings.guideOpen === false ? 'none' : '';
    renderCourseSelect();
    if (subDirInput && subDirInput.getRootNode().activeElement !== subDirInput) {
      subDirInput.value = String(settings.saveSubDir == null ? '' : settings.saveSubDir);
    }
    if (saveTarget) {
      const preview = savePathPreview();
      saveTarget.textContent = '';
      if (!preview.dir) {
        saveTarget.append(el('div', '', '未设置落盘根目录：点击“选择文件夹”后才会写入文件'));
      } else {
        saveTarget.append(el('div', '', '实际目录：' + preview.dir));
        saveTarget.append(el('div', '', '示例文件：' + preview.file));
      }
    }
    setupRow.style.display = writerRoot ? 'none' : '';
    setupText.textContent = writerRoot
      ? ''
      : (saveOnline ? '点击后选择任意本地文件夹；若没有弹窗，请双击 choose-save-folder.bat。' : '请双击 open-chatgpt.bat 或 choose-save-folder.bat。');
  }

  function pushConfig(extra) {
    send('config', { ...settings, ...(extra || {}) });
    saveSettings();
    renderParams();
  }

  /* ----------------------------- 交互 ----------------------------- */

  btnStart.onclick = () => send('start');
  btnStop.onclick = () => send('stop');
  btnMiniToggle.onclick = () => send(status && status.running ? 'stop' : 'start');
  btnMini.onclick = () => { settings.mini = !settings.mini; root.classList.toggle('mini', settings.mini); saveSettings(); keepClearSoon(); };
  btnClose.onclick = () => { settings.showPanel = false; host.style.display = 'none'; saveSettings(); };
  btnCal.onclick = () => { send('calibrate'); log_local('info', '已请求按当前环境校准静音阈值'); };

  range.oninput = () => {
    const v = clamp(Number(range.value) || 0, -3, 3);
    settings.bias = v;
    const p = ensureProfile(courseStore.active);
    if (p) { p.bias = v; p.updatedAt = Date.now(); saveCourses(); }
    pushConfig(courseExtra());
  };
  presetSel.onchange = () => { settings.preset = presetSel.value; pushConfig(); };
  const bindNum = (input, key, min, max) => {
    input.onchange = () => {
      let v = Number(input.value);
      if (!Number.isFinite(v)) v = DEFAULTS[key];
      settings[key] = clamp(v, min, max);
      pushConfig();
    };
  };
  bindNum(numMax, 'maxSegmentSec', 20, 540);
  bindNum(numMinSpeech, 'minSpeechSec', 1, 60);
  bindNum(numMinSeg, 'minSegmentSec', 2, 120);
  bindNum(numFloor, 'pauseFloorMs', 0, 5000);
  bindNum(numThr, 'silenceThreshold', 0, 1);
  bindNum(numFloorFactor, 'floorFactor', 1, 10);
  bindNum(numLoudCap, 'loudCap', 0.1, 1);
  bindNum(numSmooth, 'smooth', 0.02, 0.5);
  bindNum(numSettle, 'settleAfterSendMs', 0, 5000);
  chkAuto.onchange = () => { settings.autoStart = chkAuto.checked; settings.autoStartPreferenceSet = true; pushConfig(); };
  chkAutoTab.onchange = () => { settings.autoOpenTab = chkAutoTab.checked; pushConfig(); };
  chkSave.onchange = () => { settings.saveEnabled = chkSave.checked; settings.savePreferenceSet = true; pushConfig(); if (chkSave.checked) pingWriter(); };
  btnPing.onclick = () => pingWriter();
  btnClearBlocked.onclick = () => {
    send('clearBlockedSaves');
    log_local('info', '已请求清空页面侧失败记录（内容不会丢，只是不再挂在面板上）');
  };
  btnRetryBlocked.onclick = async () => {
    const blocked = saveOutbox.filter((job) => job.blocked);
    const outboxIds = new Set(saveOutbox.map((job) => job.saveId));
    const pageOnlyBlocked = (pageSaveState.blockedIds || []).filter((id) => !outboxIds.has(id));
    if (!blocked.length && !pageOnlyBlocked.length) return;
    const chosenCourse = String(retryCourseSel.value || '').trim();
    let missingCourse = 0;
    let retryableBlocked = 0;
    for (const job of blocked) {
      if (!job.payload || !job.payload.course) {
        if (!chosenCourse) {
          job.lastError = '这段没有分类名；请先在“失败段落分类”中明确选择';
          missingCourse += 1;
          continue;
        }
        job.payload.course = chosenCourse;
      }
      job.blocked = false;
      job.attempts = 0;
      job.nextAt = 0;
      job.lastError = '';
      job.updatedAt = Date.now();
      retryableBlocked += 1;
    }
    if (!retryableBlocked && !pageOnlyBlocked.length) {
      lastSaveError = missingCourse + ' 个失败任务缺少分类，未用当前分类自动猜测';
      await persistSaveOutbox();
      log_local('warn', lastSaveError);
      render();
      return;
    }
    if (chosenCourse) send('assignBlockedCourse', { course: chosenCourse });
    else send('retryPageSaves');
    lastSaveError = '';
    const persisted = await persistSaveOutbox();
    if (!persisted) return;
    log_local('info', '正在重试 ' + (retryableBlocked + pageOnlyBlocked.length) +
      ' 个已保留的落盘任务，并重新检查页面侧保存队列');
    render();
    pumpSaveQueue();
  };
  let installerOpen = false;
  async function chooseFolder(auto) {
    if (!ISOLATED) { saveStatus.textContent = '仅扩展模式可选择本地文件夹'; return; }
    if (installerOpen) return;
    installerOpen = true;
    if (auto) {
      settings.installerPrompted = true;
      saveSettings();
    }
    saveStatus.textContent = '正在打开 Windows 文件夹选择器…';
    try {
      const res = await callWriter('/choose-root', { method: 'POST', body: {}, timeoutMs: 125000 });
      installerOpen = false;
      if (!res || !res.ok) {
        saveStatus.textContent = (res && res.canceled) ? '已取消选择' : '选择失败：请双击 choose-save-folder.bat';
        return;
      }
      writerRoot = res.rootDir || '';
      settings.saveEnabled = true;
      settings.savePreferenceSet = true;
      pushConfig();
      saveStatus.textContent = '已选择落盘根目录';
      renderParams();
      pingWriter();
    } catch (e) {
      installerOpen = false;
      saveStatus.textContent = '选择失败：' + String(e.message || e);
    }
  }
  btnChooseRoot.onclick = () => chooseFolder(false);
  btnSetupRoot.onclick = () => chooseFolder(false);
  chipSave.onclick = () => { if (!writerRoot || !saveOnline) chooseFolder(false); else pingWriter(); };

  function renderRootCandidates(candidates) {
    const list = Array.isArray(candidates) ? candidates : [];
    rootCandidates.innerHTML = '';
    if (!list.length) {
      rootCandidates.appendChild(new Option('未发现旧目录', ''));
      return;
    }
    for (const item of list) {
      const label = item.rootDir + '（' + (item.files || 0) + ' 个文本文件）';
      rootCandidates.appendChild(new Option(label, item.rootDir));
    }
  }
  async function discoverRootCandidates() {
    if (!ISOLATED) return false;
    try {
      const res = await callWriter('/root-candidates');
      if (!res || !res.ok) return false;
      renderRootCandidates(res.candidates);
      if (res.candidates && res.candidates.length) {
        saveStatus.textContent = '已发现旧目录；请选择后点击“使用已有目录”';
      }
      return true;
    } catch {
      return false;
    }
  }
  btnFindRoots.onclick = () => {
    saveStatus.textContent = '正在检查旧落盘目录…';
    discoverRootCandidates().then((ok) => {
      saveStatus.textContent = ok
        ? (rootCandidates.value ? '发现旧目录。选中后点击“使用已有目录”' : '没有发现候选旧目录')
        : '无法检查：请先启动 writer\\start-writer.bat';
    });
  };
  const btnUseLegacyRoot = el('button', 'tiny', '使用已有目录');
  legacyRootRow.appendChild(btnUseLegacyRoot);
  btnUseLegacyRoot.onclick = async () => {
    const rootDir = rootCandidates.value;
    if (!rootDir) { saveStatus.textContent = '先点击“发现已有目录”并选择一个目录'; return; }
    try {
      const res = await callWriter('/set-root', { method: 'POST', body: { rootDir } });
      if (!res || !res.ok) {
        saveStatus.textContent = '旧目录设置失败：' + ((res && res.error) || 'writer 未运行');
        return;
      }
      writerRoot = res.rootDir || rootDir;
      settings.saveEnabled = true;
      settings.savePreferenceSet = true;
      pushConfig();
      saveStatus.textContent = '已恢复旧目录；之后的新内容会继续追加到这里';
      renderParams();
      pingWriter();
    } catch (e) {
      saveStatus.textContent = '旧目录设置失败：' + String(e.message || e);
    }
  };

  // 保留：需要严格的翻译 / 整理规则时，可把这段文案手动粘贴到上面的提示词框里。
  function translationPrompt() {
    const target = settings.language === 'de-DE' ? '德语' : (settings.language === 'en-US' ? '英语' : '中文');
    return '请记住以下音频转录处理规则：\n' +
      '1. 先忠实理解本批次转录；若它不是' + target + '，再整理为' + target + '。\n' +
      '2. 不评价内容，不改变原意，不补写不存在的事实；可省略无效重复和纯语气词，但保留有意义的重复、不确定性、数字和专业术语。\n' +
      '3. 原文中有问题但没有回答时，把这些问题放在回答最前方，并保留问句含义。\n' +
      '4. 专业术语保留原文注释。当前句子的意思或结论优先，分段清楚。\n' +
      '5. 默认只输出转录、翻译或忠实整理结果；只有在明确要求时才总结、扩写、生成文件或使用写作块。';
  }
  function refreshPrompt() {
    if (!promptBox) return;
    // 用户正在编辑时不要覆盖输入（shadow DOM 里要问 shadow root 的 activeElement）
    const rootNode = promptBox.getRootNode ? promptBox.getRootNode() : document;
    if (rootNode && rootNode.activeElement === promptBox) return;
    promptBox.value = String(settings.newSessionPrompt == null ? '' : settings.newSessionPrompt);
  }
  langSel.onchange = () => { settings.language = langSel.value; saveSettings(); };
  function applyUiLanguage() {
    const lang = settings.uiLanguage || 'zh-CN';
    const t = lang === 'de-DE'
      ? { name: 'Auto-Diktat', start: 'Start', stop: 'Stopp', choose: 'Ordner wählen', find: 'Alte Ordner suchen', use: 'Diesen Ordner verwenden', copy: 'Optionale GPT-Regeln kopieren', open: 'Neuen GPT-Chat öffnen', guideToggleOpen: 'Erste Schritte ausblenden', guideToggleClosed: 'Erste Schritte anzeigen', guide: 'Erste Schritte:\n1. Erweiterung laden und ChatGPT neu laden.\n2. writer\\start-writer.bat starten.\n3. Speicherordner wählen und Schreibtest abwarten.\n4. Optional GPT-Regeln in einem neuen Chat senden.\n5. Mit „Schnelltest“ prüfen, danach den normalen Modus verwenden.', target: 'Ausgabesprache', ui: 'Oberflächensprache' }
      : lang === 'en-US'
        ? { name: 'Auto Dictation', start: 'Start', stop: 'Stop', choose: 'Choose folder', find: 'Find existing folders', use: 'Use this folder', copy: 'Copy optional GPT rules', open: 'Open new GPT chat', guideToggleOpen: 'Hide first-use guide', guideToggleClosed: 'Show first-use guide', guide: 'First-use guide:\n1. Load the extension and reload ChatGPT.\n2. Start writer\\start-writer.bat.\n3. Choose a save folder and wait for the write check.\n4. Optionally send the GPT rules in a new chat.\n5. Run Quick test, then switch to Normal mode.', target: 'Output language', ui: 'Interface language' }
        : { name: '自动转录', start: '开始', stop: '停止', choose: '选择文件夹', find: '发现已有目录', use: '使用已有目录', copy: '复制可选处理提示词', open: '打开 GPT 新会话', guideToggleOpen: '收起首次使用教程', guideToggleClosed: '打开首次使用教程', guide: '首次使用教程：\n1. 加载扩展并刷新 ChatGPT。\n2. 启动 writer\\start-writer.bat。\n3. 选择归档目录，等待写入检查完成。\n4. 可选：复制处理提示词并在新会话中发送。\n5. 先用“快速测试”，确认无误后切换“正式模式”。', target: '输出语言', ui: '界面语言' };
    name.textContent = t.name;
    btnStart.textContent = t.start;
    btnStop.textContent = t.stop;
    btnChooseRoot.textContent = t.choose;
    btnFindRoots.textContent = t.find;
    btnUseLegacyRoot.textContent = t.use;
    btnCopyPrompt.textContent = t.copy;
    btnOpenChat.textContent = t.open;
    guide.textContent = t.guide;
    guideToggle.textContent = settings.guideOpen === false ? t.guideToggleClosed : t.guideToggleOpen;
    langLabel.textContent = t.target;
    uiLabel.textContent = t.ui;
  }
  uiSel.onchange = () => { settings.uiLanguage = uiSel.value; saveSettings(); applyUiLanguage(); };
  guideToggle.onclick = () => {
    settings.guideOpen = settings.guideOpen === false;
    guide.style.display = settings.guideOpen ? '' : 'none';
    saveSettings();
    applyUiLanguage();
  };
  btnCopyPrompt.onclick = async () => {
    refreshPrompt();
    try { await navigator.clipboard.writeText(promptBox.value); saveStatus.textContent = '提示词已复制；请在新 GPT 会话中粘贴并发送，剪贴板不等于已记住'; }
    catch { promptBox.select(); saveStatus.textContent = '自动复制失败，请手动复制文本框内容'; }
  };
  btnInjectPrompt.onclick = () => {
    const text = String(settings.newSessionPrompt == null ? '' : settings.newSessionPrompt).trim();
    if (!text) { saveStatus.textContent = '提示词为空，没有可注入的内容'; return; }
    send('injectPromptNow', {});
    saveStatus.textContent = '已请求把提示词发到当前会话';
  };
  btnResetPrompt.onclick = () => {
    settings.newSessionPrompt = DEFAULTS.newSessionPrompt;
    promptBox.value = settings.newSessionPrompt;
    saveSettings();
    pushConfig();
    saveStatus.textContent = '提示词已恢复为默认文案';
  };
  promptBox.onchange = () => {
    settings.newSessionPrompt = promptBox.value;
    saveSettings();
    pushConfig();
  };
  subDirInput.onchange = () => {
    settings.saveSubDir = subDirInput.value.trim();
    saveSettings();
    pushConfig();
  };
  chkAutoJump.onchange = () => {
    settings.autoJumpNewSession = chkAutoJump.checked;
    saveSettings();
    pushConfig();
    send('autoJump', { enabled: chkAutoJump.checked });
  };
  chkAutoInject.onchange = () => {
    settings.autoInjectPrompt = chkAutoInject.checked;
    saveSettings();
    pushConfig();
    send('autoInject', { enabled: chkAutoInject.checked });
  };
  btnOpenChat.onclick = () => {
    if (ISOLATED) chrome.runtime.sendMessage({ type: 'openChatgptNew' });
    else window.open('https://chatgpt.com/', '_blank', 'noopener');
  };

  /* —— 分类与偏置 —— */

  let courseStore = { active: '', list: [] };
  let writerCourses = [];
  let writerRoot = '';
  let saveOnline = false;
  let lastSaveOkAt = 0;

  function activeProfile() {
    return courseStore.list.find((c) => c.name === courseStore.active) || null;
  }
  function ensureProfile(name) {
    if (!name) return null;
    let p = courseStore.list.find((c) => c.name === name);
    if (!p) {
      p = { name, bias: 0, base: null, pauses: [], baseLog: [], updatedAt: Date.now() };
      courseStore.list.push(p);
    }
    return p;
  }
  async function loadCourses() {
    if (!ISOLATED) return;
    try {
      const r = await chrome.storage.local.get('gadCourses');
      if (r && r.gadCourses && Array.isArray(r.gadCourses.list)) courseStore = r.gadCourses;
    } catch { /* 忽略 */ }
  }
  async function saveCourses() {
    if (!ISOLATED) return;
    try { await chrome.storage.local.set({ gadCourses: courseStore }); } catch { /* 忽略 */ }
  }
  function renderCourseSelect() {
    const names = [...new Set([...courseStore.list.map((c) => c.name), settings.saveCourse].filter(Boolean))];
    const cur = courseStore.active || settings.saveCourse || '';
    courseSel.innerHTML = (names.length ? names : ['（还没建分类）'])
      .map((n) => '<option>' + String(n).replace(/[<>&]/g, '') + '</option>').join('');
    if (cur && names.includes(cur)) courseSel.value = cur;
  }
  function courseExtra() {
    const p = activeProfile();
    return {
      courseBase: p ? p.base : null,
      coursePauses: p ? p.pauses : [],
      courseBaseLog: p ? p.baseLog : []
    };
  }
  function applyActiveCourse() {
    const p = activeProfile();
    settings.bias = p ? (Number(p.bias) || 0) : 0;
    settings.saveCourse = courseStore.active || settings.saveCourse;
    pushConfig(courseExtra());
    renderParams();
  }

  courseSel.onchange = () => {
    courseStore.active = courseSel.value;
    saveCourses();
    applyActiveCourse();
    log_local('info', '切换到分类：' + courseStore.active);
  };
  btnNewCourse.onclick = () => {
    newCourseRow.style.display = '';
    newCourseInput.value = '';
    newCourseInput.focus();
  };
  btnNewCancel.onclick = () => { newCourseRow.style.display = 'none'; };
  btnNewOk.onclick = () => {
    const name = newCourseInput.value.trim();
    if (!name) return;
    ensureProfile(name);
    courseStore.active = name;
    saveCourses();
    newCourseRow.style.display = 'none';
    applyActiveCourse();
    log_local('ok', '已新建并切换到分类：' + name);
  };
  newCourseInput.onkeydown = (e) => { if (e.key === 'Enter') btnNewOk.onclick(); };
  btnResetBase.onclick = () => {
    const p = ensureProfile(courseStore.active);
    if (!p) return;
    p.base = null;
    p.pauses = [];
    p.baseLog = [];
    p.updatedAt = Date.now();
    saveCourses();
    send('config', { courseBase: null, coursePauses: [], courseBaseLog: [] });
    log_local('info', '已重置「' + p.name + '」的自动基准和停顿样本');
    renderParams();
  };


  function setSaveState(ok, text, extra) {
    saveOnline = !!ok;
    saveDot.className = 'dot2 ' + (ok ? 'on' : 'off');
    saveStateText.textContent = text;
    saveStatus.textContent = extra || '';
  }

  function pingWriter() {
    if (!ISOLATED) { setSaveState(false, '页面内测试模式，跳过检测'); return; }
    if (!chrome.runtime || !chrome.runtime.sendMessage) { setSaveState(false, '扩展环境不可用'); return; }
    saveStateText.textContent = '本地落盘服务：检测中…';
    try {
      chrome.runtime.sendMessage({ type: 'writerPing' }, async (res) => {
        if (chrome.runtime.lastError || !res || !res.ok) {
          setSaveState(false, '本地落盘服务：未运行',
            '双击 writer\\start-writer.bat 启动它；不启动也不影响录音和发送，只是不落盘。');
          return;
        }
        setSaveState(true, '本地落盘服务：在线',
          (res.version ? 'writer v' + res.version + '　' : '') +
          res.rootDir + '　文件按「' + (res.fileNamePattern || '{date} {weekday} {time}') + '」命名');
        writerRoot = res.rootDir || '';
        setupDot.className = 'dot2 ' + (writerRoot ? 'on' : 'off');
        setupLabel.textContent = writerRoot ? '保存目录已设置' : '首次使用：选择保存文件夹';
        writerCourses = res.courses || [];
        // 首次运行：用本地服务里的分类清单作为初始数据
        if (!courseStore.list.length && writerCourses.length) {
          writerCourses.forEach((n) => ensureProfile(n));
          if (!courseStore.active) courseStore.active = writerCourses[0];
          settings.saveCourse = courseStore.active || settings.saveCourse || '';
          await saveCourses();
          await saveSettings();
          send('config', { ...settings, ...courseExtra() });
          renderParams();
        }
        renderCourseSelect();
        renderParams();
        if (!res.rootDir) discoverRootCandidates();
        if (!res.rootDir && !settings.installerPrompted) setTimeout(() => chooseFolder(true), 350);
      });
    } catch (e) {
      setSaveState(false, '本地落盘服务：查询失败', String(e.message || e));
    }
  }
  setInterval(() => { if (settings.saveEnabled || ISOLATED) pingWriter(); }, 20000);
  setInterval(() => { render(); }, 5000);   // 让"距上次落盘多久"这类相对时间保持新鲜

  /* —— 遥测：把工具自己的运行状态投给本地服务，方便排查"为什么不发送" ——
     只发工具自身的状态和日志（电平、阈值、时长、状态机、错误），
     不包含 ChatGPT 的任何会话内容。本地服务不在时静默忽略。 */
  let lastSentLogMark = '';
  function relayTelemetry() {
    if (!ISOLATED || !chrome.runtime || !chrome.runtime.sendMessage) return;
    const st = status || {};
    // 日志只在有变化时带上：大部分时候往状态里塞 30 条日志纯属浪费
    const mark = logs.length ? (logs[logs.length - 1].t + '#' + logs.length) : '';
    const logsChanged = mark !== lastSentLogMark;
    lastSentLogMark = mark;
    const payload = {
      at: Date.now(),
      pageUrl: st.url || location.href,
      state: st.state,
      stateLabel: st.stateLabel,
      running: !!st.running,
      micReady: !!st.micReady,
      rms: st.rms,
      peak: st.peak,
      floor: st.floor,
      loud: st.loud,
      threshold: st.threshold,
      sampleDt: st.sampleDt,
      windowMs: st.windowMs,
      segMs: st.segMs,
      speechMs: st.speechMs,
      silenceMs: st.silenceMs,
      needPauseMs: st.needPauseMs,
      segmentIndex: st.segmentIndex,
      sentCount: st.sentCount,
      lastReason: st.lastReason,
      settings: {
        autoStart: settings.autoStart,
        autoOpenTab: settings.autoOpenTab,
        minSpeechSec: settings.minSpeechSec,
        minSegmentSec: settings.minSegmentSec,
        maxSegmentSec: settings.maxSegmentSec,
        saveEnabled: settings.saveEnabled,
        saveCourse: settings.saveCourse
      },
      logs: logsChanged ? logs.slice(-25) : []
    };
    try {
      chrome.runtime.sendMessage({ type: 'relayTelemetry', payload }, () => { void chrome.runtime.lastError; });
    } catch { /* 忽略 */ }
  }
  setInterval(relayTelemetry, 10000);

  async function relaySave(payload) {
    if (!ISOLATED) {
      log_local('warn', '（非扩展环境，跳过落盘转发）');
      return;
    }
    await enqueueSave(payload);
  }

  function log_local(level, message) {
    logs.push({ t: new Date().toTimeString().slice(0, 8), level, message });
    render();
  }

  /* ----------------------------- 通信 ----------------------------- */

  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (!d || d.__gad !== true || d.dir !== 'ui') return;
    if (d.type === 'status') { status = d.payload; gotStatus = true; render(); }
    else if (d.type === 'log') { logs.push(d.payload); if (logs.length > 300) logs.shift(); render(); }
    else if (d.type === 'state') { log_local('info', '状态：' + d.payload.label); }
    else if (d.type === 'settings') { settings = { ...settings, ...d.payload }; saveSettings(); render(); }
    else if (d.type === 'running') {
      // 停止后 hook 会立刻推一次；面板必须跟着变，否则看起来像没停
      if (status) { status.running = !!d.payload.running; }
      if (!d.payload.running && status) { status.state = 'off'; status.stateLabel = '已停止'; status.segMs = 0; }
      render();
    }
    else if (d.type === 'save') { relaySave(d.payload); }
    else if (d.type === 'saveQueueState') {
      pageSaveState = {
        waitingIds: Array.isArray(d.payload && d.payload.waitingIds) ? d.payload.waitingIds : [],
        blockedIds: Array.isArray(d.payload && d.payload.blockedIds) ? d.payload.blockedIds : []
      };
      render();
    }
    else if (d.type === 'segment') {
      // 把这一段量到的停顿和工具自己调的基准，按分类存起来
      const payload = d.payload || {};
      const name = payload.course || courseStore.active;
      const p = ensureProfile(name);
      if (p) {
        if (payload.autoBase != null) p.base = payload.autoBase;
        if (Array.isArray(payload.pauses)) p.pauses = payload.pauses.slice(-200);
        if (Array.isArray(payload.autoBaseLog)) p.baseLog = payload.autoBaseLog.slice(-20);
        if (typeof payload.bias === 'number') p.bias = payload.bias;
        p.updatedAt = Date.now();
        saveCourses();
        renderParams();
      }
    }
    else if (d.type === 'ready') {
      send('config', { ...settings, ...courseExtra() });
      send('autoJump', { enabled: settings.autoJumpNewSession !== false });
      send('autoInject', { enabled: settings.autoInjectPrompt !== false });
      send('ping');
    }
  });

  if (ISOLATED) {
    chrome.runtime.onMessage.addListener((msg) => {
      if (!msg || msg.type !== 'togglePanel') return;
      settings.showPanel = !settings.showPanel;
      host.style.display = settings.showPanel ? '' : 'none';
      saveSettings();
      if (settings.showPanel) keepClearSoon();
    });
  }

  /* ----------------------------- 启动 ----------------------------- */

  (async () => {
    settings = await store.load();
    await loadSaveOutbox();
    if (!settings.firstRunDone) {
      settings.firstRunDone = true;
      await saveSettings();
    }
    await loadCourses();
    root.classList.toggle('mini', settings.mini !== false);
    host.style.display = settings.showPanel === false ? 'none' : '';
    pos = settings.pos || null;
    applyPos();
    render();
    // 分类清单第一次是空的：先使用本地 writer 配置中的分类作为初始数据，
    // 之后一律以扩展自己的为准，可以在面板上新建。
    if (courseStore.active) settings.saveCourse = courseStore.active;
    if (!courseStore.list.length && settings.saveCourse) ensureProfile(settings.saveCourse);
    if (!courseStore.active) {
      courseStore.active = settings.saveCourse || (courseStore.list[0] ? courseStore.list[0].name : '');
    }
    settings.saveCourse = courseStore.active || settings.saveCourse || '';
    await saveSettings();
    send('config', { ...settings, ...courseExtra() });
    send('autoJump', { enabled: settings.autoJumpNewSession !== false });
    send('autoInject', { enabled: settings.autoInjectPrompt !== false });
    renderParams();
    // 页面探针在 document_start 就注入了，可能比面板早，所以这里主动催几次状态。
    let tries = 0;
    const ask = setInterval(() => {
      tries += 1;
      send('ping');
      if (tries >= 6 || gotStatus) clearInterval(ask);
    }, 400);
    setTimeout(() => { clampPos(); keepClearOfComposer(); }, 300);
    setTimeout(() => { clampPos(); keepClearOfComposer(); }, 1500);
    if (settings.saveEnabled) setTimeout(pingWriter, 800);
    else setTimeout(pingWriter, 2500);      // 没开落盘也探一次，小条上要能看出服务在不在
    setTimeout(discoverRootCandidates, 1200);
    setTimeout(pumpSaveQueue, 900);
  })();
})();
