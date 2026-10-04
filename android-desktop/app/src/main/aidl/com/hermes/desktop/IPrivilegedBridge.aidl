package com.hermes.desktop;

interface IPrivilegedBridge {
    void destroy() = 16777114;
    String status() = 1;
    boolean movePointer(int displayId, float x, float y, boolean dragging) = 2;
    boolean pointerButton(int displayId, float x, float y, boolean down) = 3;
    boolean click(int displayId, float x, float y) = 4;
    boolean scroll(int displayId, float x, float y, float delta) = 5;
    boolean keyEvent(int displayId, int keyCode) = 6;
    boolean keyCombination(int displayId, in int[] keyCodes) = 7;
    boolean startVirtualMouse() = 8;
    boolean virtualMouseMove(float dx, float dy) = 9;
    boolean virtualMouseButton(int button, boolean down) = 10;
    boolean virtualMouseClick(int button) = 11;
    boolean virtualMouseScroll(int vertical, int horizontal) = 12;
    boolean stopVirtualMouse() = 13;
}
