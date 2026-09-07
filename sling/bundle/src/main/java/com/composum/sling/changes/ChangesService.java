package com.composum.sling.changes;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;

/**
 * The public capability a consuming plugin (currently only {@code browser.Browser}) can
 * optionally bind to get CRXDE-like staged node-modification support (create/delete/move/copy,
 * property changes, all pending until an explicit Save All/Revert All) for free, without taking
 * on a hard dependency: bind it via an {@code OPTIONAL}/{@code DYNAMIC} {@code @Reference}, and
 * fall back to plain read-only behaviour whenever it is unbound (i.e. whenever the {@link Changes}
 * component itself is not configured/active - see its class Javadoc for why that is the deliberate
 * single on/off switch for the whole feature). Package Manager and User Manager intentionally do
 * not use this at all and keep their own, independent, immediately-committed mutation logic.
 */
public interface ChangesService {

    /**
     * The resolver to resolve/read resources through for the given request - the current staged
     * editing session's resolver (reflecting every pending, not-yet-committed change) if one
     * exists for this request's HTTP session, otherwise a resolver equivalent to
     * {@code request.getResourceResolver()}. A consuming plugin's own read paths (its tree, its
     * property listing, ...) should resolve through this instead of the request's own resolver
     * directly, so a user's own pending edits are visible immediately, before Save All.
     */
    @NotNull ResourceResolver resolver(@NotNull SlingHttpServletRequest request);

    /**
     * Whether mutating actions are currently allowed (a runtime pause independent of whether this
     * service is bound at all - see {@code Changes.Config#writeEnabled()}). A consuming plugin
     * should hide its own edit-triggering UI when this is 'false', not just when the service is
     * altogether unbound.
     */
    boolean writeEnabled();

    /**
     * The base URL for this service's on-demand dialog fragments (append '&lt;name&gt;.html' plus
     * the target path) - see the shared {@code CPM.Dialog} client framework.
     */
    @NotNull String dialogUri();

    /**
     * The URL of the pending-changes panel fragment (Save All / Revert All).
     */
    @NotNull String pendingUri();

    /**
     * The number of changes currently pending for this request's HTTP session (0 if none are
     * pending, or if no editing session exists for it yet) - a consuming plugin's own initial page
     * render should use this to pre-set its "Pending Changes" badge correctly (e.g. see
     * {@code changes/navbar/badge.html}), since the badge otherwise only ever updates reactively in
     * response to a mutation made *during* the current page's lifetime and would - wrongly - start
     * back at "no pending changes" after every page reload, even though the session (and its
     * pending changes) is unaffected by a reload and still very much there.
     */
    int pendingCount(@NotNull SlingHttpServletRequest request);

    /**
     * The classpath resource path (usable in a '${src:...}' placeholder) of this service's own
     * client stylesheet - a consuming plugin should link it in whenever this service is bound.
     */
    @NotNull String styleResource();

    /**
     * The classpath resource path (usable in a '${src:...}' placeholder) of this service's own
     * client script - a consuming plugin should include it whenever this service is bound.
     */
    @NotNull String scriptResource();

    /**
     * The full POST action URL for the given action name (append the target path yourself) - e.g.
     * {@code actionLink("copy")} for a "Paste" button, which has no dialog of its own.
     */
    @NotNull String actionLink(@NotNull String action);

    /**
     * Whether the given property name is one of the well-known, repository-managed properties
     * (primary type, identity, versioning, locking, ...) excluded from editing - a consuming
     * plugin's own display (e.g. a properties table) should use this to decide whether to offer an
     * edit affordance for a given property row.
     */
    boolean isProtectedProperty(@NotNull String name);
}
