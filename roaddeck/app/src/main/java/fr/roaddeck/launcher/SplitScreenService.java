package fr.roaddeck.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Visible-launch split-screen controller. It uses only public accessibility APIs.
 * The service reads window package names and rectangles, never text or screenshots.
 *
 * Required service XML: canRetrieveWindowContent=true,
 * accessibilityFlags=flagRetrieveInteractiveWindows,
 * accessibilityEventTypes=typeWindowsChanged|typeWindowStateChanged,
 * accessibilityFeedbackType=feedbackGeneric. No gesture capability is required.
 */
public final class SplitScreenService extends AccessibilityService {
    private static final String TAG = "RoadDeckSplit";
    private static final String WAZE = "com.waze";
    private static final String PANEL = "fr.roaddeck.launcher.SidePanelActivity";
    private static final String PANEL_SETTINGS = "fr.roaddeck.launcher.PanelSettingsActivity";
    private static final String ENABLE_MESSAGE = "Activez RoadDeck dans les services d’accessibilité Android.";
    private static final String SPLIT_UNAVAILABLE_MESSAGE = "La commande d’écran partagé n’est pas disponible actuellement pour RoadDeck.";
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
    private long phaseStartedAt, requestStartedAt, failureElapsedMs, lastWaitLogAt;
    private long lastRootMs, maximumRootMs, lastToggleMs;
    private int rootQueryCount, phaseSamples;
    private int stableCount, stableSignature;
    private boolean waitCondition;
    private String waitReason = "idle";
    private String message = "Prêt à ouvrir Waze à gauche et RoadDeck à droite.";
    private String splitActionAvailability = "unknown";
    private String failureStage = "";
    private Observation latest = new Observation();
    private WindowManager windowManager;
    private SplitFrameView seam;
    private SplitGeometry.Box seamBounds;
    private boolean frameEnabled = true, seamFramed, decorationAuthoritative;
    private boolean seamHasUnobscuredGap;
    private int seamWindowId = -1, decorationSignature, failedFrameSignature;
    private boolean framePlacementFailed;
    private int frameLeft = 12, frameTop = 12, frameRight = 12, frameBottom = 12;
    private int barLeft, barTop, barRight, barBottom, insetScreenWidth, insetScreenHeight;
    private boolean barInsetsObserved;
    private long previewUntil;
    private SplitGeometry.Box lastRequestedFrameBounds, lastPlacedFrameBounds;
    private SystemBarMask systemBarMask;
    private boolean coverSystemBars = true, panelReady;
    private long barsRevealedUntil;
    private StartupLoadingView startupView;
    private SplitGeometry.Box startupBounds;
    private final Set<Integer> startupWindowIds = new HashSet<>();
    private boolean startupSession, startupPlacementConfirmed, startupDrawn, startupReleaseQueued, startupSuspended;
    private long startupGeneration, startupReadyDeadline;
    private String startupError = "";
    private WeakReference<Activity> startupActivity;
    private Runnable pendingFirstLaunch;

    // Window identities are cached separately from geometry. Rectangles always come
    // from the current getWindows() result; no old rectangle is ever reused.
    private final SparseArray<String> windowPackages = new SparseArray<>();
    private final SparseArray<Long> ownerRequests = new SparseArray<>();
    private final SparseArray<Long> ownerRetryAt = new SparseArray<>();
    private final Set<Integer> liveWindowIds = new HashSet<>();
    private volatile long ownerGeneration;
    private long nextOwnerRequest;
    private ExecutorService ownerReader;

    private final Runnable scan = new Runnable() {
        @Override public void run() {
            scanQueued = false;
            if (instance != SplitScreenService.this) return;
            drive();
            if (pending()) enqueue(180);
            else if (phase == Phase.RUNNING
                    && (startupSession || active || SystemClock.uptimeMillis() - lastGoodPairAt < 1500)) enqueue(900);
        }
    };

    public static boolean isConnected() { return instance != null; }

    /** Settings affect decoration only; they never resize or reopen either task. */
    public static void settingsChanged() {
        SplitScreenService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            if (instance != service) return;
            service.readFrameSettings();
            service.framePlacementFailed = false;
            service.hideDecoration();
            if (service.phase == Phase.RUNNING) service.enqueue(0);
            service.publish();
        });
    }

    private void readFrameSettings() {
        Config config = new Config(this);
        frameEnabled = config.bool("splitFrame", true);
        coverSystemBars = config.bool("splitCoverBars", true);
        frameLeft = frameMargin(config, "frameLeft");
        frameTop = frameMargin(config, "frameTop");
        frameRight = frameMargin(config, "frameRight");
        frameBottom = frameMargin(config, "frameBottom");
    }

    private static int frameMargin(Config config, String key) {
        return Math.max(0, Math.min(40, config.integer(key, 12)));
    }

    /** Explicit, temporary proof of the left overlay. No background auto-preview. */
    public static void previewFrame(Context context) {
        SplitScreenService service = instance;
        if (service == null || !visibleActivity(context)) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            service.handler.post(() -> previewFrame(context));
            return;
        }
        service.readFrameSettings();
        service.readBarInsets(((Activity) context).getWindow().getDecorView().getRootWindowInsets());
        service.latest = service.observe();
        if (!service.frameEnabled || service.phase != Phase.RUNNING
                || !service.confirmedPair(service.latest)) {
            Toast.makeText(context, "Ouvrez Waze à gauche et activez l’habillage pour afficher le repère.",
                    Toast.LENGTH_LONG).show();
            service.publish();
            return;
        }
        service.previewUntil = SystemClock.uptimeMillis() + 5000;
        final long requestedUntil = service.previewUntil;
        service.updateSeam(service.latest);
        service.publish();
        service.handler.postDelayed(() -> {
            if (instance != service || service.previewUntil != requestedUntil) return;
            service.previewUntil = 0;
            if (service.phase == Phase.RUNNING) service.enqueue(0);
            service.publish();
        }, 5000);
    }

    /** Called by a visible launch, including the user's enabled automatic startup. */
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
        service.readBarInsets(((Activity) context).getWindow().getDecorView().getRootWindowInsets());
        service.refreshSplitActionAvailability();
        return service.startPair((Activity) context);
    }

    /** A rendered right panel, not a timer, permits the startup curtain to leave. */
    public static void onPanelReady(boolean ready) {
        SplitScreenService service = instance;
        if (service == null) return;
        service.handler.post(() -> {
            if (instance != service) return;
            service.panelReady = ready;
            if (service.pending() || service.phase == Phase.RUNNING) service.enqueue(0);
        });
    }

    public static void revealSystemBars(Context context) {
        SplitScreenService service = instance;
        if (service == null || !visibleActivity(context)) return;
        service.handler.post(service::revealBars);
    }

    public static void cancelStartup(Context context) {
        SplitScreenService service = instance;
        if (service == null) return;
        service.handler.post(service::cancelStartupInternal);
    }

    private void revealBars() {
        barsRevealedUntil = SystemClock.uptimeMillis() + 8000;
        if (systemBarMask != null) systemBarMask.hide();
        final long until = barsRevealedUntil;
        handler.postDelayed(() -> {
            if (instance == this && barsRevealedUntil == until) enqueue(0);
        }, 8000);
        publish();
    }

    private void cancelStartupInternal() {
        if (!startupSession) return;
        cancelScans();
        removeStartup();
        removeSeam();
        if (systemBarMask != null) systemBarMask.hide();
        phase = Phase.IDLE;
        active = false;
        message = "Préparation annulée. Vous pouvez relancer Waze depuis RoadDeck.";
        publish();
        showRecovery();
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
            out.put("frameEnabled", new Config(context).bool("splitFrame", true));
            if (!connected) {
                out.put("startupVisible", false);
                out.put("barMaskVisible", false);
                out.put("frameVisible", false);
                out.put("frameStatus", "Habillage arrêté : service d’accessibilité non connecté.");
                out.put("framePreview", false);
                out.put("framePaintedPixels", 0);
                out.put("frameNavigationPaintedPixels", 0);
                out.put("splitActionAvailability", "unknown");
                out.put("active", false);
                out.put("requested", false);
                out.put("message", accessibilityEnabled(context)
                        ? "Autorisation accordée, mais service RoadDeck non connecté. Consultez le diagnostic dans Informations."
                        : ENABLE_MESSAGE);
            }
            if (!out.has("active")) out.put("active", false);
            if (!out.has("requested")) out.put("requested", false);
            if (!out.has("splitActionAvailability")) out.put("splitActionAvailability", "unknown");
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
        systemBarMask = new SystemBarMask(this, windowManager, this::revealBars, this::publish);
        readFrameSettings();
        clearWindowOwners();
        if (ownerReader == null || ownerReader.isShutdown())
            ownerReader = Executors.newSingleThreadExecutor(task -> new Thread(task, "RoadDeckWindowOwner"));
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        // Binding alone never starts another app. MainActivity owns enabled auto-start.
        phase = Phase.IDLE;
        active = false;
        refreshSplitActionAvailability();
        publish();
    }

    @Override public void onSystemActionsChanged() {
        if (Build.VERSION.SDK_INT >= 30) {
            super.onSystemActionsChanged();
            refreshSplitActionAvailability();
            publish();
        }
    }

    /** API 29 has no public action-list query; preserve its verified launch path. */
    private void refreshSplitActionAvailability() {
        splitActionAvailability = "unknown";
        if (Build.VERSION.SDK_INT < 30 || instance != this) return;
        try {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = getSystemActions();
            if (actions == null) {
                Log.w(TAG, "Split action availability unknown: system action list is null");
                return;
            }
            boolean unreadableEntry = false;
            for (AccessibilityNodeInfo.AccessibilityAction action : actions) {
                if (action == null) {
                    unreadableEntry = true;
                    continue;
                }
                if (action.getId() == GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) {
                    splitActionAvailability = "available";
                    return;
                }
            }
            if (!unreadableEntry) splitActionAvailability = "unavailable";
        } catch (RuntimeException error) {
            // An unavailable query does not prove the action itself is absent.
            Log.w(TAG, "Cannot determine split action availability", error);
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int windowId = event.getWindowId();
        captureDecorationWindowId();
        captureStartupWindowId();
        // This passive window never changes app geometry. Reacting to its own
        // add/layout notifications would hide and restore it repeatedly.
        if (isOwnOverlayWindow(windowId)) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && windowId >= 0 && event.getPackageName() != null) {
            // Keep ownership events even while idle, before the user starts a pair.
            windowPackages.put(windowId, event.getPackageName().toString());
            ownerRetryAt.remove(windowId);
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && Build.VERSION.SDK_INT >= 28
                && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0) {
            forgetWindow(windowId);
        }
        if (!pending() && phase != Phase.RUNNING) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // The old gap is no longer authoritative as soon as the windows change.
            // Hide immediately; a new scan must revalidate both apps before restoring it.
            hideDecoration();
            if (systemBarMask != null) systemBarMask.hide();
            publish();
            enqueue(60);
        }
    }

    @Override public void onInterrupt() {
        cancelScans();
        removeStartup();
        if (systemBarMask != null) systemBarMask.hide();
        removeSeam();
        active = false;
        phase = Phase.IDLE;
        message = "Le contrôle des fenêtres a été interrompu. Ouvrez Waze depuis RoadDeck pour reprendre.";
        publish();
    }

    @Override public void onDestroy() {
        cancelScans();
        removeStartup();
        if (systemBarMask != null) systemBarMask.destroy();
        removeSeam();
        clearWindowOwners();
        // Queued copies still run their finally/recycle path; the generation
        // check skips all queries belonging to this disconnected service.
        if (ownerReader != null) ownerReader.shutdown();
        if (instance == this) instance = null;
        splitActionAvailability = "unknown";
        active = false;
        phase = Phase.IDLE;
        message = "Service RoadDeck déconnecté.";
        publish();
        super.onDestroy();
    }

    private boolean startPair(Activity activity) {
        previewUntil = 0;
        if (pending() && !"unavailable".equals(splitActionAvailability)) return true;
        failureStage = "";
        failureElapsedMs = 0;
        requestStartedAt = SystemClock.uptimeMillis();
        phaseStartedAt = requestStartedAt;
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
        if ("unavailable".equals(splitActionAvailability)) {
            fail(SPLIT_UNAVAILABLE_MESSAGE);
            return false;
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
        removeStartup();
        if (systemBarMask != null) systemBarMask.hide();
        removeSeam();
        active = false;
        swapUsed = false;
        panelVisible = panelMultiWindow = panelReady = false;
        closePanelTasks();
        overallDeadline = SystemClock.uptimeMillis() + 55000;
        startupSession = true;
        startupError = "";
        startupActivity = new WeakReference<>(activity);
        enter(Phase.WAIT_WAZE, 12000, "Ouverture de Waze…");
        final long generation = startupGeneration;
        pendingFirstLaunch = () -> {
            if (instance != this || !startupSession || startupGeneration != generation || phase != Phase.WAIT_WAZE) return;
            pendingFirstLaunch = null;
            Activity launching = startupActivity == null ? null : startupActivity.get();
            if (!visibleActivity(launching) || !launching.hasWindowFocus()) {
                fail("RoadDeck a quitté le premier plan avant l’ouverture de Waze.");
                return;
            }
            try { launching.startActivity(intent); }
            catch (RuntimeException error) { fail("Impossible d’ouvrir Waze : " + error.getClass().getSimpleName()); }
        };
        showStartup();
        // A failed overlay must not prevent a valid split. Never wait indefinitely
        // for a compositor callback on a vendor ROM that refuses this surface.
        handler.postDelayed(() -> {
            if (startupGeneration == generation && pendingFirstLaunch != null) {
                if (!startupPlacementConfirmed) {
                    startupError = "Écran de chargement non confirmé par Android.";
                    detachStartupView();
                }
                runFirstLaunch();
            }
        }, 1200);
        enqueue(180);
        return true;
    }

    private void showStartup() {
        if (!startupSession || windowManager == null) return;
        Point size = new Point();
        windowManager.getDefaultDisplay().getRealSize(size);
        if (size.x <= 0 || size.y <= 0) return;
        startupBounds = new SplitGeometry.Box(0, 0, size.x, size.y);
        startupView = new StartupLoadingView(this, this::cancelStartupInternal);
        startupView.setStage(message);
        startupView.setAlpha(0f);
        startupPlacementConfirmed = startupDrawn = startupReleaseQueued = startupSuspended = false;
        final StartupLoadingView candidate = startupView;
        candidate.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        candidate.setOnApplyWindowInsetsListener((view, insets) -> {
            readBarInsets(insets);
            return insets;
        });
        candidate.addOnLayoutChangeListener((view, left, top, right, bottom, oldL, oldT, oldR, oldB) -> {
            if (view != startupView || startupBounds == null) return;
            captureStartupWindowId();
            int[] location = new int[2]; view.getLocationOnScreen(location);
            boolean exact = location[0] == 0 && location[1] == 0
                    && view.getWidth() == startupBounds.width() && view.getHeight() == startupBounds.height();
            startupPlacementConfirmed = exact;
            view.setAlpha(exact && !startupSuspended ? 1f : 0f);
            if (!exact && view.getWidth() > 0 && view.getHeight() > 0)
                startupError = "Position plein écran du chargement refusée par Android.";
            publish();
        });
        candidate.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            @Override public void onDraw() {
                if (candidate != startupView || !startupPlacementConfirmed || startupSuspended || startupDrawn) return;
                startupDrawn = true;
                // Leave one frame for composition before replacing the visible activity.
                candidate.postOnAnimation(() -> {
                    if (candidate != startupView) return;
                    publish();
                    runFirstLaunch();
                });
            }
        });
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(size.x, size.y,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.windowAnimations = 0;
            params.setTitle("RoadDeck préparation de l’écran partagé");
            if (Build.VERSION.SDK_INT >= 28)
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
            windowManager.addView(candidate, params);
            candidate.requestApplyInsets();
        } catch (RuntimeException error) {
            startupError = "Chargement visuel indisponible : " + error.getClass().getSimpleName();
            detachStartupView();
            runFirstLaunch();
        }
        publish();
    }

    private void runFirstLaunch() {
        Runnable launch = pendingFirstLaunch;
        if (launch != null) launch.run();
    }

    private void captureStartupWindowId() {
        if (startupView == null || !startupView.isAttachedToWindow()) return;
        try {
            AccessibilityNodeInfo node = startupView.createAccessibilityNodeInfo();
            if (node == null) return;
            try { if (node.getWindowId() >= 0) startupWindowIds.add(node.getWindowId()); }
            finally { node.recycle(); }
        } catch (RuntimeException ignored) { }
    }

    private boolean isOwnOverlayWindow(int id) {
        return id >= 0 && (id == seamWindowId || startupWindowIds.contains(id)
                || (systemBarMask != null && systemBarMask.isOwnWindow(id)));
    }

    private boolean startupVisible() {
        return startupSession && startupView != null && startupView.isAttachedToWindow()
                && !startupSuspended && startupPlacementConfirmed && startupDrawn && startupView.getAlpha() > .99f;
    }

    private void suspendStartup(boolean suspended) {
        if (startupView == null || startupSuspended == suspended) return;
        startupSuspended = suspended;
        startupView.setVisibility(suspended ? View.INVISIBLE : View.VISIBLE);
        startupView.setAlpha(!suspended && startupPlacementConfirmed ? 1f : 0f);
        try {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) startupView.getLayoutParams();
            if (suspended) params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(startupView, params);
        } catch (RuntimeException error) {
            startupError = "Chargement retiré pour laisser accès à la fenêtre ouverte.";
            detachStartupView();
        }
    }

    private void updateStartup() {
        if (!startupSession) return;
        if (startupView != null) startupView.setStage(message);
        if (phase == Phase.RUNNING) {
            if (startupReadyDeadline == 0) startupReadyDeadline = SystemClock.uptimeMillis() + 10000;
            boolean frameSettled = !frameEnabled || frameVisible() || framePlacementFailed || latest.decorationObstructed;
            if (confirmedPair(latest) && panelReady && frameSettled && !startupReleaseQueued) {
                startupReleaseQueued = true;
                final long generation = startupGeneration;
                handler.postDelayed(() -> {
                    if (generation != startupGeneration || !startupSession) return;
                    if (phase == Phase.RUNNING && active && panelReady) {
                        removeStartup();
                        updateBars(latest);
                        publish();
                    } else startupReleaseQueued = false;
                }, 80);
            } else if (SystemClock.uptimeMillis() > startupReadyDeadline) {
                fail("Les fenêtres sont ouvertes, mais le panneau RoadDeck n’a pas terminé son affichage. Réessayez depuis Réglages > Waze.");
            }
        }
    }

    private void detachStartupView() {
        captureStartupWindowId();
        StartupLoadingView old = startupView;
        startupView = null;
        if (old != null) {
            old.destroy();
            try { if (old.isAttachedToWindow()) windowManager.removeViewImmediate(old); }
            catch (RuntimeException ignored) { }
        }
        startupBounds = null;
        startupPlacementConfirmed = startupDrawn = false;
    }

    private void removeStartup() {
        ++startupGeneration;
        pendingFirstLaunch = null;
        startupActivity = null;
        startupSession = false;
        startupReadyDeadline = 0;
        startupReleaseQueued = false;
        detachStartupView();
    }

    private void showRecovery() {
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("skipWazeAuto", true).putExtra("roaddeckPage", "waze"));
        } catch (RuntimeException ignored) { }
    }

    private void updateBars(Observation observation) {
        if (systemBarMask == null) return;
        boolean allowed = coverSystemBars && phase == Phase.RUNNING && active && confirmedPair(observation)
                && !observation.decorationObstructed && !observation.inputMethodVisible && !observation.keyguardLocked
                && (observation.wazeFocused || observation.panelFocused)
                && SystemClock.uptimeMillis() >= barsRevealedUntil;
        systemBarMask.update(observation.barExclusions, allowed);
    }

    private void startClose() {
        removeStartup();
        if (systemBarMask != null) systemBarMask.hide();
        previewUntil = 0;
        failureStage = "";
        failureElapsedMs = 0;
        requestStartedAt = SystemClock.uptimeMillis();
        phaseStartedAt = requestStartedAt;
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
        Phase observedPhase = phase;
        latest = observe();
        long now = SystemClock.uptimeMillis();
        phaseSamples++;
        if (startupSession) suspendStartup(latest.unknownForeground);
        if (startupSession && (latest.keyguardLocked || latest.focusedSystemWindow
                || latest.inputMethodVisible || unexpectedStartupApplication(latest))) {
            // A permission dialog, lock screen or deliberately opened app must be
            // accessible immediately. Do not relaunch RoadDeck over that surface.
            cancelScans();
            removeStartup();
            removeSeam();
            if (systemBarMask != null) systemBarMask.hide();
            active = false;
            phase = Phase.IDLE;
            message = "Préparation interrompue pour laisser accès à l’écran ouvert.";
            publish();
            return;
        }
        if (pending() && latest.screenWidth > 0 && latest.screenWidth < latest.screenHeight) {
            fail("L’écran doit rester en paysage pour afficher Waze à gauche.");
            return;
        }
        switch (phase) {
            case WAIT_WAZE:
                if (stable(latest.wazeFocused && latest.waze != null, latest)) {
                    if (latest.divider) {
                        enter(Phase.WAIT_CLEAR_EXISTING, 6500, "Préparation de l’écran partagé…");
                        toggleKnownSplit();
                    } else if (SplitGeometry.fullWidth(latest.waze, latest.screenWidth, latest.screenHeight)) {
                        enter(Phase.WAIT_DOCK_WAZE, 18000, "Redimensionnement de Waze…");
                        toggleForFocused(WAZE);
                    }
                }
                break;
            case WAIT_CLEAR_EXISTING:
                if (stable(!latest.divider && latest.wazeFocused
                        && SplitGeometry.fullWidth(latest.waze, latest.screenWidth, latest.screenHeight), latest)) {
                    enter(Phase.WAIT_DOCK_WAZE, 18000, "Redimensionnement de Waze…");
                    toggleForFocused(WAZE);
                }
                break;
            case WAIT_DOCK_WAZE:
                if (stable(latest.divider
                        && SplitGeometry.dockTransition(latest.waze, latest.screenWidth, latest.screenHeight), latest)) {
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
                    enter(Phase.WAIT_PANEL_FULL, 12000, "Préparation du panneau droit…");
                    launchPanel();
                }
                break;
            case WAIT_PANEL_FULL:
                if (stable(!latest.divider && panelVisible && latest.panelFocused
                        && SplitGeometry.fullWidth(latest.panel, latest.screenWidth, latest.screenHeight), latest)) {
                    enter(Phase.WAIT_DOCK_PANEL, 18000, "Positionnement du panneau droit…");
                    toggleForFocused(getPackageName());
                }
                break;
            case WAIT_DOCK_PANEL:
                if (stable(latest.divider && panelVisible
                        && SplitGeometry.dockTransition(latest.panel, latest.screenWidth, latest.screenHeight), latest)) {
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
                    Log.i(TAG, "verified pair " + diagnosticLine());
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
                    Log.i(TAG, "verified fullscreen " + diagnosticLine());
                    phase = Phase.IDLE;
                    message = "Panneau fermé. Waze est affiché en plein écran.";
                }
                break;
            default:
                removeSeam();
                break;
        }
        // A delayed main-thread callback must first assess the actual resulting
        // windows. Do not discard an already confirmed transition solely because
        // its callback was delivered just after the phase deadline.
        now = SystemClock.uptimeMillis();
        if (pending() && (now > overallDeadline || (phase == observedPhase && now > deadline))) {
            Log.w(TAG, "timeout " + diagnosticLine() + " overallExpired=" + (now > overallDeadline));
            String error;
            if (phase == Phase.WAIT_WAZE)
                error = "Waze ne s’est pas affiché au premier plan. Revenez dans RoadDeck pour réessayer.";
            else if (phase == Phase.CLOSE_WAIT_SPLIT || phase == Phase.CLOSE_WAIT_WAZE)
                error = "L’autoradio n’a pas confirmé le retour de Waze en plein écran.";
            else error = "L’autoradio n’a pas confirmé Waze à gauche en écran partagé. Aucun panneau ne recouvre sa carte.";
            fail(error);
            return;
        }
        if (pending() && now - lastWaitLogAt >= 1000) {
            lastWaitLogAt = now;
            Log.i(TAG, "wait " + diagnosticLine());
        }
        updateBars(latest);
        updateStartup();
        publish();
    }

    private boolean unexpectedStartupApplication(Observation observation) {
        String owner = observation.foregroundOtherPackage;
        if (owner == null || owner.isEmpty()) return false;
        // Native split briefly displays the system's task picker. Allow it only
        // while one of the two tasks is being docked, not during normal operation.
        boolean arranging = phase == Phase.WAIT_DOCK_WAZE || phase == Phase.WAIT_DOCK_PANEL
                || phase == Phase.WAIT_PAIR || phase == Phase.WAIT_CLEAR_FOR_SWAP;
        return !(arranging && ("com.android.systemui".equals(owner)
                || "com.android.launcher3".equals(owner)
                || "com.google.android.apps.nexuslauncher".equals(owner)));
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
        waitCondition = condition;
        waitReason = waitDescription(observation);
        if (!condition) { stableCount = 0; return false; }
        int signature;
        switch (phase) {
            case WAIT_WAZE:
            case WAIT_CLEAR_EXISTING:
            case WAIT_DOCK_WAZE:
            case CLOSE_WAIT_WAZE:
                signature = observation.signature(observation.waze);
                break;
            case WAIT_PANEL_FULL:
            case WAIT_DOCK_PANEL:
                signature = observation.signature(observation.panel);
                break;
            case WAIT_CLEAR_FOR_SWAP:
            case CLOSE_WAIT_SPLIT:
                signature = observation.divider ? 1 : 0;
                break;
            default:
                signature = observation.signature();
        }
        if (signature == stableSignature) stableCount++;
        else { stableSignature = signature; stableCount = 1; }
        return stableCount >= 2;
    }

    private void enter(Phase next, long timeoutMs, String text) {
        Phase previous = phase;
        phase = next;
        phaseStartedAt = SystemClock.uptimeMillis();
        phaseSamples = 0;
        waitCondition = false;
        waitReason = "awaiting first observation";
        stableCount = 0;
        stableSignature = 0;
        deadline = phaseStartedAt + timeoutMs;
        message = text;
        if (startupView != null) startupView.setStage(text);
        Log.i(TAG, "phase " + previous + " -> " + next + " requestMs="
                + (phaseStartedAt - requestStartedAt) + " timeoutMs=" + timeoutMs);
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
        long startedAt = SystemClock.uptimeMillis();
        boolean accepted = false;
        try {
            accepted = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
        } catch (RuntimeException ignored) {
        } finally {
            lastToggleMs = SystemClock.uptimeMillis() - startedAt;
            Log.i(TAG, "toggle phase=" + phase + " accepted=" + accepted + " durationMs=" + lastToggleMs);
        }
        if (accepted) return true;
        fail("L’autoradio refuse la commande d’écran partagé.");
        return false;
    }

    private void fail(String text) {
        boolean recover = startupSession;
        removeStartup();
        if (systemBarMask != null) systemBarMask.hide();
        cancelScans();
        removeSeam();
        failureStage = phase.name();
        failureElapsedMs = Math.max(0, SystemClock.uptimeMillis() - phaseStartedAt);
        Log.w(TAG, "failure " + diagnosticLine());
        phase = Phase.FAILED;
        active = false;
        message = text;
        publish();
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        if (recover) showRecovery();
    }

    private Observation observe() {
        Observation observation = new Observation();
        long startedAt = SystemClock.uptimeMillis();
        try {
            captureDecorationWindowId();
            captureStartupWindowId();
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            observation.keyguardLocked = keyguard != null && keyguard.isKeyguardLocked();
            Point size = new Point();
            if (windowManager != null) windowManager.getDefaultDisplay().getRealSize(size);
            observation.screenWidth = size.x;
            observation.screenHeight = size.y;
            addBarExclusions(observation);
            long windowsStartedAt = SystemClock.uptimeMillis();
            List<AccessibilityWindowInfo> windows = getWindows();
            observation.getWindowsMs = SystemClock.uptimeMillis() - windowsStartedAt;
            Set<Integer> seenIds = new HashSet<>();
            List<OwnedWindow> applicationWindows = new ArrayList<>();
            List<OwnedWindow> otherWindows = new ArrayList<>();
            for (AccessibilityWindowInfo window : windows) {
                try {
                    int id = window.getId(), type = window.getType();
                    seenIds.add(id);
                    if (type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                        observation.divider = true;
                        continue;
                    }
                    if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && isOwnOverlayWindow(id)) continue;
                    Rect rect = new Rect();
                    window.getBoundsInScreen(rect);
                    SplitGeometry.Box box = new SplitGeometry.Box(Math.max(0, rect.left), Math.max(0, rect.top),
                            Math.min(size.x, rect.right), Math.min(size.y, rect.bottom));
                    if (!box.valid()) continue;
                    OwnedWindow copied = new OwnedWindow(id, box, window.getLayer(), window.isFocused(), type);
                    if (type != AccessibilityWindowInfo.TYPE_APPLICATION || window.isInPictureInPictureMode()) {
                        otherWindows.add(copied);
                        continue;
                    }
                    if (!SplitGeometry.inside(box, size.x, size.y)) continue;
                    applicationWindows.add(copied);
                    String packageName = windowPackages.get(id);
                    if (packageName == null) {
                        observation.unknownOwners++;
                        requestWindowOwner(window);
                        continue;
                    }
                    if (WAZE.equals(packageName)
                            && (observation.waze == null || box.area() > observation.waze.area())) {
                        observation.waze = box;
                        observation.wazeWindowId = id;
                        observation.wazeFocused = window.isFocused();
                        observation.wazeLayer = window.getLayer();
                    } else if (getPackageName().equals(packageName)
                            && (observation.panel == null || box.area() > observation.panel.area())) {
                        observation.panel = box;
                        observation.panelWindowId = id;
                        observation.panelFocused = window.isFocused();
                        observation.panelLayer = window.getLayer();
                    }
                } catch (RuntimeException ignored) {
                    blockDecoration(observation, "Fenêtres en cours de modification.");
                } finally {
                    window.recycle();
                }
            }
            for (OwnedWindow window : applicationWindows) {
                if (window.id == observation.wazeWindowId || window.id == observation.panelWindowId) continue;
                String owner = windowPackages.get(window.id);
                if (window.focused && (owner == null || (!WAZE.equals(owner) && !getPackageName().equals(owner)))) {
                    if (owner == null) observation.unknownForeground = true;
                    if (owner != null) observation.foregroundOtherPackage = owner;
                    blockDecoration(observation, "Une autre application est au premier plan.");
                } else {
                    clipAbovePair(window, observation);
                }
            }
            for (OwnedWindow window : otherWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD)
                    observation.inputMethodVisible = true;
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && window.focused) {
                    observation.focusedSystemWindow = true;
                    blockDecoration(observation, "Une fenêtre système est au premier plan.");
                    continue;
                }
                if (!isAbovePair(window, observation)) {
                    observation.ignoredBelowPair++;
                    continue;
                }
                if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM
                        && broadSystemWindow(window.bounds, observation)
                        && !insideObservedBar(window.bounds, observation)) {
                    // A rectangular full-screen system surface is not proof of opacity
                    // or transparency. Never silently report a fully clipped frame visible.
                    blockDecoration(observation, barInsetsObserved
                            ? "Une fenêtre système étendue empêche de vérifier l’habillage."
                            : "Limites des commandes système non confirmées sur cet appareil.");
                    observation.ambiguousSystemWindows++;
                    continue;
                }
                clipAbovePair(window, observation);
            }
            liveWindowIds.clear();
            liveWindowIds.addAll(seenIds);
            for (int index = windowPackages.size() - 1; index >= 0; index--)
                if (!seenIds.contains(windowPackages.keyAt(index))) windowPackages.removeAt(index);
            for (int index = ownerRequests.size() - 1; index >= 0; index--)
                if (!seenIds.contains(ownerRequests.keyAt(index))) ownerRequests.removeAt(index);
            for (int index = ownerRetryAt.size() - 1; index >= 0; index--)
                if (!seenIds.contains(ownerRetryAt.keyAt(index))) ownerRetryAt.removeAt(index);
        } catch (RuntimeException ignored) {
            blockDecoration(observation, "Lecture des fenêtres temporairement indisponible.");
        } finally {
            observation.durationMs = SystemClock.uptimeMillis() - startedAt;
            if (observation.durationMs >= 250)
                Log.w(TAG, "slow scan phase=" + phase + " durationMs=" + observation.durationMs
                        + " getWindowsMs=" + observation.getWindowsMs);
        }
        return observation;
    }

    private static void blockDecoration(Observation observation, String reason) {
        observation.decorationObstructed = true;
        if (observation.obstructionReason.isEmpty()) observation.obstructionReason = reason;
    }

    private static boolean isAbovePair(OwnedWindow window, Observation observation) {
        SplitGeometry.Box gap = SplitGeometry.pair(observation.waze, observation.panel,
                observation.screenWidth, observation.screenHeight, observation.screenWidth).gap;
        return (observation.waze != null && window.layer >= observation.wazeLayer
                        && window.bounds.intersects(observation.waze))
                || (observation.panel != null && window.layer >= observation.panelLayer
                        && window.bounds.intersects(observation.panel))
                || (gap != null && window.layer >= Math.min(observation.wazeLayer, observation.panelLayer)
                        && window.bounds.intersects(gap));
    }

    private static void clipAbovePair(OwnedWindow window, Observation observation) {
        boolean clipped = false;
        if (observation.waze != null && window.layer >= observation.wazeLayer)
            clipped |= addIntersection(observation.systemExclusions, window.bounds, observation.waze);
        if (observation.panel != null && window.layer >= observation.panelLayer)
            clipped |= addIntersection(observation.systemExclusions, window.bounds, observation.panel);
        if (window.layer >= Math.min(observation.wazeLayer, observation.panelLayer)) {
            SplitGeometry.Pair pair = SplitGeometry.pair(observation.waze, observation.panel,
                    observation.screenWidth, observation.screenHeight, observation.screenWidth);
            if (pair.gap != null) clipped |= addIntersection(observation.systemExclusions, window.bounds, pair.gap);
        }
        if (clipped) observation.regionallyExcludedWindows++;
        else observation.ignoredBelowPair++;
    }

    private static boolean addIntersection(List<SplitGeometry.Box> list, SplitGeometry.Box a, SplitGeometry.Box b) {
        if (!a.intersects(b)) return false;
        SplitGeometry.Box clipped = new SplitGeometry.Box(Math.max(a.left, b.left), Math.max(a.top, b.top),
                Math.min(a.right, b.right), Math.min(a.bottom, b.bottom));
        if (!list.contains(clipped)) list.add(clipped);
        return true;
    }

    private static boolean broadSystemWindow(SplitGeometry.Box box, Observation observation) {
        // A broad rectangle is ambiguous, never assumed to be transparent by size.
        return (long) box.width() * 2 >= observation.screenWidth
                && (long) box.height() * 2 >= observation.screenHeight;
    }

    private static boolean insideObservedBar(SplitGeometry.Box box, Observation observation) {
        for (SplitGeometry.Box bar : observation.barExclusions)
            if (box.left >= bar.left && box.top >= bar.top && box.right <= bar.right && box.bottom <= bar.bottom)
                return true;
        return false;
    }

    @SuppressWarnings("deprecation")
    private void readBarInsets(WindowInsets insets) {
        if (insets == null || windowManager == null) return;
        Point display = new Point();
        windowManager.getDefaultDisplay().getRealSize(display);
        int left = Math.max(insets.getStableInsetLeft(), insets.getSystemWindowInsetLeft());
        int top = Math.max(insets.getStableInsetTop(), insets.getSystemWindowInsetTop());
        int right = Math.max(insets.getStableInsetRight(), insets.getSystemWindowInsetRight());
        int bottom = Math.max(insets.getStableInsetBottom(), insets.getSystemWindowInsetBottom());
        if (barInsetsObserved && barLeft == left && barTop == top && barRight == right && barBottom == bottom
                && insetScreenWidth == display.x && insetScreenHeight == display.y) return;
        barInsetsObserved = true;
        barLeft = left; barTop = top; barRight = right; barBottom = bottom;
        insetScreenWidth = display.x; insetScreenHeight = display.y;
        hideDecoration();
        if (phase == Phase.RUNNING) enqueue(0);
    }

    private void addBarExclusions(Observation observation) {
        int width = observation.screenWidth, height = observation.screenHeight;
        if (!barInsetsObserved || width != insetScreenWidth || height != insetScreenHeight) return;
        if (barLeft > 0) observation.barExclusions.add(new SplitGeometry.Box(0, 0, Math.min(width, barLeft), height));
        if (barTop > 0) observation.barExclusions.add(new SplitGeometry.Box(0, 0, width, Math.min(height, barTop)));
        if (barRight > 0) observation.barExclusions.add(new SplitGeometry.Box(Math.max(0, width - barRight), 0, width, height));
        if (barBottom > 0) observation.barExclusions.add(new SplitGeometry.Box(0, Math.max(0, height - barBottom), width, height));
        observation.systemExclusions.addAll(observation.barExclusions);
    }

    private void requestWindowOwner(AccessibilityWindowInfo window) {
        final int windowId = window.getId();
        Long retryAt = ownerRetryAt.get(windowId);
        if (ownerRequests.get(windowId) != null || ownerReader == null || ownerReader.isShutdown()
                || (retryAt != null && retryAt > SystemClock.uptimeMillis())) return;
        final long generation = ownerGeneration;
        final long token = ++nextOwnerRequest;
        final AccessibilityWindowInfo copy = AccessibilityWindowInfo.obtain(window);
        ownerRequests.put(windowId, token);
        try {
            ownerReader.execute(() -> {
                AccessibilityNodeInfo root = null;
                String owner = null;
                long startedAt = SystemClock.uptimeMillis();
                try {
                    // getRoot may wait for another app for several seconds. Never
                    // do that on the UI thread or wait for this worker from it.
                    if (generation == ownerGeneration && !Thread.currentThread().isInterrupted()) {
                        root = copy.getRoot();
                        CharSequence packageName = root == null ? null : root.getPackageName();
                        if (packageName != null) owner = packageName.toString();
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    if (root != null) root.recycle();
                    copy.recycle();
                }
                final String resolvedOwner = owner;
                final long durationMs = SystemClock.uptimeMillis() - startedAt;
                handler.post(() -> {
                    if (instance != this || generation != ownerGeneration) return;
                    Long pendingToken = ownerRequests.get(windowId);
                    if (pendingToken == null || pendingToken.longValue() != token) return;
                    ownerRequests.remove(windowId);
                    rootQueryCount++;
                    lastRootMs = durationMs;
                    maximumRootMs = Math.max(maximumRootMs, durationMs);
                    if (liveWindowIds.contains(windowId)) {
                        if (resolvedOwner != null && windowPackages.get(windowId) == null)
                            windowPackages.put(windowId, resolvedOwner);
                        else if (resolvedOwner == null)
                            ownerRetryAt.put(windowId, SystemClock.uptimeMillis() + 1500);
                    }
                    Log.i(TAG, "owner query window=" + windowId + " durationMs=" + durationMs
                            + " resolved=" + (resolvedOwner != null) + " pending=" + ownerRequests.size());
                    if (pending() || phase == Phase.RUNNING) enqueue(0);
                });
            });
        } catch (RejectedExecutionException error) {
            ownerRequests.remove(windowId);
            copy.recycle();
        }
    }

    private void forgetWindow(int windowId) {
        windowPackages.remove(windowId);
        ownerRequests.remove(windowId);
        ownerRetryAt.remove(windowId);
        liveWindowIds.remove(windowId);
    }

    private void clearWindowOwners() {
        ownerGeneration++;
        windowPackages.clear();
        ownerRequests.clear();
        ownerRetryAt.clear();
        liveWindowIds.clear();
    }

    private String waitDescription(Observation observation) {
        switch (phase) {
            case WAIT_DOCK_WAZE:
                if (!observation.divider) return "divider absent";
                if (observation.waze == null) return "Waze window owner unresolved or absent";
                if (!SplitGeometry.dockTransition(observation.waze, observation.screenWidth, observation.screenHeight))
                    return "Waze bounds not docked";
                return "confirming repeated Waze bounds";
            case WAIT_DOCK_PANEL:
                if (!observation.divider) return "divider absent";
                if (!panelVisible) return "panel lifecycle not visible";
                if (observation.panel == null) return "panel window owner unresolved or absent";
                if (!SplitGeometry.dockTransition(observation.panel, observation.screenWidth, observation.screenHeight))
                    return "panel bounds not docked";
                return "confirming repeated panel bounds";
            case WAIT_PAIR:
                if (!observation.divider) return "divider absent";
                if (!panelVisible || !panelMultiWindow) return "panel lifecycle not in multiwindow";
                if (measuredPair(observation).arrangement != SplitGeometry.WAZE_LEFT)
                    return "complete Waze-left pair not measured";
                return "confirming complete pair";
            default:
                return "divider=" + observation.divider + ", WazeFocused=" + observation.wazeFocused
                        + ", panelFocused=" + observation.panelFocused + ", panelVisible=" + panelVisible;
        }
    }

    private String diagnosticLine() {
        return "phase=" + phase + " elapsedMs=" + Math.max(0, SystemClock.uptimeMillis() - phaseStartedAt)
                + " samples=" + phaseSamples + " condition=" + waitCondition + " stable=" + stableCount
                + " reason=" + waitReason + " scanMs=" + latest.durationMs
                + " getWindowsMs=" + latest.getWindowsMs + " ownersPending=" + ownerRequests.size()
                + " unknownOwners=" + latest.unknownOwners + " lastRootMs=" + lastRootMs;
    }

    private void updateSeam(Observation observation) {
        SplitGeometry.Box gap = measuredPair(observation).gap;
        if (!confirmedPair(observation) || observation.decorationObstructed
                || (!observation.wazeFocused && !observation.panelFocused)
                || (gap != null && gap.width() > maximumGap())) {
            removeSeam();
            return;
        }
        int signature = observation.signature();
        boolean framed = frameEnabled && !(framePlacementFailed && failedFrameSignature == signature);
        if (!framed && gap == null) {
            removeSeam();
            return;
        }
        SplitGeometry.Box bounds = framed
                ? new SplitGeometry.Box(Math.min(observation.waze.left, observation.panel.left),
                        Math.min(observation.waze.top, observation.panel.top),
                        Math.max(observation.waze.right, observation.panel.right),
                        Math.max(observation.waze.bottom, observation.panel.bottom))
                : gap;
        int drawingSignature = 31 * signature + (framed ? 1 : 0);
        for (SplitGeometry.Box excluded : observation.systemExclusions)
            drawingSignature = 31 * drawingSignature + excluded.hashCode();
        drawingSignature = 31 * drawingSignature + frameLeft;
        drawingSignature = 31 * drawingSignature + frameTop;
        drawingSignature = 31 * drawingSignature + frameRight;
        drawingSignature = 31 * drawingSignature + frameBottom;
        boolean preview = framed && SystemClock.uptimeMillis() < previewUntil;
        drawingSignature = 31 * drawingSignature + (preview ? 1 : 0);
        decorationAuthoritative = true;
        if (bounds.equals(seamBounds) && seam != null && decorationSignature == drawingSignature) {
            validateSeamPosition(seam);
            return;
        }
        try {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = bounds.left;
            params.y = bounds.top;
            params.windowAnimations = 0;
            params.setTitle(framed ? "RoadDeck habillage commun" : "RoadDeck raccord du panneau");
            seamBounds = bounds;
            if (framed) lastRequestedFrameBounds = bounds;
            seamFramed = framed;
            decorationSignature = drawingSignature;
            seamHasUnobscuredGap = gap != null;
            for (SplitGeometry.Box excluded : observation.systemExclusions)
                if (gap != null && gap.intersects(excluded)) seamHasUnobscuredGap = false;
            if (seam == null) {
                seam = new SplitFrameView(this, view -> {
                    if (view == seam) publish();
                });
                seam.setOnApplyWindowInsetsListener((view, insets) -> {
                    readBarInsets(insets);
                    return insets;
                });
                seam.setAlpha(0f);
                seam.setGeometry(bounds, observation.waze, observation.panel, gap, framed,
                        observation.systemExclusions, frameLeft, frameTop, frameRight, frameBottom, preview);
                seam.addOnLayoutChangeListener((view, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> validateSeamPosition(view));
                windowManager.addView(seam, params);
            } else {
                seam.setAlpha(0f);
                seam.setGeometry(bounds, observation.waze, observation.panel, gap, framed,
                        observation.systemExclusions, frameLeft, frameTop, frameRight, frameBottom, preview);
                windowManager.updateViewLayout(seam, params);
            }
            View candidate = seam;
            candidate.requestApplyInsets();
            candidate.post(() -> validateSeamPosition(candidate));
        } catch (RuntimeException ignored) {
            if (framed) {
                framePlacementFailed = true;
                failedFrameSignature = signature;
            }
            removeSeam();
            if (framed && gap != null) enqueue(0);
            // Decoration failure must never block a functioning split-screen pair.
        }
    }

    private void validateSeamPosition(View candidate) {
        if (candidate != seam || seamBounds == null || !candidate.isAttachedToWindow()) return;
        captureDecorationWindowId();
        if (!decorationAuthoritative || !active || phase != Phase.RUNNING || instance != this) return;
        int[] location = new int[2];
        candidate.getLocationOnScreen(location);
        SplitGeometry.Box actual = new SplitGeometry.Box(location[0], location[1],
                location[0] + candidate.getWidth(), location[1] + candidate.getHeight());
        if (seamFramed) lastPlacedFrameBounds = actual;
        if (actual.equals(seamBounds)) candidate.setAlpha(1f);
        else if (candidate.getWidth() > 0 && candidate.getHeight() > 0) {
            boolean fallback = seamFramed;
            if (fallback) {
                framePlacementFailed = true;
                failedFrameSignature = latest.signature();
            }
            removeSeam();
            if (fallback) enqueue(0);
        }
        publish();
    }

    private void captureDecorationWindowId() {
        if (seam == null || !seam.isAttachedToWindow() || seamWindowId >= 0) return;
        // A local node gives our own window ID through public APIs. Never inspect
        // another application's root or contents to identify this decoration.
        try {
            AccessibilityNodeInfo ownNode = seam.createAccessibilityNodeInfo();
            if (ownNode != null) {
                try { seamWindowId = ownNode.getWindowId(); }
                finally { ownNode.recycle(); }
            }
        } catch (RuntimeException ignored) { }
    }

    private void hideDecoration() {
        decorationAuthoritative = false;
        if (seam != null) seam.setAlpha(0f);
    }

    private void removeSeam() {
        hideDecoration();
        if (seam != null) {
            try { if (windowManager != null) windowManager.removeViewImmediate(seam); }
            catch (RuntimeException ignored) { }
        }
        seam = null;
        seamBounds = null;
        seamWindowId = -1;
        seamFramed = false;
        seamHasUnobscuredGap = false;
    }

    private void publish() {
        try {
            JSONObject out = new JSONObject().put("mode", "split").put("active", active)
                    .put("requested", pending()).put("accessibilityGranted", instance == this)
                    .put("serviceConnected", instance == this)
                    .put("startupVisible", startupVisible())
                    .put("startupPending", startupSession)
                    .put("startupPlacementConfirmed", startupPlacementConfirmed)
                    .put("startupSuspended", startupSuspended)
                    .put("startupError", startupError)
                    .put("panelReady", panelReady)
                    .put("barMaskEnabled", coverSystemBars)
                    .put("barMaskTemporarilyRevealed", SystemClock.uptimeMillis() < barsRevealedUntil)
                    .put("barMaskStatus", barMaskStatus())
                    .put("splitActionAvailability", splitActionAvailability)
                    .put("message", message).put("state", phase.name())
                    .put("failureStage", failureStage).put("dividerObserved", latest.divider)
                    .put("screenWidth", latest.screenWidth).put("screenHeight", latest.screenHeight)
                    .put("phaseElapsedMs", phase == Phase.FAILED ? failureElapsedMs
                            : Math.max(0, SystemClock.uptimeMillis() - phaseStartedAt))
                    .put("phaseSamples", phaseSamples).put("waitCondition", waitCondition)
                    .put("waitReason", waitReason).put("stableSamples", stableCount)
                    .put("scanDurationMs", latest.durationMs).put("getWindowsDurationMs", latest.getWindowsMs)
                    .put("ownerQueriesPending", ownerRequests.size()).put("unknownOwners", latest.unknownOwners)
                    .put("ownerQueryCount", rootQueryCount).put("lastRootDurationMs", lastRootMs)
                    .put("maximumRootDurationMs", maximumRootMs).put("lastToggleDurationMs", lastToggleMs)
                    .put("dividerCovered", seam != null && seamHasUnobscuredGap && seam.isAttachedToWindow()
                            && seam.getAlpha() > 0.99f && seam.hasDrawnGeometry() && seam.gapPaintedPixels() > 0)
                    .put("frameEnabled", frameEnabled)
                    .put("frameVisible", frameVisible())
                    .put("frameGeometryAuthoritative", decorationAuthoritative)
                    .put("frameStatus", frameStatus())
                    .put("frameInsets", new JSONObject().put("left", frameLeft).put("top", frameTop)
                            .put("right", frameRight).put("bottom", frameBottom))
                    .put("framePaintedPixels", seam != null && seam.getAlpha() > 0.99f ? seam.paintedPixels() : 0)
                    .put("frameNavigationPaintedPixels", seam != null && seam.getAlpha() > 0.99f
                            ? seam.navigationPaintedPixels() : 0)
                    .put("framePreview", frameVisible() && seam.previewPaintedPixels() > 0
                            && SystemClock.uptimeMillis() < previewUntil)
                    .put("frameRevision", seam == null ? -1 : seam.revision())
                    .put("frameDrawnRevision", seam == null ? -1 : seam.drawnRevision())
                    .put("frameObstructionReason", latest.obstructionReason)
                    .put("frameExclusionCount", latest.systemExclusions.size())
                    .put("frameRegionallyExcludedWindows", latest.regionallyExcludedWindows)
                    .put("frameIgnoredBelowPair", latest.ignoredBelowPair)
                    .put("frameAmbiguousSystemWindows", latest.ambiguousSystemWindows)
                    .put("frameBarInsetsObserved", barInsetsObserved)
                    .put("frameMaxInsetPx", seam != null && seamFramed ? seam.maximumInsetPixels() : 0)
                    .put("framePlacementFallback", framePlacementFailed && failedFrameSignature == latest.signature())
                    .put("decorationObstructed", latest.decorationObstructed);
            if (systemBarMask != null) {
                JSONObject mask = systemBarMask.snapshot();
                java.util.Iterator<String> keys = mask.keys();
                while (keys.hasNext()) { String key = keys.next(); out.put(key, mask.get(key)); }
            } else out.put("barMaskVisible", false);
            if (startupBounds != null) out.put("startupBounds", boundsJson(startupBounds));
            if (seamBounds != null && seamFramed) {
                out.put("frameBounds", boundsJson(seamBounds));
                out.put("frameNavigationBounds", seam.navigationBounds() == null
                        ? JSONObject.NULL : boundsJson(seam.navigationBounds()));
                out.put("framePanelBounds", seam.panelBounds() == null
                        ? JSONObject.NULL : boundsJson(seam.panelBounds()));
            }
            if (lastRequestedFrameBounds != null) out.put("frameRequestedBounds", boundsJson(lastRequestedFrameBounds));
            if (lastPlacedFrameBounds != null) out.put("framePlacedBounds", boundsJson(lastPlacedFrameBounds));
            JSONArray exclusions = new JSONArray();
            for (SplitGeometry.Box box : latest.systemExclusions) {
                if (exclusions.length() == 16) break;
                exclusions.put(boundsJson(box));
            }
            out.put("frameExclusions", exclusions);
            if (latest.waze != null) out.put("wazeBounds", boundsJson(latest.waze));
            if (latest.panel != null) out.put("panelBounds", boundsJson(latest.panel));
            lastState = out.toString();
        } catch (JSONException ignored) { }
    }

    private boolean frameVisible() {
        return active && phase == Phase.RUNNING && seam != null && seamFramed
                && decorationAuthoritative && seam.isAttachedToWindow()
                && seam.getAlpha() > 0.99f && seam.hasDrawnGeometry() && seam.navigationPaintedPixels() > 0;
    }

    private String barMaskStatus() {
        if (!coverSystemBars) return "Barres Android visibles.";
        if (SystemClock.uptimeMillis() < barsRevealedUntil) return "Commandes Android affichées temporairement.";
        if (systemBarMask != null && systemBarMask.visible())
            return "Barres recouvertes par l’habillage. Touchez la bande pour afficher Android.";
        if (!active) return "Masquage des barres en attente de l’écran partagé.";
        if (latest.decorationObstructed || latest.inputMethodVisible || latest.keyguardLocked)
            return "Commandes Android accessibles pendant l’écran système.";
        return "Masquage des barres non confirmé sur cet appareil.";
    }

    private String frameStatus() {
        if (!frameEnabled) return "Habillage désactivé.";
        if (!active || phase != Phase.RUNNING) return "Habillage en attente du partage Waze / RoadDeck.";
        if (latest.decorationObstructed) return "Habillage masqué : " + latest.obstructionReason;
        if (framePlacementFailed && failedFrameSignature == latest.signature())
            return "Habillage indisponible : position refusée par Android. Raccord central seul.";
        if (frameVisible()) return "Habillage dessiné autour de Waze.";
        if (seam != null && seamFramed && seam.hasDrawnGeometry() && seam.navigationPaintedPixels() == 0)
            return "Habillage masqué par les fenêtres protégées.";
        return "Vérification de l’habillage en cours…";
    }

    private JSONObject boundsJson(SplitGeometry.Box box) throws JSONException {
        return new JSONObject().put("left", box.left).put("top", box.top)
                .put("right", box.right).put("bottom", box.bottom);
    }

    private static final class OwnedWindow {
        final int id, layer, type;
        final boolean focused;
        final SplitGeometry.Box bounds;
        OwnedWindow(int id, SplitGeometry.Box bounds, int layer, boolean focused, int type) {
            this.id = id; this.bounds = bounds; this.layer = layer; this.focused = focused; this.type = type;
        }
    }

    private static final class Observation {
        SplitGeometry.Box waze, panel;
        boolean divider, wazeFocused, panelFocused, decorationObstructed;
        boolean inputMethodVisible, focusedSystemWindow, keyguardLocked, unknownForeground;
        String foregroundOtherPackage = "";
        int wazeWindowId = -1, panelWindowId = -1;
        int wazeLayer = Integer.MAX_VALUE, panelLayer = Integer.MAX_VALUE;
        int regionallyExcludedWindows, ignoredBelowPair, ambiguousSystemWindows;
        String obstructionReason = "";
        final List<SplitGeometry.Box> barExclusions = new ArrayList<>();
        final List<SplitGeometry.Box> systemExclusions = new ArrayList<>();
        int screenWidth, screenHeight;
        int unknownOwners;
        long durationMs, getWindowsMs;
        int signature(SplitGeometry.Box box) {
            int value = box == null ? 0 : box.hashCode();
            value = 31 * value + (divider ? 1 : 0);
            value = 31 * value + screenWidth;
            return 31 * value + screenHeight;
        }
        int signature() {
            int value = waze == null ? 0 : waze.hashCode();
            value = 31 * value + (panel == null ? 0 : panel.hashCode());
            value = 31 * value + (divider ? 1 : 0);
            value = 31 * value + screenWidth;
            return 31 * value + screenHeight;
        }
    }
}
