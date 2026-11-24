package fr.roaddeck.launcher;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

/** Actual Android windows and native touch tests against a separate, explicit fixture. */
@RunWith(AndroidJUnit4.class)
public final class SplitScreenAndroid10Test {
    private static final String NAVIGATION = "com.waze";
    private static final String ROADDECK = "fr.roaddeck.launcher";
    private static final String[] CORNERS = {"corner_top_left", "corner_top_right",
            "corner_bottom_left", "corner_bottom_right"};
    private static final String[] NAMES = {"HG", "HD", "BG", "BD"};
    private static final String[] CORNER_DESCRIPTIONS = {"Coin haut gauche", "Coin haut droit",
            "Coin bas gauche", "Coin bas droit"};
    private Instrumentation instrumentation;
    private Context context;
    private UiDevice device;
    private UiAutomation automation;
    private DomProbe domProbe;
    private Rect testedNavigationContent;

    @Rule public final TestWatcher evidence = new TestWatcher() {
        @Override protected void failed(Throwable error, Description description) {
            if (device != null && context != null) {
                device.takeScreenshot(new File(evidenceDirectory(), "split-failure.png"));
                try {
                    device.dumpWindowHierarchy(new File(evidenceDirectory(), "split-failure-hierarchy.xml"));
                } catch (Exception hierarchyError) {
                    android.util.Log.w("SplitScreenAndroid10Test", "Could not dump accessibility hierarchy", hierarchyError);
                }
                writeState(error.toString());
            }
        }
    };

    @Before public void prepare() throws Exception {
        // MUST precede UiDevice.getInstance: otherwise UiAutomation disables RoadDeck's service.
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Configurator.getInstance().setWaitForIdleTimeout(500);
        Configurator.getInstance().setWaitForSelectorTimeout(1000);
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        device = UiDevice.getInstance(instrumentation);
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(serviceInfo);
        assertEquals("This regression test targets Android 10/API29", 29, Build.VERSION.SDK_INT);

        ApplicationInfo fixture = context.getPackageManager().getApplicationInfo(NAVIGATION, PackageManager.GET_META_DATA);
        assertEquals("Do not run this test against the real Waze app", "Navigation de test",
                context.getPackageManager().getApplicationLabel(fixture).toString());
        assertTrue("The navigation APK must be marked testOnly", (fixture.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0);
        assertNotNull("Missing fixture marker", fixture.metaData);
        assertTrue("Missing fixture marker", fixture.metaData.getBoolean("roaddeck.navigation_fixture", false));
        device.wakeUp();
        enableSplitServiceAfterUiAutomation();
        await("RoadDeck accessibility service connected", 15000, SplitScreenService::isConnected);
    }

    /**
     * am instrument force-stops its target package before starting the test process.
     * A service enabled by adb before instrumentation can remain stuck in AMS's binding
     * set. Enable it here, only after UiAutomation is connected without suppression.
     * These shell privileges exist only in the test harness, never in the shipped app.
     */
    private void enableSplitServiceAfterUiAutomation() throws Exception {
        ComponentName target = new ComponentName(context, SplitScreenService.class);
        String current = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> others = new ArrayList<>();
        boolean wasEnabled = false;
        if (current != null) for (String value : current.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            if (component.equals(target)) wasEnabled = true;
            else others.add(component.flattenToString());
        }
        if (wasEnabled || SplitScreenService.isConnected()) {
            putEnabledServices(others);
            AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            await("old accessibility binding disabled", 5000, () -> {
                if (SplitScreenService.isConnected()) return false;
                for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
                    if (target.equals(ComponentName.unflattenFromString(service.getId()))) return false;
                return true;
            });
            // Allow the system-server settings observer to clear a stale *pending* binding too.
            SystemClock.sleep(500);
        }
        others.add(target.flattenToString());
        putEnabledServices(others);
        device.executeShellCommand("settings --user current put secure accessibility_enabled 1");
    }

    private void putEnabledServices(List<String> services) throws Exception {
        String value = String.join(":", services);
        if (value.isEmpty()) {
            device.executeShellCommand("settings --user current delete secure enabled_accessibility_services");
        } else {
            assertTrue("Unexpected component characters in accessibility settings",
                    value.matches("[A-Za-z0-9_.$/:-]+"));
            device.executeShellCommand("settings --user current put secure enabled_accessibility_services " + value);
        }
    }

    @Test public void nativeNavigationResizesAndRemainsInteractiveAcrossPanelSettings() throws Exception {
        Intent launch = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("skipWazeAuto", true);
        MainActivity main = (MainActivity) instrumentation.startActivitySync(launch);
        await("MainActivity visible in landscape", 15000,
                () -> findActivity(MainActivity.class) != null && device.getDisplayWidth() > device.getDisplayHeight());
        final int mainTask = taskId(main);
        await("launch button is rendered before the user's first touch", 15000, () -> {
            JSONObject page = readDomElement(main, "launchWaze");
            return page.optBoolean("visible") && page.optBoolean("hit")
                    && page.optBoolean("visualStateReady") && "BUTTON".equals(page.optString("tag"));
        });
        tapPanelButton(main, "launchWaze", "Afficher Waze à gauche");
        // The engine can spend 55 seconds resolving an OEM's initial split orientation.
        await("native split active", 65000, () -> {
            JSONObject state = snapshot();
            assertNotEquals("Split engine failed: " + state, "FAILED", state.optString("state"));
            return state.optBoolean("active");
        });

        SidePanelActivity panel = awaitActivity(SidePanelActivity.class, 10000);
        int panelTask = taskId(panel);
        assertNotEquals("The side panel must have its own task, separate from HOME", mainTask, panelTask);
        assertMultiWindow(panel);
        Rect navigation = awaitWindow(NAVIGATION, 10000);
        Rect side = awaitWindow(ROADDECK, 10000);
        assertSideBySide(navigation, side);
        assertRectNear("Engine Waze bounds must agree with Android windows", navigation,
                snapshotRect("wazeBounds"));
        assertRectNear("Engine panel bounds must agree with Android windows", side,
                snapshotRect("panelBounds"));
        await("unified frame visibly attached", 10000, () -> snapshot().optBoolean("frameVisible"));
        assertTrue("Unified frame is enabled by default", snapshot().optBoolean("frameEnabled"));
        assertRectNear("Frame window follows the actual split pair", union(navigation, side),
                snapshotRect("frameBounds"));
        // AccessibilityWindowInfo can include system-bar areas even when the app's
        // native content view is inset. Measure the fixture's actual parent view
        // independently; do not accept the service's decoration bounds on trust.
        final Rect navigationContent = corner(0).getParent().getVisibleBounds();
        testedNavigationContent = new Rect(navigationContent);
        assertTrue("Native content must stay inside its application window", navigation.contains(navigationContent));
        assertTrue("Native content must retain useful height", navigationContent.height() > navigation.height() * .75);
        assertRectNear("Surround follows native content outside protected system bars",
                navigationContent, snapshotRect("frameNavigationBounds"));
        JSONObject insets = snapshot().getJSONObject("frameInsets");
        for (String edge : new String[]{"left", "top", "right", "bottom"})
            assertEquals("Default surround margin in dp: " + edge, 12, insets.getInt(edge));
        assertTrue("The left frame must have a substantial nonempty painted region",
                snapshot().optLong("frameNavigationPaintedPixels") > navigation.height() * 10L);
        assertEquals("Visibility requires an onDraw of this geometry",
                snapshot().getInt("frameRevision"), snapshot().getInt("frameDrawnRevision"));
        assertFalse("The preview must never start automatically", snapshot().optBoolean("framePreview"));
        assertFourCorners(navigation, 0);
        for (int i = 0; i < CORNERS.length; i++) {
            corner(i).click();
            final int index = i;
            await("native corner counter " + NAMES[i], 5000,
                    () -> (NAMES[index] + " : 1").equals(corner(index).getText()));
        }
        assertFourCorners(navigation, 1);
        await("frame drawn again after navigation touches", 5000,
                () -> snapshot().optBoolean("frameVisible"));
        File framedScreenshot = new File(evidenceDirectory(), "split-android10.png");
        await("actual surround pixels in the composed screenshot", 5000, () -> {
            if (!device.takeScreenshot(framedScreenshot)) return false;
            Bitmap image = BitmapFactory.decodeFile(framedScreenshot.getAbsolutePath());
            if (image == null) return false;
            boolean visible = isFrameColor(image.getPixel(navigationContent.left + 5, navigationContent.centerY()))
                    && isFrameColor(image.getPixel(navigationContent.right - 6, navigationContent.centerY()))
                    && isFrameColor(image.getPixel(navigationContent.centerX(), navigationContent.top + 5))
                    && isFrameColor(image.getPixel(navigationContent.centerX(), navigationContent.bottom - 6));
            image.recycle();
            return visible;
        });
        writeNamedState("split-frame-active.json", "surround drawn over unchanged native windows");

        // Changing only the decoration must not relaunch, enlarge or shift either app.
        setFrameEnabled(false);
        await("unified frame disabled", 5000, () -> !snapshot().optBoolean("frameVisible")
                && !snapshot().optBoolean("frameEnabled"));
        assertTrue("Native pair remains active without decoration", snapshot().optBoolean("active"));
        assertRectNear("Disabling the frame preserves navigation size", navigation,
                awaitWindow(NAVIGATION, 5000));
        File plainScreenshot = new File(evidenceDirectory(), "split-frame-disabled.png");
        await("compositor removed the surround", 5000, () -> {
            if (!device.takeScreenshot(plainScreenshot)) return false;
            Bitmap image = BitmapFactory.decodeFile(plainScreenshot.getAbsolutePath());
            if (image == null) return false;
            boolean removed = !isFrameColor(image.getPixel(navigationContent.left + 5, navigationContent.centerY()));
            image.recycle();
            return removed;
        });
        assertSubstantialSurround(framedScreenshot, plainScreenshot, navigationContent);
        setFrameEnabled(true);
        await("unified frame restored", 5000, () -> snapshot().optBoolean("frameVisible"));
        assertRectNear("Restoring the frame preserves navigation size", navigation,
                awaitWindow(NAVIGATION, 5000));

        clearAutomationNodeCache();
        final Activity previewHost = panel;
        instrumentation.runOnMainSync(() -> SplitScreenService.previewFrame(previewHost));
        await("explicit five-second preview", 3000,
                () -> snapshot().optBoolean("framePreview") && snapshot().optBoolean("frameVisible"));
        assertTrue("Failed to save preview screenshot", device.takeScreenshot(
                new File(evidenceDirectory(), "split-frame-preview.png")));
        await("preview is removed automatically", 7000, () -> !snapshot().optBoolean("framePreview"));
        await("surround remains after preview", 5000, () -> snapshot().optBoolean("frameVisible"));
        assertRectNear("The preview must not move navigation", navigation, awaitWindow(NAVIGATION, 5000));

        tapPanelButton(panel, "settings", "Réglages");
        PanelSettingsActivity settings = awaitActivity(PanelSettingsActivity.class, 15000);
        assertEquals("Settings must stay in the side panel task", panelTask, taskId(settings));
        assertMultiWindow(settings);
        await("compact settings WebView is rendered", 15000, () -> {
            JSONObject page = readDomElement(settings, "dialogTitle");
            return page.optBoolean("panelSettings") && page.optBoolean("visible")
                    && page.optBoolean("visualStateReady")
                    && "H2".equals(page.optString("tag")) && "Réglages".equals(page.optString("text"));
        });
        await("pair remains active while settings are open", 5000, () -> snapshot().optBoolean("active"));
        Rect settingsNavigation = awaitWindow(NAVIGATION, 5000);
        assertRectNear("Opening settings must not enlarge or move the navigation window", navigation, settingsNavigation);
        assertSideBySide(settingsNavigation, awaitWindow(ROADDECK, 5000));
        assertFourCorners(settingsNavigation, 1);
        await("frame remains available over compact settings", 5000,
                () -> snapshot().optBoolean("frameVisible"));
        assertTrue("Failed to save settings screenshot", device.takeScreenshot(
                new File(evidenceDirectory(), "split-settings.png")));

        // pressBack() waits for a content-change event, which need not accompany
        // activity closure. Check real key injection, then the actual lifecycle below.
        assertTrue("Android Back was not injected", device.pressKeyCode(KeyEvent.KEYCODE_BACK));
        await("settings activity finished", 10000, () -> findActivity(PanelSettingsActivity.class) == null);
        panel = awaitActivity(SidePanelActivity.class, 10000);
        assertEquals("Returning from settings must preserve the panel task", panelTask, taskId(panel));
        assertMultiWindow(panel);
        assertRectNear("Closing settings must preserve the navigation window", navigation,
                awaitWindow(NAVIGATION, 5000));
        assertFourCorners(navigation, 1);

        tapPanelButton(panel, "closeButton", "Fermer les commandes et afficher Waze en grand");
        await("pair closed and navigation restored full screen", 25000, () -> {
            JSONObject state = snapshot();
            // Do not repopulate API29's shared node cache with our own transitioning
            // activity while the engine is closing it. Verify native windows once
            // the engine has finished; its boolean alone still cannot pass this test.
            if (state.optBoolean("active") || state.optBoolean("requested")
                    || !"IDLE".equals(state.optString("state"))) return false;
            Rect bounds = windowBounds(NAVIGATION);
            return bounds != null && bounds.width() >= device.getDisplayWidth() * 0.90;
        });
        Rect fullScreen = awaitWindow(NAVIGATION, 5000);
        assertTrue("The real navigation window must expand after closePair", fullScreen.width() > navigation.width() * 1.4);
        assertTrue("Navigation must occupy almost the full display width", fullScreen.width() >= device.getDisplayWidth() * 0.90);
        assertNull("The panel window must be gone", windowBounds(ROADDECK));
        assertFalse("The frame must be removed after closing the pair", snapshot().optBoolean("frameVisible"));
        assertFalse("The frame bounds must be removed with its window", snapshot().has("frameBounds"));
        assertFourCorners(fullScreen, 1);
        for (int i = 0; i < CORNERS.length; i++) {
            corner(i).click();
            final int index = i;
            await("fullscreen native counter " + NAMES[i], 5000,
                    () -> (NAMES[index] + " : 2").equals(corner(index).getText()));
        }
        assertFourCorners(fullScreen, 2);
        writeState("passed: fixture windows, visible 12dp surround, toggle without resize, corner clicks, preview expiry, settings task, fullscreen return");
    }

    private boolean isFrameColor(int color) {
        return Math.abs(Color.red(color) - 12) <= 3
                && Math.abs(Color.green(color) - 16) <= 3 && Math.abs(Color.blue(color) - 32) <= 3;
    }

    /** The compositor capture, not frameVisible alone, proves a wide visible surround. */
    private void assertSubstantialSurround(File onFile, File offFile, Rect navigation) {
        Bitmap on = BitmapFactory.decodeFile(onFile.getAbsolutePath());
        Bitmap off = BitmapFactory.decodeFile(offFile.getAbsolutePath());
        assertNotNull("Framed capture is readable", on);
        assertNotNull("Plain capture is readable", off);
        try {
            assertEquals(on.getWidth(), off.getWidth());
            assertEquals(on.getHeight(), off.getHeight());
            // Stay clear of rounded corners and compare eight full columns/rows,
            // so the old two-pixel contour cannot pass this regression test.
            Rect[] strips = {
                    new Rect(navigation.left + 2, navigation.top + 40, navigation.left + 10, navigation.bottom - 40),
                    new Rect(navigation.right - 10, navigation.top + 40, navigation.right - 2, navigation.bottom - 40),
                    new Rect(navigation.left + 40, navigation.top + 2, navigation.right - 40, navigation.top + 10),
                    new Rect(navigation.left + 40, navigation.bottom - 10, navigation.right - 40, navigation.bottom - 2)};
            for (int side = 0; side < strips.length; side++) {
                Rect strip = strips[side];
                int changed = 0, dark = 0;
                for (int y = strip.top; y < strip.bottom; y++)
                    for (int x = strip.left; x < strip.right; x++) {
                        int a = on.getPixel(x, y), b = off.getPixel(x, y);
                        if (isFrameColor(a)) dark++;
                        if (Math.abs(Color.red(a) - Color.red(b))
                                + Math.abs(Color.green(a) - Color.green(b))
                                + Math.abs(Color.blue(a) - Color.blue(b)) >= 20) changed++;
                    }
                int area = strip.width() * strip.height();
                assertTrue("A substantial dark surround must be visible on side " + side,
                        dark > area * 0.90);
                assertTrue("The surround must change at least eight pixels of width on side " + side,
                        changed > area * 0.90);
            }
            Rect center = new Rect(navigation);
            center.inset(48, 80);
            int changed = 0;
            for (int y = center.top; y < center.bottom; y++)
                for (int x = center.left; x < center.right; x++)
                    if (on.getPixel(x, y) != off.getPixel(x, y)) changed++;
            assertTrue("The center of the native navigation surface must stay visible and unchanged",
                    changed < center.width() * center.height() * 0.01);
        } finally {
            on.recycle(); off.recycle();
        }
    }

    private Rect union(Rect first, Rect second) {
        Rect result = new Rect(first);
        result.union(second);
        return result;
    }

    private void setFrameEnabled(boolean enabled) {
        clearAutomationNodeCache();
        AtomicReference<Exception> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                new Config(context).save(new JSONObject().put("splitFrame", enabled));
                SplitScreenService.settingsChanged();
            } catch (Exception error) { failure.set(error); }
        });
        assertNull("Could not change the decoration setting", failure.get());
    }

    @After public void recordFinalState() { if (context != null) writeState("instrumentation finished"); }

    private JSONObject snapshot() { return SplitScreenService.snapshot(context); }

    /**
     * Old WebView versions may omit HTML controls from the accessibility tree.
     * The fallback only reads DOM geometry, then injects a real screen touch.
     * It never calls an onclick handler, Native.post, openSettings, or closePair.
     */
    private void tapPanelButton(Activity activity, String id, String description) {
        UiObject2 accessible = device.wait(Until.findObject(By.pkg(ROADDECK).desc(description)), 1500);
        if (accessible == null && "settings".equals(id))
            accessible = device.findObject(By.pkg(ROADDECK).text("Réglages"));
        if (accessible != null && accessible.isEnabled()) {
            Rect target = accessible.getVisibleBounds();
            if (!target.isEmpty()) {
                clearAutomationNodeCache();
                assertTrue("Could not inject native touch for " + id,
                        device.click(target.centerX(), target.centerY()));
                return;
            }
        }
        AtomicReference<JSONObject> measured = new AtomicReference<>();
        await("visible DOM button " + id, 10000, () -> {
            JSONObject page = readDomElement(activity, id);
            measured.set(page);
            return page.optBoolean("visible") && page.optBoolean("hit")
                    && !page.optBoolean("disabled") && "BUTTON".equals(page.optString("tag"));
        });
        JSONObject page = measured.get();
        double dpr = page.optDouble("dpr", 1);
        double scaleX = page.optDouble("nativeWidth") / page.optDouble("viewportWidth");
        double scaleY = page.optDouble("nativeHeight") / page.optDouble("viewportHeight");
        assertTrue("DOM viewport and native WebView scale must agree: " + page,
                Double.isFinite(scaleX) && Double.isFinite(scaleY) && dpr > 0
                        && Math.abs(scaleX - dpr) < Math.max(0.05, dpr * 0.05)
                        && Math.abs(scaleY - dpr) < Math.max(0.05, dpr * 0.05));
        int x = (int) Math.round(page.optDouble("nativeX")
                + (page.optDouble("left") + page.optDouble("width") / 2) * scaleX);
        int y = (int) Math.round(page.optDouble("nativeY")
                + (page.optDouble("top") + page.optDouble("height") / 2) * scaleY);
        assertTrue("Measured button center must be on screen", x >= 0 && y >= 0
                && x < device.getDisplayWidth() && y < device.getDisplayHeight());
        android.util.Log.i("SplitScreenAndroid10Test", "DOM-measured touch id=" + id
                + " x=" + x + " y=" + y + " dpr=" + dpr + " geometry=" + page);
        clearAutomationNodeCache();
        assertTrue("Could not inject measured touch for " + id, device.click(x, y));
    }

    private void clearAutomationNodeCache() {
        // UiAutomation.setServiceInfo clears AccessibilityInteractionClient's cache.
        // Keep the same configuration: only discard nodes read during measurement.
        // Otherwise API29's event thread can hold the shared cache lock while it
        // waits for a node refresh from this same process's UI thread.
        AccessibilityServiceInfo unchanged = automation.getServiceInfo();
        assertNotNull("UiAutomation must remain connected before injecting touch", unchanged);
        automation.setServiceInfo(unchanged);
    }

    /** All JavaScript below is read-only, including the hit-test at the element center. */
    private JSONObject readDomElement(Activity activity, String id) {
        if (domProbe == null || domProbe.activity != activity || !domProbe.id.equals(id))
            domProbe = new DomProbe(activity, id);
        DomProbe probe = domProbe;
        JSONObject previous = probe.result.get();
        // Poll without blocking the test thread or enqueuing duplicate renderer work.
        // The caller's existing deadline is the only timeout; there is no nested 3s abort.
        if (!probe.pending.compareAndSet(false, true)) return previous;
        long requestedAt = SystemClock.uptimeMillis();
        probe.phase = "queued on UI thread";
        String script = "(function(){var e=document.getElementById(" + JSONObject.quote(id) + ");"
                + "var s=document.getElementById('stage');"
                + "var o={panelSettings:!!s&&s.classList.contains('panel-settings'),"
                + "dpr:window.devicePixelRatio||1,viewportWidth:window.innerWidth,viewportHeight:window.innerHeight};"
                + "if(e){var r=e.getBoundingClientRect(),c=window.getComputedStyle(e);"
                + "var x=r.left+r.width/2,y=r.top+r.height/2,h=document.elementFromPoint(x,y);"
                + "o.left=r.left;o.top=r.top;o.width=r.width;o.height=r.height;o.tag=e.tagName;"
                + "o.text=(e.textContent||'').trim();o.disabled=!!e.disabled;"
                + "o.visible=e.getClientRects().length>0&&r.width>0&&r.height>0"
                + "&&c.display!=='none'&&c.visibility!=='hidden'&&Number(c.opacity)!==0"
                + "&&x>=0&&y>=0&&x<window.innerWidth&&y<window.innerHeight;"
                + "o.hit=!!h&&(h===e||e.contains(h));}return JSON.stringify(o);})()";
        activity.runOnUiThread(() -> {
            try {
                WebView web = findWebView(activity.getWindow().getDecorView());
                JSONObject nativeState = new JSONObject().put("id", id)
                        .put("activity", activity.getClass().getSimpleName());
                if (web == null || activity.isFinishing() || activity.isDestroyed()) {
                    probe.phase = "activity or WebView unavailable";
                    probe.result.set(nativeState);
                    probe.pending.set(false);
                    return;
                }
                int[] location = new int[2];
                web.getLocationOnScreen(location);
                nativeState.put("nativeX", location[0]).put("nativeY", location[1])
                        .put("nativeWidth", web.getWidth()).put("nativeHeight", web.getHeight())
                        .put("url", web.getUrl()).put("progress", web.getProgress())
                        .put("viewShown", web.isShown());
                probe.result.set(nativeState);
                if (!web.isShown() || web.getProgress() < 100 || web.getUrl() == null
                        || !web.getUrl().startsWith("https://appassets.androidplatform.net/assets/ui/")) {
                    probe.phase = "waiting for local document load";
                    probe.pending.set(false);
                    return;
                }
                probe.phase = "waiting for DOM callback";
                web.evaluateJavascript(script, encoded -> {
                    try {
                        Object decoded = new JSONTokener(encoded).nextValue();
                        JSONObject page = decoded instanceof String ? new JSONObject((String) decoded)
                                : decoded instanceof JSONObject ? (JSONObject) decoded : new JSONObject();
                        for (java.util.Iterator<String> keys = nativeState.keys(); keys.hasNext();) {
                            String key = keys.next(); page.put(key, nativeState.get(key));
                        }
                        page.put("callbackDelayMs", SystemClock.uptimeMillis() - requestedAt);
                        probe.result.set(page);
                        if (page.optBoolean("visible") && (("dialogTitle".equals(id)
                                && page.optBoolean("panelSettings") && "Réglages".equals(page.optString("text")))
                                || ("launchWaze".equals(id) && "BUTTON".equals(page.optString("tag"))))) {
                            probe.phase = "waiting for visual state acknowledgement";
                            web.postVisualStateCallback(requestedAt, new WebView.VisualStateCallback() {
                                @Override public void onComplete(long requestId) {
                                    try {
                                        probe.result.set(new JSONObject(page.toString()).put("visualStateReady", true));
                                        probe.phase = "DOM and visual state ready";
                                    } catch (Exception error) {
                                        probe.phase = "visual acknowledgement error: " + error;
                                    } finally { probe.pending.set(false); }
                                }
                            });
                        } else {
                            probe.phase = "DOM answered";
                            probe.pending.set(false);
                        }
                    } catch (Exception error) {
                        probe.phase = "DOM query error: " + error;
                        probe.pending.set(false);
                        android.util.Log.w("SplitScreenAndroid10Test", "Could not read DOM geometry", error);
                    }
                });
            } catch (Exception error) {
                probe.phase = "native DOM probe error: " + error;
                probe.pending.set(false);
                android.util.Log.w("SplitScreenAndroid10Test", "Could not schedule DOM query", error);
            }
        });
        return previous;
    }

    private static final class DomProbe {
        final Activity activity;
        final String id;
        final AtomicBoolean pending = new AtomicBoolean();
        final AtomicReference<JSONObject> result = new AtomicReference<>(new JSONObject());
        volatile String phase = "not started";
        DomProbe(Activity activity, String id) { this.activity = activity; this.id = id; }
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private Rect snapshotRect(String key) throws Exception {
        JSONObject box = snapshot().getJSONObject(key);
        return new Rect(box.getInt("left"), box.getInt("top"), box.getInt("right"), box.getInt("bottom"));
    }

    private void assertSideBySide(Rect navigation, Rect side) {
        assertTrue("Waze must be on the left: " + navigation + " / " + side,
                navigation.centerX() < side.centerX());
        assertTrue("Native application windows must not overlap: " + navigation + " / " + side,
                navigation.right <= side.left + 2);
        assertTrue("Navigation must really shrink, not remain behind an overlay",
                navigation.width() < device.getDisplayWidth() * 0.85);
        assertTrue("Navigation window must have useful height", navigation.height() > device.getDisplayHeight() * 0.6);
        assertTrue("RoadDeck must occupy a real window on the right", side.width() > device.getDisplayWidth() * 0.15);
    }

    private void assertFourCorners(Rect bounds, int count) {
        for (int i = 0; i < CORNERS.length; i++) {
            UiObject2 button = corner(i);
            assertEquals("Fixture controls must be native Android buttons", "android.widget.Button", button.getClassName());
            assertTrue("Corner must be an enabled native button: " + CORNERS[i], button.isEnabled() && button.isClickable());
            assertEquals("Counter for " + CORNERS[i], NAMES[i] + " : " + count, button.getText());
            Rect visible = button.getVisibleBounds();
            assertTrue("Corner must remain inside its actual navigation window: " + CORNERS[i],
                    !visible.isEmpty() && bounds.contains(visible));
            boolean left = i == 0 || i == 2;
            boolean top = i < 2;
            assertTrue("Corner must track its horizontal edge after resize: " + CORNERS[i],
                    left ? visible.centerX() < bounds.left + bounds.width() * 0.35
                         : visible.centerX() > bounds.left + bounds.width() * 0.65);
            assertTrue("Corner must track its vertical edge after resize: " + CORNERS[i],
                    top ? visible.centerY() < bounds.top + bounds.height() * 0.30
                        : visible.centerY() > bounds.top + bounds.height() * 0.70);
        }
    }

    private UiObject2 corner(int index) {
        // API29 shares a node cache between this process's accessibility clients.
        // A node cached for RoadDeck can omit resource IDs despite UiAutomation's
        // REPORT_VIEW_IDS flag. The fixture's unique native descriptions remain
        // available; all window, edge, visibility, counter and touch checks stay intact.
        UiObject2 result = device.wait(Until.findObject(By.pkg(NAVIGATION)
                .clazz("android.widget.Button").desc(CORNER_DESCRIPTIONS[index])), 5000);
        assertNotNull("Missing native fixture corner " + CORNERS[index], result);
        return result;
    }

    private void assertRectNear(String message, Rect expected, Rect actual) {
        assertTrue(message + ": expected=" + expected + " actual=" + actual,
                Math.abs(expected.left - actual.left) <= 2 && Math.abs(expected.top - actual.top) <= 2
                        && Math.abs(expected.right - actual.right) <= 2 && Math.abs(expected.bottom - actual.bottom) <= 2);
    }

    private Rect awaitWindow(String packageName, long timeout) {
        await("application window for " + packageName, timeout, () -> windowBounds(packageName) != null);
        return windowBounds(packageName);
    }

    /** Independent Android window geometry; does not trust the engine's active boolean. */
    private Rect windowBounds(String packageName) {
        Rect best = null;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = null;
            try {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                root = window.getRoot();
                if (root == null || root.getPackageName() == null || !packageName.contentEquals(root.getPackageName())) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && (best == null || bounds.width() * bounds.height() > best.width() * best.height())) best = bounds;
            } finally {
                if (root != null) root.recycle();
                window.recycle();
            }
        }
        return best;
    }

    private <T extends Activity> T findActivity(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.PAUSED}) {
                Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage);
                for (Activity activity : activities)
                    if (activity.getClass() == type && !activity.isFinishing() && !activity.isDestroyed())
                        result.set(type.cast(activity));
                if (result.get() != null) return;
            }
        });
        return result.get();
    }

    private <T extends Activity> T awaitActivity(Class<T> type, long timeout) {
        await(type.getSimpleName() + " visible", timeout, () -> findActivity(type) != null);
        return findActivity(type);
    }

    private int taskId(Activity activity) {
        int[] result = {-1};
        instrumentation.runOnMainSync(() -> result[0] = activity.getTaskId());
        return result[0];
    }

    private void assertMultiWindow(Activity activity) {
        AtomicBoolean value = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> value.set(activity.isInMultiWindowMode()));
        assertTrue(activity.getClass().getSimpleName() + " must be in Android multi-window mode", value.get());
    }

    private interface Condition { boolean evaluate(); }

    private void await(String description, long timeout, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean satisfied = condition.evaluate();
            long remaining = deadline - SystemClock.uptimeMillis();
            if (satisfied && remaining >= 0) return;
            if (remaining <= 0) break;
            SystemClock.sleep(Math.min(100, remaining));
        }
        String domState = domProbe == null ? "none" : domProbe.phase + " " + domProbe.result.get();
        fail("Timed out: " + description + ". Engine snapshot: " + snapshot() + ". Last DOM probe: " + domState);
    }

    private File evidenceDirectory() {
        File directory = context.getExternalFilesDir(null);
        assertNotNull("External files directory unavailable", directory);
        assertTrue("Cannot create evidence directory", directory.isDirectory() || directory.mkdirs());
        return directory;
    }

    private void writeState(String note) {
        writeNamedState("split-test-state.json", note);
    }

    private void writeNamedState(String name, String note) {
        try {
            JSONObject state = snapshot().put("testNote", note).put("fixture", true)
                    .put("api", Build.VERSION.SDK_INT).put("realWazeTested", false);
            if (testedNavigationContent != null) {
                Rect b = testedNavigationContent;
                state.put("fixtureContentBounds", new JSONObject().put("left", b.left).put("top", b.top)
                        .put("right", b.right).put("bottom", b.bottom));
            }
            try (FileOutputStream output = new FileOutputStream(new File(evidenceDirectory(), name))) {
                output.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            android.util.Log.e("SplitScreenAndroid10Test", "Could not save test state", error);
        }
    }
}
