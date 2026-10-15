package com.composum.sling.packages.jcr;

import com.composum.sling.packages.PackageInfo;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Calendar;
import java.util.Set;

/**
 * The detail-panel data of a single installed JCR package version (see
 * {@link JcrPackageOperations#info}).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JcrPackageInfo implements PackageInfo {

    /** the package's path relative to the package root, e.g. '/my/group/my-package-1.0.zip' */
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
    /** whether the package is installed */
    protected boolean installed;
    /** whether the package is valid */
    protected boolean valid;
    /** whether the package is sealed */
    protected boolean sealed;
    /** the creation time */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar created;
    /** the user who created the package */
    protected String createdBy;
    /** the time of the last modification */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastModified;
    /** the user who last modified the package */
    protected String lastModifiedBy;
    /** the time the package was last unpacked (installed) */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    protected Calendar lastUnpacked;
    /** the user who last unpacked (installed) the package */
    protected String lastUnpackedBy;
    /** the package dependencies */
    protected String[] dependencies;
    /** the root paths of the package's workspace filter */
    protected Set<String> filterRoots;

    // additional fields only needed to pre-fill the Update dialog
    /** the access control handling mode */
    protected String acHandling;
    /** whether installing the package requires a restart */
    protected boolean requiresRestart;
    /** whether installing the package requires administrator rights */
    protected boolean requiresRoot;
    /** the packages replaced by this package */
    protected String[] replaces;
    /** the name of the package provider */
    protected String providerName;
    /** the URL of the package provider */
    protected String providerUrl;
    /** the link to the package at the provider */
    protected String providerLink;
    /** the platform version(s) the package was tested with */
    protected String testedWith;
    /** the dependencies as editable text */
    protected String dependenciesText;
    /** the replaced packages as editable text */
    protected String replacesText;

    /**
     * Default constructor.
     */
    public JcrPackageInfo() {
    }
}
