package fr.roaddeck.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Software renderer evidence only; window composition is checked by the split-screen test. */
@RunWith(AndroidJUnit4.class)
public final class StartupLoadingRenderingTest {
    @Test public void curtainIsOpaqueAndCancelRemainsAccessibleAtThreeAspectRatios()
            throws Throwable {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            int[][] sizes = {{640, 360}, {360, 640}, {320, 260}};
            String[] labels = {"landscape", "portrait", "compact"};
            for (int index = 0; index < sizes.length; index++) {
                AtomicInteger cancelled = new AtomicInteger();
                StartupLoadingView view = new StartupLoadingView(context, cancelled::incrementAndGet);
                view.setStage("Mise en place des fenêtres…");
                int width = Math.round(sizes[index][0] * density);
                int height = Math.round(sizes[index][1] * density);
                Bitmap bitmap = render(view, width, height, 0.17f);
                try {
                    save("bitmap-startup-" + labels[index] + ".png", bitmap);
                    assertOpaque(bitmap);
                    Button cancel = view.cancelButtonForTesting();
                    Rect buttonBounds = new Rect(0, 0, cancel.getWidth(), cancel.getHeight());
                    view.offsetDescendantRectToMyCoords(cancel, buttonBounds);
                    assertTrue("Cancel needs a 48 dp high touch target",
                            buttonBounds.height() >= Math.round(48 * density));
                    assertTrue("Cancel must remain completely on screen: " + buttonBounds,
                            new Rect(0, 0, width, height).contains(buttonBounds));
                    assertEquals("Annuler le démarrage", cancel.getContentDescription());
                    cancel.performClick();
                    cancel.performClick();
                    assertEquals("A cancel request is dispatched once", 1, cancelled.get());
                    assertFalse("An unattached renderer must not own any animation callback",
                            view.animationScheduledForTesting());
                    view.destroy();
                    assertFalse(view.animationScheduledForTesting());
                    cancel.performClick();
                    assertEquals("Destroyed views cannot dispatch cancellation again",
                            1, cancelled.get());
                } finally {
                    view.destroy();
                    bitmap.recycle();
                }
            }
        });
    }

    @Test public void indeterminateAnimationChangesItsPixelsWithoutMovingTextOrCancel()
            throws Throwable {
        onMain(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            int width = Math.round(640 * density), height = Math.round(360 * density);
            StartupLoadingView view = new StartupLoadingView(context, null);
            view.setStage("Ouverture de Waze…");
            Bitmap before = render(view, width, height, 0.0f);
            Bitmap repeated = render(view, width, height, 0.0f);
            Button cancel = view.cancelButtonForTesting();
            Rect beforeBounds = new Rect(0, 0, cancel.getWidth(), cancel.getHeight());
            view.offsetDescendantRectToMyCoords(cancel, beforeBounds);
            Bitmap after = render(view, width, height, 0.31f);
            try {
                save("bitmap-startup-animation-before.png", before);
                save("bitmap-startup-animation-repeated.png", repeated);
                save("bitmap-startup-animation-after.png", after);
                assertOpaque(before);
                assertTrue("Redrawing the same phase must not change paint alpha or layout",
                        before.sameAs(repeated));
                assertOpaque(after);
                int[] a = new int[width * height], b = new int[width * height];
                before.getPixels(a, 0, width, 0, 0, width, height);
                after.getPixels(b, 0, width, 0, 0, width, height);
                int changed = 0;
                for (int i = 0; i < a.length; i++) {
                    if (a[i] != b[i]) changed++;
                    int x = i % width, y = i / width;
                    if (beforeBounds.contains(x, y)) assertEquals(
                            "Animation must not change cancel pixels", a[i], b[i]);
                }
                assertTrue("Animation must visibly change more than a few pixels", changed > 100);
                assertTrue("The whole loading page must not blink", changed < width * height / 10);
                Rect afterBounds = new Rect(0, 0, cancel.getWidth(), cancel.getHeight());
                view.offsetDescendantRectToMyCoords(cancel, afterBounds);
                assertEquals("The action must not move with the animation", beforeBounds, afterBounds);
            } finally {
                view.destroy();
                before.recycle();
                repeated.recycle();
                after.recycle();
            }
        });
    }

    private static Bitmap render(StartupLoadingView view, int width, int height, float phase) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        view.setAnimationPhaseForTesting(phase);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static void assertOpaque(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                bitmap.getWidth(), bitmap.getHeight());
        for (int index = 0; index < pixels.length; index++) {
            if (Color.alpha(pixels[index]) != 255) assertEquals(
                    "The loading curtain must not reveal setup below at "
                            + (index % bitmap.getWidth()) + "," + (index / bitmap.getWidth()),
                    255, Color.alpha(pixels[index]));
        }
    }

    private static void save(String name, Bitmap bitmap) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir(null);
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs()))
            throw new IllegalStateException("Bitmap evidence directory unavailable");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new IllegalStateException("Could not encode startup bitmap");
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
}
