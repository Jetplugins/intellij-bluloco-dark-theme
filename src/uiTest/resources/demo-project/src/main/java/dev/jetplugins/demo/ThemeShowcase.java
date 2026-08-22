package dev.jetplugins.demo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A deliberately varied file used by the Marketplace screenshot suite. */
public final class ThemeShowcase {
    private static final String THEME = "Bluloco";

    public static void main(String[] args) {
        var themes = List.of(
            new ThemePreview("Dark", "#3691FF", false),
            new ThemePreview("Midnight", "#4B9CFF", true)
        );
        var metadata = Map.of(
            "release", "2026.1.0",
            "date", LocalDate.now().toString()
        );

        themes.stream()
            .filter(ThemePreview::ready)
            .map(preview -> THEME + " " + preview.name())
            .forEach(System.out::println);

        System.out.println(metadata);
    }

    private record ThemePreview(String name, String accent, boolean midnight) {
        boolean ready() {
            return !name.isBlank() && accent.startsWith("#");
        }
    }
}
