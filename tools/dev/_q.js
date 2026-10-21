(() => {
  const out = { url: location.href.slice(-40) };
  const btn = (l) => !!document.querySelector('button[aria-label="' + l + '"]');
  out.btnDictate = btn('听写'); out.btnSend = btn('发送');
  // 横幅：composer 上方的兄弟/祖先区域
  const composer = document.querySelector('div.ProseMirror[contenteditable="true"]');
  const hits = [];
  document.querySelectorAll('div,span,p,a,button').forEach(n => {
    if (n.children.length > 2) return;
    const t = (n.innerText || '').trim();
    if (!t || t.length > 260) return;
    if (/上限|已满|限制|超出|上下文|过长|新建聊天|开始新|Limit|limit|context|too long|maximum|upgrade/i.test(t)) {
      const r = n.getBoundingClientRect();
      hits.push({ tag: n.tagName, cls: String(n.className||'').slice(0,70), y: Math.round(r.top), text: t.replace(/\s+/g,' ').slice(0,200) });
    }
  });
  out.hits = hits.slice(0, 18);
  if (composer) {
    const cr = composer.getBoundingClientRect();
    out.composerTop = Math.round(cr.top);
    out.aboveComposer = hits.filter(h => h.y < cr.top).slice(0,8);
  }
  return JSON.stringify(out);
})()