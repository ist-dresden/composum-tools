package com.composum.sling.browser;

import com.composum.sling.browser.dto.TreeNode;
import com.composum.sling.browser.tool.Favorites;
import com.composum.sling.browser.tool.Query;
import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.PluginSet;
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
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.http.HttpServletResponse;
import java.io.Reader;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static com.composum.sling.tools.Common.JCR_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Component(service = {ToolsPlugin.class, Browser.class}, immediate = true)
@Designate(ocd = Browser.Config.class)
public class Browser extends AbstractToolsPlugin {

    public static final String KEY = "browser";

    protected final List<Widget> PLUGIN_WIDGETS = List.of(
            new Page(KEY, "Browser", 9000, this::browserLink)
    );

    @ObjectClassDefinition(name = "Composum Browser")
    public @interface Config {

        @AttributeDefinition()
        String key() default Browser.KEY;

        @AttributeDefinition()
        String label() default "Browser";

        @AttributeDefinition()
        int rank() default 9000;

        @AttributeDefinition()
        boolean enabled() default true;

        @AttributeDefinition()
        String[] favorites() default {
                "ALL=^.*$",
                "Content=^/content(/.*)?$",
                "Config=^/(conf|etc)(/.*)?$",
                "Apps=^/(apps|libs|mnt)(/.*)?$",
                "Data=^/(var)(/.*)?$",
                "History=@history"
        };

        @AttributeDefinition()
        String[] queryTemplates() default {
                "[nt:file]${path} ${1}",
                "${path}//*[jcr:contains(.,'${1}')]",
                "${path}//*[jcr:like(@sling:resourceType, '%${1}%')]",
                "SELECT * FROM [nt:base] AS x WHERE ISDESCENDANTNODE(x, '${path}') AND x.[sling:resourceType] LIKE '%${1}%'"
        };

        @AttributeDefinition()
        String[] queryCsvProperties() default {
                "path",
                "name=jcr:title|title|label|name",
                "text=jcr:description|description|text|content",
                "resource type=sling:resourceType|jcr:mimeType",
                "primary type=jcr:primaryType",
                "last modified=jcr:lastModified"
        };

        @AttributeDefinition(name = "CA-Configurations",
                description = "A set of templates matching: 'caconfig-type[config-properties,...]' if only some properties should be shown, " +
                        "or 'caconfig-type' if all properties should be shown. caconfig-type is the fully qualified class name of the configuration type.")
        String[] caConfigurations();

        @AttributeDefinition()
        String[] tools() default {
        /*      "favorites",
                "query"     // if empty, all implemented and active views are enabled   */
        };

        @AttributeDefinition()
        String[] views() default {
        /*      "properties",
                "display",
                "cac",
                "json",
                "xml"       // if empty, all implemented and active views are enabled   */
        };
    }

    @Reference
    protected Manager manager;

    protected PluginSet<Tool> tools = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull final Tool service) {
            return service.isEnabled() && (enabledTools.isEmpty() || enabledTools.contains(service.key()));
        }
    };

    protected PluginSet<View> views = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull final View service) {
            return service.isEnabled() && (enabledViews.isEmpty() || enabledViews.contains(service.key()));
        }
    };

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    protected volatile @Nullable Actions actions;

    protected BundleContext bundleContext;
    protected Config config;

    protected transient List<String> enabledTools;
    protected transient List<String> enabledViews;
    protected transient List<String> favoriteRules;
    protected transient List<String> queryTemplates;
    protected transient List<String> caConfigRules;
    protected transient List<String> queryCsvProperties;

    public @NotNull List<String> favoriteRules() {
        return favoriteRules;
    }

    public @NotNull List<String> queryTemplates() {
        return queryTemplates;
    }

    public @NotNull List<String> caConfigurationRules() {
        return caConfigRules;
    }

    public @NotNull List<String> queryCsvProperties() {
        return queryCsvProperties;
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        this.config = config;
        enabledTools = Common.listOf(config.tools());
        enabledViews = Common.listOf(config.views());
        favoriteRules = Common.listOf(config.favorites());
        queryTemplates = Common.listOf(config.queryTemplates());
        caConfigRules = Common.listOf(config.caConfigurations());
        queryCsvProperties = Common.listOf(config.queryCsvProperties());
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
        return Optional.ofNullable(config).map(Config::label).orElse("Browser");
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(9000);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    @NotNull
    public Manager manager() {
        return manager;
    }

    public @NotNull PluginSet<Tool> tools() {
        return tools;
    }

    public @NotNull PluginSet<View> views() {
        return views;
    }

    public @NotNull String browserLink() {
        return manager.serverPath() + ".browser.html";
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return PLUGIN_WIDGETS;
    }

    public @NotNull Collection<String> styles() {
        final Set<String> styles = new LinkedHashSet<>();
        for (View view : views().set()) {
            styles.addAll(view.styles());
        }
        for (Tool tool : tools().set()) {
            styles.addAll(tool.styles());
        }
        Optional.ofNullable(actions).ifPresent(actions -> styles.addAll(actions.styles()));
        return styles;
    }

    public @NotNull Collection<String> scripts() {
        final Set<String> scripts = new LinkedHashSet<>();
        for (View view : views().set()) {
            scripts.addAll(view.scripts());
        }
        for (Tool tool : tools().set()) {
            scripts.addAll(tool.scripts());
        }
        Optional.ofNullable(actions).ifPresent(actions -> scripts.addAll(actions.scripts()));
        return scripts;
    }

    @Override
    protected void collectHtmlCssClasses(@NotNull final Set<String> cssClasses) {
        //if (isFavoritesSupported()) {
        cssClasses.add("browser-favorites");
        //}
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
        final ResourceResolver resolver = request.getResourceResolver();
        final RequestPathInfo pathInfo = request.getRequestPathInfo();
        final String targetPath = Optional.ofNullable(pathInfo.getSuffix()).orElse("/");
        final Resource targetResource = manager.requestResource(request);
        switch (Manager.consume(selectors, "")) {
            case "resource":
                result = resource(request);
                break;
            case "actions": {
                // actions rendering for the current resource
                if (actions != null) {
                    final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                            .with("browser.actions", (Supplier<?>) () -> actions.set(request).values())
                    ), "actions"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                }
            }
            break;
            case "action": {
                // action handler for such action implementations
                if (targetResource != null) {
                    final Actions.Action action = actions != null
                            ? actions.set(request).get(Manager.consume(selectors, ""))
                            : null;
                    if (action != null) {
                        result = action.process(request, response, selectors);
                    }
                }
            }
            break;
            case "tree": {
                // tree node data
                if (targetResource != null) {
                    result = new Result<>(new TreeNode(manager, targetResource, null));
                } else {
                    result = new Result<>(SC_NOT_FOUND, new TreeNode(targetPath));
                }
            }
            break;
            case "tool": {
                // content of the referenced tool
                final Tool tool = tools().get(Manager.consume(selectors));
                if (tool != null) {
                    result = tool.process(request, response, selectors);
                }
            }
            break;
            case "view": {
                // content of the referenced browser view
                final View view = views().get(Manager.consume(selectors));
                if (view != null) {
                    result = view.process(request, response, selectors);
                }
            }
            break;
            default: {
                final Resource contenResource = Optional.ofNullable(targetResource)
                        .map(r -> r.getChild(JCR_CONTENT)).orElse(null);
                final Map<String, Object> properties = Optional
                        .ofNullable(Optional.ofNullable(contenResource).orElse(targetResource))
                        .filter(r -> manager.isAllowedResource(r))
                        .map(this::resourceProperties).orElse(null);
                // the content of the browser page (tree + view set)
                final Values values = new Values()
                        .with("target.path", targetPath)
                        .with("target.properties", properties != null ? new Values()
                                .with(properties) : null);
                Optional.ofNullable(actions).ifPresent(actions -> {
                    values.with("browser.actions", manager.serverPath() + ".browser.actions.html");
                });
                final Reader content = templateReader(getTemplate(new TemplateContext(values), "page"));
                if (content != null) {
                    result = new Result<>(content, HTML_TYPE);
                }
            }
            break;
        }
        return result;
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/sling/browser/page.html",
                    new TemplateContext(current, new Values()
                            .with("page", new Values()
                                    .with("link", browserLink())
                                    .with("label", "Browser")
                                    .with("title", "Composum Browser"))
                            .with("browser", new Values()
                                    .with("uri", browserLink())
                                    .with("tree", manager.serverPath() + ".browser.tree.json")
                                    .with("tabView", manager.serverPath() + ".browser.view.#id#.html")
                                    .with("tabForm", manager.serverPath() + ".browser.view.#id#.form.html")
                                    .with("tools", (Supplier<?>) () -> valuesOf(tools().set()))
                                    .with("views", (Supplier<?>) () -> valuesOf(views().set()))
                                    .with("styles", (Supplier<?>) this::styles)
                                    .with("scripts", (Supplier<?>) this::scripts))
                            .with("options", new Values()
                                    .with("favorites", tools().get(Favorites.KEY) != null)
                                    .with("query", tools().get(Query.KEY) != null))
                            .with("html.cssClasses", (Supplier<?>) () -> getHtmlCssClasses("browser-page"))
                            .with(toolsValues())
                            .with("tools.navbar.center", "/sling/browser/navbar/center.html")
                            .with("tools.navbar.right", "/sling/browser/navbar/right.html")
                    ), this),
            "actions", current -> new Template("/sling/browser/navbar/actions.html",
                    new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }

    @Override
    public @NotNull String pluginLink(@NotNull String path) {
        return (path.matches("^(/com/composum)?/(lib|sling|aem)(/.*)?$"))
                ? manager.serverPath() + ".browser.resource.html" + path
                : manager.serverPath() + ".browser.html" + path;
    }
}
