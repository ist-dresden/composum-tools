package com.composum.sling.packages.registry;

import com.composum.sling.packages.jcr.JcrPackageOperations;
import com.composum.sling.tools.Common;
import com.composum.sling.tools.Result;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.api.ProgressTrackerListener;
import org.apache.jackrabbit.vault.fs.api.WorkspaceFilter;
import org.apache.jackrabbit.vault.packaging.Dependency;
import org.apache.jackrabbit.vault.packaging.NoSuchPackageException;
import org.apache.jackrabbit.vault.packaging.PackageException;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.PackageProperties;
import org.apache.jackrabbit.vault.packaging.VaultPackage;
import org.apache.jackrabbit.vault.packaging.registry.ExecutionPlan;
import org.apache.jackrabbit.vault.packaging.registry.PackageRegistry;
import org.apache.jackrabbit.vault.packaging.registry.PackageTask;
import org.apache.jackrabbit.vault.packaging.registry.RegisteredPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_OK;

/**
 * Wraps the FileVault {@code PackageRegistry} SPI (potentially several bound registry services,
 * merged into one view) for the Package Manager's tree, detail, download, install, uninstall and
 * delete operations. Unlike the JCR-backed {@link JcrPackageOperations}, a registry is
 * deliberately read/install/uninstall/remove only - there is no create/upload/update/assemble or
 * filter editing here, matching the {@code PackageRegistry} SPI itself.
 */
public class RegistryOperations {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryOperations.class);

    private final List<PackageRegistry> registries;

    public RegistryOperations(@NotNull final List<PackageRegistry> registries) {
        this.registries = registries;
    }

    /**
     * Parses a leaf tree path (see {@link RegistryTree}, '/' + {@link PackageId#toString()}) back
     * into the {@link PackageId} it identifies.
     *
     * @return the parsed id, or 'null' if the path is not a valid package id
     */
    public static @Nullable PackageId packageId(@NotNull final String path) {
        final String value = StringUtils.stripStart(path, "/");
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return PackageId.fromString(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public @NotNull Set<PackageId> packages() throws IOException {
        final Set<PackageId> all = new TreeSet<>();
        for (final PackageRegistry registry : registries) {
            all.addAll(registry.packages());
        }
        return all;
    }

    private @Nullable PackageRegistry registryOf(@NotNull final PackageId id) throws IOException {
        for (final PackageRegistry registry : registries) {
            if (registry.contains(id)) {
                return registry;
            }
        }
        return null;
    }

    public @Nullable RegisteredPackage open(@NotNull final PackageId id) throws IOException {
        final PackageRegistry registry = registryOf(id);
        return registry != null ? registry.open(id) : null;
    }

    public @NotNull RegistryInfo info(@NotNull final RegisteredPackage pkg) throws IOException {
        final PackageId id = pkg.getId();
        final RegistryInfo info = new RegistryInfo();
        info.setPath("/" + id);
        info.setGroup(id.getGroup());
        info.setName(id.getName());
        info.setVersion(id.getVersionString());
        info.setSize(pkg.getSize());
        info.setInstalled(pkg.isInstalled());
        final PackageProperties properties = pkg.getPackageProperties();
        info.setDescription(properties.getDescription());
        info.setCreated(properties.getCreated());
        info.setCreatedBy(properties.getCreatedBy());
        info.setLastModified(properties.getLastModified());
        info.setLastModifiedBy(properties.getLastModifiedBy());
        final List<String> dependencies = new ArrayList<>();
        for (final Dependency dependency : properties.getDependencies()) {
            dependencies.add(dependency.toString());
        }
        info.setDependencies(dependencies.toArray(new String[0]));
        final Set<String> roots = new LinkedHashSet<>();
        final WorkspaceFilter filter = pkg.getWorkspaceFilter();
        for (final PathFilterSet filterSet : filter.getFilterSets()) {
            roots.add(filterSet.getRoot());
        }
        info.setFilterRoots(roots);
        return info;
    }

    public @NotNull Result<InputStream> download(@NotNull final RegisteredPackage pkg) throws IOException {
        final VaultPackage vaultPackage = pkg.getPackage();
        final File file = vaultPackage.getFile();
        if (file == null || !file.exists()) {
            return new Result<>(SC_NOT_FOUND);
        }
        final String filename = pkg.getId().getDownloadName();
        final InputStream stream;
        try {
            stream = new FileInputStream(file);
        } catch (FileNotFoundException ex) {
            return new Result<>(SC_NOT_FOUND);
        }
        final Result<InputStream> result = new Result<>(SC_OK, JcrPackageOperations.ZIP_TYPE, stream);
        result.setHeader(Common.HTTP_CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return result;
    }

    public void remove(@NotNull final PackageId id) throws IOException {
        final PackageRegistry registry = registryOf(id);
        if (registry != null) {
            try {
                registry.remove(id);
            } catch (NoSuchPackageException ignore) {
            }
        }
    }

    public @NotNull JcrPackageOperations.OperationLog install(@NotNull final Session session, @NotNull final PackageId id)
            throws IOException, PackageException {
        return execute(session, id, PackageTask.Type.INSTALL);
    }

    public @NotNull JcrPackageOperations.OperationLog uninstall(@NotNull final Session session, @NotNull final PackageId id)
            throws IOException, PackageException {
        return execute(session, id, PackageTask.Type.UNINSTALL);
    }

    private @NotNull JcrPackageOperations.OperationLog execute(@NotNull final Session session, @NotNull final PackageId id,
                                                               @NotNull final PackageTask.Type type)
            throws IOException, PackageException {
        final JcrPackageOperations.OperationLog log = new JcrPackageOperations.OperationLog();
        final PackageRegistry registry = registryOf(id);
        if (registry == null) {
            log.onError(ProgressTrackerListener.Mode.TEXT, id.toString(), new IOException("package not found: " + id));
            return log;
        }
        final ExecutionPlan plan = registry.createExecutionPlan()
                .with(session)
                .with(log)
                .addTask().with(id).with(type)
                .validate()
                .execute();
        if (plan.hasErrors()) {
            for (final PackageTask task : plan.getTasks()) {
                final Throwable error = task.getError();
                if (error != null) {
                    LOG.error("registry task {} on {} failed", task.getType(), task.getPackageId(), error);
                    log.onError(ProgressTrackerListener.Mode.TEXT, id.toString(),
                            error instanceof Exception ? (Exception) error : new IOException(error));
                }
            }
        }
        return log;
    }
}
