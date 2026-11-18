package tech.calcifer.auth.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;


class NettyNativeBufferTest {

    @Test
    void releasesNettyDirectBuffers() {
        for (int value = 0; value < 32; value++) {
            ByteBuf buffer = Unpooled.directBuffer(8192);
            try {
                assertThat(buffer.isDirect()).isTrue();
                buffer.writeInt(value);
                assertThat(buffer.readInt()).isEqualTo(value);
            } finally {
                assertThat(buffer.release()).isTrue();
            }
            assertThat(buffer.refCnt()).isZero();
        }
    }

    @Test
    void closesSharedArenaRequiredByNettyCleaner() {
        // Explicitly exercise the GraalVM path even if Netty falls back to another cleaner.
        var arena = Arena.ofShared();
        try (arena) {
            var segment = arena.allocate(ValueLayout.JAVA_INT);
            segment.set(ValueLayout.JAVA_INT, 0, 42);
            assertThat(segment.get(ValueLayout.JAVA_INT, 0)).isEqualTo(42);
        }
        assertThat(arena.scope().isAlive()).isFalse();
    }
}