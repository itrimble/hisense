package com.ian.podcaststv;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** A single remote-friendly library for Apple and private subscriptions. */
final class NativeLibrary extends LinearLayout {
    static final String URL="https://podcasts.apple.com/us/library/shows";
    private final MainActivity a;
    private final TextView title, status;
    private final FrameLayout content;
    private JSONArray shows=new JSONArray(), rows=new JSONArray();
    private JSONObject selected;
    private String selectedId="";
    private int generation, episodePosition;
    private boolean refreshing;
    private String query="", showQuery="";
    private int requestedEpisodes;
    private final Button older;
    NativeLibrary(MainActivity activity) {
        super(activity); a=activity; setOrientation(VERTICAL); setPadding(32,20,32,20); setBackgroundColor(0xff111114);
        LinearLayout toolbar=new LinearLayout(a); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        title=text("Library",26); title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END); addView(title,new LinearLayout.LayoutParams(-1,80));
        button(toolbar,"Shows",()->showShows());
        button(toolbar,"Refresh",()->refresh());
        button(toolbar,"Apple Podcasts",()->a.openApple(selected==null?MainActivity.HOME:selected.optString("url",MainActivity.HOME),false));
        button(toolbar,"Playback",()->AppMenu.show(a));
        JSONObject last=NowPlaying.last(a.prefs);
        if(ResumeCard.worthOffering(last))button(toolbar,"Resume",()->{JSONObject current=NowPlaying.last(a.prefs);if(current!=null)a.playApple(current);});
        button(toolbar,"Find",()->find());
        older=button(toolbar,"Older episodes",()->olderEpisodes());
        addView(toolbar); status=text("",15); status.setTextColor(0xffb5b5bc); addView(status);
        content=new FrameLayout(a); addView(content,new LinearLayout.LayoutParams(-1,0,1));
        try { shows=new JSONArray(a.libraryCache.read("apple:shows")); } catch(Exception ignored) { }
        setVisibility(GONE);
    }
    boolean hasAppleShows() { return shows.length()>0; }
    boolean visible() { return getVisibility()==VISIBLE; }
    void showLibrary() { setVisibility(VISIBLE); showShows(); }
    void restoreFocus() {
        if(visible() && content.getChildCount()>0) {View list=content.getChildAt(0);list.requestFocus();
            if(selected!=null && list instanceof AbsListView)((AbsListView)list).setSelection(episodePosition);}
    }
    void hideLibrary() { generation++; setVisibility(GONE); }
    boolean back() { if(selected!=null) {showShows();return true;} return false; }
    private TextView text(String s,int size) { TextView t=new TextView(a);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(10,5,10,5);return t; }
    private GradientDrawable buttonSurface(int fill,int border,int width) {
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(8);d.setStroke(width,border);return d;
    }
    private Button button(LinearLayout row,String s,Runnable action) {
        Button b=new Button(a);b.setText(s);b.setTextSize(17);b.setAllCaps(false);
        b.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        b.setPadding(18,10,18,10);b.setMinimumWidth(0);b.setMinWidth(0);
        int[][] states={{-android.R.attr.state_enabled},{android.R.attr.state_pressed},{android.R.attr.state_focused},{}};
        b.setTextColor(new android.content.res.ColorStateList(states,new int[]{0xffcccccc,Color.BLACK,Color.BLACK,Color.WHITE}));
        android.graphics.drawable.StateListDrawable background=new android.graphics.drawable.StateListDrawable();
        background.addState(states[0],buttonSurface(0xff252525,0xff777777,2));
        background.addState(states[1],buttonSurface(Color.WHITE,Color.BLACK,5));
        background.addState(states[2],buttonSurface(Color.WHITE,Color.WHITE,5));
        background.addState(states[3],buttonSurface(0xff292929,0xffdddddd,2));
        b.setBackgroundTintList(null);b.setBackground(background);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,80);params.setMargins(0,8,12,8);
        b.setOnClickListener(v->action.run());row.addView(b,params);return b;
    }
    void showShows() {
        generation++;selected=null;requestedEpisodes=0;query=showQuery;older.setEnabled(true);older.setVisibility(GONE);title.setText("Library · Shows");
        ArrayList<JSONObject> merged=new ArrayList<>();
        for(int i=0;i<shows.length();i++)merged.add(shows.optJSONObject(i));
        JSONArray privateShows=a.privateFeeds.nativeShows();for(int i=0;i<privateShows.length();i++)merged.add(privateShows.optJSONObject(i));
        for(JSONObject item:merged)try {item.put("title",item.optString("title").replaceFirst("^Explicit, ",""));}catch(JSONException ignored){}
        merged.sort((x,y)->x.optString("title").compareToIgnoreCase(y.optString("title")));
        rows=new JSONArray();for(JSONObject item:merged)rows.put(item);
        status.setText(shows.length()==0 ? "Your private shows are ready. Refresh to load your Apple library." : rows.length()+" shows · Apple and private subscriptions · Refresh for updates");
        render(true);
    }
    private static String showId(String url) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("/id([0-9]+)(?:/|$|\\?)").matcher(url);
        return m.find()?m.group(1):"";
    }
    private static boolean sameShow(String a,String b) {String id=showId(a);return !id.isEmpty()&&id.equals(showId(b));}
    int captureTarget(String url) {return selected!=null&&sameShow(selected.optString("url"),url)?requestedEpisodes:0;}
    private void olderEpisodes() { loadCatalog(false); }
    private void loadCatalog(boolean refresh) {
        if(selected==null||selected.optBoolean("private"))return;
        if(a.playback.audioPlaying()&&!a.playback.privateActive()){status.setText("Pause Apple playback before loading older episodes.");return;}
        if(!refresh && rows.length()>=1000){status.setText("1,000 episodes loaded. Open Apple Podcasts for the rest.");return;}
        requestedEpisodes=refresh?Math.max(25,rows.length()):Math.min(1000,Math.max(50,rows.length()+50));
        older.setEnabled(false);
        final int ticket=++generation;
        status.setText("Loading older episodes…");
        a.openApple(selected.optString("url").replaceAll("/+$","")+"/episodes",true);
        postDelayed(()->{if(ticket==generation&&requestedEpisodes>0){
            requestedEpisodes=0;older.setEnabled(true);render(false);status.setText("Loading timed out; "+rows.length()+" episodes are available. Try Older episodes again.");a.nativeBrowsing();}},60000);
    }
    private void find() {
        EditText input=new EditText(a);input.setSingleLine(true);input.setText(query);input.setSelectAllOnFocus(true);
        input.setHint(selected==null?"Show name, Audio or Video":"Episode title or date");
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(a,android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(selected==null?"Find in your library":"Find in loaded episodes")
                .setView(input).setPositiveButton("Find",(d,w)->applyQuery(input.getText().toString()))
                .setNeutralButton("Clear",(d,w)->applyQuery("")).setNegativeButton("Cancel",null).create();
        input.setOnEditorActionListener((v,action,event)->{applyQuery(input.getText().toString());dialog.dismiss();return true;});
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();input.requestFocus();
    }
    private void applyQuery(String value) {query=value.trim();episodePosition=0;if(selected==null)showQuery=query;render(selected==null);}
    void refreshAtStartup() {
        // Feed requests run on the existing serial worker, not the UI thread.
        JSONArray feeds=a.privateFeeds.nativeShows();
        for(int i=0;i<feeds.length();i++)a.privateFeeds.nativeEpisodes(feeds.optJSONObject(i).optString("id"),true,result->{});
        refresh();
    }
    void refresh() {
        if(a.playback.audioPlaying() && !a.playback.privateActive()){status.setText("Pause Apple playback before refreshing its library.");return;}
        if(selected!=null) {if(!selected.optBoolean("private")&&rows.length()>8)loadCatalog(true);else open(selected,true);return;}
        refreshing=true;status.setText("Refreshing Apple library…");a.openApple(URL,true);
        int ticket=++generation;
        postDelayed(()->{if(ticket==generation && refreshing){refreshing=false;status.setText("Could not refresh yet. Open Apple Podcasts to check your sign-in, then retry.");a.nativeBrowsing();}},25000);
    }
    void receive(String json) {
        try {
            if(json.length()>1500000)return;
            JSONObject o=new JSONObject(json); JSONArray items=o.getJSONArray("items");
            // Accept only public Apple navigation URLs and a bounded metadata list.
            JSONArray safe=new JSONArray();
            for(int i=0;i<Math.min(items.length(),1000);i++){JSONObject item=items.getJSONObject(i);if(MainActivity.apple(item.optString("url")))safe.put(item);}
            if(safe.length()==0)return;
            if("shows".equals(o.optString("kind"))){shows=safe;a.libraryCache.write("apple:shows",safe.toString());refreshing=false;
                if(visible()&&selected==null){showShows();a.nativeBrowsing();}}
            else if("episodes".equals(o.optString("kind")) && MainActivity.apple(o.optString("page"))){
                boolean current=visible() && selected!=null && !selected.optBoolean("private") && sameShow(selected.optString("url"),o.optString("page"));
                String cacheUrl=current?selected.optString("url"):o.optString("page");
                // Only catalog requests can replace a catalog snapshot while loading older items.
                if(current && requestedEpisodes>0 && (!o.optString("page").matches(".*/episodes/?$") || !o.optBoolean("catalog")))return;
                if(o.optBoolean("loading")){if(current){status.setText("Loading older episodes… "+safe.length()+" found");if(safe.length()>rows.length()){rows=safe;a.libraryCache.write("apple:episodes:v2:"+cacheUrl,safe.toString());}}return;}
                // A later visit to the short show page must not erase older cached episodes.
                if(!o.optString("page").matches(".*/episodes/?$") || current && safe.length()<rows.length()) {
                    try {JSONArray old=new JSONArray(a.libraryCache.read("apple:episodes:v2:"+cacheUrl));
                        HashSet<String> ids=new HashSet<>();for(int i=0;i<safe.length();i++)ids.add(safe.getJSONObject(i).optString("id"));
                        for(int i=0;i<old.length()&&safe.length()<1000;i++){JSONObject item=old.getJSONObject(i);if(ids.add(item.optString("id")))safe.put(item);}
                    }catch(JSONException ignored){}
                }
                a.libraryCache.write("apple:episodes:v2:"+cacheUrl,safe.toString());
                if(current){rows=safe;status.setText(rows.length()+" loaded episodes · Older episodes loads more");render(false);requestedEpisodes=0;older.setEnabled(true);a.nativeBrowsing();}}

        } catch(Exception ignored) { }
    }
    private void open(JSONObject show,boolean refresh) {
        selected=show; requestedEpisodes=0;query="";older.setEnabled(true);older.setVisibility(show.optBoolean("private")?GONE:VISIBLE);episodePosition=0; selectedId=show.optString("id"); title.setText(show.optString("title"));int ticket=++generation;
        status.setText("Loading episodes…");content.removeAllViews();rows=new JSONArray();
        if(show.optBoolean("private")) {
            a.privateFeeds.nativeEpisodes(show.optString("id"),refresh,result->{if(ticket!=generation)return;
                if(result==null){status.setText("Could not load this feed. Choose Refresh to retry.");return;}
                rows=result;status.setText(rows.length()+" episodes · Progress saved on this TV");render(false);a.nativeBrowsing();});
        } else {
            try { rows=new JSONArray(a.libraryCache.read("apple:episodes:v2:"+show.optString("url"))); } catch(Exception ignored) { }
            if(rows.length()>0&&!refresh){
                render(false);
                if(a.playback.audioPlaying()&&!a.playback.privateActive()){a.nativeBrowsing();return;}
                status.setText(rows.length()+" cached episodes · Checking for new releases…");
                a.openApple(show.optString("url"),true);
                postDelayed(()->{if(ticket==generation){render(false);a.nativeBrowsing();}},25000);
            }
            else {if(a.playback.audioPlaying() && !a.playback.privateActive()){status.setText("Pause Apple playback to load this show, or open it in Apple Podcasts.");return;} a.openApple(show.optString("url"),true);postDelayed(()->{if(ticket==generation&&rows.length()==0){status.setText("Open Apple Podcasts to browse this show, or choose Refresh to retry.");a.nativeBrowsing();}},25000);}
        }
    }
    private void render(boolean grid) {
        content.removeAllViews(); final JSONArray data=new JSONArray();
        for(int i=0;i<rows.length();i++){JSONObject item=rows.optJSONObject(i);
            if(item!=null&&LibraryFilter.matches(item.optString("title"),item.optString("format"),query))data.put(item);}
        if(!query.isEmpty())status.setText(data.length()+" of "+rows.length()+" matches for “"+query+"” · Find → Clear removes the filter");
        else if(selected==null)status.setText(rows.length()+" shows · Apple and private subscriptions · Refresh for updates");
        else status.setText(rows.length()+" loaded episodes"+(selected.optBoolean("private")?" · Progress saved on this TV":" · Older episodes loads more"));
        if(data.length()==0){TextView empty=text("No matches. Choose Find to change or clear your search.",22);content.addView(empty);return;}
        AbsListView list;
        if(grid){GridView g=new GridView(a);g.setNumColumns(5);g.setHorizontalSpacing(16);g.setVerticalSpacing(16);list=g;}
        else {ListView l=new ListView(a);l.setDividerHeight(8);list=l;}
        list.setClipToPadding(false);list.setPadding(8,14,8,14);
        GradientDrawable ring=new GradientDrawable();ring.setColor(Color.TRANSPARENT);ring.setStroke(4,Color.WHITE);ring.setCornerRadius(8);
        list.setSelector(ring);list.setDrawSelectorOnTop(true);
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return data.length();} public Object getItem(int p){return data.optJSONObject(p);} public long getItemId(int p){return p;}
            public View getView(int p,View recycled,ViewGroup parent){
                JSONObject item=data.optJSONObject(p);LinearLayout box=new LinearLayout(a);box.setOrientation(grid?VERTICAL:HORIZONTAL);box.setPadding(10,10,10,10);
                box.setBackgroundColor(0xff242428);
                if(grid){ImageView art=new ImageView(a);art.setScaleType(ImageView.ScaleType.FIT_CENTER);box.addView(art,new LinearLayout.LayoutParams(-1,320));a.libraryCache.image(item.optString("art"),art);}
                LinearLayout labels=new LinearLayout(a);labels.setOrientation(VERTICAL);
                TextView name=text(item.optString("title"),grid?18:20);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
                TextView sub=text(item.optString("format"),14);sub.setTextColor(0xffc0bccd);labels.addView(sub);box.addView(labels,new LinearLayout.LayoutParams(-1,grid?160:-2));
                box.setContentDescription(item.optString("title")+", "+item.optString("format"));return box;
            }
        });
        list.setOnItemClickListener((parent,view,pos,id)->{
            JSONObject item=data.optJSONObject(pos);
            if(grid)open(item,false);
            else {episodePosition=pos;
                if(selected!=null&&selected.optBoolean("private"))a.privateFeeds.playFromLibrary(selected.optString("id"),item.optString("id"));
                else a.playApple(item);
            }
        });
        content.addView(list,new FrameLayout.LayoutParams(-1,-1));list.requestFocus();
        list.setSelection(grid?0:Math.min(episodePosition,data.length()-1));
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(!grid)episodePosition=position;}
        });
        if(grid)for(int i=0;i<data.length();i++)if(data.optJSONObject(i).optString("id").equals(selectedId)){list.setSelection(i);break;}
    }
}
