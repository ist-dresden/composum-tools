package com.composum.sling.packages.jcr;

import com.composum.sling.tools.Common;
import com.composum.sling.tools.Result;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.api.FilterSet;
import org.apache.jackrabbit.vault.fs.api.ImportMode;
import org.apache.jackrabbit.vault.fs.api.PathFilter;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.api.WorkspaceFilter;
import org.apache.jackrabbit.vault.fs.config.ConfigurationException;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.apache.jackrabbit.vault.fs.config.MetaInf;
import org.apache.jackrabbit.vault.fs.filter.DefaultPathFilter;
import org.apache.jackrabbit.vault.fs.io.ImportOptions;
import org.apache.jackrabbit.vault.packaging.DependencyHandling;
import org.apache.jackrabbit.vault.packaging.JcrPackage;
import org.apache.jackrabbit.vault.packaging.JcrPackageDefinition;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.Packaging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * Wraps the FileVault {@link JcrPackageManager} API (the classic, JCR-node-backed package store under
 * '/etc/packages') for the Package Manager's tree, detail, download and delete operations.
 */
public class JcrPackageOperations {

    private static final Logger LOG = LoggerFactory.getLogger(JcrPackageOperations.class);

    public static final String ZIP_TYPE = "application/zip";

    private final Packaging packaging;

    public JcrPackageOperations(@NotNull final Packaging packaging) {
        this.packaging = packaging;
    }

    public @Nullable JcrPackageManager packageManager(@Nullable final Session session) throws RepositoryException {
        return session != null ? packaging.getPackageManager(session) : null;
    }

    /**
     * The given package's path, relative to the package root (e.g. '/my/group/my-package-1.0.zip').
     *
     * @return the relative path, or 'null' if it cannot be determined
     */
    public static @Nullable String relativePath(@Nullable final JcrPackageManager manager,
                                                @NotNull final JcrPackage jcrPackage) {
        try {
            final Node node = jcrPackage.getNode();
            final Node root = manager != null ? manager.getPackageRoot(true) : null;
            if (node != null && root != null) {
                final String path = node.getPath();
                final String rootPath = root.getPath();
                if (path.startsWith(rootPath)) {
                    return path.substring(rootPath.length());
                }
            }
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return null;
    }

    /**
     * Opens the package at the given path, relative to the package root.
     *
     * @return the opened package, or 'null' if no such package exists
     */
    public @Nullable JcrPackage open(@NotNull final JcrPackageManager manager, @NotNull final String path)
            throws RepositoryException {
        final Node root = manager.getPackageRoot(true);
        final String relPath = StringUtils.stripStart(path, "/");
        if (root == null || StringUtils.isBlank(relPath) || !root.hasNode(relPath)) {
            return null;
        }
        return manager.open(root.getNode(relPath), true);
    }

    public @NotNull JcrPackageInfo info(@Nullable final JcrPackageManager manager, @NotNull final JcrPackage jcrPackage)
            throws RepositoryException {
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        final JcrPackageInfo info = new JcrPackageInfo();
        info.setPath(relativePath(manager, jcrPackage));
        if (definition != null) {
            info.setGroup(definition.get(JcrPackageDefinition.PN_GROUP));
            info.setName(definition.get(JcrPackageDefinition.PN_NAME));
            info.setVersion(definition.get(JcrPackageDefinition.PN_VERSION));
            info.setDescription(definition.get(JcrPackageDefinition.PN_DESCRIPTION));
            info.setCreated(definition.getCreated());
            info.setCreatedBy(definition.getCreatedBy());
            info.setLastModified(definition.getLastModified());
            info.setLastModifiedBy(definition.getLastModifiedBy());
            info.setLastUnpacked(definition.getLastUnpacked());
            info.setLastUnpackedBy(definition.getLastUnpackedBy());
            info.setDependencies(multiProperty(definition, JcrPackageDefinition.PN_DEPENDENCIES));
            info.setFilterRoots(filterRoots(definition));
            info.setAcHandling(definition.get(JcrPackageDefinition.PN_AC_HANDLING));
            info.setRequiresRestart(definition.getBoolean(JcrPackageDefinition.PN_REQUIRES_RESTART));
            info.setRequiresRoot(definition.getBoolean(JcrPackageDefinition.PN_REQUIRES_ROOT));
            info.setReplaces(multiProperty(definition, "replaces"));
            info.setProviderName(definition.get("providerName"));
            info.setProviderUrl(definition.get("providerUrl"));
            info.setProviderLink(definition.get("providerLink"));
            info.setTestedWith(definition.get("testedWith"));
            info.setDependenciesText(String.join("\n", info.getDependencies()));
            info.setReplacesText(String.join("\n", info.getReplaces()));
        }
        info.setSize(jcrPackage.getSize());
        info.setInstalled(jcrPackage.isInstalled());
        info.setValid(jcrPackage.isValid());
        info.setSealed(jcrPackage.isSealed());
        return info;
    }

    private static @NotNull String[] multiProperty(@NotNull final JcrPackageDefinition definition,
                                                   @NotNull final String key) {
        try {
            final Node node = definition.getNode();
            if (node.hasProperty(key)) {
                final Value[] values = node.getProperty(key).getValues();
                final String[] result = new String[values.length];
                for (int i = 0; i < values.length; i++) {
                    result[i] = values[i].getString();
                }
                return result;
            }
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
        }
        return new String[0];
    }

    private static @NotNull Set<String> filterRoots(@NotNull final JcrPackageDefinition definition)
            throws RepositoryException {
        final Set<String> roots = new LinkedHashSet<>();
        final MetaInf metaInf = definition.getMetaInf();
        final WorkspaceFilter filter = metaInf.getFilter();
        if (filter != null) {
            for (final PathFilterSet filterSet : filter.getFilterSets()) {
                roots.add(filterSet.getRoot());
            }
        }
        return roots;
    }

    /**
     * The package's filter roots, each with its import mode and its include/exclude rules as one
     * '+ pattern' / '- pattern' line per rule - the shape the Filters dialog reads back via
     * {@link #setFilters}. A rule whose filter is not a plain regex {@link DefaultPathFilter}
     * (practically never the case for a filter.xml-authored package) is silently dropped, since it
     * cannot be round-tripped through that text form.
     */
    public @NotNull List<FilterRootInfo> filterRootDetails(@NotNull final JcrPackageDefinition definition)
            throws RepositoryException {
        final List<FilterRootInfo> result = new ArrayList<>();
        final MetaInf metaInf = definition.getMetaInf();
        final WorkspaceFilter filter = metaInf.getFilter();
        if (filter != null) {
            for (final PathFilterSet filterSet : filter.getFilterSets()) {
                final FilterRootInfo info = new FilterRootInfo();
                info.setRoot(filterSet.getRoot());
                final ImportMode mode = filterSet.getImportMode();
                info.setImportMode(mode.name().toLowerCase());
                final StringBuilder rules = new StringBuilder();
                for (final FilterSet.Entry<PathFilter> entry : filterSet.getEntries()) {
                    final PathFilter pathFilter = entry.getFilter();
                    if (pathFilter instanceof DefaultPathFilter) {
                        if (rules.length() > 0) {
                            rules.append("\n");
                        }
                        rules.append(entry.isInclude() ? "+ " : "- ").append(((DefaultPathFilter) pathFilter).getPattern());
                    }
                }
                info.setRulesText(rules.toString());
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Rebuilds the package's workspace filter from the Filters dialog's repeated root/mode/rules
     * fields (index-aligned - the i-th root goes with the i-th mode and the i-th rules text), each
     * rules text being one '+ pattern' / '- pattern' regex rule per line (blank lines and lines
     * without a recognized '+'/'-' prefix are ignored). The order of the arrays is the filter order.
     */
    public void setFilters(@NotNull final JcrPackage jcrPackage, @NotNull final String[] roots,
                           @NotNull final String[] modes, @NotNull final String[] rules)
            throws RepositoryException {
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        if (definition == null) {
            return;
        }
        final DefaultWorkspaceFilter filter = new DefaultWorkspaceFilter();
        for (int i = 0; i < roots.length; i++) {
            final String root = roots[i].trim();
            if (StringUtils.isBlank(root)) {
                continue;
            }
            final PathFilterSet filterSet = new PathFilterSet(root);
            final String mode = i < modes.length ? modes[i].trim() : "";
            if (StringUtils.isNotBlank(mode)) {
                try {
                    filterSet.setImportMode(ImportMode.valueOf(mode.toUpperCase()));
                } catch (IllegalArgumentException ex) {
                    LOG.warn("unknown import mode '{}' for filter root '{}', ignored", mode, root);
                }
            }
            final String ruleText = i < rules.length ? StringUtils.defaultString(rules[i]) : "";
            for (final String line : ruleText.split("\\r?\\n")) {
                final String trimmed = line.trim();
                final boolean include = trimmed.startsWith("+");
                final boolean exclude = !include && trimmed.startsWith("-");
                if (!include && !exclude) {
                    continue;
                }
                final String pattern = trimmed.substring(1).trim();
                try {
                    final DefaultPathFilter pathFilter = new DefaultPathFilter(pattern);
                    if (include) {
                        filterSet.addInclude(pathFilter);
                    } else {
                        filterSet.addExclude(pathFilter);
                    }
                } catch (ConfigurationException ex) {
                    LOG.warn("invalid filter pattern '{}' for root '{}', ignored", pattern, root, ex);
                }
            }
            filter.add(filterSet);
        }
        definition.setFilter(filter, true);
    }

    /**
     * The repository paths the package's filter would touch, as one formatted line per entry -
     * a plain synchronous dump, not persisted anywhere.
     */
    public @NotNull List<String> coverage(@NotNull final JcrPackage jcrPackage) throws RepositoryException {
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        final OperationLog log = new OperationLog();
        if (definition != null) {
            definition.dumpCoverage(log);
        }
        return log.getLines();
    }

    public @NotNull Result<InputStream> download(@NotNull final JcrPackage jcrPackage) throws RepositoryException {
        final Property data = jcrPackage.getData();
        final Binary binary = data != null ? data.getBinary() : null;
        final InputStream stream = binary != null ? binary.getStream() : null;
        if (stream == null) {
            return new Result<>(SC_NOT_FOUND);
        }
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        final String name = definition != null ? definition.get(JcrPackageDefinition.PN_NAME) : "package";
        final String version = definition != null ? definition.get(JcrPackageDefinition.PN_VERSION) : null;
        final String filename = name + (StringUtils.isNotBlank(version) ? "-" + version : "") + ".zip";
        final Result<InputStream> result = new Result<>(SC_OK, ZIP_TYPE, stream);
        result.setHeader(Common.HTTP_CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return result;
    }

    public void delete(@NotNull final JcrPackageManager manager, @NotNull final JcrPackage jcrPackage)
            throws RepositoryException {
        manager.remove(jcrPackage);
    }

    public @NotNull JcrPackage create(@NotNull final JcrPackageManager manager, @NotNull final String group,
                                      @NotNull final String name, @Nullable final String version)
            throws RepositoryException, IOException {
        return manager.create(group, name, version);
    }

    public @NotNull JcrPackage upload(@NotNull final JcrPackageManager manager, @NotNull final InputStream input,
                                      final boolean force) throws RepositoryException, IOException {
        return manager.upload(input, force);
    }

    // Definition property editing (the Update dialog): each editable field is either a plain
    // String, a boolean ('true' present among the submitted values wins over an accompanying
    // hidden 'false' fallback - the standard checkbox/hidden-field pattern), or a newline-separated
    // multi-value field (dependencies/replaces), written directly as a String[] property.
    private interface DefinitionSetter {
        void set(@NotNull JcrPackageDefinition definition, @NotNull String key, @NotNull String[] values)
                throws RepositoryException;
    }

    private static final DefinitionSetter STRING_SETTER = (definition, key, values) ->
            definition.set(key, values.length > 0 ? values[0] : "", true);

    private static final DefinitionSetter BOOLEAN_SETTER = (definition, key, values) -> {
        boolean checked = false;
        for (final String value : values) {
            checked = checked || "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
        }
        definition.set(key, checked, true);
    };

    private static final DefinitionSetter MULTI_LINE_SETTER = (definition, key, values) -> {
        final Node node = definition.getNode();
        if (values.length > 0) {
            final List<String> lines = new ArrayList<>();
            for (final String line : values[0].split("\\r?\\n")) {
                if (StringUtils.isNotBlank(line)) {
                    lines.add(line.trim());
                }
            }
            node.setProperty(key, lines.toArray(new String[0]));
            node.getSession().save();
        }
    };

    private static final Map<String, DefinitionSetter> DEFINITION_SETTERS = new LinkedHashMap<>();

    static {
        DEFINITION_SETTERS.put(JcrPackageDefinition.PN_DESCRIPTION, STRING_SETTER);
        DEFINITION_SETTERS.put(JcrPackageDefinition.PN_AC_HANDLING, STRING_SETTER);
        DEFINITION_SETTERS.put(JcrPackageDefinition.PN_REQUIRES_RESTART, BOOLEAN_SETTER);
        DEFINITION_SETTERS.put(JcrPackageDefinition.PN_REQUIRES_ROOT, BOOLEAN_SETTER);
        DEFINITION_SETTERS.put(JcrPackageDefinition.PN_DEPENDENCIES, MULTI_LINE_SETTER);
        DEFINITION_SETTERS.put("replaces", MULTI_LINE_SETTER);
        DEFINITION_SETTERS.put("providerName", STRING_SETTER);
        DEFINITION_SETTERS.put("providerUrl", STRING_SETTER);
        DEFINITION_SETTERS.put("providerLink", STRING_SETTER);
        DEFINITION_SETTERS.put("testedWith", STRING_SETTER);
    }

    /**
     * Applies the Update dialog's submitted field values (as {@code request.getParameterMap()})
     * to the package's definition; unrecognized keys are ignored.
     */
    public void update(@NotNull final JcrPackage jcrPackage, @NotNull final Map<String, String[]> formFields)
            throws RepositoryException {
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        if (definition == null) {
            return;
        }
        for (final Map.Entry<String, DefinitionSetter> entry : DEFINITION_SETTERS.entrySet()) {
            final String[] values = formFields.get(entry.getKey());
            if (values != null) {
                entry.getValue().set(definition, entry.getKey(), values);
            }
        }
    }

    /**
     * Collects the messages of a synchronous install/uninstall/assemble operation - there is no
     * persisted audit trail or job history; the log is only ever returned in the HTTP response of
     * the request that triggered the operation.
     */
    public static class OperationLog implements ProgressTrackerListener {

        private final List<String> lines = new ArrayList<>();
        private boolean error;

        @Override
        public void onMessage(@NotNull final Mode mode, @NotNull final String action, @NotNull final String path) {
            lines.add(action + " " + path);
        }

        @Override
        public void onError(@NotNull final Mode mode, @NotNull final String path, @NotNull final Exception exception) {
            error = true;
            lines.add("E " + path + " : " + exception.getMessage());
        }

        public @NotNull List<String> getLines() {
            return lines;
        }

        public boolean isError() {
            return error;
        }
    }

    private static @NotNull ImportOptions importOptions(@NotNull final ProgressTrackerListener listener) {
        final ImportOptions options = new ImportOptions();
        options.setListener(listener);
        options.setDependencyHandling(DependencyHandling.BEST_EFFORT);
        return options;
    }

    /**
     * Installs the package, synchronously, on the calling thread - there is no async job queue;
     * the caller waits for the FileVault import to actually finish and gets its log back.
     */
    public @NotNull OperationLog install(@NotNull final JcrPackage jcrPackage)
            throws RepositoryException, PackageException, IOException {
        final OperationLog log = new OperationLog();
        jcrPackage.install(importOptions(log));
        return log;
    }

    public @NotNull OperationLog uninstall(@NotNull final JcrPackage jcrPackage)
            throws RepositoryException, PackageException, IOException {
        final OperationLog log = new OperationLog();
        jcrPackage.uninstall(importOptions(log));
        return log;
    }

    public @NotNull OperationLog assemble(@NotNull final JcrPackageManager manager, @NotNull final JcrPackage jcrPackage)
            throws RepositoryException, PackageException, IOException {
        final OperationLog log = new OperationLog();
        manager.assemble(jcrPackage, log);
        return log;
    }

    // CRX Package Manager ('/crx/packmgr/service.jsp') wire-protocol compatibility, used by
    // Maven deployment tooling (content-package-maven-plugin and forks) that still speaks it.

    /**
     * Finds the (single, unversioned) package with the given group/name, as addressed by the
     * legacy 'cmd=rm|build|uninst' protocol (which has no notion of multiple versions).
     */
    public @Nullable JcrPackage find(@NotNull final JcrPackageManager manager, @NotNull final String group,
                                     @NotNull final String name) throws RepositoryException {
        for (final JcrPackage jcrPackage : manager.listPackages()) {
            final JcrPackageDefinition definition = jcrPackage.getDefinition();
            final String pkgName = definition != null ? definition.get(JcrPackageDefinition.PN_NAME) : null;
            final String pkgGroup = StringUtils.defaultString(
                    definition != null ? definition.get(JcrPackageDefinition.PN_GROUP) : null);
            if (name.equals(pkgName) && group.equals(pkgGroup)) {
                return jcrPackage;
            }
        }
        return null;
    }

    /**
     * The package as a CRX Package Manager '&lt;package&gt;' XML element (group/name/version/
     * downloadName/size/created(By)/lastModified(By)/lastUnpacked(By)).
     */
    public @NotNull String toCrxXml(@NotNull final JcrPackage jcrPackage) throws RepositoryException {
        final JcrPackageDefinition definition = jcrPackage.getDefinition();
        final StringBuilder xml = new StringBuilder("<package>");
        if (definition != null) {
            final SimpleDateFormat dateFormat = new SimpleDateFormat(Common.XML_DATE_FORMAT);
            final String name = definition.get(JcrPackageDefinition.PN_NAME);
            final String version = definition.get(JcrPackageDefinition.PN_VERSION);
            xml.append(xmlElement("group", definition.get(JcrPackageDefinition.PN_GROUP)));
            xml.append(xmlElement("name", name));
            xml.append(xmlElement("version", version));
            xml.append(xmlElement("downloadName", name + (StringUtils.isNotBlank(version) ? "-" + version : "") + ".zip"));
            xml.append(xmlElement("size", String.valueOf(jcrPackage.getSize())));
            xml.append(xmlElement("createdBy", definition.getCreatedBy()));
            if (definition.getCreated() != null) {
                xml.append(xmlElement("created", dateFormat.format(definition.getCreated().getTime())));
            }
            xml.append(xmlElement("lastModifiedBy", definition.getLastModifiedBy()));
            if (definition.getLastModified() != null) {
                xml.append(xmlElement("lastModified", dateFormat.format(definition.getLastModified().getTime())));
            }
            xml.append(xmlElement("lastUnpackedBy", definition.getLastUnpackedBy()));
            if (definition.getLastUnpacked() != null) {
                xml.append(xmlElement("lastUnpacked", dateFormat.format(definition.getLastUnpacked().getTime())));
            }
        }
        xml.append("</package>");
        return xml.toString();
    }

    private static @NotNull String xmlElement(@NotNull final String name, @Nullable final String value) {
        return "<" + name + ">" + xmlEscape(StringUtils.defaultString(value)) + "</" + name + ">";
    }

    public static @NotNull String xmlEscape(@NotNull final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
