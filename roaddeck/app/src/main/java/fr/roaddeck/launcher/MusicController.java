package fr.roaddeck.launcher;

import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.SystemClock;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.*;

final class MusicController {
    private final Context context;
    private final Config config;
    private Bitmap lastBitmap;
    private String lastArt="";
    MusicController(Context c, Config cfg) {context=c;config=cfg;}
    boolean authorized() {
        android.app.NotificationManager nm=context.getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT>=27)
            return nm.isNotificationListenerAccessGranted(new ComponentName(context, MediaAccessService.class));
        String enabled=android.provider.Settings.Secure.getString(context.getContentResolver(),"enabled_notification_listeners");
        return enabled!=null && enabled.contains(new ComponentName(context,MediaAccessService.class).flattenToString());
    }
    private MediaController selected() {
        if(!authorized())return null;
        try {
            MediaSessionManager mgr=context.getSystemService(MediaSessionManager.class);
            List<MediaController> sessions=mgr.getActiveSessions(new ComponentName(context,MediaAccessService.class));
            String pkg=config.string("musicPackage","com.spotify.music");
            for(MediaController s:sessions) if(s.getPackageName().equals(pkg))return s;
        } catch(SecurityException ignored) { }
        return null;
    }
    JSONObject snapshot() throws JSONException {
        JSONObject j=new JSONObject().put("authorized",authorized()).put("app",config.string("musicName","Spotify"))
            .put("package",config.string("musicPackage","com.spotify.music"));
        MediaController c=selected();
        if(c==null)return j.put("active",false).put("playing",false);
        j.put("active",true);
        MediaMetadata m=c.getMetadata(); PlaybackState p=c.getPlaybackState();
        long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position=p==null?0:Math.max(0,p.getPosition());
        boolean playing=p!=null && p.getState()==PlaybackState.STATE_PLAYING;
        if(playing && p.getLastPositionUpdateTime()>0)
            position += (long)((SystemClock.elapsedRealtime()-p.getLastPositionUpdateTime())*p.getPlaybackSpeed());
        if(duration>0)position=Math.min(position,duration);
        long actions=p==null?0:p.getActions();
        j.put("playing",playing).put("position",position).put("duration",duration)
            .put("seekable",(actions & PlaybackState.ACTION_SEEK_TO)!=0)
            .put("canNext",(actions & PlaybackState.ACTION_SKIP_TO_NEXT)!=0)
            .put("canPrevious",(actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0)
            .put("title",m==null?"Lecture en cours":value(m,MediaMetadata.METADATA_KEY_TITLE,"Lecture en cours"))
            .put("artist",m==null?"":value(m,MediaMetadata.METADATA_KEY_ARTIST,""));
        Bitmap image=m==null?null:m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if(image==null && m!=null)image=m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if(image==null && m!=null)image=m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if(image!=lastBitmap) {
            lastBitmap=image; lastArt="";
            if(image!=null)try {
                double factor=Math.min(1.0,256.0/Math.max(image.getWidth(),image.getHeight()));
                Bitmap small=Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*factor)),Math.max(1,(int)(image.getHeight()*factor)),true);
                ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,85,out);
                lastArt="data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                if(small!=image)small.recycle();
            }catch(RuntimeException ignored) {lastArt="";}
        }
        return j.put("art",lastArt);
    }
    private String value(MediaMetadata m,String key,String fallback) {
        String s=m.getString(key);return s==null||s.isEmpty()?fallback:s;
    }
    boolean control(String action,long position) throws Exception {
        MediaController c=selected();if(c==null)return false;
        MediaController.TransportControls t=c.getTransportControls();
        PlaybackState p=c.getPlaybackState(); long actions=p==null?0:p.getActions();
        switch(action) {
            case "toggle": if(p!=null && p.getState()==PlaybackState.STATE_PLAYING)t.pause();else t.play();break;
            case "pause": t.pause();break;
            case "next": if((actions&PlaybackState.ACTION_SKIP_TO_NEXT)==0)throw new Exception("Suivant non proposé par cette application");t.skipToNext();break;
            case "previous": if((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)==0)throw new Exception("Précédent non proposé par cette application");t.skipToPrevious();break;
            case "seek": if((actions&PlaybackState.ACTION_SEEK_TO)==0)throw new Exception("Déplacement non proposé par cette application");t.seekTo(Math.max(0,position));break;
            default: throw new IllegalArgumentException("Commande musicale inconnue");
        }
        return true;
    }
}
