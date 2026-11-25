package fr.roaddeck.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Native, opaque startup curtain. Its message is supplied by the real window coordinator;
 * the moving ornament is deliberately indeterminate and never reports invented progress.
 * The caller owns the window and the timeout, and must call destroy when discarding it.
 */
public final class StartupLoadingView extends FrameLayout {
    private static final long CYCLE_NANOS = 4_200_000_000L;
    private static final long DRAW_INTERVAL_NANOS = 32_000_000L;
    private final float density;
    private final BackdropView backdrop;
    private final LogoView logo;
    private final RailView rail;
    private final LinearLayout content;
    private final TextView title, subtitle, stage;
    private final Button cancel;
    private boolean destroyed, callbackPosted, attached, aggregatedVisible = true;
    private long firstFrameNanos, lastDrawNanos;
    private float animationPhase;
    private int layoutSize = -1, contentWidth = -1;

    private final Choreographer.FrameCallback animation = new Choreographer.FrameCallback() {
        @Override public void doFrame(long timeNanos) {
            callbackPosted = false;
            if (!shouldAnimate()) return;
            if (firstFrameNanos == 0) firstFrameNanos = timeNanos;
            if (lastDrawNanos == 0 || timeNanos - lastDrawNanos >= DRAW_INTERVAL_NANOS) {
                lastDrawNanos = timeNanos;
                setAnimationPhase((float) ((timeNanos - firstFrameNanos) % CYCLE_NANOS)
                        / CYCLE_NANOS);
            }
            scheduleAnimation();
        }
    };

    public StartupLoadingView(Context context, Runnable onCancel) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xff090d19);
        // During setup, taps outside the explicit cancel action must not hit a moving app.
        setClickable(true);
        setFocusable(false);
        backdrop = new BackdropView(context);
        addView(backdrop, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(content, new LayoutParams(dp(560), LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        logo = new LogoView(context);
        content.addView(logo, new LinearLayout.LayoutParams(dp(144), dp(144)));
        title = label("ROADDECK", 0xfff4efff, 32);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.19f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title, columnParams(14));
        subtitle = label("Préparation de votre espace de conduite", 0xff9ba6c2, 15);
        subtitle.setMaxLines(2);
        content.addView(subtitle, columnParams(8));
        stage = label("Connexion à Waze…", 0xffddd2f8, 17);
        stage.setMaxLines(2);
        stage.setMinHeight(dp(24));
        stage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(stage, columnParams(26));
        rail = new RailView(context);
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(dp(112), dp(10));
        railParams.topMargin = dp(14);
        content.addView(rail, railParams);

        cancel = new Button(context);
        cancel.setText("Annuler");
        cancel.setContentDescription("Annuler le démarrage");
        cancel.setAllCaps(false);
        cancel.setTextColor(0xffd5cce7);
        cancel.setTextSize(15);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setMinimumWidth(0);
        cancel.setMinWidth(0);
        cancel.setMinimumHeight(dp(48));
        cancel.setMinHeight(dp(48));
        cancel.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable buttonBackground = new GradientDrawable();
        buttonBackground.setColor(0xff141a2e);
        buttonBackground.setCornerRadius(dp(24));
        buttonBackground.setStroke(dp(1), 0xff363149);
        cancel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x447d60af),
                buttonBackground, null));
        cancel.setStateListAnimator(null);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(148), dp(48));
        cancelParams.topMargin = dp(24);
        content.addView(cancel, cancelParams);
        cancel.setOnClickListener(view -> {
            if (destroyed || !cancel.isEnabled()) return;
            cancel.setEnabled(false);
            if (onCancel != null) onCancel.run();
        });
    }

    /** Stage names describe actual work; no timer-driven change to these labels. */
    public void setStage(String text) {
        if (destroyed) return;
        String value = text == null || text.trim().isEmpty() ? "Préparation de Waze…" : text;
        if (!value.contentEquals(stage.getText())) stage.setText(value);
    }

    /** Stop all frame callbacks before releasing this view or its overlay window. */
    public void destroy() {
        destroyed = true;
        stopAnimation();
        cancel.setOnClickListener(null);
    }

    @Override public void draw(Canvas canvas) {
        super.draw(canvas);
        // The curtain is an opaque surface, including after all translucent glows,
        // antialiased text and child views have been composited. The API 29 software
        // renderer can round a SRC_OVER result to alpha 254; an opaque backing pass
        // fills that residual transparency without painting over the artwork.
        canvas.drawColor(0xff090d19, PorterDuff.Mode.DST_OVER);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = View.MeasureSpec.getSize(heightMeasureSpec);
        int size = height < dp(330) ? 0 : height < dp(430) ? 1 : height < dp(560) ? 2 : 3;
        int newWidth = Math.max(dp(160), Math.min(dp(560), width - dp(32)));
        if (layoutSize != size || contentWidth != newWidth) {
            layoutSize = size;
            contentWidth = newWidth;
            LayoutParams contentParams = (LayoutParams) content.getLayoutParams();
            contentParams.width = newWidth;
            content.setLayoutParams(contentParams);
            int[] iconSizes = {56, 64, 96, 144};
            LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) logo.getLayoutParams();
            iconParams.width = dp(iconSizes[size]);
            iconParams.height = dp(iconSizes[size]);
            logo.setLayoutParams(iconParams);
            title.setTextSize(size == 0 ? 24 : size == 1 ? 26 : size == 2 ? 29 : 32);
            subtitle.setTextSize(size < 2 ? 13 : 15);
            subtitle.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
            stage.setTextSize(size < 2 ? 14 : 17);
            topMargin(title, size < 2 ? 6 : size == 2 ? 8 : 14);
            topMargin(subtitle, size < 2 ? 4 : size == 2 ? 6 : 8);
            topMargin(stage, size < 2 ? 10 : size == 2 ? 18 : 26);
            topMargin(rail, size < 2 ? 4 : size == 2 ? 8 : 14);
            topMargin(cancel, size < 2 ? 10 : size == 2 ? 18 : 24);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        scheduleAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        aggregatedVisible = visible;
        if (visible) scheduleAnimation(); else stopAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) scheduleAnimation(); else stopAnimation();
    }

    private boolean shouldAnimate() {
        return !destroyed && attached && aggregatedVisible && getWindowVisibility() == VISIBLE
                && isShown();
    }

    private void scheduleAnimation() {
        if (!callbackPosted && shouldAnimate()) {
            callbackPosted = true;
            Choreographer.getInstance().postFrameCallback(animation);
        }
    }

    private void stopAnimation() {
        if (callbackPosted) Choreographer.getInstance().removeFrameCallback(animation);
        callbackPosted = false;
        firstFrameNanos = 0;
        lastDrawNanos = 0;
    }

    // Package-visible deterministic renderer/lifecycle hooks; not part of the app bridge.
    void setAnimationPhaseForTesting(float phase) { setAnimationPhase(phase); }
    boolean animationScheduledForTesting() { return callbackPosted; }
    Button cancelButtonForTesting() { return cancel; }

    private void setAnimationPhase(float phase) {
        animationPhase = phase - (float) Math.floor(phase);
        logo.invalidate();
        rail.invalidate();
    }

    private TextView label(String text, int color, int size) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        label.setFontFeatureSettings("kern");
        return label;
    }

    private LinearLayout.LayoutParams columnParams(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private void topMargin(View view, int value) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(value);
        view.setLayoutParams(params);
    }

    private int dp(float value) { return Math.round(value * density); }

    private final class BackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Shader background, halo;

        BackdropView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            background = new LinearGradient(0, 0, width, height,
                    new int[]{0xff0c1223, 0xff0a0e1b, 0xff080b15}, null, Shader.TileMode.CLAMP);
            halo = new RadialGradient(width * 0.5f, height * 0.40f,
                    Math.max(1, Math.min(width * 0.60f, height * 0.70f)),
                    new int[]{0x343f2367, 0x142d1d4b, 0x002d1d4b},
                    new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            // setShader does not reset Paint alpha. The horizon below deliberately
            // uses a translucent color; never carry that alpha into the next frame.
            paint.setAlpha(255);
            paint.setShader(background);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(halo);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(0x124c5072);
            // A quiet horizon gives the otherwise opaque curtain depth without a bitmap.
            float horizon = getHeight() * 0.87f;
            for (int i = -3; i <= 3; i++) {
                float bottomX = getWidth() * 0.5f + i * getWidth() * 0.24f;
                canvas.drawLine(getWidth() * 0.5f + i * getWidth() * 0.07f, horizon,
                        bottomX, getHeight(), paint);
            }
            canvas.drawLine(0, horizon, getWidth(), horizon, paint);
            canvas.drawLine(0, getHeight() * 0.94f, getWidth(), getHeight() * 0.94f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private final class LogoView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF card = new RectF(23, 23, 105, 105);
        private final RectF orbit = new RectF(7, 7, 121, 121);
        private final Path leftLane = new Path(), rightLane = new Path(), centerLane = new Path();
        private final Shader cardFill = new LinearGradient(25, 23, 101, 108,
                new int[]{0xff50316f, 0xff261e40, 0xff151c30}, null, Shader.TileMode.CLAMP);
        private final Shader laneFill = new LinearGradient(43, 91, 86, 36,
                new int[]{0xffaf7aff, 0xffd5b5ff, 0xff8de5ef}, null, Shader.TileMode.CLAMP);
        private final Shader glow = new RadialGradient(64, 64, 62,
                new int[]{0x3f8b47d0, 0x147d48b8, 0x007d48b8},
                new float[]{0, 0.62f, 1}, Shader.TileMode.CLAMP);

        LogoView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            leftLane.moveTo(42, 92);
            leftLane.cubicTo(42, 72, 69, 64, 69, 36);
            rightLane.moveTo(63, 94);
            rightLane.cubicTo(63, 73, 87, 67, 88, 38);
            centerLane.moveTo(52, 90);
            centerLane.cubicTo(52, 73, 78, 65, 78, 39);
        }

        @Override protected void onDraw(Canvas canvas) {
            // Arc and lane rendering below changes strokeCap to ROUND. Start each
            // logo frame with the same complete Paint state, including the BUTT
            // cap used by the preceding closed contours; otherwise their Skia
            // rasterization differs between the first draw and later draws.
            paint.reset();
            paint.setAntiAlias(true);
            canvas.save();
            canvas.scale(getWidth() / 128f, getHeight() / 128f);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(glow);
            canvas.drawCircle(64, 64, 62, paint);
            paint.setShader(cardFill);
            canvas.drawRoundRect(card, 25, 25, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.1f);
            paint.setColor(0xff6f4f91);
            canvas.drawRoundRect(card, 25, 25, paint);
            paint.setColor(0x42564475);
            canvas.drawOval(orbit, paint);
            paint.setStrokeWidth(2.3f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xffb985f4);
            canvas.drawArc(orbit, animationPhase * 360f - 130f, 67f, false, paint);
            paint.setColor(0xff95dce8);
            canvas.drawArc(orbit, animationPhase * 360f + 92f, 11f, false, paint);
            paint.setShader(laneFill);
            paint.setStrokeWidth(3.6f);
            canvas.drawPath(leftLane, paint);
            canvas.drawPath(rightLane, paint);
            paint.setStrokeWidth(2.2f);
            paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{5, 8},
                    -animationPhase * 52f));
            canvas.drawPath(centerLane, paint);
            paint.setPathEffect(null);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffeadbff);
            float angle = (animationPhase * 360f - 63f) * (float) Math.PI / 180f;
            canvas.drawCircle(64 + 57 * (float) Math.cos(angle),
                    64 + 57 * (float) Math.sin(angle), 3.1f, paint);
            canvas.restore();
        }
    }

    private final class RailView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();

        RailView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = dp(2), centerY = getHeight() / 2f;
            track.set(0, centerY - radius, getWidth(), centerY + radius);
            paint.setColor(0xff25283e);
            canvas.drawRoundRect(track, radius, radius, paint);
            // An oscillating capsule is an activity indicator, not a percentage bar.
            float capsule = getWidth() * 0.28f;
            float offset = (getWidth() - capsule)
                    * (0.5f - 0.5f * (float) Math.cos(animationPhase * Math.PI * 4));
            track.set(offset, centerY - radius, offset + capsule, centerY + radius);
            paint.setColor(0xffbc89fa);
            canvas.drawRoundRect(track, radius, radius, paint);
        }
    }
}
