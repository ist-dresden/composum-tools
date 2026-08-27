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
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.EXT_HTML;

/**
 * Base class for a {@link Tool} implementation: provides the {@link TemplateBuilder} plumbing
 * shared by every tool (delegating to the owning {@link Browser}), URI building and generic,
 * filtered resource-property collection.
 */
public abstract class AbstractTool implements Tool, TemplateBuilder {

    /**
     * Default constructor.
     */
    protected AbstractTool() {
    }

    /**
     * The browser plugin this tool is registered with.
     *
     * @return the browser plugin this tool is registered with
     */
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
    public @NotNull String adjustLink(@NotNull final String link) {
        return link.replaceFirst("^.+(" + Pattern.quote(browser().manager().serverPath()) + ")", "$1");
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

    /**
     * This tool's own base URI (no action, '.html' extension).
     *
     * @return this tool's own base URI (no action, '.html' extension)
     */
    protected @NotNull String uri() {
        return uri(key(), EXT_HTML);
    }

    /**
     * This tool's URI for the given action ('.html' extension).
     *
     * @param action the action selector to append to this tool's key
     * @return this tool's URI for the given action ('.html' extension)
     */
    protected @NotNull String uri(@NotNull final String action) {
        return uri(key() + "." + action, EXT_HTML);
    }

    /**
     * Builds a tool URI from the given selector(s) and extension.
     *
     * @param keyAction the selector(s) identifying this tool and the requested action
     * @param ext       the URI extension
     * @return the resulting tool URI
     */
    protected @NotNull String uri(@NotNull final String keyAction, @NotNull final String ext) {
        return browser().manager().serverPath() + ".browser.tool." + keyAction + "." + ext;
    }

    /**
     * Collects the given resource's allowed properties (per {@link Manager#isAllowedProperty})
     * into the given map, excluding binary ({@link InputStream}) values.
     *
     * @param resource   the resource whose properties to collect
     * @param properties the map to add the collected properties to
     * @return the given 'properties' map
     */
    protected @NotNull Map<String, Object> resourceProperties(@NotNull Resource resource,
                                                              @NotNull Map<String, Object> properties) {
        return resourceProperties(resource, properties, (name) -> true);
    }

    /**
     * Collects the given resource's allowed properties (per {@link Manager#isAllowedProperty} and
     * the given name filter) into the given map, excluding binary ({@link InputStream}) values.
     *
     * @param resource   the resource whose properties to collect
     * @param properties the map to add the collected properties to
     * @param nameFilter an additional filter a property name must satisfy to be collected
     * @return the given 'properties' map
     */
    protected @NotNull Map<String, Object> resourceProperties(@NotNull Resource resource,
                                                              @NotNull Map<String, Object> properties,
                                                              @NotNull Function<String, Boolean> nameFilter) {
        return resourceProperties(resource, properties, nameFilter, (value) -> !(value instanceof InputStream));
    }

    /**
     * Collects the given resource's allowed properties (per {@link Manager#isAllowedProperty} and
     * the given name/value filters) into the given map.
     *
     * @param resource    the resource whose properties to collect
     * @param properties  the map to add the collected properties to
     * @param nameFilter  an additional filter a property name must satisfy to be collected
     * @param valueFilter an additional filter a property value must satisfy to be collected
     * @return the given 'properties' map
     */
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
