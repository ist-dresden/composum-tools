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
import static com.composum.sling.tools.Common.JCR_LAST_MODIFIED;
import static com.composum.sling.tools.Common.JCR_MIME_TYPE;
import static com.composum.sling.tools.Common.PROPERTY_DATE_FORMAT;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/** The browser view presenting the properties of the current resource. */
@Component(service = {View.class, PropertiesView.class}, immediate = true)
@Designate(ocd = PropertiesView.Config.class)
public class PropertiesView extends AbstractView {

    /**
     * Default constructor.
     */
    public PropertiesView() {
    }

    /** the key of this view */
    public static final String KEY = "properties";

    /** OSGi metatype configuration of the Properties view. */
    @ObjectClassDefinition(name = "Composum Browser Properties View")
    public @interface Config {

        /**
         * The selector key of this view.
         *
         * @return the selector key of this view
         */
        @AttributeDefinition()
        String key() default PropertiesView.KEY;

        /**
         * The navigation label of this view.
         *
         * @return the navigation label of this view
         */
        @AttributeDefinition()
        String label() default "Properties";

        /**
         * The navigation rank of this view.
         *
         * @return the navigation rank of this view
         */
        @AttributeDefinition()
        int rank() default 8000;
    }

    /** the browser plugin this view belongs to */
    @Reference
    protected Browser browser;

    /** the bundle context this view was activated with */
    protected BundleContext bundleContext;
    /** the current OSGi configuration */
    protected Config config;

    /**
     * Activates or reconfigures this view and attaches it to the browser.
     *
     * @param bundleContext the bundle context of this component
     * @param config        the current OSGi configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        browser.views().attach(this);
    }

    /** Detaches this view from the browser. */
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
            case "form":{
                final org.apache.sling.api.resource.Resource resource = browser.targetResource(request);
                if (resource != null) {
                    final Resource values = new Resource(resource, browser.manager);
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                            new TemplateContext.Values()
                                    .with("resource", values)
                                    .with("browser.writeEnabled", browser.writeEnabled(request))
                                    .with("browser.dialog", browser.dialogUri())
                                    .with("browser.propertyCopyAction", browser.changeActionLink("propertyCopy"))
                                    .with("browser.propertyDeleteAction", browser.changeActionLink("propertyDelete"))
                    ), "form"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                }
            }
            break;
            case "load":
                result = openContent(request);
                break;
            default: {
                final org.apache.sling.api.resource.Resource resource = browser.targetResource(request);
                if (resource != null) {
                    final Resource values = new Resource(resource, browser.manager);
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                            new TemplateContext.Values()
                                    .with("resource", values)
                                    .with("properties", values.get("properties"))
                                    .with("browser.writeEnabled", browser.writeEnabled(request))
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

    /** the template factories of this view by template key */
    public final Map<String, Factory> templates = Map.of(
            "view", current ->
                    new Template("/sling/browser/view/properties/properties.html",
                            new TemplateContext(current, new TemplateContext.Values()), this),
            "form", current ->
                    new Template("/sling/browser/view/properties/form.html",
                            new TemplateContext(current, new TemplateContext.Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    /**
     * Opens the binary content of the requested resource as a download.
     *
     * @param request the current request
     * @return the content stream result, or 'Not Found' if there is no binary content
     */
    protected Result<InputStream> openContent(@NotNull final SlingHttpServletRequest request) {
        Result<InputStream> result = new Result<>(SC_NOT_FOUND);
        org.apache.sling.api.resource.Resource resource = browser.targetResource(request);
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
                Calendar lastModified = values.get(JCR_LAST_MODIFIED, Calendar.class);
                if (lastModified != null) {
                    result.setHeader(HTTP_LAST_MODIFIED, lastModified.getTime());
                }
            }
        }
        return result;
    }

    /**
     * The file name of the resource; for a 'jcr:content' resource the name of its parent.
     *
     * @param resource the resource
     * @return the file name
     */
    protected @NotNull String filename(@NotNull final org.apache.sling.api.resource.Resource resource) {
        final String name = resource.getName();
        return JCR_CONTENT.equals(name) ? Optional.ofNullable(resource.getParent())
                .map(org.apache.sling.api.resource.Resource::getName).orElse(name) : name;
    }

    /** The value types of a displayed property. */
    public enum PropertyType {
        /** a value of a type without specific handling */
        Unknown,
        /** a text value */
        String,
        /** an integer number value */
        Long,
        /** a floating point number value */
        Double,
        /** a boolean value */
        Boolean,
        /** a date value */
        Date,
        /** a binary value */
        Binary
    }

    /** The template model of a resource with its properties, resolved lazily. */
    public class Resource extends LinkedHashMap<String, Object> {

        /** The allowed properties of a resource sorted by name. */
        public class Properties extends TreeMap<String, Property> {

            /** the value map the properties are read from */
            protected final ValueMap values;

            /**
             * Creates the property collection from a value map; only properties allowed by the manager are included.
             *
             * @param values  the values of the resource, can be {@code null}
             * @param manager the manager deciding which properties are allowed
             */
            public Properties(@Nullable final ValueMap values, @NotNull final Manager manager) {
                this.values = values;
                if (values != null) {
                    for (String key : values.keySet()) {
                        if (manager.isAllowedProperty(key)) {
                            put(key, new Property(key, (Supplier<?>) () -> this.values.get(key), Resource.this,
                                    browser.isProtectedProperty(key)));
                        }
                    }
                }
            }
        }

        /** the wrapped Sling resource */
        protected transient org.apache.sling.api.resource.Resource resource;

        /**
         * Creates the template model of a resource.
         *
         * @param resource the resource to wrap
         * @param manager  the manager deciding which properties are allowed
         */
        public Resource(@NotNull final org.apache.sling.api.resource.Resource resource,
                        @NotNull final Manager manager) {
            this.resource = resource;
            put("name", (Supplier<String>) resource::getName);
            put("path", (Supplier<String>) resource::getPath);
            put("properties", new Properties(resource.getValueMap(), manager).values());
            put("hasChildren", (Supplier<Boolean>) resource::hasChildren);
        }

        /**
         * The link to download a binary property value of this resource.
         *
         * @return the download link
         */
        protected @NotNull String binaryDownloadLink() {
            return browser.manager.serverPath()
                    + ".browser.view.properties.load.html" + resource.getPath();
        }

        /**
         * Resolves a property value as an absolute resource path.
         *
         * @param value the property value
         * @return the allowed resource at the path, or {@code null} if the value is not a path of such a resource
         */
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

        /**
         * Resolves a property value as a resource type relative to the search path.
         *
         * @param value the property value
         * @return the first allowed resource found in the search path, or {@code null} if there is none
         */
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

        /**
         * The resolver of the wrapped resource.
         *
         * @return the resource resolver
         */
        protected ResourceResolver resolver() {
            return resource.getResourceResolver();
        }
    }

    /** The template model of a single property with its type and value. */
    public static class Property extends LinkedHashMap<String, Object> {

        /** the resource owning the property, can be {@code null} */
        protected final transient Resource resource;
        /** provides the raw property value */
        protected final Supplier<?> supplier;

        /** the prepared value (a map or a list of maps), determined on first access */
        protected transient Object value;
        /** the type of the property, determined on first access */
        protected transient PropertyType type;

        /**
         * Creates a property without an owning resource.
         *
         * @param name  the property name
         * @param value provides the raw property value
         */
        public Property(@NotNull final String name, @NotNull final Supplier<?> value) {
            this(name, value, null, false);
        }

        /**
         * Creates a property.
         *
         * @param name        the property name
         * @param value       provides the raw property value
         * @param resource    the resource owning the property, can be {@code null}
         * @param isProtected whether the property is protected against editing
         */
        public Property(@NotNull final String name, @NotNull final Supplier<?> value,
                        @Nullable final Resource resource, final boolean isProtected) {
            this.resource = resource;
            this.supplier = value;
            put("name", name);
            put("value", (Supplier<?>) this::getValue);
            put("multiValue", (Supplier<Boolean>) this::isMultiValue);
            put("type", (Supplier<String>) this::getType);
            // repository-managed properties (primary type, identity, versioning, ...) stay
            // editable - this is a warning affordance, not a block - see ChangeOperations#PROTECTED_PROPERTIES
            put("protected", isProtected);
            put("css", (Supplier<String>) () -> "type-" + this.getType().toLowerCase()
                    .replace("[]", " type-multi"));
        }

        /**
         * The prepared value of this property; this also determines its type.
         *
         * @return a value map, or a list of value maps for a multi-value property
         */
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

        /**
         * Whether this property has multiple values.
         *
         * @return {@code true} if the property has multiple values
         */
        public boolean isMultiValue() {
            return getValue() instanceof Collection<?>;
        }

        /**
         * The type name of this property, with a trailing '[]' for a multi-value property.
         *
         * @return the type name
         */
        public String getType() {
            getValue();
            return isMultiValue() ? type.name() + "[]" : type.name();
        }

        /**
         * Prepares a single value for presentation.
         *
         * @param value the raw value
         * @return a map with the 'type', the displayed 'value' and optionally a 'link'
         */
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
                // the same format the Changes property-edit dialog uses to pre-fill a Date value
                // (see ChangeOperations#DATE_FORMAT) - so a value reads identically whether you are
                // just looking at it here or editing it there
                result.put("value", new SimpleDateFormat(PROPERTY_DATE_FORMAT)
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