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
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
    private Instrumentation instrumentation;
    private Context context;
    private UiDevice device;
    private UiAutomation automation;

    @Rule public final TestWatcher evidence = new TestWatcher() {
        @Override protected void failed(Throwable error, Description description) {
            if (device != null && context != null) {
                device.takeScreenshot(new File(evidenceDirectory(), "split-failure.png"));
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
        AtomicBoolean accepted = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> accepted.set(SplitScreenService.begin(main)));
        assertTrue("Split request rejected: " + snapshot(), accepted.get());
        // The engine can spend 55 seconds resolving an OEM's initial split orientation.
        await("native split active", 65000, () -> snapshot().optBoolean("active"));

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
        assertFourCorners(navigation, 0);
        for (int i = 0; i < CORNERS.length; i++) {
            corner(i).click();
            final int index = i;
            await("native corner counter " + NAMES[i], 5000,
                    () -> (NAMES[index] + " : 1").equals(corner(index).getText()));
        }
        assertFourCorners(navigation, 1);
        assertTrue("Failed to save split screenshot", device.takeScreenshot(
                new File(evidenceDirectory(), "split-android10.png")));

        UiObject2 settingsButton = device.wait(Until.findObject(By.pkg(ROADDECK).desc("Réglages")), 10000);
        assertNotNull("The side panel settings button must be accessible", settingsButton);
        settingsButton.click();
        PanelSettingsActivity settings = awaitActivity(PanelSettingsActivity.class, 15000);
        assertEquals("Settings must stay in the side panel task", panelTask, taskId(settings));
        assertMultiWindow(settings);
        await("settings WebView is rendered", 15000,
                () -> device.hasObject(By.pkg(ROADDECK).text("Réglages")));
        await("pair remains active while settings are open", 5000, () -> snapshot().optBoolean("active"));
        Rect settingsNavigation = awaitWindow(NAVIGATION, 5000);
        assertRectNear("Opening settings must not enlarge or move the navigation window", navigation, settingsNavigation);
        assertSideBySide(settingsNavigation, awaitWindow(ROADDECK, 5000));
        assertFourCorners(settingsNavigation, 1);
        assertTrue("Failed to save settings screenshot", device.takeScreenshot(
                new File(evidenceDirectory(), "split-settings.png")));

        // Exercise the compact settings Back handler and closePanelSettings bridge.
        assertTrue("Android Back was not injected", device.pressBack());
        await("settings activity finished", 10000, () -> findActivity(PanelSettingsActivity.class) == null);
        panel = awaitActivity(SidePanelActivity.class, 10000);
        assertEquals("Returning from settings must preserve the panel task", panelTask, taskId(panel));
        assertMultiWindow(panel);
        assertRectNear("Closing settings must preserve the navigation window", navigation,
                awaitWindow(NAVIGATION, 5000));
        assertFourCorners(navigation, 1);

        UiObject2 closeButton = device.wait(Until.findObject(By.pkg(ROADDECK)
                .desc("Fermer les commandes et afficher Waze en grand")), 10000);
        assertNotNull("The closePair action must be accessible", closeButton);
        closeButton.click();
        await("pair closed and navigation restored full screen", 25000, () -> {
            JSONObject state = snapshot();
            Rect bounds = windowBounds(NAVIGATION);
            return !state.optBoolean("active") && !state.optBoolean("requested")
                    && "IDLE".equals(state.optString("state"))
                    && bounds != null && bounds.width() >= device.getDisplayWidth() * 0.90;
        });
        Rect fullScreen = awaitWindow(NAVIGATION, 5000);
        assertTrue("The real navigation window must expand after closePair", fullScreen.width() > navigation.width() * 1.4);
        assertTrue("Navigation must occupy almost the full display width", fullScreen.width() >= device.getDisplayWidth() * 0.90);
        assertNull("The panel window must be gone", windowBounds(ROADDECK));
        assertFourCorners(fullScreen, 1);
        for (int i = 0; i < CORNERS.length; i++) {
            corner(i).click();
            final int index = i;
            await("fullscreen native counter " + NAMES[i], 5000,
                    () -> (NAMES[index] + " : 2").equals(corner(index).getText()));
        }
        assertFourCorners(fullScreen, 2);
        writeState("passed: fixture windows, corner clicks, settings task, fullscreen return");
    }

    @After public void recordFinalState() { if (context != null) writeState("instrumentation finished"); }

    private JSONObject snapshot() { return SplitScreenService.snapshot(context); }

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
        UiObject2 result = device.wait(Until.findObject(By.res(NAVIGATION, CORNERS[index])), 5000);
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
        do {
            if (condition.evaluate()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out: " + description + ". Engine snapshot: " + snapshot());
    }

    private File evidenceDirectory() {
        File directory = context.getExternalFilesDir(null);
        assertNotNull("External files directory unavailable", directory);
        assertTrue("Cannot create evidence directory", directory.isDirectory() || directory.mkdirs());
        return directory;
    }

    private void writeState(String note) {
        try {
            JSONObject state = snapshot().put("testNote", note).put("fixture", true)
                    .put("api", Build.VERSION.SDK_INT).put("realWazeTested", false);
            try (FileOutputStream output = new FileOutputStream(new File(evidenceDirectory(), "split-test-state.json"))) {
                output.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            android.util.Log.e("SplitScreenAndroid10Test", "Could not save test state", error);
        }
    }
}
