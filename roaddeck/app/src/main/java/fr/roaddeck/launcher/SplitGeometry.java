package fr.roaddeck.launcher;

/** Geometry only. No Android dependencies, screen capture, or guessed overlay width. */
final class SplitGeometry {
    static final int NONE = 0;
    static final int WAZE_LEFT = 1;
    static final int WAZE_RIGHT = 2;

    static final class Box {
        final int left, top, right, bottom;

        Box(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
        boolean valid() { return width() > 0 && height() > 0; }
        long area() { return valid() ? (long) width() * height() : 0; }
        boolean intersects(Box other) {
            return other != null && left < other.right && right > other.left
                    && top < other.bottom && bottom > other.top;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Box)) return false;
            Box b = (Box) other;
            return left == b.left && top == b.top && right == b.right && bottom == b.bottom;
        }

        @Override public int hashCode() {
            int value = left;
            value = 31 * value + top;
            value = 31 * value + right;
            return 31 * value + bottom;
        }
    }

    static final class Pair {
        final int arrangement;
        final Box gap;
        Pair(int arrangement, Box gap) {
            this.arrangement = arrangement;
            this.gap = gap;
        }
    }

    private SplitGeometry() {}

    static boolean inside(Box box, int width, int height) {
        return box != null && box.valid() && width > 0 && height > 0
                && box.left >= 0 && box.top >= 0 && box.right <= width && box.bottom <= height;
    }

    static boolean reduced(Box box, int width, int height) {
        return inside(box, width, height) && width >= height
                && (long) box.width() * 6 >= width
                && (long) box.width() * 100 <= (long) width * 85
                && (long) box.height() * 100 >= (long) height * 40;
    }

    static boolean fullWidth(Box box, int width, int height) {
        return inside(box, width, height)
                && (long) box.width() * 100 >= (long) width * 85
                && (long) box.height() * 100 >= (long) height * 40;
    }

    static boolean onLeft(Box box, int width) {
        return box != null && (long) box.left + box.right < width;
    }

    /**
     * Accept only genuinely adjacent, substantial application windows. The divider's
     * own touch window can overlap app content, so its rectangle is never a mask.
     */
    static Pair pair(Box waze, Box panel, int width, int height, int maximumGap) {
        if (!reduced(waze, width, height) || !reduced(panel, width, height)
                || maximumGap < 0 || waze.intersects(panel)) return new Pair(NONE, null);

        Box left, right;
        int arrangement;
        if (waze.right <= panel.left) {
            left = waze; right = panel; arrangement = WAZE_LEFT;
        } else if (panel.right <= waze.left) {
            left = panel; right = waze; arrangement = WAZE_RIGHT;
        } else {
            return new Pair(NONE, null);
        }

        int gapWidth = right.left - left.right;
        int top = Math.max(waze.top, panel.top);
        int bottom = Math.min(waze.bottom, panel.bottom);
        int sharedHeight = bottom - top;
        if (gapWidth > maximumGap || sharedHeight <= 0
                || (long) sharedHeight * 100 < (long) Math.max(waze.height(), panel.height()) * 65
                || (long) sharedHeight * 100 < (long) height * 40
                || (long) (right.right - left.left) * 100 < (long) width * 70) {
            return new Pair(NONE, null);
        }

        // A zero-width divider needs no overlay. Never enlarge this gap into either app.
        Box gap = gapWidth == 0 ? null : new Box(left.right, top, right.left, bottom);
        if (gap != null && (gap.intersects(waze) || gap.intersects(panel)))
            return new Pair(NONE, null);
        return new Pair(arrangement, gap);
    }
}
