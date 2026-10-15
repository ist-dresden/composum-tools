package com.composum.sling.tools;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

/** Provides the platform specific settings and resource handling rules of the tools. */
public interface PlatformConfig {

    /**
     * Checks whether the tools are usable in the context of the given request.
     *
     * @param request the current request
     * @return {@code true} if the tools are allowed for the request
     */
    boolean toolsAllowed(@NotNull SlingHttpServletRequest request);

    /**
     * The resource types treated as binary/file-like.
     *
     * @return the file-like resource types
     */
    @NotNull Collection<String> fileTypes();

    /**
     * The display name of the given resource; a content child (jcr:content) is named after its parent.
     *
     * @param resource the resource to name, may be {@code null}
     * @return the name of the resource, or {@code null} if there is no resource
     */
    @Nullable String nameOf(@Nullable Resource resource);

    /**
     * The content resource of the given resource, e.g. the jcr:content child of a page or file.
     *
     * @param resource the resource to get the content for, may be {@code null}
     * @return the content resource, or the given resource if it has no separate content resource
     */
    @Nullable Resource contentOf(@Nullable Resource resource);

    /**
     * The resource representing the original (editable) variant of the given resource.
     *
     * @param resource the resource to get the original for, may be {@code null}
     * @return the original resource
     */
    @Nullable Resource originalOf(@Nullable Resource resource);

    /**
     * The run modes of the current Sling instance.
     *
     * @return the active run modes
     */
    @NotNull Set<String> runmodes();

    /**
     * The known JCR primary node type names offered as autocomplete suggestions when editing the
     * well-known {@code jcr:primaryType} property (see {@code com.composum.sling.changes.Changes}'
     * 'primaryTypeSuggest' route) - a static, admin-curated list rather than a live
     * {@code NodeTypeManager} lookup (JCR-only API, deliberately not used anywhere in this
     * resolver-API-only project). Deliberately a separate list from {@link #mixinTypes()}: the two
     * are disjoint value ranges (a primary type can never be assigned as a mixin, or vice versa),
     * and offering one's names while editing the other would just be confusing.
     *
     * @return the primary node type names to suggest
     */
    @NotNull Collection<String> primaryTypes();

    /**
     * The known JCR mixin node type names offered as autocomplete suggestions when editing the
     * well-known {@code jcr:mixinTypes} property (see {@code com.composum.sling.changes.Changes}'
     * 'mixinTypeSuggest' route) - see {@link #primaryTypes()} for why this is a separate list.
     *
     * @return the mixin node type names to suggest
     */
    @NotNull Collection<String> mixinTypes();
}
