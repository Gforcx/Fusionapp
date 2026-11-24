package fr.roaddeck.launcher;

import static org.junit.Assert.assertEquals;
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
import java.util.ArrayList;
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
                assertIdenticalOutside(baseline, clipped, localCutout);
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
                assertIdenticalOutside(baseline, preview, badgeArea);

                Bitmap afterPreview = frame.render(Collections.emptyList(), false);
                assertTrue("Disabling the preview must restore the entire transparent opening",
                        baseline.sameAs(afterPreview));
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

    private static void assertIdenticalOutside(Bitmap expected, Bitmap actual, Rect changedArea) {
        int width = expected.getWidth(), height = expected.getHeight();
        int[] before = new int[width * height], after = new int[width * height];
        expected.getPixels(before, 0, width, 0, 0, width, height);
        actual.getPixels(after, 0, width, 0, 0, width, height);
        for (int i = 0; i < before.length; i++) {
            int x = i % width, y = i / width;
            if (!changedArea.contains(x, y) && before[i] != after[i]) {
                fail("Unrelated decoration changed at " + x + "," + y);
            }
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
