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
@Component(service = {ToolsPlugin.class, PackageManager.class}, immediate = true)
@Designate(ocd = PackageManager.Config.class)
public class PackageManager extends AbstractToolsPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(PackageManager.class);

    public static final String KEY = "packages";
    public static final String LABEL = "Packages";
    public static final int RANK = 4000;
    public static final String TILE_KEY = "tile";

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
     * The '?mode=registry' query suffix to append to a mode-agnostic URL built for the given
     * package, or an empty string for a JCR-backed one - so the client never has to know which
     * backend a given path belongs to, it just carries the mode along.
     */
    protected @NotNull String modeQuery(@NotNull final PackageInfo info) {
        return info instanceof JcrPackageInfo ? "" : "?mode=" + MODE_REGISTRY;
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return List.of(
                new Page(key(), label(), rank(), this::pageLink),
                new Tile(TILE_KEY, label(), rank())
        );
    }

    @Override
    public @Nullable String widgetViewLink(@NotNull final SlingHttpServletRequest request,
                                           @NotNull final SlingHttpServletResponse response,
                                           @NotNull final String widgetKey) {
        return TILE_KEY.equals(widgetKey) ? pageLink() : super.widgetViewLink(request, response, widgetKey);
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
                        .with("packages.modeLink", pageLink() + "?mode=" + (registryMode ? "jcr" : MODE_REGISTRY))
                ), "page"));
                return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
            }
        }
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

    protected @NotNull Result<?> viewPackage(@NotNull final SlingHttpServletRequest request) {
        final PackageInfo info = packageInfo(request);
        if (info == null) {
            return new Result<>(SC_NOT_FOUND);
        }
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                .with("packages.actions", (Supplier<?>) () -> actions(info))
                .with("packages.info", (Supplier<?>) () -> valuesOf(info))
        ), "/sling/packages/details/content.html"));
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
                .with("link", manager.serverPath() + "." + key() + ".download.html" + info.getPath() + modeQuery(info)));
        result.add(actionGroup(lastGroup.toArray(new Values[0])));
        if (isJcr) {
            result.add(action("coverage", "card-list", "Coverage"));
        }
        if (config.writeEnabled()) {
            result.add(action("delete", "trash", "Delete"));
        }
        return result;
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

    protected @NotNull Result<?> renderTile(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            JcrPackageManager manager = jcrOperations.packageManager(session);
            final int count = manager != null ? manager.listPackages().size() : 0;
            final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                    .with("label", label())
                    .with("tile.count", count)
            ), "/sling/packages/tile.html"));
            return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
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
            final boolean force = "true".equalsIgnoreCase(request.getParameter("force"))
                    || "on".equalsIgnoreCase(request.getParameter("force"));
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
            final JcrPackage jcrPackage = openPackage(request);
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            jcrOperations.update(jcrPackage, request.getParameterMap());
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
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
        try {
            if (isRegistryMode(request)) {
                final PackageId id = RegistryOperations.packageId(targetPath(request));
                if (id == null) {
                    return new Result<>(SC_NOT_FOUND);
                }
                registryOperations().remove(id);
                return new Result<>(Map.of("deleted", targetPath(request)));
            }
            final JcrPackageManager manager = jcrOperations.packageManager(session(request));
            final JcrPackage jcrPackage = manager != null ? jcrOperations.open(manager, targetPath(request)) : null;
            if (jcrPackage == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            jcrOperations.delete(manager, jcrPackage);
            return new Result<>(Map.of("deleted", targetPath(request)));
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
        final boolean force = "true".equalsIgnoreCase(request.getParameter("force"))
                || "on".equalsIgnoreCase(request.getParameter("force"));
        final JcrPackageManager jcrPackageManager = jcrOperations.packageManager(session);
        if (jcrPackageManager != null) {
            final JcrPackage jcrPackage;
            try (InputStream input = file.getInputStream()) {
                if (input != null) {
                    jcrPackage = jcrOperations.upload(jcrPackageManager, input, force);
                } else {
                    return new Result<>(SC_NOT_FOUND);
                }
            }
            final JcrPackageOperations.OperationLog log = jcrOperations.install(jcrPackage);
            final String data = jcrOperations.toCrxXml(jcrPackage);
            return log.isError()
                    ? crxResponse("", data, "500", "install does not succeed")
                    : crxResponse("", data, "200", "ok");
        }
        return new Result<>(SC_INTERNAL_SERVER_ERROR);
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
}
