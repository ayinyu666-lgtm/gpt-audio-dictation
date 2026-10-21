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
    // 默认停着：扩展自己会起来，但录音要人点。面板比页面脚本晚加载几秒，
    // 那几秒里必须用"停"这个默认值，否则用户还没说话工具就开始录了。
    autoStart: false,
    autoJumpNewSession: true,
    autoInjectPrompt: true,
    newSessionPrompt: '回忆关于会议记录/翻译相关的任务要求，根据要求开始任务。',
    // 落盘子文件夹；空串 = 直接放课程目录（writer 侧按这个语义处理）
    saveSubDir: '原始录音',
    autoOpenTab: false,
    finishOnStop: true,
    saveEnabled: true,
    savePreferenceSet: false
  };

  // 上下文上限相关的节奏参数（毫秒）
  const LIMIT_CONFIRM_GAP_MS = 3000;      // 两次疑似命中之间至少隔这么久才算"连续"
  const LIMIT_SUSPECT_TTL_MS = 15000;     // 单次疑似过这么久没二次命中就忘掉
  const JUMP_COOLDOWN_MS = 45000;         // 跳到新会话后先别急着开录
  const INJECT_COOLDOWN_MS = 60000;       // 两次注入至少隔这么久
  const INJECT_SETTLE_MS = 2500;          // 判定进入新会话后等页面稳定再注入
  const INJECT_RECORD_BLOCK_MS = 45000;   // 注入后这段时间内、GPT 还在输出时不开新录

  /*
   * 上下文已满的判据：中英双语都要覆盖，用户界面可能切换语言。
   *
   * 这里只放"这个会话装不下了"的说法。**绝不能**出现「新聊天 / New chat」——
   * 侧边栏左上角那个常驻按钮的文本就是它，body.innerText 里永远存在，
   * 放进这条正则会导致每轮都命中、开课几秒就把整节课跳走。
   */
  const CONTEXT_LIMIT_RE = new RegExp(
    '达到上限|已达上限|上限已满|上下文已满|上下文已达|上下文超|上下文长度|' +
    '对话太[长多]|对话过长|此对话已达|此对话已?超|已达.{0,4}上限|已达到.{0,6}限制|' +
    '请开启?新对话|请开始新对话|需要新对话|换[一]?个新对话|' +
    'reached the (?:limit|maximum)|hit the (?:limit|context)|context (?:length|limit|window)|' +
    'maximum context|too long to continue|conversation (?:is )?too long|' +
    'start a new (?:chat|conversation) to continue|' +
    'message limit|length limit|out of context',
    'i'
  );

  /*
   * 「新聊天」这类说法只在横幅节点里才算数，用来兜住没写"上限"两个字的横幅。
   * 正文扫描绝不使用这条正则。
   */
  const NEW_CHAT_HINT_RE = new RegExp(
    '开启?新对话|开始新对话|新的对话|新建聊天|新聊天|new chat|new conversation', 'i'
  );

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
    // 新会话跟随 / 上下文上限 / 提示词注入
    limitSuspectAt: 0,      // 第一次疑似"上下文已满"的时刻
    limitSuspectCount: 0,   // 连续疑似次数
    jumpedAt: 0,            // 上一次自动跳到新会话的时刻
    injectAt: 0,            // 上一次注入提示词的时刻
    injectedText: '',       // 上一次注入的文本，用来把这条消息从落盘认领里排除
    lastConversationId: '', // 上一次看到的会话 id，用来判断"是不是换了会话"
    limitHitLoggedFor: '',  // 已经打过日志的命中片段，避免每轮刷屏
    limitWarnedAt: 0,       // 自动跳转关闭时，手动提醒的节流时刻
  };
  window.__gadMain = S;

  // 页面刷新/关闭不会重建扩展面板的 outbox；把尚未交给面板的轻量任务留在本机
  // localStorage 里。只保存任务元数据，不保存回答正文；回答由页面恢复后重新读取。
  const SAVE_QUEUE_STORAGE_KEY = 'gad.pageSaveQueue.v1';
  const SAVE_QUEUE_ITEM_PREFIX = 'gad.pageSaveJob.v1:';
  const SAVE_ANSWER_TIMEOUT_MS = 10 * 60 * 1000;
  // 匹配不到对应轮次时的容忍时间：长会话被虚拟化后旧轮次会离开 DOM，这种段落等下去也没用
  const SAVE_TURN_MISS_TIMEOUT_MS = 2 * 60 * 1000;
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
  /**
   * 取 URL 里的会话 id（/c/<id>）。取不到返回空串。
   * 换会话的判断必须靠它：只看 href 全串的话，同一会话里改了个 query 也会被当成换会话。
   */
  function conversationIdFromHref(href) {
    try {
      const u = new URL(String(href || ''), location.href);
      const m = /^\/c\/([^/?#]+)/.exec(u.pathname);
      return m ? m[1] : '';
    } catch {
      return '';
    }
  }

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
        userLooseFingerprint: job.userLooseFingerprint || '',
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
      case 'clearBlockedSaves': {
        // 长会话被虚拟化后，旧轮次离开 DOM，这些段落再也匹配不上了。
        // 允许一键清掉这些永远不可能成功的记录，避免面板上挂着一长串失败。
        const before = S.saveQueue.length;
        S.saveQueue = S.saveQueue.filter((job) => !job.blocked);
        persistSaveQueue();
        postSaveQueueState(true);
        log('info', '已清空 ' + (before - S.saveQueue.length) + ' 条失败记录');
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
      case 'autoJump':
        // 面板勾选框变化时只送这一个开关，不整体覆盖设置
        S.settings.autoJumpNewSession = !!(d.payload && d.payload.enabled);
        post('settings', S.settings);
        break;
      case 'autoInject':
        S.settings.autoInjectPrompt = !!(d.payload && d.payload.enabled);
        post('settings', S.settings);
        break;
      case 'injectPromptNow':
        // 面板上的"立即注入"按钮：不管开关状态，执行一次
        injectPrompt();
        api.pushStatus();
        break;
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

      maybeHandleContextLimit();

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
    // 刚跳到新会话：页面和提示词流程都还没走完，这时候开录只会录到空段
    if (S.jumpedAt && now() - S.jumpedAt < JUMP_COOLDOWN_MS) return;
    // 刚注入提示词、GPT 还在回忆任务要求：等它答完再开录，否则会录进答话干扰
    if (S.injectAt && ui.generating && now() - S.injectAt < INJECT_RECORD_BLOCK_MS) return;
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

  /*
   * 宽松指纹：只保留字母数字、忽略大小写和标点空白。
   * 输入框里的文本和真正发出去的消息常常差几个标点/空格，精确指纹会认不出来；
   * 宽松指纹用来做第二层兜底。
   */
  function looseFingerprint(text) {
    const value = String(text || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
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
    // 我们注入的任务提示词也是一条用户消息，绝不能让它被当成一段转录落盘
    const injectedFp = S.injectedText ? textFingerprint(S.injectedText) : '';
    const injectedLoose = S.injectedText ? looseFingerprint(S.injectedText) : '';
    const promptText = String(S.settings.newSessionPrompt || '');
    const promptFp = promptText ? textFingerprint(promptText) : '';
    const promptLoose = promptText ? looseFingerprint(promptText) : '';
    return users.map((node, index) => {
      const nextUser = users[index + 1] || null;
      const answerNodes = answers.filter((answer) =>
        follows(answer, node) && (!nextUser || follows(nextUser, answer)));
      const userText = cleanNodeText(node);
      const answerText = answerNodes.map(cleanNodeText).filter(Boolean).join('\n\n');
      const fp = textFingerprint(userText);
      const loose = looseFingerprint(userText);
      const isInjected =
        (injectedFp && fp === injectedFp) || (injectedLoose && loose === injectedLoose) ||
        (promptFp && fp === promptFp) || (promptLoose && loose === promptLoose);
      return {
        ordinal: index + 1,
        userText,
        answerText,
        fingerprint: fp,
        loose,
        isInjected: !!isInjected
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
    /*
     * 会话一长，ChatGPT 会把旧轮次从 DOM 移走（实测这个会话只剩最近 10 轮）。
     * 所以"第 N 轮"这种全局序号在页面上根本取不到，必须用文本指纹认领本段自己的消息。
     * 优先用输入框里抓到的文本；抓不到就退回"窗口里最新的那条用户消息"——
     * 刚发出去的段落一定在窗口末尾。
     */
    const lastTurn = turns.length ? turns[turns.length - 1] : null;
    const capturedFingerprint = S.pendingComposerText
      ? textFingerprint(S.pendingComposerText)
      : (lastTurn ? lastTurn.fingerprint : '');
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
      userFingerprint: capturedFingerprint,
      userLooseFingerprint: S.pendingComposerText ? looseFingerprint(S.pendingComposerText) : (lastTurn ? lastTurn.loose : ''),
      userFingerprintSource: S.pendingComposerText ? 'composer' : (lastTurn ? 'latest' : ''),
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

  /* 为一段待保存记录找它在页面上的轮次；找不到返回 null。 */
  /*
   * 为一段待保存记录认领它在页面上的轮次。
   * 只用文本指纹，不看"第几轮"：DOM 里只有最近若干轮，绝对序号不可用。
   * 指纹命中取最后一次出现；没有指纹时只允许"刚入队"的段落用窗口最后一条，
   * 避免积压的旧任务互相串答案。
   */
  function resolveJobTurn(job, turns) {
    if (!turns.length) return null;
    // 第一道闸：注入的提示词那一轮永远不参与认领（无论后面怎么匹配）
    const usable = turns.filter((item) => !item.isInjected);
    if (!usable.length) return null;
    turns = usable;

    let fresh = now() - (Number(job.queuedAt) || 0) <= 90000;
    // 第二道闸：刚注入过提示词的这一分钟内，不许用"窗口最后一条"兜底，
    // 否则 GPT 对提示词的回话会被当成某段转录的答案。
    if (S.injectAt && now() - S.injectAt < INJECT_COOLDOWN_MS) fresh = false;

    if (job.userFingerprint) {
      const exact = turns.filter((item) => item.fingerprint === job.userFingerprint);
      if (exact.length) return exact[exact.length - 1];
    }
    if (job.userLooseFingerprint) {
      const loose = turns.filter((item) => item.loose === job.userLooseFingerprint);
      if (loose.length) return loose[loose.length - 1];
    }
    // 指纹都对不上但还没超时：刚发出去的那一段几乎一定是窗口里最新的用户消息。
    // 宁可认领最新一条，也不要因为差一个标点就把整段内容丢掉。
    if (fresh) return turns[turns.length - 1];
    return null;
  }
  /*
   * 处理待保存队列。
   *
   * 旧实现只拿"队列里第一个还没失败的"那一段：一旦它匹配不上（会话太长被虚拟化，
   * 原始轮次已经不在 DOM 里），它就会占着位置等满 10 分钟才被标记失败，后面的段落
   * 全部堵死。实测积压 84 段时落盘彻底停摆，而每 10 分钟才前进一段。
   *
   * 现在改成扫描所有候选、挑"此刻真的能保存"的那一段；匹配不上的先跳过并单独计时，
   * 超时才标记失败。一段卡住不会再拖住其它段。
   */
  function processSaveQueue() {
    if (!S.settings.saveEnabled) return;
    const currentRoute = queueRouteKey(location.href);

    let candidates = S.saveQueue.filter((item) =>
      !item.blocked && (item.routeKey || queueRouteKey(item.url)) === currentRoute);

    // 当前路由上没有待办时，看看能不能把老路由上的段落迁过来（同一会话换了 URL）
    if (!candidates.length) {
      const migratable = S.saveQueue.find((item) => {
        if (item.blocked) return false;
        const itemRoute = item.routeKey || queueRouteKey(item.url);
        return rootToConversationRoute(itemRoute, currentRoute) &&
          now() - (Number(item.queuedAt) || 0) <= SAVE_ROUTE_MIGRATION_MAX_AGE_MS;
      });
      if (migratable) {
        migratable.routeKey = currentRoute;
        migratable.url = location.href;
        persistSaveQueue();
      }
      return;
    }

    const turns = chatTurns();
    const generating = !!buttonFor(['停止', 'Stop', 'Stopp']);
    let dirty = false;

    for (const job of candidates) {
      if (job.submittedAt) {
        if (now() - job.submittedAt < 30000) continue;   // 刚提交过，等本地服务回执
        job.submittedAt = 0;
        dirty = true;
      }

      const turn = resolveJobTurn(job, turns);
      if (!turn) {
        if (!job.missedAt) { job.missedAt = now(); dirty = true; }
        if (now() - job.missedAt > SAVE_TURN_MISS_TIMEOUT_MS) {
          job.blocked = true;
          job.lastError = '找不到这段对应的用户消息（会话太长，原始轮次已不在页面上）';
          dirty = true;
          log('error', '第 ' + job.index + ' 段' + job.lastError);
        }
        continue;
      }
      if (job.missedAt) { job.missedAt = 0; dirty = true; }
      if (!job.userFingerprint || job.userFingerprint !== turn.fingerprint) {
        job.userFingerprint = turn.fingerprint;
        dirty = true;
      }

      const answer = turn.answerText;
      if (generating || !answer) {
        if (now() - job.queuedAt > SAVE_ANSWER_TIMEOUT_MS) {
          job.blocked = true;
          job.lastError = '对应回答超过 10 分钟仍未完整出现，已保留待处理记录';
          dirty = true;
          log('error', '第 ' + job.index + ' 段' + job.lastError);
        }
        continue;
      }
      if (job.lastSeen !== answer) {
        job.lastSeen = answer;
        job.stable = 0;
        dirty = true;
        continue;
      }
      job.stable += 1;
      dirty = true;
      if (job.stable < 2) continue;      // 连续两次没变，约 3 秒，才算答完

      if (dirty) persistSaveQueue();
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
        assistant: answer,
        // 空串有意义：writer 收到 '' 就把文件直接放课程目录，不要替换成默认值
        subDir: String(S.settings.saveSubDir == null ? '' : S.settings.saveSubDir)
      });
      job.submittedAt = now();
      persistSaveQueue();
      log('info', '第 ' + job.index + ' 段的回答已交给本地归档');
      return;
    }

    if (dirty) persistSaveQueue();
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

  /* ------------------- 6. 上下文上限检测与自动跳新会话 ------------------- */

  /*
   * 上下文满了以后 ChatGPT 会在页面上挂一条横幅，但横幅的文案随版本变。
   * 分两段扫，规则不同：
   *   - 正文：只用 CONTEXT_LIMIT_RE。侧边栏常驻的「新聊天」按钮也在 body 里，
   *     所以正文不能用 NEW_CHAT_HINT_RE，否则每轮都命中、开课几秒就被跳走。
   *   - 横幅节点：CONTEXT_LIMIT_RE || NEW_CHAT_HINT_RE，兜住没写"上限"两个字的横幅。
   * 只取正文前 6000 字符：横幅在页面上部，取全页既慢又会把旧回答里的
   * "对话太长"之类字眼算进来。
   */
  function scanLimitText() {
    let hits = 0;
    try {
      let body = String(document.body ? document.body.innerText || '' : '').slice(0, 6000);
      // 双保险：把侧边栏整块从正文里剔掉再扫，就算以后按钮文案改成"已达上限"也不误判
      const rails = document.querySelectorAll(
        'nav, aside, [data-testid*="sidebar"], [aria-label*="侧边栏"], [aria-label*="Sidebar"]'
      );
      for (const node of rails) {
        const rail = String(node.innerText || node.textContent || '');
        if (rail) body = body.split(rail).join('');
      }
      const m = CONTEXT_LIMIT_RE.exec(body);
      if (m) {
        hits += 1;
        // 只在"还没确认"的阶段打一次（首次疑似）：真出误判时能从遥测里直接看到命中了哪句话
        if (S.limitSuspectCount <= 1) {
          const snippet = body.slice(Math.max(0, m.index - 30), m.index + 60).replace(/\s+/g, ' ');
          if (S.limitHitLoggedFor !== snippet) {
            S.limitHitLoggedFor = snippet;
            log('info', '上下文上限疑似命中：…' + snippet + '…');
          }
        }
      }
    } catch { /* 忽略 */ }
    try {
      const banners = document.querySelectorAll('[role="alert"], [aria-live], [data-testid*="banner"]');
      for (const node of banners) {
        const text = String(node.innerText || node.textContent || '');
        if (text && (CONTEXT_LIMIT_RE.test(text) || NEW_CHAT_HINT_RE.test(text))) hits += 1;
      }
    } catch { /* 忽略 */ }
    return hits;
  }

  /**
   * 判定"上下文已满"。必须连续两次命中（间隔 ≥3s）才算，
   * 一次误判就把整节课跳走是不可接受的；单次疑似 15 秒后自动忘掉。
   */
  function detectContextLimit() {
    if (S.limitSuspectAt && now() - S.limitSuspectAt > LIMIT_SUSPECT_TTL_MS) {
      S.limitSuspectAt = 0;
      S.limitSuspectCount = 0;
    }
    if (!scanLimitText()) return false;
    if (!S.limitSuspectAt || now() - S.limitSuspectAt >= LIMIT_CONFIRM_GAP_MS) {
      if (S.limitSuspectCount >= 1 && S.limitSuspectAt && now() - S.limitSuspectAt >= LIMIT_CONFIRM_GAP_MS) {
        // 第二次命中：确认
        S.limitSuspectCount = 2;
        S.limitSuspectAt = now();
        return true;
      }
      S.limitSuspectAt = now();
      S.limitSuspectCount = 1;
      log('info', '疑似出现上下文上限提示，等待二次确认');
      return false;
    }
    return false;
  }

  /** 把不属于当前会话的落盘任务冻住，别让它们每 1.5 秒空转还永不超时。 */
  function freezeForeignQueueJobs(currentRouteKey) {
    let changed = 0;
    for (const job of S.saveQueue) {
      const jobRoute = job.routeKey || queueRouteKey(job.url);
      if (jobRoute === currentRouteKey) continue;
      if (job.blocked) continue;
      job.blocked = true;
      job.busy = false;
      job.submittedAt = 0;
      job.lastError = '会话已切换，原轮次已不在当前页面';
      changed += 1;
    }
    if (!changed) return 0;
    persistSaveQueue();
    postSaveQueueState(true);
    log('warn', changed + ' 段属于旧会话，已标记为待处理（面板可一键清空）');
    return changed;
  }

  /** 切走之前把还没发出去的这段抢救一下：点「转录并发送」，让它跟着旧会话发出去。 */
  function rescuePendingSegment() {
    const p = S.pending;
    if (!p) return true;
    const dispatched = click('transcribeSend') || click('send');
    if (dispatched) {
      log('warn', '会话即将切换，已补发未发送的段落，本段不再计入新会话');
      return true;
    }
    log('warn', '会话切换时第 ' + p.seg.index + ' 段还没发出去，且找不到发送按钮——这段内容丢了');
    return false;
  }

  /** 优先点侧边栏的「新聊天」，点不到才退回落地点首页；域名一律动态取。 */
  function openNewChat() {
    try {
      const candidates = Array.from(document.querySelectorAll('a[href="/"], a[href="' + location.origin + '/"]'));
      for (const node of candidates) {
        if (node && typeof node.click === 'function') { node.click(); return true; }
      }
      const byLabel = Array.from(document.querySelectorAll('a, button'));
      for (const node of byLabel) {
        const label = String(node.getAttribute('aria-label') || node.innerText || node.textContent || '');
        if (/新聊天|新的聊天|New chat/i.test(label)) {
          node.click();
          return true;
        }
      }
    } catch { /* 落到下面的硬跳转 */ }
    try {
      location.href = location.origin + '/';
      return true;
    } catch { /* 忽略 */ }
    return false;
  }

  /*
   * 把提示词写进输入框并发送。这段文本之后会被 chatTurns() 标成 isInjected，
   * 不会被落盘认领成转录段。
   */
  async function injectPrompt() {
    const text = String(S.settings.newSessionPrompt || '').trim();
    if (!text) {
      log('warn', '提示词为空，跳过注入');
      return false;
    }
    const composer = document.querySelector(COMPOSER_SELECTOR)
      || document.querySelector('[contenteditable="true"]');
    if (!composer) {
      log('warn', '找不到输入框，提示词没注入');
      return false;
    }
    let written = false;
    try {
      composer.focus();
      // execCommand 是这条路径上唯一能让 ProseMirror 认账的写入方式
      written = document.execCommand('insertText', false, text);
    } catch { written = false; }
    const has = String(composer.innerText || composer.textContent || '').includes(text);
    if (!written || !has) {
      // 退路：直接写内容再补一个 input 事件，让 React/ProseMirror 同步状态
      try {
        composer.textContent = text;
        composer.dispatchEvent(new InputEvent('input', { bubbles: true, data: text }));
      } catch { /* 忽略 */ }
    }
    S.injectAt = now();
    S.injectedText = text;
    await sleep(400);
    const sent = click('send');
    log('ok', '已在新会话注入提示词，请等待 ChatGPT 回忆任务要求' + (sent ? '' : '（发送按钮未找到，请手动发送）'));
    return true;
  }

  /** 检测到上下文满之后的整套动作：抢救未发送段 → 跳新会话 → 记冷却。 */
  function jumpToNewSession() {
    if (S.pending) rescuePendingSegment();
    log('warn', '检测到上下文已满，自动跳转新会话');
    const ok = openNewChat();
    if (!ok) log('error', '跳转新会话失败：既没有「新聊天」入口，也无法改写地址');
    S.jumpedAt = now();
    S.limitSuspectAt = 0;
    S.limitSuspectCount = 0;
    S.pending = null;
    S.segment = null;
    if (S.running) setState('idle');
  }

  // 主循环里每轮问一次：满了就跳，跳完有 45 秒冷却，不会连跳
  function maybeHandleContextLimit() {
    if (!S.running || S.state === 'off') return;
    if (S.jumpedAt && now() - S.jumpedAt < JUMP_COOLDOWN_MS) return;
    if (!detectContextLimit()) return;
    // 开关关掉时也照样检测：只提醒，不跳转，别让整节课悄悄卡死
    if (S.settings.autoJumpNewSession === false) {
      if (now() - (S.limitWarnedAt || 0) > 60000) {
        S.limitWarnedAt = now();
        log('warn', '上下文似乎已满，自动跳转已关闭，请手动开一个新会话');
      }
      return;
    }
    jumpToNewSession();
  }

  // 页面路由变化（切会话）时重置并上报
  let lastHref = location.href;
  S.lastConversationId = conversationIdFromHref(lastHref);
  setInterval(() => {
    if (location.href !== lastHref) {
      const previousHref = lastHref;
      lastHref = location.href;
      const previousRoute = queueRouteKey(previousHref);
      const currentRoute = queueRouteKey(lastHref);
      const previousId = S.lastConversationId;
      const currentId = conversationIdFromHref(lastHref);
      S.lastConversationId = currentId;

      const firstChatTransition = rootToConversationRoute(previousRoute, currentRoute);
      // 会话 → 会话：这是"换了一个会话"，不是发送结果
      const conversationChanged = !!previousId && !!currentId && previousId !== currentId;

      if (firstChatTransition) {
        // / → /c/... 是正常发送结果，把老路由上的任务迁过来，别让它们卡住
        migrateRootQueueJobs(previousHref, lastHref);
      }

      if (conversationChanged) {
        // 旧会话的轮次再也匹配不上了：冻住它们，别每 1.5 秒空转扫描
        freezeForeignQueueJobs(currentRoute);
        // 切走之前先抢救没发出去的段；救不下来就明确告警，不静默丢弃
        if (S.running && S.state !== 'off') {
          rescuePendingSegment();
          // 新会话 = 新文件：从头编号，否则内容会继续写进旧文件名
          S.sessionStart = now();
          S.segmentIndex = 0;
          S.pending = null;
          S.segment = null;
          if (S.state !== 'off') setState('idle');
          log('info', '已跟随新会话，落盘改用新文件');
        } else {
          S.pending = null;
          S.segment = null;
        }
      } else if (!firstChatTransition && S.running && S.state !== 'off') {
        log('warn', '页面切换了会话，重新开始');
        S.segment = null;
        S.pending = null;
        setState('idle');
      }

      // 进入新会话（首跳 / → /c/... 也算）后自动注入任务提示词
      const enteredNewConversation =
        (conversationChanged && !!currentId) || (firstChatTransition && !!currentId);
      if (enteredNewConversation && S.running &&
          S.settings.autoInjectPrompt !== false &&
          now() - (S.injectAt || 0) > INJECT_COOLDOWN_MS) {
        setTimeout(() => {
          if (!S.running) return;
          injectPrompt().catch(() => {});
        }, INJECT_SETTLE_MS);
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
