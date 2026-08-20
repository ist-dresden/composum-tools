package com.composum.sling.tools.template;

import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;

/**
 * The rendering environment a {@link Template} / {@link TemplateReader} is bound to: it resolves
 * and opens template resources, provides the {@link XSSAPI} used for output encoding, builds
 * plugin resource links, and converts context values into their template-usable String and
 * navigable (map/iterable) representations. Typically implemented by the plugin that owns the
 * templates (see {@code AbstractToolsPlugin} for the default implementation).
 */
public interface TemplateBuilder {

    /**
     * creates a {@link Template} bound to the given (parent) context - used to register named
     * templates with their own fixed set of context values, see e.g. the 'templates' map of a
     * plugin implementation
     */
    interface Factory {
        Template create(TemplateContext current);
    }

    /**
     * resolves a template for the given key: either a registered template name or an absolute
     * resource path (as used by the 'include', 'each' and 'if' placeholders of a
     * {@link TemplateReader}, see {@link TemplateReader#templateReader}); returns 'null' if the
     * key cannot be resolved to a template
     */
    @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key);

    /**
     * opens the given template's resource content for reading; returns 'null' if the template is
     * 'null' or its content cannot be opened
     */
    @Nullable Reader openTemplate(@Nullable Template template);

    /**
     * provides the Sling API used by a {@link TemplateReader} for output encoding (XSS filtering)
     */
    @NotNull XSSAPI xssapi();

    /**
     * builds a templating 'src=...' link to the given resource path in the builder's context
     * (plugin) - used by the 'src' placeholder type of a {@link TemplateReader}
     */
    @NotNull String pluginLink(@NotNull String path);

    /**
     * renders a resolved (primitive) context value as the String to embed into a template, or - for
     * a format expression - as the pattern text applied via {@link java.util.Formatter}
     */
    @NotNull String toString(@NotNull Object value);

    /**
     * transforms an arbitrary value into a variant a template can navigate via its placeholder key
     * syntax, e.g. wrapping a {@code Map.Entry} as a {key, value} map, or adapting a resource,
     * array, {@link Iterable} or {@link java.util.Iterator} - used for every element produced by
     * the 'each' placeholder of a {@link TemplateReader}
     */
    @NotNull Object valuesOf(@NotNull Object value);
}
