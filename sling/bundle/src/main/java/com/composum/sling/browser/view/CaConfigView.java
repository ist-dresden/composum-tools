package com.composum.sling.browser.view;

import com.composum.sling.browser.AbstractView;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.View;
import com.composum.sling.browser.view.PropertiesView.Property;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.caconfig.ConfigurationBuilder;
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

import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

@Component(service = {View.class, CaConfigView.class}, immediate = true)
@Designate(ocd = CaConfigView.Config.class)
public class CaConfigView extends AbstractView {

    public static final String KEY = "cac";

    @ObjectClassDefinition(name = "Composum Browser CA-Config View")
    public @interface Config {

        @AttributeDefinition()
        String key() default CaConfigView.KEY;

        @AttributeDefinition()
        String label() default "CaC";

        @AttributeDefinition()
        int rank() default 5000;
    }

    /** Property keys we do not display from the configurations. */
    public static final Pattern IGNORED_PROPERTY_KEYS = Pattern.compile("^(jcr|cq):.*$");

    /** Property keys we want to display from the configurations. */
    public static final Pattern USEFUL_PROPERTY_KEYS = Pattern.compile("^cq:lastRep.*ed$");

    public static final Pattern RULE_PATTERN = Pattern.compile(
            "^(?<type>[^\\[(]+)(?<filter>\\([^)]+\\))?(\\[(?<props>.*)])?$");

    public static class ConfigurationRule {

        public final String configType;
        public final List<Pattern> properties;

        public ConfigurationRule(Matcher matcher) {
            configType = matcher.group("type");
            properties = new ArrayList<>();
            final String props = matcher.group("props");
            if (StringUtils.isNotBlank(props)) {
                for (String pattern : StringUtils.split(props, ",")) {
                    try {
                        properties.add(Pattern.compile(pattern));
                    } catch (PatternSyntaxException ignore) {
                    }
                }
            }
        }
    }

    private List<ConfigurationRule> parseConfigurationRules(Collection<String> configuredRules) {
        List<ConfigurationRule> parsedConfigurations = new ArrayList<>();
        for (final String rule : configuredRules) {
            if (StringUtils.isNotBlank(rule)) {
                Matcher matcher = RULE_PATTERN.matcher(rule);
                if (matcher.matches()) {
                    parsedConfigurations.add(new ConfigurationRule(matcher));
                }
            }
        }
        return parsedConfigurations;
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
        return Optional.ofNullable(config).map(Config::label).orElse("CaC");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(5000);
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
            default: {
                final Resource resource = browser.manager.requestResource(request);
                if (resource != null) {
                    final Reader content = browser.templateReader(getTemplate(new TemplateContext(
                            new Values()
                                    .with("providers", (Supplier<?>) () -> getProviders(request))
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
                    new Template("/sling/browser/view/cac/cac.html",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);

    }

    protected List<Values> getProviders(@NotNull final SlingHttpServletRequest request) {
        List<Values> result = new ArrayList<>();
        for (final SettingsProvider provider : getSettingsProviders(request)) {
            result.add(new Values()
                    .with("name", provider.getName())
                    .with("domId", (Supplier<?>) () -> domId(provider.getName()))
                    .with("available", (Supplier<?>) provider::isAvailable)
                    .with("properties", (Supplier<?>) () -> getProviderProperties(provider))
            );
        }
        return result;
    }

    protected List<Property> getProviderProperties(@NotNull final SettingsProvider provider) {
        List<Property> result = new ArrayList<>();
        for (final Map.Entry<String, Object> entry : provider.getProperties().entrySet()) {
            result.add(new Property(entry.getKey(), (Supplier<?>) entry::getValue));
        }
        return result;
    }

    protected @NotNull String domId(@NotNull final String serviceType) {
        return serviceType.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    protected static Comparator<String> NAME_COMPARATOR = new Comparator<>() {

        @Override
        public int compare(String o1, String o2) {
            return key(o1).compareTo(key(o2));
        }

        @NotNull
        private String key(@NotNull final String name) {
            return StringUtils.contains(name, ":") ? "X_" + name : "A_" + name;
        }
    };

    protected abstract static class SettingsProvider {

        protected Map<String, Object> properties;

        public abstract String getName();

        public abstract String getLabel();

        public abstract boolean isAvailable();

        public abstract @NotNull Iterable<String> getPropertyNames();

        public abstract @Nullable Object getProperty(@NotNull String name);

        public @NotNull Map<String, Object> getProperties() {
            if (properties == null) {
                properties = new TreeMap<>(NAME_COMPARATOR);
                for (String name : getPropertyNames()) {
                    addProperty(properties, name);
                }
            }
            return properties;
        }

        protected void addProperty(@NotNull final Map<String, Object> properties, @NotNull final String name) {
            if (StringUtils.isNotBlank(name)) {
                Object value = getProperty(name);
                if (value != null) {
                    properties.put(name, value);
                }
            }
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

    protected static class ConfigurationProvider extends SettingsProvider {

        @NotNull
        protected final ConfigurationRule config;
        @NotNull
        protected final ValueMap valueMap;

        public ConfigurationProvider(@NotNull ConfigurationRule config, @NotNull ValueMap valueMap) {
            this.config = config;
            this.valueMap = valueMap;
        }

        @Override
        public String getName() {
            return config.configType;
        }

        @Override
        public String getLabel() {
            return config.configType;
        }

        @Override
        public boolean isAvailable() {
            return !valueMap.isEmpty();
        }

        @Override
        public @NotNull Iterable<String> getPropertyNames() {
            final Set<String> propertyNames = new HashSet<>();
            for (String property : valueMap.keySet()) {
                if (config.properties.isEmpty()
                        && (!IGNORED_PROPERTY_KEYS.matcher(property).matches()
                        || USEFUL_PROPERTY_KEYS.matcher(property).matches())) {
                    propertyNames.add(property);
                }
                for (final Pattern pattern : config.properties) {
                    final Matcher matcher = pattern.matcher(property);
                    if (matcher.matches()) {
                        propertyNames.add(property);
                        break;
                    }
                }
            }
            return propertyNames;
        }

        @Override
        public @Nullable Object getProperty(@NotNull final String name) {
            return valueMap.get(name);
        }
    }

    protected @NotNull List<SettingsProvider> getSettingsProviders(@NotNull final SlingHttpServletRequest request) {
        final List<SettingsProvider> providers = new ArrayList<>();
        final Resource targetResource = browser.manager.requestResource(request);
        if (targetResource != null) {
            final ConfigurationBuilder builder = targetResource.adaptTo(ConfigurationBuilder.class);
            if (builder != null) {
                for (ConfigurationRule config : parseConfigurationRules(browser.caConfigurationRules())) {
                    @NotNull ValueMap valueMap = builder.name(config.configType).asValueMap();
                    providers.add(new ConfigurationProvider(config, valueMap));
                }
            }
        }
        return providers;
    }
}
