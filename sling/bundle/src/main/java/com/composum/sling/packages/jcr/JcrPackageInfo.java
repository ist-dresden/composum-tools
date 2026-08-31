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
    protected String group;
    protected String name;
    protected String version;
    protected String description;
    protected long size;
    protected boolean installed;
    protected boolean valid;
    protected boolean sealed;
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

    // additional fields only needed to pre-fill the Update dialog
    protected String acHandling;
    protected boolean requiresRestart;
    protected boolean requiresRoot;
    protected String[] replaces;
    protected String providerName;
    protected String providerUrl;
    protected String providerLink;
    protected String testedWith;
    protected String dependenciesText;
    protected String replacesText;
}
