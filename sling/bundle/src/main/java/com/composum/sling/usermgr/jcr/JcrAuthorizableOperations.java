package com.composum.sling.usermgr.jcr;

import com.composum.sling.usermgr.AuthorizableInfo;
import com.composum.sling.usermgr.AuthorizableRef;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wraps the Jackrabbit {@link UserManager} API for the User Manager's detail-view, create,
 * delete, enable/disable, password and group-membership operations - the sole backend, unlike
 * {@code com.composum.sling.packages.jcr.JcrPackageOperations}'s registry-mode counterpart, there
 * is nothing else to abstract over here.
 */
public class JcrAuthorizableOperations {

    public @Nullable UserManager userManager(@Nullable final Session session) throws RepositoryException {
        return session instanceof JackrabbitSession ? ((JackrabbitSession) session).getUserManager() : null;
    }

    /**
     * Opens the authorizable at the given path.
     *
     * @return the opened authorizable, or 'null' if no such authorizable exists at that path
     */
    public @Nullable Authorizable open(@NotNull final UserManager userManager, @NotNull final String path)
            throws RepositoryException {
        return userManager.getAuthorizableByPath(path);
    }

    /**
     * Opens the authorizable at the given path, resolving the {@link UserManager} from the
     * session itself - the overload the {@code UserManager} plugin class calls, so that class
     * (whose own name collides with this interface's) never needs to name the Jackrabbit
     * {@link UserManager} type at all.
     *
     * @return the opened authorizable, or 'null' if no such authorizable exists at that path or
     * the session is not a {@link JackrabbitSession}
     */
    public @Nullable Authorizable open(@NotNull final Session session, @NotNull final String path)
            throws RepositoryException {
        final UserManager userManager = userManager(session);
        return userManager != null ? userManager.getAuthorizableByPath(path) : null;
    }

    public @NotNull AuthorizableInfo info(@NotNull final Authorizable authorizable) throws RepositoryException {
        final AuthorizableInfo info = new AuthorizableInfo();
        info.setPath(authorizable.getPath());
        info.setId(authorizable.getID());
        info.setPrincipalName(authorizable.getPrincipal().getName());
        info.setGroups(declaredMemberOf(authorizable));
        if (authorizable instanceof Group) {
            info.setType("group");
            info.setMembers(declaredMembers((Group) authorizable));
        } else {
            final User user = (User) authorizable;
            info.setSystemUser(user.isSystemUser());
            info.setType(info.isSystemUser() ? "system-user" : "user");
            info.setDisabled(user.isDisabled());
            info.setDisabledReason(user.isDisabled() ? user.getDisabledReason() : null);
        }
        return info;
    }

    public @NotNull List<AuthorizableRef> declaredMemberOf(@NotNull final Authorizable authorizable)
            throws RepositoryException {
        return toRefs(authorizable.declaredMemberOf());
    }

    public @NotNull List<AuthorizableRef> declaredMembers(@NotNull final Group group) throws RepositoryException {
        return toRefs(group.getDeclaredMembers());
    }

    /**
     * Resolves an authorizable by its id (as opposed to {@link #open}, which resolves one by
     * path) - used for the Add-to-Group/Add-Member dialogs, which only ever know the target's id
     * (typed into a text field), not its path.
     *
     * @return the resolved authorizable, or 'null' if no such id exists or the session has no
     * user manager
     */
    public @Nullable Authorizable findById(@NotNull final Session session, @NotNull final String id)
            throws RepositoryException {
        final UserManager userManager = userManager(session);
        return userManager != null ? userManager.getAuthorizable(id) : null;
    }

    public void addToGroup(@NotNull final Session session, @NotNull final Group group,
                           @NotNull final Authorizable member) throws RepositoryException {
        group.addMember(member);
        session.save();
    }

    public void removeFromGroup(@NotNull final Session session, @NotNull final Group group,
                                @NotNull final Authorizable member) throws RepositoryException {
        group.removeMember(member);
        session.save();
    }

    private @NotNull List<AuthorizableRef> toRefs(@NotNull final Iterator<? extends Authorizable> iterator)
            throws RepositoryException {
        final List<AuthorizableRef> result = new ArrayList<>();
        while (iterator.hasNext()) {
            result.add(toRef(iterator.next()));
        }
        return result;
    }

    private @NotNull AuthorizableRef toRef(@NotNull final Authorizable authorizable) throws RepositoryException {
        final String id = authorizable.getID();
        final String type = authorizable instanceof Group ? "group"
                : ((User) authorizable).isSystemUser() ? "system-user" : "user";
        return new AuthorizableRef(id, authorizable.getPath(), type, id);
    }

    /**
     * Resolves the {@link UserManager} from the given session, failing loudly (rather than
     * returning 'null') for the write operations below - unlike a read like {@link #open}, a
     * missing user manager here is a genuine error, not a "not found".
     */
    private @NotNull UserManager requireUserManager(@NotNull final Session session) throws RepositoryException {
        final UserManager userManager = userManager(session);
        if (userManager == null) {
            throw new RepositoryException("No Jackrabbit UserManager available for this session.");
        }
        return userManager;
    }

    /**
     * Creates a new user, saving the session afterward - Oak's {@code UserManager} does not
     * auto-save (see {@link UserManager#isAutoSave()}), unlike the classic in-content
     * {@code JcrPackageManager} operations {@code com.composum.sling.packages.jcr.JcrPackageOperations}
     * wraps. Every write operation below follows the same create-then-save shape.
     *
     * @param intermediatePath where under '/home/users' to create the user, or blank for the
     *                         default (id-hash) location - passing one requires the 4-arg
     *                         {@code UserManager#createUser} overload, which in turn requires an
     *                         explicit {@link Principal} even though the id already implies one
     */
    public @NotNull User createUser(@NotNull final Session session, @NotNull final String id,
                                    @NotNull final String password, @Nullable final String intermediatePath)
            throws RepositoryException {
        final UserManager userManager = requireUserManager(session);
        final User user = StringUtils.isBlank(intermediatePath)
                ? userManager.createUser(id, password)
                : userManager.createUser(id, password, new NamedPrincipal(id), intermediatePath);
        session.save();
        return user;
    }

    public @NotNull User createSystemUser(@NotNull final Session session, @NotNull final String id,
                                          @Nullable final String intermediatePath) throws RepositoryException {
        final UserManager userManager = requireUserManager(session);
        final User user = userManager.createSystemUser(id, StringUtils.trimToNull(intermediatePath));
        session.save();
        return user;
    }

    public @NotNull Group createGroup(@NotNull final Session session, @NotNull final String id,
                                      @Nullable final String intermediatePath) throws RepositoryException {
        final UserManager userManager = requireUserManager(session);
        final Group group = StringUtils.isBlank(intermediatePath)
                ? userManager.createGroup(id)
                : userManager.createGroup(id, new NamedPrincipal(id), intermediatePath);
        session.save();
        return group;
    }

    /** ids that can never be deleted - the same hard block the legacy Nodes 'usermgr' servlet
     * applied; enforced here (not just via button suppression in {@code UserManager#actions}) so
     * a delete request can't slip through even if it bypasses the UI */
    public static final Set<String> PROTECTED_IDS = Set.of("admin", "anonymous");

    public void delete(@NotNull final Session session, @NotNull final Authorizable authorizable)
            throws RepositoryException {
        if (PROTECTED_IDS.contains(authorizable.getID())) {
            throw new RepositoryException("The '" + authorizable.getID() + "' authorizable cannot be deleted.");
        }
        authorizable.remove();
        session.save();
    }

    /** re-enables a disabled user - passing 'null' as the reason is Jackrabbit's own convention
     * for lifting {@code User#disable}, as opposed to {@link #disable} passing a non-null one */
    public void enable(@NotNull final Session session, @NotNull final User user) throws RepositoryException {
        user.disable(null);
        session.save();
    }

    public void disable(@NotNull final Session session, @NotNull final User user, @Nullable final String reason)
            throws RepositoryException {
        // never pass 'null' here - that would re-enable the user instead (see #enable)
        user.disable(StringUtils.defaultString(reason));
        session.save();
    }

    /** admin bypass - no old-password check, matching this plugin's coarse
     * {@code Config#writeEnabled()} trust model (see {@code UserManager}'s class Javadoc) */
    public void changePassword(@NotNull final Session session, @NotNull final User user, @NotNull final String password)
            throws RepositoryException {
        user.changePassword(password);
        session.save();
    }

    /** a minimal, name-only {@link Principal} - all the 4-arg create overloads need is a name */
    private static final class NamedPrincipal implements Principal {

        private final String name;

        NamedPrincipal(@NotNull final String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    private static final String REP_AUTHORIZABLE_ID = "rep:authorizableId";

    /**
     * Finds authorizables whose id contains the given text - an indexed lookup
     * ({@link UserManager#findAuthorizables(String, String, int)}), unlike
     * {@link com.composum.sling.usermgr.jcr.JcrAuthorizableTree#immediateChildren}'s plain node
     * walk, so this scales to a large '/home' regardless of how deeply an authorizable is
     * nested - the whole reason a "Find" box exists instead of a Package-Manager-style recursive
     * folder listing (see {@code JcrAuthorizableTree}'s class Javadoc).
     *
     * @param session the current session, resolved to a {@link UserManager} internally - the
     *                overload the {@code UserManager} plugin class calls, so that class (whose
     *                own name collides with the Jackrabbit {@link UserManager} interface) never
     *                needs to name that type at all
     * @param text    the (sub-)string to search the authorizable id for
     * @param type    'user', 'group', or 'null'/anything else for both
     * @param limit   the maximum number of results to return
     * @return the matching authorizables, or an empty list if the session has no user manager
     */
    public @NotNull List<AuthorizableRef> find(@NotNull final Session session, @NotNull final String text,
                                               @Nullable final String type, final int limit)
            throws RepositoryException {
        final UserManager userManager = userManager(session);
        if (userManager == null) {
            return List.of();
        }
        final int searchType = "user".equals(type) ? UserManager.SEARCH_TYPE_USER
                : "group".equals(type) ? UserManager.SEARCH_TYPE_GROUP : UserManager.SEARCH_TYPE_AUTHORIZABLE;
        final Iterator<Authorizable> iterator = userManager.findAuthorizables(REP_AUTHORIZABLE_ID, text, searchType);
        final List<AuthorizableRef> result = new ArrayList<>();
        while (iterator.hasNext() && result.size() < limit) {
            result.add(toRef(iterator.next()));
        }
        return result;
    }

    /**
     * The repository paths where an ACL grants or denies a privilege to this authorizable's
     * principal - a read-only diagnostic report, the User Manager equivalent of
     * {@code com.composum.sling.packages.jcr.JcrPackageOperations#coverage}. Two plain JCR-SQL2
     * queries (one per ACE node type - not a single UNION query, which is an Oak-specific
     * extension not guaranteed on every JCR implementation) via
     * {@link ResourceResolver#findResources}, the same query-execution API the Browser module's
     * own Query tool already uses ({@code browser.tool.JcrQuery#find}) rather than the raw JCR
     * {@code QueryManager}. For each hit, the protected path is simply its grandparent - an ACE's
     * parent is the ACL node ('rep:policy'/'rep:repoPolicy'), whose parent is the node the ACL
     * actually controls.
     */
    public @NotNull List<String> affectedPaths(@NotNull final ResourceResolver resolver,
                                               @NotNull final Authorizable authorizable) throws RepositoryException {
        final String principalName = authorizable.getPrincipal().getName();
        final Set<String> paths = new TreeSet<>();
        for (final String aceType : List.of("rep:GrantACE", "rep:DenyACE")) {
            final String query = "SELECT * FROM [" + aceType + "] WHERE [rep:principalName] = " + sql2Literal(principalName);
            final Iterator<Resource> iterator = resolver.findResources(query, "JCR-SQL2");
            while (iterator.hasNext()) {
                final Resource ace = iterator.next();
                final Resource acl = ace.getParent();
                final Resource controlled = acl != null ? acl.getParent() : null;
                if (controlled != null) {
                    paths.add(controlled.getPath());
                }
            }
        }
        return new ArrayList<>(paths);
    }

    private @NotNull String sql2Literal(@NotNull final String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
