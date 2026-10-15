package com.composum.sling.packages;

import java.util.Calendar;
import java.util.Set;

/**
 * The common detail data of a single package version, independent of the backing store.
 */
public interface PackageInfo {
    /**
     * The package's path relative to the package root.
     *
     * @return the package's path relative to the package root
     */
    String getPath();

    /**
     * The package group.
     *
     * @return the package group
     */
    String getGroup();

    /**
     * The package name.
     *
     * @return the package name
     */
    String getName();

    /**
     * The package version.
     *
     * @return the package version
     */
    String getVersion();

    /**
     * The package description.
     *
     * @return the package description
     */
    String getDescription();

    /**
     * The package size in bytes.
     *
     * @return the package size in bytes
     */
    long getSize();

    /**
     * Whether the package is installed.
     *
     * @return {@code true} if the package is installed
     */
    boolean isInstalled();

    /**
     * The creation time.
     *
     * @return the creation time
     */
    Calendar getCreated();

    /**
     * The user who created the package.
     *
     * @return the user who created the package
     */
    String getCreatedBy();

    /**
     * The time of the last modification.
     *
     * @return the time of the last modification
     */
    Calendar getLastModified();

    /**
     * The user who last modified the package.
     *
     * @return the user who last modified the package
     */
    String getLastModifiedBy();

    /**
     * The time the package was last unpacked (installed).
     *
     * @return the time the package was last unpacked (installed)
     */
    Calendar getLastUnpacked();

    /**
     * The user who last unpacked (installed) the package.
     *
     * @return the user who last unpacked (installed) the package
     */
    String getLastUnpackedBy();

    /**
     * The package dependencies.
     *
     * @return the package dependencies
     */
    String[] getDependencies();

    /**
     * The root paths of the package's workspace filter.
     *
     * @return the root paths of the package's workspace filter
     */
    Set<String> getFilterRoots();
}
