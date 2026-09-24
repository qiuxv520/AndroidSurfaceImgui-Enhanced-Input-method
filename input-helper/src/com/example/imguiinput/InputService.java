package com.example.imguiinput;

import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A one-pixel, non-touchable Android window for ImGui's InputConnection. */
public final class InputService extends Service {
    private volatile PrintWriter output;
    private Socket socket;
    private EditText editor;
    private WindowManager windows;
    private boolean connecting;
    private final Handler handler = new Handler();
    private final ExecutorService writerThread = Executors.newSingleThreadExecutor();
    private boolean resetting;
    private boolean editQueued;
    private boolean imeWasVisible;
    private boolean synced;
    private boolean closing = true;
    private boolean showPending;
    private long session;
    private int showAttempts;
    private final Runnable showRequest = this::requestKeyboard;

    private void send(String message) {
        if (writerThread.isShutdown()) return;
        writerThread.execute(() -> {
            PrintWriter writer = output;
            if (writer != null) writer.println(message);
        });
    }

    private void emitEdit() {
        if (!synced || resetting || editor == null) return;
        send("EDIT " + session + " " + Math.max(0, editor.getSelectionStart()) + " "
                + Math.max(0, editor.getSelectionEnd()) + " " + Base64.encodeToString(
                editor.getText().toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private void queueEdit() {
        if (!synced || resetting || closing || editQueued) return;
        editQueued = true;
        handler.post(() -> {
            editQueued = false;
            if (!closing) emitEdit();
        });
    }

    private void addInputWindow() {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT);
        p.setTitle("dum");
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.dimAmount = 0;
        p.windowAnimations = 0;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        editor.setElevation(0);
        windows.addView(editor, p);
    }

    private void setWindowFocusable(boolean focusable) {
        if (!editor.isAttachedToWindow()) addInputWindow();
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) editor.getLayoutParams();
        int idleFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        p.flags = focusable ? p.flags & ~idleFlags : p.flags | idleFlags;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | (focusable ? WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                             : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        windows.updateViewLayout(editor, p);
    }

    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        Context theme = new ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar);
        editor = new EditText(theme) {
            @Override public void onWindowFocusChanged(boolean focused) {
                super.onWindowFocusChanged(focused);
                if (focused && showPending && !closing) {
                    handler.removeCallbacks(showRequest);
                    // Run after ViewRoot/IMM finish processing the focus event, without a fixed delay.
                    handler.post(showRequest);
                }
            }
            @Override protected void onSelectionChanged(int start, int end) {
                super.onSelectionChanged(start, end);
                queueEdit();
            }
            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) closeInput();
                    return true;
                }
                return super.onKeyPreIme(keyCode, event);
            }
        };
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setTextColor(Color.TRANSPARENT);
        editor.setCursorVisible(false);
        editor.setPadding(0, 0, 0, 0);
        editor.setMinWidth(0);
        editor.setMinHeight(0);
        if (Build.VERSION.SDK_INT >= 26)
            editor.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { queueEdit(); }
        });
        editor.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { closeInput(); return true; }
            return false;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            editor.setOnApplyWindowInsetsListener((view, insets) -> {
                if (!synced || closing) return insets;
                boolean visible = insets.isVisible(WindowInsets.Type.ime());
                if (synced) {
                    DisplayMetrics metrics = new DisplayMetrics();
                    windows.getDefaultDisplay().getRealMetrics(metrics);
                    int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    if (visible && bottom == 0) bottom = windows.getMaximumWindowMetrics()
                            .getWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
                    int top = bottom > 0 ? metrics.heightPixels - bottom : -1;
                    send("IME " + session + " " + (visible ? 1 : 0) + " " + top + " "
                            + metrics.widthPixels + " " + metrics.heightPixels);
                    Log.i("ImguiInput", "dum IME visible=" + visible + " top=" + top);
                }
                if (visible) {
                    showPending = false;
                    handler.removeCallbacks(showRequest);
                }
                if (imeWasVisible && !visible && !closing) closeInput();
                if (!closing) imeWasVisible = visible;
                return insets;
            });
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { closeInput(); stopSelf(); return START_NOT_STICKY; }
        if (!connecting) {
            connecting = true;
            int port = intent.getIntExtra("port", -1);
            String token = intent.getStringExtra("token");
            new Thread(() -> connect(port, token), "dum-input-connection").start();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void showKeyboard() {
        if (!synced) return;
        if (closing) {
            closing = false;
            imeWasVisible = false;
            setWindowFocusable(true);
        }
        showPending = true;
        showAttempts = 0;
        handler.removeCallbacks(showRequest);
        editor.requestFocus();
        handler.post(showRequest);
    }

    private void requestKeyboard() {
        if (!synced || closing || !showPending) return;
        editor.requestFocus();
        if (!editor.hasWindowFocus()) {
            if (++showAttempts < 60) handler.postDelayed(showRequest, 32);
            else closeInput();
            return;
        }
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (!ime.isActive(editor)) ime.restartInput(editor);
        boolean requested = ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        if (Build.VERSION.SDK_INT < 30 && requested) { imeWasVisible = true; showPending = false; }
        if (!imeWasVisible) {
            if (++showAttempts < 60) handler.postDelayed(showRequest, 32);
            else closeInput();
        }
        Log.i("ImguiInput", "dum showSoftInput=" + requested + " window=1x1 session=" + session);
    }

    private void applySync(String line) {
        String[] fields = line.split(" ", 7);
        if (fields.length != 7) return;
        resetting = true;
        try {
            session = Long.parseLong(fields[1]);
            int start = Integer.parseInt(fields[2]);
            int end = Integer.parseInt(fields[3]);
            int flags = Integer.parseInt(fields[4]);
            int limit = Math.max(0, Integer.parseInt(fields[5]));
            String text = new String(Base64.decode(fields[6], Base64.DEFAULT), StandardCharsets.UTF_8);
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | ((flags & 1) != 0 ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)
                    | ((flags & 2) != 0 ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
            editor.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    | ((flags & 1) != 0 ? EditorInfo.IME_ACTION_NONE : EditorInfo.IME_ACTION_DONE));
            editor.setFilters(new InputFilter[]{(source, sourceStart, sourceEnd, dest, destStart, destEnd) -> {
                int available = limit - (dest.subSequence(0, destStart).toString()
                        + dest.subSequence(destEnd, dest.length())).getBytes(StandardCharsets.UTF_8).length;
                String added = source.subSequence(sourceStart, sourceEnd).toString();
                if (added.getBytes(StandardCharsets.UTF_8).length <= available) return null;
                int cut = 0, bytes = 0;
                while (cut < added.length()) {
                    int cp = added.codePointAt(cut);
                    int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                    if (bytes + size > available) break;
                    bytes += size;
                    cut += Character.charCount(cp);
                }
                return source.subSequence(sourceStart, sourceStart + cut);
            }});
            editor.setText(text);
            editor.setSelection(Math.max(0, Math.min(start, editor.length())),
                    Math.max(0, Math.min(end, editor.length())));
            synced = true;
            if (!closing) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).restartInput(editor);
            Log.i("ImguiInput", "dum synced session=" + session + " chars=" + editor.length());
        } finally { resetting = false; }
        if (!editor.isAttachedToWindow()) addInputWindow();
    }

    private void connect(int port, String token) {
        if (port < 1 || token == null) { handler.post(() -> { closeInput(); stopSelf(); }); return; }
        try {
            socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
            socket.setTcpNoDelay(true);
            output = new PrintWriter(socket.getOutputStream(), true);
            output.println("AUTH " + token);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                final String message = line;
                handler.post(() -> {
                    try {
                        if (message.startsWith("SYNC ")) applySync(message);
                        else if (message.equals("SHOW " + session)) showKeyboard();
                        else if (message.equals("HIDE " + session)) closeInput();
                    } catch (Exception e) { Log.e("ImguiInput", "dum protocol", e); closeInput(); }
                });
            }
        } catch (Exception e) {
            if (!closing) Log.e("ImguiInput", "dum connection", e);
        }
        handler.post(() -> { closeInput(); stopSelf(); });
    }

    private void closeInput() {
        if (!closing) {
            emitEdit();
            closing = true;
            imeWasVisible = false;
            showPending = false;
            handler.removeCallbacks(showRequest);
            if (editor != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(editor.getWindowToken(), 0);
            // Keep the tiny, non-touchable window warm, but return focus to the foreground app.
            // Removing the last overlay made this device repeatedly kill the service between edits.
            if (editor != null && editor.isAttachedToWindow()) setWindowFocusable(false);
            send("HIDDEN " + session);
        }
    }

    @Override public void onDestroy() {
        closeInput();
        handler.removeCallbacksAndMessages(null);
        if (editor != null && editor.isAttachedToWindow()) windows.removeViewImmediate(editor);
        super.onDestroy();
        writerThread.execute(() -> {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        });
        writerThread.shutdown();
    }
}
