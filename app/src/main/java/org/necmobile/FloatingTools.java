package org.necmobile;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.List;

final class FloatingTools extends FrameLayout {
    private final Button ball;
    private final FrameLayout panel;
    private final SharedPreferences preferences;
    private final Runnable onCollapse;
    private final Rect safe = new Rect(), movement = new Rect();
    private final int size, margin, slop;
    private String positionKey;
    private boolean expanded, leftEdge, dragging;
    private float vertical = 1, x, y, downX, downY, downRawX, downRawY;
    private int pointer = -1;

    FloatingTools(Activity activity, List<View> shortcuts, Runnable onCollapse) {
        super(activity);
        this.onCollapse = onCollapse;
        preferences = activity.getSharedPreferences("player", 0);
        size = Ui.dp(activity, 48);
        margin = Ui.dp(activity, 8);
        slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        panel = new FrameLayout(activity) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                float radius = (getWidth() - Ui.dp(activity, 56)) / 2f - Ui.dp(activity, 8);
                for (int i = 0; i < getChildCount(); i++) {
                    double angle = Math.toRadians(-90 + i * 360.0 / getChildCount());
                    getChildAt(i).setTranslationX((float)Math.cos(angle) * radius);
                    getChildAt(i).setTranslationY((float)Math.sin(angle) * radius);
                }
            }
        };
        for (View shortcut : shortcuts) {
            shortcut.setPadding(0, 0, 0, 0);
            shortcut.setBackground(Ui.background(0xf02b3748, Ui.dp(activity, 28)));
            shortcut.setBackgroundTintList(null);
            if (shortcut instanceof Button) {
                Button button = (Button)shortcut;
                button.setTextSize(12); button.setMinHeight(0); button.setMinimumHeight(0);
                button.setStateListAnimator(null);
            }
            panel.addView(shortcut, new FrameLayout.LayoutParams(Ui.dp(activity, 56), Ui.dp(activity, 56), Gravity.CENTER));
        }
        panel.setBackground(Ui.background(0xe010151f, Ui.dp(activity, 160)));
        panel.setClipToOutline(true);
        panel.setVisibility(GONE);
        addView(panel);
        ball = Ui.button(activity, "≡", this::toggle);
        ball.setTextSize(22);
        ball.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ball.setTextColor(Color.WHITE);
        ball.setPadding(0, 0, 0, 0);
        ball.setMinHeight(0);
        ball.setMinimumHeight(0);
        ball.setBackground(Ui.background(0xf0232c3a, size / 2));
        ball.setBackgroundTintList(null);
        ball.setStateListAnimator(null);
        ball.setAlpha(0.58f);
        ball.setContentDescription("展开工具栏，可拖动位置");
        ball.setOnTouchListener(this::touchBall);
        addView(ball);
    }

    boolean isExpanded() { return expanded; }
    void collapse() { setExpanded(false); }
    private void toggle() { setExpanded(!expanded); }

    private void setExpanded(boolean show) {
        if (expanded == show) return;
        expanded = show;
        panel.animate().cancel();
        panel.setVisibility(show ? VISIBLE : GONE);
        ball.setText(show ? "×" : "≡");
        ball.setAlpha(show ? 0.92f : 0.58f);
        ball.setBackground(Ui.background(0xf0232c3a, Ui.dp((Activity)getContext(), 34)));
        ball.setContentDescription(show ? "收起工具栏，可拖动位置" : "展开工具栏，可拖动位置");
        if (show) {
            panel.setAlpha(0);
            panel.setScaleX(0.88f);
            panel.setScaleY(0.88f);
            panel.animate().alpha(1).scaleX(1).scaleY(1).setDuration(140).start();
        } else onCollapse.run();
        requestLayout();
    }

    void cancelInteraction() {
        pointer = -1;
        dragging = false;
        ball.setPressed(false);
        collapse();
        requestLayout();
    }

    private boolean touchBall(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointer = event.getPointerId(0);
            downRawX = event.getRawX(); downRawY = event.getRawY();
            downX = ball.getTranslationX(); downY = ball.getTranslationY();
            dragging = false;
            ball.setPressed(true);
        } else if (action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.getActionIndex()) == pointer)) {
            cancelInteraction();
        } else if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) && pointer >= 0) {
            int index = event.findPointerIndex(pointer);
            if (index < 0) { cancelInteraction(); return true; }
            float dx = event.getX(index) + event.getRawX() - event.getX() - downRawX;
            float dy = event.getY(index) + event.getRawY() - event.getY() - downRawY;
            if (!dragging && dx * dx + dy * dy > slop * slop) {
                dragging = true;
                collapse();
            }
            if (dragging) {
                x = Math.max(movement.left, Math.min(movement.right, downX + dx));
                y = Math.max(movement.top, Math.min(movement.bottom, downY + dy));
                ball.setTranslationX(x); ball.setTranslationY(y);
            }
            if (action == MotionEvent.ACTION_UP) {
                pointer = -1;
                ball.setPressed(false);
                if (dragging) {
                    leftEdge = x < (movement.left + movement.right) / 2f;
                    vertical = movement.height() > 0 ? (y - movement.top) / movement.height() : 0;
                    preferences.edit().putBoolean(positionKey + ".left", leftEdge).putFloat(positionKey + ".y", vertical).apply();
                    dragging = false;
                    requestLayout();
                } else view.performClick();
            }
        }
        return true;
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        safe.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) safe.set(Math.max(safe.left, cutout.getSafeInsetLeft()), Math.max(safe.top, cutout.getSafeInsetTop()),
                    Math.max(safe.right, cutout.getSafeInsetRight()), Math.max(safe.bottom, cutout.getSafeInsetBottom()));
        }
        requestLayout();
        return insets;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        String key = width > height ? "tools.landscape" : "tools.portrait";
        if (!key.equals(positionKey)) {
            positionKey = key;
            leftEdge = preferences.getBoolean(key + ".left", false);
            vertical = Math.max(0, Math.min(1, preferences.getFloat(key + ".y", 1)));
        }
        cancelInteraction();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int centerSize = expanded ? Ui.dp((Activity)getContext(), 68) : size;
        ball.measure(MeasureSpec.makeMeasureSpec(centerSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(centerSize, MeasureSpec.EXACTLY));
        int diameter = Math.max(1, Math.min(Ui.dp((Activity)getContext(), 272),
                Math.min(width - safe.left - safe.right, height - safe.top - safe.bottom) - margin * 2));
        panel.measure(MeasureSpec.makeMeasureSpec(diameter, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(diameter, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int minX = safe.left + margin, minY = safe.top + margin;
        movement.set(minX, minY, Math.max(minX, getWidth() - safe.right - margin - size),
                Math.max(minY, getHeight() - safe.bottom - margin - size));
        if (!dragging) { x = leftEdge ? movement.left : movement.right; y = movement.top + vertical * movement.height(); }
        int diameter = panel.getMeasuredWidth();
        float centerX = x + size / 2f, centerY = y + size / 2f;
        if (expanded) {
            centerX = Math.max(minX + diameter / 2f, Math.min(getWidth() - safe.right - margin - diameter / 2f, centerX));
            centerY = Math.max(minY + diameter / 2f, Math.min(getHeight() - safe.bottom - margin - diameter / 2f, centerY));
        }
        int centerSize = ball.getMeasuredWidth();
        ball.layout(0, 0, centerSize, centerSize);
        ball.setTranslationX(centerX - centerSize / 2f); ball.setTranslationY(centerY - centerSize / 2f);
        int panelX = Math.round(centerX - diameter / 2f), panelY = Math.round(centerY - diameter / 2f);
        panel.layout(panelX, panelY, panelX + diameter, panelY + diameter);
        panel.setPivotX(diameter / 2f); panel.setPivotY(diameter / 2f);
    }
}
