// Playback speed, fast-forward/rewind and the sleep timer for Apple's web player
// (audio episodes; video plays in the app's native player).
// Loaded after inject.js; idempotent.
(function () {
  var P = window.__ptv;
  if (!P || P.setSpeed) return;

  // Playback speed chosen in the app (remembered per show). Apple's player resets the
  // rate when an episode loads, so it is re-applied whenever that happens.
  P.speed = 1;
  P.setSpeed = function (s) { P.speed = s; P.applyRate(); };
  P.applyRate = function () {
    var m = P.media;
    if (!m || P.scanRate) return;
    m.preservesPitch = true;
    if (Math.abs(m.playbackRate - P.speed) > 0.001) m.playbackRate = P.speed;
  };

  // Fast-forward / rewind for audio, as on Apple TV: each press steps 2x, 3x, 4x, 8x,
  // 16x, 32x (the other key slows, then reverses). Forward up to 16x plays sped up
  // (Chromium mutes above 4x); faster, and rewind, step the position instead.
  var LADDER = [-32, -16, -8, -4, -3, -2, 0, 2, 3, 4, 8, 16, 32];
  P.scanRate = 0;
  P.scan = function (dir) {
    var m = P.media;
    if (!m || (P.parked && P.parked.el === m)) return 0;
    var i = LADDER.indexOf(P.scanRate), r = LADDER[Math.max(0, Math.min(LADDER.length - 1, i + dir))];
    clearInterval(P.scanTimer);
    P.scanTimer = null;
    P.scanRate = r;
    if (r === 0) { P.applyRate(); if (m.paused) m.play(); return 0; }
    if (r > 0 && r <= 16) {
      m.playbackRate = r;
      if (m.paused) m.play();
    } else {
      m.playbackRate = 1;
      m.pause();
      P.scanTimer = setInterval(function () {
        m.currentTime = Math.max(0, Math.min((m.duration || 1e9) - 1, m.currentTime + r * 0.25));
      }, 250);
    }
    return r;
  };
  P.endScan = function () {
    if (!P.scanRate) return false;
    clearInterval(P.scanTimer);
    P.scanTimer = null;
    P.scanRate = 0;
    P.applyRate();
    if (P.media && P.media.paused) P.media.play();
    return true;
  };

  // Sleep timer: fade out over `secs`, then pause (as Apple's app does).
  P.fadeOutAndPause = function (secs) {
    var m = P.media;
    if (!m || m.paused) return;
    var v0 = m.volume, steps = Math.max(1, Math.round(secs * 4)), i = 0;
    clearInterval(P.fadeTimer);
    P.fadeTimer = setInterval(function () {
      i++;
      m.volume = Math.max(0, v0 * (1 - i / steps));
      if (i >= steps) {
        clearInterval(P.fadeTimer);
        P.cmd('pause');
        m.volume = v0;
      }
    }, 250);
  };

  // Sleep timer "when current episode ends": stop there, and keep Apple's queue from
  // starting the next episode for a little while.
  P.sleepAtEnd = false;
  P.onEnded = function () {
    if (!P.sleepAtEnd) return;
    P.sleepAtEnd = false;
    P.sleepHoldUntil = Date.now() + 20000;
    if (window.PodcastsTV) PodcastsTV.onSleepEnded();
  };
  P.sleepHold = function () {
    return !!P.sleepHoldUntil && Date.now() < P.sleepHoldUntil;
  };
})();
