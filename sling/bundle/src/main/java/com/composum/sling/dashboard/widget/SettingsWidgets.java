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

@Component(service = {ToolsPlugin.class}, immediate = true)
@Designate(ocd = SettingsWidgets.Config.class)
public class SettingsWidgets extends AbstractToolsPlugin {

    public static final String KEY = "settings";
    public static final String LABEL = "Service Settings";
    public static final int RANK = 5500;

    @ObjectClassDefinition(name = "Settings Widgets")
    public @interface Config {

        @AttributeDefinition(name = "Inspected Settings",
                description = "a set of request templates matching: 'service-type(filter)[service-properties,...]'; caution: prevent from showing secrets!")
        String[] inspectedSettings() default {
                "org.osgi.service.http.HttpService[org\\.osgi\\.service\\.http\\..*,osgi\\.http\\.service\\.endpoints]",
                "org.apache.sling.settings.SlingSettingsService[runModes]"
        };

        @AttributeDefinition()
        String key() default SettingsWidgets.KEY;

        @AttributeDefinition()
        String label() default SettingsWidgets.LABEL;

        @AttributeDefinition()
        int rank() default SettingsWidgets.RANK;

        @AttributeDefinition()
        boolean enabled() default true;
    }

    public static final Pattern SETTINGS_RULE = Pattern.compile(
            "^(?<type>[^\\[(]+)(?<filter>\\([^)]+\\))?(\\[(?<props>.*)])?$");

    public static final Pattern PROPERTY_NAME = Pattern.compile("^[a-zA-Z0-9$@_:.-]+$");

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    protected BundleContext bundleContext;
    protected Config config;

    protected Settings settings;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        settings = new Settings(config);
        bundleContext.addServiceListener(settings);
        manager.plugins().attach(this);
    }

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

        public class SettingsRule {

            public final String serviceType;
            public final String filter;
            public final List<Pattern> properties;

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

        protected List<SettingsRule> configuration;
        protected transient Map<SettingsRule, List<ServiceReference<?>>> serviceReferences = new HashMap<>();

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

        public Object getService(@NotNull final ServiceReference<?> reference) {
            return bundleContext.getService(reference);
        }

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

        public @Nullable Object getProperty(@NotNull final ServiceReference<?> reference,
                                            @NotNull final String name) {
            Object value = reference.getProperty(name);
            if (value == null) {
                value = getProperty(getService(reference), name);
            }
            return value;
        }

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
