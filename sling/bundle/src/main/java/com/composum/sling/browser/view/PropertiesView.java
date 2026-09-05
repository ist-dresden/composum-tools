package com.composum.sling.browser.view;

import com.composum.sling.browser.AbstractView;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.InputStream;
import java.io.Reader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.HTTP_CONTENT_DISPOSITION;
import static com.composum.sling.tools.Common.HTTP_LAST_MODIFIED;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_DATA;
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

@Component(service = {View.class, PropertiesView.class}, immediate = true)
@Designate(ocd = PropertiesView.Config.class)
public class PropertiesView extends AbstractView {

    public static final String KEY = "properties";

    @ObjectClassDefinition(name = "Composum Browser Properties View")
    public @interface Config {

        @AttributeDefinition()
        String key() default PropertiesView.KEY;

        @AttributeDefinition()
        String label() default "Properties";

        @AttributeDefinition()
        int rank() default 8000;
    }

    @Reference
    protected Browser browser;

    protected BundleContext bundleContext;
    protected Config config;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        browser.views().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        browser.views().detach(this);
    }

    @Override
    public Browser browser() {
        return browser;
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse("Properties");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(8000);
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = browser.resource(request);
                break;
            case "form":
                result = new Result<>(SC_OK);
                break;
            case "load":
                result = openContent(request);
                break;
            default: {
                final org.apache.sling.api.resource.Resource resource = browser.manager.requestResource(request);
                if (resource != null) {
                    final Resource values = new Resource(resource, browser.manager);
                    final RequestPathInfo pathInfo = request.getRequestPathInfo();
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                            new TemplateContext.Values()
                                    .with("resource", values)
                                    .with("properties", values.get("properties"))
                    ), "view"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                }
            }
            break;
        }
        return result;
    }

    public final Map<String, Factory> templates = Map.of(
            "view", current ->
                    new Template("/sling/browser/view/properties/properties.html",
                            new TemplateContext(current, new TemplateContext.Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    protected Result<InputStream> openContent(@NotNull final SlingHttpServletRequest request) {
        Result<InputStream> result = new Result<>(SC_NOT_FOUND);
        org.apache.sling.api.resource.Resource resource = browser.manager.requestResource(request);
        if (resource != null) {
            ValueMap values = resource.getValueMap();
            InputStream stream = values.get(JCR_DATA, InputStream.class);
            if (stream != null) {
                result = new Result<>(stream);
                String mimeType = values.get(JCR_MIME_TYPE, String.class);
                if (StringUtils.isNotBlank(mimeType)) {
                    result.setContentType(mimeType);
                }
                String disposition = "attachment; filename=" + filename(resource);
                result.setHeader(HTTP_CONTENT_DISPOSITION, disposition);
                Calendar lastModified = values.get("jcr:lastModified", Calendar.class);
                if (lastModified != null) {
                    result.setHeader(HTTP_LAST_MODIFIED, lastModified.getTime());
                }
            }
        }
        return result;
    }

    protected @NotNull String filename(@NotNull final org.apache.sling.api.resource.Resource resource) {
        final String name = resource.getName();
        return JCR_CONTENT.equals(name) ? Optional.ofNullable(resource.getParent())
                .map(org.apache.sling.api.resource.Resource::getName).orElse(name) : name;
    }

    public enum PropertyType {Unknown, String, Long, Double, Boolean, Date, Binary}

    public class Resource extends LinkedHashMap<String, Object> {

        public class Properties extends TreeMap<String, Property> {

            protected final ValueMap values;

            public Properties(@Nullable final ValueMap values, @NotNull final Manager manager) {
                this.values = values;
                if (values != null) {
                    for (String key : values.keySet()) {
                        if (manager.isAllowedProperty(key)) {
                            put(key, new Property(key, (Supplier<?>) () -> this.values.get(key), Resource.this));
                        }
                    }
                }
            }
        }

        protected transient org.apache.sling.api.resource.Resource resource;

        public Resource(@NotNull final org.apache.sling.api.resource.Resource resource,
                        @NotNull final Manager manager) {
            this.resource = resource;
            put("name", (Supplier<String>) resource::getName);
            put("path", (Supplier<String>) resource::getPath);
            put("properties", new Properties(resource.getValueMap(), manager).values());
            put("hasChildren", (Supplier<Boolean>) resource::hasChildren);
        }

        protected @NotNull String binaryDownloadLink() {
            return browser.manager.serverPath()
                    + ".browser.view.properties.load.html" + resource.getPath();
        }

        protected @Nullable org.apache.sling.api.resource.Resource resolvePath(@NotNull final String value) {
            if (StringUtils.isNotBlank(value) && value.startsWith("/")) {
                final ResourceResolver resolver = resolver();
                final org.apache.sling.api.resource.Resource resource = resolver.getResource(value);
                if (resource != null && browser.manager.isAllowedResource(resource)) {
                    return resource;
                }
            }
            return null;
        }

        protected @Nullable org.apache.sling.api.resource.Resource resolveType(@NotNull final String value) {
            if (StringUtils.isNotBlank(value) && StringUtils.countMatches(value, "/") > 1) {
                final ResourceResolver resolver = resolver();
                org.apache.sling.api.resource.Resource resource;
                for (String root : resolver.getSearchPath()) {
                    resource = resolvePath(root + value);
                    if (resource != null) {
                        return resource;
                    }
                }
            }
            return null;
        }

        protected ResourceResolver resolver() {
            return resource.getResourceResolver();
        }
    }

    public static class Property extends LinkedHashMap<String, Object> {

        protected final transient Resource resource;
        protected final Supplier<?> supplier;

        protected transient Object value;
        protected transient PropertyType type;

        public Property(@NotNull final String name, @NotNull final Supplier<?> value) {
            this(name, value, null);
        }

        public Property(@NotNull final String name, @NotNull final Supplier<?> value,
                        @Nullable final Resource resource) {
            this.resource = resource;
            this.supplier = value;
            put("name", name);
            put("value", (Supplier<?>) this::getValue);
            put("multiValue", (Supplier<Boolean>) this::isMultiValue);
            put("type", (Supplier<String>) this::getType);
            put("css", (Supplier<String>) () -> "type-" + this.getType().toLowerCase()
                    .replace("[]", " type-multi"));
        }

        protected @NotNull Object getValue() {
            if (value == null) {
                Object rawValue = supplier.get();
                if (rawValue instanceof Object[]) {
                    final List<Map<String, Object>> values = new ArrayList<>();
                    for (Object item : (Object[]) rawValue) {
                        values.add(getValue(item));
                    }
                    value = values;
                    if (rawValue instanceof String[]) {
                        type = PropertyType.String;
                    } else if (rawValue instanceof Long[]) {
                        type = PropertyType.Long;
                    } else if (rawValue instanceof Double[]) {
                        type = PropertyType.Double;
                    } else if (rawValue instanceof Boolean[]) {
                        type = PropertyType.Boolean;
                    } else if (rawValue instanceof Calendar[]) {
                        type = PropertyType.Date;
                    } else {
                        type = PropertyType.Unknown;
                    }
                } else {
                    final Map<String, Object> v = getValue(rawValue);
                    value = v;
                    type = (PropertyType) v.get("type");
                }
            }
            return value;
        }

        public boolean isMultiValue() {
            return getValue() instanceof Collection<?>;
        }

        public String getType() {
            getValue();
            return isMultiValue() ? type.name() + "[]" : type.name();
        }

        protected @NotNull Map<String, Object> getValue(@Nullable final Object value) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (value instanceof String) {
                result.put("type", PropertyType.String);
                result.put("value", value);
                if (resource != null) {
                    org.apache.sling.api.resource.Resource target = resource.resolvePath((String) value);
                    if (target == null) {
                        target = resource.resolveType((String) value);
                    }
                    if (target != null) {
                        result.put("link", target.getPath());
                    }
                }
            } else if (value instanceof Calendar) {
                result.put("type", PropertyType.Date);
                result.put("value", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z")
                        .format(((Calendar) value).getTime()));
            } else if (value instanceof InputStream) {
                result.put("type", PropertyType.Binary);
                result.put("value", "download...");
                if (resource != null) {
                    result.put("link", resource.binaryDownloadLink());
                }
            } else if (value instanceof Double) {
                result.put("type", PropertyType.Double);
                result.put("value", value.toString());
            } else if (value instanceof Boolean) {
                result.put("type", PropertyType.Boolean);
                result.put("value", value.toString());
            } else if (value instanceof Number) {
                result.put("type", PropertyType.Long);
                result.put("value", value.toString());
            } else if (value != null) {
                result.put("type", PropertyType.Unknown);
                result.put("value", value.toString());
            }
            return result;
        }
    }
}