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
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ScrollView;
import java.util.List;

final class FloatingTools extends FrameLayout {
    private final Button ball;
    private final ScrollView panel;
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
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(2);
        int padding = Ui.dp(activity, 4);
        grid.setPadding(padding, padding, padding, padding);
        for (int i = 0; i < shortcuts.size(); i++) {
            int span = i == shortcuts.size() - 1 && i % 2 == 0 ? 2 : 1;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec(i / 2), GridLayout.spec(i % 2, span, GridLayout.FILL, 1f));
            params.width = 0;
            params.height = Ui.dp(activity, 44);
            int gap = Ui.dp(activity, 2);
            params.setMargins(gap, gap, gap, gap);
            shortcuts.get(i).setPadding(0, 0, 0, 0);
            grid.addView(shortcuts.get(i), params);
        }
        panel = new ScrollView(activity);
        panel.addView(grid);
        panel.setBackground(Ui.background(0xf01b2330, Ui.dp(activity, 12)));
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
            downX = x; downY = y;
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
        ball.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
        int panelWidth = Math.max(1, Math.min(Ui.dp((Activity)getContext(), 176), width - safe.left - safe.right - margin * 2));
        int panelHeight = Math.max(1, height - safe.top - safe.bottom - size - margin * 3);
        panel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(panelHeight, MeasureSpec.AT_MOST));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int minX = safe.left + margin, minY = safe.top + margin;
        movement.set(minX, minY, Math.max(minX, getWidth() - safe.right - margin - size),
                Math.max(minY, getHeight() - safe.bottom - margin - size));
        if (!dragging) { x = leftEdge ? movement.left : movement.right; y = movement.top + vertical * movement.height(); }
        int panelWidth = panel.getMeasuredWidth(), panelHeight = panel.getMeasuredHeight();
        boolean above = y - movement.top >= movement.bottom - y;
        if (expanded) y = above ? Math.max(y, movement.top + panelHeight + margin) : Math.min(y, movement.bottom - panelHeight - margin);
        ball.layout(0, 0, size, size);
        ball.setTranslationX(x); ball.setTranslationY(y);
        int panelX = leftEdge ? movement.left : movement.right + size - panelWidth;
        int panelY = Math.round(above ? y - margin - panelHeight : y + size + margin);
        panel.layout(panelX, panelY, panelX + panelWidth, panelY + panelHeight);
        panel.setPivotX(leftEdge ? size / 2f : panelWidth - size / 2f);
        panel.setPivotY(above ? panelHeight : 0);
    }
}
