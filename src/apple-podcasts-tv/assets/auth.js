// Runs inside Apple's sign-in frame (idmsa.apple.com) only, put there by AuthBridge.
// That frame is cross-origin to the podcasts page, so the page script cannot reach its
// boxes and key presses sent from the app land on Apple's wrapper, not the box. This
// helper receives what the viewer typed on their phone and edits the box directly.
(function () {
  if (window.top === window || window.__ptvAuth) return;
  window.__ptvAuth = true;

  // On this TV, Apple's sign-in animations (boxes sliding and scaling in) froze part-way:
  // the password form drew squashed and the 2FA boxes drew away from their labels. Make
  // every animation and transition jump straight to its end state.
  var still = document.createElement('style');
  still.textContent = '*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;' +
    'transition-duration:0s!important;transition-delay:0s!important}';
  (document.head || document.documentElement).appendChild(still);

  var port = window.PodcastsTVAuth;
  if (!port) return;

  function visible(el) {
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none';
  }
  function typable(el) {
    return el && el.tagName === 'INPUT' && !el.readOnly && !el.disabled && visible(el) &&
      /^(text|email|password|tel|number|search)$/i.test(el.type || 'text');
  }
  // The box being typed into: the focused one, else the first empty one (the password
  // after the email step, the next digit of a two-factor code), else the last one.
  function field() {
    if (typable(document.activeElement)) return document.activeElement;
    var boxes = Array.prototype.filter.call(document.querySelectorAll('input'), typable);
    for (var i = 0; i < boxes.length; i++) if (!boxes[i].value) return boxes[i];
    return boxes[boxes.length - 1] || null;
  }
  function insert(el, text) {
    el.focus();
    if (!document.execCommand('insertText', false, text)) {
      var set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      set.call(el, el.value + text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  function submit(el) {
    var b = document.querySelector('#sign-in, button[type="submit"]');
    if (b && !b.disabled && visible(b)) { b.click(); return; }
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
    });
  }

  // Remote navigation inside the frame: a visible ring moves between Apple's boxes and
  // buttons in page order; stepping past either end hands the remote back to the app.
  var ringed = null;
  function controls() {
    return Array.prototype.filter.call(
      document.querySelectorAll('input, button, a[href], [role="button"], [tabindex]:not([tabindex="-1"])'),
      function (el) { return !el.disabled && el.type !== 'hidden' && visible(el); });
  }
  function ring(el) {
    if (ringed) { ringed.style.outline = ringed.__ptvOutline || ''; ringed.style.outlineOffset = ''; }
    ringed = el;
    if (!el) return;
    el.__ptvOutline = el.style.outline;
    el.style.outline = '3px solid #fff';
    el.style.outlineOffset = '2px';
    el.focus();
  }
  function reply(o) { port.postMessage(JSON.stringify(o)); }
  function nav(m) {
    var list = controls();
    if (m.type === 'navEnter') { ring(field() || list[0] || null); return; }
    if (m.type === 'navLeave') { ring(null); return; }
    if (m.type === 'navSelect') {
      if (!ringed) return;
      if (typable(ringed)) {
        ringed.focus();
        if (ringed.select) ringed.select(); // a 2FA digit: the next digit typed replaces it
        reply({ field: true });
      } else {
        ringed.click();
      }
      return;
    }
    var i = list.indexOf(ringed), step = m.dir === 'up' || m.dir === 'left' ? -1 : 1;
    var next = i < 0 ? 0 : i + step;
    if (next < 0 || next >= list.length) { ring(null); reply({ edge: m.dir }); return; }
    ring(list[next]);
  }

  port.onmessage = function (e) {
    if (!innerWidth || !innerHeight) return; // a hidden, preloaded copy of the frame
    var m;
    try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.type.indexOf('nav') === 0) { nav(m); return; }
    var el = field();
    if (!el) return;
    if (m.type === 'text') {
      // One character at a time: two-factor boxes move focus to the next box after each digit.
      for (var i = 0; i < m.text.length; i++) {
        var target = field();
        if (!target) break;
        var digitBox = target.maxLength === 1;
        if (digitBox && target.value && target.select) target.select(); // overwrite, don't append
        insert(target, m.text.charAt(i));
        // Apple moves to the next box itself; if it has not, do it here.
        if (digitBox && target.value && document.activeElement === target) {
          var all = Array.prototype.filter.call(document.querySelectorAll('input'), typable);
          var next = all[all.indexOf(target) + 1];
          if (next) next.focus();
        }
      }
    } else if (m.type === 'backspace') {
      // In a run of one-digit boxes (2FA), an empty box deletes the digit before it.
      if (!el.value && el.maxLength === 1) {
        var boxes = Array.prototype.filter.call(document.querySelectorAll('input'), typable);
        var prev = boxes[boxes.indexOf(el) - 1];
        if (prev) el = prev;
      }
      el.focus();
      if (el.select && el.maxLength === 1) el.select();
      document.execCommand('delete', false);
    } else if (m.type === 'enter') {
      submit(el);
    } else if (m.type === 'tab') {
      var boxes = Array.prototype.filter.call(document.querySelectorAll('input'), typable);
      var next = boxes[boxes.indexOf(el) + 1];
      if (next) next.focus();
    }
  };
  port.postMessage('ready');
})();
