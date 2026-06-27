package br.com.finalcraft.everyconfig.binding.introspect;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * The Jackson module that teaches a mapper FinalConfig's binding behavior: the key-naming annotations
 * (via {@link FinalConfigAnnotationIntrospector}, inserted ahead of Jackson's own so native annotations
 * still resolve) and stable enum-by-name serialization. Registered on every codec's mapper so the
 * dynamic tree and typed binding observe identical shapes.
 */
public final class FinalConfigModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public FinalConfigModule() {
        super("FinalConfigModule");
        addSerializer(Enum.class, (JsonSerializer) new EnumNameSerializer());
    }

    @Override
    public void setupModule(final SetupContext context) {
        super.setupModule(context);
        context.insertAnnotationIntrospector(new FinalConfigAnnotationIntrospector());
    }
}
