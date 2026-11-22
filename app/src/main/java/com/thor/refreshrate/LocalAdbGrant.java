package com.thor.refreshrate;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.security.auth.x500.X500Principal;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * One-time, on-device wireless ADB helper for the DroidDeck DUMP grant.
 * Never runs during game monitoring. No arbitrary shell commands, root, or PC.
 */
final class LocalAdbGrant {
    private static final String KEY_ALIAS = "thor_refresh_local_adb";
    private static final String DUMP_PERMISSION = "android.permission.DUMP";
    private static final String PAIR_HOST = "127.0.0.1";

    interface Callback {
        void finished(String message, boolean granted);
    }

    private LocalAdbGrant() { }

    static boolean granted(Context context) {
        return context.checkSelfPermission(DUMP_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void pairAndGrant(Context context, String pairingPort, String pairingCode,
                             String connectionPort, Callback callback) {
        final Context app = context.getApplicationContext();
        final int pairPort = parsePort(pairingPort);
        final int sessionPort = connectionPort.trim().isEmpty() ? 0 : parsePort(connectionPort);
        final String code = pairingCode.trim();
        final boolean pairedBefore = app.getSharedPreferences("local_adb_setup", Context.MODE_PRIVATE)
                .getBoolean("paired", false);
        final boolean requestPairing = !pairingPort.trim().isEmpty() || !code.isEmpty();
        if (granted(app)) {
            callback.finished("Permission already granted.", true);
            return;
        }
        if ((!pairedBefore && !requestPairing)
                || (requestPairing && (pairPort == 0 || !code.matches("[0-9]{6}")))
                || (!connectionPort.trim().isEmpty() && sessionPort == 0)) {
            callback.finished("Enter the six-digit code and pairing port (or retry " +
                    "a completed pairing using only the connection port).", false);
            return;
        }
        new Thread(() -> {
            LocalConnection manager = null;
            try {
                manager = new LocalConnection();
                manager.setTimeout(12, TimeUnit.SECONDS);
                manager.setThrowOnUnauthorised(true);
                // The initial pairing uses Android's short-lived six-digit code.
                // A paired client may retry a failed connection without another code.
                if (requestPairing) {
                    if (!manager.pair(PAIR_HOST, pairPort, code)) {
                        callback.finished("Pairing rejected. Open a new pairing code and retry.", false);
                        return;
                    }
                    app.getSharedPreferences("local_adb_setup", Context.MODE_PRIVATE)
                            .edit().putBoolean("paired", true).apply();
                }
                // The ADB TLS session port differs from the temporary pairing port.
                // Restrict discovery to *this* device, never another ADB device on Wi-Fi.
                int targetPort = sessionPort > 0 ? sessionPort : discoverLocalConnectPort(app);
                if (targetPort == 0) {
                    callback.finished("Paired successfully, but couldn't discover the connection port. " +
                            "Enter the connection port from Wireless debugging, " +
                            "leave pairing details blank, and press the grant button again.", false);
                    return;
                }
                boolean connected = manager.connect(PAIR_HOST, targetPort);
                if (!connected && !manager.isConnected()) {
                    callback.finished("Pairing succeeded, but the local ADB session did not connect. " +
                            "Enter the connection port from Wireless debugging and retry " +
                            "without pairing details. If ADB authorization was revoked, " +
                            "pair again using a fresh code.", false);
                    return;
                }
                String response = shellGrant(manager, app.getPackageName());
                boolean success = granted(app) || response.contains("THOR_GRANT_OK");
                if (success) {
                    callback.finished("DUMP granted. You can now turn off Wireless debugging.", true);
                } else {
                    callback.finished("Android rejected the permission grant: " + response, false);
                }
            } catch (Exception e) {
                callback.finished("Local ADB setup failed: " + usefulMessage(e), false);
            } finally {
                if (manager != null) {
                    try { manager.disconnect(); } catch (Exception ignored) { }
                }
            }
        }, "ThorLocalAdbSetup").start();
    }

    /** Discover only the Thor's own ADB TLS port. Never connect to LAN peers. */
    private static int discoverLocalConnectPort(Context context) throws InterruptedException {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return 0;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger found = new AtomicInteger();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int errorCode) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo service) {
                nsd.resolveService(service, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        if (isThisDevice(info.getHost()) && info.getPort() > 0) {
                            found.compareAndSet(0, info.getPort());
                            done.countDown();
                        }
                    }
                });
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(10, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // Some vendor builds don't support resolving their own mDNS announcements.
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
        }
        return found.get();
    }

    private static boolean isThisDevice(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (host.equals(addresses.nextElement())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String usefulMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    /** Command is constant except for our own package name; not a general-purpose shell. */
    private static String shellGrant(LocalConnection connection, String packageName) throws Exception {
        String command = "pm grant " + packageName + " android.permission.DUMP " +
                "2>&1; if [ $? -eq 0 ]; then echo THOR_GRANT_OK; else echo THOR_GRANT_FAILED; fi; " +
                "echo THOR_CMD_END";
        try (AdbStream stream = connection.openStream("shell:" + command)) {
            InputStream input = stream.openInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                String output = bytes.toString(StandardCharsets.UTF_8.name());
                if (output.contains("THOR_CMD_END")) {
                    return output.replace("THOR_CMD_END", "").trim();
                }
                if (bytes.size() > 8192) throw new IllegalStateException("Unexpected ADB output");
            }
            return bytes.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    /** Keys stay in Android Keystore; private key never leaves the device. */
    private static final class LocalConnection extends AbsAdbConnectionManager {
        private final PrivateKey privateKey;
        private final Certificate certificate;

        LocalConnection() throws Exception {
            setApi(Build.VERSION.SDK_INT);
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048)
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(new X500Principal("CN=ThorRefreshRateControl"))
                        .build();
                generator.initialize(spec);
                generator.generateKeyPair();
            }
            privateKey = (PrivateKey) keyStore.getKey(KEY_ALIAS, null);
            certificate = keyStore.getCertificate(KEY_ALIAS);
            if (privateKey == null || certificate == null) {
                throw new IllegalStateException("ADB identity not available");
            }
        }

        @Override protected PrivateKey getPrivateKey() { return privateKey; }
        @Override protected Certificate getCertificate() { return certificate; }
        @Override protected String getDeviceName() { return "ThorRefreshRateControl"; }
    }
}
