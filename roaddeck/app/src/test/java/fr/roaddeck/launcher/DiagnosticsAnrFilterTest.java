package fr.roaddeck.launcher;

/** Synthetic ART trace fixtures; calls the production parser without Android APIs. */
public final class DiagnosticsAnrFilterTest {
    static int checks;
    static void check(boolean value,String message){checks++;org.junit.Assert.assertTrue(message,value);}
    static final String OWN="fr.roaddeck.launcher";
    static String header(String name,int pid){return "----- pid "+pid+" at 2026-11-23 11:24:27 -----\nCmd line: "+name+"\n";}
    static String mainThread(){return "\"main\" prio=5 tid=1 Native\n  | group=\"main\" secret=SECRET\n";}
    static String frame(){return "  at android.graphics.HardwareRenderer.nSyncAndDrawFrame(Native method)\n";}
    static String filter(String raw){return Diagnostics.filterOwnAnrFrames(raw,OWN,2223);}
    @org.junit.Test
    public void filtersOwnMainAndRenderThreadFrames(){
        checks=0;
        String trace=header(OWN,2223)+"Build fingerprint: PRIVATE_BUILD\n"+mainThread()+frame()
            +"  at fr.roaddeck.launcher.MainActivity.onCreate(MainActivity.java:42)\n"
            +"  - waiting to lock <0xPRIVATE> (a private.Document) held by thread 7\n"
            +"  MESSAGE_SECRET_TUYA\n  at private.Message.invalid(url=https://PRIVATE.example/doc?secret=SECRET)\n"
            +"\"worker-secret\" prio=5 tid=3 Runnable\n  at secret.Document.read(file.java:1)\n"
            +"\"RenderThread\" daemon prio=10 tid=17 Native\n"
            +"  native: #00 pc 0000123 /system/lib64/libhwui.so (android::uirenderer::renderthread::RenderThread::threadLoop()+336) (BuildId: abc123)\n"
            +"  native: #01 pc 0000124 /apex/private/libc.so (__futex_wait_ex+148) (BuildId: abc123)\n"
            +"----- end 2223 -----\n"+mainThread()+"  at secret.OutOfProcess.nope(x:1)\n";
        String got=filter(trace);
        check(got.contains("main ; state=Native"),"main state retained");
        check(got.contains("RenderThread ; state=Native"),"render state retained");
        check(got.contains("android.graphics.HardwareRenderer.nSyncAndDrawFrame\n"),"Java class/method retained");
        check(got.contains("fr.roaddeck.launcher.MainActivity.onCreate\n"),"application frame retained");
        check(got.contains("android::uirenderer::renderthread::RenderThread::threadLoop\n"),"native class/method retained");
        check(got.contains("__futex_wait_ex\n"),"native function retained");
        check(!got.contains("SECRET")&&!got.contains("PRIVATE")&&!got.contains("https://"),"private text absent");
        check(!got.contains("MainActivity.java")&&!got.contains("/system/")&&!got.contains("BuildId"),"source paths and build ids omitted");
        check(!got.contains("secret.Document")&&!got.contains("OutOfProcess"),"other threads and closed process excluded");
        check(filter(header("com.foreign",2223)+mainThread()+frame()).isEmpty(),"foreign process excluded");
        check(filter(header(OWN,2224)+mainThread()+frame()).isEmpty(),"different PID excluded");
        check(filter(header(OWN+":worker",2223)+mainThread()+frame()).isEmpty(),"subprocess excluded");
        check(filter(header(OWN+".foreign",2223)+mainThread()+frame()).isEmpty(),"package prefix excluded");
        check(filter("Cmd line: "+OWN+"\n"+mainThread()+frame()).isEmpty(),"PID header required");
        check(filter("----- pid 2223 at timestamp -----\n"+mainThread()+frame()).isEmpty(),"process header required");
        check(filter(header(OWN,2223)+"Cmd line: com.foreign\n"+mainThread()+frame()).isEmpty(),"changed process ownership rejected");
        check(filter(header(OWN,2223)+"\"RenderThread\" sysTid=17\n  native: #00 pc a1 /system/lib.so (poll+5)\n").contains("RenderThread ; state=unknown\n  native poll\n"),"native-only thread supported without guessing state");
        check(filter(header(OWN,2223)+"\"main\" prio=5 tid=1 SECRETSTATE\n"+frame()).contains("state=unknown"),"unknown state redacted");
        check(!filter(header(OWN,2223)+"\"main\" prio=5 tid=1 SECRETSTATE\n"+frame()).contains("SECRETSTATE"),"free state omitted");
        String many=header(OWN,2223)+mainThread();for(int i=0;i<60;i++)many+=frame();
        check(filter(many).lines().filter(x->x.startsWith("  at ")).count()==32,"32 frames per thread maximum");
        String padding="x".repeat(8192);
        check(filter(padding+"\n"+trace).isEmpty(),"reader parser boundary at8KiB");
        check(filter(null).isEmpty()&&filter("").isEmpty(),"empty input tolerated");
        check(filter("malformed document SECRET").isEmpty(),"unrecognized format returns no text");
        check(Diagnostics.filterOwnAnrFrames(trace,OWN,0).isEmpty(),"invalid PID rejected");
        System.out.println("PASS: "+checks+" assertions on the production ANR parser; synthetic ART trace fixtures.");
    }
}
