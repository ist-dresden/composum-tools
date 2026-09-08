package com.composum.sling.tools;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * The framework-wide service for everything related to how one resource relates to (or is
 * referenced by) others:
 * <ul>
 * <li>{@link #getSupertypeChain}/{@link #getRelatedPathSet}, the resource-type/override/overlay
 * related-paths logic (originally {@code browser.impl.RelatedPaths}, migrated here so it is usable
 * outside the Browser plugin - the computation itself now lives entirely in this service's
 * implementation, with no separate public type of its own);</li>
 * <li>{@link #findReferences}/{@link #updateReferences}, resolver-API-only discovery and
 * rewriting of resources that reference a given path - either through a plain path/reference/
 * string-typed property value, or embedded as a link in a rich-text-like string property (an
 * {@code ="path"} pattern, e.g. an {@code href}/{@code src} attribute) - primarily meant for a
 * consuming plugin's own "adjust references" option after a node move (see
 * {@code com.composum.sling.changes.Changes}), but generic enough for any similar use.</li>
 * </ul>
 */
public interface ReferencesService {

    /**
     * The chain of resource super types for the given resource (see
     * {@code sling:resourceSuperType}) - also included for content resources since this is used
     * quite often in AEM.
     */
    @NotNull List<RelatedPath> getSupertypeChain(@NotNull Resource resource);

    /**
     * The set of related paths for the given resource: for a resource type, the resource type
     * found in the search path and /mnt/(override|overlay); otherwise the override/overlay/
     * resource-type locations relevant to the resource itself.
     */
    @NotNull List<RelatedPath> getRelatedPathSet(@NotNull Resource resource);

    /**
     * Finds every resource under 'searchRoot' with at least one property (plain-valued, or
     * embedded in a rich-text-like string) referencing 'path' or one of its descendants. Uses a
     * full-text search query (via {@link ResourceResolver#findResources}) to find candidates
     * efficiently, then confirms each candidate by inspecting its own properties directly - so a
     * result is exact, even though the initial query is a fuzzy, index-backed pre-filter.
     *
     * @param resolver   the resolver (and its search index) to search with
     * @param searchRoot the repository root path to search under; blank means the whole repository
     * @param path       the path to find references to (an exact-value or rich-text-embedded match
     *                   for it, or for a descendant of it, both count)
     * @return the matching resources, each with the names of its matching properties
     */
    @NotNull List<Hit> findReferences(@NotNull ResourceResolver resolver, @NotNull String searchRoot,
                                      @NotNull String path);

    /**
     * Rewrites every occurrence of 'oldPath' (or a descendant of it) found in 'hit' (from a
     * previous {@link #findReferences} call against that same 'oldPath') to the corresponding path
     * under 'newPath' - a plain-valued match is replaced outright, a rich-text-embedded match keeps
     * the rest of its surrounding text unchanged. The caller is responsible for the resolver's own
     * commit/discard lifecycle (this only stages the property changes via
     * {@link org.apache.sling.api.resource.ModifiableValueMap}).
     */
    void updateReferences(@NotNull Hit hit, @NotNull String oldPath, @NotNull String newPath)
            throws PersistenceException;

    /**
     * One resource with at least one property matching a {@link #findReferences} search - named
     * 'Hit', not 'Reference', to avoid colliding with the (far more pervasive in this codebase)
     * OSGi {@code @Reference} annotation's simple name once a class both implements this service
     * and injects dependencies via that annotation (as {@code ReferencesServiceImpl} itself does).
     */
    interface Hit {

        /** the resource whose properties reference the searched path. */
        @NotNull Resource resource();

        /** the names of the properties that matched. */
        @NotNull Set<String> propertyNames();
    }
}
