(function () {
  'use strict';

  const term = new Terminal({
    cursorBlink: true,
    cursorStyle: 'bar',
    scrollback: 5000,
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
    fontSize: 13,
    lineHeight: 1.15,
    convertEol: false,
    allowProposedApi: true,
    theme: {
      background: '#101210',
      foreground: '#E8E8DF',
      cursor: '#9C8CF7',
      selectionBackground: '#3A4A3E',
      black: '#1B1B1B', red: '#CC5555', green: '#55AA55', yellow: '#CCAA55',
      blue: '#5555CC', magenta: '#AA55AA', cyan: '#55AAAA', white: '#CCCCCC',
      brightBlack: '#666666', brightRed: '#FF7777', brightGreen: '#77FF77',
      brightYellow: '#FFFF77', brightBlue: '#7777FF', brightMagenta: '#FF77FF',
      brightCyan: '#77FFFF', brightWhite: '#FFFFFF'
    }
  });

  const fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('terminal'));

  // 不让页面改系统剪贴板、也不让 OSC 8 变成可点的链接。
  try {
    term.parser.registerOscHandler(52, () => true);
    term.parser.registerOscHandler(8, () => true);
  } catch (e) { /* 旧版本没有 parser API 就算了 */ }

  const enc = new TextEncoder();
  function toB64(bytes) {
    let s = '';
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s);
  }
  function fromB64(s) {
    const bin = atob(s);
    const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    return a;
  }

  term.onData(d => {
    try { Bridge.write(toB64(enc.encode(d))); } catch (e) {}
  });
  term.onResize(({ cols, rows }) => {
    try { Bridge.resize(cols, rows); } catch (e) {}
  });

  let fitTimer = 0;
  function fitNow() {
    if (fitTimer) return;
    fitTimer = setTimeout(() => {
      fitTimer = 0;
      try { fit.fit(); } catch (e) {}
    }, 30);
  }

  window.addEventListener('resize', fitNow);
  if (window.ResizeObserver) {
    new ResizeObserver(fitNow).observe(document.getElementById('terminal'));
  }

  setTimeout(() => {
    try { fit.fit(); } catch (e) {}
    try { Bridge.ready(); } catch (e) {}
  }, 50);

  window.mikaWrite = s => { try { term.write(fromB64(s)); } catch (e) {} };
  window.mikaFocus = () => { try { term.focus(); } catch (e) {} };
  window.mikaCopyAll = () => {
    try {
      term.selectAll();
      const text = term.getSelection();
      term.clearSelection();
      if (text) Bridge.copy(text);
    } catch (e) {}
  };

  document.addEventListener('pointerdown', () => {
    try { term.focus(); } catch (e) {}
  }, true);
})();
