package com.brctl.brightnesshook;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    // 参数定义：{属性名, 显示标签}
    private static final String[][] PARAMS = {
        {"lux_inc", "光感倍率（>1更亮，1=不变）"},
        {"smooth_delay", "光感延迟输出（毫秒，0=关闭）"},
        {"smooth_window", "滤波窗口（毫秒）"},
        {"filter_mode", "滤波方式（median / mean）"},
        {"low_max", "低亮度档上限（%）"},
        {"mid_max", "中亮度档上限（%）"},
        {"low_inc", "低亮度倍率"},
        {"mid_inc", "中亮度倍率"},
        {"hig_inc", "高亮度倍率"},
        {"brighten_debounce", "变亮防抖（毫秒）"},
        {"darken_debounce", "变暗防抖（毫秒）"},
    };

    private EditText[] fields;

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

        Button about = new Button(this);
        about.setText("关于");
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aboutLp.setMargins(0, dp(8), 0, 0);
        layout.addView(about, aboutLp);

        scroll.addView(layout);
        setContentView(scroll);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("亮度曲线调节 v1.1\n\n"
                        + "基于 LSPosed 的自动亮度调节模块\n"
                        + "光感拦截 · 中值滤波 · 分段倍率 · 防抖调节\n\n"
                        + "作者：酷安 折翼之舞007")
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
        for (int i = 0; i < PARAMS.length; i++) {
            fields[i].setText(getProp(PARAMS[i][0]));
        }
        toast("已刷新");
    }

    private void saveAll() {
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
