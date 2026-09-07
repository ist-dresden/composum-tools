package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

public interface PlatformConfig {

    boolean toolsAllowed(@NotNull SlingHttpServletRequest request);

    @NotNull Collection<String> fileTypes();

    @Nullable String nameOf(@Nullable Resource resource);

    @Nullable Resource contentOf(@Nullable Resource resource);

    @Nullable Resource originalOf(@Nullable Resource resource);

    @NotNull Set<String> runmodes();

    /**
     * The known JCR primary node type names offered as autocomplete suggestions when editing the
     * well-known {@code jcr:primaryType} property (see {@code com.composum.sling.changes.Changes}'
     * 'primaryTypeSuggest' route) - a static, admin-curated list rather than a live
     * {@code NodeTypeManager} lookup (JCR-only API, deliberately not used anywhere in this
     * resolver-API-only project). Deliberately a separate list from {@link #mixinTypes()}: the two
     * are disjoint value ranges (a primary type can never be assigned as a mixin, or vice versa),
     * and offering one's names while editing the other would just be confusing.
     */
    @NotNull Collection<String> primaryTypes();

    /**
     * The known JCR mixin node type names offered as autocomplete suggestions when editing the
     * well-known {@code jcr:mixinTypes} property (see {@code com.composum.sling.changes.Changes}'
     * 'mixinTypeSuggest' route) - see {@link #primaryTypes()} for why this is a separate list.
     */
    @NotNull Collection<String> mixinTypes();
}
