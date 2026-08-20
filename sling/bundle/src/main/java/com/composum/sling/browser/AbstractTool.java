package com.composum.sling.browser;

import com.composum.sling.tools.Manager;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.EXT_HTML;

public abstract class AbstractTool implements Tool, TemplateBuilder {

    public abstract Browser browser();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @Nullable Reader openTemplate(@Nullable Template template) {
        return browser().openTemplate(template);
    }

    @Override
    public @NotNull XSSAPI xssapi() {
        return browser().xssapi();
    }

    @Override
    public @NotNull String pluginLink(@NotNull String path) {
        return browser().pluginLink(path);
    }

    @Override
    public @NotNull String toString(@NotNull Object value) {
        return browser().toString(value);
    }

    @Override
    public @NotNull Object valuesOf(@NotNull Object value) {
        return browser().valuesOf(value);
    }

    protected @NotNull String uri() {
        return uri(key(), EXT_HTML);
    }

    protected @NotNull String uri(@NotNull final String action) {
        return uri(key() + "." + action, EXT_HTML);
    }

    protected @NotNull String uri(@NotNull final String keyAction, @NotNull final String ext) {
        return browser().manager().serverPath() + ".browser.tool." + keyAction + "." + ext;
    }

    protected @NotNull Map<String, Object> resourceProperties(@NotNull Resource resource,
                                                              @NotNull Map<String, Object> properties) {
        return resourceProperties(resource, properties, (name) -> true);
    }

    protected @NotNull Map<String, Object> resourceProperties(@NotNull Resource resource,
                                                              @NotNull Map<String, Object> properties,
                                                              @NotNull Function<String, Boolean> nameFilter) {
        return resourceProperties(resource, properties, nameFilter, (value) -> !(value instanceof InputStream));
    }

    protected @NotNull Map<String, Object> resourceProperties(@NotNull Resource resource,
                                                              @NotNull Map<String, Object> properties,
                                                              @NotNull Function<String, Boolean> nameFilter,
                                                              @NotNull Function<Object, Boolean> valueFilter) {
        final ValueMap values = resource.getValueMap();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            final String name = entry.getKey();
            final Object value;
            if (browser().manager().isAllowedProperty(name) && nameFilter.apply(name)
                    && valueFilter.apply(value = entry.getValue())) {
                properties.put(name, value);
            }
        }
        return properties;
    }

    @Override
    public @NotNull Values values(@NotNull Values values) {
        final String path = Optional.ofNullable(Manager.CURRENT_REQUEST.get())
                .map(r -> r.getRequestPathInfo().getSuffix())
                .orElse("");
        return values
                .with("id", key())
                .with("uri", (Supplier<String>) () -> uri() + path)
                .with("label", label())
                .with("icon", icon());
    }

    @Override
    public @NotNull Collection<String> styles() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull Collection<String> scripts() {
        return Collections.emptyList();
    }
}
