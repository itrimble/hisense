import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
// Exercise the actual page helper with Apple's ordinary and explicit-content labels.
const source = fs.readFileSync(new URL('../assets/nowplaying.js', import.meta.url), 'utf8');
const helper = source.slice(source.indexOf('  P.playEpisodePage ='), source.lastIndexOf('})();'));
function check(label, {disabled = false, mini = false, visible = true, page = '?i=123', playing = false} = {}) {
  let clicks = 0;
  const button = {getAttribute: () => label, closest: () => mini, disabled, click: () => clicks++};
  const P = {shown: () => visible, nav: {clear() {}}};
  const context = {P, Date, location: {search: page}, document: {querySelectorAll: selector => selector === 'audio, video' ? (playing ? [{paused: false, readyState: 3}] : []) : [button]}};
  vm.runInNewContext(helper, context);
  const result = P.playEpisodePage('123', 13);
  return {result, clicks, position: P.resumeTo?.t};
}
assert.equal(check('Play').clicks, 1);
assert.equal(check('Explicit, Play, 1h 40m').clicks, 1);
assert.equal(check('Resume').clicks, 1);
assert.equal(check('Play').position, 13, 'short playback positions must be passed to the resume helper');
assert.equal(check('Play', {mini: true}).clicks, 0);
assert.equal(check('Play', {disabled: true}).clicks, 0);
assert.equal(check('Play', {visible: false}).clicks, 0);
assert.equal(check('Play', {page: '?i=456'}).result, 'wait');
assert.equal(check('Save Episode').clicks, 0);
assert.equal(check('Play').result, 'wait', 'a click is not proof of playback');
assert.equal(check('Pause', {playing: true}).result, 'ok');
assert.equal(check('Pause', {playing: true}).clicks, 0);
console.log('Resume-page regression checks passed');
