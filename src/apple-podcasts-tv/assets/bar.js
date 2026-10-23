// Apple's mini player bar floats over the bottom of every page, even with nothing loaded
// (it then shows just an Apple logo). On the TV it behaves like a player overlay instead:
// hidden until something plays, shown when playback starts or the remote is used, then
// fading away after a few seconds. It stays while the highlight is on one of its buttons
// or when it is expanded into Apple's full-screen player. Loaded after inject.js.
(function () {
  var P = window.__ptv;
  if (!P || P.bar) return;
  var B = P.bar = {};
  var SHOW_MS = 5000;
  var until = 0, wasPlaying = false;
  // Shown and hidden in one step: on this TV every frame of a fade is a full software redraw.
  var CSS = '.chrome-player.__ptv-bar-hidden{visibility:hidden!important;pointer-events:none!important}';

  function bar() { return document.querySelector('.chrome-player'); }

  // Something to control: an episode loaded in Apple's player, or handed to the native one.
  function loaded() {
    var m = P.media;
    return !!(P.parked || (m && (m.currentSrc || m.getAttribute('src'))));
  }

  function keep(e) {
    if (P.nav && P.nav.el && e.contains(P.nav.el)) return true; // the highlight is in it
    var d = e.querySelector('[role="dialog"]');                  // expanded full-screen player
    return !!(d && d.getBoundingClientRect().height > innerHeight * 0.6);
  }

  function update() {
    var e = bar();
    if (!e) return;
    if (!document.getElementById('__ptv-bar') && document.head) {
      var s = document.createElement('style');
      s.id = '__ptv-bar';
      s.textContent = CSS;
      document.head.appendChild(s);
    }
    var m = P.media, playing = !!(m && !m.paused && !m.ended);
    if (playing && !wasPlaying) until = Date.now() + SHOW_MS; // playback just started
    wasPlaying = playing;
    var show = !P.privatePlaying && loaded() && (Date.now() < until || keep(e));
    e.classList.toggle('__ptv-bar-hidden', !show);
  }

  // The app calls this on every remote key press.
  B.poke = function () {
    until = Date.now() + SHOW_MS;
    update();
  };

  setInterval(update, 400);
  update();
})();
