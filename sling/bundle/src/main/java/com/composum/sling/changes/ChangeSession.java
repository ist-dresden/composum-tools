package com.composum.sling.changes;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;
import java.util.ArrayList;
import java.util.List;

/**
 * A staged, multi-request editing session for {@link Changes}' node-modification actions -
 * analogous to AEM CRXDE's pending-changes tray: every mutation (create/delete/move/copy/property
 * change) is applied to one long-lived {@link ResourceResolver} (so a consuming plugin's own
 * reads, via {@link ChangesService#resolver}, can show the pending state immediately) without
 * persisting it, and collected as a {@link Change} log entry, until an explicit
 * {@link #commit}/{@link #discard}.
 * <p>
 * Held as an {@link HttpSession} attribute, created lazily on the first mutating request and
 * removed again right after commit/discard - it never lingers around empty, so a plain read-only
 * browsing session never pays for one. Implements {@link HttpSessionBindingListener} itself so an
 * abandoned or invalidated HTTP session can never leak a live resolver: removal from the
 * {@code HttpSession} (explicit, or via session invalidation/expiry) always reverts and closes it.
 * Package-private - only {@link Changes} itself needs to know this exists; every other plugin
 * only ever sees the {@link ChangesService} interface.
 */
class ChangeSession implements HttpSessionBindingListener {

    private static final String ATTRIBUTE = ChangeSession.class.getName();

    private final ResourceResolver resolver;
    private final List<Change> changes = new ArrayList<>();

    private ChangeSession(@NotNull final ResourceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * The editing session for the current HTTP session, if one exists (or, if requested and none
     * exists yet, a freshly created one, cloning the request's own resolver so it starts out with
     * the same authentication context but independent, transient change tracking).
     *
     * @param request        the current request
     * @param createIfAbsent whether to create a new session if none exists yet
     * @return the editing session, or 'null' if none exists and 'createIfAbsent' is 'false'
     */
    static @Nullable ChangeSession get(@NotNull final SlingHttpServletRequest request, final boolean createIfAbsent) {
        final HttpSession httpSession = request.getSession(createIfAbsent);
        if (httpSession == null) {
            return null;
        }
        ChangeSession session;
        try {
            session = (ChangeSession) httpSession.getAttribute(ATTRIBUTE);
        } catch (ClassCastException ex) {
            // a stale instance from a previous version of this bundle (e.g. left over across a
            // redeploy) - same class name, different classloader, so it fails to cast even though
            // it "is" a ChangeSession. Discard it and start fresh; removeAttribute still correctly
            // reverts/closes its resolver via #valueUnbound, since that dispatch only needs the
            // shared servlet-API HttpSessionBindingListener interface, not this bundle's class.
            try {
                httpSession.removeAttribute(ATTRIBUTE);
            } catch (RuntimeException ignore) {
                // best-effort cleanup of a stale, previous-bundle-version instance - nothing more
                // to do if even that fails
            }
            session = null;
        }
        if (session == null && createIfAbsent) {
            try {
                session = new ChangeSession(request.getResourceResolver().clone(null));
            } catch (LoginException ex) {
                throw new IllegalStateException("cannot open an editing session", ex);
            }
            httpSession.setAttribute(ATTRIBUTE, session);
        }
        return session;
    }

    /**
     * Persists every pending change and ends the editing session (equivalent to CRXDE's "Save
     * All"). A no-op if no editing session exists.
     */
    static void commit(@NotNull final SlingHttpServletRequest request) throws PersistenceException {
        final ChangeSession session = get(request, false);
        if (session != null) {
            session.resolver.commit();
            // the resolver has no pending changes anymore at this point, so the revert() that
            // #valueUnbound performs below (triggered by the attribute removal) is a harmless no-op
            discard(request);
        }
    }

    /**
     * Discards every pending change and ends the editing session (equivalent to CRXDE's "Revert
     * All"). A no-op if no editing session exists.
     */
    static void discard(@NotNull final SlingHttpServletRequest request) {
        final HttpSession httpSession = request.getSession(false);
        if (httpSession != null) {
            httpSession.removeAttribute(ATTRIBUTE);
        }
    }

    /**
     * This session's resolver - every mutation (and, while this session exists, every resource
     * read that should reflect the pending state) must go through this one, not
     * {@code request.getResourceResolver()}.
     */
    @NotNull ResourceResolver resolver() {
        return resolver;
    }

    /**
     * Appends one entry to the pending-changes log.
     *
     * @param action a short human label, e.g. "Created", "Deleted", "Moved", "Copied", "Changed"
     * @param path   the affected resource's path
     * @param detail additional detail (see {@link Change}), or 'null' if there is none
     */
    void log(@NotNull final String action, @NotNull final String path, @Nullable final String detail) {
        changes.add(new Change(action, path, detail));
    }

    /**
     * The pending-changes log, in the order the changes were made.
     */
    @NotNull List<Change> pendingChanges() {
        return List.copyOf(changes);
    }

    @Override
    public void valueBound(@NotNull final HttpSessionBindingEvent event) {
        // no-op - nothing to do when this instance is attached to the HttpSession
    }

    @Override
    public void valueUnbound(@NotNull final HttpSessionBindingEvent event) {
        if (resolver.isLive()) {
            try {
                resolver.revert();
            } finally {
                resolver.close();
            }
        }
    }
}
