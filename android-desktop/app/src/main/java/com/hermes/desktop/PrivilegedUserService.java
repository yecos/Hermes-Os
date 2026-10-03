package com.hermes.desktop;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PrivilegedUserService extends IPrivilegedBridge.Stub {

    private Object inputManager;
    private Method injectInputEvent;
    private Method setDisplayId;
    private Method setButtonState;

    public PrivilegedUserService() {
        initializeInputBridge();
    }

    private void initializeInputBridge() {
        try {
            Class<?> clazz = Class.forName("android.hardware.input.InputManager");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            inputManager = getInstance.invoke(null);

            injectInputEvent = clazz.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent.class,
                    int.class
            );
            injectInputEvent.setAccessible(true);

            setDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);

            try {
                setButtonState = MotionEvent.class.getDeclaredMethod("setButtonState", int.class);
                setButtonState.setAccessible(true);
            } catch (Throwable ignored) {
                setButtonState = null;
            }
        } catch (Throwable ignored) {
            inputManager = null;
            injectInputEvent = null;
            setDisplayId = null;
        }
    }

    @Override
    public String status() {
        return "uid=" + Process.myUid()
                + ";directInput=" + (inputManager != null && injectInputEvent != null);
    }

    @Override
    public boolean movePointer(int displayId, float x, float y, boolean dragging) {
        int action = dragging ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        int buttons = dragging ? MotionEvent.BUTTON_PRIMARY : 0;
        return injectMouse(displayId, action, x, y, buttons);
    }

    @Override
    public boolean pointerButton(int displayId, float x, float y, boolean down) {
        int action = down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP;
        int buttons = down ? MotionEvent.BUTTON_PRIMARY : 0;
        if (injectMouse(displayId, action, x, y, buttons)) {
            return true;
        }

        String motion = down ? "DOWN" : "UP";
        return shellOk(
                "/system/bin/input", "mouse", "-d", String.valueOf(displayId),
                "motionevent", motion, String.valueOf(x), String.valueOf(y)
        );
    }

    @Override
    public boolean click(int displayId, float x, float y) {
        return shellOk(
                "/system/bin/input", "mouse", "-d", String.valueOf(displayId),
                "tap", String.valueOf(x), String.valueOf(y)
        );
    }

    @Override
    public boolean scroll(int displayId, float x, float y, float delta) {
        if (shellOk(
                "/system/bin/input", "mouse", "-d", String.valueOf(displayId),
                "scroll", String.valueOf(x), String.valueOf(y),
                "--axis", "VSCROLL," + delta
        )) {
            return true;
        }

        float y2 = y + (delta > 0 ? -180f : 180f);
        return shellOk(
                "/system/bin/input", "touchscreen", "-d", String.valueOf(displayId),
                "swipe", String.valueOf(x), String.valueOf(y),
                String.valueOf(x), String.valueOf(y2), "120"
        );
    }

    @Override
    public boolean keyEvent(int displayId, int keyCode) {
        return shellOk(
                "/system/bin/input", "keyboard", "-d", String.valueOf(displayId),
                "keyevent", String.valueOf(keyCode)
        );
    }

    @Override
    public boolean keyCombination(int displayId, int[] keyCodes) {
        List<String> command = new ArrayList<>();
        command.add("/system/bin/input");
        command.add("keyboard");
        command.add("-d");
        command.add(String.valueOf(displayId));
        command.add("keycombination");
        for (int keyCode : keyCodes) {
            command.add(String.valueOf(keyCode));
        }
        return shellOk(command.toArray(new String[0]));
    }

    private boolean injectMouse(
            int displayId,
            int action,
            float x,
            float y,
            int buttonState
    ) {
        if (inputManager == null || injectInputEvent == null || setDisplayId == null) {
            return false;
        }

        MotionEvent event = null;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            event = MotionEvent.obtain(now, now, action, x, y, 0);
            event.setSource(InputDevice.SOURCE_MOUSE);
            setDisplayId.invoke(event, displayId);
            if (setButtonState != null) {
                setButtonState.invoke(event, buttonState);
            }

            Object result = injectInputEvent.invoke(inputManager, event, 0);
            return !(result instanceof Boolean) || ((Boolean) result);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (event != null) {
                event.recycle();
            }
        }
    }

    private boolean shellOk(String... command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Throwable ignored) {
            if (process != null) process.destroy();
            return false;
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
