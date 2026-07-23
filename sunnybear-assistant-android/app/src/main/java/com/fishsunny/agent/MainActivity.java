package com.fishsunny.agent;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.fishsunny.agent.network.WebSocketManager;

/**
 * 主界面：服务器地址输入 + 连接/断开 控制。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "sunnybear_prefs";
    private static final String KEY_SERVER_HOST = "server_host";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String WS_PREFIX = "ws://";
    private static final String WS_PATH = "/android-bridge";

    private EditText etServerUrl;
    private EditText etUsername;
    private EditText etPassword;
    private Button btnConnect;
    private TextView tvStatus;
    private TextView tvDeviceId;
    private ImageView ivStatusDot;

    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerUrl = findViewById(R.id.et_server_url);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnConnect = findViewById(R.id.btn_connect);
        tvStatus = findViewById(R.id.tv_status);
        tvDeviceId = findViewById(R.id.tv_device_id);
        ivStatusDot = findViewById(R.id.iv_status_dot);

        // 启动 WebSocket 前台服务
        Intent serviceIntent = new Intent(this, WebSocketManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 恢复上次保存的内容
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedHost = prefs.getString(KEY_SERVER_HOST, "");
        if (!savedHost.isEmpty()) {
            etServerUrl.setText(savedHost);
        }
        String savedUser = prefs.getString(KEY_USERNAME, "");
        if (!savedUser.isEmpty()) {
            etUsername.setText(savedUser);
        }
        String savedPass = prefs.getString(KEY_PASSWORD, "");
        if (!savedPass.isEmpty()) {
            etPassword.setText(savedPass);
        }

        // 显示设备 ID
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        tvDeviceId.setText(deviceId != null ? deviceId : "未知");

        btnConnect.setOnClickListener(v -> {
            if (isConnected) {
                disconnect();
            } else {
                connect();
            }
        });
    }

    private void connect() {
        String host = etServerUrl.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            tvStatus.setText("请输入服务器地址");
            return;
        }
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // 自动补全 ws:// 前缀和 /android-bridge 路径
        if (!host.startsWith("ws://") && !host.startsWith("wss://")) {
            host = WS_PREFIX + host;
        }
        if (!host.endsWith(WS_PATH)) {
            host = host + WS_PATH;
        }

        // 保存设置
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SERVER_HOST, etServerUrl.getText().toString().trim())
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .apply();

        if (!isAccessibilityEnabled()) {
            tvStatus.setText("请先开启无障碍服务");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        WebSocketManager ws = WebSocketManager.getInstance();
        if (ws == null) {
            tvStatus.setText("服务未就绪，请稍后再试");
            return;
        }

        ws.setConnectionCallback((status, connected) -> runOnUiThread(() -> {
            tvStatus.setText(status);
            if (connected) updateConnectionState(true);
        }));

        ws.connect(host, username, password);
    }

    private void disconnect() {
        WebSocketManager ws = WebSocketManager.getInstance();
        if (ws != null) ws.disconnect();
        updateConnectionState(false);
        tvStatus.setText("未连接");
    }

    private void updateConnectionState(boolean connected) {
        isConnected = connected;
        if (connected) {
            btnConnect.setText("断开");
            btnConnect.setBackgroundResource(R.drawable.bg_button_disconnect);
            ivStatusDot.setImageResource(R.drawable.dot_green);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_connected));
        } else {
            btnConnect.setText("连接");
            btnConnect.setBackgroundResource(R.drawable.bg_button_primary);
            ivStatusDot.setImageResource(R.drawable.dot_gray);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_disconnected));
        }
    }

    private boolean isAccessibilityEnabled() {
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {
        }
        if (enabled != 1) return false;

        String serviceName = getPackageName() + "/" + AgentAccessibilityService.class.getName();
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabledServices != null && enabledServices.contains(serviceName);
    }
}
