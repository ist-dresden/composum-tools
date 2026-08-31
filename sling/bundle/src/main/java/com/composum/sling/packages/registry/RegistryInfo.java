package com.composum.sling.packages.registry;

import com.composum.sling.packages.PackageInfo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Calendar;
import java.util.Set;

/**
 * The detail-panel data of a single registered package (see {@link RegistryOperations#info}) -
 * the {@code PackageRegistry} SPI is read/install/uninstall/remove only, so unlike
 * {@code com.composum.sling.packages.jcr.JcrPackageInfo} there is nothing here to feed an edit dialog.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegistryInfo implements PackageInfo {

    /** the package's id ('group:name:version'), used as the tree/detail path in registry mode */
    protected String path;
    protected String group;
    protected String name;
    protected String version;
    protected String description;
    protected long size;
    protected boolean installed;
    protected Calendar created;
    protected String createdBy;
    protected Calendar lastModified;
    protected String lastModifiedBy;
    protected String[] dependencies;
    protected Set<String> filterRoots;
}
