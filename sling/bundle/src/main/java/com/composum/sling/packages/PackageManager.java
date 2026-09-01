package com.composum.sling.packages;

import com.composum.sling.packages.jcr.JcrPackageInfo;
import com.composum.sling.packages.jcr.JcrPackageOperations;
import com.composum.sling.packages.jcr.JcrPackageTree;
import com.composum.sling.packages.registry.RegistryOperations;
import com.composum.sling.packages.registry.RegistryTree;
import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Tile;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.packaging.JcrPackage;
import org.apache.jackrabbit.vault.packaging.JcrPackageDefinition;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.Packaging;
import org.apache.jackrabbit.vault.packaging.registry.PackageRegistry;
import org.apache.jackrabbit.vault.packaging.registry.RegisteredPackage;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.request.RequestPathInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * The Package Manager: browses, inspects, creates, uploads, edits, installs, uninstalls, assembles,
 * downloads and deletes the content packages of the classic JCR-node-backed {@link JcrPackageManager}
 * (FileVault's '/etc/packages' store) - see {@link com.composum.sling.packages.jcr.JcrPackageOperations}.
 * A second backend merges every bound FileVault {@code PackageRegistry} SPI service into one tree -
 * read/install/uninstall/remove only, no create/upload/edit/filters/build there, see
 * {@link com.composum.sling.packages.registry.RegistryOperations}. Which backend a request targets
 * is a single {@code ?mode=jcr|registry} request parameter (default 'jcr') - every mode-agnostic
 * route (tree/view/download/install/uninstall/delete) is a single URL that branches on it
 * internally via {@link #isRegistryMode}, so the client never needs two different URLs for the
 * same action; only the JCR-only capabilities (create/upload/edit/filters/build/coverage) have no
 * registry counterpart at all. Install/uninstall/assemble run synchronously on the request thread -
 * there is deliberately no async job queue and no persisted audit trail or install history, the
 * operation's log is only ever returned in the HTTP response of the request that triggered it. A
 * CRX Package Manager compatibility endpoint ({@code POST .service.html}, 'cmd=ls|rm|build|uninst'
 * or upload+install) lets Maven deployment tooling that still speaks '/crx/packmgr/service.jsp'
 * target this instead.
 */
@Component(service = {ToolsPlugin.class, PackageManager.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = PackageManager.Config.class)
public class PackageManager extends AbstractToolsPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(PackageManager.class);

    public static final String KEY = "packages";
    public static final String LABEL = "Packages";
    public static final int RANK = 4000;

    private static final String DIALOGS_ROOT = "/sling/packages/dialogs/";
    private static final String MODE_REGISTRY = "registry";

    @ObjectClassDefinition(name = "Composum Package Manager")
    public @interface Config {

        @AttributeDefinition()
        String key() default PackageManager.KEY;

        @AttributeDefinition()
        String label() default PackageManager.LABEL;

        @AttributeDefinition()
        int rank() default PackageManager.RANK;

        @AttributeDefinition()
        boolean enabled() default true;

        @AttributeDefinition(name = "Show Packages Tile",
                description = "whether Package Managers dashboard tile should be shown in the Tools Dashboard")
        boolean showTile() default true;

        @AttributeDefinition(name = "Write Enabled",
                description = "whether mutating operations (create/upload/update/install/uninstall/assemble/delete) are allowed")
        boolean writeEnabled() default true;
    }

    @Reference
    protected Manager manager;

    @Reference
    protected Packaging packaging;

    // the registry backend is optional: 0..n PackageRegistry services may be bound (or none, e.g.
    // on a plain Sling instance without FileVault's registry bundle) - registry mode then simply
    // shows an empty tree instead of gating the whole plugin's activation, unlike the mandatory
    // 'packaging' reference above
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    protected volatile List<PackageRegistry> packageRegistries;

    protected Config config;
    protected JcrPackageOperations jcrOperations;

    protected @NotNull RegistryOperations registryOperations() {
        return new RegistryOperations(packageRegistries);
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.config = config;
        this.jcrOperations = new JcrPackageOperations(packaging);
        manager.plugins().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        manager.plugins().detach(this);
    }

    protected @NotNull Manager manager() {
        return manager;
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

    public @NotNull String pageLink() {
        return manager.serverPath() + "." + key() + ".html";
    }

    protected @NotNull String actionLink(@NotNull final String action) {
        return manager.serverPath() + "." + key() + "." + action + ".json";
    }

    /**
     * Whether the given request targets the registry backend ({@code ?mode=registry}); every
     * other value (including no 'mode' parameter at all) means the JCR backend.
     */
    protected boolean isRegistryMode(@NotNull final SlingHttpServletRequest request) {
        return MODE_REGISTRY.equals(request.getParameter("mode"));
    }

    /**
     * Whether the given checkbox parameter is checked - checked, and not just present, since the
     * standard checkbox/hidden-field pattern (an accompanying hidden 'false' fallback so an
     * unchecked box still submits a value) submits the field twice when checked; unlike
     * {@link SlingHttpServletRequest#getParameter}, which only ever sees the first of multiple
     * values for the same name (here the hidden 'false', regardless of the checkbox's own state),
     * this checks every submitted value.
     */
    protected boolean isChecked(@NotNull final SlingHttpServletRequest request, @NotNull final String name) {
        final String[] values = request.getParameterValues(name);
        if (values != null) {
            for (final String value : values) {
                if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The '?mode=registry' query suffix to append to a mode-agnostic URL built for the given
     * package, or an empty string for a JCR-backed one - so the client never has to know which
     * backend a given path belongs to, it just carries the mode along.
     */
    protected @NotNull String modeQuery(@NotNull final PackageInfo info) {
        return info instanceof JcrPackageInfo ? "" : "?mode=" + MODE_REGISTRY;
    }

    @Override
    public @NotNull List<Widget> widgets() {
        List<Widget> widgets = new ArrayList<>();
        widgets.add(new Page(key(), label(), rank(), this::pageLink));
        if (config.showTile()) {
            widgets.add(new Tile(key(), label(), rank()));
        }
        return widgets;
    }

    @Override
    public @Nullable String widgetViewLink(@NotNull final SlingHttpServletRequest request,
                                           @NotNull final SlingHttpServletResponse response,
                                           @NotNull final String widgetKey) {
        return pageLink();
    }

    @Override
    public @Nullable String widgetViewTarget(@NotNull final SlingHttpServletRequest request,
                                             @NotNull final SlingHttpServletResponse response,
                                             @NotNull final String widgetKey) {
        return "_self";
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        switch (request.getMethod()) {
            case "GET":
                return processGet(request, response, selectors);
            case "POST":
                return processPost(request, response, selectors);
            default:
                return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    protected @Nullable Session session(@NotNull final SlingHttpServletRequest request) {
        return request.getResourceResolver().adaptTo(Session.class);
    }

    protected @NotNull String targetPath(@NotNull final SlingHttpServletRequest request) {
        final RequestPathInfo pathInfo = request.getRequestPathInfo();
        return Optional.ofNullable(pathInfo.getSuffix()).filter(p -> !p.isEmpty()).orElse("/");
    }

    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        switch (Manager.consume(selectors, "")) {
            case "resource":
                return resource(request);
            case "tree":
                return treeNode(request);
            case "ancestors":
                return ancestorsOf(request);
            case "view":
                return viewPackage(request);
            case "download":
                return downloadPackage(request);
            case "dialog":
                return dialog(request, Manager.consume(selectors, ""));
            case "tile":
                return renderTile(request);
            default: {
                final boolean registryMode = isRegistryMode(request);
                final String mode = registryMode ? MODE_REGISTRY : "jcr";
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                        .with("packages.modeIsJcr", !registryMode)
                        .with("packages.mode", mode)
                        .with("packages.modeLink", pageLink() + (registryMode ? "" : "?mode=" + MODE_REGISTRY))
                        .with("packages.createEnabled", !registryMode && config.writeEnabled())
                        // pre-selects this path in the tree on initial load, see PackagesTree
                        .with("packages.path", targetPath(request))
                ), "page"));
                return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
            }
        }
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/sling/packages/page.html",
                    new TemplateContext(current, new Values()
                            .with("page", new Values()
                                    .with("key", key())
                                    .with("link", pageLink())
                                    .with("label", label())
                                    .with("title", "Composum Package Manager"))
                            .with("packages", new Values()
                                    .with("uri", pageLink())
                                    .with("tree", manager.serverPath() + "." + key() + ".tree.json")
                                    .with("ancestors", manager.serverPath() + "." + key() + ".ancestors.json")
                                    .with("view", manager.serverPath() + "." + key() + ".view.json")
                                    .with("dialog", manager.serverPath() + "." + key() + ".dialog.")
                                    .with("writeEnabled", config.writeEnabled()))
                            .with("html.cssClasses", (Supplier<?>) () -> getHtmlCssClasses("packages-page"))
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
     * Opens the package addressed by the request's suffix path in whichever backend
     * {@link #isRegistryMode} selects, and maps it to the shared {@link PackageInfo} view.
     *
     * @return the package's info, or 'null' if no such package exists in the selected backend
     */
    protected @Nullable PackageInfo packageInfo(@NotNull final SlingHttpServletRequest request) {
        try {
            if (isRegistryMode(request)) {
                try (RegisteredPackage pkg = openRegistryPackage(request)) {
                    return pkg != null ? registryOperations().info(pkg) : null;
                }
            } else {
                final JcrPackage pkg = openPackage(request);
                return pkg != null ? jcrOperations.info(jcrOperations.packageManager(session(request)), pkg) : null;
            }
        } catch (IOException | RepositoryException ex) {
            return null;
        }
    }

    /**
     * The detail view for the request's suffix path: for a package (leaf), the usual property
     * table + action bar; for an intermediate (group/name) node, a navigable list of every
     * package nested under it instead, with its own action bar (currently just Purge Old
     * Versions) - see {@link #actions} / {@link #viewFolder}.
     */
    protected @NotNull Result<?> viewPackage(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        if ("/".equals(path)) {
            return new Result<>(SC_NOT_FOUND);
        }
        // a package (leaf) path is resolved directly against the repository/registry, independent
        // of the tree structure - a leaf's tree-node key is its whole path, not a path segment, so
        // it can't be found by splitting the path the way a folder path can (see JcrPackageTree/
        // RegistryTree#findNode); only if that fails do we treat the path as an intermediate node
        final PackageInfo info = packageInfo(request);
        if (info != null) {
            final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                    .with("packages.actions", (Supplier<?>) () -> actions(info))
                    .with("packages.info", (Supplier<?>) () -> valuesOf(info))
                    .with("packages.downloadUri", (Supplier<?>) () -> downloadUri(info))
                    .with("packages.downloadLabel", (Supplier<?>) () -> StringUtils.substringAfterLast(info.getPath(), "/"))
                    .with("packages.isModified", (Supplier<?>) () -> info.getLastModified() != null && info.getLastModified().after(info.getLastUnpacked()))
            ), "/sling/packages/details/content.html"));
            return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
        }
        try {
            final List<PackageListEntry> leaves;
            if (isRegistryMode(request)) {
                leaves = new RegistryTree(registryOperations().packages()).leavesUnder(path);
            } else {
                final Session session = session(request);
                final JcrPackageManager manager = session != null ? jcrOperations.packageManager(session) : null;
                leaves = manager != null ? new JcrPackageTree(manager).leavesUnder(path) : List.of();
            }
            return leaves.isEmpty() ? new Result<>(SC_NOT_FOUND) : viewFolder(leaves);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> viewFolder(@NotNull final List<PackageListEntry> entries) {
        final List<Values> actions = config.writeEnabled()
                ? List.of(action("purge", "trash", "Purge Old Versions"))
                : List.of();
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                .with("packages.actions", actions)
                .with("packages.entries", entries)
        ), "/sling/packages/details/folder.html"));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    public @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull List<String> selectors) {
        final String action = Manager.consume(selectors, "");
        // the CRX Package Manager compatibility endpoint speaks its own wire protocol (always
        // HTTP 200, business status embedded in the XML body) and gates its own sub-commands
        // individually ('ls' is read-only) - handled before the generic writeEnabled gate below
        if ("service".equals(action)) {
            return servicePackage(request);
        }
        if (!config.writeEnabled()) {
            return errorResult(SC_FORBIDDEN, "Write operations are disabled.");
        }
        switch (action) {
            case "create":
                return createPackage(request);
            case "upload":
                return uploadPackage(request);
            case "update":
                return updatePackage(request);
            case "install":
                return installPackage(request);
            case "uninstall":
                return uninstallPackage(request);
            case "assemble":
                return assemblePackage(request);
            case "delete":
                return deletePackage(request);
            case "purge":
                return purgePackages(request);
            case "filters":
                return filtersPackage(request);
            default:
                return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    // Detail-panel action bar (rendered server-side, see details/toolbar.html + details/action*.html) -
    // a plain button (or, for 'download', a link) per action, grouped in Bootstrap button-groups.
    // The registry backend has no create/upload/edit/filters/build/coverage counterpart, so those
    // are simply omitted for a registry-backed package (info not a JcrPackageInfo).

    protected @NotNull Values action(@NotNull final String key, @NotNull final String icon, @NotNull final String label) {
        return new Values().with("key", key).with("icon", icon).with("label", label);
    }

    protected @NotNull Values actionGroup(@NotNull final Values... groupedActions) {
        return new Values().with("group", true).with("actions", List.of(groupedActions));
    }

    protected @NotNull List<Values> actions(@NotNull final PackageInfo info) {
        final boolean isJcr = info instanceof JcrPackageInfo;
        final List<Values> result = new ArrayList<>();
        if (config.writeEnabled()) {
            if (isJcr) {
                result.add(actionGroup(
                        action("edit", "pencil", "Edit"),
                        action("filters", "funnel", "Filters")));
            }
            // both stay available regardless of the current 'installed' state - installing an
            // already installed package (re-install, e.g. after fixing its content) is normal
            result.add(actionGroup(
                    action("uninstall", "box-arrow-down", "Uninstall"),
                    action("install", "box-arrow-in-down", "Install")));
        }
        final List<Values> lastGroup = new ArrayList<>();
        if (config.writeEnabled() && isJcr) {
            lastGroup.add(action("assemble", "arrow-repeat", "Build"));
        }
        lastGroup.add(action("download", "download", "Download")
                .with("link", downloadUri(info)));
        result.add(actionGroup(lastGroup.toArray(new Values[0])));
        if (isJcr) {
            result.add(action("coverage", "card-list", "Coverage"));
        }
        if (config.writeEnabled()) {
            result.add(action("delete", "trash", "Delete"));
        }
        return result;
    }

    protected @NotNull String downloadUri(@NotNull final PackageInfo info) {
        return manager.serverPath() + "." + key() + ".download.html" + info.getPath() + modeQuery(info);
    }

    // Read operations

    protected @NotNull Result<?> treeNode(@NotNull final SlingHttpServletRequest request) {
        try {
            final PackageTreeNode node;
            if (isRegistryMode(request)) {
                node = new RegistryTree(registryOperations().packages()).nodeAt(targetPath(request));
            } else {
                final Session session = session(request);
                if (session == null) {
                    return new Result<>(SC_INTERNAL_SERVER_ERROR);
                }
                JcrPackageManager manager = jcrOperations.packageManager(session);
                node = manager != null ? new JcrPackageTree(manager).nodeAt(targetPath(request)) : null;
            }
            return node != null ? new Result<>(node) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The tree (folder) paths leading down to the package addressed by the request's suffix path -
     * lets the client drill a jstree open down to a specific selection (initial page load, browser
     * back/forward navigation, or reselecting a package after an edit), see 'PackagesTree#openNode'.
     */
    protected @NotNull Result<?> ancestorsOf(@NotNull final SlingHttpServletRequest request) {
        try {
            return new Result<>(ancestorChain(request, targetPath(request)));
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> downloadPackage(@NotNull final SlingHttpServletRequest request) {
        try {
            if (isRegistryMode(request)) {
                try (RegisteredPackage pkg = openRegistryPackage(request)) {
                    return pkg != null ? registryOperations().download(pkg) : new Result<>(SC_NOT_FOUND);
                }
            }
            final JcrPackage jcrPackage = openPackage(request);
            return jcrPackage != null ? jcrOperations.download(jcrPackage) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @Nullable JcrPackage openPackage(@NotNull final SlingHttpServletRequest request)
            throws RepositoryException {
        final Session session = session(request);
        if (session == null) {
            return null;
        }
        JcrPackageManager manager = jcrOperations.packageManager(session);
        return manager != null ? jcrOperations.open(manager, targetPath(request)) : null;
    }

    protected @Nullable RegisteredPackage openRegistryPackage(@NotNull final SlingHttpServletRequest request)
            throws IOException {
        final PackageId id = RegistryOperations.packageId(targetPath(request));
        return id != null ? registryOperations().open(id) : null;
    }

    protected static final int TILE_LAST_INSTALLED_LIMIT = 7;

    protected @NotNull Result<?> renderTile(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final JcrPackageManager manager = jcrOperations.packageManager(session);
            final List<JcrPackage> packages = manager != null ? manager.listPackages() : List.of();
            final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                    .with("label", label())
                    .with("tile.count", packages.size())
                    .with("packages.lastInstalled", lastInstalled(packages, TILE_LAST_INSTALLED_LIMIT))
            ), "/sling/packages/tile.html"));
            return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The (up to) 'limit' most recently installed packages, newest first - for the Dashboard
     * tile. 'lastUnpacked' (FileVault's own term for "last installed") is used both as the sort
     * key and as the displayed timestamp; a package never installed has no 'lastUnpacked' and is
     * excluded. The raw {@link JcrPackageDefinition#getLastUnpacked()} 'Calendar' is passed
     * through as-is (not via {@link #valuesOf}, which would flatten it to a JSON timestamp) so
     * the template's own date formatting (see 'AbstractToolsPlugin#toString') applies.
     */
    protected @NotNull List<Values> lastInstalled(@NotNull final List<JcrPackage> packages, final int limit)
            throws RepositoryException {
        final List<JcrPackageDefinition> definitions = new ArrayList<>();
        for (final JcrPackage jcrPackage : packages) {
            final JcrPackageDefinition definition = jcrPackage.getDefinition();
            if (definition != null && definition.getLastUnpacked() != null) {
                definitions.add(definition);
            }
        }
        definitions.sort(Comparator.comparing(JcrPackageDefinition::getLastUnpacked, Comparator.reverseOrder()));
        final List<Values> result = new ArrayList<>();
        for (final JcrPackageDefinition definition : definitions.subList(0, Math.min(limit, definitions.size()))) {
            result.add(new Values()
                    .with("name", definition.get(JcrPackageDefinition.PN_NAME))
                    .with("version", definition.get(JcrPackageDefinition.PN_VERSION))
                    .with("lastInstalled", definition.getLastUnpacked()));
        }
        return result;
    }

    // Dialogs (loaded on demand, see the shared 'CPM.Dialog' client framework)

    protected @NotNull Result<?> dialog(@NotNull final SlingHttpServletRequest request, @NotNull final String name) {
        switch (name) {
            case "create":
                return renderDialog(DIALOGS_ROOT + "create.html", new Values()
                        .with("dialog.action", actionLink("create")));
            case "upload":
                return renderDialog(DIALOGS_ROOT + "upload.html", new Values()
                        .with("dialog.action", actionLink("upload")));
            case "update":
                return jcrPackageDialog(request, DIALOGS_ROOT + "update.html", info -> new Values()
                        .with("dialog.action", actionLink("update") + info.getPath())
                        .with("info", valuesOf(info)));
            case "filters":
                return filtersDialog(request);
            case "coverage":
                return coverageDialog(request);
            case "purge":
                return purgeDialog(request);
            case "install":
            case "uninstall":
            case "assemble":
            case "delete": {
                // 'assemble' has no registry counterpart, but the button that opens this dialog is
                // never rendered for a registry-backed package in the first place (see #actions),
                // so resolving it the same mode-aware way as the others needs no special-casing -
                // a forced '?mode=registry' would just 404 on the confirm POST, same as any other
                // registry package that doesn't support the requested action
                final String title = StringUtils.capitalize(name);
                return packageDialog(request, DIALOGS_ROOT + "confirm.html", info -> {
                    final String packageLabel = info.getName() + (StringUtils.isNotBlank(info.getVersion())
                            ? " " + info.getVersion() : "");
                    return new Values()
                            .with("dialog.action", actionLink(name) + info.getPath() + modeQuery(info))
                            .with("dialog.title", title + " Package")
                            .with("dialog.message", title + " " + packageLabel + "?");
                });
            }
            default:
                return new Result<>(SC_NOT_FOUND);
        }
    }

    /**
     * Renders the given dialog template against the package addressed by the request, in
     * whichever backend {@link #isRegistryMode} selects.
     */
    protected @NotNull Result<?> packageDialog(@NotNull final SlingHttpServletRequest request,
                                               @NotNull final String templatePath,
                                               @NotNull final Function<PackageInfo, Values> values) {
        final PackageInfo info = packageInfo(request);
        return info != null ? renderDialog(templatePath, values.apply(info)) : new Result<>(SC_NOT_FOUND);
    }

    /**
     * Like {@link #packageDialog}, but only ever resolves the package in the JCR backend - for
     * dialogs (Edit, Build) that have no registry counterpart at all.
     */
    protected @NotNull Result<?> jcrPackageDialog(@NotNull final SlingHttpServletRequest request,
                                                  @NotNull final String templatePath,
                                                  @NotNull final Function<JcrPackageInfo, Values> values) {
        try {
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            final JcrPackageInfo info = jcrOperations.info(jcrOperations.packageManager(session(request)), jcrPackage);
            return renderDialog(templatePath, values.apply(info));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> filtersDialog(@NotNull final SlingHttpServletRequest request) {
        try (final JcrPackage jcrPackage = openPackage(request)) {
            final JcrPackageDefinition definition = jcrPackage != null ? jcrPackage.getDefinition() : null;
            if (definition == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            return renderDialog(DIALOGS_ROOT + "filters.html", new Values()
                    .with("dialog.action", actionLink("filters") + targetPath(request))
                    .with("filterRoots", jcrOperations.filterRootDetails(definition)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> coverageDialog(@NotNull final SlingHttpServletRequest request) {
        try {
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            List<String> lines = jcrOperations.coverage(jcrPackage);
            if (lines.isEmpty()) {
                // not a failure: dumpCoverage only reports paths that currently exist in the
                // repository under the filter roots - an empty result means either no filter
                // roots are defined yet, or none of their target paths exist here (yet)
                lines = List.of("(no repository content currently matches this package's filter)");
            }
            return renderDialog(DIALOGS_ROOT + "coverage.html", new Values().with("coverage.lines", lines));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> purgeDialog(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        try {
            final int count = purgeCandidates(request, path).size();
            return renderDialog(DIALOGS_ROOT + "confirm.html", new Values()
                    .with("dialog.action", actionLink("purge") + path + (isRegistryMode(request) ? "?mode=" + MODE_REGISTRY : ""))
                    .with("dialog.title", "Purge Old Versions")
                    .with("dialog.message", count > 0
                            ? "Delete " + count + " old version" + (count == 1 ? "" : "s")
                            + " here, keeping only the latest of each package?"
                            : "There are no old versions to purge here."));
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> renderDialog(@NotNull final String templatePath, @NotNull final Values values) {
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values().with(values)), templatePath));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    // Write operations

    protected @NotNull Result<?> errorResult(final int statusCode, @Nullable final String message) {
        return new Result<>(statusCode, Map.of("message", StringUtils.defaultString(message, "Request failed.")));
    }

    protected @NotNull Result<?> operationResult(@NotNull final JcrPackageOperations.OperationLog log) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("log", log.getLines());
        data.put("error", log.isError());
        return new Result<>(log.isError() ? SC_INTERNAL_SERVER_ERROR : SC_OK, data);
    }

    protected @NotNull Result<?> createPackage(@NotNull final SlingHttpServletRequest request) {
        final String name = request.getParameter("name");
        if (StringUtils.isBlank(name)) {
            return errorResult(SC_BAD_REQUEST, "Package name is required.");
        }
        try {
            final String group = StringUtils.defaultString(request.getParameter("group"));
            final String version = request.getParameter("version");
            final JcrPackageManager manager = jcrOperations.packageManager(session(request));
            final JcrPackage jcrPackage = manager != null ? jcrOperations.create(manager, group, name, version) : null;
            return jcrPackage != null ? new Result<>(Map.of("path", StringUtils.defaultString(
                    JcrPackageOperations.relativePath(manager, jcrPackage)))) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> uploadPackage(@NotNull final SlingHttpServletRequest request) {
        final RequestParameter file = request.getRequestParameter("file");
        if (file == null || file.getSize() <= 0) {
            return errorResult(SC_BAD_REQUEST, "A package file is required.");
        }
        try {
            final boolean force = isChecked(request, "force");
            final JcrPackageManager jcrPackageManager = jcrOperations.packageManager(session(request));
            if (jcrPackageManager != null) {
                try (InputStream input = file.getInputStream()) {
                    if (input != null) {
                        final JcrPackage jcrPackage = jcrOperations.upload(jcrPackageManager, input, force);
                        return new Result<>(Map.of("path", StringUtils.defaultString(
                                JcrPackageOperations.relativePath(jcrPackageManager, jcrPackage))));
                    }
                }
            }
            return new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> updatePackage(@NotNull final SlingHttpServletRequest request) {
        try {
            final JcrPackageManager manager = jcrOperations.packageManager(session(request));
            JcrPackage jcrPackage = manager != null ? jcrOperations.open(manager, targetPath(request)) : null;
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            // a group/name/version change renames (moves) the underlying node, so the response
            // must report the package's possibly new path back to the client - it's still
            // selected by its old path otherwise, which no longer exists afterwards
            jcrPackage = jcrOperations.update(manager, jcrPackage, request.getParameterMap());
            return new Result<>(Map.of("path", StringUtils.defaultString(
                    JcrPackageOperations.relativePath(manager, jcrPackage))));
        } catch (RepositoryException | PackageException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> installPackage(@NotNull final SlingHttpServletRequest request) {
        try {
            if (isRegistryMode(request)) {
                final PackageId id = RegistryOperations.packageId(targetPath(request));
                final Session session = session(request);
                if (id == null || session == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                return operationResult(registryOperations().install(session, id));
            }
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            return operationResult(jcrOperations.install(jcrPackage));
        } catch (RepositoryException | PackageException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> uninstallPackage(@NotNull final SlingHttpServletRequest request) {
        try {
            if (isRegistryMode(request)) {
                final PackageId id = RegistryOperations.packageId(targetPath(request));
                final Session session = session(request);
                if (id == null || session == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                return operationResult(registryOperations().uninstall(session, id));
            }
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            return operationResult(jcrOperations.uninstall(jcrPackage));
        } catch (RepositoryException | PackageException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> assemblePackage(@NotNull final SlingHttpServletRequest request) {
        try {
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            JcrPackageManager manager = jcrOperations.packageManager(session(request));
            return manager != null ? operationResult(jcrOperations.assemble(manager, jcrPackage)) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException | PackageException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> filtersPackage(@NotNull final SlingHttpServletRequest request) {
        try {
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            final String[] roots = Optional.ofNullable(request.getParameterValues("root")).orElseGet(() -> new String[0]);
            final String[] modes = Optional.ofNullable(request.getParameterValues("mode")).orElseGet(() -> new String[0]);
            final String[] rules = Optional.ofNullable(request.getParameterValues("rules")).orElseGet(() -> new String[0]);
            jcrOperations.setFilters(jcrPackage, roots, modes, rules);
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> deletePackage(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        try {
            // the full ancestor chain (top-down) must be captured before the delete itself -
            // afterward the package is gone from the tree to look it up by
            final List<String> ancestors = ancestorChain(request, path);
            if (isRegistryMode(request)) {
                final PackageId id = RegistryOperations.packageId(path);
                if (id == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                registryOperations().remove(id);
            } else {
                final JcrPackageManager manager = jcrOperations.packageManager(session(request));
                final JcrPackage jcrPackage = manager != null ? jcrOperations.open(manager, path) : null;
                if (jcrPackage == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                jcrOperations.delete(manager, jcrPackage);
            }
            return deletedResult(path, survivingAncestor(request, ancestors));
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    /**
     * The leaf (version) paths a "purge" of the request's suffix path would delete, in whichever
     * backend {@link #isRegistryMode} selects - see {@link JcrPackageTree#purgeCandidates} /
     * {@link RegistryTree#purgeCandidates}.
     */
    protected @NotNull List<String> purgeCandidates(@NotNull final SlingHttpServletRequest request, @NotNull final String path)
            throws RepositoryException, IOException {
        if (isRegistryMode(request)) {
            return new RegistryTree(registryOperations().packages()).purgeCandidates(path);
        }
        final Session session = session(request);
        final JcrPackageManager manager = session != null ? jcrOperations.packageManager(session) : null;
        return manager != null ? new JcrPackageTree(manager).purgeCandidates(path) : List.of();
    }

    protected @NotNull Result<?> deletedResult(@NotNull final String path, @Nullable final String parent) {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", path);
        if (parent != null) {
            data.put("parent", parent);
        }
        return new Result<>(data);
    }

    /**
     * The tree (folder) paths leading down to the given package path, top-down, in whichever
     * backend {@link #isRegistryMode} selects - see {@link JcrPackageTree#ancestorsOf} /
     * {@link RegistryTree#ancestorsOf}.
     */
    protected @NotNull List<String> ancestorChain(@NotNull final SlingHttpServletRequest request, @NotNull final String path)
            throws RepositoryException, IOException {
        if (isRegistryMode(request)) {
            return new RegistryTree(registryOperations().packages()).ancestorsOf(path);
        }
        final Session session = session(request);
        final JcrPackageManager manager = session != null ? jcrOperations.packageManager(session) : null;
        return manager != null ? new JcrPackageTree(manager).ancestorsOf(path) : List.of();
    }

    /**
     * The deepest folder in the given (top-down) ancestor chain that still exists - for deciding
     * which folder to select and show after deleting a package (see
     * {@code PackagesDetail#onDialogSuccess}): if the deleted package was the only one in its
     * name folder, that folder no longer exists, so its parent group folder is tried next, and so
     * on up the chain - possibly all the way up if the package was the sole occupant of its
     * entire group.
     *
     * @param ancestors the chain as captured by {@link #ancestorChain} <em>before</em> the delete
     * @return the deepest still-existing folder, or 'null' if none of them do (e.g. the chain
     * itself was empty, or every folder in it is now gone)
     */
    protected @Nullable String survivingAncestor(@NotNull final SlingHttpServletRequest request, @NotNull final List<String> ancestors)
            throws RepositoryException, IOException {
        if (ancestors.isEmpty()) {
            return null;
        }
        if (isRegistryMode(request)) {
            final RegistryTree tree = new RegistryTree(registryOperations().packages());
            for (int i = ancestors.size() - 1; i >= 0; i--) {
                if (tree.nodeAt(ancestors.get(i)) != null) {
                    return ancestors.get(i);
                }
            }
            return null;
        }
        final Session session = session(request);
        final JcrPackageManager manager = session != null ? jcrOperations.packageManager(session) : null;
        if (manager == null) {
            return null;
        }
        final JcrPackageTree tree = new JcrPackageTree(manager);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            if (tree.nodeAt(ancestors.get(i)) != null) {
                return ancestors.get(i);
            }
        }
        return null;
    }

    /**
     * Deletes every old version under the request's suffix path (a group or name folder), keeping
     * only the latest version of each package found there - see {@link #purgeCandidates}. Returns
     * the same '{log, error}' shape as install/uninstall/assemble, so the client's generic dialog
     * success handler shows the same result popup with the list of what was purged.
     */
    protected @NotNull Result<?> purgePackages(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        try {
            final List<String> candidates = purgeCandidates(request, path);
            final JcrPackageOperations.OperationLog log = new JcrPackageOperations.OperationLog();
            if (isRegistryMode(request)) {
                for (final String leafPath : candidates) {
                    final PackageId id = RegistryOperations.packageId(leafPath);
                    if (id != null) {
                        try {
                            registryOperations().remove(id);
                            log.onMessage(ProgressTrackerListener.Mode.TEXT, "Deleted", leafPath);
                        } catch (IOException ex) {
                            log.onError(ProgressTrackerListener.Mode.TEXT, leafPath, ex);
                        }
                    }
                }
            } else {
                final JcrPackageManager manager = jcrOperations.packageManager(session(request));
                if (manager == null) {
                    return new Result<>(SC_INTERNAL_SERVER_ERROR);
                }
                for (final String leafPath : candidates) {
                    final JcrPackage jcrPackage = jcrOperations.open(manager, leafPath);
                    if (jcrPackage != null) {
                        try {
                            jcrOperations.delete(manager, jcrPackage);
                            log.onMessage(ProgressTrackerListener.Mode.TEXT, "Deleted", leafPath);
                        } catch (RepositoryException ex) {
                            log.onError(ProgressTrackerListener.Mode.TEXT, leafPath, ex);
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                log.onMessage(ProgressTrackerListener.Mode.TEXT, "No old versions found under", path);
            }
            return operationResult(log);
        } catch (RepositoryException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    // CRX Package Manager compatibility ('cmd=ls|rm|build|uninst', or upload+install if a 'file'
    // is posted without a 'cmd') - the wire protocol Maven deployment tooling like
    // content-package-maven-plugin speaks against '/crx/packmgr/service.jsp'; point such a
    // plugin's serviceURL at this endpoint (POST 'tools.packages.service.html') instead. Only
    // ever operates on the JCR backend - the legacy protocol itself has no registry notion.
    // Always answers HTTP 200 with the business-level result embedded as '<status code="...">'
    // in the XML body - that is the protocol's own convention, not an error on our side.

    protected @NotNull Result<?> servicePackage(@NotNull final SlingHttpServletRequest request) {
        final String cmd = request.getParameter("cmd");
        try {
            if (StringUtils.isNotBlank(cmd)) {
                switch (cmd) {
                    case "ls":
                        return crxList(request);
                    case "rm":
                        return config.writeEnabled() ? crxRemove(request) : crxResponse("", "", "403", "write disabled");
                    case "build":
                        return config.writeEnabled() ? crxBuildOrUninstall(request, true) : crxResponse("", "", "403", "write disabled");
                    case "uninst":
                        return config.writeEnabled() ? crxBuildOrUninstall(request, false) : crxResponse("", "", "403", "write disabled");
                    default:
                        return crxResponse("", "", "400", "unsupported command '" + cmd + "'");
                }
            } else if (config.writeEnabled()) {
                return crxUpload(request);
            } else {
                return crxResponse("", "", "403", "write disabled");
            }
        } catch (RepositoryException | PackageException | IOException ex) {
            LOG.error(ex.getMessage(), ex);
            return crxResponse("", "", "500", StringUtils.defaultString(ex.getMessage(), "error"));
        }
    }

    protected @NotNull Result<?> crxList(@NotNull final SlingHttpServletRequest request) throws RepositoryException {
        final Session session = session(request);
        final JcrPackageManager manager = jcrOperations.packageManager(session);
        final StringBuilder data = new StringBuilder("<packages>");
        if (manager != null) {
            for (final JcrPackage jcrPackage : manager.listPackages()) {
                data.append(jcrOperations.toCrxXml(jcrPackage));
            }
        }
        data.append("</packages>");
        return crxResponse(crxRequestXml("ls", null, null), data.toString(), "200", "ok");
    }

    protected @NotNull Result<?> crxRemove(@NotNull final SlingHttpServletRequest request) throws RepositoryException {
        final String name = StringUtils.defaultString(request.getParameter("name"));
        final String group = StringUtils.defaultString(request.getParameter("group"));
        final String requestXml = crxRequestXml("rm", name, group);
        final Session session = session(request);
        final JcrPackageManager jcrPackageManager = session != null ? jcrOperations.packageManager(session) : null;
        final JcrPackage jcrPackage = jcrPackageManager != null ? jcrOperations.find(jcrPackageManager, group, name) : null;
        if (jcrPackage == null) {
            return crxResponse(requestXml, "", "500", "Package '" + group + ":" + name + "' does not exist.");
        }
        jcrOperations.delete(jcrPackageManager, jcrPackage);
        return crxResponse(requestXml, "", "200", "ok");
    }

    protected @NotNull Result<?> crxBuildOrUninstall(@NotNull final SlingHttpServletRequest request, final boolean build)
            throws RepositoryException, PackageException, IOException {
        final String name = StringUtils.defaultString(request.getParameter("name"));
        final String group = StringUtils.defaultString(request.getParameter("group"));
        final String requestXml = crxRequestXml(build ? "build" : "uninst", name, group);
        final Session session = session(request);
        final JcrPackageManager jcrPackageManager = session != null ? jcrOperations.packageManager(session) : null;
        final JcrPackage jcrPackage = jcrPackageManager != null ? jcrOperations.find(jcrPackageManager, group, name) : null;
        if (jcrPackage == null) {
            return crxResponse(requestXml, "", "500", "Package '" + group + ":" + name + "' does not exist.");
        }
        final JcrPackageOperations.OperationLog log = build
                ? jcrOperations.assemble(jcrPackageManager, jcrPackage)
                : jcrOperations.uninstall(jcrPackage);
        final String data = jcrOperations.toCrxXml(jcrPackage);
        return log.isError()
                ? crxResponse(requestXml, data, "500", (build ? "assemble" : "uninstall") + " does not succeed")
                : crxResponse(requestXml, data, "200", "ok");
    }

    protected @NotNull Result<?> crxUpload(@NotNull final SlingHttpServletRequest request)
            throws RepositoryException, PackageException, IOException {
        final RequestParameter file = request.getRequestParameter("file");
        if (file == null || file.getSize() <= 0) {
            return crxResponse("", "", "400", "no package file accessible");
        }
        final Session session = session(request);
        if (session == null) {
            return crxResponse("", "", "500", "no session");
        }
        final boolean force = isChecked(request, "force");
        final JcrPackageManager jcrPackageManager = jcrOperations.packageManager(session);
        if (jcrPackageManager != null) {
            final JcrPackage jcrPackage;
            try (InputStream input = file.getInputStream()) {
                if (input != null) {
                    jcrPackage = jcrOperations.upload(jcrPackageManager, input, force);
                } else {
                    return crxResponse("", "", "400", "no package content found");
                }
            }
            final JcrPackageOperations.OperationLog log = jcrOperations.install(jcrPackage);
            final String data = jcrOperations.toCrxXml(jcrPackage);
            return log.isError()
                    ? crxResponse("", data, "500", "install does not succeed")
                    : crxResponse("", data, "200", "ok");
        }
        return crxResponse("", "", "500", "internal error");
    }

    protected @NotNull Result<?> crxResponse(@NotNull final String requestXml, @NotNull final String dataXml,
                                             @NotNull final String statusCode, @NotNull final String statusMessage) {
        final String xml = "<repo>" + requestXml
                + "<response>"
                + (dataXml.isEmpty() ? "" : "<data>" + dataXml + "</data>")
                + "<status code=\"" + statusCode + "\">" + JcrPackageOperations.xmlEscape(statusMessage) + "</status>"
                + "</response></repo>";
        return new Result<>(new StringReader(xml), "text/xml;charset=utf-8");
    }

    protected @NotNull String crxRequestXml(@NotNull final String cmd, @Nullable final String name, @Nullable final String group) {
        final StringBuilder xml = new StringBuilder("<request>")
                .append("<param name=\"cmd\" value=\"").append(JcrPackageOperations.xmlEscape(cmd)).append("\"/>");
        if (StringUtils.isNotBlank(name)) {
            xml.append("<param name=\"name\" value=\"").append(JcrPackageOperations.xmlEscape(name)).append("\"/>");
        }
        if (StringUtils.isNotBlank(group)) {
            xml.append("<param name=\"group\" value=\"").append(JcrPackageOperations.xmlEscape(group)).append("\"/>");
        }
        return xml.append("</request>").toString();
    }
}
