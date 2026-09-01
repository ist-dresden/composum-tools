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
    protected String group;
    protected String name;
    protected String version;
    protected String description;
    protected long size;
    protected boolean installed;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar created;
    protected String createdBy;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastModified;
    protected String lastModifiedBy;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastUnpacked;
    protected String lastUnpackedBy;
    protected String[] dependencies;
    protected Set<String> filterRoots;
}
