package com.composum.aem.console.impl;

import com.composum.aem.console.ConsoleProxy;
import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.PluginSet;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Composum Tools console: a {@link ToolsPlugin} that aggregates all registered {@link ConsoleProxy}
 * services into one page, dispatching each request to the proxy addressed by its leading selector
 * (see {@link #processGet}) and rendering the shared page chrome around whichever proxy's content
 * was requested.
 */
@Component(service = {ToolsPlugin.class, Console.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = Console.Config.class)
public class Console extends AbstractToolsPlugin {

    public static final String KEY = "console";

    @ObjectClassDefinition(name = "Composum Tools Console")
    public @interface Config {

        @AttributeDefinition()
        String key() default Console.KEY;

        @AttributeDefinition()
        String label() default "Console";

        @AttributeDefinition()
        int rank() default 1000;

        @AttributeDefinition()
        boolean enabled() default true;

        @AttributeDefinition()
        String[] views() default {
        /*      "requests",
                "resolver",
                "servlets"      // if empty, all implemented and active views (proxies) are enabled   */
        };
    }

    protected PluginSet<ConsoleProxy> proxies = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull ConsoleProxy service) {
            return service.isEnabled();
        }
    };

    @Reference
    protected Manager manager;

    protected BundleContext bundleContext;
    protected Config config;

    protected transient List<String> views;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        views = Common.listOf(config.views());
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
        return Optional.ofNullable(config).map(Config::label).orElse("Console");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(1000);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    public @NotNull Manager manager() {
        return manager;
    }

    public @NotNull PluginSet<ConsoleProxy> proxies() {
        return proxies;
    }

    @Override
    public @NotNull List<Widget> widgets() {
        List<Widget> widgets = new ArrayList<>();
        for (ConsoleProxy proxy : proxies.set()) {
            final String key = proxy.key();
            widgets.add(new Page(key, proxy.label(), () -> proxyLink(proxy), widgets.isEmpty()));
        }
        return widgets;
    }

    protected @NotNull String proxyLink(@NotNull final ConsoleProxy proxy) {
        return manager().serverPath() + ".console." + proxy.key() + ".html";
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        switch (request.getMethod()) {
            case "GET":
                result = processGet(request, response, selectors);
                break;
            case "POST":
                result = processPost(request, response, selectors);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * Routes a GET request: the leading selector is either {@code "resource"} (serves a static
     * resource, see {@link #resource}) or the {@link ConsoleProxy#key()} of the proxy whose content
     * is requested, to which the (further reduced) request is then delegated.
     */
    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        final String key;
        switch (key = Manager.consume(selectors, "page")) {
            case "resource":
                result = resource(request);
                break;
            default: {
                // content of the specified proxy
                final ConsoleProxy proxy = proxies.get(key);
                if (proxy != null) {
                    result = proxy.process(request, response, selectors);
                }
            }
            break;
        }
        return result;
    }

    /**
     * Routes a POST request to the proxy identified by the leading selector (see
     * {@link ConsoleProxy#key()}), e.g. for a proxy's own form submissions.
     */
    public @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        final ConsoleProxy proxy = proxies.get(Manager.consume(selectors));
        if (proxy != null) {
            result = proxy.process(request, response, selectors);
        }
        return result;
    }

    /**
     * The templates this console can render; currently just the shared {@code "page"} chrome that
     * every {@link ConsoleProxy}'s {@code processGet} renders its content into.
     */
    public final Map<String, Factory> templates = Map.of(
            "page", current -> new Template("/com/composum/aem/console/page.html",
                    new TemplateContext(current, new Values()
                            .with(toolsValues())
                    ), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    /**
     * Builds a link back to this console's {@code "resource"} route (see {@link #processGet}), used
     * e.g. by {@code AbstractConsoleProxy#rewriteResourceLink} to keep a proxied plugin's static
     * resources (CSS/JS) loading through this console instead of the original console's own path.
     */
    @Override
    public @NotNull String pluginLink(@NotNull String path) {
        return manager.serverPath() + ".console.resource.html" + path;
    }
}
