package com.hrmp.aether;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

private int dp(float value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
}

private GradientDrawable shape(int color, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radius));
    return drawable;
}

private TextView text(String value, int size, int color, boolean bold) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.CENTER);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
}

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    final int background = Color.rgb(12, 17, 30);
    final int accent = Color.rgb(0, 220, 180);
    final int white = Color.WHITE;
    final int muted = Color.rgb(160, 174, 195);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    root.setPadding(dp(24), dp(35), dp(24), dp(24));
    root.setBackgroundColor(background);

    TextView logo = text("HRMP", 19, accent, true);
    root.addView(logo, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(35)));

    TextView title = text("A E T H E R", 30, white, true);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
    titleParams.topMargin = dp(8);
    root.addView(title, titleParams);

    TextView subtitle = text("NEXT GENERATION CONNECTION", 12, muted, false);
    root.addView(subtitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(35)));

    TextView status = text("READY TO CONFIGURE", 14, accent, true);
    status.setBackground(shape(Color.rgb(24, 42, 52), 18));
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
    statusParams.topMargin = dp(35);
    root.addView(status, statusParams);

    TextView info = text(
            "Your connection workspace\\n\\n"
            + "Configure your Aether connection when the compatible VPN core is integrated.",
            15, muted, false);
    info.setLineSpacing(dp(5), 1f);
    LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(130));
    infoParams.topMargin = dp(25);
    root.addView(info, infoParams);

    Button connect = new Button(this);
    connect.setText("VPN CORE NOT CONNECTED");
    connect.setTextColor(background);
    connect.setAllCaps(false);
    connect.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    connect.setBackground(shape(accent, 16));
    connect.setEnabled(false);

    LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
    buttonParams.topMargin = dp(20);
    root.addView(connect, buttonParams);

    TextView footer = text("HRMP AETHER  •  ANDROID", 11, muted, false);
    LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
    footerParams.topMargin = dp(30);
    root.addView(footer, footerParams);

    setContentView(root);
}

      }
