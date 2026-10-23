// "Smoother scrolling" mode: Apple's page draws frosted glass (backdrop blur) on more
// than 100 elements and forces ~100 more onto their own layers. On this TV that left
// Chrome short of tile memory and redrawing at about one frame per second. Lite mode
// drops those effects and gives the formerly frosted surfaces solid backgrounds in the
// same colours. Loaded after inject.js; idempotent.
(function () {
  var P = window.__ptv;
  if (!P || P.setLite) return;
  var CSS =
    '*,*::before,*::after{backdrop-filter:none!important;-webkit-backdrop-filter:none!important;' +
    'will-change:auto!important;mix-blend-mode:normal!important}' +
    '.chrome-player::before{background-color:rgba(38,38,40,.97)!important}' +
    'nav.navigation{background-color:rgba(38,38,40,.98)!important}' +
    '.shelf-grid-nav__arrow{background-color:rgba(40,40,40,.92)!important}' +
    '.episode-hero__video-badge{background-color:rgba(0,0,0,.8)!important}' +
    '[class*="popover"],[class*="modal__container"],.chrome-volume__slider{background-color:rgba(40,40,40,.97)!important}' +
    // This TV's WebView has no GPU (Mali-470, OpenGL ES 2.0 only), so Chrome redraws the whole
    // page in software, about 250 ms a frame. Fades and smooth scrolling cost several frames
    // per remote press (outline and shadow transitions on Apple's buttons alone took two).
    '*,*::before,*::after{transition:none!important;scroll-behavior:auto!important}';
  P.lite = false;

  function apply(doc) {
    var s = doc.getElementById('__ptv-lite');
    if (P.lite && !s && doc.head) {
      s = doc.createElement('style');
      s.id = '__ptv-lite';
      s.textContent = CSS;
      doc.head.appendChild(s);
    } else if (!P.lite && s) {
      s.remove();
    }
  }
  P.setLite = function (on) {
    P.lite = !!on;
    P.docs(document).forEach(apply);
  };
  // Apple's password step on this TV draws its login frame over the sheet's subtitle
  // ("You will be signed in to..."); hide that line while the password frame is up.
  function signInOverlap(doc) {
    var body = doc.querySelector('.generic-auth-presentation__body');
    if (!body) return;
    var f = doc.querySelector('iframe[src*="idmsa.apple.com"]');
    var tall = !!f && f.getBoundingClientRect().height >= 300;
    body.style.visibility = tall ? 'hidden' : '';
  }

  // Apple's sign-in sheet: animations finish instantly (they froze part-way on this TV).
  var STILL = '*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;' +
    'transition-duration:0s!important;transition-delay:0s!important}';
  function stillSheet(doc) {
    if (doc.location.pathname.indexOf('/includes/commerce/') !== 0 || doc.getElementById('__ptv-still') || !doc.head) return;
    var s = doc.createElement('style');
    s.id = '__ptv-still';
    s.textContent = STILL;
    doc.head.appendChild(s);
  }

  // Looping animations (Apple's "now playing" bars, loading shimmer) would keep redrawing
  // the whole page in software for as long as they run; stop them where they stand.
  function stopLoops(doc) {
    if (!doc.getAnimations) return;
    doc.getAnimations().forEach(function (a) {
      var t = a.effect && a.effect.getTiming && a.effect.getTiming();
      if (t && t.iterations === Infinity && a.playState === 'running') a.pause();
    });
  }

  // Sheets (sign-in) arrive as new same-origin frames; style them too.
  setInterval(function () {
    P.docs(document).forEach(function (d) {
      if (P.lite) { apply(d); stopLoops(d); }
      stillSheet(d);
      signInOverlap(d);
    });
  }, 1000);
})();
