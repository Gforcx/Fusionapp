package fr.roaddeck.launcher;

import java.util.Random;
import org.junit.Test;

/** Regression checks for the failure that prompted this version: masking Waze instead of resizing it. */
public final class SplitGeometryTest {
    private static int assertions;

    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private static SplitGeometry.Box box(int l, int t, int r, int b) {
        return new SplitGeometry.Box(l, t, r, b);
    }

    private static SplitGeometry.Pair pair(SplitGeometry.Box waze, SplitGeometry.Box panel) {
        return SplitGeometry.pair(waze, panel, 1024, 600, 48);
    }

    @Test public void nativeSplitNeverMasksEitherApplication() { runChecks(); }

    public static void main(String[] args) { runChecks(); }

    private static void runChecks() {
        assertions = 0;
        SplitGeometry.Box waze = box(0, 24, 600, 552);
        SplitGeometry.Box panel = box(610, 24, 1024, 552);
        SplitGeometry.Pair accepted = pair(waze, panel);
        check(accepted.arrangement == SplitGeometry.WAZE_LEFT, "Measured adjacent activities accepted");
        check(accepted.gap.equals(box(600, 24, 610, 552)), "Only the real 10px gap is covered");
        check(!accepted.gap.intersects(waze), "All Waze controls remain outside mask");
        check(!accepted.gap.intersects(panel), "RoadDeck controls remain outside mask");
        check(accepted.gap.top == 24 && accepted.gap.bottom == 552, "System bars excluded");

        SplitGeometry.Pair reverse = pair(panel, waze);
        check(reverse.arrangement == SplitGeometry.WAZE_RIGHT, "Wrong side detected for bounded swap");
        check(reverse.gap.equals(accepted.gap), "Reverse detection does not invent a new gap");

        SplitGeometry.Pair touching = pair(waze, box(600, 24, 1024, 552));
        check(touching.arrangement == SplitGeometry.WAZE_LEFT && touching.gap == null,
                "Touching windows require no overlay");
        check(pair(box(0, 0, 1024, 600), box(680, 0, 1024, 600)).arrangement == SplitGeometry.NONE,
                "Old full-width Waze plus overlay is never reported as a split");
        check(pair(waze, box(590, 24, 1024, 552)).arrangement == SplitGeometry.NONE,
                "Even 10px of overlap is rejected");
        check(pair(waze, box(649, 24, 1024, 552)).arrangement == SplitGeometry.NONE,
                "Large unknown empty space is not a divider");
        check(pair(waze, box(648, 24, 1024, 552)).gap.width() == 48,
                "Maximum measured gap is accepted exactly");
        check(pair(null, panel).arrangement == SplitGeometry.NONE, "Missing Waze rejected");
        check(pair(waze, null).arrangement == SplitGeometry.NONE, "Missing panel rejected");
        check(pair(box(-1, 24, 600, 552), panel).arrangement == SplitGeometry.NONE,
                "Off-display Waze rejected");
        check(pair(waze, box(610, 24, 1025, 552)).arrangement == SplitGeometry.NONE,
                "Off-display panel rejected");
        check(pair(box(0, 0, 600, 250), box(610, 350, 1024, 600)).arrangement == SplitGeometry.NONE,
                "Unrelated diagonal windows rejected");
        check(pair(box(0, 0, 1024, 295), box(0, 305, 1024, 600)).arrangement == SplitGeometry.NONE,
                "Top-bottom split is not claimed as Waze-left");
        check(pair(box(0, 0, 160, 600), box(170, 0, 1024, 600)).arrangement == SplitGeometry.NONE,
                "Small popup is not accepted as Waze's full activity");
        check(SplitGeometry.pair(waze, panel, 600, 1024, 48).arrangement == SplitGeometry.NONE,
                "Portrait display rejected for this landscape mode");
        check(SplitGeometry.pair(waze, panel, 1024, 600, -1).arrangement == SplitGeometry.NONE,
                "Negative maximum divider gap rejected");
        check(!SplitGeometry.inside(box(20, 20, 10, 10), 1024, 600), "Inverted rectangle rejected");
        check(SplitGeometry.fullWidth(box(48, 24, 1024, 600), 1024, 600),
                "Fullscreen with navigation insets is recognised");
        check(!SplitGeometry.fullWidth(waze, 1024, 600), "Reduced Waze is not fullscreen");
        check(SplitGeometry.reduced(waze, 1024, 600), "Reduced native activity recognised");
        check(SplitGeometry.onLeft(waze, 1024) && !SplitGeometry.onLeft(panel, 1024),
                "Physical left and right are distinguished");

        // Actual API29 CI regression: Recents minimizes the primary into 104px
        // at the right edge until a secondary app is launched. That is progress,
        // not failure, but it must never be reported as an active Waze/RoadDeck pair.
        SplitGeometry.Box rightPeek = box(1176, 0, 1280, 720);
        SplitGeometry.Box leftPeek = box(0, 0, 104, 720);
        check(SplitGeometry.dockTransition(rightPeek, 1280, 720), "Right dock peek advances setup");
        check(SplitGeometry.dockTransition(leftPeek, 1280, 720), "Left dock peek advances setup");
        check(!SplitGeometry.onLeft(rightPeek, 1280) && SplitGeometry.onLeft(leftPeek, 1280),
                "Peek still identifies the correct physical side");
        check(!SplitGeometry.reduced(rightPeek, 1280, 720), "Peek is not a usable full activity");
        check(SplitGeometry.pair(rightPeek, box(0, 0, 1166, 720), 1280, 720, 48).arrangement
                == SplitGeometry.NONE, "Waze peek can never activate or receive a divider mask");
        check(SplitGeometry.pair(box(0, 0, 1166, 720), rightPeek, 1280, 720, 48).arrangement
                == SplitGeometry.NONE, "Panel peek can never activate or receive a divider mask");
        check(!SplitGeometry.dockTransition(box(0, 0, 1280, 720), 1280, 720),
                "Fullscreen app does not prove a dock transition");
        check(!SplitGeometry.dockTransition(box(1280, 0, 1280, 720), 1280, 720),
                "Zero-width disappeared app does not prove a dock transition");
        check(!SplitGeometry.dockTransition(box(1270, 0, 1290, 720), 1280, 720),
                "Off-screen transitional surface rejected");
        check(!SplitGeometry.dockTransition(null, 1280, 720), "Missing transitional surface rejected");

        // Across common head-unit sizes, every accepted cover must be entirely outside both apps.
        Random random = new Random(0x52444cL);
        int[] widths = {800, 1024, 1280, 1920};
        int[] heights = {480, 600, 720, 1080};
        for (int i = 0; i < 2000; i++) {
            int index = random.nextInt(widths.length), width = widths[index], height = heights[index];
            int inset = random.nextInt(30), top = random.nextInt(40), bottom = height - random.nextInt(40);
            int gap = random.nextInt(49), dividing = width / 3 + random.nextInt(width / 3);
            SplitGeometry.Box left = box(inset, top, dividing, bottom);
            SplitGeometry.Box right = box(dividing + gap, top, width - inset, bottom);
            SplitGeometry.Pair result = SplitGeometry.pair(left, right, width, height, 48);
            check(result.arrangement == SplitGeometry.WAZE_LEFT, "Valid measured pair " + i);
            if (gap == 0) {
                check(result.gap == null, "No zero-width overlay " + i);
            } else {
                check(result.gap.left == left.right && result.gap.right == right.left,
                        "No invented or expanded horizontal bounds " + i);
                check(result.gap.top == top && result.gap.bottom == bottom,
                        "Exact shared height " + i);
                check(!result.gap.intersects(left) && !result.gap.intersects(right),
                        "No app content masked " + i);
            }
        }
        System.out.println("PASS: " + assertions + " native split geometry assertions");
    }
}
