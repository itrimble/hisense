import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../assets/native-library.js',import.meta.url),'utf8');
function capture(path,nodes){
  const messages=[];
  const context={window:{__ptv:{}},location:{origin:'https://podcasts.apple.com',pathname:path,search:''},URL,
    document:{querySelectorAll:()=>nodes},setInterval(){},PodcastsTV:{nativeLibrary:s=>messages.push(JSON.parse(s))}};
  vm.runInNewContext(source,context);return messages;
}
const episode={href:'https://podcasts.apple.com/us/podcast/show/id123?i=456',textContent:'Date Title Description mentioning video',
 closest(){return this;},querySelector(selector){
  if(selector.includes('episode-lockup-title'))return {textContent:'Episode title'};
  if(selector.includes('published-date'))return {textContent:'Oct 23'};
  if(selector.includes('play-button-text'))return {textContent:'45 min'};
  return null;
 }};
const result=capture('/us/podcast/show/id123',[episode,episode]);
assert.equal(result.length,1);assert.equal(result[0].items.length,1,'duplicate links collapse');
assert.equal(result[0].items[0].title,'Episode title','never import a whole description as title');
assert.equal(result[0].items[0].format,'Oct 23 · 45 min','description mentioning video is not a format flag');
assert.equal(result[0].items[0].id,'456');
assert.deepEqual(capture('/us/library/shows',[]),[],'empty/loading pages must not erase cached subscriptions');
assert.deepEqual(capture('/us/search',[episode]),[]);
console.log('Native library capture checks passed');

// Catalog capture holds the renderer until the requested batch is available,
// scrolls the real page container, then publishes a final snapshot.
let now=0, catalogNodes=[episode], scroll={scrollTop:0,scrollHeight:1000};
const messages=[];
const context={window:{__ptv:{nativeLibraryTarget:2}},location:{origin:'https://podcasts.apple.com',pathname:'/us/podcast/show/id123/episodes',search:''},URL,
 document:{querySelectorAll:()=>catalogNodes,querySelector:()=>scroll},Date:{now:()=>now},setInterval(){},PodcastsTV:{nativeLibrary:s=>messages.push(JSON.parse(s))}};
vm.runInNewContext(source,context);
assert.equal(messages.at(-1).loading,true);
assert.equal(scroll.scrollTop,1000);
catalogNodes=[episode,{...episode,href:'https://podcasts.apple.com/us/podcast/show/id123?i=789'}];
context.window.__ptv.nativeLibraryCapture();
assert.equal(messages.at(-1).loading,false);
assert.equal(messages.at(-1).items.length,2);
context.window.__ptv.nativeLibraryTarget=50;
context.window.__ptv.nativeLibraryCapture();
assert.equal(messages.at(-1).loading,true);
now=19000;
context.window.__ptv.nativeLibraryCapture();
assert.equal(messages.at(-1).loading,false,'end-of-list or stalled loading releases the renderer');
console.log('Catalog batch checks passed');
