package org.necmobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(16, 21, 31), CARD = Color.rgb(27, 35, 48), ACCENT = Color.rgb(118, 224, 192);
    static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    static TextView text(Activity activity, String label, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(activity, 5), 0, dp(activity, 5));
        return view;
    }
    static Button button(Activity activity, String label, Runnable click) {
        Button button = new Button(activity);
        button.setText(label); button.setTextSize(13); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setTextColor(Color.WHITE);
        button.setOnClickListener(view -> click.run());
        return button;
    }
    static LinearLayout column(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    static LinearLayout row(Activity activity) {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }
    static GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(radius);
        return drawable;
    }
    static void title(TextView text) { text.setTypeface(Typeface.DEFAULT, Typeface.BOLD); }
    static void insets(Activity activity, View view, int padding) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int p = dp(activity, padding);
            target.setPadding(p + insets.getSystemWindowInsetLeft(), p + insets.getSystemWindowInsetTop(),
                    p + insets.getSystemWindowInsetRight(), p + insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.requestApplyInsets();
    }
}
