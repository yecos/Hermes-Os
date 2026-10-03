package com.hermes.desktop;

interface IPrivilegedBridge {
    void destroy() = 16777114;
    String status();
    boolean movePointer(int displayId, float x, float y, boolean dragging);
    boolean pointerButton(int displayId, float x, float y, boolean down);
    boolean click(int displayId, float x, float y);
    boolean scroll(int displayId, float x, float y, float delta);
    boolean keyEvent(int displayId, int keyCode);
    boolean keyCombination(int displayId, in int[] keyCodes);
}
