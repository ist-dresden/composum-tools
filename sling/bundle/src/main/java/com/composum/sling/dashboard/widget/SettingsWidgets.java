package com.composum.sling.dashboard.widget;

import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Processor;
import com.composum.sling.tools.Properties;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Tile;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * The tools plugin which provides a dashboard tile presenting the properties of configured OSGi services.
 */
@Component(service = {ToolsPlugin.class}, immediate = true)
@Designate(ocd = SettingsWidgets.Config.class)
public class SettingsWidgets extends AbstractToolsPlugin {

    /** The default key of this plugin. */
    public static final String KEY = "settings";
    /** The default label of this plugin. */
    public static final String LABEL = "Service Settings";
    /** The default rank of this plugin. */
    public static final int RANK = 5500;

    /**
     * Default constructor.
     */
    public SettingsWidgets() {
    }

    /**
     * The OSGi configuration of the settings widgets.
     */
    @ObjectClassDefinition(name = "Settings Widgets")
    public @interface Config {

        /**
         * The service settings to inspect, each given as a request template.
         *
         * @return the inspected service settings templates
         */
        @AttributeDefinition(name = "Inspected Settings",
                description = "a set of request templates matching: 'service-type(filter)[service-properties,...]'; caution: prevent from showing secrets!")
        String[] inspectedSettings() default {
                "org.osgi.service.http.HttpService[org\\.osgi\\.service\\.http\\..*,osgi\\.http\\.service\\.endpoints]",
                "org.apache.sling.settings.SlingSettingsService[runModes]"
        };

        /**
         * The key of the plugin.
         *
         * @return the plugin key
         */
        @AttributeDefinition()
        String key() default SettingsWidgets.KEY;

        /**
         * The label of the plugin.
         *
         * @return the plugin label
         */
        @AttributeDefinition()
        String label() default SettingsWidgets.LABEL;

        /**
         * The rank of the plugin.
         *
         * @return the plugin rank
         */
        @AttributeDefinition()
        int rank() default SettingsWidgets.RANK;

        /**
         * Whether the plugin is enabled.
         *
         * @return 'true' if the plugin is enabled
         */
        @AttributeDefinition()
        boolean enabled() default true;
    }

    /** Matches an inspected settings rule: 'service-type(filter)[service-properties,...]'. */
    public static final Pattern SETTINGS_RULE = Pattern.compile(
            "^(?<type>[^\\[(]+)(?<filter>\\([^)]+\\))?(\\[(?<props>.*)])?$");

    /** Matches a plain property name (not a pattern). */
    public static final Pattern PROPERTY_NAME = Pattern.compile("^[a-zA-Z0-9$@_:.-]+$");

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    /** The bundle context of this component. */
    protected BundleContext bundleContext;
    /** The current configuration. */
    protected Config config;

    /** The renderer of the inspected service settings. */
    protected Settings settings;

    /**
     * Reads the configuration, starts tracking the services and attaches this plugin to the manager.
     *
     * @param bundleContext the bundle context
     * @param config        the component configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        settings = new Settings(config);
        bundleContext.addServiceListener(settings);
        manager.plugins().attach(this);
    }

    /**
     * Detaches this plugin from the manager.
     */
    @Deactivate
    protected void deactivate() {
        manager.plugins().detach(this);
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse(LABEL);
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(RANK);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled() && settings.isEnabled();
    }

    @Override
    public @Nullable String widgetViewLink(@NotNull final SlingHttpServletRequest request,
                                           @NotNull final SlingHttpServletResponse response,
                                           @NotNull final String widgetKey) {
        return widgetLink(request, response, widgetKey + ".view");
    }

    @Override
    public @NotNull List<Widget> widgets() {
        final List<Widget> widgets = new ArrayList<>();
        widgets.add(new Tile(key(), label(), rank()));
        return widgets;
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull final List<String> selectors) {
        Result<?> result;
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = resource(request);
                break;
            case "settings":
            default:
                result = settings.process(request, response, selectors);
                break;
        }
        return result;
    }

    /**
     * Renders the configured, inspected OSGi service properties, tracking service changes to
     * invalidate its cached results.
     */
    protected class Settings implements Processor, ServiceListener {

        /**
         * A rule which selects a service and the service properties to inspect.
         */
        public class SettingsRule {

            /** The type (interface, PID or implementation class name) of the service. */
            public final String serviceType;
            /** The optional service filter. */
            public final String filter;
            /** The patterns of the names of the properties to inspect (all properties if empty). */
            public final List<Pattern> properties;

            /**
             * Creates a rule from a matched rule expression.
             *
             * @param matcher the matcher with the groups 'type', 'filter' and 'props' of {@link #SETTINGS_RULE}
             */
            public SettingsRule(Matcher matcher) {
                serviceType = matcher.group("type");
                filter = matcher.group("filter");
                properties = new ArrayList<>();
                final String props = matcher.group("props");
                if (StringUtils.isNotBlank(props)) {
                    for (String pattern : StringUtils.split(props, ",")) {
                        properties.add(Pattern.compile(pattern));
                    }
                }
            }
        }

        /** The configured rules. */
        protected List<SettingsRule> configuration;
        /** The cached service references of the rules. */
        protected transient Map<SettingsRule, List<ServiceReference<?>>> serviceReferences = new HashMap<>();

        /**
         * Creates the settings renderer from the configured rules.
         *
         * @param config the component configuration
         */
        public Settings(Config config) {
            configuration = new ArrayList<>();
            for (final String rule : config.inspectedSettings()) {
                if (StringUtils.isNotBlank(rule)) {
                    Matcher matcher = SETTINGS_RULE.matcher(rule);
                    if (matcher.matches()) {
                        configuration.add(new SettingsRule(matcher));
                    }
                }
            }
            serviceReferences.clear();
        }

        @Override
        public void serviceChanged(ServiceEvent event) {
            final ServiceReference<?> ref = event.getServiceReference();
            for (Map.Entry<SettingsRule, List<ServiceReference<?>>> entry : serviceReferences.entrySet()) {
                if (entry.getValue().contains(ref)) {
                    serviceReferences.remove(entry.getKey());
                }
            }
        }

        @Override
        public boolean isEnabled() {
            return !configuration.isEmpty();
        }

        @Override
        public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull final List<String> selectors) {
            Result<?> result = new Result<>(SC_NOT_FOUND);
            final String template = Manager.consume(selectors, "tile");
            final ResourceResolver resolver = request.getResourceResolver();
            final Resource context = manager.requestResource(request);
            final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                    .with("services", (Supplier<?>) () -> getServices(resolver, context))
            ), "/sling/dashboard/widgets/settings/" + template + ".html"));
            if (content != null) {
                result = new Result<>(content, HTML_TYPE);
            }
            return result;
        }

        /**
         * Retrieves the service object of a service reference.
         *
         * @param reference the service reference
         * @return the service object, or {@code null} if the service is not available
         */
        public Object getService(@NotNull final ServiceReference<?> reference) {
            return bundleContext.getService(reference);
        }

        /**
         * Collects the services matching the configured rules.
         *
         * @param resolver the resolver used to render the property values
         * @param context  the current context resource
         * @return the template values of the services
         */
        protected List<Values> getServices(@NotNull final ResourceResolver resolver,
                                           @Nullable final Resource context) {
            List<Values> providers = new ArrayList<>();
            for (SettingsRule config : configuration) {
                for (ServiceReference<?> reference : getServiceReferences(config)) {
                    final String name = Optional.ofNullable(reference.getProperty("service.pid"))
                            .map(Object::toString).orElse(config.serviceType);
                    final boolean available = getService(reference) != null;
                    providers.add(new Values()
                            .with("name", name)
                            .with("label", StringUtils.substringAfterLast(name, "."))
                            .with("status", available ? "success" : "danger")
                            .with("available", available)
                            .with("properties", (Supplier<?>) () -> getProperties(resolver, context, config, reference))
                    );
                }
            }
            return providers;
        }

        /**
         * Collects the inspected properties of a service.
         *
         * @param resolver  the resolver used to render the property values
         * @param context   the current context resource
         * @param config    the rule which selected the service
         * @param reference the service reference
         * @return the template values of the properties
         */
        public @NotNull List<Values> getProperties(@NotNull final ResourceResolver resolver,
                                                   @Nullable final Resource context,
                                                   @NotNull final SettingsRule config,
                                                   @NotNull final ServiceReference<?> reference) {
            final List<Values> result = new ArrayList<>();
            for (final String name : getPropertyNames(config, reference)) {
                final StringBuilder buffer = new StringBuilder();
                String type = Properties.toHtml(manager, resolver, context, buffer, getProperty(reference, name));
                result.add(new Values()
                        .with("name", name)
                        .with("value", buffer.toString())
                        .with("type", type)
                );
            }
            return result;
        }

        /**
         * Determines the names of the properties to inspect for a service.
         *
         * @param config    the rule which selected the service
         * @param reference the service reference
         * @return the property names
         */
        public @NotNull Iterable<String> getPropertyNames(@NotNull final SettingsRule config,
                                                          @NotNull final ServiceReference<?> reference) {
            final Set<String> propertyNames = new HashSet<>();
            for (final String name : reference.getPropertyKeys()) {
                if (config.properties.isEmpty()) {
                    propertyNames.add(name);
                } else {
                    for (final Pattern pattern : config.properties) {
                        final Matcher matcher = pattern.matcher(name);
                        if (matcher.matches()) {
                            propertyNames.add(name);
                            break;
                        }
                    }
                }
            }
            Object service = bundleContext.getService(reference);
            if (service != null) {
                for (final Pattern pattern : config.properties) {
                    final String name = pattern.toString();
                    if (PROPERTY_NAME.matcher(name).matches()) {
                        propertyNames.add(name);
                    }
                }
            }
            return propertyNames;
        }

        /**
         * Determines the value of a service property, from the service reference or, if not found there,
         * from the getter of the service object.
         *
         * @param reference the service reference
         * @param name      the property name
         * @return the property value, or {@code null} if it can not be determined
         */
        public @Nullable Object getProperty(@NotNull final ServiceReference<?> reference,
                                            @NotNull final String name) {
            Object value = reference.getProperty(name);
            if (value == null) {
                value = getProperty(getService(reference), name);
            }
            return value;
        }

        /**
         * Finds the service references matching a rule (cached until a service change).
         *
         * @param config the rule
         * @return the matching service references
         */
        protected @NotNull List<ServiceReference<?>> getServiceReferences(@NotNull final SettingsRule config) {
            List<ServiceReference<?>> configReferences = serviceReferences.get(config);
            if (configReferences == null) {
                configReferences = new ArrayList<>();
                try {
                    ServiceReference<?>[] references = bundleContext.getAllServiceReferences(config.serviceType,
                            StringUtils.isNotBlank(config.filter) ? config.filter : null);
                    if (references != null) {
                        configReferences.addAll(Arrays.asList(references));
                    } else {
                        ServiceReference<?>[] all = bundleContext.getAllServiceReferences(null,
                                StringUtils.isNotBlank(config.filter) ? config.filter : null);
                        for (ServiceReference<?> ref : all) {
                            try {
                                Object service;
                                if (config.serviceType.equals(ref.getProperty("service.pid"))
                                        || (!config.serviceType.contains("~")
                                        && config.serviceType.equals(ref.getProperty("service.factoryPid")))
                                        || ((service = bundleContext.getService(ref)) != null
                                        && config.serviceType.equals(service.getClass().getName()))) {
                                    configReferences.add(ref);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                serviceReferences.put(config, configReferences);
            }
            return configReferences;
        }

        /**
         * Reads a property from a service object using the getter named like the property
         * (or its bean style 'get' variant).
         *
         * @param object the service object
         * @param name   the property name
         * @return the property value, or {@code null} if there is no accessible getter
         */
        protected @Nullable Object getProperty(@Nullable final Object object, @Nullable final String name) {
            Object value = null;
            if (object != null && StringUtils.isNotBlank(name)) {
                final Class<?> objectClass = object.getClass();
                Method getter;
                try {
                    try {
                        getter = objectClass.getMethod(name);
                        value = getter.invoke(object);
                    } catch (NoSuchMethodException ignore) {
                        getter = objectClass.getMethod(
                                "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
                        value = getter.invoke(object);
                    }
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignore) {
                }
            }
            return value;
        }
    }
}
