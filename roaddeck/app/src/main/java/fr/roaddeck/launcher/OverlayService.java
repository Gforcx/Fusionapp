package fr.roaddeck.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/**
 * A user-started, visible, stoppable right-hand dashboard. No root, ADB,
 * accessibility service, screen capture or Shizuku. The left side has no
 * RoadDeck window at all: touches go to the underlying application normally.
 */
public final class OverlayService extends Service {
    private static final String CHANNEL="roaddeck-overlay-v1";
    private static final int NOTIFICATION_ID=4204;
    private static final String STOP="fr.roaddeck.launcher.OVERLAY_STOP";
    private static final String COLLAPSE="fr.roaddeck.launcher.OVERLAY_COLLAPSE";
    private static volatile OverlayService instance;
    static volatile String lastError="";
    private volatile boolean attached;
    private boolean ready,collapsed,destroyed;
    private Config config;
    private MusicController music;
    private TuyaClient tuya;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private FrameLayout root;
    private WebView web;
    private Button restore;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final AtomicBoolean weatherBusy=new AtomicBoolean(),gateBusy=new AtomicBoolean();
    private volatile long nextWeatherAttempt;
    private long lastGateAt=-10000,confirmationExpires;
    private String gateToken="",lastSnapshot="";
    private final Runnable tick=new Runnable(){public void run(){
        if(destroyed||!attached)return;
        if(!Settings.canDrawOverlays(OverlayService.this)){
            lastError="L’autorisation d’affichage par-dessus a été retirée.";stopSelf();return;
        }
        if(!collapsed){pushState();refreshWeather();}
        main.postDelayed(this,collapsed?3000:1000);
    }};
    static boolean isActive(){OverlayService service=instance;return service!=null&&service.attached&&service.ready&&!service.destroyed;}

    @Override public void onCreate(){
        super.onCreate();instance=this;
        config=new Config(this);music=new MusicController(this,config);tuya=new TuyaClient(config);
        windows=getSystemService(WindowManager.class);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?"":intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(!Settings.canDrawOverlays(this)){
            lastError="RoadDeck doit être autorisé à s’afficher par-dessus les autres applications.";
            stopSelf();return START_NOT_STICKY;
        }
        try{
            startNotice();
            if(!attached)createPanel();
            setCollapsed(COLLAPSE.equals(action));
        }catch(Exception error){
            lastError="Panneau RoadDeck : "+message(error);stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void startNotice(){
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Panneau au-dessus de Waze",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Panneau RoadDeck actif. Vous pouvez le masquer ou le fermer à tout moment.");
        channel.setShowBadge(false);manager.createNotificationChannel(channel);
        int immutable=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        Intent open=new Intent(this,MainActivity.class).putExtra("roaddeckPage","waze")
            .putExtra("skipWazeAuto",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent home=PendingIntent.getActivity(this,4204,open,immutable);
        PendingIntent collapse=PendingIntent.getService(this,4205,new Intent(this,OverlayService.class).setAction(COLLAPSE),immutable);
        PendingIntent stop=PendingIntent.getService(this,4206,new Intent(this,OverlayService.class).setAction(STOP),immutable);
        Notification note=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("RoadDeck — panneau actif").setContentText("Waze reste derrière. Masquer ou fermer ici.")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(home)
            .addAction(new Notification.Action.Builder(null,"Masquer",collapse).build())
            .addAction(new Notification.Action.Builder(null,"Fermer",stop).build()).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID,note);
    }
    @SuppressWarnings("deprecation") private int screenWidth(){
        DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.widthPixels;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private WindowManager.LayoutParams expandedParams(){
        int width=OverlayGeometry.width(screenWidth(),config.integer("overlayWidth",34));
        // An ordinary, opaque, rectangular overlay. No title bar, shadow or divider.
        // Most importantly: NOT a full-screen transparent window.
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.OPAQUE);
        p.gravity=Gravity.TOP|Gravity.RIGHT;p.x=0;p.y=0;p.alpha=1f;p.dimAmount=0;p.windowAnimations=0;
        p.setTitle("RoadDeck controls");
        if(config.bool("keepScreen",true))p.flags|=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        return p;
    }
    @SuppressWarnings("SetJavaScriptEnabled") private void createPanel(){
        root=new FrameLayout(this);root.setBackgroundColor(0xff0c1020);root.setElevation(0);
        web=new WebView(this);web.setBackgroundColor(0xff0c1020);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);web.setHorizontalScrollBarEnabled(false);
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
        restore=new Button(this);restore.setText("RD");restore.setTextColor(Color.WHITE);restore.setTextSize(16);
        restore.setTypeface(Typeface.DEFAULT,Typeface.BOLD);restore.setContentDescription("Afficher le panneau RoadDeck");
        restore.setPadding(0,0,0,0);restore.setMinWidth(0);restore.setMinimumWidth(0);restore.setMinHeight(0);
        restore.setBackgroundColor(0xff312047);restore.setVisibility(View.GONE);restore.setOnClickListener(v->setCollapsed(false));
        root.addView(restore,new FrameLayout.LayoutParams(-1,-1));
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
        web.addJavascriptInterface(new Bridge(),"Native");
        params=expandedParams();windows.addView(root,params);attached=true;
        web.loadUrl("https://appassets.androidplatform.net/assets/ui/overlay.html");
        main.removeCallbacks(tick);main.post(tick);
    }
    private void setCollapsed(boolean value){
        if(!attached||destroyed)return;
        collapsed=value;gateToken=""; // A hidden confirmation must never remain valid.
        try{
            params=expandedParams();
            if(value){params.width=dp(54);params.height=dp(54);params.gravity=Gravity.RIGHT|Gravity.TOP;params.y=dp(12);}
            web.setVisibility(value?View.GONE:View.VISIBLE);restore.setVisibility(value?View.VISIBLE:View.GONE);
            windows.updateViewLayout(root,params);
            if(!value){lastSnapshot="";emit("cancelConfirm",new JSONObject());pushState();}
        }catch(RuntimeException error){lastError=message(error);stopSelf();}
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);if(attached)setCollapsed(collapsed);
    }
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
            case "ready": ready=true;pushInitial();pushState();break;
            case "collapse":setCollapsed(true);break;
            case "stop":stopSelf();break;
            case "settings":openMain(data.optString("page","waze"));break;
            case "openMusic":
                Intent launch=getPackageManager().getLaunchIntentForPackage(config.string("musicPackage","com.spotify.music"));
                if(launch==null)throw new Exception("Application musicale non installée. Choisissez votre lecteur dans les réglages.");
                setCollapsed(true);startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));break;
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
            case "width":
                config.save(obj("overlayWidth",OverlayGeometry.percent(data.optInt("value",34))));
                setCollapsed(collapsed);emit("width",obj("value",config.integer("overlayWidth",34)));break;
            case "gate":requestGate();break;
            case "gateCancel":gateToken="";break;
            case "gateConfirm":
                String token=data.optString("token");
                if(token.isEmpty()||!token.equals(gateToken)||SystemClock.elapsedRealtime()>confirmationExpires)
                    throw new Exception("Confirmation expirée. Appuyez de nouveau sur Portail.");
                gateToken="";triggerGate();break;
            default:throw new IllegalArgumentException("Commande overlay inconnue");
        }
    }
    private void pushInitial(){
        emit("settings",obj("musicName",config.string("musicName","Spotify"),"radioBuffer",config.integer("radioBuffer",15),
            "overlayWidth",config.integer("overlayWidth",34),"weatherCity",config.string("weatherCity","")));
        emit("favorites",config.favorites());emit("weather",config.weather());
    }
    private void pushState(){
        if(!ready||destroyed||collapsed)return;
        try{
            JSONObject radio=RadioService.instance==null?config.lastStation().put("playing",false).put("wanted",false).put("status","Arrêtée").put("buffered",0):RadioService.instance.snapshot();
            JSONObject state=obj("music",music.snapshot(),"radio",radio);String serial=state.toString();
            if(!lastSnapshot.equals(serial)){lastSnapshot=serial;emit("state",state);}
        }catch(Exception error){emit("error",obj("message",message(error)));}
    }
    private void playRadio(JSONObject station) throws Exception{
        if(station.optString("url").isEmpty()){openMain("radioDirectory");return;}
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
    private void openMain(String page){
        if(!Core.set("general","waze","music","radio","gate","weather","about","radioDirectory").contains(page))page="waze";
        Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("roaddeckPage",page).putExtra("skipWazeAuto",true);
        stopSelf();startActivity(intent);
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
        if(gateBusy.get())return;
        if(config.string("tuyaHome","").isEmpty()||config.string("tuyaScene","").isEmpty()){
            notice("Configurez votre scène Tuya dans Réglages → Portail.");return;
        }
        if(config.bool("gateConfirm",true)){
            gateToken=UUID.randomUUID().toString();confirmationExpires=SystemClock.elapsedRealtime()+15000;
            emit("gateConfirm",obj("token",gateToken));
        }else triggerGate();
    }
    private void triggerGate(){
        if(SystemClock.elapsedRealtime()-lastGateAt<3000){notice("Commande déjà envoyée. Vérifiez le portail.");return;}
        if(!gateBusy.compareAndSet(false,true))return;
        lastGateAt=SystemClock.elapsedRealtime();emit("gateState",obj("busy",true,"message","Envoi à Tuya…"));
        io.execute(()->{try{tuya.trigger();emit("gateState",obj("busy",false,"message","Commande acceptée par Tuya. Position du portail non vérifiée."));}
        catch(Exception error){emit("gateState",obj("busy",false,"error",true,"message",message(error)));}
        finally{gateBusy.set(false);}});
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        destroyed=true;attached=false;if(instance==this)instance=null;
        main.removeCallbacksAndMessages(null);io.shutdownNow();gateToken="";
        if(root!=null)try{windows.removeViewImmediate(root);}catch(RuntimeException ignored){}
        if(web!=null){web.removeJavascriptInterface("Native");web.stopLoading();web.destroy();web=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
