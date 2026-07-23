package com.windletter.desktop.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultSessionControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-24T01:00:00Z");

    @TempDir
    Path directory;

    @Test
    void shouldResetOnActivityThenClearSessionKeyWhenLatestTimerExpires()
        throws Exception {
        VaultService service = service(directory.resolve("vault.wlv"));
        char[] password = "correct horse battery staple".toCharArray();
        FakeScheduler scheduler = new FakeScheduler();
        VaultSessionController controller = new VaultSessionController(scheduler);
        VaultSession session = null;
        try {
            session = service.create(password, 15);
            VaultSessionKey sessionKey = session.sessionKey();
            java.lang.reflect.Field field = VaultSessionKey.class
                .getDeclaredField("key");
            field.setAccessible(true);
            byte[] ownedKek = (byte[]) field.get(sessionKey);

            controller.unlock(session);
            assertEquals(Duration.ofMinutes(15), scheduler.tasks.get(0).delay);
            controller.activity();
            assertTrue(scheduler.tasks.get(0).cancelled);

            scheduler.tasks.get(0).fire();
            assertTrue(controller.isUnlocked());

            scheduler.tasks.get(1).fire();
            assertTrue(!controller.isUnlocked());
            assertTrue(isAllZero(ownedKek));
            assertThrows(IllegalStateException.class, controller::session);
        } finally {
            controller.close();
            if (session != null) {
                session.close();
            }
            clear(password);
        }
    }

    @Test
    void shouldLockPreviousSessionWhenAReplacementIsInstalled() throws Exception {
        VaultService firstService = service(directory.resolve("first.wlv"));
        VaultService secondService = service(directory.resolve("second.wlv"));
        char[] password = "correct horse battery staple".toCharArray();
        FakeScheduler scheduler = new FakeScheduler();
        VaultSessionController controller = new VaultSessionController(scheduler);
        VaultSession first = null;
        VaultSession second = null;
        try {
            first = firstService.create(password, 15);
            second = secondService.create(password, 30);
            controller.unlock(first);
            controller.unlock(second);

            assertThrows(IllegalStateException.class, first::payload);
            assertEquals(second, controller.session());
            assertEquals(Duration.ofMinutes(30), scheduler.tasks.get(1).delay);

            controller.lock();
            assertThrows(IllegalStateException.class, second::payload);
        } finally {
            controller.close();
            if (second != null) {
                second.close();
            }
            if (first != null) {
                first.close();
            }
            clear(password);
        }
    }

    private static VaultService service(Path vaultPath) {
        return new VaultService(
            vaultPath,
            () -> new VaultKdfCalibration(
                VaultKdfParameters.minimumSupported(),
                1,
                VaultKdfCalibrator.DEFAULT_TARGET_MILLIS
            ),
            new SecureRandom(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static boolean isAllZero(byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static final class FakeScheduler implements VaultAutoLockScheduler {

        private final List<Task> tasks = new ArrayList<>();

        @Override
        public Cancellable schedule(Runnable action, Duration delay) {
            Task task = new Task(action, delay);
            tasks.add(task);
            return task;
        }

        @Override
        public void close() {
        }
    }

    private static final class Task implements VaultAutoLockScheduler.Cancellable {

        private final Runnable action;
        private final Duration delay;
        private boolean cancelled;

        private Task(Runnable action, Duration delay) {
            this.action = action;
            this.delay = delay;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private void fire() {
            action.run();
        }
    }
}
