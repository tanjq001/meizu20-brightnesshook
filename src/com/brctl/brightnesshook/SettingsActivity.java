package com.brctl.brightnesshook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    // 参数定义：{属性名, 显示标签}
    private static final String[][] PARAMS = {
        {"lux_inc", "光感倍率 (lux_inc)｜>1更亮，1=不变"},
        {"smooth_delay", "光感延迟 (smooth_delay)｜毫秒"},
        {"smooth_window", "滤波窗口 (smooth_window)｜毫秒"},
        {"filter_mode", "滤波方式 (filter_mode)｜median / mean"},
        {"brighten_debounce", "变亮防抖 (brighten_debounce)｜毫秒"},
        {"darken_debounce", "变暗防抖 (darken_debounce)｜毫秒"},
    };

    // 默认值：{属性名, 默认值}
    private static final String[][] DEFAULTS = {
        {"lux_inc", "1.3"},
        {"smooth_delay", "0"},
        {"smooth_window", "4000"},
        {"filter_mode", "median"},
        {"brighten_debounce", "1000"},
        {"darken_debounce", "2000"},
    };

    private EditText[] fields;
    private TextView luxView;
    private Handler handler;
    private Runnable poller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("亮度曲线调节");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(8));
        layout.addView(title);

        TextView hint = new TextView(this);
        hint.setText("修改后点「保存并生效」，无需重启。\n首次保存会请求 root 权限。");
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(12));
        layout.addView(hint);

        luxView = new TextView(this);
        luxView.setText("当前光感：等待传感器数据...");
        luxView.setTextSize(15);
        luxView.setGravity(Gravity.CENTER);
        luxView.setTypeface(android.graphics.Typeface.MONOSPACE);
        luxView.setPadding(0, 0, 0, dp(12));
        layout.addView(luxView);

        fields = new EditText[PARAMS.length];
        for (int i = 0; i < PARAMS.length; i++) {
            TextView label = new TextView(this);
            label.setText(PARAMS[i][1]);
            label.setTextSize(14);
            label.setPadding(0, dp(10), 0, dp(4));
            layout.addView(label);

            EditText edit = new EditText(this);
            edit.setText(getProp(PARAMS[i][0]));
            edit.setTextSize(16);
            edit.setSingleLine(true);
            edit.setInputType(InputType.TYPE_CLASS_TEXT);
            fields[i] = edit;
            layout.addView(edit);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(16), 0, 0);

        Button save = new Button(this);
        save.setText("保存并生效");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAll();
            }
        });
        btns.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshAll();
            }
        });
        btns.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(btns);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.setMargins(0, dp(8), 0, 0);

        Button restore = new Button(this);
        restore.setText("恢复默认");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestore();
            }
        });
        row2.addView(restore, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button about = new Button(this);
        about.setText("关于");
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        row2.addView(about, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        layout.addView(row2, row2Lp);

        scroll.addView(layout);
        setContentView(scroll);

        handler = new Handler(Looper.getMainLooper());
        startLuxPolling();
    }

    private void startLuxPolling() {
        poller = new Runnable() {
            @Override
            public void run() {
                String raw = getSystemProp("sys.brctl.raw_lux");
                String cur = getSystemProp("sys.brctl.cur_lux");
                if (raw.isEmpty() && cur.isEmpty()) {
                    luxView.setText("当前光感：等待传感器数据...");
                } else {
                    luxView.setText("原始光感 " + formatLux(raw) + " lux  →  计算后 " + formatLux(cur) + " lux");
                }
                handler.postDelayed(this, 500);
            }
        };
        handler.post(poller);
    }

    private String formatLux(String s) {
        if (s == null || s.isEmpty()) {
            return " --.- ";
        }
        try {
            return String.format(java.util.Locale.US, "%6.1f", Float.parseFloat(s));
        } catch (Throwable t) {
            return " --.- ";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && poller != null) {
            handler.removeCallbacks(poller);
        }
    }

    private String getSystemProp(String fullKey) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, fullKey, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void showAbout() {
        StringBuilder sb = new StringBuilder();
        sb.append("亮度曲线调节 v1.1\n\n");
        sb.append("基于 LSPosed 的自动亮度调节模块\n");
        sb.append("光感拦截 · 中值滤波 · 防抖调节\n\n");
        sb.append("机型：魅族 20\n");
        sb.append("在 Flyme 10.5.0.0.0 上开发\n");
        sb.append("其它版本系统未验证\n\n");
        sb.append("作者：酷安 折翼之舞007\n");
        sb.append("源码：https://github.com/tanjq001/meizu20-brightnesshook\n\n");
        sb.append("──────────────\n");
        sb.append("【调节指南】\n\n");
        sb.append("屏幕过暗\n");
        sb.append("  增大 lux_inc（如 1.3→1.8）\n\n");
        sb.append("屏幕过亮\n");
        sb.append("  减小 lux_inc（如 1.3→1.0）\n\n");
        sb.append("响应慢、不灵敏\n");
        sb.append("  减小 brighten_debounce / darken_debounce\n\n");
        sb.append("明暗跳跃、忽明忽暗\n");
        sb.append("  增大 smooth_delay，滤波方式用 median\n\n");
        sb.append("──────────────\n");
        sb.append("【参数说明】\n\n");
        sb.append("光感倍率 (lux_inc)\n");
        sb.append("  光感读数倍率，>1 更亮，<1 更暗，1=不变，默认 1.3\n\n");
        sb.append("光感延迟 (smooth_delay)\n");
        sb.append("  光感延迟输出毫秒数。建议设 1000~5000，0=不延迟，上限 60000，默认 0\n\n");
        sb.append("滤波窗口 (smooth_window)\n");
        sb.append("  中值/均值滤波时间窗毫秒数，0=关闭，上限 60000，默认 4000\n\n");
        sb.append("滤波方式 (filter_mode)\n");
        sb.append("  median=中值(抗脉冲噪声) / mean=均值，默认 median\n\n");
        sb.append("变亮防抖 (brighten_debounce)\n");
        sb.append("  变亮防抖毫秒数，默认 1000\n\n");
        sb.append("变暗防抖 (darken_debounce)\n");
        sb.append("  变暗防抖毫秒数，默认 2000");
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, "persist.brctl." + key, "");
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void refreshAll() {
        clearFocusAndKeyboard();
        for (int i = 0; i < PARAMS.length; i++) {
            fields[i].setText(getProp(PARAMS[i][0]));
        }
        toast("已刷新");
    }

    private void saveAll() {
        clearFocusAndKeyboard();
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < PARAMS.length; i++) {
            String val = fields[i].getText().toString().trim();
            if (val.isEmpty()) {
                continue;
            }
            cmd.append("setprop persist.brctl.").append(PARAMS[i][0])
                    .append(" ").append(val).append("; ");
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd.toString()});
            int code = p.waitFor();
            if (code == 0) {
                toast("已保存，立即生效");
            } else {
                toast("保存失败，请确认已授予 root 权限");
            }
        } catch (Throwable t) {
            toast("保存失败: " + t.getMessage());
        }
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认")
                .setMessage("确定恢复所有参数为默认值？")
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        restoreDefaults();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restoreDefaults() {
        clearFocusAndKeyboard();
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < DEFAULTS.length; i++) {
            cmd.append("setprop persist.brctl.").append(DEFAULTS[i][0])
                    .append(" ").append(DEFAULTS[i][1]).append("; ");
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd.toString()});
            int code = p.waitFor();
            if (code == 0) {
                refreshAll();
                toast("已恢复默认配置");
            } else {
                toast("恢复失败，请确认已授予 root 权限");
            }
        } catch (Throwable t) {
            toast("恢复失败: " + t.getMessage());
        }
    }

    private void clearFocusAndKeyboard() {
        View current = getCurrentFocus();
        if (current != null) {
            current.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
