(async () => {
  const old = document.getElementById('gad-test-root');
  if (old) old.remove();
  const root = document.createElement('div');
  root.id = 'gad-test-root';
  document.body.appendChild(root);

  const composer = document.createElement('div');
  composer.className = 'ProseMirror';
  composer.setAttribute('contenteditable', 'true');
  composer.setAttribute('role', 'textbox');
  root.appendChild(composer);

  const addButton = (label, attrs = {}) => {
    const b = document.createElement('button');
    b.setAttribute('aria-label', label);
    for (const [k, v] of Object.entries(attrs)) b.setAttribute(k, v);
    root.appendChild(b);
    return b;
  };

  const send = addButton('发送', { 'data-testid': 'send-button' });
  let userNode = null;
  let answerNode = null;
  send.onclick = () => {
    composer.textContent = '';
    composer.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'deleteContentBackward' }));
  };

  const dictate = addButton('听写');
  dictate.onclick = () => {
    dictate.remove();
    const cancel = addButton('正在启动听写；点击可取消');
    const transcribe = addButton('转录并发送');
    transcribe.onclick = () => {
      cancel.remove();
      transcribe.remove();
      composer.textContent = 'synthetic audio transcript';
      composer.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText' }));
      userNode = document.createElement('div');
      userNode.className = 'group/user-message';
      userNode.textContent = 'synthetic audio transcript';
      root.appendChild(userNode);
      answerNode = document.createElement('div');
      answerNode.className = 'MarkdownRoot';
      answerNode.textContent = 'synthetic GPT answer';
      root.appendChild(answerNode);
    };
  };

  window.__gadTestAudioStart = Date.now();
  const fakeAnalyser = {
    fftSize: 4096,
    getFloatTimeDomainData(buffer) {
      const loud = Date.now() - window.__gadTestAudioStart < 8000;
      for (let i = 0; i < buffer.length; i += 1) buffer[i] = loud ? 0.08 * Math.sin(i / 5) : 0;
    }
  };
  window.__gadMain.analyser = fakeAnalyser;
  window.__gadMain.buffer = new Float32Array(fakeAnalyser.fftSize);
  window.__gadMain.audioContext = { sampleRate: 48000 };
  window.postMessage({ __gad: true, dir: 'main', type: 'config', payload: {
    preset: 'test', autoStart: false, saveEnabled: true, savePreferenceSet: true,
    saveCourse: 'Example', language: 'en-US', uiLanguage: 'en-US'
  } }, location.origin);
  window.postMessage({ __gad: true, dir: 'main', type: 'start', payload: { reason: 'Synthetic page test' } }, location.origin);
  await new Promise((resolve) => setTimeout(resolve, 17000));
  const s = window.__gadMain;
  return {
    marker: 'gad-test',
    running: s.running, state: s.state, sentCount: s.sentCount, segmentIndex: s.segmentIndex,
    saveQueue: s.saveQueue.length, segments: s.segments.slice(-3), logs: s.logs.slice(-20),
    composer: composer.innerText || '', hasUser: !!userNode, hasAnswer: !!answerNode
  };
})()
