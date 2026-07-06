package br.com.finalcraft.everyconfig.binding.introspect;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * The Jackson module that teaches a mapper EveryConfig's binding behavior: the key-naming annotations
 * (via {@link EveryConfigAnnotationIntrospector}, inserted ahead of Jackson's own so native annotations
 * still resolve) and stable enum-by-name serialization (via {@link EnumNameSerializers}, which defers to a
 * {@code @JsonValue} enum's own form). Registered on every codec's mapper so the dynamic tree and typed
 * binding observe identical shapes.
 */
public final class EveryConfigModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public EveryConfigModule() {
        super("EveryConfigModule");
    }

    @Override
    public void setupModule(final SetupContext context) {
        super.setupModule(context);
        context.insertAnnotationIntrospector(new EveryConfigAnnotationIntrospector());
        context.addSerializers(new EnumNameSerializers());
    }
}
