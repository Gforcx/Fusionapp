package fr.roaddeck.launcher;
public final class OverlayGeometryTest {
    private static int count;
    private static void check(boolean b,String message){count++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        check(OverlayGeometry.percent(-100)==24,"Lower clamp");
        check(OverlayGeometry.percent(500)==46,"Upper clamp");
        check(OverlayGeometry.percent(34)==34,"Default retained");
        for(int w:new int[]{480,800,904,1024,1280,1648,1920,3840})
            for(int p:new int[]{-1,24,28,34,40,46,100}){
                int px=OverlayGeometry.width(w,p);
                check(px>0&&px<w/2,"Left half remains outside overlay");
                check(px==Math.round(w*OverlayGeometry.percent(p)/100f),"Expected pixel width");
            }
        boolean thrown=false;try{OverlayGeometry.width(0,34);}catch(IllegalArgumentException e){thrown=true;}
        check(thrown,"Zero width rejected");
        System.out.println("PASS: "+count+" overlay geometry assertions");
    }
}
