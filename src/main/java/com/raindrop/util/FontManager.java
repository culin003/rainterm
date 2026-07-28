package com.raindrop.util;

import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.Optional;

/**
 * Loads fonts bundled in the app resources so rendering does not depend on what
 * is installed on the user's machine. Critical for the jpackage/jlink deb build:
 * without a bundled CJK font, the logical "Monospaced" family resolves to a
 * runtime font with no Chinese glyphs and CJK text renders as tofu (boxes).
 *
 * <p>Sarasa Mono SC is a true dual-width monospace with full CJK + Latin coverage,
 * so terminal columns stay aligned while Chinese renders correctly.
 */
public final class FontManager {

    /** Embedded family name of the bundled font (from its {@code name} table). */
    public static final String MONO_CJK_FAMILY = "Sarasa Mono SC";

    private static final String MONO_CJK_RESOURCE = "/fonts/SarasaMonoSC-Regular.ttf";

    private static volatile boolean loaded = false;

    private FontManager() {}

    /**
     * Register bundled fonts with the JavaFX font system. Idempotent; safe to call
     * once at startup before any Scene is built. Must run on a thread where the
     * JavaFX toolkit is initialized (the Application Thread).
     */
    public static synchronized void loadBundledFonts() {
        if (loaded) {
            return;
        }
        try (InputStream in = FontManager.class.getResourceAsStream(MONO_CJK_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled font not found on classpath: " + MONO_CJK_RESOURCE);
            }
            Font font = Font.loadFont(in, 12);
            if (font == null) {
                throw new IllegalStateException("JavaFX failed to load bundled font: " + MONO_CJK_RESOURCE);
            }
            loaded = true;
        } catch (Exception e) {
            throw new RuntimeException("Unable to load bundled CJK font", e);
        }
    }

    /**
     * The monospace CJK family name if the bundled font loaded, otherwise the JavaFX
     * logical "Monospaced" family as a last-resort fallback.
     */
    public static String monoCjkFamily() {
        return loaded ? MONO_CJK_FAMILY : "Monospaced";
    }

    public static Optional<Font> monoCjkFont(double size) {
        return loaded ? Optional.of(Font.font(MONO_CJK_FAMILY, size)) : Optional.empty();
    }
}
