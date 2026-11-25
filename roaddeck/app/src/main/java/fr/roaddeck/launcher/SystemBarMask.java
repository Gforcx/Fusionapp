package fr.roaddeck.launcher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Visually covers measured system-bar strips while a confirmed split pair is active.
 * This does not hide Android's system windows or enlarge the application tasks.
 * All calls and callbacks belong to the main thread. The caller owns eligibility
 * and the reveal interval; this class has no timer and never inspects other windows.
 */
final class SystemBarMask {
    private static final int COLOR = 0xff0c1020;
    private final Context context;
    private final WindowManager windowManager;
    private final Runnable onReveal;
    private final Runnable onChanged;
    private final List<Part> parts = new ArrayList<>();
    // Removal events arrive after removeViewImmediate. Keep these identities for
    // this manager's lifetime so they cannot invalidate the real split pair.
    private final Set<Integer> ownWindowIds = new HashSet<>();
    private List<SplitGeometry.Box> requested = Collections.emptyList();
    private List<SplitGeometry.Box> failedBounds;
    private boolean allowed, placementConfirmed, changing, destroyed;
    private String error = "";

    SystemBarMask(Context context, WindowManager windowManager,
            Runnable onReveal, Runnable onChanged) {
        this.context = context;
        this.windowManager = windowManager;
        this.onReveal = onReveal;
        this.onChanged = onChanged;
    }

    /** Reuses existing windows when their requested rectangles have not changed. */
    void update(List<SplitGeometry.Box> measuredBars, boolean allowed) {
        if (destroyed) return;
        if (!allowed || measuredBars == null || measuredBars.isEmpty()) {
            hide();
            return;
        }
        List<SplitGeometry.Box> bounds = normalized(measuredBars);
        if (bounds == null) {
            this.allowed = false;
            removeParts();
            requested = Collections.emptyList();
            failedBounds = null;
            setError("Zone de barre Android invalide.");
            return;
        }
        this.allowed = true;
        if (bounds.equals(failedBounds)) return;
        if (bounds.equals(requested) && !parts.isEmpty()) {
            validatePlacement();
            return;
        }

        changing = true;
        removeParts();
        requested = bounds;
        failedBounds = null;
        error = "";
        try {
            for (SplitGeometry.Box box : requested) {
                View view = new View(context);
                view.setBackgroundColor(COLOR);
                view.setAlpha(0f);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                view.setContentDescription("Afficher les commandes Android");
                view.setOnClickListener(clicked -> {
                    // Consume the whole gesture in this window. Removing it on
                    // ACTION_UP does not forward a click to a hidden system button.
                    hide();
                    if (onReveal != null) onReveal.run();
                });
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        box.width(), box.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = box.left;
                params.y = box.top;
                // PixelFormat.OPAQUE may make transparent view pixels opaque at
                // composition time. Hide the entire Surface, not only the View.
                params.alpha = 0f;
                params.windowAnimations = 0;
                params.setTitle("RoadDeck barre Android masquée");
                if (Build.VERSION.SDK_INT >= 28)
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
                Part part = new Part(box, view, params);
                parts.add(part);
                view.addOnLayoutChangeListener((changed, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> validatePlacement());
                windowManager.addView(view, params);
                captureWindowId(part);
                view.post(this::validatePlacement);
            }
        } catch (RuntimeException failure) {
            failedBounds = new ArrayList<>(requested);
            error = "Impossible d’afficher le masque des barres Android.";
            removeParts();
        } finally {
            changing = false;
        }
        validatePlacement();
        changed();
    }

    /** Removes all masks immediately; the next eligible update may try again. */
    void hide() {
        if (destroyed) return;
        boolean wasPresent = allowed || !parts.isEmpty() || placementConfirmed;
        allowed = false;
        removeParts();
        requested = Collections.emptyList();
        failedBounds = null;
        if (wasPresent) changed();
    }

    void destroy() {
        if (destroyed) return;
        allowed = false;
        destroyed = true;
        removeParts();
        requested = Collections.emptyList();
        failedBounds = null;
        ownWindowIds.clear();
    }

    boolean isOwnWindow(int windowId) {
        if (windowId < 0) return false;
        if (ownWindowIds.contains(windowId)) return true;
        // A window event can precede its first layout callback. Resolve only our
        // local views, without asking AccessibilityService for any window root.
        for (Part part : parts) captureWindowId(part);
        return ownWindowIds.contains(windowId);
    }

    boolean visible() {
        if (destroyed || !allowed || !placementConfirmed || parts.isEmpty()) return false;
        for (Part part : parts)
            if (!part.view.isAttachedToWindow() || part.view.getAlpha() < 0.99f
                    || part.params.alpha < 0.99f) return false;
        return true;
    }

    JSONObject snapshot() {
        JSONObject out = new JSONObject();
        try {
            JSONArray actual = new JSONArray();
            if (placementConfirmed)
                for (Part part : parts) actual.put(boxJson(part.placed));
            JSONArray desired = new JSONArray();
            for (SplitGeometry.Box box : requested) desired.put(boxJson(box));
            out.put("barMaskVisible", visible());
            out.put("barMaskPlacementConfirmed", placementConfirmed);
            out.put("barMaskBounds", actual);
            out.put("barMaskRequestedBounds", desired);
            out.put("barMaskError", error);
        } catch (JSONException impossible) { /* All values are finite primitives. */ }
        return out;
    }

    private List<SplitGeometry.Box> normalized(List<SplitGeometry.Box> measuredBars) {
        Point screen = new Point();
        try { windowManager.getDefaultDisplay().getRealSize(screen); }
        catch (RuntimeException unavailable) { return null; }
        List<SplitGeometry.Box> result = new ArrayList<>();
        for (SplitGeometry.Box box : measuredBars) {
            if (!SplitGeometry.inside(box, screen.x, screen.y)) return null;
            if (!result.contains(box)) result.add(new SplitGeometry.Box(box.left, box.top, box.right, box.bottom));
        }
        result.sort(Comparator.comparingInt((SplitGeometry.Box b) -> b.top)
                .thenComparingInt(b -> b.left).thenComparingInt(b -> b.bottom).thenComparingInt(b -> b.right));
        return result;
    }

    private void validatePlacement() {
        if (destroyed || changing || !allowed || parts.isEmpty()) return;
        boolean allLaidOut = parts.size() == requested.size();
        for (Part part : parts) {
            captureWindowId(part);
            View view = part.view;
            if (!view.isAttachedToWindow() || view.getWidth() <= 0 || view.getHeight() <= 0) {
                allLaidOut = false;
                continue;
            }
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            part.placed = new SplitGeometry.Box(location[0], location[1],
                    location[0] + view.getWidth(), location[1] + view.getHeight());
            if (!part.requested.equals(part.placed)) {
                failedBounds = new ArrayList<>(requested);
                error = "Le masque des barres Android n’a pas pu être placé correctement.";
                removeParts();
                changed();
                return;
            }
        }
        if (!allLaidOut) {
            if (placementConfirmed) {
                failedBounds = new ArrayList<>(requested);
                error = "La position du masque des barres Android n’est plus confirmée.";
                removeParts();
                changed();
            }
            return;
        }
        if (!placementConfirmed) {
            placementConfirmed = true;
            try {
                for (Part part : parts) {
                    part.view.setAlpha(1f);
                    part.params.alpha = 1f;
                    windowManager.updateViewLayout(part.view, part.params);
                }
            } catch (RuntimeException failure) {
                failedBounds = new ArrayList<>(requested);
                error = "Impossible d’afficher le masque des barres Android.";
                removeParts();
            }
            changed();
        }
    }

    private void captureWindowId(Part part) {
        if (part.windowId >= 0 || !part.view.isAttachedToWindow()) return;
        try {
            AccessibilityNodeInfo ownNode = part.view.createAccessibilityNodeInfo();
            if (ownNode != null) {
                try {
                    part.windowId = ownNode.getWindowId();
                    if (part.windowId >= 0) ownWindowIds.add(part.windowId);
                } finally { ownNode.recycle(); }
            }
        } catch (RuntimeException ignored) { }
    }

    private void removeParts() {
        boolean wasChanging = changing;
        changing = true;
        placementConfirmed = false;
        try {
            for (Part part : parts) {
                captureWindowId(part);
                part.view.setAlpha(0f);
                // If removal races with a system detach, the view also remains
                // non-visible instead of leaving an invisible touch interceptor.
                part.view.setVisibility(View.GONE);
            }
            for (Part part : parts) {
                try { windowManager.removeViewImmediate(part.view); }
                catch (RuntimeException ignored) { }
            }
            parts.clear();
        } finally {
            changing = wasChanging;
        }
    }

    private void setError(String value) {
        boolean different = !error.equals(value);
        error = value;
        if (different) changed();
    }

    private void changed() {
        if (!changing && !destroyed && onChanged != null) onChanged.run();
    }

    private static JSONObject boxJson(SplitGeometry.Box box) throws JSONException {
        return new JSONObject().put("left", box.left).put("top", box.top)
                .put("right", box.right).put("bottom", box.bottom);
    }

    private static final class Part {
        final SplitGeometry.Box requested;
        final View view;
        final WindowManager.LayoutParams params;
        SplitGeometry.Box placed;
        int windowId = -1;
        Part(SplitGeometry.Box requested, View view, WindowManager.LayoutParams params) {
            this.requested = requested;
            this.view = view;
            this.params = params;
        }
    }
}
