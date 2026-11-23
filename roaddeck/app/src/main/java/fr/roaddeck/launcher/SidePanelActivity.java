package fr.roaddeck.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.webkit.*;
import android.widget.FrameLayout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** An ordinary Android activity occupying the side of a real split-screen pair. */
public final class SidePanelActivity extends Activity {
    private boolean ready,visible,destroyed,openingSettings;
    private Config config;
    private MusicController music;
    private TuyaClient tuya;
    private FrameLayout root;
    private WebView web;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final AtomicBoolean weatherBusy=new AtomicBoolean(),gateBusy=new AtomicBoolean();
    private volatile long nextWeatherAttempt;
    private long lastGateAt=-10000,confirmationExpires;
    private String gateToken="",lastSnapshot="";
    private final Runnable tick=new Runnable(){public void run(){
        if(destroyed||!visible)return;
        pushState();refreshWeather();main.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle savedState){
        super.onCreate(savedState);
        config=new Config(this);music=new MusicController(this,config);tuya=new TuyaClient(config);
        stopService(new Intent(this,OverlayService.class));
        root=new FrameLayout(this);root.setBackgroundColor(0xff0c1020);
        web=new WebView(this);web.setBackgroundColor(0xff0c1020);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();String path=uri.getPath();
                if("https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost())&&path!=null&&path.matches("/assets/ui/[A-Za-z0-9_.-]+")){
                    try{
                        String mime=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".webp")?"image/webp":"application/octet-stream";
                        return new WebResourceResponse(mime,"UTF-8",getAssets().open(path.substring(8)));
                    }catch(IOException ignored){}
                }
                return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
        });
        web.addJavascriptInterface(new Bridge(),"Native");fullscreen();
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/overlay.html");
    }
    private void fullscreen(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if(root!=null)root.setKeepScreenOn(config.bool("keepScreen",true));
    }
    @Override protected void onStart(){
        super.onStart();visible=true;openingSettings=false;lastSnapshot="";nextWeatherAttempt=0;
        if(ready){pushInitial();pushState();}
        main.removeCallbacks(tick);main.post(tick);
        SplitScreenService.onPanelState(true,isInMultiWindowMode());
    }
    @Override protected void onResume(){super.onResume();fullscreen();SplitScreenService.onPanelState(true,isInMultiWindowMode());}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        SplitScreenService.onPanelState(visible,isInMultiWindowMode());
        if(ready)emit("wazeState",SplitScreenService.snapshot(this));
    }
    @Override protected void onStop(){
        visible=false;main.removeCallbacks(tick);cancelGateConfirmation();
        if(!openingSettings&&!isChangingConfigurations())SplitScreenService.onPanelState(false,isInMultiWindowMode());
        super.onStop();
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);fullscreen();
        SplitScreenService.onPanelState(visible,isInMultiWindowMode());
    }
    @Override public void onMultiWindowModeChanged(boolean inMultiWindowMode,Configuration configuration){
        super.onMultiWindowModeChanged(inMultiWindowMode,configuration);
        SplitScreenService.onPanelState(visible,inMultiWindowMode);
        if(ready)emit("wazeState",SplitScreenService.snapshot(this));
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)fullscreen();}
    @Override public void onBackPressed(){SplitScreenService.closePair(this);}
    private void cancelGateConfirmation(){gateToken="";confirmationExpires=0;emit("cancelConfirm",new JSONObject());}
    private final class Bridge{
        @JavascriptInterface public void post(String text){
            if(text==null||text.length()>16000)return;
            main.post(()->{if(destroyed)return;try{handle(new JSONObject(text));}catch(Exception error){emit("error",obj("message",message(error)));}});
        }
    }
    private JSONObject obj(Object... fields){
        JSONObject j=new JSONObject();try{for(int i=0;i<fields.length;i+=2)j.put((String)fields[i],fields[i+1]);}
        catch(JSONException e){throw new IllegalArgumentException(e);}return j;
    }
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private void emit(String type,Object data){
        main.post(()->{if(!destroyed&&ready&&web!=null)web.evaluateJavascript("window.RoadDeckOverlay.receive("+obj("type",type,"data",data)+")",null);});
    }
    private void notice(String text){emit("notice",obj("message",text));}
    private void handle(JSONObject command) throws Exception{
        String type=command.optString("type");JSONObject data=command.optJSONObject("data");if(data==null)data=new JSONObject();
        switch(type){
            case "ready": ready=true;pushInitial();pushState();emit("wazeState",SplitScreenService.snapshot(this));break;
            case "collapse":case "stop":case "close":case "waze":SplitScreenService.closePair(this);break;
            case "settings":openSettings(data.optString("page","waze"));break;
            case "radioDirectory":openSettings("radioDirectory");break;
            case "openMusic":
                Intent launch=getPackageManager().getLaunchIntentForPackage(config.string("musicPackage","com.spotify.music"));
                if(launch==null)throw new Exception("Application musicale non installée. Choisissez votre lecteur dans les réglages.");
                cancelGateConfirmation();startActivity(launch);break;
            case "media":
                String action=data.optString("action","toggle");
                if("toggle".equals(action)&&RadioService.instance!=null)RadioService.instance.pause();
                if(!music.control(action,0))notice("Lancez une lecture dans votre application musicale et autorisez les commandes dans les réglages.");
                pushState();break;
            case "radioControl":
                if(RadioService.instance!=null){
                    if(RadioService.instance.snapshot().optBoolean("wanted"))RadioService.instance.pause();
                    else{music.control("pause",0);RadioService.instance.resume();}
                }else playRadio(config.lastStation());
                pushState();break;
            case "radioPlay":playRadio(data);break;
            case "radioStep":stepRadio(data.optInt("direction",1));break;
            case "favorites":emit("favorites",config.favorites());break;
            case "width":notice("La largeur des deux fenêtres est gérée par l’écran partagé Android.");break;
            case "gate":case "gatePrepare":requestGate();break;
            case "gateCancel":gateToken="";break;
            case "gateConfirm":
                String token=data.optString("token");
                if(!visible||token.isEmpty()||!token.equals(gateToken)||SystemClock.elapsedRealtime()>confirmationExpires)
                    throw new Exception("Confirmation expirée. Appuyez de nouveau sur Portail.");
                gateToken="";triggerGate();break;
            default:throw new IllegalArgumentException("Commande du panneau inconnue");
        }
    }
    private void pushInitial(){
        emit("settings",obj("musicName",config.string("musicName","Spotify"),"radioBuffer",config.integer("radioBuffer",15),
            "navigationMode","split","splitFrame",config.bool("splitFrame",true),"weatherCity",config.string("weatherCity","")));
        emit("favorites",config.favorites());emit("weather",config.weather());
    }
    private void pushState(){
        if(!ready||destroyed||!visible)return;
        try{
            JSONObject radio=RadioService.instance==null?config.lastStation().put("playing",false).put("wanted",false).put("status","Arrêtée").put("buffered",0):RadioService.instance.snapshot();
            JSONObject state=obj("music",music.snapshot(),"radio",radio);String serial=state.toString();
            if(!lastSnapshot.equals(serial)){lastSnapshot=serial;emit("state",state);}
        }catch(Exception error){emit("error",obj("message",message(error)));}
    }
    private void playRadio(JSONObject station) throws Exception{
        if(station.optString("url").isEmpty()){openSettings("radioDirectory");return;}
        Core.streamUrl(station.optString("url"));music.control("pause",0);
        startForegroundService(new Intent(this,RadioService.class).setAction("play").putExtra("station",station.toString()));
    }
    private void stepRadio(int direction) throws Exception{
        JSONArray favorites=config.favorites();if(favorites.length()==0){notice("Ajoutez des radios favorites dans les réglages.");return;}
        String url=RadioService.instance==null?config.lastStation().optString("url"):RadioService.instance.snapshot().optString("url");
        int index=-1;for(int i=0;i<favorites.length();i++)if(favorites.getJSONObject(i).optString("url").equals(url))index=i;
        int next=index<0?(direction<0?favorites.length()-1:0):(index+(direction<0?-1:1)+favorites.length())%favorites.length();
        playRadio(favorites.getJSONObject(next));
    }
    private void openSettings(String page){
        if(!Core.set("general","waze","music","radio","gate","weather","about","radioDirectory").contains(page))page="waze";
        cancelGateConfirmation();
        Intent intent=new Intent(this,PanelSettingsActivity.class)
            .putExtra("roaddeckPage",page).putExtra("skipWazeAuto",true);
        // No NEW_TASK or CLEAR_TOP: the Waze task remains alongside this task.
        openingSettings=true;
        try{startActivity(intent);}catch(RuntimeException error){openingSettings=false;notice(message(error));}
    }
    private void refreshWeather(){
        if(!ready||weatherBusy.get()||System.currentTimeMillis()<nextWeatherAttempt)return;
        try{
            JSONObject settings=config.publicSettings();if(settings.optString("weatherLat").isEmpty()||settings.optString("weatherLon").isEmpty())return;
            JSONObject cached=config.weather();long now=System.currentTimeMillis();
            boolean same=settings.optString("weatherLat").equals(cached.optString("lat"))&&settings.optString("weatherLon").equals(cached.optString("lon"));
            if(same&&now-cached.optLong("updatedAt")<900000){nextWeatherAttempt=cached.optLong("updatedAt")+900000;return;}
            if(!weatherBusy.compareAndSet(false,true))return;nextWeatherAttempt=now+300000;
            io.execute(()->{try{
                JSONObject result=WeatherClient.current(settings);JSONObject current=config.publicSettings();
                if(current.optString("weatherLat").equals(settings.optString("weatherLat"))&&current.optString("weatherLon").equals(settings.optString("weatherLon"))){
                    config.cacheWeather(result);nextWeatherAttempt=System.currentTimeMillis()+900000;emit("weather",result);
                }
            }catch(Exception error){try{emit("weather",config.weather().put("error",message(error)));}catch(JSONException ignored){}}
            finally{weatherBusy.set(false);}});
        }catch(Exception error){emit("error",obj("message",message(error)));}
    }
    private void requestGate(){
        if(!visible||gateBusy.get())return;
        if(config.string("tuyaHome","").isEmpty()||config.string("tuyaScene","").isEmpty()){
            notice("Configurez votre scène Tuya dans Réglages → Portail.");return;
        }
        if(config.bool("gateConfirm",true)){
            gateToken=UUID.randomUUID().toString();confirmationExpires=SystemClock.elapsedRealtime()+15000;
            emit("gateConfirm",obj("token",gateToken));
        }else triggerGate();
    }
    private void triggerGate(){
        if(!visible)return;
        if(SystemClock.elapsedRealtime()-lastGateAt<3000){notice("Commande déjà envoyée. Vérifiez le portail.");return;}
        if(!gateBusy.compareAndSet(false,true))return;
        lastGateAt=SystemClock.elapsedRealtime();emit("gateState",obj("busy",true,"message","Envoi à Tuya…"));
        io.execute(()->{try{tuya.trigger();emit("gateState",obj("busy",false,"message","Commande acceptée par Tuya. Position du portail non vérifiée."));}
        catch(Exception error){emit("gateState",obj("busy",false,"error",true,"message",message(error)));}
        finally{gateBusy.set(false);}});
    }
    @Override protected void onDestroy(){
        destroyed=true;ready=false;visible=false;gateToken="";
        main.removeCallbacksAndMessages(null);io.shutdownNow();
        if(web!=null){web.removeJavascriptInterface("Native");web.stopLoading();web.destroy();web=null;}
        // RadioService and active music sessions deliberately outlive this activity.
        super.onDestroy();
    }
}
