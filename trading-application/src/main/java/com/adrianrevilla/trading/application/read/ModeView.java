package com.adrianrevilla.trading.application.read;

import java.util.List;

/** The trading mode of the system and the modes that can be selected today. */
public record ModeView(String mode, List<String> available) {
}
