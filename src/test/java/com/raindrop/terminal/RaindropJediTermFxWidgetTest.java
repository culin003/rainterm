package com.raindrop.terminal;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RaindropJediTermFxWidgetTest {

    @Test
    void testCtrlLetterMapsToControlByte() {
        assertEquals(0x03, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.C));
        assertEquals(0x04, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.D));
        assertEquals(0x15, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.U));
        assertEquals(0x1A, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.Z));
        assertEquals(0x01, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.A));
    }

    @Test
    void testCtrlPunctuationMapsToControlByte() {
        assertEquals(0, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.SPACE));
        assertEquals(0x1B, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.OPEN_BRACKET));
        assertEquals(0x1C, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.BACK_SLASH));
        assertEquals(0x1D, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.CLOSE_BRACKET));
        assertEquals(0x1E, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.CIRCUMFLEX));
        assertEquals(0x1F, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.UNDERSCORE));
    }

    @Test
    void testNonControlKeysReturnMinusOne() {
        assertEquals(-1, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.ENTER));
        assertEquals(-1, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.TAB));
        assertEquals(-1, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.DIGIT1));
        assertEquals(-1, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.F1));
        assertEquals(-1, RaindropJediTermFxWidget.ctrlCodeOf(KeyCode.ESCAPE));
    }
}
