package com.composum.sling.packages.jcr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * One filter root of a package's workspace filter, for the Filters dialog: the root path, its
 * import mode, and its include/exclude rules as one '+ pattern' / '- pattern' line per rule (see
 * {@link JcrPackageOperations#filterRootDetails}/{@link JcrPackageOperations#setFilters}).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterRootInfo {

    protected String root;
    protected String importMode;
    protected String rulesText;
}
