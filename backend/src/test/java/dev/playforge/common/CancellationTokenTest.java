package dev.playforge.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationTokenTest {

    @Test
    void onCancelRunsOnce() {
        CancellationToken t = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        t.onCancel(calls::incrementAndGet);
        t.cancel();
        t.cancel();
        assertThat(calls).hasValue(1);
        assertThat(t.isCancelled()).isTrue();
    }

    @Test
    void onCancelAfterCancelledRunsImmediately() {
        CancellationToken t = new CancellationToken();
        t.cancel();
        AtomicInteger calls = new AtomicInteger();
        t.onCancel(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }

    @Test
    void shutdownCancelsAndIsDistinguishable() {
        CancellationToken t = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        t.onCancel(calls::incrementAndGet);
        t.shutdown();
        assertThat(t.isCancelled()).isTrue();
        assertThat(t.isShutdown()).isTrue();
        assertThat(calls).hasValue(1);

        CancellationToken u = new CancellationToken();
        u.cancel();
        assertThat(u.isShutdown()).isFalse();
    }

    @Test
    void throwIfCancelled() {
        CancellationToken t = new CancellationToken();
        t.throwIfCancelled();
        t.cancel();
        assertThatThrownBy(t::throwIfCancelled).isInstanceOf(CancelledException.class);
    }
}
