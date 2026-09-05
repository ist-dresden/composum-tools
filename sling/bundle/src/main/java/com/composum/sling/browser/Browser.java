package com.composum.sling.browser;

import com.composum.sling.browser.impl.RelatedPaths;
import com.composum.sling.browser.impl.TreeNode;
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
import org.osgi.service.component.annotations.ReferencePolicy;
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

/**
 * The JCR/Sling resource browser: a tree view plus a pluggable set of {@link Tool}s (e.g.
 * favorites, query) and {@link View}s (e.g. properties, JSON/XML dump) for the selected resource,
 * with a pluggable {@link Actions} set (e.g. edit/activate on AEM) for that resource.
 */
@Component(service = {ToolsPlugin.class, Browser.class}, immediate = true)
@Designate(ocd = Browser.Config.class)
public class Browser extends AbstractToolsPlugin {

    /** this plugin's default selector key */
    public static final String KEY = "browser";
    /** this plugin's default navigation label */
    public static final String LABEL = "Browser";
    /** this plugin's default navigation rank */
    public static final int RANK = 9000;

    /**
     * Default constructor.
     */
    public Browser() {
    }

    /** the single dashboard widget contributed by this plugin */
    protected final List<Widget> PLUGIN_WIDGETS = List.of(
            new Page(KEY, "Browser", 9000, this::browserLink)
    );

    /**
     * OSGi metatype configuration for the browser's key/label/rank, its favorites/query templates,
     * exposed CA configurations, and which tools/views are enabled.
     */
    @ObjectClassDefinition(name = "Composum Browser")
    public @interface Config {

        /**
         * @return this plugin's selector key
         */
        @AttributeDefinition()
        String key() default Browser.KEY;

        /**
         * @return this plugin's navigation label
         */
        @AttributeDefinition()
        String label() default Browser.LABEL;

        /**
         * @return this plugin's navigation rank
         */
        @AttributeDefinition()
        int rank() default Browser.RANK;

        /**
         * @return whether this plugin is enabled
         */
        @AttributeDefinition()
        boolean enabled() default true;

        /**
         * @return the favorites tool's quick-access root paths, as 'label=regex' pairs
         */
        @AttributeDefinition()
        String[] favorites() default {
                "ALL=^.*$",
                "Content=^/content(/.*)?$",
                "Config=^/(conf|etc)(/.*)?$",
                "Apps=^/(apps|libs|mnt)(/.*)?$",
                "Data=^/(var|tmp)(/.*)?$",
                "History=@history"
        };

        /**
         * @return the query tool's query templates
         */
        @AttributeDefinition()
        String[] queryTemplates() default {
                "[nt:file]${path} ${1}",
                "${path}//*[jcr:contains(.,'${1}')]",
                "${path}//*[jcr:like(@sling:resourceType, '%${1}%')]",
                "SELECT * FROM [nt:base] AS x WHERE ISDESCENDANTNODE(x, '${path}') AND x.[sling:resourceType] LIKE '%${1}%'"
        };

        /**
         * @return the query tool's CSV export column definitions
         */
        @AttributeDefinition()
        String[] queryCsvProperties() default {
                "path",
                "name=jcr:title|title|label|name",
                "text=jcr:description|description|text|content",
                "resource type=sling:resourceType|jcr:mimeType",
                "primary type=jcr:primaryType",
                "last modified=jcr:lastModified"
        };

        /**
         * @return the CA-configuration types exposed by the 'cac' view
         */
        @AttributeDefinition(name = "CA-Configurations",
                description = "A set of templates matching: 'caconfig-type[config-properties,...]' if only some properties should be shown, " +
                        "or 'caconfig-type' if all properties should be shown. caconfig-type is the fully qualified class name of the configuration type.")
        String[] caConfigurations();

        /**
         * @return the enabled tool keys, or empty to enable all implemented and active tools
         */
        @AttributeDefinition()
        String[] tools() default {
        /*      "favorites",
                "query"     // if empty, all implemented and active views are enabled   */
        };

        /**
         * @return the enabled view keys, or empty to enable all implemented and active views
         */
        @AttributeDefinition()
        String[] views() default {
        /*      "properties",
                "display",
                "cac",
                "json",
                "xml"       // if empty, all implemented and active views are enabled   */
        };
    }

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    /** the enabled, registered {@link Tool} implementations */
    protected PluginSet<Tool> tools = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull final Tool service) {
            return service.isEnabled() && (enabledTools.isEmpty() || enabledTools.contains(service.key()));
        }
    };

    /** the enabled, registered {@link View} implementations */
    protected PluginSet<View> views = new PluginSet<>() {
        @Override
        protected boolean isEnabled(@NotNull final View service) {
            return service.isEnabled() && (enabledViews.isEmpty() || enabledViews.contains(service.key()));
        }
    };

    /** the currently registered {@link Actions} implementation, if any */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    protected volatile @Nullable Actions actions;

    /** the bundle context this plugin was activated with */
    protected BundleContext bundleContext;
    /** the current OSGi configuration */
    protected Config config;

    /** the configured enabled tool keys (see {@link Config#tools()}) */
    protected transient List<String> enabledTools;
    /** the configured enabled view keys (see {@link Config#views()}) */
    protected transient List<String> enabledViews;
    /** the configured favorites rules (see {@link Config#favorites()}) */
    protected transient List<String> favoriteRules;
    /** the configured query templates (see {@link Config#queryTemplates()}) */
    protected transient List<String> queryTemplates;
    /** the configured CA-configuration rules (see {@link Config#caConfigurations()}) */
    protected transient List<String> caConfigRules;
    /** the configured query CSV export column definitions (see {@link Config#queryCsvProperties()}) */
    protected transient List<String> queryCsvProperties;

    /**
     * The configured favorites rules.
     *
     * @return the configured favorites rules
     */
    public @NotNull List<String> favoriteRules() {
        return favoriteRules;
    }

    /**
     * The configured query templates.
     *
     * @return the configured query templates
     */
    public @NotNull List<String> queryTemplates() {
        return queryTemplates;
    }

    /**
     * The configured CA-configuration rules.
     *
     * @return the configured CA-configuration rules
     */
    public @NotNull List<String> caConfigurationRules() {
        return caConfigRules;
    }

    /**
     * The configured query CSV export column definitions.
     *
     * @return the configured query CSV export column definitions
     */
    public @NotNull List<String> queryCsvProperties() {
        return queryCsvProperties;
    }

    /**
     * @param bundleContext the bundle context of this component
     * @param config        the current OSGi configuration
     */
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
     * The enabled, registered {@link Tool} implementations.
     *
     * @return the enabled, registered {@link Tool} implementations
     */
    public @NotNull PluginSet<Tool> tools() {
        return tools;
    }

    /**
     * The enabled, registered {@link View} implementations.
     *
     * @return the enabled, registered {@link View} implementations
     */
    public @NotNull PluginSet<View> views() {
        return views;
    }

    /**
     * This browser's own base link.
     *
     * @return this browser's own base link
     */
    public @NotNull String browserLink() {
        return manager.serverPath() + ".browser.html";
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return PLUGIN_WIDGETS;
    }

    /**
     * The client-side stylesheet resource paths needed by the currently enabled tools/views/actions.
     *
     * @return the client-side stylesheet resource paths needed by the currently enabled tools/views/actions
     */
    public @NotNull Collection<String> styles() {
        final Set<String> styles = new LinkedHashSet<>();
        for (View view : views().list()) {
            styles.addAll(view.styles());
        }
        for (Tool tool : tools().list()) {
            styles.addAll(tool.styles());
        }
        Optional.ofNullable(actions).ifPresent(actions -> styles.addAll(actions.styles()));
        return styles;
    }

    /**
     * The client-side script resource paths needed by the currently enabled tools/views/actions.
     *
     * @return the client-side script resource paths needed by the currently enabled tools/views/actions
     */
    public @NotNull Collection<String> scripts() {
        final Set<String> scripts = new LinkedHashSet<>();
        for (View view : views().list()) {
            scripts.addAll(view.scripts());
        }
        for (Tool tool : tools().list()) {
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

    /**
     * Routes a GET request by its leading selector ('resource', 'actions', 'action', 'tree',
     * 'tool', 'view', or the browser page itself by default).
     *
     * @param request   the current request
     * @param response  the current response
     * @param selectors the request selectors remaining after routing
     * @return the routed result, or 'Bad Request' if no matching route is found
     */
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
            case "related": {
                // related links for the current path
                if (targetResource != null) {
                    final RelatedPaths relatedPaths = new RelatedPaths(manager, targetResource);
                    final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                            .with("browser.related", new Values()
                                    .with("paths", (Supplier<?>) relatedPaths::getRelatedPathSet)
                                    .with("types", (Supplier<?>) relatedPaths::getSupertypeChain))
                    ), "related"));
                    if (content != null) {
                        result = new Result<>(content, HTML_TYPE);
                    }
                }
            }
            break;
            case "actions": {
                // actions rendering for the current resource
                if (actions != null) {
                    final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                            .with("browser.actions", (Supplier<?>) () -> actions != null ? actions.set(request).values() : null)
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
                                .with(properties) : null)
                        .with("browser.related", manager.serverPath() + ".browser.related.html");
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

    /** the templates this plugin can render: the browser page itself, and its actions bar */
    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/sling/browser/page.html",
                    new TemplateContext(current, new Values()
                            .with("page", new Values()
                                    .with("key", key())
                                    .with("link", browserLink())
                                    .with("label", label())
                                    .with("title", "Composum Browser"))
                            .with("browser", new Values()
                                    .with("uri", browserLink())
                                    .with("tree", manager.serverPath() + ".browser.tree.json")
                                    .with("tabView", manager.serverPath() + ".browser.view.#id#.html")
                                    .with("tabForm", manager.serverPath() + ".browser.view.#id#.form.html")
                                    .with("tools", (Supplier<?>) () -> valuesOf(tools().list()))
                                    .with("views", (Supplier<?>) () -> valuesOf(views().list()))
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
            "related", current -> new Template("/sling/browser/navbar/related.html",
                    new TemplateContext(current, new Values()), this),
            "actions", current -> new Template("/sling/browser/navbar/actions.html",
                    new TemplateContext(current, new Values()), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }
}
