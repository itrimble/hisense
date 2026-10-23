// Private shows share Apple's library grid. Only metadata and opaque IDs enter this page.
(function () {
  var P = window.__ptv;
  if (!P || P.privateLibrary) return;
  var feeds = [], active = null, detail = null, hidden = [], lastTile = null;
  var css = document.createElement('style');
  css.textContent = '.ptv-private-card{display:block;color:inherit;text-decoration:none!important;position:relative;border-radius:8px}' +
    '.ptv-private-card.__ptv-focus{outline:none!important;box-shadow:none!important}.ptv-private-card.__ptv-focus::after{content:"";position:absolute;inset:0;border:4px solid white;border-radius:8px;pointer-events:none}' +
    '.ptv-private-card img{display:block;width:100%;aspect-ratio:1;object-fit:cover;border-radius:8px;background:#333}' +
    '.ptv-private-item strong{display:block;margin-top:6px;font-size:13px;font-weight:400;line-height:16px}' +
    '.ptv-private-item small{display:block;color:#aaa;font-size:13px;margin-top:4px}' +
    '.ptv-private-detail{padding:30px 40px;color:inherit}.ptv-private-detail header{display:flex;gap:28px;margin:24px 0}' +
    '.ptv-private-detail header img{width:200px;height:200px;object-fit:cover;border-radius:12px}' +
    '.ptv-private-detail h1{font-size:32px;line-height:1.2;margin:16px 0}.ptv-private-detail p{color:#aaa}' +
    '.ptv-private-detail button{color:inherit;background:#303030;border:0;border-radius:8px;padding:14px 18px;font:inherit;text-align:left}' +
    '.ptv-private-episodes{display:grid;gap:10px}.ptv-private-episodes button{width:100%;display:block}' +
    '.ptv-private-episodes small{display:block;color:#aaa;margin-top:8px}';
  document.head.appendChild(css);
  function el(tag, text, cls) { var e = document.createElement(tag); if(text)e.textContent=text;if(cls)e.className=cls;return e; }
  function title(f) { return f.title.replace(/\s*\((?:Audio|Video)\s*-\s*Club TWiT\)\s*$/i,''); }
  function picture(f) { var i=el('img'); i.alt=title(f); if(f.art)i.src=f.art; i.referrerPolicy='no-referrer';return i; }
  function restore() {
    if(detail)detail.remove(); detail=null; active=null;
    hidden.forEach(function(e){e.node.style.display=e.display;});hidden=[];
    if(P.nav)P.nav.clear();
    if(lastTile && lastTile.isConnected){lastTile.scrollIntoView({block:'center'});if(P.nav)P.nav.focus(lastTile);}
  }
  function open(f, push) {
    restore(); active=f;
    if(push)history.pushState({ptvPrivate:f.id},'', '#private-show-'+f.id);
    var main=document.querySelector('main');if(!main)return;
    Array.from(main.children).forEach(function(n){hidden.push({node:n,display:n.style.display});n.style.display='none';});
    detail=el('section',null,'ptv-private-detail');main.appendChild(detail);
    var back=el('button','‹ Shows');back.onclick=function(){history.back();};detail.appendChild(back);
    var header=el('header');header.appendChild(picture(f));var labels=el('div');
    labels.appendChild(el('h1',title(f)));labels.appendChild(el('p','Club TWiT · '+f.format));
    labels.appendChild(el('p','Your private subscription · Progress saved on this TV'));
    header.appendChild(labels);detail.appendChild(header);
    detail.appendChild(el('h2','Episodes'));detail.appendChild(el('p','Loading episodes…','ptv-private-status'));
    main.scrollTop=0;detail.scrollIntoView({block:'start'});if(P.nav)P.nav.focus(back);
    PodcastsTV.privateShow(f.id);
  }
  function install() {
    var legacy=document.getElementById('__ptv-private-feeds');if(legacy)legacy.remove();
    if(active){if(location.hash!=='#private-show-'+active.id)restore();else return;}
    if(!/\/library\/shows\/?$/.test(location.pathname))return;
    var grid=document.querySelector('main .library-page ul[data-testid="grid"]');if(!grid)return;
    var sample=grid.querySelector('li:not([data-private-id])');
    feeds.slice().sort(function(a,b){return title(a).localeCompare(title(b))||a.format.localeCompare(b.format);}).forEach(function(f){
      var existing=grid.querySelector('[data-private-id="'+f.id+'"]');
      if(existing){var img=existing.querySelector('img');if(f.art && img.getAttribute('src')!==f.art)img.src=f.art;return;}
      var li=el('li',null,sample?sample.className:'grid-item');li.dataset.privateId=f.id;li.classList.add('ptv-private-item');
      var a=el('a',null,'ptv-private-card');a.href='#private-show-'+f.id;
      a.setAttribute('aria-label',title(f)+' · '+f.format+' · Club TWiT');a.appendChild(picture(f));
      li.appendChild(a);li.appendChild(el('strong',title(f)));li.appendChild(el('small','Club TWiT · '+f.format));
      a.onclick=function(e){e.preventDefault();e.stopPropagation();lastTile=a;open(f,true);};
      var before=Array.from(grid.children).find(function(n){
        var label=n.querySelector('[data-testid="product-lockup-title"],strong');return label && label.textContent.localeCompare(title(f))>0;
      });grid.insertBefore(li,before||null);
    });
    var id=location.hash.replace('#private-show-','');var f=feeds.find(function(x){return x.id===id;});if(f)open(f,false);
  }
  P.privateLibrary={
    update:function(list){
      feeds=list;
      if(active && detail){var f=feeds.find(function(f){return f.id===active.id;});if(f && f.art)detail.querySelector('header img').src=f.art;}
      document.querySelectorAll('[data-private-id]').forEach(function(n){if(!feeds.some(function(f){return f.id===n.dataset.privateId;}))n.remove();});
      install();
    },
    loaded:function(f){
      if(!active||active.id!==f.id||!detail)return;
      var art=detail.querySelector('header img');if(f.art)art.src=f.art;
      var status=detail.querySelector('.ptv-private-status');if(status)status.remove();
      var old=detail.querySelector('.ptv-private-episodes');if(old)old.remove();
      var list=el('div',null,'ptv-private-episodes');
      f.episodes.forEach(function(e){var b=el('button',e.title);b.setAttribute('aria-label','Play '+e.title+' · '+e.format);
        b.appendChild(el('small',e.format+' · '+e.date+(e.duration?' · '+Math.round(e.duration/60)+' min':'')));
        b.onclick=function(){PodcastsTV.privatePlay(f.id,e.id);};list.appendChild(b);});
      if(!f.episodes.length)list.appendChild(el('p','No playable episodes in this feed.'));
      detail.appendChild(list);
    },
    failed:function(id){if(!active||active.id!==id||!detail)return;var s=detail.querySelector('.ptv-private-status');
      if(s){s.textContent='Could not load episodes. ';var retry=el('button','Retry');retry.onclick=function(){s.textContent='Loading episodes…';PodcastsTV.privateShow(id);};s.appendChild(retry);}}
  };
  window.addEventListener('popstate',function(){restore();install();});
  install();setInterval(install,750);PodcastsTV.privateLibrary();
})();
