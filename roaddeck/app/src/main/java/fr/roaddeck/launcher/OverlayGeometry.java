package fr.roaddeck.launcher;

/** Pure geometry: there is deliberately no full-screen transparent touch surface. */
final class OverlayGeometry {
    static int percent(int value) { return Math.max(24, Math.min(46, value)); }
    static int width(int displayWidth, int requestedPercent) {
        if (displayWidth <= 0) throw new IllegalArgumentException("Invalid display width");
        return Math.max(1, (int)Math.round(displayWidth * percent(requestedPercent) / 100.0));
    }
    private OverlayGeometry() {}
}
