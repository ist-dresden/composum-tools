package com.composum.sling.dashboard.impl;

import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
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
import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;

@Component(service = {ToolsPlugin.class, Dashboard.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = Dashboard.Config.class)
public class Dashboard extends AbstractToolsPlugin {

    public static final String KEY = "dashboard";

    protected final List<Widget> WIDGETS = List.of(
            new Page(KEY, "Dashboard", this::getDashboardLink)
    );

    @ObjectClassDefinition(name = "Composum Tools Dashboard")
    public @interface Config {

        @AttributeDefinition()
        String key() default Dashboard.KEY;

        @AttributeDefinition()
        String label() default "Dashboard";

        @AttributeDefinition()
        int rank() default 5000;

        @AttributeDefinition()
        boolean enabled() default true;
    }

    @Reference
    protected Manager manager;

    protected BundleContext bundleContext;
    protected Config config;

    protected @NotNull Manager manager() {
        return manager;
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
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
        return Optional.ofNullable(config).map(Config::label).orElse("Dashboard");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(5000);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    public @NotNull String getDashboardLink() {
        return manager.serverPath() + ".dashboard.html";
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return WIDGETS;
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
            default:
                break;
        }
        return result;
    }

    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        Result<?> result = new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        switch (Manager.consume(selectors, "page")) {
            case "resource":
                result = resource(request);
                break;
            case "tree":
                // tree node data
                break;
            case "view":
                // content of the current browser view
                break;
            default: {
                // the content of the dashboard page (tiles)
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                ), "page"));
                if (content != null) {
                    result = new Result<>(content, HTML_TYPE);
                }
            }
            break;
        }
        return result;
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/sling/dashboard/page.html",
                    new TemplateContext(current, new Values()
                            .with("page", new Values()
                                    .with("link", dashboardLink())
                                    .with("label", "Dashboard")
                                    .with("title", "Composum Dashboard"))
                            .with("html.cssClasses", (Supplier<?>) () -> getHtmlCssClasses("dashboard-page"))
                            .with(toolsValues())
                    ), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    protected @NotNull String dashboardLink() {
        return manager.serverPath() + ".dashboard.html";
    }

    @Override
    public @NotNull String pluginLink(@NotNull String path) {
        return (path.matches("^(/com/composum)?/(lib|sling|aem)(/.*)?$"))
                ? manager.serverPath() + ".dashboard.resource.html" + path
                : manager.serverPath() + ".dashboard.html" + path;
    }
}
