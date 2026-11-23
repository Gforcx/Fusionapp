package fr.roaddeck.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Static decoration only. It neither changes a task's bounds nor receives input. */
final class SplitFrameView extends View {
    private static final int BACKGROUND = 0xff0c1020;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corners = new Path();
    private final RectF rect = new RectF();
    private final List<SplitGeometry.Box> exclusions = new ArrayList<>();
    private final float edge, radius;
    private SplitGeometry.Box area, navigation, panel, gap;
    private boolean framed;

    SplitFrameView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        edge = Math.max(1f, 2f * density);
        radius = Math.max(edge, 6f * density);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        // No WebView, bitmap, blur, hardware layer, animation or recurring invalidation.
        setWillNotDraw(false);
    }

    int maximumInsetPixels() { return (int) Math.ceil(radius); }

    void setGeometry(SplitGeometry.Box area, SplitGeometry.Box navigation,
            SplitGeometry.Box panel, SplitGeometry.Box gap, boolean framed,
            List<SplitGeometry.Box> exclusions) {
        this.area = area;
        this.navigation = navigation;
        this.panel = panel;
        this.gap = gap;
        this.framed = framed;
        this.exclusions.clear();
        this.exclusions.addAll(exclusions);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (area == null) return;
        int saved = canvas.save();
        // Some OEM apps report bounds extending beneath a system bar. The
        // accessibility overlay is above that bar, so explicitly exclude it.
        for (SplitGeometry.Box box : exclusions)
            canvas.clipOutRect(box.left - area.left, box.top - area.top,
                    box.right - area.left, box.bottom - area.top);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        if (gap != null)
            canvas.drawRect(gap.left - area.left, gap.top - area.top,
                    gap.right - area.left, gap.bottom - area.top, paint);
        if (framed) {
            drawCardEdge(canvas, navigation, true);
            drawCardEdge(canvas, panel, false);
        }
        canvas.restoreToCount(saved);
    }

    private void drawCardEdge(Canvas canvas, SplitGeometry.Box box, boolean navigationCard) {
        if (box == null) return;
        int saved = canvas.save();
        rect.set(box.left - area.left, box.top - area.top,
                box.right - area.left, box.bottom - area.top);
        // Never paint the rectangular bounding-box slack between unequal windows.
        canvas.clipRect(rect);
        float corner = Math.min(radius, Math.min(rect.width(), rect.height()) / 8f);
        corners.reset();
        corners.setFillType(Path.FillType.EVEN_ODD);
        corners.addRect(rect, Path.Direction.CW);
        corners.addRoundRect(rect, corner, corner, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        canvas.drawPath(corners, paint);
        float stroke = Math.min(edge, corner);
        rect.inset(stroke / 2f, stroke / 2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(navigationCard ? 0xaa614879 : 0xff34263f);
        canvas.drawRoundRect(rect, Math.max(0, corner - stroke / 2f),
                Math.max(0, corner - stroke / 2f), paint);
        canvas.restoreToCount(saved);
    }
}
