package br.com.finalcraft.everyconfig.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FCMapperProfilesTest {

    @Test
    void storageSafeDisablesTimestampsAndUnknownFailure() {
        final ObjectMapper mapper = FCMapperProfiles.storageSafe(JsonMapper.builder().build());
        assertFalse(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test
    void compactUsesNonAbsentInclusion() {
        final ObjectMapper mapper = FCMapperProfiles.compact(JsonMapper.builder().build());
        assertSame(JsonInclude.Include.NON_ABSENT,
                mapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion());
    }

    @Test
    void isolateReturnsDistinctCopyOfUserMapper() {
        final ObjectMapper user = JsonMapper.builder().build();
        final ObjectMapper isolated = FCMapperProfiles.isolate(user, JsonMapper::new);
        assertNotSame(user, isolated);
    }

    @Test
    void isolateBuildsDefaultWhenUserIsNull() {
        final ObjectMapper built = JsonMapper.builder().build();
        final ObjectMapper isolated = FCMapperProfiles.isolate(null, () -> built);
        assertSame(built, isolated);
    }

    @Test
    void baseReadContractLeavesMapOrderingToTheTree() {
        final ObjectMapper mapper = FCMapperProfiles.baseReadContract(JsonMapper.builder().build());
        // Key order is owned by the tree/reconciler, so the mapper must not re-sort map entries.
        assertFalse(mapper.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
    }
}
