package com.composum.sling.packages.registry;

import com.composum.sling.packages.PackageInfo;
import com.fasterxml.jackson.annotation.JsonFormat;
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
    /** the package group */
    protected String group;
    /** the package name */
    protected String name;
    /** the package version */
    protected String version;
    /** the package description */
    protected String description;
    /** the package size in bytes */
    protected long size;
    /** 'true' if the package is currently installed */
    protected boolean installed;
    /** the creation time of the package */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar created;
    /** the user that created the package */
    protected String createdBy;
    /** the time of the last modification of the package */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastModified;
    /** the user that last modified the package */
    protected String lastModifiedBy;
    /** the time the package was last unpacked (installed) */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastUnpacked;
    /** the user that last unpacked (installed) the package */
    protected String lastUnpackedBy;
    /** the package dependencies as strings */
    protected String[] dependencies;
    /** the root paths of the package's workspace filter */
    protected Set<String> filterRoots;

    /** Default constructor. */
    public RegistryInfo() {
    }
}
