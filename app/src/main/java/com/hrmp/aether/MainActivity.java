package com.hrmp.aether;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = Color.rgb(12, 17, 30);
    private static final int CARD = Color.rgb(24, 32, 48);
    private static final int ACCENT = Color.rgb(0, 220, 180);
    private static final int WHITE = Color.WHITE;
    private static final int MUTED = Color.rgb(160, 174, 195);

    private EditText configInput;
    private TextView statusView;
    private TextView engineView;
    private TextView savedCountView;

    private final ArrayList<SavedConfig> configs = new ArrayList<>();

    private enum Engine {
        AUTO, AETHER, XRAY, WIREGUARD, AMNEZIAWG
    }

    private Engine selectedEngine = Engine.AUTO;

    private static class SavedConfig {
        final String name;
        final String data;
        final Engine engine;

        SavedConfig(String name, String data, Engine engine) {
            this.name = name;
            this.data = data;
            this.engine = engine;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value, int size, int color,
                          boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(5), dp(8), dp(5));

        if (bold) {
            v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return v;
    }

    private LinearLayout.LayoutParams lp(int height) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
    }

    private void space(LinearLayout root, int height) {
        root.addView(new TextView(this), lp(height));
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(CARD, 14));
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(24));
        root.setBackgroundColor(BG);

        scroll.addView(root);

        root.addView(text("HRMP", 18, ACCENT, true), lp(30));

        LinearLayout.LayoutParams titleLp = lp(55);
        titleLp.topMargin = dp(4);

        root.addView(text("A E T H E R", 29, WHITE, true), titleLp);

        root.addView(text(
                "SMART MULTI-CORE VPN",
                12, MUTED, false), lp(30));

        statusView = text(
                "●  READY TO CONFIGURE",
                14, ACCENT, true);

        statusView.setBackground(shape(CARD, 16));

        LinearLayout.LayoutParams statusLp = lp(48);
        statusLp.topMargin = dp(18);

        root.addView(statusView, statusLp);

        space(root, 12);

        root.addView(text(
                "YOUR CONFIGURATION",
                13, ACCENT, true), lp(32));

        configInput = new EditText(this);
        configInput.setTextColor(WHITE);
        configInput.setHintTextColor(MUTED);
        configInput.setHint(
                "Paste a VPN configuration or URI here...");
        configInput.setTextSize(13);
        configInput.setGravity(Gravity.TOP | Gravity.START);
        configInput.setPadding(
                dp(14), dp(14), dp(14), dp(14));
        configInput.setMinLines(5);
        configInput.setSelectAllOnFocus(false);
        configInput.setBackground(shape(CARD, 14));

        root.addView(configInput, lp(145));

        Button paste = button("📋  Paste from clipboard");
        paste.setOnClickListener(v -> pasteClipboard());
        root.addView(paste, lp(48));

        Button importFile = button("📂  Import configuration file");
        importFile.setOnClickListener(v -> openConfigFile());
        root.addView(importFile, lp(48));

        space(root, 10);

        root.addView(text(
                "CONNECTION ENGINE",
                13, ACCENT, true), lp(32));

        engineView = text(
                "Detected engine: Not detected",
                14, WHITE, true);

        engineView.setBackground(shape(CARD, 14));

        root.addView(engineView, lp(62));

        Button detect = button("🔎  Detect configuration type");
        detect.setOnClickListener(v -> detectConfig());
        root.addView(detect, lp(50));

        space(root, 8);

        addEngineButton(root, "AUTO  •  Detect automatically",
                Engine.AUTO);

        addEngineButton(root, "AETHER  •  Aether core",
                Engine.AETHER);

        addEngineButton(root, "XRAY  •  VLESS / VMess / Trojan / SS",
                Engine.XRAY);

        addEngineButton(root, "WIREGUARD  •  Standard WG",
                Engine.WIREGUARD);

        addEngineButton(root, "AMNEZIAWG  •  Obfuscated WG",
                Engine.AMNEZIAWG);

        space(root, 10);

        Button save = button("＋  Save configuration");
        save.setOnClickListener(v -> saveConfig());
        root.addView(save, lp(50));

        savedCountView = text(
                "Saved configurations: 0",
                12, MUTED, false);

        root.addView(savedCountView, lp(35));

        Button showSaved = button("☰  Show saved configurations");
        showSaved.setOnClickListener(v -> showSavedConfigs());
        root.addView(showSaved, lp(48));

        space(root, 10);

        Button connect = button("CONNECT");
        connect.setTextColor(BG);
        connect.setTextSize(17);
        connect.setBackground(shape(ACCENT, 16));

        connect.setOnClickListener(v -> {
            String raw = configInput.getText().toString().trim();

            if (raw.isEmpty()) {
                toast("First enter or import a configuration.");
                return;
            }

            Engine engine = selectedEngine == Engine.AUTO
                    ? detectEngine(raw) : selectedEngine;

            if (engine == null) {
                toast("Unknown configuration format.");
                return;
            }

            engineView.setText(
                    "Selected engine: " + engineName(engine));

            statusView.setText(
                    "CONFIG DETECTED — VPN CORE NOT INTEGRATED");

            toast("Detected " + engineName(engine)
                    + ". Real VPN connection is not implemented yet.");
        });

        root.addView(connect, lp(58));

        TextView disclaimer = text(
                "ENGINE STATUS\\n"
                + "Configuration import and basic detection are available.\\n"
                + "Real VPN connection requires the corresponding core "
                + "and Android VPN service to be integrated.",
                12, MUTED, false);

        disclaimer.setLineSpacing(dp(4), 1f);

        LinearLayout.LayoutParams disclaimerLp = lp(105);
        disclaimerLp.topMargin = dp(14);

        root.addView(disclaimer, disclaimerLp);

        root.addView(text(
                "HRMP AETHER  •  ANDROID",
                10, MUTED, false), lp(32));

        setContentView(scroll);
    }

    private void addEngineButton(
            LinearLayout root, String label, Engine engine) {

        Button b = button(label);

        b.setOnClickListener(v -> {
            selectedEngine = engine;

            String raw = configInput.getText().toString().trim();

            if (!raw.isEmpty() && engine != Engine.AUTO) {
                engineView.setText(
                        "Selected engine: " + engineName(engine));
            } else if (!raw.isEmpty()) {
                detectConfig();
            } else {
                engineView.setText(
                        "Selected engine: " + engineName(engine));
            }

            toast("Selected: " + engineName(engine));
        });

        root.addView(b, lp(46));
    }

    private void pasteClipboard() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(
                            Context.CLIPBOARD_SERVICE);

            if (clipboard == null
                    || !clipboard.hasPrimaryClip()) {
                toast("Clipboard is empty.");
                return;
            }

            ClipData data = clipboard.getPrimaryClip();

            if (data == null || data.getItemCount() == 0) {
                toast("Clipboard is empty.");
                return;
            }

            CharSequence value =
                    data.getItemAt(0).coerceToText(this);

            if (value != null) {
                configInput.setText(value.toString());
                detectConfig();
            }
        } catch (Exception e) {
            toast("Could not read clipboard.");
        }
    }

    private void openConfigFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(intent, 101);
        } catch (Exception e) {
            toast("Could not open file picker.");
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != 101 || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        Uri uri = data.getData();

        if (uri == null) {
            return;
        }

        try (java.io.InputStream in =
                     getContentResolver().openInputStream(uri)) {

            if (in == null) {
                toast("Could not read selected file.");
                return;
            }

            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int count;
            int total = 0;

            while ((count = in.read(buffer)) != -1) {
                total += count;

                if (total > 1024 * 1024) {
                    toast("File too large. Maximum is 1 MB.");
                    return;
                }

                out.write(buffer, 0, count);
            }

            configInput.setText(
                    out.toString("UTF-8").trim());

            detectConfig();
            toast("Configuration file imported.");

        } catch (Exception e) {
            toast("Could not import this file.");
        }
    }

    private void detectConfig() {
        String raw = configInput.getText().toString().trim();

        Engine engine = detectEngine(raw);

        if (raw.isEmpty()) {
            engineView.setText("Detected engine: Not detected");
            statusView.setText("●  READY TO CONFIGURE");
            toast("Enter a configuration first.");
            return;
        }

        if (engine == null) {
            engineView.setText(
                    "Detected engine: Unknown format");
            statusView.setText("CONFIGURATION NOT RECOGNIZED");
            toast("Could not identify the configuration.");
            return;
        }

        if (selectedEngine == Engine.AUTO) {
            engineView.setText(
                    "Detected engine: " + engineName(engine));
        } else {
            engineView.setText(
                    "Selected: " + engineName(selectedEngine)
                    + "\\nDetected format: " + engineName(engine));
        }

        statusView.setText("CONFIGURATION DETECTED");
    }

    private Engine detectEngine(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);

        if (lower.startsWith("vless://")
                || lower.startsWith("vmess://")
                || lower.startsWith("trojan://")
                || lower.startsWith("ss://")
                || lower.startsWith("socks://")
                || lower.startsWith("hysteria2://")
                || lower.startsWith("hy2://")
                || lower.startsWith("tuic://")) {
            return Engine.XRAY;
        }

        if (lower.contains("[interface]")
                && lower.contains("[peer]")) {

            if (lower.contains("jc =")
                    || lower.contains("jmin =")
                    || lower.contains("jmax =")
                    || lower.contains("s1 =")
                    || lower.contains("s2 =")
                    || lower.contains("h1 =")
                    || lower.contains("h2 =")
                    || lower.contains("h3 =")
                    || lower.contains("h4 =")) {
                return Engine.AMNEZIAWG;
            }

            return Engine.WIREGUARD;
        }

        if (lower.startsWith("aether://")
                || lower.startsWith("aether:")) {
            return Engine.AETHER;
        }

        // JSON formats need core-specific parsing to identify reliably.
        if (s.startsWith("{") || s.startsWith("[")) {
            return null;
        }

        return null;
    }

    private String engineName(Engine engine) {
        switch (engine) {
            case AETHER:
                return "Aether";
            case XRAY:
                return "Xray";
            case WIREGUARD:
                return "WireGuard";
            case AMNEZIAWG:
                return "AmneziaWG";
            default:
                return "Auto detect";
        }
    }

    private void saveConfig() {
        String raw = configInput.getText().toString().trim();

        if (raw.isEmpty()) {
            toast("Enter a configuration first.");
            return;
        }

        Engine detected = detectEngine(raw);

        if (detected == null) {
            toast("Unknown format; configuration not saved.");
            return;
        }

        String name = engineName(detected)
                + " " + (configs.size() + 1);

        configs.add(new SavedConfig(name, raw, detected));

        savedCountView.setText(
                "Saved configurations: " + configs.size());

        toast("Configuration saved for this app session.");
    }

    private void showSavedConfigs() {
        if (configs.isEmpty()) {
            toast("No saved configurations in this session.");
            return;
        }

        String[] names = new String[configs.size()];

        for (int i = 0; i < configs.size(); i++) {
            names[i] = configs.get(i).name;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Saved configurations")
                .setItems(names, (dialog, which) -> {
                    SavedConfig item = configs.get(which);

                    configInput.setText(item.data);
                    selectedEngine = Engine.AUTO;
                    detectConfig();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(
                this, message, Toast.LENGTH_LONG).show();
    }
            }
