package com.windletter.desktop.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveClipboardTest {

    @Test
    void shouldClearOwnedPlaintextAfterTheDelayOrOnClose() {
        FakeAccess access = new FakeAccess();
        FakeDelay delay = new FakeDelay();
        SensitiveClipboard clipboard = new SensitiveClipboard(access, delay);

        assertTrue(clipboard.copy("恢复后的明文 🌬️"));
        assertEquals("恢复后的明文 🌬️", access.text);
        delay.fire();
        assertNull(access.text);

        assertTrue(clipboard.copy("第二份明文"));
        clipboard.close();
        assertNull(access.text);
    }

    @Test
    void shouldNotClearContentTheUserCopiedLater() {
        FakeAccess access = new FakeAccess();
        FakeDelay delay = new FakeDelay();
        SensitiveClipboard clipboard = new SensitiveClipboard(access, delay);

        assertTrue(clipboard.copy("应用复制的明文"));
        access.text = "用户后来复制的内容";
        delay.fire();

        assertEquals("用户后来复制的内容", access.text);
        assertFalse(delay.scheduled);
    }

    private static final class FakeAccess
        implements SensitiveClipboard.Access {

        private String text;

        @Override
        public boolean setText(String value) {
            text = value;
            return true;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public void clear() {
            text = null;
        }
    }

    private static final class FakeDelay
        implements SensitiveClipboard.Delay {

        private Runnable action;
        private boolean scheduled;

        @Override
        public void schedule(Runnable value) {
            action = value;
            scheduled = true;
        }

        @Override
        public void cancel() {
            action = null;
            scheduled = false;
        }

        private void fire() {
            Runnable current = action;
            if (current != null) {
                current.run();
            }
        }
    }
}
