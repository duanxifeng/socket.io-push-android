package com.yy.httpproxy.socketio;

import android.content.Context;
import android.os.Handler;
import android.util.Base64;
import android.util.Pair;

import com.yy.httpproxy.AndroidLoggingHandler;
import com.yy.httpproxy.requester.RequestInfo;
import com.yy.httpproxy.service.DnsHandler;
import com.yy.httpproxy.service.PushedNotification;
import com.yy.httpproxy.stats.Stats;
import com.yy.httpproxy.subscribe.CachedSharedPreference;
import com.yy.httpproxy.subscribe.PushCallback;
import com.yy.httpproxy.subscribe.PushSubscriber;
import com.yy.httpproxy.thirdparty.NotificationProvider;
import com.yy.httpproxy.util.JSONUtil;
import com.yy.httpproxy.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import io.socket.engineio.client.transports.WebSocket;


public class SocketIOProxyClient implements PushSubscriber {

    private static final int PROTOCOL_VERSION = 1;
    private static String TAG = "SocketIOProxyClient";
    private final NotificationProvider notificationProvider;
    private PushCallback pushCallback;

    private String pushId;
    private Callback socketCallback;
    private CachedSharedPreference cachedSharedPreference;
    private Map<String, Boolean> topics = new HashMap<>();
    private Map<String, String> topicToLastPacketId = new HashMap<>();
    private boolean connected = false;
    private String uid;
    private Stats stats = new Stats();
    private String host = "";
    private String packageName = "";
    private String[] tags = new String[]{};
    private Socket socket;
    private List<Pair> cachedEvent = new ArrayList<Pair>();

    public void unsubscribeBroadcast(String topic) {
        topics.remove(topic);
        topicToLastPacketId.remove(topic);
        JSONObject data = new JSONObject();
        try {
            data.put("topic", topic);
            sendObjectToServer("unsubscribeTopic", data);
        } catch (JSONException e) {
        }
    }

    public String getHost() {
        return host;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public interface Callback {
        void onNotification(PushedNotification notification);

        void onConnect();

        void onDisconnect();
    }

    private final Emitter.Listener connectListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            stats.onConnect();
            sendPushIdAndTopicToServer();
            sendCachedObjectToServer();
        }
    };

    private final Emitter.Listener disconnectListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            connected = false;
            uid = null;
            stats.onDisconnect();
            if (socketCallback != null) {
                socketCallback.onDisconnect();
            }
        }
    };

    public void unbindUid() {
        sendObjectToServer("unbindUid", null);
    }

    private void sendPushIdAndTopicToServer() {
        if (pushId != null) {
            Log.i(TAG, "sendPushIdAndTopicToServer " + pushId);
            JSONObject object = new JSONObject();
            try {
                object.put("id", pushId);
                object.put("version", PROTOCOL_VERSION);
                object.put("platform", "android");
                if (topics.size() > 0) {
                    JSONArray array = new JSONArray();
                    object.put("topics", array);
                    for (String topic : topics.keySet()) {
                        array.put(topic);
                    }
                    JSONObject lastPacketIds = new JSONObject();
                    object.put("lastPacketIds", lastPacketIds);
                    for (Map.Entry<String, String> entry : topicToLastPacketId.entrySet()) {
                        if (entry.getValue() != null && topics.containsKey(entry.getKey())) {
                            lastPacketIds.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (topics.containsKey("noti")) {
                        String lastNotiId = cachedSharedPreference.get("lastNotiId");
                        if (lastNotiId == null) {
                            lastNotiId = "0";
                        }
                        Log.i(TAG, "lastNotiId " + lastNotiId);
                        lastPacketIds.put("noti", lastNotiId);
                    }
                }
                String lastUniCastId = cachedSharedPreference.get("lastUnicastId");
                if (lastUniCastId != null) {
                    object.put("lastUnicastId", lastUniCastId);
                }
                sendObjectToServer("pushId", object);
            } catch (JSONException e) {
                Log.e(TAG, "connectListener error ", e);
            }
        }
    }

    private final Emitter.Listener pushIdListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject data = (JSONObject) args[0];
            String pushId = data.optString("id");
            uid = data.optString("uid", "");
            tags = JSONUtil.toStringArray(data.optJSONArray("tags"));
            Log.d(TAG, "on pushId " + pushId + " ,uid " + uid);
            connected = true;
            if (socketCallback != null) {
                socketCallback.onConnect();
            }
            sendTokenToServer();
        }
    };

    public void sendTokenToServer() {
        if (notificationProvider != null && notificationProvider.getToken() != null) {
            Log.i(TAG, "sendTokenToServer " + pushId);
            JSONObject object = new JSONObject();
            try {
                object.put("token", notificationProvider.getToken());
                object.put("type", notificationProvider.getType());
                object.put("package_name", packageName);
                sendObjectToServer("token", object, true);
            } catch (JSONException e) {
                Log.e(TAG, "sendTokenToServer error ", e);
            }
        }
    }

    public void sendNotificationClick(String id) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            if (notificationProvider != null) {
                object.put("type", notificationProvider.getType());
            }
            Log.i(TAG, "notificationClick " + id);
            sendObjectToServer("notificationClick", object, true);
        } catch (JSONException e) {
            Log.e(TAG, "sendNotificationClick error ", e);
        }
    }

    public void bindUid(Map<String, String> data) {
        Log.i(TAG, "bindUid " + pushId);
        JSONObject object = JSONUtil.toJSONObject(data);
        sendObjectToServer("bindUid", object);
    }

    private final Emitter.Listener notificationListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            try {
                JSONObject data = (JSONObject) args[0];
                JSONObject android = data.optJSONObject("android");
                Log.i(TAG, "on notification topic " + android);
                String id = data.optString("id", null);
                updateLastPacketId(id, data.optString("ttl", null), data.optString("unicast", null), "noti");
                long timestamp = data.optLong("timestamp", 0);
                if (timestamp > 0 && id != null) {
                    JSONObject object = new JSONObject();
                    object.put("id", id);
                    object.put("timestamp", timestamp);
                    Log.d(TAG, "notificationReply " + id + " " + timestamp);
                    sendObjectToServer("notificationReply", object, true);
                }

                String title = android.optString("title");
                if (socketCallback != null && title != null && !title.isEmpty()) {
                    socketCallback.onNotification(new PushedNotification(id, android));
                }

            } catch (Exception e) {
                Log.e(TAG, "handle notification error ", e);
            }
        }
    };

    private void updateLastPacketId(String id, Object ttl, Object unicast, String topic) {
        if (id != null && ttl != null) {
            Log.d(TAG, "on push topic " + topic + " id " + id);
            Boolean reciveTtl = topics.get(topic);
            if (reciveTtl == null) {
                reciveTtl = false;
            }
            if (unicast != null) {
                cachedSharedPreference.save("lastUnicastId", id);
            } else if (reciveTtl && topic != null) {
                if (topic.equals("noti")) {
                    cachedSharedPreference.save("lastNotiId", id);
                }
                topicToLastPacketId.put(topic, id);
            }
        }
    }

    private final Emitter.Listener version2PushListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (pushCallback != null) {
                try {
                    String json = args[0].toString();

                    Log.i(TAG, "on push topic data: " + json);
                    pushCallback.onPush(json);

                    if (args.length > 1) {
                        JSONArray ttlData = (JSONArray) args[1];
                        String topic = ttlData.optString(0, null);
                        String id = ttlData.optString(1, null);

                        String ttl = "1";

                        int unicast = ttlData.optInt(2, 0);
                        String unicastObj = null;
                        if (unicast == 1) {
                            unicastObj = "1";
                        }
                        updateLastPacketId(id, ttl, unicastObj, topic);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "handle push error ", e);
                }
            }
        }
    };


    private final Emitter.Listener pushListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (pushCallback != null) {
                try {
                    JSONObject data = (JSONObject) args[0];
                    String topic = data.optString("topic", null);
                    if (topic == null) {
                        topic = data.optString("t", null);
                    }
                    String dataBase64 = data.optString("data", null);
                    if (dataBase64 == null) {
                        dataBase64 = data.optString("d", null);
                    }
                    byte[] dataBytes;
                    if (dataBase64 == null) {
                        String json = data.optString("j");
                        pushCallback.onPush(json);
                    } else {
                        dataBytes = Base64.decode(dataBase64, Base64.DEFAULT);
                        pushCallback.onPush(new String(dataBytes, "UTF-8"));
                    }

                    String id = data.optString("id", null);
                    if (id == null) {
                        id = data.optString("i", null);
                    }

                    String ttl = data.optString("ttl", null);
                    if (ttl == null) {
                        ttl = data.optString("t", null);
                    }

                    String unicast = data.optString("unicast", null);
                    if (unicast == null) {
                        unicast = data.optString("u", null);
                    }

                    updateLastPacketId(id, ttl, unicast, topic);
                } catch (Exception e) {
                    Log.e(TAG, "handle push error ", e);
                }
            }
        }
    };

    private Handler handler = new Handler();

    private Runnable statsTask = new Runnable() {
        @Override
        public void run() {
            sendStats();
        }
    };

    private void sendStats() {
        try {
            JSONArray requestStats = stats.getRequestJsonArray();
            if (requestStats.length() > 0) {
                JSONObject object = new JSONObject();
                object.put("requestStats", requestStats);
                sendObjectToServer("stats", object);
                Log.d(TAG, "send stats " + requestStats.length());
            }
        } catch (JSONException e) {
            Log.e(TAG, "sendStats error", e);
        }
        postStatsTask();
    }

    private void postStatsTask() {
        handler.removeCallbacks(statsTask);
        handler.postDelayed(statsTask, 10 * 60 * 1000L);
    }

    public void reportStats(String path, int successCount, int errorCount, int latency) {
        stats.reportStats(path, successCount, errorCount, latency);
    }

    public SocketIOProxyClient(Context context, String host, String pushId, NotificationProvider provider, DnsHandler dnsHandler) {
        this.packageName = context.getPackageName();
        cachedSharedPreference = new CachedSharedPreference(context);
        try {
            AndroidLoggingHandler.reset(new AndroidLoggingHandler());
            java.util.logging.Logger.getLogger("").setLevel(Level.FINEST);
        } catch (Exception e) {

        }
        this.host = host;
        this.notificationProvider = provider;
        if (provider == null) {
            topics.put("noti", true);
        }
        if (pushId == null) {
            pushId = cachedSharedPreference.get("pushId");
        }
        this.pushId = pushId;
        try {
            IO.Options opts = new IO.Options();
            opts.transports = new String[]{WebSocket.NAME};
            opts.dnsHandler = dnsHandler;
            if (host.startsWith("https")) {
                try {
                    opts.sslContext = SSLContext.getInstance("TLS");
                    TrustManager tm = new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };
                    opts.sslContext.init(null, new TrustManager[]{tm}, null);
                    opts.hostnameVerifier = new HostnameVerifier() {

                        @Override
                        public boolean verify(String s, SSLSession sslSession) {
                            return true;
                        }
                    };
                } catch (Exception e) {
                    Log.e(TAG, "ssl init error ", e);
                }
            }
            Log.i(TAG, "connecting " + packageName + " " + host);
            socket = IO.socket(host, opts);
            socket.on(Socket.EVENT_CONNECT, connectListener);
            socket.on("pushId", pushIdListener);
            socket.on("push", pushListener);
            socket.on("p", version2PushListener);
            socket.on("noti", notificationListener);
            socket.on("n", notificationListener);
            socket.on(Socket.EVENT_DISCONNECT, disconnectListener);
            socket.connect();
            postStatsTask();

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }

    public void disconnect() {
        socket.disconnect();
        this.socketCallback = null;
        this.pushCallback = null;
    }

    public void request(RequestInfo requestInfo) {
        try {
            Log.d(TAG, "request " + requestInfo.getPath());

            requestInfo.setTimestamp();

            JSONObject object = new JSONObject();
            if (requestInfo.getBody() != null) {
                object.put("data", Base64.encodeToString(requestInfo.getBody(), Base64.NO_WRAP));
            }
            object.put("path", requestInfo.getPath());
            object.put("sequenceId", String.valueOf(requestInfo.getSequenceId()));

            sendObjectToServer("packetProxy", object);

        } catch (Exception e) {
        }
    }

    @Override
    public void subscribeBroadcast(String topic, boolean receiveTtlPackets) {
        if (!topics.containsKey(topic)) {
            topics.put(topic, receiveTtlPackets);
            JSONObject data = new JSONObject();
            try {
                data.put("topic", topic);
                String lastPacketId = topicToLastPacketId.get(topic);
                if (lastPacketId != null && receiveTtlPackets) {
                    data.put("lastPacketId", lastPacketId);
                }
                sendObjectToServer("subscribeTopic", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void addTag(String tag) {
        JSONObject data = new JSONObject();
        try {
            data.put("tag", tag);
            sendObjectToServer("addTag", data);
        } catch (JSONException e) {
        }
    }

    public void removeTag(String tag) {
        JSONObject data = new JSONObject();
        try {
            data.put("tag", tag);
            sendObjectToServer("removeTag", data);
        } catch (JSONException e) {
        }
    }

    private void sendObjectToServer(String event, Object object) {
        sendObjectToServer(event, object, false);
    }

    private void sendObjectToServer(String event, Object object, boolean cached) {
        if (socket.connected()) {
            if (object == null) {
                socket.emit(event);
            } else {
                socket.emit(event, object);
            }
        } else if (cached) {
            cachedEvent.add(new Pair<>(event, object));
        }
    }

    private void sendCachedObjectToServer() {
        List<Pair> newList = new ArrayList<>(cachedEvent);
        cachedEvent.clear();
        for (Pair<String, Objects> p : newList) {
            sendObjectToServer(p.first, p.second);
        }
    }

    public void setPushCallback(PushCallback pushCallback) {
        this.pushCallback = pushCallback;
    }

    public void setSocketCallback(Callback socketCallback) {
        this.socketCallback = socketCallback;
    }

    public String getUid() {
        return uid;
    }

    public boolean isConnected() {
        return connected;
    }
}