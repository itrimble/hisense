// Remote arrows move a visible highlight between the page's buttons, links and
// fields (tvOS-style focus), instead of steering a pointer. Loaded after inject.js;
// idempotent. Coordinates returned to the app are CSS px in the top-level viewport.
(function () {
  var P = window.__ptv;
  if (!P || P.nav) return;
  var N = P.nav = { el: null, ring: null };
  var SEL = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [role="tab"],' +
    '[role="menuitem"], [role="option"], [role="checkbox"], [role="switch"], [tabindex]:not([tabindex="-1"]), iframe';
  // A crisp ring with an unblurred halo: cheap for this TV's GPU to repaint on every step.
  var CSS = '.__ptv-focus{outline:3px solid #fff!important;outline-offset:3px!important;' +
    'box-shadow:0 0 0 7px rgba(255,255,255,.22)!important;scroll-margin:150px 90px!important}' +
    // On a tile's artwork: its grid cell clips anything outside, and the picture covers an
    // outline inside, so the ring is a border laid over the picture.
    '.__ptv-focus.__ptv-in{outline:none!important;box-shadow:none!important}' +
    '.__ptv-in::after{content:""!important;position:absolute!important;inset:0!important;' +
    'border:4px solid #fff!important;border-radius:inherit!important;z-index:10!important;pointer-events:none!important;' +
    'opacity:1!important;background:none!important;transform:none!important}'; // Apple's own ::after is a hidden hover tint

  function styleDoc(doc) {
    if (doc.__ptvNavCss || !doc.head) return;
    var s = doc.createElement('style');
    s.textContent = CSS;
    doc.head.appendChild(s);
    doc.__ptvNavCss = true;
  }

  // Rectangle in top-level viewport coordinates, adding up same-origin frame offsets.
  function rectOf(el) {
    var r = el.getBoundingClientRect(), x = r.left, y = r.top;
    var w = el.ownerDocument.defaultView;
    while (w && w.frameElement) {
      var f = w.frameElement, fr = f.getBoundingClientRect();
      x += fr.left + f.clientLeft;
      y += fr.top + f.clientTop;
      w = f.ownerDocument.defaultView;
    }
    return { x: x, y: y, w: r.width, h: r.height, cx: x + r.width / 2, cy: y + r.height / 2 };
  }

  function crossOrigin(f) {
    try { return !f.contentDocument; } catch (e) { return true; }
  }

  // Where focus may go: inside the topmost open modal (Apple's sign-in sheet, dialogs), else everywhere.
  function roots() {
    var docs = P.docs(document);
    for (var i = docs.length - 1; i >= 0; i--) {
      // Only true modals: Apple's mini player is a non-modal <dialog open> at the bottom.
      var ds = docs[i].querySelectorAll('dialog:modal, [aria-modal="true"]');
      for (var j = ds.length - 1; j >= 0; j--) if (P.shown(ds[j])) return [ds[j]];
    }
    return docs;
  }

  // Offset of a same-origin document's viewport within the top-level one.
  function frameOffset(doc) {
    var x = 0, y = 0, w = doc.defaultView;
    while (w && w.frameElement) {
      var f = w.frameElement, fr = f.getBoundingClientRect();
      x += fr.left + f.clientLeft;
      y += fr.top + f.clientTop;
      w = f.ownerDocument.defaultView;
    }
    return { x: x, y: y };
  }

  // Every control focus may move to, with its rectangle. This runs on every key press,
  // so it touches each element once: one rectangle read, no style or ancestor walks.
  // The list of controls in a root, reused until that document changes (a mutation
  // observer marks it stale); querying 800 controls on every key press cost ~15 ms here.
  function controls(root, doc) {
    if (!doc.__ptvNavMo) {
      doc.__ptvNavMo = new MutationObserver(function () { doc.__ptvNavList = null; });
      doc.__ptvNavMo.observe(doc.documentElement, { childList: true, subtree: true,
        attributes: true, attributeFilter: ['inert', 'disabled', 'href', 'tabindex', 'role'] });
    }
    if (root !== doc) return root.querySelectorAll(SEL); // a modal: small, not cached
    if (!doc.__ptvNavList) doc.__ptvNavList = doc.querySelectorAll(SEL);
    return doc.__ptvNavList;
  }

  function candidates() {
    var out = [], vw = window.innerWidth, vh = window.innerHeight;
    roots().forEach(function (root) {
      var doc = root.ownerDocument || root, off = frameOffset(doc);
      var inert = !!doc.querySelector('[inert]');
      styleDoc(doc);
      controls(root, doc).forEach(function (el) {
        if (!el.isConnected) return;
        if (el.tagName === 'IFRAME' && !crossOrigin(el)) return; // its contents are walked instead
        // Not aria-hidden: Apple sets it on the whole page while its mini player is up.
        if (el.disabled || (inert && el.closest('[inert]'))) return;
        var b = el.getBoundingClientRect();
        if (b.width < 6 || b.height < 6) return;
        if (b.width * b.height > vw * vh * 0.6) return; // full-screen wrappers (the mini player's) are not controls
        var x = b.left + off.x, y = b.top + off.y;
        out.push({ el: el, r: { x: x, y: y, w: b.width, h: b.height, cx: x + b.width / 2, cy: y + b.height / 2 } });
      });
    });
    return out;
  }

  // Distance between two 1-D ranges (0 when they overlap).
  function apart(a0, a1, b0, b1) {
    return a1 < b0 ? b0 - a1 : b1 < a0 ? a0 - b1 : 0;
  }

  function score(c, r, dx, dy) {
    var gap, off, drift;
    if (dx) {
      if (dx > 0 ? r.cx <= c.cx + 1 : r.cx >= c.cx - 1) return null;
      gap = dx > 0 ? r.x - (c.x + c.w) : c.x - (r.x + r.w);
      off = apart(c.y, c.y + c.h, r.y, r.y + r.h);
      drift = Math.abs(r.cy - c.cy);
    } else {
      if (dy > 0 ? r.cy <= c.cy + 1 : r.cy >= c.cy - 1) return null;
      gap = dy > 0 ? r.y - (c.y + c.h) : c.y - (r.y + r.h);
      off = apart(c.x, c.x + c.w, r.x, r.x + r.w);
      drift = Math.abs(r.cx - c.cx) * 0.25;
    }
    return Math.max(0, gap) + off * 3 + drift * 0.3;
  }

  // Where the highlight ring goes for `el`, or null if the viewer cannot see it. Apple's
  // grid tiles (Library > Shows) keep their link in a control layer at opacity 0 until it
  // is hovered. The link still takes clicks, so it counts, and the ring goes on the
  // layer's parent (the artwork), which shows.
  function ringFor(el) {
    if (P.shown(el)) return el;
    if (el.tagName !== 'A' || !el.getClientRects().length) return null;
    var clear = null, w = el.ownerDocument.defaultView;
    for (var n = el; n; n = n.parentElement) {
      var cs = w.getComputedStyle(n);
      if (cs.visibility === 'hidden') return null;
      if (cs.opacity === '0') clear = n;
    }
    var host = clear && clear.parentElement;
    return host && P.shown(host) ? host : null;
  }

  function shown(el) { return !!ringFor(el); }

  function alive(el) {
    return el && el.isConnected && shown(el);
  }

  // Something else on top (a popover, a sheet edge): the viewer could not see the highlight.
  function covered(el) {
    var r = el.getBoundingClientRect(), d = el.ownerDocument, w = d.defaultView;
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    if (x < 0 || y < 0 || x >= w.innerWidth || y >= w.innerHeight) return false; // off screen: unknown
    var hit = d.elementFromPoint(x, y);
    return !!hit && hit !== el && !el.contains(hit) && !hit.contains(el);
  }

  // Moves the highlight. Scrolling it into view and telling the app where it ended up
  // (for the hover that reveals Apple's card buttons) wait for the next frame, so the
  // key press returns without forcing a layout of the whole page.
  function setFocus(el, rect) {
    var ring = ringFor(el) || el;
    if (N.ring && N.ring !== ring) N.ring.classList.remove('__ptv-focus', '__ptv-in');
    N.el = el;
    N.ring = ring;
    N.lastRect = rect || N.lastRect;
    styleDoc(el.ownerDocument);
    // A style only: focus() ran Apple's focus handlers on every step. No ring on Apple's
    // embedded sign-in frame: around a whole frame it reads as a stray line.
    if (el.tagName !== 'IFRAME') ring.classList.add('__ptv-focus');
    if (ring !== el) ring.classList.add('__ptv-in');
    var w = el.ownerDocument.defaultView || window;
    w.requestAnimationFrame(function () {
      if (N.el !== el) return;
      el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' }); // one frame, not an animation
      var r = rectOf(el);
      N.lastRect = r;
      if (window.PodcastsTV && PodcastsTV.onFocusAt) PodcastsTV.onFocusAt(r.cx, r.cy, window.innerWidth);
    });
  }

  function report(el) {
    return JSON.stringify({ ok: true, frame: el.tagName === 'IFRAME', field: P.editable(el) });
  }

  // Moves the highlight one step. Returns JSON for the app: {ok,x,y,vw,frame,field} or {edge:true}.
  N.move = function (dx, dy, leaving) {
    var cur0 = alive(N.el) ? N.el : null;
    // Highlight on Apple's login frame (password, 2FA): arrows go inside it, unless the
    // remote has just stepped out of it.
    if (cur0 && cur0.tagName === 'IFRAME' && crossOrigin(cur0) && !leaving) {
      return JSON.stringify({ ok: true, frame: true });
    }
    var list = candidates();
    if (!list.length) return JSON.stringify({ edge: true });
    var cur = cur0;
    if (!cur) {
      // First press: start at the top-left-most control that is on screen.
      var vh = window.innerHeight, best = null;
      list.forEach(function (o) {
        if (o.r.y < 0 || o.r.y > vh || !shown(o.el) || covered(o.el)) return;
        if (!best || o.r.y + o.r.x * 0.5 < best.r.y + best.r.x * 0.5) best = o;
      });
      if (!best) best = list[0];
      setFocus(best.el, best.r);
      return report(best.el);
    }
    // The sidebar is a vertical menu. Left is its edge, not a diagonal jump
    // to the narrower logo above; Up/Down stay in the menu until Right exits.
    var sidebar = cur.closest('nav[data-testid="navigation"]');
    if (sidebar && dx < 0) return JSON.stringify({ edge: true });
    var c = rectOf(cur);
    var ranked = [];
    list.forEach(function (o) {
      if (o.el === cur) return;
      // Episode rows are links containing their own Play and More buttons.
      // Keep those actions reachable, including Up from a button to its row.
      var nestedAction = cur.tagName === 'A' && o.el.matches('button, [role="button"]') ||
        o.el.tagName === 'A' && cur.matches('button, [role="button"]');
      if (!nestedAction && (o.el.contains(cur) || cur.contains(o.el))) return;
      if (sidebar && dy && !sidebar.contains(o.el)) return;
      var detail = cur.closest('.ptv-private-detail');
      if (detail && dy && !detail.contains(o.el)) return;
      if (sidebar && dx > 0 && sidebar.contains(o.el)) return;
      var s = score(c, o.r, dx, dy);
      // A sparse page (Channels) has cards above the sidebar selection and a
      // closer footer below it. Right should enter the cards before legal links.
      var content = sidebar && dx > 0 && o.el.closest('main') && !o.el.closest('footer');
      var priority = sidebar && dx > 0 && !content ? 1 : 0;
      if (s !== null) ranked.push({ el: o.el, r: o.r, s: s, priority: priority });
    });
    ranked.sort(function (a, b) { return a.priority - b.priority || a.s - b.s; });
    for (var i = 0; i < ranked.length && i < 40; i++) {
      if (shown(ranked[i].el) && !covered(ranked[i].el)) {
        setFocus(ranked[i].el, ranked[i].r);
        return report(ranked[i].el);
      }
    }
    return JSON.stringify({ edge: true });
  };

  // Presses a control the way a click would. Text boxes get focus (the app then raises
  // the TV keyboard); Apple's cross-origin login frame gets focus so it takes the arrows.
  // WebView 153 no longer turns the app's synthetic touches into clicks, hence this.
  function activate(el) {
    P.userPicked();
    if (P.editable(el)) { el.focus(); return 'field'; }
    if (el.tagName === 'IFRAME') { el.focus(); return 'frame'; }
    var b = el.closest('button, [role="button"]') || el;
    if (P.resumesParked(b) && P.resumeParked()) return 'resumed';
    el.click();
    return 'clicked';
  }

  // Select: press the highlighted control, if it is still there.
  N.select = function () {
    if (!alive(N.el)) return JSON.stringify({ none: true });
    N.el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' });
    return JSON.stringify({ did: activate(N.el) });
  };

  // Pointer mode: press whatever is under the pointer (CSS px in the top viewport),
  // looking through same-origin frames.
  N.clickAt = function (x, y) {
    var doc = document, el = null;
    for (;;) {
      el = doc.elementFromPoint(x, y);
      if (!el || el.tagName !== 'IFRAME' || crossOrigin(el)) break;
      var fr = el.getBoundingClientRect();
      x -= fr.left + el.clientLeft;
      y -= fr.top + el.clientTop;
      doc = el.contentDocument;
    }
    if (!el) return JSON.stringify({ none: true });
    return JSON.stringify({ did: activate(el) });
  };

  // After the full-screen player closes: highlight the Play/Resume button nearest to
  // where the highlight was (Apple re-renders and relabels it while playing).
  N.restore = function () {
    if (alive(N.el) && /^(play|resume|continue)/i.test(N.el.getAttribute('aria-label') || '')) return;
    var at = N.lastRect, best = null, bestD = 1e9;
    candidates().forEach(function (o) {
      if (!/^(play|resume|continue)/i.test(o.el.getAttribute('aria-label') || o.el.textContent.trim())) return;
      var d = at ? Math.abs(o.r.cx - at.cx) + Math.abs(o.r.cy - at.cy) : o.r.y;
      if (d < bestD && shown(o.el)) { best = o.el; bestD = d; }
    });
    if (best) setFocus(best);
  };

  P.captureNavigation = function () {
    var e = N.el, main = document.querySelector('main');
    return { path: location.pathname + location.search + location.hash,
      href: e && e.getAttribute('href'), label: e && e.getAttribute('aria-label'),
      text: e && e.textContent.trim().slice(0, 300), x: window.scrollX, y: window.scrollY,
      mainY: main ? main.scrollTop : 0 };
  };
  P.restoreNavigation = function (state) {
    if (!state) return;
    var tries = 0;
    function restore() {
      // Apple's startup router drops our private-show fragment. Reapply it once
      // the base page is loaded so the private library can recreate the detail.
      var hashAt = state.path.indexOf('#private-show-');
      if (hashAt >= 0 && location.pathname + location.search === state.path.slice(0, hashAt) && !location.hash)
        history.replaceState(null, '', state.path);
      if (location.pathname + location.search + location.hash !== state.path) return;
      var target = Array.from(document.querySelectorAll(SEL)).find(function (e) {
        return shown(e) && (state.href ? e.getAttribute('href') === state.href :
          state.label ? e.getAttribute('aria-label') === state.label :
          state.text && e.textContent.trim().slice(0, 300) === state.text);
      });
      if (target) { setFocus(target); return; }
      if (++tries < 40) { setTimeout(restore, 500); return; }
      window.scrollTo(state.x || 0, state.y || 0);
      var main = document.querySelector('main'); if (main) main.scrollTop = state.mainY || 0;
    }
    restore();
  };

  N.focus = function (el) { if (el && el.isConnected && shown(el)) setFocus(el); };

  N.clear = function () {
    if (N.ring) N.ring.classList.remove('__ptv-focus', '__ptv-in');
    N.el = N.ring = null;
  };
})();
