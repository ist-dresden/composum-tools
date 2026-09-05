package com.composum.sling.tools;

import com.composum.sling.tools.impl.Server;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import com.composum.sling.tools.template.TemplateReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.type.PrimitiveType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * Base class for a {@link ToolsPlugin} implementation: provides the {@link TemplateBuilder}
 * plumbing (template resolution, output value conversion), classpath-resource serving, and
 * generic, filtered resource-property collection shared by every dashboard tools plugin.
 */
public abstract class AbstractToolsPlugin implements ToolsPlugin, TemplateBuilder {

    private static final String DEFAULT_RESOURCE_ROOT = "/com/composum";

    /** The manager this plugin is registered with. */
    public Manager manager;

    /** TODO: for compatibility (probably overridden by projects tools); periodically check for removal */
    protected @NotNull Manager manager() {
        return manager;
    }

    /**
     * Default constructor.
     */
    protected AbstractToolsPlugin() {
    }

    @Override
    public @Nullable String widgetLink(@NotNull final SlingHttpServletRequest request,
                                       @NotNull final SlingHttpServletResponse response,
                                       @NotNull final String selectors) {
        return manager().serverPath() + "." + key() + "." + selectors + ".html";
    }

    @Override
    public @Nullable String widgetViewLink(@NotNull final SlingHttpServletRequest request,
                                           @NotNull final SlingHttpServletResponse response,
                                           @NotNull final String widgetKey) {
        return null;
    }

    @Override
    public @NotNull String adjustLink(@NotNull final String link) {
        return link.replaceFirst("^.+(" + Pattern.quote(manager().serverPath()) + ")", "$1");
    }

    /**
     * This plugin's own base link (no path).
     *
     * @return this plugin's own base link (no path)
     */
    public @NotNull String pluginLink() {
        return pluginLink(null);
    }

    @Override
    public @NotNull String pluginLink(@Nullable String path) {
        return (StringUtils.isNotBlank(path) && path.matches("^(/com/composum)?/(lib|sling|aem)(/.*)?$"))
                ? manager().serverPath() + "." + key() + ".resource.html" + path
                : manager().serverPath() + "." + key() + ".html" + path;
    }

    /**
     * The common template context values shared by every tools page.
     *
     * @return the common template context values shared by every tools page (navigation, current
     * user, system client libraries)
     */
    protected @NotNull Values toolsValues() {
        return new Values()
                .with("tools", new Values()
                        .with("uri", (Supplier<String>) () -> manager().serverPath())
                        .with("home.link", manager().serverPath() + ".browser.html")
                        .with("pages", valuesOf(manager().getToolsPages()))
                        .with("navbar", new Values()
                                .with("left", "/sling/tools/navbar/navbarLeft.html")
                                .with("right", "/sling/tools/navbar/navbarRight.html")
                        )
                )
                .with("user", new Values()
                        .with("name", (Supplier<String>) () -> Optional.ofNullable(Manager.CURRENT_REQUEST.get())
                                .map(req -> req.getResourceResolver().getUserID())
                                .orElse("anonymous"))
                        .with("login", (Supplier<String>) () -> manager().loginUri())
                )
                .with("system.clientlibs", (Supplier<?>) () -> valuesOf(manager().systemClientlibs()));
    }

    /**
     * The space-separated CSS classes to apply to the HTML root element.
     *
     * @param mainHtmlClass the main CSS class of the rendered HTML root element
     * @return the space-separated CSS classes to apply to the HTML root element (the main class,
     * the current runmode classes, and any classes contributed by {@link #collectHtmlCssClasses})
     */
    @NotNull
    public String getHtmlCssClasses(@NotNull final String mainHtmlClass) {
        final Set<String> cssClasses = new TreeSet<>();
        cssClasses.add(mainHtmlClass);
        manager().addRunmodeCssClasses(cssClasses);
        collectHtmlCssClasses(cssClasses);
        return StringUtils.join(cssClasses, " ");
    }

    /**
     * Hook for subclasses to contribute additional CSS classes to {@link #getHtmlCssClasses}; a
     * no-op by default.
     *
     * @param cssClasses the mutable set of CSS classes to add to
     */
    protected void collectHtmlCssClasses(@NotNull final Set<String> cssClasses) {
    }

    // Repository access

    /**
     * The resource's allowed properties.
     *
     * @param resource the resource whose allowed properties to collect
     * @return the resource's allowed properties (per {@link Manager#isAllowedProperty})
     */
    protected @NotNull Map<String, Object> resourceProperties(@NotNull final Resource resource) {
        return resourceProperties(resource, new LinkedHashMap<>());
    }

    /**
     * Collects the resource's allowed properties into the given map.
     *
     * @param resource   the resource whose allowed properties to collect
     * @param properties the map to add the collected properties to
     * @return the given 'properties' map
     */
    protected @NotNull Map<String, Object> resourceProperties(@NotNull final Resource resource,
                                                              @NotNull final Map<String, Object> properties) {
        final ValueMap values = resource.getValueMap();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            final String name = entry.getKey();
            if (manager().isAllowedProperty(name)) {
                properties.put(name, entry.getValue());
            }
        }
        return properties;
    }

    // Templating

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String path) {
        return new Template(path, context, this);
    }

    @Override
    public @NotNull XSSAPI xssapi() {
        return manager().xssapi();
    }

    @Override
    public @NotNull String toString(@NotNull Object value) {
        if (value instanceof String) {
            return (String) value;
        } else if (value instanceof Date) {
            return new SimpleDateFormat(Common.HTML_DATE_FORMAT).format((Date) value);
        } else if (value instanceof Calendar) {
            return new SimpleDateFormat(Common.HTML_DATE_FORMAT).format(((Calendar) value).getTime());
        } else if (value instanceof Double) {
            return new DecimalFormat(Common.HTML_DECIMAL_FORMAT).format(value);
        } else if (!value.getClass().isPrimitive()) {
            try {
                return Server.MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException ignore) {
            }
        }
        return value.toString();
    }

    /**
     * A reader that renders the given template's placeholders while being read.
     *
     * @param template the template to render
     * @return a reader that renders the given template's placeholders while being read, or 'null'
     * if the template is 'null' or its content cannot be opened
     */
    @Nullable
    public TemplateReader templateReader(@Nullable Template template) {
        return Optional.ofNullable(template)
                .map(tmpl -> openTemplate(template))
                .map(reader -> new TemplateReader(template, reader))
                .orElse(null);
    }

    @Override
    public @Nullable Reader openTemplate(@Nullable Template template) {
        return Optional.ofNullable(template)
                .map(tmpl -> openTemplate(resourcePath(tmpl.getPath())))
                .map(stream -> new InputStreamReader(stream, StandardCharsets.UTF_8))
                .orElse(null);
    }

    /**
     * The raw, unrendered content of the resource at the given path.
     *
     * @param path the classpath-relative resource path to open
     * @return the raw, unrendered content of the resource at the given path, or 'null' if the
     * path is blank or the resource cannot be opened
     */
    protected @Nullable InputStream openTemplate(@Nullable final String path) {
        return StringUtils.isNotBlank(path) ? getClass().getResourceAsStream(path) : null;
    }

    @Override
    public @NotNull Object valuesOf(@Nullable final Object value) {
        if (value instanceof PrimitiveType || value instanceof String || value instanceof Map ||
                value instanceof ValuesIterable || value instanceof ValuesIterator) {
            return value;
        } else if (value instanceof Values.Provider) {
            return ((Values.Provider) value).values(new Values());
        } else if (value instanceof Map.Entry) {
            final Map.Entry<?, ?> entry = (Map.Entry<?, ?>) value;
            return Map.of("key", entry.getKey(), "value", valuesOf(entry.getValue()));
        } else if (value instanceof Object[]) {
            return new ValuesIterable(Arrays.asList((Object[]) value));
        } else if (value instanceof Iterable) {
            return new ValuesIterable((Iterable<?>) value);
        } else if (value instanceof Iterator) {
            return new ValuesIterator((Iterator<?>) value);
        } else if (value instanceof Resource) {
            final Resource resource = (Resource) value;
            final Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name", resource.getName());
            properties.put("path", resource.getPath());
            return resourceProperties((Resource) value, properties);
        } else {
            try {
                return Server.MAPPER.readValue(Server.MAPPER.writeValueAsString(value), Map.class);
            } catch (JsonProcessingException ignore) {
            }
        }
        return value != null ? value : "";
    }

    /**
     * An {@link Iterable} that lazily applies {@link #valuesOf(Object)} to every element of a
     * delegate iterable, so a template's 'each' placeholder can navigate arbitrary element types.
     */
    protected class ValuesIterable implements Iterable<Object> {

        /** the wrapped iterable whose elements are converted on the fly */
        protected final Iterable<?> delegate;

        /**
         * Wraps the given iterable.
         *
         * @param iterable the iterable to wrap
         */
        public ValuesIterable(Iterable<?> iterable) {
            delegate = iterable;
        }

        @Override
        public @NotNull Iterator<Object> iterator() {
            return new ValuesIterator(delegate.iterator());
        }

        @Override
        public void forEach(Consumer<? super Object> action) {
            for (Object value : delegate) {
                action.accept(valuesOf(value));
            }
        }
    }

    /**
     * An {@link Iterator} that lazily applies {@link #valuesOf(Object)} to every element of a
     * delegate iterator, so a template's 'each' placeholder can navigate arbitrary element types.
     */
    protected class ValuesIterator implements Iterator<Object> {

        /** the wrapped iterator whose elements are converted on the fly */
        protected final Iterator<?> delegate;

        /**
         * Wraps the given iterator.
         *
         * @param iterator the iterator to wrap
         */
        public ValuesIterator(Iterator<?> iterator) {
            delegate = iterator;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public Object next() {
            return hasNext() ? valuesOf(delegate.next()) : null;
        }
    }

    // File resources

    /**
     * The classpath root under which this plugin's classpath (client) resources are located.
     *
     * @return the classpath root under which this plugin's classpath (client) resources are located
     */
    protected @NotNull String resourceRoot() {
        return DEFAULT_RESOURCE_ROOT;
    }

    /**
     * Prefixes the given path with {@link #resourceRoot()} unless it is already rooted.
     *
     * @param path a resource path, either already rooted (at {@link #resourceRoot()} or the
     *             server path) or relative
     * @return the given path, prefixed with {@link #resourceRoot()} unless it is already rooted
     */
    protected @NotNull String resourcePath(@NotNull String path) {
        return !path.startsWith(resourceRoot() + "/") && !path.startsWith(manager().serverPath()) ? resourceRoot() + path : path;
    }

    /**
     * Serves a single classpath (client) resource (script, stylesheet, image, font) requested via
     * its request suffix.
     *
     * @param request the current request; its suffix identifies the resource to serve
     * @return the resource's content and MIME type, or a 'Not Found' result if it cannot be served
     */
    public @NotNull Result<InputStream> resource(@NotNull final SlingHttpServletRequest request) {
        Result<InputStream> result = new Result<>(SC_NOT_FOUND);
        final String path = request.getRequestPathInfo().getSuffix();
        final InputStream data = openResource(path);
        if (data != null) {
            result = new Result<>(SC_OK, Common.pathMimeType(path), data);
        }
        return result;
    }

    /**
     * The classpath resource's content at the given path.
     *
     * @param path the resource path to open
     * @return the resource's content, or 'null' if the path is 'null' or not allowed (see
     * {@link #isAllowedResource})
     */
    protected @Nullable InputStream openResource(@Nullable String path) {
        if (path != null) {
            path = resourcePath(path);
            if (isAllowedResource(path)) {
                return getResourceClassLoader().getResourceAsStream(path);
            }
        }
        return null;
    }

    /**
     * Whether the given path is a client resource that may be served via {@link #resource}.
     *
     * @param path the resource path to check
     * @return whether the given path is a client resource (script, stylesheet, image, font) under
     * {@link #resourceRoot()} that may be served via {@link #resource}
     */
    protected boolean isAllowedResource(@Nullable final String path) {
        return StringUtils.isNotBlank(path) &&
                path.matches("^" + resourceRoot() + "(/[^/]+)*/[^/]+\\.(js|css|png|jpe?g|webp|svg|gif|woff2?)$");
    }

    /**
     * The class loader used to resolve classpath (client) resources.
     *
     * @return the class loader used to resolve classpath (client) resources
     */
    protected @NotNull ClassLoader getResourceClassLoader() {
        return getClass().getClassLoader();
    }
}
