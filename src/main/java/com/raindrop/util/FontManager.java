package com.raindrop.util;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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

    private static final double MEASURE_SIZE = 14;

    private static volatile boolean loaded = false;
    private static volatile List<String> monospaceCache = null;

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

    /**
     * Resolve a configured terminal font family to one JavaFX can actually render.
     * Falls back to the bundled CJK family when the stored name is blank or no
     * longer installed (e.g. the config was copied from another machine).
     */
    public static String resolveTerminalFamily(String configured) {
        if (configured == null || configured.isBlank()) {
            return monoCjkFamily();
        }
        return Font.getFamilies().contains(configured) ? configured : monoCjkFamily();
    }

    /**
     * Monospace families installed on this machine, bundled family first. Filtered by
     * measuring a narrow vs. wide glyph: proportional fonts render them at different
     * widths, so unequal advance means "not monospace". Families JavaFX can't resolve
     * fall back to a proportional default and are filtered out the same way.
     *
     * <p>Must be called on the JavaFX Application Thread ({@link Text} instantiation).
     * The result is cached because measuring every installed family costs ~100ms.
     */
    public static synchronized List<String> monospaceFamilies() {
        if (monospaceCache != null) {
            return monospaceCache;
        }
        List<String> mono = new ArrayList<>();
        if (loaded) {
            mono.add(MONO_CJK_FAMILY);
        }
        for (String family : Font.getFamilies()) {
            if (!family.equals(MONO_CJK_FAMILY) && isMonospace(family)) {
                mono.add(family);
            }
        }
        monospaceCache = List.copyOf(mono);
        return monospaceCache;
    }

    private static boolean isMonospace(String family) {
        Font font = Font.font(family, MEASURE_SIZE);
        if (font == null || !font.getFamily().equals(family)) {
            return false;
        }
        return Math.abs(advance(font, "i") - advance(font, "W")) < 0.01;
    }

    private static double advance(Font font, String s) {
        Text text = new Text(s);
        text.setFont(font);
        return text.getLayoutBounds().getWidth();
    }
}
