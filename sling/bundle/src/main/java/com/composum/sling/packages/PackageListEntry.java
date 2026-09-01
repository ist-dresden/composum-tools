package com.composum.sling.packages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * One row of the intermediate (group/name) node's "packages under here" list view (see
 * {@code jcr.JcrPackageTree#leavesUnder}/{@code registry.RegistryTree#leavesUnder}) - the
 * package's display label (its tree node names, relative to the listed folder, joined by '/')
 * and its tree path, for navigating straight to it (see 'details/folderEntry.html').
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageListEntry {

    protected final String label;
    protected final String path;

    public PackageListEntry(@NotNull final String label, @NotNull final String path) {
        this.label = label;
        this.path = path;
    }
}
