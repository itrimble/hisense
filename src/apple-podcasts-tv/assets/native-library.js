// Capture presentation metadata from the signed-in page, never tokens or media URLs.
(function () {
  var P=window.__ptv;if(!P||P.nativeLibraryCapture)return;
  var last='', observedCount=0, quietSince=Date.now();
  function art(node) {
    var source=node.querySelector('source[type="image/jpeg"], source[srcset]');
    var img=node.querySelector('img');
    return source ? source.srcset.split(',')[0].trim().split(' ')[0] : img ? img.currentSrc : '';
  }
  P.nativeLibraryCapture=function(){
    var items=[],kind='',page=location.origin+location.pathname;
    var catalog=/\/episodes\/?$/.test(location.pathname), target=Number(P.nativeLibraryTarget)||0;
    if(/\/library\/shows\/?$/.test(location.pathname)){
      kind='shows';
      document.querySelectorAll('main .library-page ul[data-testid="grid"] li:not([data-private-id])').forEach(function(node){
        var a=node.querySelector('a[data-testid="product-lockup-link"]');
        if(a)items.push({id:a.href,url:a.href,title:(node.querySelector('[data-testid=product-lockup-title]')||a).textContent.trim(),art:art(node),format:'Podcast'});
      });
    } else if(/\/podcast\//.test(location.pathname) && !location.search){
      kind='episodes';var seen={};
      document.querySelectorAll('main a[href*="?i="]').forEach(function(a){
        if(seen[a.href]||!a.textContent.trim())return;seen[a.href]=true;
        var row=a.closest('[data-testid="episode-lockup"],li,article')||a.parentElement;
        var name=a.querySelector('[data-testid=episode-lockup-title],h3');
        var date=a.querySelector('[data-testid=episode-details__published-date]');
        var duration=a.querySelector('[data-testid=hero__play-button-text]');
        if(!name)return;
        items.push({id:new URL(a.href).searchParams.get('i'),url:a.href,title:name.textContent.trim(),art:art(row),format:[date&&date.textContent.trim(),duration&&duration.textContent.trim()].filter(Boolean).join(' · ')});
      });
    }
    if(!kind||!items.length)return;
    var json=JSON.stringify({kind:kind,page:page,items:items});
    if(catalog && target>0){
      if(items.length!==observedCount){observedCount=items.length;quietSince=Date.now();}
      var ready=items.length>=target || Date.now()-quietSince>18000;
      if(!ready){
        var scroll=document.querySelector('.scrollable-page');
        if(scroll)scroll.scrollTop=scroll.scrollHeight;
      }
      json=JSON.stringify({kind:kind,page:page,items:items,loading:!ready,catalog:true});
    }
    if(json!==last){last=json;PodcastsTV.nativeLibrary(json);}
  };
  setInterval(P.nativeLibraryCapture,1500);P.nativeLibraryCapture();
})();
