package fr.roaddeck.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pixel evidence for the passive surround, independent of window-manager placement. */
@RunWith(AndroidJUnit4.class)
public final class SplitFrameRenderingTest {
    @Test public void surroundPaintsAllFourMarginsAndLeavesRoundedOpeningTransparent()
            throws Throwable {
        onMain(() -> {
            try (Frame frame = new Frame()) {
                Bitmap bitmap = frame.render(Collections.emptyList(), false);
                int middleX = frame.navigation.width() / 2;
                int middleY = frame.navigation.height() / 2;
                assertOpaque(bitmap, frame.dp(6), middleY);
                assertOpaque(bitmap, middleX, frame.dp(6));
                assertOpaque(bitmap, frame.navigation.width() - frame.dp(6), middleY);
                assertOpaque(bitmap, middleX, frame.navigation.height() - frame.dp(6));

                // Inside the nominal rectangular opening, the rounded corner remains filled.
                assertOpaque(bitmap, frame.dp(14), frame.dp(14));
                assertEquals("The rounded opening must reveal Waze", 0,
                        Color.alpha(bitmap.getPixel(frame.dp(32), frame.dp(32))));
                assertTransparent(bitmap, new Rect(frame.dp(40), frame.dp(40),
                        frame.navigation.width() - frame.dp(40),
                        frame.navigation.height() - frame.dp(40)));
                assertTrue("The left surround must contain painted area",
                        frame.view.navigationPaintedPixels() > 0);
            }
        });
    }

    @Test public void exclusionsClearOnlyTheirPixelsAndFullExclusionReportsNoPaint()
            throws Throwable {
        onMain(() -> {
            try (Frame frame = new Frame()) {
                Bitmap baseline = frame.render(Collections.emptyList(), false);
                long navigationBefore = frame.view.navigationPaintedPixels();
                long totalBefore = frame.view.paintedPixels();
                Rect localCutout = new Rect(0, frame.dp(80), frame.dp(8), frame.dp(120));
                SplitGeometry.Box screenCutout = new SplitGeometry.Box(
                        frame.area.left + localCutout.left, frame.area.top + localCutout.top,
                        frame.area.left + localCutout.right, frame.area.top + localCutout.bottom);
                Bitmap clipped = frame.render(Collections.singletonList(screenCutout), false);

                assertTransparent(clipped, localCutout);
                assertIdenticalOutside("local-exclusion", baseline, clipped, localCutout);
                long removed = (long) localCutout.width() * localCutout.height();
                assertEquals("A small obstruction must remove only its part of the left margin",
                        navigationBefore - removed, frame.view.navigationPaintedPixels());
                assertEquals(totalBefore - removed, frame.view.paintedPixels());

                Bitmap fullyClipped = frame.render(Collections.singletonList(frame.area), false);
                assertTransparent(fullyClipped,
                        new Rect(0, 0, fullyClipped.getWidth(), fullyClipped.getHeight()));
                assertTrue("An empty draw must still complete its geometry revision",
                        frame.view.hasDrawnGeometry());
                assertEquals("An attached but fully clipped frame must not claim left-side paint",
                        0L, frame.view.navigationPaintedPixels());
                assertEquals(0L, frame.view.paintedPixels());
                assertEquals(0L, frame.view.gapPaintedPixels());
            }
        });
    }

    @Test public void previewAddsOnlyACentralBadgeAndDisappearsWhenDisabled() throws Throwable {
        onMain(() -> {
            try (Frame frame = new Frame()) {
                Bitmap baseline = frame.render(Collections.emptyList(), false);
                Bitmap preview = frame.render(Collections.emptyList(), true);
                int middleX = frame.navigation.width() / 2;
                int middleY = frame.navigation.height() / 2;
                assertEquals(0, Color.alpha(baseline.getPixel(middleX, middleY)));
                assertOpaque(preview, middleX, middleY);
                Rect badgeArea = new Rect(frame.dp(12), middleY - frame.dp(20),
                        frame.navigation.width() - frame.dp(12), middleY + frame.dp(20));
                assertIdenticalOutside("preview", baseline, preview, badgeArea);

                Bitmap afterPreview = frame.render(Collections.emptyList(), false);
                assertTrue("Disabling the preview must restore the entire transparent opening",
                        baseline.sameAs(afterPreview));
            }
        });
    }

    @Test public void systemBarsMoveTheSurroundInsideVisibleContentRegardlessOfExclusionOrder()
            throws Throwable {
        onMain(() -> {
            try (Frame frame = new Frame()) {
                SplitGeometry.Box topBar = new SplitGeometry.Box(frame.area.left, frame.area.top,
                        frame.area.right, frame.area.top + frame.dp(24));
                SplitGeometry.Box bottomBar = new SplitGeometry.Box(frame.area.left,
                        frame.area.bottom - frame.dp(28), frame.area.right, frame.area.bottom);
                // This edge only spans the available height after both horizontal bars are removed.
                SplitGeometry.Box leftBar = new SplitGeometry.Box(frame.area.left, topBar.bottom,
                        frame.area.left + frame.dp(16), bottomBar.top);
                List<SplitGeometry.Box> exclusions = Arrays.asList(topBar, bottomBar, leftBar);
                Bitmap forward = frame.render(exclusions, false);
                SplitGeometry.Box expectedNavigation = new SplitGeometry.Box(leftBar.right,
                        topBar.bottom, frame.navigation.right, bottomBar.top);
                assertEquals(expectedNavigation, frame.view.navigationBounds());
                int middleX = frame.navigation.width() / 2;
                assertOpaque(forward, middleX, frame.dp(24) + frame.dp(6));
                assertOpaque(forward, middleX, frame.area.height() - frame.dp(28) - frame.dp(6));
                assertOpaque(forward, frame.dp(16) + frame.dp(6), frame.area.height() / 2);
                assertTransparent(forward, new Rect(0, 0, frame.area.width(), frame.dp(24)));
                assertTransparent(forward, new Rect(0, frame.area.height() - frame.dp(28),
                        frame.area.width(), frame.area.height()));
                assertTransparent(forward, new Rect(0, 0, frame.dp(16), frame.area.height()));

                Bitmap reversed = frame.render(Arrays.asList(leftBar, bottomBar, topBar), false);
                assertEquals(expectedNavigation, frame.view.navigationBounds());
                assertTrue("Exclusion ordering must not move the frame or change its pixels",
                        forward.sameAs(reversed));
                SplitGeometry.Box floatingControl = new SplitGeometry.Box(expectedNavigation.left,
                        frame.area.top + frame.dp(80), expectedNavigation.left + frame.dp(8),
                        frame.area.top + frame.dp(120));
                assertEquals("A small floating control must not shift the whole navigation frame",
                        expectedNavigation, SplitFrameView.visibleFrameBounds(frame.navigation,
                                Arrays.asList(topBar, bottomBar, leftBar, floatingControl)));
                assertNull("A fully covered app must have no available frame bounds",
                        SplitFrameView.visibleFrameBounds(frame.navigation,
                                Collections.singletonList(frame.area)));
            }
        });
    }

    private static void assertOpaque(Bitmap bitmap, int x, int y) {
        assertEquals("Expected visible surround or badge at " + x + "," + y,
                255, Color.alpha(bitmap.getPixel(x, y)));
    }

    private static void assertTransparent(Bitmap bitmap, Rect region) {
        int[] pixels = new int[region.width() * region.height()];
        bitmap.getPixels(pixels, 0, region.width(), region.left, region.top,
                region.width(), region.height());
        for (int i = 0; i < pixels.length; i++) {
            if (Color.alpha(pixels[i]) != 0) {
                fail("Unexpected painted pixel at " + (region.left + i % region.width())
                        + "," + (region.top + i / region.width()));
            }
        }
    }

    private static void assertIdenticalOutside(String label, Bitmap expected, Bitmap actual,
            Rect changedArea) {
        int width = expected.getWidth(), height = expected.getHeight();
        int[] before = new int[width * height], after = new int[width * height];
        expected.getPixels(before, 0, width, 0, 0, width, height);
        actual.getPixels(after, 0, width, 0, 0, width, height);
        int changed = 0, partialAlpha = 0, bothOpaque = 0;
        int flatOpaque = 0, flatTransparent = 0;
        int maxAlpha = 0, maxRed = 0, maxGreen = 0, maxBlue = 0;
        StringBuilder samples = new StringBuilder();
        for (int i = 0; i < before.length; i++) {
            int x = i % width, y = i / width;
            if (!changedArea.contains(x, y) && before[i] != after[i]) {
                changed++;
                int a0 = Color.alpha(before[i]), a1 = Color.alpha(after[i]);
                if ((a0 > 0 && a0 < 255) || (a1 > 0 && a1 < 255)) partialAlpha++;
                if (a0 == 255 && a1 == 255) bothOpaque++;
                boolean flat = uniformNeighborhood(before, width, height, x, y);
                if (flat && a0 == 255) flatOpaque++;
                if (flat && a0 == 0) flatTransparent++;
                maxAlpha = Math.max(maxAlpha, Math.abs(a0 - a1));
                maxRed = Math.max(maxRed, Math.abs(Color.red(before[i]) - Color.red(after[i])));
                maxGreen = Math.max(maxGreen, Math.abs(Color.green(before[i]) - Color.green(after[i])));
                maxBlue = Math.max(maxBlue, Math.abs(Color.blue(before[i]) - Color.blue(after[i])));
                if (changed <= 24) samples.append(x).append(',').append(y)
                        .append(" before=").append(Integer.toHexString(before[i]))
                        .append(" after=").append(Integer.toHexString(after[i]))
                        .append(" flat3x3=").append(flat).append('\n');
            }
        }
        if (changed != 0) {
            String details = "changedOutside=" + changed + " partialAlpha=" + partialAlpha
                    + " bothOpaque=" + bothOpaque + " flatOpaque=" + flatOpaque
                    + " flatTransparent=" + flatTransparent + " maxDeltaARGB="
                    + maxAlpha + "," + maxRed + "," + maxGreen + "," + maxBlue
                    + " permittedRegion=" + changedArea + "\n" + samples;
            saveComparison(label, expected, actual, details);
            fail("Unrelated decoration changed: " + label + "\n" + details);
        }
    }

    private static boolean uniformNeighborhood(int[] pixels, int width, int height, int x, int y) {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return false;
        int expected = pixels[y * width + x];
        for (int yy = y - 1; yy <= y + 1; yy++) {
            for (int xx = x - 1; xx <= x + 1; xx++) {
                if (pixels[yy * width + xx] != expected) return false;
            }
        }
        return true;
    }

    private static void saveComparison(String label, Bitmap before, Bitmap after, String details) {
        try {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File directory = context.getExternalFilesDir(null);
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs()))
                throw new IllegalStateException("Bitmap evidence directory unavailable");
            String prefix = "bitmap-" + label;
            try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-before.png"))) {
                if (!before.compress(Bitmap.CompressFormat.PNG, 100, output))
                    throw new IllegalStateException("Could not encode before bitmap");
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-after.png"))) {
                if (!after.compress(Bitmap.CompressFormat.PNG, 100, output))
                    throw new IllegalStateException("Could not encode after bitmap");
            }
            try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-diff.txt"))) {
                output.write(details.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            android.util.Log.e("SplitFrameRenderingTest", "Could not save comparison evidence", error);
        }
    }

    private interface MainAction { void run() throws Throwable; }

    private static void onMain(MainAction action) throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
    }

    private static final class Frame implements AutoCloseable {
        final float density;
        final SplitFrameView view;
        final SplitGeometry.Box area, navigation, panel, gap;
        final List<Bitmap> bitmaps = new ArrayList<>();

        Frame() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            density = context.getResources().getDisplayMetrics().density;
            // A non-zero screen origin catches local/screen-coordinate clipping mistakes.
            int left = dp(19), top = dp(31), height = dp(200);
            navigation = new SplitGeometry.Box(left, top, left + dp(280), top + height);
            gap = new SplitGeometry.Box(navigation.right, top,
                    navigation.right + dp(8), top + height);
            panel = new SplitGeometry.Box(gap.right, top, gap.right + dp(220), top + height);
            area = new SplitGeometry.Box(left, top, panel.right, top + height);
            view = new SplitFrameView(context, null);
            view.measure(View.MeasureSpec.makeMeasureSpec(area.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(area.height(), View.MeasureSpec.EXACTLY));
            view.layout(0, 0, area.width(), area.height());
        }

        int dp(int value) { return Math.round(value * density); }

        Bitmap render(List<SplitGeometry.Box> exclusions, boolean preview) {
            view.setGeometry(area, navigation, panel, gap, true, exclusions,
                    12, 12, 12, 12, preview);
            Bitmap bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            bitmaps.add(bitmap);
            bitmap.eraseColor(Color.TRANSPARENT);
            view.draw(new Canvas(bitmap));
            return bitmap;
        }

        @Override public void close() {
            for (Bitmap bitmap : bitmaps) bitmap.recycle();
        }
    }
}
