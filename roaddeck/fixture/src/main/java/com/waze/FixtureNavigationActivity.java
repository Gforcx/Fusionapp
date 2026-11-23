package com.waze;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A native, resizable navigation test surface. This is NOT the Waze application. */
public final class FixtureNavigationActivity extends Activity {
    private static final int[] IDS = {R.id.corner_top_left, R.id.corner_top_right,
            R.id.corner_bottom_left, R.id.corner_bottom_right};
    private static final String[] SHORT_NAMES = {"HG", "HD", "BG", "BD"};
    private static final String[] DESCRIPTIONS = {"Coin haut gauche", "Coin haut droit",
            "Coin bas gauche", "Coin bas droit"};
    private static final int[] GRAVITIES = {Gravity.TOP | Gravity.LEFT,
            Gravity.TOP | Gravity.RIGHT, Gravity.BOTTOM | Gravity.LEFT,
            Gravity.BOTTOM | Gravity.RIGHT};
    private final int[] counters = new int[4];
    private final Button[] buttons = new Button[4];
    private FrameLayout root;
    private TextView dimensions;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            int[] saved = state.getIntArray("corner_counters");
            if (saved != null && saved.length == counters.length)
                System.arraycopy(saved, 0, counters, 0, counters.length);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setId(R.id.navigation_root);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(0xff11334b);
        root.addView(new Grid(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.VERTICAL);
        legend.setGravity(Gravity.CENTER);
        legend.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView title = label("NAVIGATION DE TEST", 21);
        title.setId(R.id.fixture_title);
        legend.addView(title);
        legend.addView(label("Application factice pour les tests RoadDeck.\nAucune carte ni navigation Waze.", 13));
        dimensions = label("", 14);
        legend.addView(dimensions);
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        center.setMargins(dp(8), dp(76), dp(8), dp(76));
        root.addView(legend, center);

        for (int i = 0; i < buttons.length; i++) {
            final int corner = i;
            Button button = new Button(this);
            buttons[i] = button;
            button.setId(IDS[i]);
            button.setAllCaps(false);
            button.setTextSize(16);
            button.setContentDescription(DESCRIPTIONS[i]);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(4), 0, dp(4), 0);
            updateButton(i);
            button.setOnClickListener(v -> { counters[corner]++; updateButton(corner); });
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(112), dp(56), GRAVITIES[i]);
            position.setMargins(dp(8), dp(8), dp(8), dp(8));
            root.addView(button, position);
        }
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateDimensions());
        setContentView(root);
        immersive();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private void updateButton(int corner) {
        buttons[corner].setText(SHORT_NAMES[corner] + " : " + counters[corner]);
    }

    private void updateDimensions() {
        dimensions.setText(root.getWidth() + " × " + root.getHeight() + " px · "
                + (isInMultiWindowMode() ? "fenêtre redimensionnée" : "plein écran"));
        int width = Math.min(dp(112), Math.max(dp(64), (root.getWidth() - dp(32)) / 2));
        for (Button button : buttons) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
            if (params.width != width) { params.width = width; button.setLayoutParams(params); }
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    @Override protected void onResume() { super.onResume(); immersive(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }
    @Override public void onMultiWindowModeChanged(boolean active, Configuration configuration) {
        super.onMultiWindowModeChanged(active, configuration);
        root.post(this::updateDimensions);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putIntArray("corner_counters", counters);
        super.onSaveInstanceState(out);
    }

    private final class Grid extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Grid() { super(FixtureNavigationActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(0x443fbcd4); paint.setStrokeWidth(dp(1));
            int step = dp(44);
            for (int x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), paint);
            for (int y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, paint);
        }
    }
}
