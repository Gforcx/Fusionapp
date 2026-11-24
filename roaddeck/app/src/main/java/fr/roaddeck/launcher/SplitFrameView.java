package fr.roaddeck.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Passive decoration: native task bounds and all touch targets remain unchanged. */
final class SplitFrameView extends View {
    interface DrawListener { void onFrameDrawn(SplitFrameView view); }
    private static final int BACKGROUND = 0xff0c1020;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path navigationFill = new Path(), navigationAccent = new Path();
    private final Path panelFill = new Path(), panelAccent = new Path();
    private final RectF inner = new RectF();
    private final List<SplitGeometry.Box> exclusions = new ArrayList<>();
    private final float density, edge, radius;
    private final DrawListener listener;
    private SplitGeometry.Box area, navigation, panel, gap;
    private boolean framed, preview;
    private int leftInset, topInset, rightInset, bottomInset;
    private int revision, drawnRevision = -1;
    private long navigationPixels, totalPixels, gapPixels, previewPixels;

    SplitFrameView(Context context, DrawListener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        edge = Math.max(1f, 2f * density);
        radius = 18f * density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
    }

    int maximumInsetPixels() {
        // Rounded corners occupy a little more than the straight margins.
        return (int) Math.ceil(Math.max(Math.max(leftInset, rightInset),
                Math.max(topInset, bottomInset)) + radius + edge);
    }
    boolean hasDrawnGeometry() { return drawnRevision == revision; }
    long navigationPaintedPixels() { return hasDrawnGeometry() ? navigationPixels : 0; }
    long paintedPixels() { return hasDrawnGeometry() ? totalPixels : 0; }
    long gapPaintedPixels() { return hasDrawnGeometry() ? gapPixels : 0; }
    long previewPaintedPixels() { return hasDrawnGeometry() ? previewPixels : 0; }
    int revision() { return revision; }
    int drawnRevision() { return drawnRevision; }

    void setGeometry(SplitGeometry.Box area, SplitGeometry.Box navigation,
            SplitGeometry.Box panel, SplitGeometry.Box gap, boolean framed,
            List<SplitGeometry.Box> exclusions, int left, int top, int right, int bottom,
            boolean preview) {
        this.area = area;
        this.navigation = navigation;
        this.panel = panel;
        this.gap = gap;
        this.framed = framed;
        this.preview = preview;
        this.leftInset = Math.round(left * density);
        this.topInset = Math.round(top * density);
        this.rightInset = Math.round(right * density);
        this.bottomInset = Math.round(bottom * density);
        this.exclusions.clear();
        this.exclusions.addAll(exclusions);
        revision++;
        drawnRevision = -1;
        buildPaths();
        invalidate();
    }

    private void buildPaths() {
        navigationFill.reset(); navigationAccent.reset();
        panelFill.reset(); panelAccent.reset();
        if (area == null || !framed) return;
        if (navigation != null) {
            RectF outer = local(navigation);
            // Keep a transparent opening even on unusually small split surfaces.
            float horizontalScale = Math.min(1f, Math.max(0f, outer.width() - edge * 2f)
                    / Math.max(1f, leftInset + rightInset));
            float verticalScale = Math.min(1f, Math.max(0f, outer.height() - edge * 2f)
                    / Math.max(1f, topInset + bottomInset));
            inner.set(outer.left + leftInset * horizontalScale,
                    outer.top + topInset * verticalScale,
                    outer.right - rightInset * horizontalScale,
                    outer.bottom - bottomInset * verticalScale);
            float corner = Math.min(radius, Math.min(inner.width(), inner.height()) / 2f);
            navigationFill.setFillType(Path.FillType.EVEN_ODD);
            navigationFill.addRect(outer, Path.Direction.CW);
            navigationFill.addRoundRect(inner, corner, corner, Path.Direction.CW);
            Path contour = new Path();
            contour.addRoundRect(inner, corner, corner, Path.Direction.CW);
            strokePath(contour, navigationAccent, edge);
        }
        if (panel != null) {
            RectF outer = local(panel);
            float corner = Math.min(6f * density, Math.min(outer.width(), outer.height()) / 8f);
            panelFill.setFillType(Path.FillType.EVEN_ODD);
            panelFill.addRect(outer, Path.Direction.CW);
            panelFill.addRoundRect(outer, corner, corner, Path.Direction.CW);
            outer.inset(edge / 2f, edge / 2f);
            Path contour = new Path();
            contour.addRoundRect(outer, Math.max(0f, corner - edge / 2f),
                    Math.max(0f, corner - edge / 2f), Path.Direction.CW);
            strokePath(contour, panelAccent, edge);
        }
    }

    private void strokePath(Path contour, Path destination, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.getFillPath(contour, destination);
        paint.setStyle(Paint.Style.FILL);
    }

    private RectF local(SplitGeometry.Box box) {
        return new RectF(box.left - area.left, box.top - area.top,
                box.right - area.left, box.bottom - area.top);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (area == null) return;
        int saved = canvas.save();
        Region allowed = new Region(0, 0, getWidth(), getHeight());
        for (SplitGeometry.Box box : exclusions) {
            int left = box.left - area.left, top = box.top - area.top;
            int right = box.right - area.left, bottom = box.bottom - area.top;
            canvas.clipOutRect(left, top, right, bottom);
            allowed.op(left, top, right, bottom, Region.Op.DIFFERENCE);
        }
        previewPixels = 0;
        Region total = new Region();
        Region nav = new Region();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        gapPixels = 0;
        if (gap != null) {
            RectF bounds = local(gap);
            canvas.drawRect(bounds, paint);
            Region gapRegion = new Region((int) bounds.left, (int) bounds.top,
                    (int) bounds.right, (int) bounds.bottom);
            gapRegion.op(allowed, Region.Op.INTERSECT);
            total.op(gapRegion, Region.Op.UNION);
            gapPixels = pixelArea(gapRegion);
        }
        if (framed) {
            drawShape(canvas, navigationFill, navigation, BACKGROUND);
            drawShape(canvas, navigationAccent, navigation, 0xff986acc);
            drawShape(canvas, panelFill, panel, BACKGROUND);
            drawShape(canvas, panelAccent, panel, 0xff34263f);
            addShape(nav, navigationFill, navigation, allowed);
            addShape(nav, navigationAccent, navigation, allowed);
            total.op(nav, Region.Op.UNION);
            addShape(total, panelFill, panel, allowed);
            addShape(total, panelAccent, panel, allowed);
            if (preview) drawPreview(canvas, allowed);
        }
        canvas.restoreToCount(saved);
        navigationPixels = pixelArea(nav);
        totalPixels = pixelArea(total);
        if (drawnRevision != revision) {
            drawnRevision = revision;
            final int submittedRevision = revision;
            // One notification per geometry; publishing never invalidates this view.
            post(() -> {
                if (listener != null && isAttachedToWindow() && revision == submittedRevision)
                    listener.onFrameDrawn(this);
            });
        }
    }

    private void drawShape(Canvas canvas, Path path, SplitGeometry.Box bounds, int color) {
        if (bounds == null) return;
        int saved = canvas.save();
        canvas.clipRect(local(bounds));
        paint.setColor(color);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(saved);
    }

    private void addShape(Region result, Path path, SplitGeometry.Box bounds, Region allowed) {
        if (bounds == null) return;
        Region clipping = new Region(bounds.left - area.left, bounds.top - area.top,
                bounds.right - area.left, bounds.bottom - area.top);
        clipping.op(allowed, Region.Op.INTERSECT);
        Region shape = new Region();
        shape.setPath(path, clipping);
        result.op(shape, Region.Op.UNION);
    }

    private void drawPreview(Canvas canvas, Region allowed) {
        if (navigation == null || inner.isEmpty()) return;
        int saved = canvas.save();
        canvas.clipRect(inner);
        textPaint.setColor(0xffffffff);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        textPaint.setTextSize(14f * density);
        String label = "Habillage RoadDeck";
        float available = Math.max(1f, inner.width() - 24f * density);
        float measured = textPaint.measureText(label);
        if (measured > available) textPaint.setTextSize(textPaint.getTextSize() * available / measured);
        float width = Math.min(inner.width(), textPaint.measureText(label) + 24f * density);
        float height = Math.min(inner.height(), 36f * density);
        RectF badge = new RectF(inner.centerX() - width / 2f, inner.centerY() - height / 2f,
                inner.centerX() + width / 2f, inner.centerY() + height / 2f);
        Path badgePath = new Path();
        badgePath.addRoundRect(badge, 8f * density, 8f * density, Path.Direction.CW);
        Region badgeRegion = new Region();
        badgeRegion.setPath(badgePath, allowed);
        previewPixels = pixelArea(badgeRegion);
        paint.setColor(0xffab47e5);
        canvas.drawRoundRect(badge, 8f * density, 8f * density, paint);
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        canvas.drawText(label, badge.centerX(), badge.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint);
        canvas.restoreToCount(saved);
    }

    private static long pixelArea(Region region) {
        long area = 0;
        RegionIterator iterator = new RegionIterator(region);
        Rect box = new Rect();
        while (iterator.next(box)) area += (long) box.width() * box.height();
        return area;
    }
}
