package com.composum.sling.tools;

import com.composum.sling.tools.impl.Server;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext.Values;
import com.composum.sling.tools.template.TemplateReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
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

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

public abstract class AbstractToolsPlugin implements ToolsPlugin, TemplateBuilder {

    private static final String DEFAULT_RESOURCE_ROOT = "/com/composum";

    protected abstract @NotNull Manager manager();

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

    @NotNull
    public String getHtmlCssClasses(@NotNull final String mainHtmlClass) {
        final Set<String> cssClasses = new TreeSet<>();
        cssClasses.add(mainHtmlClass);
        manager().addRunmodeCssClasses(cssClasses);
        collectHtmlCssClasses(cssClasses);
        return StringUtils.join(cssClasses, " ");
    }

    protected void collectHtmlCssClasses(@NotNull final Set<String> cssClasses) {
    }

    // Repository access

    protected @NotNull Map<String, Object> resourceProperties(@NotNull final Resource resource) {
        return resourceProperties(resource, new LinkedHashMap<>());
    }

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

    protected @Nullable InputStream openTemplate(@Nullable final String path) {
        return StringUtils.isNotBlank(path) ? getClass().getResourceAsStream(path) : null;
    }

    @Override
    public @NotNull Object valuesOf(@NotNull final Object value) {
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
        return value;
    }

    protected class ValuesIterable implements Iterable<Object> {

        protected final Iterable<?> delegate;

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

    protected class ValuesIterator implements Iterator<Object> {

        protected final Iterator<?> delegate;

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

    protected @NotNull String resourceRoot() {
        return DEFAULT_RESOURCE_ROOT;
    }

    protected @NotNull String resourcePath(@NotNull String path) {
        return !path.startsWith(resourceRoot() + "/") && !path.startsWith(manager().serverPath()) ? resourceRoot() + path : path;
    }

    public @NotNull Result<InputStream> resource(@NotNull final SlingHttpServletRequest request) {
        Result<InputStream> result = new Result<>(SC_NOT_FOUND);
        final String path = request.getRequestPathInfo().getSuffix();
        final InputStream data = openResource(path);
        if (data != null) {
            result = new Result<>(SC_OK, Common.pathMimeType(path), data);
        }
        return result;
    }

    protected @Nullable InputStream openResource(@Nullable String path) {
        if (path != null) {
            path = resourcePath(path);
            if (isAllowedResource(path)) {
                return getResourceClassLoader().getResourceAsStream(path);
            }
        }
        return null;
    }

    protected boolean isAllowedResource(@Nullable final String path) {
        return StringUtils.isNotBlank(path) &&
                path.matches("^" + resourceRoot() + "(/[^/]+)*/[^/]+\\.(js|css|png|jpe?g|webp|svg|gif|woff2?)$");
    }

    protected @NotNull ClassLoader getResourceClassLoader() {
        return getClass().getClassLoader();
    }
}
