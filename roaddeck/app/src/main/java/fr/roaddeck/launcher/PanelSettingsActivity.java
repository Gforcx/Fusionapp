package fr.roaddeck.launcher;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

/** Settings stay in the side-panel task instead of reopening the HOME launcher. */
public final class PanelSettingsActivity extends MainActivity {
    @Override protected boolean isPanelSettings(){return true;}

    @Override public void onCreate(Bundle savedState){
        super.onCreate(savedState);
        final View decor=getWindow().getDecorView();
        final Runnable report=()->{
            if(!isFinishing()&&!isDestroyed()&&decor.getWindowVisibility()==View.VISIBLE)
                SplitScreenService.onAppInsets(this);
        };
        ViewGroup content=findViewById(android.R.id.content);
        // Keep MainActivity's hierarchy and any existing insets listener intact.
        // This child observes dispatch first and returns the exact received value.
        View observer=new View(this);
        observer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        observer.setOnApplyWindowInsetsListener((view,insets)->{decor.post(report);return insets;});
        content.addView(observer,0,new ViewGroup.LayoutParams(0,0));
        decor.addOnLayoutChangeListener((view,l,t,r,b,oldL,oldT,oldR,oldB)->report.run());
        observer.requestApplyInsets();
        decor.post(report);
    }
}
