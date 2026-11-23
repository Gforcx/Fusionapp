package fr.roaddeck.launcher;

import android.app.*;
import android.content.*;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.*;
import androidx.media3.extractor.metadata.icy.IcyInfo;
import org.json.*;

/** Radio continues natively while Waze or a music app has focus. */
public final class RadioService extends Service {
    static RadioService instance;
    private ExoPlayer player;
    private MediaSession session;
    private Config config;
    private JSONObject station=new JSONObject();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String status="Arrêtée",track="";
    private boolean wanted;
    private int initialBuffer=15,retries;
    private long generation;
    private static final int NOTIFICATION_ID=42;
    static final String CHANNEL="roaddeck-radio";

    @Override public void onCreate() {
        super.onCreate();instance=this;config=new Config(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Lecture radio",NotificationManager.IMPORTANCE_LOW));
        session=new MediaSession(this,"RoadDeckRadio");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay(){resume();}
            @Override public void onPause(){pause();}
            @Override public void onStop(){stopSelf();}
        });
        session.setSessionActivity(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(NOTIFICATION_ID,notification());
        if(intent!=null) {
            String action=intent.getAction();
            if("stop".equals(action)){stopSelf();return START_NOT_STICKY;}
            if("pause".equals(action)){pause();return START_NOT_STICKY;}
            if("toggle".equals(action)){if(player!=null && player.getPlayWhenReady())pause();else resume();return START_NOT_STICKY;}
            if("play".equals(action))try {
                String json=intent.getStringExtra("station");
                JSONObject next=json==null?config.lastStation():new JSONObject(json);
                play(next);
            }catch(Exception e){status="Erreur : "+e.getMessage();updateNotification();}
        }
        return START_NOT_STICKY;
    }
    private void play(JSONObject next) throws Exception {
        String url=Core.streamUrl(next.optString("url"));
        station=new JSONObject().put("name",next.optString("name","Radio Internet"))
            .put("url",url).put("uuid",next.optString("uuid")).put("hls",next.optBoolean("hls")||url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8"));
        config.lastStation(station);generation++;wanted=true;retries=0;track="";
        if(player!=null){player.release();player=null;}
        boolean hls=station.optBoolean("hls");
        int[] durations=Core.bufferDurations(config.integer("radioBuffer",15),hls);initialBuffer=durations[2]/1000;
        DefaultLoadControl load=new DefaultLoadControl.Builder()
            .setBufferDurationsMs(durations[0],durations[1],durations[2],durations[3])
            .setPrioritizeTimeOverSizeThresholds(true).setBackBuffer(0,false).build();
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
            .setUserAgent("RoadDeck/0.1").setConnectTimeoutMs(10000).setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true);
        DefaultMediaSourceFactory sources=new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this,http))
            .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(Integer.MAX_VALUE) {
                @Override public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo info) {
                    if(info.exception instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code=((HttpDataSource.InvalidResponseCodeException)info.exception).responseCode;
                        if(code>=400 && code<500 && code!=408 && code!=429)return C.TIME_UNSET;
                    }
                    if(super.getRetryDelayMsFor(info)==C.TIME_UNSET)return C.TIME_UNSET;
                    return Core.retryDelay(info.errorCount);
                }
            });
        player=new ExoPlayer.Builder(this).setLoadControl(load).setMediaSourceFactory(sources).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
        player.setHandleAudioBecomingNoisy(true);player.setWakeMode(C.WAKE_MODE_NETWORK);
        MediaItem.Builder item=new MediaItem.Builder().setUri(url);
        if(hls)item.setMimeType(MimeTypes.APPLICATION_M3U8).setLiveConfiguration(
            new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(initialBuffer*1000L+5000).build());
        player.setMediaItem(item.build());
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_BUFFERING)status="Préchargement";
                else if(state==Player.STATE_READY){status=player.getPlayWhenReady()?"En lecture":"En pause";retries=0;}
                else if(state==Player.STATE_ENDED){status="Flux terminé";wanted=false;}
                updateNotification();
            }
            @Override public void onIsPlayingChanged(boolean playing){if(playing)status="En lecture";updateNotification();}
            @Override public void onPlayWhenReadyChanged(boolean ready,int reason) {
                if(!ready){wanted=false;status="En pause";}
                updateNotification();
            }
            @Override public void onMetadata(Metadata metadata) {
                for(int i=0;i<metadata.length();i++)if(metadata.get(i) instanceof IcyInfo) {
                    String title=((IcyInfo)metadata.get(i)).title;
                    if(title!=null){track=title;updateNotification();}
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                Throwable cause=error;
                while(cause!=null) {
                    if(cause instanceof HttpDataSource.InvalidResponseCodeException) {
                        int code=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                        if(code>=400&&code<500&&code!=408&&code!=429){wanted=false;status="Flux indisponible : HTTP "+code;updateNotification();return;}
                    }
                    cause=cause.getCause();
                }
                if(wanted && error.errorCode>=2000 && error.errorCode<2100) {
                    status="Reconnexion";long ticket=generation;retries++;
                    handler.postDelayed(()->{if(wanted&&generation==ticket&&player!=null){player.prepare();player.play();}},Math.min(30000,2000L*Math.min(retries,15)));
                } else {wanted=false;status="Erreur audio : "+error.getErrorCodeName();}
                updateNotification();
            }
        });
        status="Préchargement";session.setActive(true);player.prepare();player.play();updateNotification();
    }
    void pause() {
        wanted=false;generation++;
        if(player!=null)player.pause();status="En pause";updateNotification();
    }
    void resume() {
        wanted=true;
        if(player==null)try{play(config.lastStation());}catch(Exception e){status=e.getMessage();updateNotification();}
        else {if(player.getPlaybackState()==Player.STATE_IDLE)player.prepare();player.play();}
    }
    JSONObject snapshot() throws JSONException {
        JSONObject j=new JSONObject(station.toString()).put("status",status).put("track",track)
            .put("playing",player!=null&&player.isPlaying()).put("loading",player!=null&&player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_BUFFERING)
            .put("wanted",wanted).put("initialBuffer",initialBuffer).put("buffered",player==null?0:Math.max(0,player.getTotalBufferedDuration()/1000));
        return j;
    }
    private PendingIntent command(String action,int id) {
        return PendingIntent.getService(this,id,new Intent(this,RadioService.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification() {
        boolean playing=player!=null&&player.getPlayWhenReady();
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(R.drawable.ic_music).setContentTitle(station.optString("name","Radio RoadDeck"))
            .setContentText(track.isEmpty()?status:track).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play,playing?"Pause":"Lecture",command("toggle",1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Arrêter",command("stop",2)).build())
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1));
        return b.build();
    }
    private void updateNotification() {
        if(session==null)return;
        boolean playing=player!=null&&player.isPlaying();
        int state=playing?PlaybackState.STATE_PLAYING:player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_STOP).setState(state,0,playing?1:0).build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,track.isEmpty()?station.optString("name","Radio"):track)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,station.optString("name")).build());
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification());
    }
    @Override public void onDestroy() {
        wanted=false;generation++;handler.removeCallbacksAndMessages(null);
        if(player!=null){player.release();player=null;}if(session!=null){session.release();session=null;}
        instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
