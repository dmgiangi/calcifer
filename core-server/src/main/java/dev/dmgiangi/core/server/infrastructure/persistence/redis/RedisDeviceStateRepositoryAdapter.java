package dev.dmgiangi.core.server.infrastructure.persistence.redis;

import dev.dmgiangi.core.server.domain.model.DesiredDeviceState;
import dev.dmgiangi.core.server.domain.model.DeviceCapability;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;


@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisDeviceStateRepositoryAdapter implements DeviceStateRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Prefissi per organizzare i dati in Redis
    private static final String KEY_PREFIX = "device:";
    private static final String INDEX_OUTPUTS = "index:active:outputs";

    @Override
    public void saveDesiredState(DesiredDeviceState state) {
        String key = generateKey(state.id());

        // 1. Salva lo stato (upsert)
        redisTemplate.opsForValue().set(key, state);

        // 2. Gestione Indice per il Reconciler
        if (state.type().capability == DeviceCapability.OUTPUT) {
            // Aggiungi al set degli output attivi
            redisTemplate.opsForSet().add(INDEX_OUTPUTS, key);
        } else {
            // Se per assurdo un device cambia tipo o vogliamo pulire
            redisTemplate.opsForSet().remove(INDEX_OUTPUTS, key);
        }

        log.debug("Saved state for device {} to Redis", key);
    }

    @Override
    public List<DesiredDeviceState> findAllActiveOutputDevices() {
        // 1. Recupera tutte le chiavi dal Set indice
        Set<Object> keys = redisTemplate.opsForSet().members(INDEX_OUTPUTS);

        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. MultiGet: Recupera tutti i valori in una sola chiamata di rete (Pipeline)
        // Convertiamo Set<Object> in List<String> per il metodo multiGet
        List<String> stringKeys = keys.stream().map(Object::toString).toList();
        List<Object> results = redisTemplate.opsForValue().multiGet(stringKeys);

        if (results == null) {
            return Collections.emptyList();
        }

        // 3. Filtra eventuali null (se una chiave è nell'indice ma il dato è scaduto/cancellato)
        // e casta al tipo di dominio
        return results.stream().filter(Objects::nonNull).map(obj -> (DesiredDeviceState) obj).toList();
    }

    // Helper per generare chiavi consistenti: "device:esp32-kitchen:light-1"
    private String generateKey(DeviceId id) {
        return KEY_PREFIX + id.toString(); // Usa il tuo toString() "controller:component"
    }
}