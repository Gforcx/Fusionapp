package fr.roaddeck.launcher;
import java.util.Arrays;

public final class CoreTest {
    static int assertions;
    static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static void rejects(Runnable r){boolean failed=false;try{r.run();}catch(IllegalArgumentException e){failed=true;}check(failed,"Invalid input was accepted");}
    public static void main(String[] args){
        check(Core.sha256("").equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"SHA-256 empty body");
        check(Core.sha256("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),"SHA-256 abc");
        check(Core.hmac("Jefe","what do ya want for nothing?").equals("5BDCC146BF60754E6A042426089575C75A003F089D2739839DEC58B964EC3843"),"HMAC RFC 4231 vector");
        check(Core.sign("client-test","secret-test","","1700000000000","nonce","GET","/v1.0/token?grant_type=1","").equals(args[0]),"Token signature independent Python oracle");
        check(Core.sign("client-test","secret-test","token-test","1700000000000","nonce","POST","/v1.0/homes/123/scenes/abc/trigger","").equals(args[1]),"Scene signature independent Python oracle");
        check(Core.safeId("123456789").equals("123456789"),"Home ID fixture");
        check(Core.safeId("scene_ABC-12").equals("scene_ABC-12"),"Scene ID");
        for(String id:new String[]{"", "../x", "a/b", "abc?x=1", "abc\nxyz"})rejects(()->Core.safeId(id));
        check(Core.endpoint("https://openapi.tuyaeu.com").endsWith("tuyaeu.com"),"Allowed endpoint");
        for(String endpoint:new String[]{"http://openapi.tuyaeu.com","https://openapi.tuyaeu.com.attacker.example","https://example.com"})rejects(()->Core.endpoint(endpoint));
        check(Core.streamUrl(" https://example.org/live.mp3 ").equals("https://example.org/live.mp3"),"Stream URL trimming");
        check(Core.streamUrl("http://example.org:8000/live").startsWith("http://"),"Legacy HTTP stream");
        for(String u:new String[]{"javascript:alert(1)","file:///etc/passwd","ftp://example.org/file","https://user:secret@example.org/live","https:///missing-host"})rejects(()->Core.streamUrl(u));
        for(int buffer:new int[]{-1,0,5,15,30,60,120,999})for(boolean hls:new boolean[]{false,true}){
            int[] v=Core.bufferDurations(buffer,hls);
            check(v[0]>=v[2]&&v[0]>=v[3]&&v[1]>=v[0],"Media3 duration invariants "+Arrays.toString(v));
            check(v[2]>=5000&&v[2]<=120000,"Startup buffer clamp");
            if(hls)check(v[2]<=8000,"HLS short-window guard");
        }
        check(Core.retryDelay(-2)==1000,"Minimum retry delay");
        check(Core.retryDelay(4)==4000,"Retry progression");
        check(Core.retryDelay(Integer.MAX_VALUE)==5000,"Retry cap");
        System.out.println("PASS: "+assertions+" core assertions");
    }
}
