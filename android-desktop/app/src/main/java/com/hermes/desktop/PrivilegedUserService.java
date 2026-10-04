package com.hermes.desktop;

import android.os.Process;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PrivilegedUserService extends IPrivilegedBridge.Stub {

    private Object inputManager;
    private Method injectInputEvent;
    private Method setDisplayId;
    private Method setButtonState;

    private final Object virtualMouseLock = new Object();
    private java.lang.Process virtualMouseProcess;
    private BufferedWriter virtualMouseWriter;
    private String virtualMouseError;
    private float virtualMouseRemainderX;
    private float virtualMouseRemainderY;

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
        boolean uinputReady;
        synchronized (virtualMouseLock) {
            uinputReady = virtualMouseProcess != null && virtualMouseProcess.isAlive() && virtualMouseWriter != null;
        }
        return "uid=" + Process.myUid()
                + ";directInput=" + (inputManager != null && injectInputEvent != null)
                + ";uinputMouse=" + uinputReady
                + (virtualMouseError == null ? "" : ";uinputError=" + virtualMouseError);
    }

    @Override
    public boolean startVirtualMouse() {
        synchronized (virtualMouseLock) {
            if (virtualMouseProcess != null && virtualMouseProcess.isAlive() && virtualMouseWriter != null) {
                return true;
            }

            stopVirtualMouseLocked();
            virtualMouseError = null;

            try {
                ProcessBuilder builder = androidProcessBuilder("/system/bin/uinput", "-");
                virtualMouseProcess = builder.start();
                virtualMouseWriter = new BufferedWriter(
                        new OutputStreamWriter(virtualMouseProcess.getOutputStream())
                );

                // Android 12 uinput parser uses numeric ioctl/event codes.
                writeVirtualMouseLocked(
                        "{"
                                + "\"id\":501,"
                                + "\"command\":\"register\","
                                + "\"name\":\"Hermes Virtual Mouse\","
                                + "\"vid\":6353,"
                                + "\"pid\":20560,"
                                + "\"bus\":\"usb\","
                                + "\"configuration\":["
                                + "{\"type\":100,\"data\":[1,2]},"
                                + "{\"type\":101,\"data\":[272,273,274,277,278]},"
                                + "{\"type\":102,\"data\":[0,1,6,8]}"
                                + "]"
                                + "}"
                );
                writeVirtualMouseLocked(
                        "{\"id\":501,\"command\":\"delay\",\"duration\":650}"
                );

                // Keep stdin open so the uinput device stays registered.
                virtualMouseWriter.flush();
                try {
                    Thread.sleep(750);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (!virtualMouseProcess.isAlive()) {
                    virtualMouseError = "uinput exited";
                    stopVirtualMouseLocked();
                    return false;
                }

                return true;
            } catch (Throwable error) {
                virtualMouseError = error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ":" + error.getMessage());
                stopVirtualMouseLocked();
                return false;
            }
        }
    }

    @Override
    public boolean virtualMouseMove(float dx, float dy) {
        synchronized (virtualMouseLock) {
            if (!ensureVirtualMouseLocked()) return false;

            virtualMouseRemainderX += dx;
            virtualMouseRemainderY += dy;

            int relX = (int) virtualMouseRemainderX;
            int relY = (int) virtualMouseRemainderY;
            virtualMouseRemainderX -= relX;
            virtualMouseRemainderY -= relY;

            if (relX == 0 && relY == 0) return true;

            return injectVirtualEventsLocked(
                    2, 0, relX,
                    2, 1, relY,
                    0, 0, 0
            );
        }
    }

    @Override
    public boolean virtualMouseButton(int button, boolean down) {
        int code = buttonCode(button);
        if (code == 0) return false;

        synchronized (virtualMouseLock) {
            if (!ensureVirtualMouseLocked()) return false;
            return injectVirtualEventsLocked(
                    1, code, down ? 1 : 0,
                    0, 0, 0
            );
        }
    }

    @Override
    public boolean virtualMouseClick(int button) {
        int code = buttonCode(button);
        if (code == 0) return false;

        synchronized (virtualMouseLock) {
            if (!ensureVirtualMouseLocked()) return false;
            return injectVirtualEventsLocked(
                    1, code, 1,
                    0, 0, 0,
                    1, code, 0,
                    0, 0, 0
            );
        }
    }

    @Override
    public boolean virtualMouseScroll(int vertical, int horizontal) {
        if (vertical == 0 && horizontal == 0) return true;

        synchronized (virtualMouseLock) {
            if (!ensureVirtualMouseLocked()) return false;

            List<Integer> values = new ArrayList<>();
            if (vertical != 0) {
                values.add(2);
                values.add(8); // REL_WHEEL
                values.add(vertical);
            }
            if (horizontal != 0) {
                values.add(2);
                values.add(6); // REL_HWHEEL
                values.add(horizontal);
            }
            values.add(0);
            values.add(0);
            values.add(0);

            int[] events = new int[values.size()];
            for (int i = 0; i < values.size(); i++) events[i] = values.get(i);
            return injectVirtualEventsLocked(events);
        }
    }

    @Override
    public boolean stopVirtualMouse() {
        synchronized (virtualMouseLock) {
            stopVirtualMouseLocked();
            return true;
        }
    }

    private int buttonCode(int button) {
        switch (button) {
            case 1: return 272; // BTN_LEFT
            case 2: return 273; // BTN_RIGHT
            case 3: return 274; // BTN_MIDDLE
            default: return 0;
        }
    }

    private boolean ensureVirtualMouseLocked() {
        if (virtualMouseProcess != null && virtualMouseProcess.isAlive() && virtualMouseWriter != null) {
            return true;
        }
        return startVirtualMouse();
    }

    private boolean injectVirtualEventsLocked(int... events) {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{\"id\":501,\"command\":\"inject\",\"events\":[");
            for (int i = 0; i < events.length; i++) {
                if (i > 0) json.append(',');
                json.append(events[i]);
            }
            json.append("]}");
            writeVirtualMouseLocked(json.toString());
            virtualMouseWriter.flush();
            return virtualMouseProcess != null && virtualMouseProcess.isAlive();
        } catch (Throwable error) {
            virtualMouseError = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ":" + error.getMessage());
            stopVirtualMouseLocked();
            return false;
        }
    }

    private void writeVirtualMouseLocked(String json) throws Exception {
        if (virtualMouseWriter == null) throw new IllegalStateException("uinput writer unavailable");
        virtualMouseWriter.write(json);
        virtualMouseWriter.newLine();
    }

    private void stopVirtualMouseLocked() {
        if (virtualMouseWriter != null) {
            try {
                virtualMouseWriter.close();
            } catch (Throwable ignored) {
            }
            virtualMouseWriter = null;
        }
        if (virtualMouseProcess != null) {
            try {
                virtualMouseProcess.destroy();
            } catch (Throwable ignored) {
            }
            virtualMouseProcess = null;
        }
        virtualMouseRemainderX = 0f;
        virtualMouseRemainderY = 0f;
    }

    @Override
    public boolean movePointer(int displayId, float x, float y, boolean dragging) {
        int action = dragging ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_HOVER_MOVE;
        int buttons = dragging ? MotionEvent.BUTTON_PRIMARY : 0;
        if (injectMouse(displayId, action, x, y, buttons)) {
            return true;
        }

        return shellOk(
                "/system/bin/input", "mouse", "-d", String.valueOf(displayId),
                "motionevent", "MOVE", String.valueOf(x), String.valueOf(y)
        );
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

    private ProcessBuilder androidProcessBuilder(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        String inheritedPath = builder.environment().get("PATH");
        String androidPath = "/system/bin:/system/xbin:/vendor/bin:/product/bin";
        builder.environment().put(
                "PATH",
                inheritedPath == null || inheritedPath.isEmpty()
                        ? androidPath
                        : androidPath + ":" + inheritedPath
        );
        return builder;
    }

    private boolean shellOk(String... command) {
        java.lang.Process process = null;
        try {
            process = androidProcessBuilder(command).start();

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
        synchronized (virtualMouseLock) {
            stopVirtualMouseLocked();
        }
        System.exit(0);
    }
}
