// Injected into podcasts.apple.com by MainActivity. Idempotent: safe to run
// on every page event. __WIDTH__ is replaced with the layout width in CSS px.
(function () {
  var W = __WIDTH__;
  if (window.__ptv) { window.__ptv.setWidth(W); return; }
  var P = window.__ptv = { media: null, handlers: {}, width: W };

  // Lay the page out as a desktop window of P.width px; WebView scales it to the TV.
  P.viewport = function () {
    if (!document.head) return;
    var ms = document.querySelectorAll('meta[name=viewport]'), c = 'width=' + P.width;
    if (!ms.length) {
      var m = document.createElement('meta');
      m.name = 'viewport';
      document.head.appendChild(m);
      ms = [m];
    }
    // Chromium honours the last tag it parses, so rewrite every one.
    for (var i = 0; i < ms.length; i++) if (ms[i].getAttribute('content') !== c) ms[i].setAttribute('content', c);
  };
  P.setWidth = function (w) { P.width = w; P.viewport(); };

  // Remember whichever media element the player uses (it may be detached from the DOM).
  P.track = function (m) {
    if (!m || P.media === m) return;
    P.media = m;
    ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (ev) {
      m.addEventListener(ev, P.report);
    });
    m.addEventListener('playing', P.videoStarted);
    m.addEventListener('loadedmetadata', P.videoStarted);
    ['play', 'loadedmetadata', 'ratechange'].forEach(function (ev) { m.addEventListener(ev, function () { if (P.applyRate) P.applyRate(); }); });
    m.addEventListener('ended', function () { if (P.onEnded) P.onEnded(); });
  };

  // Video episodes: hand playback to the app's native player (this TV cannot decode
  // 1080p video in the web player fast enough). Asked once per start of playback.
  P.isVideo = function () {
    var m = P.media;
    return !!(m && m.tagName === 'VIDEO' && m.videoWidth > 0);
  };
  P.videoStarted = function () {
    if (!P.isVideo() || !window.PodcastsTV || P.media.paused || P.nativeOn || P.noNative) return;
    if (document.fullscreenElement || P.cssFullOn) return;
    PodcastsTV.onVideo();
  };
  // Pauses the web player and returns what the native player needs to take over. The
  // video element is then "parked" without its file, which frees the TV's single
  // hardware decoder; pressing play on it again resumes in the native player.
  P.handoff = function () {
    var m = P.media, k = P.parked;
    if (!m || m.tagName !== 'VIDEO') return null;
    var resuming = k && k.el === m;
    var src = resuming ? k.full : m.currentSrc, t = resuming ? k.t : (m.currentTime || 0);
    if (!resuming && P.resumePoint) t = P.resumePoint(t) || t; // continuing from the app's own record
    if (!src) return null;
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    P.nativeOn = true;
    if (!resuming) {
      if (!P.call('pause')) m.pause(); // through Apple's own handler, so its buttons show "paused"
      m.pause();
      P.parked = { el: m, src: m.getAttribute('src'), full: src, t: t, page: location.pathname };
      m.removeAttribute('src');
      m.load();
      setTimeout(P.closeApplePlayer, 800);
    }
    var h1 = document.querySelector('h1'), show = document.querySelector('a[href*="/podcast/"][href$="' +
      (location.pathname.match(/\/id\d+/) || [''])[0] + '"]');
    var np = (P.np && P.np()) || {}; // Apple's episode id, artwork and page (nowplaying.js)
    return JSON.stringify({ src: src, t: t, id: np.id || '', art: np.art || '', url: np.url || '',
      duration: np.duration || 0,
      title: (md && md.title) || (h1 ? h1.textContent.trim() : ''),
      artist: (md && md.artist) || (show ? show.textContent.trim() : '') });
  };
  // The native player closed at `t` seconds; the web player stays parked there.
  P.resumeAt = function (t) {
    P.nativeOn = false;
    if (P.parked && isFinite(t)) P.parked.t = t;
    P.report();
    P.closeApplePlayer();
    if (P.nav) setTimeout(P.nav.restore, 400); // highlight back on the play button
  };
  // Apple opens its own full-screen video view when a video starts. With the video handed
  // to the native player it only shows a placeholder, so close it (its modal holds the video).
  P.closeApplePlayer = function () {
    var m = P.parked ? P.parked.el : P.media;
    if (!m) return false;
    var d = m.closest('[role="dialog"], [aria-modal="true"], dialog');
    if (!d) return false;
    // Only when expanded to full screen; the same element is the mini bar otherwise.
    var r = d.getBoundingClientRect();
    if (r.width * r.height < innerWidth * innerHeight * 0.6) return false;
    var cs = d.querySelectorAll('button[aria-label="Close"]');
    for (var i = 0; i < cs.length; i++) {
      var b = cs[i].getBoundingClientRect(); // fades in on hover, so ignore opacity
      if (b.width > 0 && b.height > 0) { cs[i].click(); return true; }
    }
    return false;
  };
  // The native player failed: give the episode back to the web player for this session.
  P.webFallback = function (t) {
    var k = P.parked;
    P.noNative = true;
    P.nativeOn = false;
    P.parked = null;
    if (!k) return;
    var m = k.el;
    m.addEventListener('loadedmetadata', function seek() {
      m.removeEventListener('loadedmetadata', seek);
      try { m.currentTime = t; } catch (e) {}
      origPlay.call(m);
      P.fullscreen();
    });
    m.setAttribute('src', k.src || k.full);
    m.load();
  };
  // Apple's own player does not recover once its video is parked, so its Play buttons
  // for that episode (episode page or mini player) resume the native player instead.
  P.resumesParked = function (el) {
    var k = P.parked;
    if (!k || P.nativeOn || !window.PodcastsTV) return false;
    if (!/^(play|resume)\b/i.test((el && el.getAttribute('aria-label')) || '')) return false;
    return location.pathname === k.page || !!el.closest('[role="dialog"][aria-modal="false"]');
  };
  P.resumeParked = function () {
    if (!P.parked || P.nativeOn || !window.PodcastsTV) return false;
    PodcastsTV.onVideo();
    return true;
  };
  // Play on a parked video: same episode -> resume natively; a new file -> unpark.
  P.playParked = function (m) {
    var k = P.parked;
    if (!k || k.el !== m || !window.PodcastsTV) return false;
    var cur = m.getAttribute('src');
    if (cur && cur !== k.src) { P.parked = null; return false; }
    if (cur) { m.removeAttribute('src'); m.load(); }
    PodcastsTV.onVideo();
    return true;
  };
  // Full screen via the Fullscreen API (shown by the app's custom view); falls
  // back to pinning the video over the page with CSS if the API refuses.
  P.fullscreen = function () {
    var m = P.media;
    if (!P.isVideo()) return false;
    var p = m.requestFullscreen ? m.requestFullscreen() : null;
    if (p && p.catch) p.catch(function () { P.cssFull(true); });
    else if (!p) P.cssFull(true);
    return true;
  };
  P.cssFull = function (on) {
    var m = P.media;
    if (!m) return;
    if (!P.cssStyle) {
      P.cssStyle = document.createElement('style');
      P.cssStyle.textContent = '.__ptv-full{position:fixed!important;left:0!important;top:0!important;' +
        'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;' +
        'z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important}';
      document.head.appendChild(P.cssStyle);
    }
    m.classList.toggle('__ptv-full', on);
    P.cssFullOn = on;
    if (window.PodcastsTV) PodcastsTV.onVideoMode(on);
  };
  P.exitFullscreen = function () {
    if (P.cssFullOn) { P.cssFull(false); return true; }
    if (document.fullscreenElement) { document.exitFullscreen(); return true; }
    return false;
  };
  var origPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    P.track(this);
    if (P.playParked(this)) return Promise.resolve();
    if (P.sleepHold && P.sleepHold()) return Promise.resolve(); // sleep timer just stopped playback
    return origPlay.apply(this, arguments);
  };
  document.addEventListener('play', function (e) { P.track(e.target); }, true);

  // Capture the page's own Media Session handlers so remote keys drive the real player.
  if (navigator.mediaSession && navigator.mediaSession.setActionHandler) {
    var origSet = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
    navigator.mediaSession.setActionHandler = function (action, handler) {
      P.handlers[action] = handler;
      try { return origSet(action, handler); } catch (e) {}
    };
  }

  P.report = function () {
    if (!window.PodcastsTV) return;
    var m = P.media;
    var md = navigator.mediaSession && navigator.mediaSession.metadata;
    var playing = !!(m && !m.paused && !m.ended);
    PodcastsTV.onState(playing, md ? md.title || '' : '', md ? md.artist || '' : '');
  };

  P.button = function (re) {
    var bs = document.querySelectorAll('button[aria-label]');
    for (var i = 0; i < bs.length; i++) {
      var b = bs[i];
      if (re.test(b.getAttribute('aria-label')) && b.offsetParent !== null && !b.disabled) {
        b.click();
        return true;
      }
    }
    return false;
  };

  P.call = function (action) {
    var h = P.handlers[action];
    if (!h) return false;
    try { h({ action: action }); return true; } catch (e) { return false; }
  };

  P.cmd = function (c) {
    var m = P.media;
    if (c === 'scanfwd') return P.scan(1);
    if (c === 'scanback') return P.scan(-1);
    if ((c === 'toggle' || c === 'play' || c === 'pause') && P.endScan()) {
      if (c === 'pause') m.pause();
      return;
    }
    switch (c) {
      case 'toggle':
        if (P.parked && !P.nativeOn) return P.cmd('play');
        if (m && !m.paused) return P.cmd('pause');
        return P.cmd('play');
      case 'play':
        if (P.resumeParked()) return;
        if (P.call('play')) return;
        if (m) { m.play(); return; }
        P.button(/^play$/i);
        return;
      case 'pause':
        if (P.call('pause')) return;
        if (m) { m.pause(); return; }
        P.button(/^pause$/i);
        return;
      case 'fwd':
        if (P.call('seekforward')) return;
        if (m) m.currentTime = Math.min(m.duration || 1e9, m.currentTime + 30);
        return;
      case 'back':
        if (P.call('seekbackward')) return;
        if (m) m.currentTime = Math.max(0, m.currentTime - 15);
        return;
      // Podcasts has no track skipping in the player bar; next/prev fall back to seeking.
      case 'next':
        if (!P.call('nexttrack') && !P.button(/^skip ahead$/i)) P.cmd('fwd');
        return;
      case 'prev':
        if (!P.call('previoustrack') && !P.button(/^skip back$/i)) P.cmd('back');
        return;
    }
  };

  // Every same-origin document: the sign-in sheet lives in a full-screen
  // /includes/commerce/navigator iframe, not in the top page.
  P.docs = function (doc, out) {
    out = out || [];
    out.push(doc);
    var frames = doc.querySelectorAll('iframe');
    for (var i = 0; i < frames.length; i++) {
      try { if (frames[i].contentDocument) P.docs(frames[i].contentDocument, out); } catch (e) {}
    }
    return out;
  };

  // Close the topmost visible sheet or dialog. Returns true if there was one.
  P.escape = function () {
    if (P.cssFullOn) return P.exitFullscreen();
    var docs = P.docs(document), d = null;
    for (var j = 0; j < docs.length; j++) {
      // Not Apple's mini player: a non-modal role="dialog" bar that is always open.
      var all = docs[j].querySelectorAll('dialog:modal, [aria-modal="true"], [role="dialog"]:not([aria-modal="false"])');
      for (var i = 0; i < all.length; i++) {
        if (all[i].getClientRects().length) d = all[i];
      }
    }
    if (!d) return false;
    var c = d.querySelector('button[aria-label*="lose" i], button[aria-label*="ismiss" i], button[aria-label*="ancel" i]');
    if (c) { c.click(); return true; }
    var doc = d.ownerDocument, t = doc.activeElement || doc.body;
    ['keydown', 'keyup'].forEach(function (type) {
      t.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true }));
    });
    return true;
  };

  // Before phone-typed text arrives: keep a text box the viewer selected, else
  // select the sign-in box. Returns what now has focus ('field', 'frame', '').
  // Really on screen: laid out, and no ancestor (across same-origin frames) hidden or transparent.
  P.shown = function (el) {
    if (!el || !el.getClientRects().length) return false;
    for (var n = el; n; ) {
      if (n.nodeType === 1) {
        var cs = n.ownerDocument.defaultView.getComputedStyle(n);
        if (cs.visibility === 'hidden' || cs.opacity === '0') return false;
      }
      var parent = n.parentNode;
      if (!parent || parent.nodeType === 9) {
        var w = n.ownerDocument && n.ownerDocument.defaultView;
        parent = w && w.frameElement;
      }
      n = parent;
    }
    return true;
  };
  P.editable = function (el) {
    if (!P.shown(el) || el.disabled || el.readOnly) return false;
    if (el.isContentEditable || el.tagName === 'TEXTAREA') return true;
    return el.tagName === 'INPUT' && /^(text|email|password|tel|search|url|number)$/i.test(el.type || 'text');
  };
  P.focusField = function () {
    var docs = P.docs(document);
    // Deepest same-origin document that holds focus, if its focused element is a text box.
    var doc = document, crossFrame = null;
    while (doc.activeElement && doc.activeElement.tagName === 'IFRAME') {
      var inner = null;
      try { inner = doc.activeElement.contentDocument; } catch (e) {}
      if (!inner) { crossFrame = doc.activeElement; break; }
      doc = inner;
    }
    if (!crossFrame && P.editable(doc.activeElement) && !P.isDone(doc.activeElement)) return 'field';
    var focusIn = function (el) {
      var w = el.ownerDocument.defaultView;
      if (w && w.frameElement) w.frameElement.focus();
      el.focus();
    };
    var pick = function (list) {
      var best = null;
      list.forEach(function (d) {
        d.querySelectorAll('input, textarea').forEach(function (el) {
          if (P.editable(el) && !P.isDone(el) && (!best || (!el.value && best.value))) best = el;
        });
      });
      return best;
    };
    // 1. A box showing in the sign-in sheet (frames), 2. Apple's login frame if it is
    // showing (Apple preloads it hidden during the email step), 3. a box on the page.
    var sheet = pick(docs.slice(1));
    if (sheet) { focusIn(sheet); return 'field'; }
    if (crossFrame && P.shown(crossFrame)) return 'frame';
    var login = null;
    docs.forEach(function (d) {
      d.querySelectorAll('iframe[src*="idmsa.apple.com"]').forEach(function (f) {
        if (P.shown(f)) login = f;
      });
    });
    if (login) { focusIn(login); return 'frame'; } // Apple's form focuses its own box
    var page = pick([document]);
    if (page) { focusIn(page); return 'field'; }
    return '';
  };

  // After the phone sends Enter, the box it typed into is finished: Apple moves on to
  // the password (in its own login frame) but leaves the email box on screen and
  // focused, so later text must not land there. Forgotten once the viewer picks a box.
  P.done = null;
  P.beforeEnter = function () {
    var where = P.focusField();
    var el = P.activeField();
    P.done = el ? { el: el, value: el.value } : null;
    return where === 'frame' && !el ? 'frame' : false; // page boxes still get a real Enter key
  };
  P.isDone = function (el) {
    return !!(P.done && P.done.el === el && el.value === P.done.value);
  };
  P.userPicked = function () { P.done = null; };

  // The focused text box, if it lives in a same-origin document (null inside Apple's
  // cross-origin login frame, which only native key events can reach).
  P.activeField = function () {
    var doc = document;
    while (doc.activeElement && doc.activeElement.tagName === 'IFRAME') {
      var inner = null;
      try { inner = doc.activeElement.contentDocument; } catch (e) {}
      if (!inner) return null;
      doc = inner;
    }
    return P.editable(doc.activeElement) ? doc.activeElement : null;
  };
  // Phone-typed text as a real browser edit (fires input events, works with any
  // character). Returns false when the app must fall back to native key events.
  // Returns true when done here, 'frame' when the box is in Apple's login frame (the app
  // hands the text to its helper there), false when the app should press keys.
  P.typeText = function (text) {
    var where = P.focusField();
    var el = P.activeField();
    if (!el) return where === 'frame' ? 'frame' : false;
    if (!el.ownerDocument.execCommand('insertText', false, text)) {
      var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
      setter.call(el, el.value + text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return true;
  };
  P.deleteBack = function () {
    var where = P.focusField();
    var el = P.activeField();
    if (!el) return where === 'frame' ? 'frame' : false;
    if (!el.ownerDocument.execCommand('delete', false)) {
      var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value').set;
      setter.call(el, el.value.slice(0, -1));
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return true;
  };

  // Reload any frame (in any same-origin document) whose src contains `host`.
  // Apple's sheet never retries its login frame after a DNS hiccup on its own.
  P.retryFrames = function (host) {
    var n = 0;
    P.docs(document).forEach(function (doc) {
      doc.querySelectorAll('iframe').forEach(function (f) {
        if ((f.src || '').indexOf(host) >= 0) { f.src = f.src; n++; }
      });
    });
    return n;
  };

  // Tell the app when Apple's sign-in sheet opens or closes, so it can offer
  // typing on a phone via QR code.
  // Apple's "Sign In or Sign Up" promo card covers the sidebar (and the remote's
  // highlight); close it. The Sign In button under it stays.
  P.dismissPromo = function () {
    var c = document.querySelector('.sign-in-popover__content');
    var pop = c && c.closest('.popover, [class*="popover"]');
    var b = pop && pop.querySelector('button[aria-label="Close"]');
    if (b && P.shown(b)) { b.click(); return true; }
    return false;
  };

  P.signInOpen = false;
  setInterval(function () {
    P.dismissPromo();
    var f = document.querySelector('iframe[src*="commerce/navigator"]');
    var open = !!(f && f.getClientRects().length && getComputedStyle(f).visibility !== 'hidden' &&
      getComputedStyle(f).opacity !== '0');
    if (open !== P.signInOpen && window.PodcastsTV) {
      P.signInOpen = open;
      PodcastsTV.onSignInSheet(open);
    }
  }, 1000);

  // This first runs while the document is still being parsed, often before <head> exists.
  // Watch the whole document until <head> appears, so Apple's width=device-width tag is
  // rewritten as soon as the parser inserts it, before first paint (no visible shrink).
  var vpTarget = null;
  var vpObserver = new MutationObserver(function () {
    P.viewport();
    if (document.head && vpTarget !== document.head) vpWatch(document.head);
  });
  function vpWatch(t) {
    vpObserver.disconnect();
    vpTarget = t;
    vpObserver.observe(t, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
  }
  vpWatch(document.head || document);
  P.viewport();
  document.addEventListener('DOMContentLoaded', P.viewport);
})();
