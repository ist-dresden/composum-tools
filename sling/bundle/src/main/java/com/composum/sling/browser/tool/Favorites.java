package com.composum.sling.browser.tool;

import com.composum.sling.browser.AbstractTool;
import com.composum.sling.browser.Browser;
import com.composum.sling.browser.Tool;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Component(service = {Tool.class, Favorites.class}, immediate = true)
@Designate(ocd = Favorites.Config.class)
public class Favorites extends AbstractTool {

    public static final String KEY = "favorites";

    @ObjectClassDefinition(name = "Composum Browser Favorites Tool")
    public @interface Config {

        @AttributeDefinition()
        String key() default Favorites.KEY;

        @AttributeDefinition()
        String label() default "Favorites";

        @AttributeDefinition()
        String icon() default "star";

        @AttributeDefinition()
        int rank() default 6000;

        @AttributeDefinition()
        int historyMax() default 100;
    }

    public static final Pattern GROUP_PATTERN = Pattern.compile("^(?<label>[^=]+)=(?<pattern>.+)$");

    @Reference
    protected Browser browser;

    protected BundleContext bundleContext;
    protected Config config;

    protected Map<String, Values> favoriteGroups = new LinkedHashMap<>();

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        this.favoriteGroups.clear();
        for (final String rule : browser.favoriteRules()) {
            final Matcher matcher = GROUP_PATTERN.matcher(rule);
            if (matcher.matches()) {
                final String label = matcher.group("label");
                final String pattern = matcher.group("pattern");
                final Values values = new Values()
                        .with("name", label)
                        .with("pattern", pattern);
                if ("ALL".equals(label)) {
                    values.with("icon", "stars");
                } else if ("@history".equals(pattern)) {
                    values.with("icon", "clock-history");
                }
                this.favoriteGroups.put(label, values);
            }
        }
        browser.tools().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        browser.tools().detach(this);
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
        return Optional.ofNullable(config).map(Config::label).orElse("Favorites");
    }

    @Override
    public @NotNull String icon() {
        return Optional.ofNullable(config).map(Config::icon).orElse("star");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(6000);
    }

    @Override
    public @NotNull Result<?> process(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response, @NotNull List<String> selectors) {
        Result<?> result = new Result<>(SC_NOT_FOUND);
        final RequestPathInfo pathInfo = request.getRequestPathInfo();
        final String path = Optional.ofNullable(pathInfo.getSuffix()).filter(StringUtils::isNotBlank).orElse("/");
        final Reader content = browser.templateReader(getTemplate(new TemplateContext(new Values()
                .with("favorites", new Values()
                        .with("groups", favoriteGroups)
                        .with("historyMax", config.historyMax()))
        ), "tool"));
        if (content != null) {
            result = new Result<>(content, HTML_TYPE);
        }
        return result;
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "tool", current ->
                    new Template("/sling/browser/tool/favorites/favorites.html",
                            new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    @Override
    public @NotNull Collection<String> styles() {
        return Collections.singletonList("/sling/browser/tool/favorites/style.css");
    }

    @Override
    public @NotNull Collection<String> scripts() {
        return Collections.singletonList("/sling/browser/tool/favorites/script.js");
    }
}
