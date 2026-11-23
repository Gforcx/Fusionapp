package fr.roaddeck.launcher;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Rect;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import java.io.*;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private android.widget.FrameLayout root;
    private String pendingPage;
    private AlertDialog gateConfirmation;
    private Config config;
    private MusicController music;
    private TuyaClient tuya;
    private final RadioDirectory directory=new RadioDirectory();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private final AtomicBoolean weatherBusy=new AtomicBoolean(),gateBusy=new AtomicBoolean(),diagnosticBusy=new AtomicBoolean();
    private boolean ready,visible,gateDialog;
    private long nextWeatherAttempt,lastGateAt;
    private String lastState="";
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private final Runnable tick=new Runnable(){public void run(){if(!visible)return;pushState();refreshWeather(false);emit("wazeState",overlaySnapshot());main.postDelayed(this,1000);}};

    protected boolean isPanelSettings(){return false;}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);config=new Config(this);music=new MusicController(this,config);tuya=new TuyaClient(config);
        root=new android.widget.FrameLayout(this);
        web=new WebView(this);web.setBackgroundColor(0xff090d19);
        root.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(root);
        pendingPage=getIntent().getStringExtra("roaddeckPage");
        if(isPanelSettings()&&pendingPage==null)pendingPage="general";
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();
                if("https".equals(u.getScheme())&&"appassets.androidplatform.net".equals(u.getHost())){
                    String path=u.getPath();
                    if(path!=null && path.matches("/assets/ui/[A-Za-z0-9_.-]+"))try{
                        String mime=path.endsWith(".html")?"text/html":path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".webp")?"image/webp":path.endsWith(".png")?"image/png":path.endsWith(".svg")?"image/svg+xml":"application/octet-stream";
                        return new WebResourceResponse(mime,"UTF-8",getAssets().open(path.substring(8)));
                    }catch(IOException ignored){}
                }
                return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageFinished(WebView v,String url){/* UI sends ready only after its script is loaded. */}
        });
        web.addJavascriptInterface(new Bridge(),"Native");
        fullscreen();applyScreenSettings();web.loadUrl(ORIGIN+"/assets/ui/index.html"+(isPanelSettings()?"?panelSettings=1":""));
    }
    private void fullscreen(){
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void applyScreenSettings(){
        if(config.bool("keepScreen",true))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onStart(){
        super.onStart();visible=true;main.removeCallbacks(tick);main.post(tick);
        if(isPanelSettings())SplitScreenService.onPanelState(true,isInMultiWindowMode());
    }
    @Override protected void onStop(){
        visible=false;main.removeCallbacks(tick);
        if(gateConfirmation!=null){gateConfirmation.dismiss();gateConfirmation=null;}gateDialog=false;
        if(isPanelSettings()&&!isFinishing()&&!isChangingConfigurations())SplitScreenService.onPanelState(false,isInMultiWindowMode());
        super.onStop();
    }
    @Override protected void onResume(){
        super.onResume();fullscreen();
        stopService(new Intent(this,OverlayService.class));
        if(isPanelSettings())SplitScreenService.onPanelState(true,isInMultiWindowMode());
        if(ready){pushState();emit("wazeState",overlaySnapshot());}
    }
    @Override public void onMultiWindowModeChanged(boolean inMultiWindowMode,android.content.res.Configuration configuration){
        super.onMultiWindowModeChanged(inMultiWindowMode,configuration);
        if(isPanelSettings())SplitScreenService.onPanelState(visible,inMultiWindowMode);
        if(ready)emit("wazeState",overlaySnapshot());
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        pendingPage=intent.getStringExtra("roaddeckPage");
        if(ready)showPendingPage();
    }
    private void showPendingPage(){
        String page=pendingPage;pendingPage=null;
        if(page==null)return;
        if("radioDirectory".equals(page))web.evaluateJavascript("window.RoadDeck.radioDirectory()",null);
        else if(Core.set("general","waze","music","radio","gate","weather","about").contains(page))
            web.evaluateJavascript("window.RoadDeck.openSettings("+JSONObject.quote(page)+")",null);
    }
    private JSONObject settingsForUi() throws JSONException {
        JSONObject settings=config.publicSettings();
        if(isPanelSettings()||getIntent().getBooleanExtra("skipWazeAuto",false))settings.put("suppressAutoWaze",true);
        return settings;
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)fullscreen();}
    @Override public void onBackPressed(){if(isPanelSettings()&&!ready){finish();return;}if(web!=null)web.evaluateJavascript("window.RoadDeck&&window.RoadDeck.back()",null);}
    @Override protected void onDestroy(){ready=false;main.removeCallbacksAndMessages(null);io.shutdownNow();if(web!=null){web.removeJavascriptInterface("Native");web.destroy();web=null;}super.onDestroy();}

    private final class Bridge {
        @JavascriptInterface public void post(String message){
            if(message==null||message.length()>32000)return;
            main.post(()->{try{handle(new JSONObject(message));}catch(Exception e){error(e);}});
        }
    }
    private JSONObject obj(Object... fields){
        JSONObject j=new JSONObject();try{for(int i=0;i<fields.length;i+=2)j.put((String)fields[i],fields[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return j;
    }
    private void emit(String type,Object data){
        if(isDestroyed())return;
        main.post(()->{if(ready&&web!=null&&!isDestroyed())web.evaluateJavascript("window.RoadDeck.receive("+obj("type",type,"data",data).toString()+")",null);});
    }
    private void error(Exception e){emit("error",obj("message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));}
    private void toast(String text){emit("notice",obj("message",text));}
    private void handle(JSONObject command) throws Exception {
        String type=command.optString("type");JSONObject d=command.optJSONObject("data");if(d==null)d=new JSONObject();
        final JSONObject data=d;
        switch(type){
            case "ready": ready=true;emit("settings",settingsForUi());emit("favorites",config.favorites());emit("weather",config.weather());emit("diagnostics",diagnostics());pushState();emit("wazeState",overlaySnapshot());main.postDelayed(this::showPendingPage,100);break;
            case "saveSettings": config.save(d);SplitScreenService.settingsChanged();applyScreenSettings();nextWeatherAttempt=0;emit("settings",config.publicSettings());emit("saved",obj("message","Réglages enregistrés sur cet appareil"));pushState();break;
            case "openWaze": openWaze(d);break;
            case "permissions": musicPermissionHelp();break;
            case "appInfo": startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));break;
            case "notificationAccess": startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));break;
            case "wazeSetup": requestSplitSetup();break;
            case "wazeClose": SplitScreenService.closePair(this);emit("wazeState",overlaySnapshot());break;
            case "closePanelSettings": if(isPanelSettings())finish();break;
            case "wazeBack": toast("Utilisez le bouton Retour d’Android dans Waze.");break;
            case "wazeLayout": case "overlay": break; // Legacy UI commands do not control Waze's window.
            case "notificationPermission": if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);break;
            case "defaultLauncher": startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));break;
            case "androidSettings": startActivity(new Intent(Settings.ACTION_SETTINGS));break;
            case "wifiSettings": startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));break;
            case "bluetoothSettings": startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));break;
            case "diagnostics": emit("diagnostics",diagnostics());break;
            case "copyDiagnostics": copyDiagnostics();break;
            case "apps": listApps(d.optString("purpose","open"));break;
            case "openApp": launchPackage(d.optString("package"));break;
            case "openMusic": if(RadioService.instance!=null)RadioService.instance.pause();launchPackage(config.string("musicPackage","com.spotify.music"));break;
            case "media":
                String action=d.optString("action");
                if(action.equals("toggle")&&RadioService.instance!=null)RadioService.instance.pause();
                if(!music.control(action,d.optLong("position"))) {
                    if(!music.authorized())toast("Autoriser l’accès aux notifications dans Réglages > Musique.");
                    else if(action.equals("toggle"))launchPackage(config.string("musicPackage","com.spotify.music"));
                    else toast("Lancer d’abord une lecture dans l’application musicale.");
                }
                pushState();break;
            case "radioSearch":
                io.execute(()->{try{emit("radioResults",obj("request",data.optInt("request"),"stations",directory.search(data.optString("query"),data.optBoolean("franceOnly",true))));}catch(Exception e){emit("radioResults",obj("request",data.optInt("request"),"stations",new JSONArray(),"error",e.getMessage()));}});break;
            case "radioPlay": startRadio(d);break;
            case "radioControl":
                if("stop".equals(d.optString("action"))){stopService(new Intent(this,RadioService.class));}
                else if(RadioService.instance!=null){if(RadioService.instance.snapshot().optBoolean("wanted"))RadioService.instance.pause();else{music.control("pause",0);RadioService.instance.resume();}}
                else startRadio(config.lastStation());
                break;
            case "favorite": config.favorite(d);emit("favorites",config.favorites());break;
            case "gate": openGate();break;
            case "openTuya":
                if(installed("com.tuya.smart"))launchPackage("com.tuya.smart");else launchPackage("com.tuya.smartlife");break;
            case "tuyaTest":
                io.execute(()->{try{JSONArray raw=tuya.scenes(),clean=new JSONArray();for(int i=0;i<raw.length();i++){JSONObject s=raw.getJSONObject(i);clean.put(obj("name",s.optString("name","Scène"),"id",s.optString("scene_id",s.optString("id"))));}emit("tuyaScenes",clean);}catch(Exception e){error(e);}});break;
            case "cities":
                io.execute(()->{try{emit("cities",obj("request",data.optInt("request"),"items",WeatherClient.cities(data.optString("query"))));}catch(Exception e){error(e);}});break;
            case "weatherRefresh": nextWeatherAttempt=0;refreshWeather(true);break;
            default:throw new IllegalArgumentException("Commande inconnue");
        }
    }
    private boolean installed(String pkg){return getPackageManager().getLaunchIntentForPackage(pkg)!=null;}
    private void launchPackage(String pkg) throws Exception {
        if("com.waze".equals(pkg)){web.evaluateJavascript("window.RoadDeck.openWaze()",null);return;}
        if(!pkg.matches("[A-Za-z0-9_.]{1,200}"))throw new IllegalArgumentException("Application invalide");
        Intent intent=getPackageManager().getLaunchIntentForPackage(pkg);
        if(intent==null)throw new Exception("Application non installée : "+pkg);
        startActivity(intent);
    }
    private void listApps(String purpose){
        io.execute(()->{try{
            List<ResolveInfo> apps=getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
            Collator col=Collator.getInstance(Locale.FRANCE);apps.sort((a,b)->col.compare(a.loadLabel(getPackageManager()).toString(),b.loadLabel(getPackageManager()).toString()));
            JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();
            for(ResolveInfo info:apps){String pkg=info.activityInfo.packageName;if(pkg.equals(getPackageName())||!seen.add(pkg))continue;result.put(obj("name",info.loadLabel(getPackageManager()).toString(),"package",pkg));}
            emit("apps",obj("purpose",purpose,"items",result));
        }catch(Exception e){error(e);}});
    }
    private JSONObject overlaySnapshot(){
        return SplitScreenService.snapshot(this);
    }
    private void requestSplitSetup(){
        stopService(new Intent(this,OverlayService.class));
        JSONObject state=overlaySnapshot();
        if(state.optBoolean("serviceConnected",SplitScreenService.isConnected())){
            if("unavailable".equals(state.optString("splitActionAvailability")))
                toast("Le service RoadDeck est connecté, mais Android ne lui propose pas actuellement la commande d’écran partagé.");
            else toast("Le service RoadDeck est connecté. Appuyez sur Ouvrir Waze.");
            return;
        }
        boolean granted=state.optBoolean("accessibilityGranted");
        String explanation=granted
            ? "L’autorisation d’accessibilité est déjà enregistrée, mais le service RoadDeck n’est pas connecté à Android. La cause n’est pas déterminée.\n\nDans les réglages d’accessibilité, désactivez puis réactivez RoadDeck et revenez ici pour vérifier la connexion. Si le problème continue, ouvrez Informations et copiez le rapport de diagnostic."
            : "Activez l’assistant RoadDeck dans les réglages d’accessibilité. Il demande à Android de redimensionner les deux fenêtres. RoadDeck ne lit pas vos messages ni le contenu des pages. Le mode écran partagé doit être disponible sur cet appareil.";
        new AlertDialog.Builder(this).setTitle(granted?"Service autorisé mais déconnecté":"Waze et RoadDeck côte à côte")
            .setMessage(explanation)
            .setNegativeButton("Annuler",null)
            .setNeutralButton("Informations",(dialog,which)->{pendingPage="about";if(ready)showPendingPage();})
            .setPositiveButton("Ouvrir les réglages",(dialog,which)->{
                try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
                catch(ActivityNotFoundException error){toast("Ouvrez les réglages Android → Accessibilité → RoadDeck.");}
            }).show();
    }
    private void openWaze(JSONObject data) throws Exception {
        stopService(new Intent(this,OverlayService.class));
        if(!visible)throw new Exception("Revenez dans RoadDeck pour lancer Waze et le panneau.");
        if(!installed("com.waze"))throw new Exception("Installez et ouvrez Waze une première fois avant de lancer l’écran partagé.");
        if(!SplitScreenService.isConnected()){
            if(data.optBoolean("automatic",false))toast(overlaySnapshot().optBoolean("accessibilityGranted")
                ?"RoadDeck est autorisé mais son service n’est pas connecté. Consultez Réglages → Waze ou copiez le diagnostic dans Informations."
                :"Activez d’abord l’assistant écran partagé dans Réglages → Waze.");
            else requestSplitSetup();
            return;
        }
        boolean started=SplitScreenService.begin(this);
        if(started&&isPanelSettings()&&SplitScreenService.snapshot(this).optBoolean("active"))finish();
        emit("wazeState",overlaySnapshot());
        if(!started)toast(overlaySnapshot().optString("message","L’écran partagé n’a pas pu démarrer."));
    }
    private void musicPermissionHelp(){
        if(Build.VERSION.SDK_INT<33||music.authorized()){
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));return;
        }
        new AlertDialog.Builder(this).setTitle("Commandes musicales : accès aux notifications")
            .setMessage("Android peut bloquer ce droit pour une APK installée manuellement.\n\n1. Ouvrez Infos de l’appli.\n2. Menu ⋮ > Autoriser les paramètres restreints (ou option en bas de la page). Confirmez avec votre code.\n3. Revenez ici, puis Accès aux notifications et activez RoadDeck.\n\nCe droit peut donner accès à des notifications sensibles. Il est facultatif et ne sert pas à intégrer Waze.")
            .setNegativeButton("Annuler",null)
            .setNeutralButton("Infos de l’appli",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
            .setPositiveButton("Accès aux notifications",(d,w)->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))).show();
    }
    private void startRadio(JSONObject station) throws Exception {
        Core.streamUrl(station.optString("url"));music.control("pause",0);
        Intent intent=new Intent(this,RadioService.class).setAction("play").putExtra("station",station.toString());
        startForegroundService(intent);
        String uuid=station.optString("uuid");if(!uuid.isEmpty())io.execute(()->directory.click(uuid));
    }
    private void pushState(){
        if(!ready)return;
        try{
            JSONObject radio=RadioService.instance==null?config.lastStation().put("playing",false).put("wanted",false).put("status","Arrêtée").put("buffered",0):RadioService.instance.snapshot();
            JSONObject state=obj("music",music.snapshot(),"radio",radio,"online",online());
            String serialized=state.toString();if(!serialized.equals(lastState)){lastState=serialized;emit("state",state);}
        }catch(Exception e){error(e);}
    }
    private boolean online(){
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network network=cm.getActiveNetwork();
        NetworkCapabilities c=network==null?null:cm.getNetworkCapabilities(network);
        return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private JSONObject diagnostics(){
        JSONObject panel=overlaySnapshot();
        return obj("android",Build.VERSION.RELEASE,"sdk",Build.VERSION.SDK_INT,"device",Build.MANUFACTURER+" "+Build.MODEL,
            "width",getResources().getDisplayMetrics().widthPixels,"height",getResources().getDisplayMetrics().heightPixels,
            "wazeInstalled",installed("com.waze"),"wazePanel",panel,"accessibilityGranted",panel.optBoolean("accessibilityGranted"),
            "accessibilityConnected",panel.optBoolean("serviceConnected",SplitScreenService.isConnected()),"diagnosticReportAvailable",true,"multiWindow",isInMultiWindowMode(),
            "musicAccess",music.authorized(),"musicListenerConnected",MediaAccessService.connected,
            "tuyaInstalled",installed("com.tuya.smart")||installed("com.tuya.smartlife"),"online",online());
    }
    private void copyDiagnostics(){
        if(!visible||isFinishing()||isDestroyed()||!diagnosticBusy.compareAndSet(false,true))return;
        JSONObject screen=diagnostics();
        toast("Préparation du rapport local…");
        io.execute(()->{
            try{
                String report=Diagnostics.report(getApplicationContext(),screen);
                main.post(()->{
                    try{
                        if(!visible||isFinishing()||isDestroyed())return;
                        ClipboardManager clipboard=getSystemService(ClipboardManager.class);
                        if(clipboard==null)throw new IllegalStateException("Presse-papiers indisponible");
                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic RoadDeck",report));
                        JSONObject updated=diagnostics();updated.put("diagnosticReport",report);
                        emit("diagnostics",updated);toast("Rapport copié. Vous pouvez le coller dans votre message.");
                    }catch(Exception error){error(error);}
                    finally{diagnosticBusy.set(false);}
                });
            }catch(Exception error){diagnosticBusy.set(false);error(error);}
        });
    }
    private void refreshWeather(boolean force){
        if(!ready||weatherBusy.get()||(!force&&System.currentTimeMillis()<nextWeatherAttempt))return;
        try{
            JSONObject cfg=config.publicSettings();if(cfg.optString("weatherLat").isEmpty()||cfg.optString("weatherLon").isEmpty())return;
            JSONObject cached=config.weather();long now=System.currentTimeMillis();
            boolean same=cfg.optString("weatherLat").equals(cached.optString("lat"))&&cfg.optString("weatherLon").equals(cached.optString("lon"));
            if(!force&&same&&now-cached.optLong("updatedAt")<900000){nextWeatherAttempt=cached.optLong("updatedAt")+900000;return;}
            if(!weatherBusy.compareAndSet(false,true))return;nextWeatherAttempt=now+300000;
            io.execute(()->{try{
                JSONObject result=WeatherClient.current(cfg);
                JSONObject current=config.publicSettings();
                if(current.optString("weatherLat").equals(cfg.optString("weatherLat"))&&current.optString("weatherLon").equals(cfg.optString("weatherLon"))){config.cacheWeather(result);nextWeatherAttempt=System.currentTimeMillis()+900000;emit("weather",result);}
            }catch(Exception e){JSONObject cache=config.weather();try{cache.put("error",e.getMessage());emit("weather",cache);}catch(Exception ignored){}}finally{weatherBusy.set(false);}});
        }catch(Exception e){error(e);}
    }
    private void openGate(){
        if(!visible||gateBusy.get()||gateDialog)return;
        if(SystemClock.elapsedRealtime()-lastGateAt<3000){toast("Commande déjà envoyée. Vérifier le portail.");return;}
        if(config.bool("gateConfirm",true)){
            gateDialog=true;
            gateConfirmation=new AlertDialog.Builder(this).setTitle("Commander le portail ?")
                .setMessage("Exécuter la scène Tuya configurée. Vérifier que personne ne se trouve dans la zone de mouvement.")
                .setNegativeButton("Annuler",null).setPositiveButton("Envoyer",(dialog,which)->triggerGate())
                .setOnDismissListener(dialog->{gateDialog=false;gateConfirmation=null;}).show();
        }else triggerGate();
    }
    private void triggerGate(){
        if(!visible)return;
        if(!gateBusy.compareAndSet(false,true))return;
        lastGateAt=SystemClock.elapsedRealtime();emit("gateState",obj("busy",true,"message","Envoi à Tuya…"));
        io.execute(()->{try{
            tuya.trigger();emit("gateState",obj("busy",false,"message","Commande acceptée par Tuya. Position du portail non vérifiée."));
        }catch(Exception e){emit("gateState",obj("busy",false,"error",true,"message",e.getMessage()));}finally{gateBusy.set(false);}});
    }
}
