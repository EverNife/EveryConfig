package br.com.finalcraft.everyconfig.core.coerce;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The centralized type classification shared by the dynamic API ({@code Config}) and the binder. */
class TypeFamilyTest {

    enum Color { RED }

    static class Pojo {
        public int x;
    }

    @Test
    void nativeScalarCoversNumbersStringsBooleansCharsEnums() {
        assertTrue(TypeFamily.isNativeScalar(1));
        assertTrue(TypeFamily.isNativeScalar(1L));
        assertTrue(TypeFamily.isNativeScalar(1.5));
        assertTrue(TypeFamily.isNativeScalar("s"));
        assertTrue(TypeFamily.isNativeScalar(new StringBuilder("s"))); // CharSequence
        assertTrue(TypeFamily.isNativeScalar(true));
        assertTrue(TypeFamily.isNativeScalar('c'));
        assertTrue(TypeFamily.isNativeScalar(Color.RED));

        assertFalse(TypeFamily.isNativeScalar(new Pojo()));
        assertFalse(TypeFamily.isNativeScalar(new ArrayList<String>()));
        assertFalse(TypeFamily.isNativeScalar(UUID.randomUUID())); // a UUID is NOT a native scalar here
        assertFalse(TypeFamily.isNativeScalar(null));
    }

    @Test
    void preformedNodeOrMapIsNodeOrMapOnly() {
        assertTrue(TypeFamily.isPreformedNodeOrMap(JsonNodeFactory.instance.objectNode()));
        assertTrue(TypeFamily.isPreformedNodeOrMap(new HashMap<String, Object>()));

        assertFalse(TypeFamily.isPreformedNodeOrMap(new Pojo()));
        assertFalse(TypeFamily.isPreformedNodeOrMap("s"));
        assertFalse(TypeFamily.isPreformedNodeOrMap(new ArrayList<String>()));
    }

    @Test
    void userPojoTypeExcludesJdkContainersPrimitivesEnumsArrays() {
        assertTrue(TypeFamily.isUserPojoType(Pojo.class));

        assertFalse(TypeFamily.isUserPojoType(null));
        assertFalse(TypeFamily.isUserPojoType(int.class));
        assertFalse(TypeFamily.isUserPojoType(String[].class));
        assertFalse(TypeFamily.isUserPojoType(Color.class));
        assertFalse(TypeFamily.isUserPojoType(String.class)); // java.*
        assertFalse(TypeFamily.isUserPojoType(UUID.class));   // java.*
        assertFalse(TypeFamily.isUserPojoType(Map.class));
        assertFalse(TypeFamily.isUserPojoType(List.class));
    }
}
