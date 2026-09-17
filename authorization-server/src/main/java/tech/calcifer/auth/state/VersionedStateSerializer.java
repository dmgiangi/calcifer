package tech.calcifer.auth.state;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;


final class VersionedStateSerializer<T> {

    private static final byte[] MAGIC = {'C', 'A', 'S'};
    private static final byte VERSION = 1;
    private final Class<T> type;
    private final SerializingConverter writer = new SerializingConverter();
    private final DeserializingConverter reader = new DeserializingConverter();

    VersionedStateSerializer(Class<T> type) {
        this.type = type;
    }

    byte[] serialize(T value) {
        byte[] payload = writer.convert(value);
        return ByteBuffer.allocate(MAGIC.length + 1 + payload.length).put(MAGIC).put(VERSION).put(payload).array();
    }

    T deserialize(byte[] encoded) {
        if (encoded == null || encoded.length <= MAGIC.length || !Arrays.equals(
            MAGIC,
            Arrays.copyOf(encoded, MAGIC.length)
        ) || encoded[MAGIC.length] != VERSION) {
            throw new StateUnavailableException("Unsupported authorization state encoding");
        }
        Object value = reader.convert(Arrays.copyOfRange(encoded, MAGIC.length + 1, encoded.length));
        if (!type.isInstance(value)) {
            throw new StateUnavailableException("Unexpected authorization state type");
        }
        return type.cast(value);
    }
}
