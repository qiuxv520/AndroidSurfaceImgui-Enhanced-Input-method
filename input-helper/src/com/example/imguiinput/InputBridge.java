package com.example.imguiinput;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root process owns the temporary dum package and outlives a crashed native parent. */
public final class InputBridge {
    private static final String PACKAGE = "com.example.imguiinput";
    private static File payloadApk;
    private static File payloadDir;
    private static final PrintWriter output = new PrintWriter(System.out, true);
    private static final AtomicBoolean closing = new AtomicBoolean();
    private static final AtomicBoolean cleaned = new AtomicBoolean();
    private static Handler handler;
    private static ServerSocket server;
    private static Socket activeSocket;
    private static PrintWriter client;
    private static ClipboardManager clipboard;
    private static Context context;
    private static boolean installed;
    private static boolean shown;
    private static boolean starting;
    private static long showStarted;
    private static boolean measuringShow;
    private static String user;
    private static String token;
    private static String session = "0";
    private static String editorSync = "SYNC 0 0 0 0 1023 ";
    private static RandomAccessFile lockFile;
    private static FileLock ownerLock;

    private static void trace(String event) {
        // Timing only: never log editor contents, clipboard data, or authentication tokens.
        System.err.println("dum input t=" + SystemClock.uptimeMillis() + " " + event);
    }

    private static void send(String message) {
        synchronized (output) { output.println(message); }
    }

    private static String encode(String text) {
        return Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String decode(String text) {
        return new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static Context systemContext() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Method create = cls.getDeclaredMethod("systemMain");
        create.setAccessible(true);
        Object thread = create.invoke(null);
        Method getContext = cls.getDeclaredMethod("getSystemContext");
        getContext.setAccessible(true);
        return (Context) getContext.invoke(thread);
    }

    private static String run(File input, String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) try (FileInputStream file = new FileInputStream(input)) {
                byte[] bytes = new byte[16384];
                int count;
                while ((count = file.read(bytes)) != -1) stdin.write(bytes, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException(args[0] + " exited " + code + ": " + result);
        return result.toString().trim();
    }

    private static void cleanup() {
        if (!cleaned.compareAndSet(false, true)) return;
        if (installed) {
            try {
                try { run(null, "/system/bin/cmd", "deviceidle", "tempwhitelist", "-u", user, "-r", PACKAGE); }
                catch (Exception ignored) {}
                String result = run(null, "/system/bin/pm", "uninstall", PACKAGE);
                if (!result.contains("Success")) throw new IllegalStateException(result);
                Log.i("ImguiInput", "dum package uninstalled");
                send("UNINSTALLED");
            } catch (Exception e) {
                Log.e("ImguiInput", "dum uninstall failed", e);
                send("ERROR dum uninstall failed: " + e.getMessage());
            }
        }
        // Exact files from our private per-run directory only; no recursive deletion.
        if (payloadApk != null && payloadApk.exists() && !payloadApk.delete())
            send("ERROR cannot remove temporary dum APK");
        if (payloadDir != null && payloadDir.exists() && !payloadDir.delete())
            send("ERROR cannot remove temporary dum directory");
        trace("runtime payload cleaned");
    }

    private static void shutdown() {
        if (!closing.compareAndSet(false, true)) return;
        shown = false;
        sendClient("HIDE " + session);
        try { server.close(); } catch (Exception ignored) {}
        new Thread(() -> {
            cleanup();
            send("STOPPED");
            System.exit(0);
        }, "dum-uninstall").start();
    }

    private static void sendClient(String message) {
        if (client != null) client.println(message);
    }

    private static void show() {
        shown = true;
        showStarted = SystemClock.uptimeMillis();
        measuringShow = true;
        trace("show session=" + session + " warm=" + (client != null));
        if (client != null) {
            sendClient("SHOW " + session);
            if (!client.checkError()) return;
            try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
            client = null;
            activeSocket = null;
        }
        ensureService();
    }

    private static void ensureService() {
        if (closing.get() || client != null) return;
        if (starting) return;
        starting = true;
        trace("service launch");
        new Thread(() -> {
            try {
                // A short exemption permits this package's background service startup.
                // It expires after 10 seconds; no persistent battery setting is changed.
                run(null, "/system/bin/cmd", "deviceidle", "tempwhitelist", "-u", user, "-d", "10000", PACKAGE);
                String result = run(null, "/system/bin/am", "startservice", "--user", user,
                        "-n", PACKAGE + "/.InputService", "--ei", "port",
                        Integer.toString(server.getLocalPort()), "--es", "token", token);
                if (result.contains("Error:")) throw new IllegalStateException(result);
            } catch (Exception e) {
                send("ERROR dum start failed: " + e.getMessage());
            } finally { handler.post(() -> starting = false); }
        }, "dum-show").start();
    }

    private static void command(String line) {
        if (closing.get()) return;
        try {
            if (line.startsWith("SYNC ")) {
                session = line.split(" ", 3)[1];
                editorSync = line;
                sendClient(line);
            } else if (line.startsWith("SHOW ")) {
                if (line.substring(5).equals(session)) show();
            } else if (line.startsWith("HIDE ")) {
                shown = false;
                sendClient("HIDE " + session);
            } else if (line.equals("QUIT")) {
                shutdown();
            } else if (line.equals("GETCLIP")) {
                ClipData clip = clipboard.getPrimaryClip();
                CharSequence value = clip != null && clip.getItemCount() > 0
                        ? clip.getItemAt(0).coerceToText(context) : "";
                send("C " + encode(value == null ? "" : value.toString()));
            } else if (line.startsWith("SETCLIP ")) {
                clipboard.setPrimaryClip(ClipData.newPlainText("dum", decode(line.substring(8))));
            }
        } catch (Exception e) {
            Log.e("ImguiInput", "dum command failed", e);
            send("ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static void acceptClients() {
        while (!closing.get()) {
            Socket accepted = null;
            try (Socket socket = server.accept()) {
                accepted = socket;
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                if (!("AUTH " + token).equals(reader.readLine())) continue;
                socket.setSoTimeout(0);
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                handler.post(() -> {
                    activeSocket = socket;
                    client = writer;
                    trace("service connected");
                    sendClient(editorSync);
                    sendClient((shown && !closing.get() ? "SHOW " : "HIDE ") + session);
                });
                String line;
                while ((line = reader.readLine()) != null) {
                    final String message = line;
                    handler.post(() -> {
                        if (activeSocket != socket) return;
                        if (measuringShow && message.startsWith("IME " + session + " 1 ")) {
                            trace("visible session=" + session + " latency_ms="
                                    + (SystemClock.uptimeMillis() - showStarted));
                            measuringShow = false;
                        }
                        if (message.startsWith("EDIT " + session + " ")
                                || message.startsWith("IME " + session + " ")) send(message);
                        else if (message.equals("HIDDEN " + session)) {
                            shown = false;
                            measuringShow = false;
                            trace("hidden session=" + session);
                            send(message);
                        }
                    });
                }
            } catch (Exception e) {
                if (!closing.get()) Log.w("ImguiInput", "dum client connection closed", e);
            } finally {
                final Socket disconnected = accepted;
                if (disconnected != null) handler.post(() -> {
                    if (activeSocket == disconnected) {
                        client = null;
                        activeSocket = null;
                        trace("service disconnected");
                        if (shown) { shown = false; send("HIDDEN " + session); }
                    }
                });
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected embedded APK path");
        File apk = new File(args[0]).getCanonicalFile();
        File directory = apk.getParentFile();
        if (!apk.getName().equals("dum_input.apk") || directory == null
                || !new File("/data/adb").equals(directory.getParentFile())
                || !directory.getName().matches("dum-input-[a-zA-Z0-9]{6}"))
            throw new IllegalArgumentException("Invalid private runtime path");
        payloadApk = apk;
        payloadDir = directory;
        Runtime.getRuntime().addShutdownHook(new Thread(InputBridge::cleanup, "dum-shutdown-cleanup"));
        // One native owner at a time: a second run must not uninstall the first run's helper.
        lockFile = new RandomAccessFile("/data/adb/dum_input.lock", "rw");
        ownerLock = lockFile.getChannel().tryLock();
        if (ownerLock == null) { send("ERROR dum input is already in use"); return; }
        Looper.prepareMainLooper();
        handler = new Handler(Looper.getMainLooper());
        context = systemContext();
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (!apk.isFile()) { send("ERROR Missing embedded APK"); return; }
        user = run(null, "/system/bin/am", "get-current-user");
        if (!user.matches("[0-9]+")) throw new IllegalStateException("Unknown Android user");
        // Stream the root-owned APK so PackageInstaller need not open /data/adb itself.
        String result = run(apk, "/system/bin/pm", "install", "-r", "--user", user,
                "-S", Long.toString(apk.length()));
        if (!result.contains("Success")) { send("ERROR dum install failed: " + result); return; }
        installed = true;
        run(null, "/system/bin/appops", "set", "--user", user, PACKAGE, "SYSTEM_ALERT_WINDOW", "allow");
        Log.i("ImguiInput", "dum package installed for native window lifetime");
        byte[] secret = new byte[24];
        new SecureRandom().nextBytes(secret);
        token = Base64.encodeToString(secret, Base64.NO_WRAP | Base64.URL_SAFE);
        server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(InputBridge::acceptClients, "dum-input-relay");
        accept.setDaemon(true);
        accept.start();
        Thread input = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String current = line;
                    handler.post(() -> command(current));
                }
            } catch (Exception e) { Log.w("ImguiInput", "native pipe closed", e); }
            // EOF also happens when the native owner is killed or crashes.
            handler.post(InputBridge::shutdown);
        }, "dum-native-lifetime");
        input.setDaemon(true);
        input.start();
        send("READY");
        send("IME_AVAILABLE");
        // Prepare the service while the native window is starting. Its idle window cannot take focus.
        handler.post(InputBridge::ensureService);
        Looper.loop();
    }
}
