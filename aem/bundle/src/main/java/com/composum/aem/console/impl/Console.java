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

    /** this plugin's default selector key */
    public static final String KEY = "console";
    /** this plugin's default navigation label */
    public static final String LABEL = "Console";
    /** this plugin's default navigation rank */
    public static final int RANK = 1000;

    /**
     * Default constructor.
     */
    public Console() {
    }

    /**
     * OSGi metatype configuration for this console's key/label/rank, whether it is enabled, and
     * which registered {@link ConsoleProxy} views to show.
     */
    @ObjectClassDefinition(name = "Composum Tools Console")
    public @interface Config {

        /**
         * @return this plugin's selector key
         */
        @AttributeDefinition()
        String key() default Console.KEY;

        /**
         * @return this plugin's navigation label
         */
        @AttributeDefinition()
        String label() default Console.LABEL;

        /**
         * @return this plugin's navigation rank
         */
        @AttributeDefinition()
        int rank() default Console.RANK;

        /**
         * @return whether this plugin is enabled
         */
        @AttributeDefinition()
        boolean enabled() default true;

        /**
         * @return the enabled {@link ConsoleProxy} keys, or empty to enable all implemented and active ones
         */
        @AttributeDefinition()
        String[] views() default {
        /*      "requests",
                "resolver",
                "servlets"      // if empty, all implemented and active views (proxies) are enabled   */
        };
    }

    /** the enabled, registered {@link ConsoleProxy} implementations */
    protected PluginSet<ConsoleProxy> proxies = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull ConsoleProxy service) {
            return service.isEnabled();
        }
    };

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    /** the bundle context this plugin was activated with */
    protected BundleContext bundleContext;
    /** the current OSGi configuration */
    protected Config config;

    /** the configured enabled {@link ConsoleProxy} keys (see {@link Config#views()}) */
    protected transient List<String> views;

    /**
     * @param bundleContext the bundle context of this component
     * @param config        the current OSGi configuration
     */
    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        views = Common.listOf(config.views());
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
        return config.enabled();
    }

    /**
     * The enabled, registered {@link ConsoleProxy} implementations.
     *
     * @return the enabled, registered {@link ConsoleProxy} implementations
     */
    public @NotNull PluginSet<ConsoleProxy> proxies() {
        return proxies;
    }

    @Override
    public @NotNull List<Widget> widgets() {
        List<Widget> widgets = new ArrayList<>();
        for (ConsoleProxy proxy : proxies.list()) {
            final String key = proxy.key();
            widgets.add(new Page(key, proxy.label(), proxy.rank(), () -> proxyLink(proxy), widgets.isEmpty()));
        }
        return widgets;
    }

    /**
     * The link to the given proxy's own page.
     *
     * @param proxy the proxy to build a link for
     * @return the link to the given proxy's own page
     */
    protected @NotNull String proxyLink(@NotNull final ConsoleProxy proxy) {
        return manager.serverPath() + ".console." + proxy.key() + ".html";
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
     *
     * @param request   the current request
     * @param response  the current response
     * @param selectors the request selectors remaining after routing
     * @return the routed result, or a 'Bad Request' result if no matching proxy is found
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
     *
     * @param request   the current request
     * @param response  the current response
     * @param selectors the request selectors remaining after routing
     * @return the routed result, or a 'Bad Request' result if no matching proxy is found
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
}
