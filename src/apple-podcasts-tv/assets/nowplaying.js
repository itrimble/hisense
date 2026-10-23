// What is playing, read from Apple's own player (MusicKit), for the app's media controls,
// screensaver and "continue" card; plus the Apple API calls the app makes on the viewer's
// behalf: saving the position reached in the app's own video player, and chapters.
// Loaded after inject.js; idempotent.
(function () {
  var P = window.__ptv;
  if (!P || P.np) return;
  var API = 'https://amp-api.podcasts.apple.com/v1/';
  var chapterCache = {};

  function kit() {
    try { return window.MusicKit && MusicKit.getInstance ? MusicKit.getInstance() : null; } catch (e) { return null; }
  }
  function storefront() { return (location.pathname.match(/^\/([a-z]{2})\//) || [0, 'us'])[1]; }
  function art(u, px) { return u ? u.replace('{w}', px).replace('{h}', px).replace('{f}', 'jpg') : ''; }
  function headers(i) {
    return { authorization: 'Bearer ' + i.developerToken, 'media-user-token': i.musicUserToken || '' };
  }

  // The episode in Apple's player, or null.
  P.np = function () {
    var i = kit(), n = i && i.nowPlayingItem;
    if (!n || !n.id) return null;
    var a = n.attributes || {}, m = P.media;
    var playing = typeof i.isPlaying === 'boolean' ? i.isPlaying : !!(m && !m.paused && !m.ended);
    return {
      id: String(n.id), title: a.name || n.title || '', show: a.artistName || '',
      art: art(n.artworkURL || (a.artwork && a.artwork.url), 600), url: a.url || '',
      position: P.parked ? P.parked.t : +i.currentPlaybackTime || 0, // parked: where the native player stopped
      duration: +i.currentPlaybackDuration || (a.durationInMillis || 0) / 1000,
      playing: playing && !P.parked, video: a.mediaKind === 'video', rate: m ? m.playbackRate || 1 : 1
    };
  };

  P.npReport = function () {
    if (!window.PodcastsTV || !PodcastsTV.onNowPlaying) return;
    var s = P.np();
    PodcastsTV.onNowPlaying(s ? JSON.stringify(s) : '');
  };
  // On every play/pause (P.report runs then), and every 5 s while playing.
  var report = P.report;
  P.report = function () { report(); setTimeout(P.npReport, 300); };
  setInterval(function () { var m = P.media; if (m && !m.paused) P.npReport(); }, 5000);

  // Saves how far the viewer got, as Apple's player does every few seconds, for episodes
  // the app plays itself (video). Episode ids are Apple's numeric ids.
  P.savePosition = function (id, t, d) {
    var i = kit();
    if (!i || !i.isAuthorized || !/^\d+$/.test(String(id)) || !(t >= 0)) return false;
    var h = headers(i);
    h['content-type'] = 'application/json';
    fetch(API + 'me/playback/positions/podcast-episodes/' + id, {
      method: 'PUT', headers: h,
      body: JSON.stringify({ type: 'playback-positions', attributes: {
        positionInMilliseconds: Math.round(t * 1000), recordedAtTimestamp: new Date().toISOString(),
        completed: d > 0 && t >= d - 1 } })
    }).then(function (r) { console.log('[ptv] saved position', id, Math.round(t), r.status); },
      function (e) { console.log('[ptv] saving position failed', id, e); });
    return true;
  };

  // Chapters (Apple's or the show's own), as [{name, start}] in seconds; sent to the app.
  P.chapters = function (id) {
    if (!/^\d+$/.test(String(id)) || !window.PodcastsTV) return false;
    var done = function (list) { chapterCache[id] = list; PodcastsTV.onChapters(String(id), JSON.stringify(list)); };
    if (chapterCache[id]) { done(chapterCache[id]); return true; }
    var i = kit();
    if (!i || !i.developerToken) return false;
    fetch(API + 'catalog/' + storefront() + '/podcast-episodes/' + id +
        '?include[podcast-episodes]=chapters&associate[chapters]=timeframes', { headers: headers(i) })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) {
        var rel = j && j.data && j.data[0] && j.data[0].relationships, list = [];
        ((rel && rel.chapters && rel.chapters.data) || []).forEach(function (c) {
          var a = c.attributes || {}, tf = c.meta && c.meta.associations && c.meta.associations.timeframes;
          var start = tf && tf.data && tf.data[0] && tf.data[0].attributes.startInMilliseconds;
          if (a.inTableOfContents !== false && a.name && typeof start === 'number') list.push({ name: a.name, start: start / 1000 });
        });
        list.sort(function (x, y) { return x.start - y.start; });
        done(list);
      }, function () { done([]); });
    return true;
  };

  // Where to start instead of Apple's position (resuming), for the episode now loaded; else null.
  P.resumePoint = function (cur) {
    var r = P.resumeTo, n = P.np();
    if (!r || Date.now() > r.until || !n || n.id !== r.id) return null;
    if (cur > 15 || r.t - cur < 30) { P.resumeTo = null; return null; }
    P.resumeTo = null;
    return r.t;
  };
  document.addEventListener('playing', function (e) {
    var m = e.target, t = P.resumeTo && !P.nativeOn ? P.resumePoint(m.currentTime || 0) : null;
    if (t !== null && !(m.tagName === 'VIDEO' && m.videoWidth > 0)) m.currentTime = t; // video: in P.handoff
  }, true);

  // Feeds such as SoundCloud redirect to signed links that expire a couple of minutes
  // after they are handed out. Chromium keeps using the link it was redirected to, so
  // playing again after a longer pause re-requests an expired link, the element fails
  // with a network error and Apple's player drops the episode. After such a pause, load
  // the feed's own link again first (a fresh redirect), at the same spot.
  var LONG_PAUSE_MS = 60000, play = HTMLMediaElement.prototype.play;
  document.addEventListener('pause', function (e) { e.target.__ptvPausedAt = Date.now(); }, true);
  HTMLMediaElement.prototype.play = function () {
    var m = this, src = m.currentSrc;
    if (m.paused && m.__ptvPausedAt && Date.now() - m.__ptvPausedAt > LONG_PAUSE_MS &&
        /^https?:/.test(src) && !(P.parked && P.parked.el === m)) {
      var t = m.currentTime;
      m.__ptvPausedAt = 0;
      m.src = src;
      m.currentTime = t; // before loading: becomes the start position
    }
    return play.apply(m, arguments);
  };

  P.seekTo = function (t) {
    var i = kit();
    if (i && i.seekToTime && i.nowPlayingItem) { i.seekToTime(t); return true; }
    if (P.media) { P.media.currentTime = t; return true; }
    return false;
  };

  // On an episode's page: press its own Play/Resume button (Apple starts where the viewer
  // left off). 'wait' until that page has rendered. If Apple starts near the beginning
  // although the app saw the viewer get to `t`, playback moves there (P.resumeTo).
  P.playEpisodePage = function (id, t) {
    if (t > 0) P.resumeTo = { id: String(id), t: t, until: Date.now() + 60000 };
    if (location.search.indexOf('i=' + id) < 0) return 'wait';
    var media = document.querySelectorAll('audio, video');
    for (var j = 0; j < media.length; j++) {
      if (!media[j].paused && media[j].readyState >= 2) return 'ok';
    }
    // The server-rendered button can exist before Apple's player handles clicks.
    // Keep polling until playback starts, with time for each click to take effect.
    if (P.resumeClickAt && Date.now() - P.resumeClickAt < 3000) return 'wait';
    var bs = document.querySelectorAll('button[aria-label]');
    for (var k = 0; k < bs.length; k++) {
      var b = bs[k];
      if (/^(?:explicit,\s*)?(play|resume)\b/i.test(b.getAttribute('aria-label')) && !b.closest('.chrome-player, [role="dialog"]') &&
          P.shown(b) && !b.disabled) {
        if (P.nav) P.nav.clear();
        P.resumeClickAt = Date.now();
        b.click();
        return 'wait';
      }
    }
    return 'wait';
  };
})();
