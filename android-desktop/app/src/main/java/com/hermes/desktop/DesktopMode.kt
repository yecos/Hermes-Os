package com.hermes.desktop

enum class DesktopMode {
    DESKTOP,
    TV;

    val label: String
        get() = if (this == DESKTOP) "Escritorio" else "TV"
}
