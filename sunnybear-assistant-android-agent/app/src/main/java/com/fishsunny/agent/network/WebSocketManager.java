package com.fishsunny.agent.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.fishsunny.agent.AgentAccessibilityService;
import com.fishsunny.agent.MainActivity;
import com.fishsunny.agent.protocol.CommandRequest;
import com.fishsunny.agent.protocol.CommandResponse;
import com.google.gson.Gson;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 前台服务，管理到 sunnybear-assistant 的 WebSocket 长连接。
 * <p>
 * 生命周期：
 * <ol>
 *   <li>外部调用 {@link #connect(String)} 启动连接</li>
 *   <li>连接成功后自动注册设备信息</li>
 *   <li>保持心跳，自动重连</li>
 *   <li>收到命令 → 转发给 {@link AgentAccessibilityService} 执行</li>
 *   <li>调用 {@link #disconnect()} 断开</li>
 * </ol>
 */
public class WebSocketManager extends Service {

    private static final String TAG = "SunnyBear-WS";
    private static final String CHANNEL_ID = "sunnybear_agent";
    private static final int NOTIFICATION_ID = 1001;

    // 单例访问 — 供 MainActivity 和 AgentAccessibilityService 使用
    private static WebSocketManager instance;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private String serverUrl;
    private String basicAuth;
    private String deviceId;
    private boolean shouldReconnect = false;
    private boolean connected = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Gson gson = new Gson();

    /** 等待响应的回调：requestId → pending future */
    private final Map<String, PendingResponse> pendingResponses = new ConcurrentHashMap<>();

    /** 连接状态变化回调 */
    private ConnectionCallback connectionCallback;

    // ==================== Service 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // 无限等待
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("等待连接"));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        disconnect();
        instance = null;
        super.onDestroy();
    }

    // ==================== 公开 API ====================

    public static WebSocketManager getInstance() {
        return instance;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getAndroidDeviceId() {
        return deviceId;
    }

    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    /** 连接到指定服务器 */
    public void connect(String url) {
        connect(url, null, null);
    }

    /** 连接到指定服务器（带 Basic Auth） */
    public void connect(String url, String username, String password) {
        this.serverUrl = url;
        this.basicAuth = null;
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            String credentials = username + ":" + password;
            this.basicAuth = "Basic " + android.util.Base64.encodeToString(
                    credentials.getBytes(), android.util.Base64.NO_WRAP);
        }
        this.shouldReconnect = true;
        doConnect();
    }

    /** 断开连接 */
    public void disconnect() {
        shouldReconnect = false;
        connected = false;
        if (webSocket != null) {
            webSocket.close(1000, "用户断开");
            webSocket = null;
        }
        updateStatus("未连接");
    }

    /** 发送响应到服务器 */
    public void sendResponse(CommandResponse response) {
        if (webSocket != null && connected) {
            String json = gson.toJson(response);
            webSocket.send(json);
        }
    }

    // ==================== WebSocket 核心逻辑 ====================

    private void doConnect() {
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.w(TAG, "服务器地址为空，跳过连接");
            return;
        }

        Log.i(TAG, "正在连接: " + serverUrl);
        updateStatus("连接中...");

        Request.Builder requestBuilder = new Request.Builder()
                .url(serverUrl);
        if (basicAuth != null) {
            requestBuilder.addHeader("Authorization", basicAuth);
        }
        Request request = requestBuilder.build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WebSocket 已连接");
                connected = true;
                updateStatus("已连接 ✓");
                updateNotification("已连接到 AI 助手");

                // 注册设备
                CommandResponse register = CommandResponse.register(deviceId,
                        Build.MANUFACTURER + " " + Build.MODEL);
                ws.send(gson.toJson(register));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "收到: " + text);
                try {
                    CommandRequest cmd = gson.fromJson(text, CommandRequest.class);
                    if (cmd.id != null && cmd.method != null) {
                        // 有 id 的命令 → 需要返回结果
                        CommandExecutor.execute(WebSocketManager.this, cmd, result -> {
                            CommandResponse resp = result != null
                                    ? CommandResponse.success(cmd.id, result)
                                    : CommandResponse.failure(cmd.id, "执行返回 null");
                            sendResponse(resp);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析命令失败: " + text, e);
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                // 不支持二进制消息
                Log.w(TAG, "收到不支持的二进制消息");
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket 关闭中: " + code + " " + reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket 已关闭: " + code + " " + reason);
                connected = false;
                updateStatus("已断开");
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket 连接失败: " + t.getMessage());
                connected = false;
                updateStatus("连接失败");
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        Log.i(TAG, "5 秒后重连...");
        mainHandler.postDelayed(() -> {
            if (shouldReconnect && !connected) {
                doConnect();
            }
        }, 5000);
    }

    // ==================== 通知栏 ====================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SunnyBear Agent",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持与 AI 助手的连接");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SunnyBear Agent")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void updateStatus(String text) {
        updateNotification(text);
        if (connectionCallback != null) {
            mainHandler.post(() -> connectionCallback.onStatusChanged(text, connected));
        }
    }

    // ==================== 回调接口 ====================

    public interface ConnectionCallback {
        void onStatusChanged(String status, boolean connected);
    }

    /** 内部使用：等待响应的回调 */
    interface ResponseCallback {
        void onResult(String result);
    }

    private static class PendingResponse {
        final ResponseCallback callback;
        final long createdAt;

        PendingResponse(ResponseCallback callback) {
            this.callback = callback;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
