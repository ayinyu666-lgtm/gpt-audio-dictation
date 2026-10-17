/*
 * GPT Audio Dictation —— 页面侧核心逻辑（MAIN world，document_start 注入）
 *
 * 做四件事：
 *  1. 挂钩 navigator.mediaDevices.getUserMedia。ChatGPT 的「听写」正是用这个取麦克风，
 *     我们在它拿到的那一路 MediaStream 上挂 AnalyserNode，于是能读到它"正在录的那一路"音频电平。
 *     不额外占用麦克风，也不需要系统音频回环，更不用去猜网页上那条波形。
 *  2. 读页面状态、点按钮（听写 / 转录并发送 / 发送）。
 *  3. 跑自动循环：开录 → 判断该收口 → 发送 → 立刻开下一段。
 *  4. 通过 window.postMessage 和扩展的界面侧通信。
 *
 * 采样窗口自适应：前台 100ms 采一次，用 4096 点窗（约 85ms）；
 * 标签页被浏览器降频到 1s 时自动换成 32768 点窗（约 683ms），保证不把"没看到的音频"误判成静音。
 */
(() => {
  if (window.__gadMain && window.__gadMain.version >= 1) return;

  const DEFAULTS = {
    preset: 'normal',
    maxSegmentSec: 300,
    minSpeechSec: 4,
    minSegmentSec: 6,
    pauseFloorMs: 0,
    pauseCeilMs: 0,
    silenceThreshold: 0,
    floorFactor: 2.5,
    loudCap: 0.45,
    smooth: 0.08,
    settleAfterSendMs: 400,
    autoStart: true,
    autoOpenTab: false,
    finishOnStop: true,
    saveEnabled: true,
    savePreferenceSet: false
  };

  const PRESETS = {
    normal: {},
    // 快速测试：把偏置推满、停顿门槛直接钉短，几十秒就能看完一轮循环
    test: { bias: 3, maxSegmentSec: 45, minSpeechSec: 2, minSegmentSec: 4, settleAfterSendMs: 300, pauseCeilMs: 1400, pauseFloorMs: 400 }
  };

  const STATE_LABEL = {
    off: '已停止',
    waiting: '等待页面',
    idle: '待命',
    recording: '录音中',
    finalizing: '转录中',
    error: '异常'
  };

  const lerp = (a, b, t) => a + (b - a) * t;
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
  const now = () => Date.now();

  const S = {
    version: 1,
    settings: { ...DEFAULTS },
    running: false,
    state: 'off',
    stateSince: now(),
    logs: [],
    segments: [],
    sentCount: 0,
    segmentIndex: 0,
    lastReason: '',
    error: '',
    level: { rms: 0, peak: 0, t: 0 },
    floor: 0.006,
    loud: 0.05,
    threshold: 0.012,
    hist: [],
    // 自动调整：工具自己学到的激进程度基准，以及量到的停顿分布
    autoBase: null,          // null = 还没学到，先用默认 4
    pauses: [],              // 每次真实停顿的时长（毫秒），滚动保留
    recentSegs: [],          // 最近几段的说话时长与是否被硬上限逼发
    autoBaseLog: [],
    windowMs: 85,
    sampleDt: 100,
    segment: null,
    pending: null,
    pendingComposerText: '',
    saveQueue: [],
    lastQueuedUserOrdinalByRoute: Object.create(null),
  };
  window.__gadMain = S;

  // 页面刷新/关闭不会重建扩展面板的 outbox；把尚未交给面板的轻量任务留在本机
  // localStorage 里。只保存任务元数据，不保存回答正文；回答由页面恢复后重新读取。
  const SAVE_QUEUE_STORAGE_KEY = 'gad.pageSaveQueue.v1';
  const SAVE_QUEUE_ITEM_PREFIX = 'gad.pageSaveJob.v1:';
  const SAVE_ANSWER_TIMEOUT_MS = 10 * 60 * 1000;
  const SAVE_ROUTE_MIGRATION_MAX_AGE_MS = 10 * 60 * 1000;
  let lastPersistedPageQueueIds = new Set();
  const queueRouteKey = (value) => {
    try {
      const u = new URL(String(value || location.href), location.href);
      u.hash = '';
      return u.href;
    } catch {
      return String(value || location.href);
    }
  };
  function rootToConversationRoute(fromRoute, toRoute) {
    try {
      const from = new URL(fromRoute);
      const to = new URL(toRoute);
      return from.origin === to.origin && from.pathname === '/' && /^\/c\//.test(to.pathname);
    } catch {
      return false;
    }
  }
  function migrateRootQueueJobs(fromHref, toHref) {
    const fromRoute = queueRouteKey(fromHref);
    const toRoute = queueRouteKey(toHref);
    if (!rootToConversationRoute(fromRoute, toRoute)) return false;
    let changed = 0;
    for (const job of S.saveQueue) {
      if ((job.routeKey || queueRouteKey(job.url)) !== fromRoute) continue;
      if (now() - (Number(job.queuedAt) || 0) > SAVE_ROUTE_MIGRATION_MAX_AGE_MS) continue;
      job.routeKey = toRoute;
      job.url = location.href;
      changed += 1;
    }
    if (changed) persistSaveQueue();
    return changed > 0;
  }
  function persistSaveQueue() {
    try {
      const snapshot = S.saveQueue.map((job) => ({
        saveId: job.saveId,
        index: job.index,
        at: job.at,
        url: job.url,
        routeKey: job.routeKey || queueRouteKey(job.url),
        queuedAt: job.queuedAt,
        sessionStart: job.sessionStart,
        durationSec: job.durationSec,
        speechSec: job.speechSec,
        course: job.course || '',
        userOrdinal: Number(job.userOrdinal) || 0,
        userFingerprint: job.userFingerprint || '',
        userFingerprintSource: job.userFingerprintSource || '',
        blocked: job.blocked === true,
        lastError: job.lastError || ''
      }));
      const currentIds = new Set(snapshot.map((job) => job.saveId));
      for (const job of snapshot) {
        localStorage.setItem(SAVE_QUEUE_ITEM_PREFIX + job.saveId, JSON.stringify(job));
      }
      for (const oldId of lastPersistedPageQueueIds) {
        if (!currentIds.has(oldId)) {
          localStorage.removeItem(SAVE_QUEUE_ITEM_PREFIX + oldId);
        }
      }
      lastPersistedPageQueueIds = currentIds;
      localStorage.removeItem(SAVE_QUEUE_STORAGE_KEY);
    } catch (e) {
      log('warn', '页面待保存队列无法写入本地存储：' + String(e.message || e));
    }
    postSaveQueueState();
  }
  let lastSaveQueueState = '';
  function postSaveQueueState(force = false) {
    const state = {
      waitingIds: S.saveQueue.filter((job) => !job.blocked).map((job) => job.saveId),
      blockedIds: S.saveQueue.filter((job) => job.blocked).map((job) => job.saveId)
    };
    const signature = JSON.stringify(state);
    if (!force && signature === lastSaveQueueState) return;
    lastSaveQueueState = signature;
    post('saveQueueState', state);
  }
  function restoreSaveQueue() {
    try {
      const raw = localStorage.getItem(SAVE_QUEUE_STORAGE_KEY);
      let list = [];
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed)) list.push(...parsed);
      }
      for (const key of Object.keys(localStorage)) {
        if (!key.startsWith(SAVE_QUEUE_ITEM_PREFIX)) continue;
        try {
          const item = JSON.parse(localStorage.getItem(key) || 'null');
          if (item && typeof item === 'object') list.push(item);
        } catch { /* 单条损坏不影响其他保存任务 */ }
      }
      if (!list.length) return;
      const seen = new Set();
      S.saveQueue = list
        .filter((job) => job && typeof job === 'object' && typeof job.saveId === 'string' && job.saveId)
        .filter((job) => {
          if (seen.has(job.saveId)) return false;
          seen.add(job.saveId);
          return true;
        })
        .map((job) => ({
          ...job,
          routeKey: job.routeKey || queueRouteKey(job.url),
          queuedAt: Number(job.queuedAt) || now(),
          submittedAt: Number(job.submittedAt) || 0,
          course: String(job.course || ''),
          userOrdinal: Number(job.userOrdinal) || 0,
          userFingerprint: String(job.userFingerprint || ''),
          userFingerprintSource: String(job.userFingerprintSource || ''),
          blocked: job.blocked === true,
          lastError: String(job.lastError || ''),
          lastSeen: '',
          stable: 0,
          busy: false
        }));
      if (S.saveQueue.length) {
        for (const job of S.saveQueue) {
          const route = job.routeKey || queueRouteKey(job.url);
          S.lastQueuedUserOrdinalByRoute[route] = Math.max(
            Number(S.lastQueuedUserOrdinalByRoute[route]) || 0,
            Number(job.userOrdinal) || 0
          );
        }
        log('info', '已从页面刷新前的会话队列恢复 ' + S.saveQueue.length + ' 个待保存段落');
      }
      lastPersistedPageQueueIds = new Set(S.saveQueue.map((job) => job.saveId));
      persistSaveQueue();
      postSaveQueueState();
    } catch (e) {
      log('warn', '页面待保存队列读取失败：' + String(e.message || e));
    }
  }
  restoreSaveQueue();

  /* ------------------------------ 通信 ------------------------------ */

  function post(type, payload) {
    try {
      window.postMessage({ __gad: true, dir: 'ui', type, payload }, location.origin);
    } catch { /* 忽略 */ }
  }

  function log(level, message) {
    const rec = { t: new Date().toTimeString().slice(0, 8), level, message: String(message) };
    S.logs.push(rec);
    if (S.logs.length > 300) S.logs.shift();
    post('log', rec);
  }

  S.setSettings = function setSettings(patch) {
    if (!patch) return;
    const preset = PRESETS[patch.preset || S.settings.preset] || {};
    S.settings = { ...S.settings, ...preset, ...patch };
    // Old panel versions sent saveEnabled=false without an explicit preference.
    // Migrate that state once so a reload does not silently discard transcripts.
    if (patch.savePreferenceSet !== true && patch.saveEnabled === false) {
      S.settings.saveEnabled = true;
    }
    if (patch.preset) S.settings.preset = patch.preset;
    // 偏置：你滑的那一格，叠在工具自己学到的基准上
    if (patch.bias !== undefined) S.settings.bias = clamp(Number(patch.bias) || 0, -3, 3);
    // 分类切换：把该分类已学到的基准和停顿样本接过来
    if (patch.courseBase !== undefined) {
      S.autoBase = (patch.courseBase === null || patch.courseBase === '' || patch.courseBase === undefined)
        ? null : clamp(Number(patch.courseBase), 1, 10);
    }
    if (Array.isArray(patch.coursePauses)) {
      S.pauses = patch.coursePauses.filter((v) => Number.isFinite(v) && v > 0).slice(-200);
    }
    if (patch.courseBaseLog !== undefined && Array.isArray(patch.courseBaseLog)) {
      S.autoBaseLog = patch.courseBaseLog.slice(-20);
    }
    post('settings', S.settings);
  };

  window.addEventListener('message', (ev) => {
    const d = ev.data;
    if (!d || d.__gad !== true || d.dir !== 'main') return;
    switch (d.type) {
      case 'config':
        S.setSettings(d.payload);
        // 如果用户把"自动开始"关了，而这一轮是我们自动开的，就停下来
        if (S.autoStarted && S.running && !S.settings.autoStart) {
          S.autoStarted = false;
          api.stop();
          log('info', '自动开始已关闭，本轮停止');
        }
        api.pushStatus();
        break;
      case 'start':
        api.start(d.payload && d.payload.reason);
        break;
      case 'stop':
        api.stop();
        break;
      case 'ping':
        postSaveQueueState(true);
        api.pushStatus();
        break;
      case 'calibrate': {
        const lvl = readLevel();
        if (!lvl) { log('warn', '还没挂上麦克风，先点一次「开始」再校准'); break; }
        S.floor = Math.max(0.0005, lvl.rms);
        S.threshold = Math.max(0.008, S.floor * 3.5);
        log('ok', '已按当前环境校准：本底 ' + S.floor.toFixed(4) + '，判定阈值 ' + S.threshold.toFixed(4) +
          (S.settings.silenceThreshold > 0 ? '（注意：手动阈值会覆盖自动值）' : ''));
        api.pushStatus();
        break;
      }
      case 'saveResult': {
        const result = d.payload || {};
        const idx = S.saveQueue.findIndex((job) => job.saveId === result.saveId);
        if (idx < 0) break;
        if (result.ok) {
          const job = S.saveQueue[idx];
          S.saveQueue.splice(idx, 1);
          persistSaveQueue();
          log('ok', '第 ' + job.index + ' 段已确认落盘' + (result.duplicate ? '（重复请求已去重）' : ''));
        } else if (result.retryable === false) {
          const job = S.saveQueue[idx];
          job.blocked = true;
          job.submittedAt = 0;
          job.busy = false;
          job.lastError = String(result.error || '未知原因');
          persistSaveQueue();
          log('error', '第 ' + job.index + ' 段落盘失败且已保留，等待手动重试：' + job.lastError);
        } else {
          S.saveQueue[idx].submittedAt = 0;
          S.saveQueue[idx].busy = false;
          S.saveQueue[idx].lastError = String(result.error || '未知原因');
          persistSaveQueue();
          log('warn', '第 ' + S.saveQueue[idx].index + ' 段落盘待重试：' + String(result.error || '未知原因'));
        }
        break;
      }
      case 'retryPageSaves': {
        let changed = 0;
        let missingCourse = 0;
        for (const job of S.saveQueue) {
          if (!job.blocked) continue;
          if (!job.course) {
            job.lastError = '这段没有分类名，不能用当前分类猜测；请先在面板明确选择分类';
            missingCourse += 1;
            continue;
          }
          // 上一次若因输入框指纹不准而失败，重试时回到稳定的页面轮次匹配。
          if (job.userFingerprintSource === 'composer') {
            job.userFingerprint = '';
            job.userFingerprintSource = '';
          }
          job.blocked = false;
          job.submittedAt = 0;
          job.busy = false;
          job.stable = 0;
          job.lastError = '';
          changed += 1;
        }
        if (changed) {
          persistSaveQueue();
          log('info', '已重新检查 ' + changed + ' 个失败的页面保存任务');
        }
        if (missingCourse) {
          persistSaveQueue();
          log('error', missingCourse + ' 个页面保存任务缺少分类，未使用当前分类猜测目录');
        }
        break;
      }
      case 'assignBlockedCourse': {
        const course = String(d.payload && d.payload.course || '').trim();
        if (!course) break;
        let changed = 0;
        for (const job of S.saveQueue) {
          if (!job.blocked) continue;
          if (!job.course) job.course = course;
          if (job.userFingerprintSource === 'composer') {
            job.userFingerprint = '';
            job.userFingerprintSource = '';
          }
          job.blocked = false;
          job.submittedAt = 0;
          job.busy = false;
          job.stable = 0;
          job.lastError = '';
          changed += 1;
        }
        if (changed) {
          persistSaveQueue();
          log('info', '已为用户明确选择的分类重新检查 ' + changed + ' 个页面保存任务');
        }
        break;
      }
      default:
        break;
    }
  });

  /* ------------------------- 1. 麦克风电平 ------------------------- */

  function installMicHook() {
    if (S.micWrapped) return true;
    const md = navigator.mediaDevices;
    if (!md || typeof md.getUserMedia !== 'function') return false;
    const original = md.getUserMedia.bind(md);
    md.getUserMedia = async function (constraints) {
      const stream = await original(constraints);
      try {
        if (!constraints || constraints.audio) {
          /*
           * 关键：ChatGPT 每开一段听写都会走一次 getUserMedia，拿到的是一条全新的流。
           * 早先这里只在"还没分析器"时挂一次，于是从第二段起我们还在读上一段那条
           * 已经结束的流 —— 电平恒为 0、说话时长恒为 0、永远不发送。实测就是这个。
           * 现在每次新流都重新挂，并把旧的分析器断开。
           */
          const AC = window.AudioContext || window.webkitAudioContext;
          const ac = S.audioContext || new AC();
          await ac.resume().catch(() => {});
          const src = ac.createMediaStreamSource(stream);
          const an = ac.createAnalyser();
          an.fftSize = 4096;
          an.smoothingTimeConstant = 0;
          src.connect(an);
          const oldAnalyser = S.analyser;
          const oldSource = S.sourceNode;
          S.analyser = an;
          S.sourceNode = src;
          S.buffer = new Float32Array(an.fftSize);
          S.audioContext = ac;
          S.micReadyAt = now();
          S.zeroRunSince = 0;
          try { if (oldSource) oldSource.disconnect(); } catch { /* 忽略 */ }
          try { if (oldAnalyser) oldAnalyser.disconnect(); } catch { /* 忽略 */ }
          log('info', '已挂上麦克风通道（第 ' + (S.streams = (S.streams || 0) + 1) + ' 条流）');
          post('mic', { ready: true, sampleRate: ac.sampleRate });
        }
      } catch (e) {
        S.error = '挂接分析器失败: ' + e;
        log('error', S.error);
      }
      return stream;
    };
    S.micWrapped = true;
    return true;
  }
  installMicHook();

  /** 采样窗口跟随实际采样间隔走，避免被降频时把没看到的音频当成静音。 */
  function tuneWindow(dtMs, sampleRate) {
    const rate = sampleRate || 48000;
    // 向上取整到 2 的幂：窗口必须覆盖整个采样间隔，否则会漏掉音频、把说话当成静音
    const wanted = clamp(Math.round((dtMs / 1000) * rate), 2048, 32768);
    const pow = Math.pow(2, Math.ceil(Math.log2(wanted)));
    if (!S.analyser) return;
    if (S.analyser.fftSize !== pow) {
      S.analyser.fftSize = pow;
      S.buffer = new Float32Array(pow);
    }
    S.windowMs = (pow / rate) * 1000;
  }

  function readLevel() {
    const an = S.analyser;
    if (!an) return null;
    an.getFloatTimeDomainData(S.buffer);
    const b = S.buffer;
    let sum = 0;
    let peak = 0;
    for (let i = 0; i < b.length; i++) {
      const v = b[i];
      sum += v * v;
      const a = v < 0 ? -v : v;
      if (a > peak) peak = a;
    }
    return { rms: Math.sqrt(sum / b.length), peak, t: now(), samples: b.length, rate: S.audioContext ? S.audioContext.sampleRate : 48000 };
  }

  S.read = readLevel;

  /*
   * 自适应本底与阈值。
   *
   * 教室里底噪是变的：有时安静，有时投影仪/空调/远处说话的底噪明显更大。
   * 固定阈值不行；只取"最低值"也不行——一直有人讲话时它会一路爬到说话声上，
   * 于是说话声够不到阈值、说话时长永远是 0、门槛永远不会下降，**彻底不发送**（死锁）。
   *
   * 所以同时估两边：
   *   本底 floor = 最近 60 秒的 5% 分位  → 贴着"当前环境最安静时"的水平，随底噪涨落
   *   大声 loud  = 最近 60 秒的 90% 分位 → 近似"说话时有多大"
   * 阈值 = min(floor × 本底倍数, loud × 大声上限)，再夹在 [0.006, 0.06]：
   *   - 底噪变大 → floor 上升 → 阈值跟着上升，不会把底噪当说话
   *   - 底噪变小 → floor 下降 → 阈值跟着下降
   *   - 但永远不超过"最近大声"的那个比例，**保证说得再轻也一定能被判定为说话**，死锁不可能发生
   *
   * 三个系数（本底倍数 / 大声上限 / 平滑）都在面板上可调。
   */
  function updateFloor(rms) {
    const ff = clamp(Number(S.settings.floorFactor) || 2.5, 1, 10);
    const lc = clamp(Number(S.settings.loudCap) || 0.45, 0.1, 1);
    const sm = clamp(Number(S.settings.smooth) || 0.08, 0.02, 0.5);
    const manual = Number(S.settings.silenceThreshold) || 0;
    if (manual > 0) {
      S.floor = manual / ff;
      S.loud = manual * 2.2;
      S.threshold = manual;
      return;
    }

    S.hist.push(rms);
    if (S.hist.length > 600) S.hist.shift();      // 约 60 秒

    if (S.hist.length >= 20) {
      const sorted = S.hist.slice().sort((a, b) => a - b);
      S.floor = clamp(sorted[Math.floor(sorted.length * 0.05)], 0.0003, 0.05);
      S.loud = sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.9))];
    }

    let target = clamp(S.floor * ff, 0.007, 0.06);
    if (S.loud > 0.015) target = Math.min(target, S.loud * lc);
    target = clamp(target, 0.006, 0.06);
    // 平滑：底噪缓慢漂移时不跟得太急，突然变吵/变静时也能在几秒内跟上
    S.threshold = S.threshold * (1 - sm) + target * sm;
  }

  /* ------------------------- 2. 页面状态与点击 ------------------------- */

  /*
   * 工具只看"按钮状态"和"输入框是否为空"，不读会话内容、不读 ChatGPT 的回答。
   * 需要知道的只有四件事：现在在录音吗、在转录吗、麦克风按钮可用吗、输入框里还有没有文字。
   */
  const BUTTON_LABELS = {
    dictate: ['听写', '开始听写', 'Dictate', 'Start dictation', 'Diktieren'],
    transcribeSend: ['转录并发送', '发送转录', 'Transcribe and send', 'Transcribe & send', 'Transkribieren und senden'],
    cancelTranscribe: ['取消转录', '停止转录', 'Cancel transcription', 'Stop transcription', 'Transkription abbrechen'],
    cancelDictate: [
      '取消听写', '停止听写', '正在启动听写；点击可取消',
      'Cancel dictation', 'Stop dictation', 'Starting dictation; click to cancel',
      'Diktat abbrechen', 'Diktat wird gestartet'
    ],
    send: ['发送', 'Send', 'Senden']
  };

  function buttonFor(labels) {
    const list = Array.isArray(labels) ? labels : [labels];
    for (const label of list) {
      const exact = document.querySelector('button[aria-label="' + label + '"]');
      if (exact) return exact;
    }
    // The send control has a stable test id across several ChatGPT layouts.
    if (list === BUTTON_LABELS.send) {
      const byTestId = document.querySelector('[data-testid="send-button"]');
      if (byTestId) return byTestId;
    }
    return null;
  }

  const btn = (kind) => buttonFor(BUTTON_LABELS[kind] || kind);

  function readUi() {
    const pm = document.querySelector('div.ProseMirror[contenteditable="true"]')
      || document.querySelector('[contenteditable="true"]');
    const dictationActive = !!btn('transcribeSend') || !!btn('cancelDictate');
    return {
      hasComposer: !!pm,
      composerText: pm ? (pm.innerText || '').replace(/\s+/g, '') : '',
      recording: dictationActive,
      dictationStarting: !btn('transcribeSend') && !!btn('cancelDictate'),
      transcribing: !!btn('cancelTranscribe'),
      micIdle: !!btn('dictate'),
      generating: !!buttonFor(['停止', 'Stop', 'Stopp'])
    };
  }

  const click = (label) => {
    const b = btn(label);
    if (!b) return false;
    b.click();
    return true;
  };

  /* ------------------------- 3. 参数与决策 ------------------------- */

  /** 生效激进程度 = 工具学到的自动基准 + 你的偏置。 */
  function effectiveAgg() {
    const base = (S.autoBase == null) ? 4 : clamp(Number(S.autoBase), 1, 10);
    const bias = clamp(Number(S.settings.bias) || 0, -3, 3);
    return Math.round(clamp(base + bias, 1, 10) * 10) / 10;
  }

  /** 从量到的停顿里取分位；样本不够返回 null（那时退回默认值）。 */
  function pausePercentile(p) {
    const arr = S.pauses;
    if (!arr || arr.length < 12) return null;
    const s = arr.slice().sort((a, b) => a - b);
    return s[clamp(Math.floor(s.length * p), 0, s.length - 1)];
  }

  /**
   * 把生效激进程度 + 量到的停顿节奏换算成一组阈值。
   *
   * 两件事分开：
   *   - 停顿门槛（多长的停顿算"该收口"）优先用**这位讲师自己的节奏**（实测分位），
   *     样本不够才退回按激进程度插值的默认值；
   *   - 激进程度决定"多快开始收紧"（rampStart / softTarget），并作为一个整体缩放
   *     作用在实测门槛上：偏置调快，门槛就变短。
   */
  function tuning() {
    const st = S.settings;
    const eff = effectiveAgg();
    const t = (eff - 1) / 9;
    const rampStartSec = Math.round(lerp(24, 6, t));
    const softTargetSec = Math.round(lerp(140, 40, t));
    const maxSegmentMs = clamp((Number(st.maxSegmentSec) || 300) * 1000, 20000, 540000);
    const minSpeechMs = clamp((Number(st.minSpeechSec) || 4) * 1000, 1000, 60000);
    const minSegmentMs = clamp((Number(st.minSegmentSec) || 6) * 1000, 2000, 120000);

    const aggScale = clamp(1 + (4 - eff) * 0.12, 0.6, 1.8);
    const p50 = pausePercentile(0.5);
    const p90 = pausePercentile(0.9);
    const p30 = pausePercentile(0.3);

    let maxPauseMs, minPauseMs, ultimateMs, learned;
    if (p90 != null) {
      learned = true;
      maxPauseMs = clamp(Math.round(p90 * 1.1 * aggScale), 900, 4000);
      minPauseMs = clamp(Math.round(p50 * 1.3 * aggScale), 350, 1800);
      ultimateMs = clamp(Math.round(p30 * 1.1 * aggScale), 250, 900);
    } else {
      learned = false;
      maxPauseMs = Math.round(lerp(3200, 900, t));
      minPauseMs = Math.round(lerp(950, 380, t));
      ultimateMs = clamp(Math.round(minPauseMs * 0.5), 300, minPauseMs);
    }

    // 手动覆盖永远优先
    if (Number(st.pauseCeilMs) > 0) maxPauseMs = clamp(Number(st.pauseCeilMs), 200, 8000);
    if (Number(st.pauseFloorMs) > 0) minPauseMs = clamp(Number(st.pauseFloorMs), 150, 5000);
    minPauseMs = Math.min(minPauseMs, maxPauseMs);
    ultimateMs = Math.min(ultimateMs, minPauseMs);

    return {
      basePauseMs: maxPauseMs,
      minPauseMs,
      rampStartSec,
      softTargetSec,
      maxSegmentMs,
      minSpeechMs,
      minSegmentMs,
      maxPauseMs,
      ultimateMs,
      extraRampSec: 60,
      effectiveAgg: eff,
      autoBase: (S.autoBase == null ? null : S.autoBase),
      bias: clamp(Number(S.settings.bias) || 0, -3, 3),
      learned,
      pauseN: S.pauses ? S.pauses.length : 0,
      pauseP50: p50,
      pauseP90: p90
    };
  }

  /** 本段现在要求的「连续静音多久就收口」。 */
  function requiredPauseMs(speechMs) {
    const t = tuning();
    const s = speechMs / 1000;
    if (s <= t.rampStartSec) return t.maxPauseMs;
    const k1 = clamp((s - t.rampStartSec) / Math.max(1, t.softTargetSec - t.rampStartSec), 0, 1);
    if (k1 < 1) return Math.round(lerp(t.maxPauseMs, t.minPauseMs, k1));
    const k2 = clamp((s - t.softTargetSec) / t.extraRampSec, 0, 1);
    return Math.round(lerp(t.minPauseMs, t.ultimateMs, k2));
  }

  /*
   * 两种结局：send（收口发送）/ wait。
   * 空段不管：没人说话就一直录着，等真有人说话、并且攒够 minSpeech 之后才可能发送。
   */
  function evaluate(seg) {
    const t = tuning();
    const age = now() - seg.startTs;
    if (seg.speechMs < t.minSpeechMs) return { action: 'wait' };
    const need = requiredPauseMs(seg.speechMs);

    if (age >= t.maxSegmentMs) {
      return { action: 'send', why: '到达硬上限 ' + Math.round(t.maxSegmentMs / 1000) + 's（兜底，正常不该走到这里）' };
    }
    if (age < t.minSegmentMs) return { action: 'wait' };
    if (seg.silenceMs >= need) {
      return {
        action: 'send',
        why: '停顿 ' + (seg.silenceMs / 1000).toFixed(1) + 's ≥ 门槛 ' + (need / 1000).toFixed(1) +
          's（已说 ' + Math.round(seg.speechMs / 1000) + 's）'
      };
    }
    return { action: 'wait' };
  }

  /* ------------------------- 4. 主循环 ------------------------- */

  function setState(next, note) {
    if (S.state === next) return;
    S.state = next;
    S.stateSince = now();
    if (note) log('info', '状态 → ' + STATE_LABEL[next] + '：' + note);
    post('state', { state: next, label: STATE_LABEL[next] });
    // 状态一变就推一次：早先只在"运行时"每 250ms 推，一停止面板就永远停在旧状态，
    // 看起来就像"点了停止没反应"。
    postStatus();
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  const api = {
    start(reason) {
      if (S.running) return;
      S.running = true;
      S.sessionStart = now();      // 这节课的起点，落盘文件名用它
      S.segmentIndex = 0;
      S.sentCount = 0;
      S.lastReason = '';
      S.segments = [];
      S.error = '';
      S.floor = 0.006;
      log('info', '▶ 自动听写已开启' + (reason ? '（' + reason + '）' : ''));
      setState('waiting');
      post('running', { running: true });
    },
    stop() {
      if (!S.running) return;
      S.running = false;
      S.nextStartAt = 0;
      log('info', '■ 已停止');
      setState('off');
      post('running', { running: false });
      postStatus();
    },
    pushStatus() { postStatus(); },
    snapshot() { return buildStatus(); }
  };
  S.api = api;

  let tickCount = 0;

  async function tick() {
    tickCount += 1;
    lastProgress = now();      // 心跳：看门狗靠这个判断主循环是不是真的卡住了
    try {
      if (!S.analyser) installMicHook();

      const ui = readUi();
      if (!ui.hasComposer) {
        if (S.running) setState('waiting', '输入框还没出现（可能没登录）');
        return;
      }

      if (!S.running) {
        if (S.state !== 'off') setState('off');
        return;
      }

      switch (S.state) {
        case 'waiting':
        case 'error':
          setState('idle');
          return;
        case 'idle':
          await tryStart(ui);
          return;
        case 'recording':
          await recordingTick(ui);
          return;
        case 'finalizing':
          await finalizingTick(ui);
          return;
        default:
          setState('idle');
      }
    } catch (e) {
      S.error = String(e && e.message ? e.message : e);
      log('error', '循环出错：' + S.error);
    }
  }

  async function tryStart(ui) {
    // 页面已经在录音了（用户自己点的，或上一轮留下的），直接接管，不要干等
    if (ui.recording) {
      S.segmentIndex += 1;
      S.segment = {
        index: S.segmentIndex,
        userTurnBase: outerMatches(USER_MESSAGE_SELECTOR).length,
        saveCourse: String(S.settings.saveCourse || ''),
        startTs: now(),
        lastTs: now(),
        speechMs: 0,
        silenceMs: 0,
        rms: 0,
        peak: 0,
        samples: 0,
        lostSamples: 0,
        speaking: false,
        silenceRunMs: 0,
      };
      S.pendingComposerText = '';
      setState('recording', '接管已在进行的录音');
      log('info', '页面本来就在录音，直接接管这一段');
      return;
    }
    if (!ui.micIdle) return;
    // 失败退避：刚发完一段时「听写」按钮往往要过一两秒才回来，
    // 不退避就会每 100ms 点一次、日志被刷爆（实测刷了几百条）
    if (S.nextStartAt && now() < S.nextStartAt) return;
    if (!click('dictate')) return;
    let after = readUi();
    for (let i = 0; i < 8 && !after.recording && !after.transcribing; i += 1) {
      await sleep(250);
      after = readUi();
    }
    if (!after.recording && !after.transcribing) {
      S.nextStartAt = now() + 2000;
      log('warn', '点了「听写」但 2 秒内没进入录音状态，稍后重试');
      return;
    }
    S.nextStartAt = 0;
    S.segmentIndex += 1;
    S.segment = {
      index: S.segmentIndex,
      userTurnBase: outerMatches(USER_MESSAGE_SELECTOR).length,
      saveCourse: String(S.settings.saveCourse || ''),
      startTs: now(),
      lastTs: now(),
      speechMs: 0,
      silenceMs: 0,
      rms: 0,
      peak: 0,
      samples: 0,
      lostSamples: 0,
      speaking: false,
        silenceRunMs: 0,
    };
    S.pendingComposerText = '';
    setState('recording', '第 ' + S.segmentIndex + ' 段');
  }

  async function recordingTick(ui) {
    const seg = S.segment;
    if (!seg) { setState('idle'); return; }

    if (!ui.recording && !ui.transcribing) {
      log('warn', '录音被页面自己结束了（本段 ' + Math.round((now() - seg.startTs) / 1000) + 's）');
      if (ui.composerText) beginFinalize('页面自动结束录音', ui, false);
      else { S.segment = null; setState('idle'); }
      return;
    }

    const lvl = readLevel();
    if (!lvl) {
      // 探针挂晚了：ChatGPT 已经拿到麦克风流，我们没拦住。只能重开这一段。
      seg.lostSamples += 1;
      log('warn', '读不到麦克风电平（探针挂晚了），重开这一段');
      click('cancelDictate');
      await sleep(700);
      S.segment = null;
      setState('idle');
      return;
    }
    seg.lostSamples = 0;

    /*
     * 安全网：如果分析器读出来的一直是绝对零（不是"很小"，是 0），
     * 说明我们挂在那条已经结束的流上。这时候不管怎么等都等不到声音，
     * 直接取消这一段并把分析器置空，下次 ChatGPT 开新流时会重新挂上。
     */
    if (lvl.rms === 0 && lvl.peak === 0) {
      if (!S.zeroRunSince) S.zeroRunSince = now();
      if (now() - S.zeroRunSince > 8000) {
        log('warn', '麦克风那一路连续 8 秒全是绝对零（挂到了已结束的流），重开这一段');
        click('cancelDictate');
        await sleep(700);
        S.analyser = null;
        S.sourceNode = null;
        S.zeroRunSince = 0;
        S.segment = null;
        setState('idle');
        return;
      }
    } else {
      S.zeroRunSince = 0;
    }

    const t0 = seg.lastTs;
    seg.lastTs = lvl.t;
    const dt = clamp(lvl.t - t0, 10, 4000);
    S.sampleDt = dt;
    tuneWindow(dt, lvl.rate);

    /*
     * 判定"在说话"只看 rms，并带迟滞：已经判定说话的，要掉到阈值的 70% 以下才算停。
     * 常态下不用峰值——实测峰值会把键盘声、翻页声、咳嗽都算成说话，那样"连续静音"永远攒不起来，永远发不出去。
     */
    const wasSpeaking = seg.speaking;
    const speaking = seg.speaking ? lvl.rms > S.threshold * 0.7 : lvl.rms > S.threshold;
    seg.speaking = speaking;
    const credited = Math.min(dt, S.windowMs);
    updateFloor(lvl.rms);

    seg.samples += 1;
    seg.rms = lvl.rms;
    if (lvl.peak > seg.peak) seg.peak = lvl.peak;
    S.level = { rms: lvl.rms, peak: lvl.peak, t: lvl.t };

    if (speaking) {
      // 刚从静音转回说话：刚刚那一段静音就是一次真实停顿，记下来当"这位讲师的节奏"
      if (!wasSpeaking) {
        const run = seg.silenceRunMs || 0;
        if (run >= 200 && run <= 8000) {
          S.pauses.push(Math.round(run));
          if (S.pauses.length > 200) S.pauses.shift();
        }
      }
      seg.silenceRunMs = 0;
      seg.speechMs += credited;
      seg.silenceMs = 0;
    } else {
      seg.silenceRunMs = (seg.silenceRunMs || 0) + credited;
      seg.silenceMs += credited;
    }

    /*
     * 保险丝：录了很久却一次都没判定到说话，说明阈值估偏了（死锁）。
     * 这时候不能干等——直接把阈值压到"最近大声"的 35% 以下，并把原因写进日志。
     * 这是"发送完全不发生"这个故障的兜底，正常情况下永远不会触发。
     */
    if (seg.speechMs === 0 && !seg.thresholdForced && now() - seg.startTs > 40000) {
      seg.thresholdForced = true;
      const before = S.threshold;
      S.threshold = Math.max(0.006, Math.min(S.threshold, S.loud * 0.35));
      log('warn', '录了 40 秒一次都没判定到说话（本底 ' + S.floor.toFixed(3) + '，大声 ' + S.loud.toFixed(3) +
        '，阈值 ' + before.toFixed(3) + ' → ' + S.threshold.toFixed(3) + '）——已强制下调阈值');
    }

    const verdict = evaluate(seg);
    if (verdict.action === 'send') beginFinalize(verdict.why, ui, true);
  }

  function beginFinalize(reason, ui, viaClick) {
    const seg = S.segment;
    if (!seg) return;
    S.lastReason = reason;
    const dispatchAttempted = viaClick ? click('transcribeSend') : false;
    S.pending = { seg, reason, t0: now(), dispatchAttempted };
    S.segment = null;
    setState('finalizing', reason);
    log('info', '第 ' + seg.index + ' 段收口：' + reason +
      '（时长 ' + Math.round((now() - seg.startTs) / 1000) + 's，说话 ' + Math.round(seg.speechMs / 1000) + 's）');
    if (viaClick && !dispatchAttempted) {
      log('error', '找不到“转录并发送”按钮，本段不会判定为已发送');
    }
  }

  async function finalizingTick(ui) {
    const p = S.pending;
    if (!p) { setState('idle'); return; }

    if (ui.recording || ui.transcribing) {
      const limit = clamp(Number(S.settings.finalizeTimeoutMs) || 90000, 15000, 600000);
      if (now() - p.t0 > limit) {
        log('error', '转录超过 ' + Math.round(limit / 1000) + 's 还没结束，放弃这一段重新开始');
        S.pending = null;
        setState('idle');
      }
      return;
    }

    /*
     * 转录结束。判断方式只用"输入框是否已清空"——这是输入框状态，不是 ChatGPT 的输出。
     * 空了就说明「转录并发送」已经把文字发出去了；还留着文字，就说明没自动发出去，补点一次「发送」。
     */
    let sent = !!p.dispatchAttempted && !ui.composerText;
    if (!sent) {
      if (ui.composerText) {
        log('warn', '文字留在输入框里没自动发出去，补点一次“发送”');
        const fallbackDispatched = click('send');
        await sleep(700);
        const ui2 = readUi();
        sent = fallbackDispatched && !ui2.composerText;
      } else {
        log('error', '输入框为空且没有确认发送动作，本段保持未发送');
      }
      if (!sent) log('error', '第 ' + p.seg.index + ' 段没能发出去，需要你手动看一下');
    }
    recordSegment(p, sent);

    S.pending = null;
    setState('idle');
    await sleep(clamp(Number(S.settings.settleAfterSendMs) || 400, 0, 5000));
  }

  /*
   * 段长反馈：让工具自己微调"自动基准"。
   * 每次最多动 0.1，并且只在真有证据时动——宁可不动，也不要瞎调。
   *   - 最近 5 段里有 ≥2 段是被硬上限逼着发的 → 太慢，基准 +0.1
   *   - 最近 5 段的说话时长中位数 < 25s       → 太碎，基准 −0.1
   */
  function updateAutoBase(speechSec, reason) {
    S.recentSegs.push({ speechSec: Number(speechSec) || 0, capped: /硬上限/.test(String(reason || '')) });
    if (S.recentSegs.length > 12) S.recentSegs.shift();
    const last5 = S.recentSegs.slice(-5);
    if (last5.length < 5) return;

    const cappedCount = last5.filter((x) => x.capped).length;
    const sorted = last5.map((x) => x.speechSec).sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)];

    let delta = 0;
    let why = '';
    if (cappedCount >= 2) { delta = 0.1; why = '最近 5 段里有 ' + cappedCount + ' 段被硬上限逼着发'; }
    else if (median < 25) { delta = -0.1; why = '最近 5 段说话时长中位数只有 ' + median.toFixed(0) + 's，偏碎'; }
    if (!delta) return;

    const before = (S.autoBase == null) ? 4 : S.autoBase;
    const after = clamp(Math.round((before + delta) * 10) / 10, 1, 10);
    if (after === before) return;
    S.autoBase = after;
    S.autoBaseLog.push({ at: new Date().toTimeString().slice(0, 8), from: before, to: after, why });
    if (S.autoBaseLog.length > 20) S.autoBaseLog.shift();
    log('info', '自动基准 ' + before + ' → ' + after + '（' + why + '）');
  }

  function recordSegment(p, sent) {
    const item = {
      index: p.seg.index,
      at: new Date(p.seg.startTs).toLocaleTimeString(),
      durationSec: Math.round((now() - p.seg.startTs) / 100) / 10,
      speechSec: Math.round(p.seg.speechMs / 100) / 10,
      reason: p.reason,
      sent: !!sent
    };
    S.segments.push(item);
    if (S.segments.length > 100) S.segments.shift();
    if (sent) {
      S.sentCount += 1;
      log('ok', '第 ' + p.seg.index + ' 段已发送');
      queueSave(p.seg);
    } else {
      log('error', '第 ' + p.seg.index + ' 段没发出去');
    }
    updateAutoBase(item.speechSec, item.reason);
      // 把量到的停顿和当前基准一起交给界面侧持久化（按分类存）
    post('segment', {
      item,
      course: S.settings.saveCourse || '',
      autoBase: S.autoBase,
      bias: clamp(Number(S.settings.bias) || 0, -3, 3),
      autoBaseLog: S.autoBaseLog.slice(-20),
      pauses: S.pauses.slice(-200)
    });
  }

  /* ------------------- 4b. 落盘采集（只在开关打开时工作） ------------------- */

  /*
   * 这是整个工具里唯一读会话内容的地方，而且必须在"自动落盘"开关打开时才工作。
   * 它完全跑在自己的定时器上，不参与主循环，慢一点、失败都无所谓——
   * 录音和发送的节奏一秒都不会被它影响。
   */
  const USER_MESSAGE_SELECTOR = '[class~="group/user-message"]';
  const ANSWER_TEXT_SELECTOR = '[class*="MarkdownRoot"]';
  const COMPOSER_SELECTOR = 'div.ProseMirror[contenteditable="true"]';

  function outerMatches(selector) {
    return Array.from(document.querySelectorAll(selector)).filter((node) => {
      const parent = node.parentElement;
      return !(parent && parent.closest(selector));
    });
  }

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

  function textFingerprint(text) {
    const value = String(text || '').replace(/\s+/g, ' ').trim();
    let hash = 2166136261;
    for (let i = 0; i < value.length; i += 1) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return value.length + ':' + (hash >>> 0).toString(16);
  }

  const follows = (later, earlier) =>
    !!(earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING);

  /*
   * ChatGPT 当前 DOM 没有稳定的消息 ID；一个用户消息又可能命中两层同名节点。
   * 这里只取最外层节点，并把“用户消息 -> 它之后、下一条用户消息之前的回答”
   * 配成一轮。保存队列按轮次取值，积压多段时也不会全部拿最后一条回答。
   */
  function chatTurns() {
    const users = outerMatches(USER_MESSAGE_SELECTOR);
    const answers = outerMatches(ANSWER_TEXT_SELECTOR);
    return users.map((node, index) => {
      const nextUser = users[index + 1] || null;
      const answerNodes = answers.filter((answer) =>
        follows(answer, node) && (!nextUser || follows(nextUser, answer)));
      const userText = cleanNodeText(node);
      const answerText = answerNodes.map(cleanNodeText).filter(Boolean).join('\n\n');
      return {
        ordinal: index + 1,
        userText,
        answerText,
        fingerprint: textFingerprint(userText)
      };
    });
  }

  /*
   * 录音转写会短暂出现在输入框里，然后由 ChatGPT 自己发送。保存功能只旁听
   * 这个短暂文本，用它的指纹认出“本段自己的用户消息”，用于避开手动插入消息。
   * 这个观察器不点击、不修改输入框，也不参与任何发送判断。
   */
  function capturePendingComposerText() {
    if (!S.settings.saveEnabled || !S.pending || S.pendingComposerText) return;
    const ui = readUi();
    if (!ui.transcribing) return;
    const composer = document.querySelector(COMPOSER_SELECTOR);
    const text = composer ? String(composer.innerText || '').trim() : '';
    if (text) S.pendingComposerText = text;
  }

  function installSaveTextObserver() {
    if (S.saveTextObserver || typeof MutationObserver !== 'function') return;
    const attach = () => {
      if (S.saveTextObserver || !document.documentElement) return;
      S.saveTextObserver = new MutationObserver(capturePendingComposerText);
      S.saveTextObserver.observe(document.documentElement, {
        subtree: true,
        childList: true,
        characterData: true
      });
    };
    attach();
    if (!S.saveTextObserver) document.addEventListener('DOMContentLoaded', attach, { once: true });
  }

  installSaveTextObserver();

  function queueSave(seg) {
    if (!S.settings.saveEnabled) return;
    const saveId = 's-' + String([location.href, S.sessionStart || seg.startTs, seg.index]
      .join('|')).replace(/[^a-zA-Z0-9._:-]/g, '_').slice(0, 140);
    const routeKey = queueRouteKey(location.href);
    const turns = chatTurns();
    const userTurnBase = Number(seg.userTurnBase);
    const previousOrdinal = Number(S.lastQueuedUserOrdinalByRoute[routeKey]) || 0;
    const userOrdinal = previousOrdinal
      ? Math.max(previousOrdinal + 1, turns.length)
      : Math.max(Math.max(0, userTurnBase || 0) + 1, turns.length);
    S.lastQueuedUserOrdinalByRoute[routeKey] = userOrdinal;
    const capturedFingerprint = S.pendingComposerText
      ? textFingerprint(S.pendingComposerText)
      : '';
    const knownTurn = capturedFingerprint
      ? turns.find((turn) => turn.fingerprint === capturedFingerprint) || null
      : (turns[userOrdinal - 1] || null);
    S.saveQueue.push({
      saveId,
      index: seg.index,
      at: seg.startTs,
      url: location.href,
      routeKey,
      queuedAt: now(),
      // 这节课的起始时刻：本地服务用它来定文件名，所以它中途重启也不会换文件
      sessionStart: S.sessionStart || seg.startTs,
      durationSec: Math.round((now() - seg.startTs) / 100) / 10,
      speechSec: Math.round(seg.speechMs / 100) / 10,
      // 分类在入队时冻结；之后切换分类不会把旧段写成新分类。
      course: String(seg.saveCourse || ''),
      userOrdinal,
      userFingerprint: capturedFingerprint || (knownTurn ? knownTurn.fingerprint : ''),
      userFingerprintSource: capturedFingerprint ? 'composer' : (knownTurn ? 'ordinal' : ''),
      blocked: false,
      lastError: '',
      lastSeen: '',
      stable: 0,
      busy: false,
      submittedAt: 0
    });
    S.pendingComposerText = '';
    persistSaveQueue();
    if (S.saveQueue.length > 100) {
      log('warn', '待保存队列超过 100 段，继续保留，不丢弃旧段；请检查本地落盘服务');
    }
  }

  function processSaveQueue() {
    if (!S.settings.saveEnabled) return;
    const currentRoute = queueRouteKey(location.href);
    let job = S.saveQueue.find((item) =>
      !item.blocked && (item.routeKey || queueRouteKey(item.url)) === currentRoute);
    if (!job) {
      job = S.saveQueue.find((item) => {
        if (item.blocked) return false;
        const itemRoute = item.routeKey || queueRouteKey(item.url);
        return rootToConversationRoute(itemRoute, currentRoute) &&
          now() - (Number(item.queuedAt) || 0) <= SAVE_ROUTE_MIGRATION_MAX_AGE_MS;
      });
      if (job) {
        job.routeKey = currentRoute;
        job.url = location.href;
        persistSaveQueue();
      }
    }
    if (!job || job.busy) return;
    if (job.submittedAt) {
      if (now() - job.submittedAt < 30000) return;
      job.submittedAt = 0;
    }
    job.busy = true;
    try {
      if (now() - job.queuedAt > 120000) {
        log('warn', '第 ' + job.index + ' 段的回答等待超过 120s，继续等待，不丢弃落盘任务');
      }

      const turns = chatTurns();
      let turn = null;
      let byOrdinal = null;
      if (job.userOrdinal > 0) {
        byOrdinal = turns[job.userOrdinal - 1] || null;
      }
      const allMatches = job.userFingerprint
        ? turns.filter((item) => item.fingerprint === job.userFingerprint)
        : [];
      const eligibleMatches = allMatches
        .filter((item) => item.ordinal >= job.userOrdinal)
        .sort((a, b) => Math.abs(a.ordinal - job.userOrdinal) - Math.abs(b.ordinal - job.userOrdinal));
      if (job.userFingerprintSource === 'composer') {
        // 输入框指纹是本段自己的文本；只接受本段轮次或之后的同一文本。
        turn = eligibleMatches[0] || null;
      } else if (job.userFingerprintSource === 'ordinal') {
        if (byOrdinal && byOrdinal.fingerprint === job.userFingerprint) {
          turn = byOrdinal;
        } else if (eligibleMatches.length) {
          turn = eligibleMatches[0];
        } else if (byOrdinal && now() - job.queuedAt > 60000) {
          turn = byOrdinal;
          log('warn', '第 ' + job.index + ' 段消息文本排版有变化，改按稳定轮次继续保存');
        }
      } else if (byOrdinal) {
        turn = byOrdinal;
      }
      if (!turn) {
        if (now() - job.queuedAt > SAVE_ANSWER_TIMEOUT_MS) {
          job.blocked = true;
          job.lastError = '等待对应回答超过 10 分钟，已保留任务，请检查页面后手动重试';
          persistSaveQueue();
          log('error', '第 ' + job.index + ' 段' + job.lastError);
        }
        if (job.stable !== 0) { job.stable = 0; persistSaveQueue(); }
        return;
      }
      if (!job.userFingerprint || job.userFingerprint !== turn.fingerprint) {
        job.userFingerprint = turn.fingerprint;
        persistSaveQueue();
      }
      const generating = !!buttonFor(['停止', 'Stop', 'Stopp']);
      const last = turn.answerText;
      if (generating || !last) {
        if (now() - job.queuedAt > SAVE_ANSWER_TIMEOUT_MS) {
          job.blocked = true;
          job.lastError = '对应回答超过 10 分钟仍未完整出现，已保留任务，请检查页面后手动重试';
          persistSaveQueue();
          log('error', '第 ' + job.index + ' 段' + job.lastError);
        }
        if (job.stable !== 0) { job.stable = 0; persistSaveQueue(); }
        return;
      }
      if (job.lastSeen !== last) {
        job.lastSeen = last;
        job.stable = 0;
        persistSaveQueue();
        return;
      }
      job.stable += 1;
      persistSaveQueue();
      if (job.stable < 2) return;           // 连续两次没变，约 3 秒，才算答完
      post('save', {
        saveId: job.saveId,
        course: job.course || '',
        index: job.index,
        at: job.at,
        sessionStart: job.sessionStart,
        durationSec: job.durationSec,
        speechSec: job.speechSec,
        url: job.url,
        user: turn.userText,
        assistant: last
      });
      job.submittedAt = now();
      persistSaveQueue();
      log('info', '第 ' + job.index + ' 段的回答已交给本地归档');
    } catch (e) {
      job.stable = 0;
      log('warn', '落盘采集出错：' + String(e.message || e));
    } finally {
      job.busy = false;
    }
  }

  setInterval(() => { try { processSaveQueue(); } catch { /* 忽略 */ } }, 1500);

  /* ------------------------- 5. 状态上报 ------------------------- */

  function buildStatus() {
    const t = tuning();
    const seg = S.segment;
    return {
      state: S.state,
      stateLabel: STATE_LABEL[S.state] || S.state,
      running: S.running,
      rms: S.level.rms,
      peak: S.level.peak,
      floor: S.floor,
      loud: S.loud,
      threshold: S.threshold,
      micReady: !!S.analyser,
      sampleDt: Math.round(S.sampleDt),
      windowMs: Math.round(S.windowMs),
      tuning: t,
      needPauseMs: seg ? requiredPauseMs(seg.speechMs) : t.maxPauseMs,
      segMs: seg ? now() - seg.startTs : 0,
      speechMs: seg ? seg.speechMs : 0,
      silenceMs: seg ? seg.silenceMs : 0,
      segmentIndex: S.segmentIndex,
      sentCount: S.sentCount,
      lastReason: S.lastReason,
      segments: S.segments.slice(-8).reverse(),
      settings: S.settings,
      error: S.error,
      url: location.href
    };
  }

  function postStatus() { post('status', buildStatus()); }

  /* ------------------------- 启动 ------------------------- */

  /*
   * 主循环用"自调度 + 心跳看门狗"：
   * 正常情况下每 100ms 跑一次（停止时 400ms）；万一某次卡死，看门狗 5 秒后重新起一条，
   * 保证不需要任何人来手动重启。
   */
  let timer = null;
  let loopToken = 0;
  let lastProgress = now();

  function loop() {
    const my = ++loopToken;
    tick().finally(() => {
      if (my !== loopToken) return;              // 已有更新的循环接管，本链退出
      timer = setTimeout(loop, S.running ? 100 : 400);
    });
  }

  setInterval(() => {
    if (now() - lastProgress > 5000) {
      log('warn', '主循环 5 秒没动静，自动重启');
      lastProgress = now();
      loop();
    }
  }, 3000);

  setInterval(() => { if (S.running) postStatus(); }, 250);

  // 页面路由变化（切会话）时重置并上报
  let lastHref = location.href;
  setInterval(() => {
    if (location.href !== lastHref) {
      const previousHref = lastHref;
      lastHref = location.href;
      const firstChatTransition = rootToConversationRoute(
        queueRouteKey(previousHref),
        queueRouteKey(lastHref)
      );
      migrateRootQueueJobs(previousHref, lastHref);
      if (S.running && S.state !== 'off') {
        if (firstChatTransition) {
          // 新会话从 / 跳到 /c/... 是正常发送结果，不能把正在 finalizing 的段清掉。
          log('info', '新会话已建立，保留当前录音/转录状态');
        } else {
          log('warn', '页面切换了会话，重新开始');
          S.segment = null;
          S.pending = null;
          setState('idle');
        }
      }
      postStatus();
    }
  }, 600);

  /*
   * 全自动：页面加载后如果"自动开始"是开的，就自己跑起来，不需要人点任何东西。
   * 等 5 秒是为了让界面侧把用户存的设置送过来（界面比页面脚本晚一步加载）。
   */
  setTimeout(() => {
    if (S.settings.autoStart && !S.running) {
      S.autoStarted = true;
      api.start('自动开始');
    }
  }, 5000);

  post('ready', { version: S.version });
  log('info', '页面探针已就绪');
})();
