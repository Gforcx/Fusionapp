package fr.roaddeck.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * User-started split-screen controller. It uses only public accessibility APIs.
 * The service reads window package names and rectangles, never text or screenshots.
 *
 * Required service XML: canRetrieveWindowContent=true,
 * accessibilityFlags=flagRetrieveInteractiveWindows,
 * accessibilityEventTypes=typeWindowsChanged|typeWindowStateChanged,
 * accessibilityFeedbackType=feedbackGeneric. No gesture capability is required.
 */
public final class SplitScreenService extends AccessibilityService {
    private static final String WAZE = "com.waze";
    private static final String PANEL = "fr.roaddeck.launcher.SidePanelActivity";
    private static final String PANEL_SETTINGS = "fr.roaddeck.launcher.PanelSettingsActivity";
    private static final String ENABLE_MESSAGE = "Activez RoadDeck dans les services d’accessibilité Android.";
    private static volatile SplitScreenService instance;
    private static volatile String lastState = "{}";

    private enum Phase {
        IDLE, WAIT_WAZE, WAIT_CLEAR_EXISTING, WAIT_DOCK_WAZE,
        WAIT_CLEAR_FOR_SWAP, WAIT_PANEL_FULL, WAIT_DOCK_PANEL,
        WAIT_PAIR, RUNNING, CLOSE_WAIT_SPLIT, CLOSE_WAIT_WAZE, FAILED
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Phase phase = Phase.IDLE;
    private boolean panelVisible, panelMultiWindow, active, swapUsed;
    private boolean scanQueued;
    private long scanAt, deadline, overallDeadline, lastGoodPairAt;
    private int stableCount, stableSignature;
    private String message = "Prêt à ouvrir Waze à gauche et RoadDeck à droite.";
    private Observation latest = new Observation();
    private WindowManager windowManager;
    private View seam;
    private SplitGeometry.Box seamBounds;

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            scanQueued = false;
            if (instance != SplitScreenService.this) return;
            drive();
            if (pending()) enqueue(180);
            else if (phase == Phase.RUNNING
                    && (active || SystemClock.uptimeMillis() - lastGoodPairAt < 1500)) enqueue(900);
        }
    };

    public static boolean isConnected() { return instance != null; }

    /** Must be called for an explicit request from a visible activity. */
    public static boolean begin(Context context) {
        SplitScreenService service = instance;
        if (service == null) {
            detachedMessage(ENABLE_MESSAGE);
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper() || !visibleActivity(context)) {
            service.message = "Ouvrez RoadDeck et appuyez sur Ouvrir Waze.";
            service.publish();
            return false;
        }
        return service.startPair((Activity) context);
    }

    /** User-requested close only; service disconnects never launch or close other apps. */
    public static void closePair(Context context) {
        SplitScreenService service = instance;
        if (service == null) {
            detachedMessage("Le service d’accessibilité est arrêté. Réactivez-le pour fermer le partage.");
            return;
        }
        if (!visibleActivity(context)) {
            service.message = "Fermez le partage depuis le panneau RoadDeck.";
            service.publish();
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) service.startClose();
        else service.handler.post(service::startClose);
    }

    /** Lifecycle hints only: getWindows() remains the authority for geometry. */
    public static void onPanelState(boolean visible, boolean multiWindow) {
        SplitScreenService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            if (instance != service) return;
            service.panelVisible = visible;
            if (visible) service.panelMultiWindow = multiWindow;
            // Do not close or undock on onStop: Settings and configuration changes also stop it.
            if (service.pending() || service.phase == Phase.RUNNING) service.enqueue(60);
        });
    }

    public static JSONObject snapshot(Context context) {
        JSONObject out;
        try { out = new JSONObject(lastState); }
        catch (JSONException ignored) { out = new JSONObject(); }
        try {
            boolean connected = isConnected();
            out.put("mode", "split");
            out.put("accessibilityGranted", connected || accessibilityEnabled(context));
            out.put("serviceConnected", connected);
            if (!connected) {
                out.put("active", false);
                out.put("requested", false);
                out.put("message", accessibilityEnabled(context)
                        ? "Service RoadDeck activé mais pas encore connecté. Revenez dans RoadDeck."
                        : ENABLE_MESSAGE);
            }
            if (!out.has("active")) out.put("active", false);
            if (!out.has("requested")) out.put("requested", false);
        } catch (JSONException ignored) { }
        return out;
    }

    private static boolean visibleActivity(Context context) {
        if (!(context instanceof Activity)) return false;
        Activity activity = (Activity) context;
        return !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow() != null
                && activity.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }

    private static boolean accessibilityEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName wanted = new ComponentName(context, SplitScreenService.class);
            for (String item : enabled.split(":"))
                if (wanted.equals(ComponentName.unflattenFromString(item))) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static void detachedMessage(String text) {
        try {
            lastState = new JSONObject().put("mode", "split").put("active", false)
                    .put("requested", false).put("message", text).put("state", "IDLE").toString();
        } catch (JSONException ignored) { }
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        // Fresh process/service means an idle controller. No stored auto-start or auto-toggles.
        phase = Phase.IDLE;
        active = false;
        publish();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || (!pending() && phase != Phase.RUNNING)) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // The old gap is no longer authoritative as soon as the windows change.
            // Hide immediately; a new scan must revalidate both apps before restoring it.
            if (seam != null) {
                seam.setAlpha(0f);
                publish();
            }
            enqueue(60);
        }
    }

    @Override public void onInterrupt() {
        cancelScans();
        removeSeam();
        active = false;
        phase = Phase.IDLE;
        message = "Le contrôle des fenêtres a été interrompu. Ouvrez Waze depuis RoadDeck pour reprendre.";
        publish();
    }

    @Override public void onDestroy() {
        cancelScans();
        removeSeam();
        if (instance == this) instance = null;
        active = false;
        phase = Phase.IDLE;
        message = "Service RoadDeck déconnecté.";
        publish();
        super.onDestroy();
    }

    private boolean startPair(Activity activity) {
        if (pending()) return true;
        latest = observe();
        if (confirmedPair(latest)) {
            phase = Phase.RUNNING;
            active = true;
            lastGoodPairAt = SystemClock.uptimeMillis();
            message = "Waze à gauche, RoadDeck à droite.";
            updateSeam(latest);
            publish();
            enqueue(900);
            return true;
        }
        if (latest.screenWidth < latest.screenHeight) {
            fail("Placez l’écran en paysage pour afficher Waze à gauche.");
            return false;
        }
        Intent intent = wazeIntent();
        if (intent == null) {
            fail("Waze n’est pas installé sur cet appareil.");
            return false;
        }
        cancelScans();
        removeSeam();
        active = false;
        swapUsed = false;
        panelVisible = panelMultiWindow = false;
        closePanelTasks();
        overallDeadline = SystemClock.uptimeMillis() + 55000;
        enter(Phase.WAIT_WAZE, 12000, "Ouverture de Waze…");
        try {
            // The first launch uses the visible activity that received the user's request.
            activity.startActivity(intent);
        } catch (RuntimeException error) {
            fail("Impossible d’ouvrir Waze : " + error.getClass().getSimpleName());
            return false;
        }
        enqueue(180);
        return true;
    }

    private void startClose() {
        cancelScans();
        removeSeam();
        active = false;
        latest = observe();
        overallDeadline = SystemClock.uptimeMillis() + 15000;
        if (latest.divider) {
            SplitGeometry.Pair pair = measuredPair(latest);
            if (pair.arrangement == SplitGeometry.NONE || !panelMultiWindow) {
                fail("Le partage actuel n’est pas la paire Waze/RoadDeck. Aucun autre écran n’a été fermé.");
                return;
            }
            enter(Phase.CLOSE_WAIT_SPLIT, 7000, "Fermeture du partage…");
            if (!toggleKnownSplit()) return;
        } else {
            finishCloseAndShowWaze();
        }
        enqueue(180);
    }

    private void drive() {
        latest = observe();
        long now = SystemClock.uptimeMillis();
        if (pending() && latest.screenWidth > 0 && latest.screenWidth < latest.screenHeight) {
            fail("L’écran doit rester en paysage pour afficher Waze à gauche.");
            return;
        }
        if (pending() && (now > deadline || now > overallDeadline)) {
            String error;
            if (phase == Phase.WAIT_WAZE)
                error = "Waze ne s’est pas affiché au premier plan. Revenez dans RoadDeck pour réessayer.";
            else if (phase == Phase.CLOSE_WAIT_SPLIT || phase == Phase.CLOSE_WAIT_WAZE)
                error = "L’autoradio n’a pas confirmé le retour de Waze en plein écran.";
            else error = "L’autoradio n’a pas confirmé Waze à gauche en écran partagé. Aucun panneau ne recouvre sa carte.";
            fail(error);
            return;
        }
        switch (phase) {
            case WAIT_WAZE:
                if (stable(latest.wazeFocused && latest.waze != null, latest)) {
                    if (latest.divider) {
                        enter(Phase.WAIT_CLEAR_EXISTING, 6500, "Préparation de l’écran partagé…");
                        toggleKnownSplit();
                    } else if (SplitGeometry.fullWidth(latest.waze, latest.screenWidth, latest.screenHeight)) {
                        enter(Phase.WAIT_DOCK_WAZE, 7000, "Redimensionnement de Waze…");
                        toggleForFocused(WAZE);
                    }
                }
                break;
            case WAIT_CLEAR_EXISTING:
                if (stable(!latest.divider && latest.wazeFocused
                        && SplitGeometry.fullWidth(latest.waze, latest.screenWidth, latest.screenHeight), latest)) {
                    enter(Phase.WAIT_DOCK_WAZE, 7000, "Redimensionnement de Waze…");
                    toggleForFocused(WAZE);
                }
                break;
            case WAIT_DOCK_WAZE:
                if (stable(latest.divider
                        && SplitGeometry.reduced(latest.waze, latest.screenWidth, latest.screenHeight), latest)) {
                    if (SplitGeometry.onLeft(latest.waze, latest.screenWidth)) {
                        enter(Phase.WAIT_PAIR, 8000, "Ouverture du panneau RoadDeck à droite…");
                        launchPanel();
                    } else if (!swapUsed) {
                        swapUsed = true;
                        enter(Phase.WAIT_CLEAR_FOR_SWAP, 6500, "Placement de Waze sur le côté gauche…");
                        toggleKnownSplit();
                    } else fail("L’autoradio place Waze du mauvais côté. Aucun panneau ne masque sa carte.");
                }
                break;
            case WAIT_CLEAR_FOR_SWAP:
                if (stable(!latest.divider, latest)) {
                    enter(Phase.WAIT_PANEL_FULL, 6500, "Préparation du panneau droit…");
                    launchPanel();
                }
                break;
            case WAIT_PANEL_FULL:
                if (stable(!latest.divider && panelVisible && latest.panelFocused
                        && SplitGeometry.fullWidth(latest.panel, latest.screenWidth, latest.screenHeight), latest)) {
                    enter(Phase.WAIT_DOCK_PANEL, 6500, "Positionnement du panneau droit…");
                    toggleForFocused(getPackageName());
                }
                break;
            case WAIT_DOCK_PANEL:
                if (stable(latest.divider && panelVisible
                        && SplitGeometry.reduced(latest.panel, latest.screenWidth, latest.screenHeight), latest)) {
                    if (SplitGeometry.onLeft(latest.panel, latest.screenWidth)) {
                        fail("L’autoradio n’a pas conservé le panneau à droite. Aucune partie de Waze n’est masquée.");
                    } else {
                        enter(Phase.WAIT_PAIR, 8000, "Ouverture de Waze à gauche…");
                        launchWaze();
                    }
                }
                break;
            case WAIT_PAIR:
                if (stable(confirmedPair(latest), latest)) {
                    phase = Phase.RUNNING;
                    active = true;
                    lastGoodPairAt = now;
                    message = "Waze à gauche, RoadDeck à droite.";
                    updateSeam(latest);
                }
                break;
            case RUNNING:
                boolean good = confirmedPair(latest);
                active = good;
                if (good) {
                    lastGoodPairAt = now;
                    message = "Waze à gauche, RoadDeck à droite.";
                    updateSeam(latest);
                } else {
                    // Settings, the keyboard, Home, or another app must never get a stale mask.
                    removeSeam();
                    message = "La paire Waze/RoadDeck n’est pas entièrement visible.";
                }
                break;
            case CLOSE_WAIT_SPLIT:
                if (stable(!latest.divider, latest)) finishCloseAndShowWaze();
                break;
            case CLOSE_WAIT_WAZE:
                if (stable(!latest.divider && latest.wazeFocused
                        && SplitGeometry.fullWidth(latest.waze, latest.screenWidth, latest.screenHeight), latest)) {
                    phase = Phase.IDLE;
                    message = "Panneau fermé. Waze est affiché en plein écran.";
                }
                break;
            default:
                removeSeam();
                break;
        }
        publish();
    }

    private boolean confirmedPair(Observation observation) {
        return observation.divider && panelVisible && panelMultiWindow
                && measuredPair(observation).arrangement == SplitGeometry.WAZE_LEFT;
    }

    private SplitGeometry.Pair measuredPair(Observation observation) {
        return SplitGeometry.pair(observation.waze, observation.panel, observation.screenWidth,
                observation.screenHeight, maximumGap());
    }

    private int maximumGap() {
        return Math.max(1, Math.round(48 * getResources().getDisplayMetrics().density));
    }

    private boolean stable(boolean condition, Observation observation) {
        if (!condition) { stableCount = 0; return false; }
        int signature = observation.signature();
        if (signature == stableSignature) stableCount++;
        else { stableSignature = signature; stableCount = 1; }
        return stableCount >= 2;
    }

    private void enter(Phase next, long timeoutMs, String text) {
        phase = next;
        stableCount = 0;
        stableSignature = 0;
        deadline = SystemClock.uptimeMillis() + timeoutMs;
        message = text;
        publish();
    }

    private boolean pending() {
        return phase != Phase.IDLE && phase != Phase.RUNNING && phase != Phase.FAILED;
    }

    private void enqueue(long delayMs) {
        long when = SystemClock.uptimeMillis() + delayMs;
        if (scanQueued && scanAt <= when) return;
        handler.removeCallbacks(scan);
        scanQueued = true;
        scanAt = when;
        handler.postDelayed(scan, delayMs);
    }

    private void cancelScans() {
        handler.removeCallbacks(scan);
        scanQueued = false;
    }

    private Intent wazeIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(WAZE);
        if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void launchWaze() {
        Intent intent = wazeIntent();
        if (intent == null) { fail("Waze n’est plus disponible sur cet appareil."); return; }
        try { startActivity(intent); }
        catch (RuntimeException error) { fail("Impossible d’ouvrir Waze : " + error.getClass().getSimpleName()); }
    }

    private void launchPanel() {
        try {
            // No LAUNCH_ADJACENT: after docking, Recents may own the secondary focus;
            // asking for its opposite side would replace Waze in the primary stack.
            startActivity(new Intent().setClassName(this, PANEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException error) {
            fail("Impossible d’ouvrir le panneau RoadDeck : " + error.getClass().getSimpleName());
        }
    }

    private void finishCloseAndShowWaze() {
        closePanelTasks();
        panelVisible = panelMultiWindow = false;
        enter(Phase.CLOSE_WAIT_WAZE, 7000, "Retour à Waze en plein écran…");
        launchWaze();
    }

    private void closePanelTasks() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return;
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                ComponentName component = info.baseIntent == null ? null : info.baseIntent.getComponent();
                if (component != null && getPackageName().equals(component.getPackageName())
                        && (PANEL.equals(component.getClassName()) || PANEL_SETTINGS.equals(component.getClassName())))
                    task.finishAndRemoveTask();
            }
        } catch (RuntimeException ignored) {
            // Window verification will catch a surviving stale task; never remove unknown tasks.
        }
    }

    private boolean toggleForFocused(String packageName) {
        Observation before = observe();
        boolean expectedFocused = WAZE.equals(packageName) ? before.wazeFocused
                : panelVisible && before.panelFocused;
        if (before.divider || !expectedFocused) {
            fail("La fenêtre a changé pendant la préparation. Revenez dans RoadDeck pour recommencer.");
            return false;
        }
        return sendToggle();
    }

    private boolean toggleKnownSplit() {
        Observation before = observe();
        if (!before.divider || (before.waze == null && before.panel == null)) {
            fail("L’écran partagé a changé. Aucune bascule supplémentaire n’a été envoyée.");
            return false;
        }
        return sendToggle();
    }

    private boolean sendToggle() {
        try {
            if (performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) return true;
        } catch (RuntimeException ignored) { }
        fail("L’autoradio refuse la commande d’écran partagé.");
        return false;
    }

    private void fail(String text) {
        cancelScans();
        removeSeam();
        phase = Phase.FAILED;
        active = false;
        message = text;
        publish();
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private Observation observe() {
        Observation observation = new Observation();
        try {
            Point size = new Point();
            if (windowManager != null) windowManager.getDefaultDisplay().getRealSize(size);
            observation.screenWidth = size.x;
            observation.screenHeight = size.y;
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = null;
                try {
                    if (window.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                        observation.divider = true;
                        continue;
                    }
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                            || window.isInPictureInPictureMode()) continue;
                    Rect rect = new Rect();
                    window.getBoundsInScreen(rect);
                    SplitGeometry.Box box = new SplitGeometry.Box(rect.left, rect.top, rect.right, rect.bottom);
                    if (!SplitGeometry.inside(box, size.x, size.y)) continue;
                    root = window.getRoot();
                    CharSequence packageName = root == null ? null : root.getPackageName();
                    if (packageName == null) continue;
                    if (WAZE.contentEquals(packageName)
                            && (observation.waze == null || box.area() > observation.waze.area())) {
                        observation.waze = box;
                        observation.wazeFocused = window.isFocused();
                    } else if (getPackageName().contentEquals(packageName)
                            && (observation.panel == null || box.area() > observation.panel.area())) {
                        observation.panel = box;
                        observation.panelFocused = window.isFocused();
                    }
                } catch (RuntimeException ignored) {
                    // Windows can disappear between getWindows() and getRoot(). Retry on the next event.
                } finally {
                    if (root != null) root.recycle();
                    window.recycle();
                }
            }
        } catch (RuntimeException ignored) {
            // An unreadable layout never permits a mask or a successful state.
        }
        return observation;
    }

    private void updateSeam(Observation observation) {
        SplitGeometry.Box gap = measuredPair(observation).gap;
        if (!confirmedPair(observation) || gap == null || gap.width() > maximumGap()) {
            removeSeam();
            return;
        }
        if (gap.equals(seamBounds) && seam != null) {
            validateSeamPosition(seam);
            return;
        }
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(gap.width(), gap.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = gap.left;
            params.y = gap.top;
            params.setTitle("RoadDeck raccord du panneau");
            seamBounds = gap;
            if (seam == null) {
                seam = new View(this);
                seam.setBackgroundColor(0xff0b1020);
                seam.setAlpha(0f);
                seam.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                seam.addOnLayoutChangeListener((view, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> validateSeamPosition(view));
                windowManager.addView(seam, params);
            } else {
                seam.setAlpha(0f);
                windowManager.updateViewLayout(seam, params);
            }
            View candidate = seam;
            candidate.post(() -> validateSeamPosition(candidate));
        } catch (RuntimeException ignored) {
            removeSeam();
            // Failure to conceal the divider never blocks the functioning split-screen pair.
        }
    }

    private void validateSeamPosition(View candidate) {
        if (candidate != seam || seamBounds == null || !candidate.isAttachedToWindow()) return;
        int[] location = new int[2];
        candidate.getLocationOnScreen(location);
        SplitGeometry.Box actual = new SplitGeometry.Box(location[0], location[1],
                location[0] + candidate.getWidth(), location[1] + candidate.getHeight());
        if (actual.equals(seamBounds)) candidate.setAlpha(1f);
        else if (candidate.getWidth() > 0 && candidate.getHeight() > 0) removeSeam();
        publish();
    }

    private void removeSeam() {
        if (seam != null) {
            try { if (windowManager != null) windowManager.removeViewImmediate(seam); }
            catch (RuntimeException ignored) { }
        }
        seam = null;
        seamBounds = null;
    }

    private void publish() {
        try {
            JSONObject out = new JSONObject().put("mode", "split").put("active", active)
                    .put("requested", pending()).put("accessibilityGranted", instance == this)
                    .put("message", message).put("state", phase.name())
                    .put("dividerCovered", seam != null && seam.getAlpha() > 0.99f);
            if (latest.waze != null) out.put("wazeBounds", boundsJson(latest.waze));
            if (latest.panel != null) out.put("panelBounds", boundsJson(latest.panel));
            lastState = out.toString();
        } catch (JSONException ignored) { }
    }

    private JSONObject boundsJson(SplitGeometry.Box box) throws JSONException {
        return new JSONObject().put("left", box.left).put("top", box.top)
                .put("right", box.right).put("bottom", box.bottom);
    }

    private static final class Observation {
        SplitGeometry.Box waze, panel;
        boolean divider, wazeFocused, panelFocused;
        int screenWidth, screenHeight;
        int signature() {
            int value = waze == null ? 0 : waze.hashCode();
            value = 31 * value + (panel == null ? 0 : panel.hashCode());
            return 31 * value + (divider ? 1 : 0);
        }
    }
}
