package com.composum.sling.usermgr.jcr;

import com.composum.sling.usermgr.AffectedPathEntry;
import com.composum.sling.usermgr.AuthorizableInfo;
import com.composum.sling.usermgr.AuthorizableRef;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.Query;
import org.apache.jackrabbit.api.security.user.QueryBuilder;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.ConstraintViolationException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wraps the Jackrabbit {@link UserManager} API and, for ACL-related lookups, the resolver-API
 * JCR-SQL2 query mechanism - the sole backend, unlike
 * {@code com.composum.sling.packages.jcr.JcrPackageOperations}'s registry-mode counterpart, there
 * is nothing else to abstract over here. Covers: detail-view info ({@link #info}), the wildcard
 * ('*'/'?') name search backing both the Add-to-Group/Add-Member autocomplete
 * ({@link #find}) and the search bar's three modes ({@link #directAffectedPaths}/
 * {@link #affectedPathsByPathPattern} - see {@code UserManager#query}'s own Javadoc for what each
 * mode computes); create/delete/enable/disable/password/group-membership writes; the free-form
 * 'profile' child-node property editor ({@link #profileProperties}/{@link #updateProfile}); and
 * the ACL "Affected Paths" report ({@link #affectedPaths}, and its reverse,
 * {@link #affectedPathsByPathPattern}), both built on the same principal-batched
 * {@link #queryAffectedPaths} query helper.
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
            throw new ConstraintViolationException("The '" + authorizable.getID() + "' authorizable cannot be deleted.");
        }
        authorizable.remove();
        session.save();
    }

    /** the three synthetic tree roots ({@code UserManager}'s own three-root layout, see
     * {@link JcrAuthorizableTree}) - never actual "folders" a user created, so
     * {@link #deleteFolder} refuses to remove any of them */
    private static final Set<String> PROTECTED_FOLDERS = Set.of(
            JcrAuthorizableTree.USERS_PATH, JcrAuthorizableTree.SYSTEM_PATH, JcrAuthorizableTree.GROUPS_PATH);

    /**
     * Whether the given path is an existing plain intermediate tree folder
     * ({@code rep:AuthorizableFolder}) rather than a leaf {@link Authorizable} - the delete
     * dialog/action needs to tell the two apart, since a folder is not an {@link Authorizable} at
     * all and {@link #delete} (which operates on one) does not apply to it.
     */
    public boolean isFolder(@NotNull final Session session, @NotNull final String path) throws RepositoryException {
        return session.nodeExists(path)
                && "rep:AuthorizableFolder".equals(session.getNode(path).getPrimaryNodeType().getName());
    }

    /**
     * Deletes the given intermediate folder and everything nested under it - a plain, recursive
     * JCR node removal (a folder is not an {@link Authorizable}, so {@link #delete} does not
     * apply), which silently takes any users/groups/subfolders it contains along with it. Refuses
     * to remove any of the three synthetic tree roots (see {@link #PROTECTED_FOLDERS}) - those
     * are not actual "folders" an admin created, and removing one would strip a whole branch of
     * the authorizable tree out from under the plugin.
     */
    public void deleteFolder(@NotNull final Session session, @NotNull final String path) throws RepositoryException {
        if (PROTECTED_FOLDERS.contains(path)) {
            throw new ConstraintViolationException("The '" + path + "' root folder cannot be deleted.");
        }
        session.getNode(path).remove();
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

    private static final String PROFILE_NODE_NAME = "profile";

    /**
     * A user's 'profile' child node properties - the informal, un-typed name/value convention
     * AEM/Jackrabbit deployments commonly use for display-name/contact information (e.g.
     * 'givenName'/'familyName'/'email'), not a JCR-defined node type with a fixed schema - hence
     * the generic name/value editor this backs (see {@code UserManager#changeProfileDialog}/
     * {@code #changeProfile}), rather than a fixed set of form fields. Only plain, single-value
     * String properties are considered - a profile property is, in practice, always exactly that;
     * 'jcr:*' protected properties (primaryType, created, ...) are always skipped.
     *
     * @return the profile properties, sorted by name - empty if the user has no 'profile' child
     * node at all
     */
    public @NotNull List<AuthorizableInfo.ProfileEntry> profileProperties(@NotNull final Session session,
                                                                           @NotNull final String userPath)
            throws RepositoryException {
        final List<AuthorizableInfo.ProfileEntry> result = new ArrayList<>();
        final String profilePath = userPath + "/" + PROFILE_NODE_NAME;
        if (session.nodeExists(profilePath)) {
            final Node profile = session.getNode(profilePath);
            final Map<String, String> sorted = new TreeMap<>();
            final PropertyIterator iterator = profile.getProperties();
            while (iterator.hasNext()) {
                final Property property = iterator.nextProperty();
                if (!property.getName().startsWith("jcr:")
                        && property.getType() == PropertyType.STRING && !property.isMultiple()) {
                    sorted.put(property.getName(), property.getString());
                }
            }
            sorted.forEach((name, value) -> result.add(new AuthorizableInfo.ProfileEntry(name, value)));
        }
        return result;
    }

    /**
     * Replaces a user's 'profile' child node properties with exactly the given set - removes any
     * existing profile property not present in 'properties', sets/updates the rest; creates the
     * 'profile' child node itself (a plain 'nt:unstructured', the same convention AEM's own user
     * admin UI uses) if it does not exist yet and 'properties' is non-empty. Properties are
     * removed/collected first, then applied, to avoid mutating the live {@link PropertyIterator}
     * mid-walk.
     */
    public void updateProfile(@NotNull final Session session, @NotNull final String userPath,
                              @NotNull final Map<String, String> properties) throws RepositoryException {
        final String profilePath = userPath + "/" + PROFILE_NODE_NAME;
        final boolean exists = session.nodeExists(profilePath);
        if (!exists && properties.isEmpty()) {
            return;
        }
        final Node profile = exists ? session.getNode(profilePath)
                : session.getNode(userPath).addNode(PROFILE_NODE_NAME, "nt:unstructured");
        final List<Property> obsolete = new ArrayList<>();
        final PropertyIterator iterator = profile.getProperties();
        while (iterator.hasNext()) {
            final Property property = iterator.nextProperty();
            if (!property.getName().startsWith("jcr:") && !properties.containsKey(property.getName())) {
                obsolete.add(property);
            }
        }
        try {
            for (final Property property : obsolete) {
                property.remove();
            }
            for (final Map.Entry<String, String> entry : properties.entrySet()) {
                profile.setProperty(entry.getKey(), entry.getValue());
            }
        } catch (RuntimeException ex) {
            // e.g. an IllegalArgumentException for a property name that isn't a valid JCR name
            throw new RepositoryException("The profile could not be updated: " + ex.getMessage(), ex);
        }
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

    /**
     * Finds authorizables whose id or principal name matches the given wildcard pattern - an
     * indexed lookup ({@link UserManager#findAuthorizables(Query)} with
     * {@link QueryBuilder#nameMatches}), unlike
     * {@link com.composum.sling.usermgr.jcr.JcrAuthorizableTree#immediateChildren}'s plain node
     * walk, so this scales to a large '/home' regardless of how deeply an authorizable is
     * nested - the whole reason a "Find" box exists instead of a Package-Manager-style recursive
     * folder listing (see {@code JcrAuthorizableTree}'s class Javadoc).
     *
     * @param session the current session, resolved to a {@link UserManager} internally - the
     *                overload the {@code UserManager} plugin class calls, so that class (whose
     *                own name collides with the Jackrabbit {@link UserManager} interface) never
     *                needs to name that type at all
     * @param pattern the pattern to match the authorizable id/principal name against - '*' for
     *                any run of characters, '?' for a single one (see {@link #wildcardToSqlLike});
     *                a pattern with no wildcard at all is treated as a plain "contains" search,
     *                matching this method's previous, simpler behavior
     * @param type    'user', 'group', or 'null'/anything else for both
     * @param limit   the maximum number of results to return
     * @return the matching authorizables, or an empty list if the session has no user manager
     */
    public @NotNull List<AuthorizableRef> find(@NotNull final Session session, @NotNull final String pattern,
                                               @Nullable final String type, final int limit)
            throws RepositoryException {
        final List<AuthorizableRef> result = new ArrayList<>();
        for (final Authorizable authorizable : findAuthorizables(session, pattern, type, limit)) {
            result.add(toRef(authorizable));
        }
        return result;
    }

    /**
     * The raw candidate lookup {@link #find} converts to {@link AuthorizableRef}s - also used
     * directly by {@link #directAffectedPaths}/{@link #affectedPathsForCandidates}, which need the
     * actual {@link Authorizable} (for its principal name and, in the latter case, its own group
     * memberships), not just the flattened id/label/icon shape {@link #find}'s callers want.
     */
    private @NotNull List<Authorizable> findAuthorizables(@NotNull final Session session, @NotNull final String pattern,
                                                           @Nullable final String type, final int limit)
            throws RepositoryException {
        final UserManager userManager = userManager(session);
        if (userManager == null) {
            return List.of();
        }
        final String likePattern = wildcardToSqlLike(pattern);
        final Class<? extends Authorizable> selector = "user".equals(type) ? User.class
                : "group".equals(type) ? Group.class : null;
        final Iterator<Authorizable> iterator = userManager.findAuthorizables(new Query() {
            @Override
            public <T> void build(@NotNull final QueryBuilder<T> builder) {
                builder.setCondition(builder.nameMatches(likePattern));
                if (selector != null) {
                    builder.setSelector(selector);
                }
                builder.setLimit(0, limit);
            }
        });
        final List<Authorizable> result = new ArrayList<>();
        while (iterator.hasNext() && result.size() < limit) {
            result.add(iterator.next());
        }
        return result;
    }

    /**
     * The search bar's name-pattern mode (with an optional path-pattern narrowing it further): for
     * every authorizable matching the given name wildcard pattern, its own <em>directly</em>
     * configured affected paths - no group-membership inheritance (unlike {@link #affectedPaths}),
     * deliberately: a name-and-path search is meant as a genuine AND of both criteria on the same
     * principal ("who, among these name matches, is themselves directly granted/denied on a
     * matching path") - pulling in each candidate's inherited group rules too made this mode look
     * near-indistinguishable from {@link #affectedPathsByPathPattern}'s own, deliberately broader
     * "anyone at all" search, since group principals ended up dominating both result sets. One
     * principal-batched query pair (see {@link #queryAffectedPaths}) covering every candidate's
     * own principal name at once, regardless of candidate count.
     *
     * @param pathPattern the wildcard pattern (see {@link #wildcardToRegex}) a hit's affected path
     *                     must additionally match, or blank/'null' for the name-pattern-only mode
     */
    public @NotNull List<AffectedPathEntry> directAffectedPaths(@NotNull final ResourceResolver resolver,
                                                                 @NotNull final Session session,
                                                                 @NotNull final String namePattern,
                                                                 @Nullable final String pathPattern,
                                                                 @Nullable final String type, final int limit)
            throws RepositoryException {
        final Map<String, PrincipalInfo> principals = new LinkedHashMap<>();
        for (final Authorizable candidate : findAuthorizables(session, namePattern, type, limit)) {
            principals.put(candidate.getPrincipal().getName(), new PrincipalInfo(candidate));
        }
        final Pattern pathFilter = StringUtils.isNotBlank(pathPattern) ? wildcardToRegex(pathPattern) : null;
        return queryAffectedPaths(resolver, principals, pathFilter, limit);
    }

    /**
     * The search bar's path-pattern-only mode: every ACL rule (any principal at all, including
     * ones that would never turn up in a name search, e.g. a service principal) whose affected
     * path matches the given wildcard pattern - the reverse of {@link #affectedPaths}: that method
     * answers "where does this authorizable's principal have rights", this one answers "who has
     * rights somewhere matching this path". Unlike {@link #queryAffectedPaths} (used by every
     * other mode here), the candidate principal set isn't known up front, so this still has to
     * inspect every 'rep:GrantACE'/'rep:DenyACE' in the whole repository once (there is no index
     * on "the path two ancestors up") - but exactly once each, regardless of how many end up
     * matching, and each matching principal's own path is resolved and cached at most once too.
     */
    public @NotNull List<AffectedPathEntry> affectedPathsByPathPattern(@NotNull final ResourceResolver resolver,
                                                                        @NotNull final Session session,
                                                                        @NotNull final String pathPattern,
                                                                        @Nullable final String type, final int limit)
            throws RepositoryException {
        final UserManager userManager = userManager(session);
        if (userManager == null) {
            return List.of();
        }
        final Pattern pathRegex = wildcardToRegex(pathPattern);
        final Map<String, PrincipalInfo> resolved = new LinkedHashMap<>();
        final List<AffectedPathEntry> result = new ArrayList<>();
        for (final String aceType : List.of("rep:GrantACE", "rep:DenyACE")) {
            final String ruleType = "rep:GrantACE".equals(aceType) ? "grant" : "deny";
            final Iterator<Resource> iterator = resolver.findResources("SELECT * FROM [" + aceType + "]", "JCR-SQL2");
            while (iterator.hasNext() && result.size() < limit) {
                final Resource ace = iterator.next();
                final Resource acl = ace.getParent();
                final Resource controlled = acl != null ? acl.getParent() : null;
                if (controlled == null || !pathRegex.matcher(controlled.getPath()).matches()) {
                    continue;
                }
                final String principalName = ace.getValueMap().get("rep:principalName", String.class);
                if (principalName == null) {
                    continue;
                }
                final PrincipalInfo principal = resolved.computeIfAbsent(principalName,
                        name -> resolvePrincipal(userManager, name, type));
                if (principal != null) {
                    result.add(new AffectedPathEntry(controlled.getPath(), principalName, principal.path,
                            principal.icon, ruleType, formatPrivileges(ace)));
                }
            }
        }
        result.sort(Comparator.comparing(AffectedPathEntry::getPath).thenComparing(AffectedPathEntry::getPrincipal));
        return result;
    }

    /**
     * Resolves a principal name to its own authorizable, honoring 'type' - 'null' (cached the same
     * as any other result, avoiding repeat lookups for the same principal) if it doesn't resolve
     * at all or doesn't match 'type'.
     */
    private @Nullable PrincipalInfo resolvePrincipal(@NotNull final UserManager userManager,
                                                      @NotNull final String principalName, @Nullable final String type) {
        try {
            final Authorizable authorizable = userManager.getAuthorizable(new NamedPrincipal(principalName));
            if (authorizable == null) {
                return null;
            }
            final boolean isGroup = authorizable instanceof Group;
            if (("user".equals(type) && isGroup) || ("group".equals(type) && !isGroup)) {
                return null;
            }
            return new PrincipalInfo(authorizable);
        } catch (RepositoryException ex) {
            return null;
        }
    }

    /** an authorizable's own path plus the Bootstrap Icons name for its type - everything the
     * "Type" and "Principal" columns of an {@link AffectedPathEntry} row need about whichever
     * principal it names, gathered once so {@link #queryAffectedPaths} doesn't need the
     * {@link Authorizable} itself, just this small, already-resolved pair */
    private static final class PrincipalInfo {

        final String path;
        final String icon;

        PrincipalInfo(@NotNull final Authorizable authorizable) throws RepositoryException {
            this.path = authorizable.getPath();
            final String type = authorizable instanceof Group ? "group"
                    : ((User) authorizable).isSystemUser() ? "system-user" : "user";
            this.icon = AuthorizableRef.iconOf(type);
        }
    }

    /**
     * Translates a user-facing wildcard pattern ('*' for any run of characters, '?' for a single
     * one) into the SQL LIKE pattern {@link QueryBuilder#nameMatches} expects ('%'/'_'), escaping
     * any literal use of '%'/'_'/'\' in the input. A pattern with neither '*' nor '?' is treated
     * as an implicit "contains" search (wrapped in a leading/trailing '*') - the common case of
     * just typing part of a name, preserved from this method's simpler predecessor.
     */
    // package-private (not private) so JcrAuthorizableOperationsTest can exercise it directly -
    // pure String logic, no JCR/Sling types involved, the ideal candidate for a real unit test
    static @NotNull String wildcardToSqlLike(@NotNull final String pattern) {
        final String effective = hasWildcard(pattern) ? pattern : "*" + pattern + "*";
        final StringBuilder like = new StringBuilder();
        for (int i = 0; i < effective.length(); i++) {
            final char c = effective.charAt(i);
            switch (c) {
                case '*':
                    like.append('%');
                    break;
                case '?':
                    like.append('_');
                    break;
                case '%':
                case '_':
                case '\\':
                    like.append('\\').append(c);
                    break;
                default:
                    like.append(c);
            }
        }
        return like.toString();
    }

    /**
     * Translates the same user-facing wildcard pattern as {@link #wildcardToSqlLike} into a
     * case-insensitive, full-match {@link Pattern} - used where the candidate value (a repository
     * path here) is already in hand and matched in Java, rather than pushed into a JCR query.
     */
    // package-private, same reasoning as #wildcardToSqlLike
    static @NotNull Pattern wildcardToRegex(@NotNull final String pattern) {
        final String effective = hasWildcard(pattern) ? pattern : "*" + pattern + "*";
        final StringBuilder regex = new StringBuilder();
        for (int i = 0; i < effective.length(); i++) {
            final char c = effective.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    static boolean hasWildcard(@NotNull final String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    }

    /**
     * The repository paths where an ACL grants or denies a privilege to this authorizable's own
     * principal, or to any group it is a (declared or inherited) member of - a read-only
     * diagnostic report, the User Manager equivalent of
     * {@code com.composum.sling.packages.jcr.JcrPackageOperations#coverage}. Including the group
     * memberships is what makes the "Principal" column meaningful (see
     * {@link AffectedPathEntry}'s own Javadoc) and, more importantly, is what actually answers
     * "why does this user have access to X" - a real-world grant is at least as often held by a
     * group the user belongs to as by the user's own principal directly. Two plain JCR-SQL2
     * queries total (one per ACE node type - not a single UNION query, which is an Oak-specific
     * extension not guaranteed on every JCR implementation, and not one query per candidate
     * principal either, which would not scale with group membership count), each with an
     * OR-chained '[rep:principalName] = ...' condition covering every candidate principal at
     * once, via {@link ResourceResolver#findResources}, the same query-execution API the Browser
     * module's own Query tool already uses ({@code browser.tool.JcrQuery#find}) rather than the
     * raw JCR {@code QueryManager}. For each hit, the protected path is simply its grandparent -
     * an ACE's parent is the ACL node ('rep:policy'/'rep:repoPolicy'), whose parent is the node
     * the ACL actually controls.
     */
    public @NotNull List<AffectedPathEntry> affectedPaths(@NotNull final ResourceResolver resolver,
                                                           @NotNull final Authorizable authorizable) throws RepositoryException {
        final Map<String, PrincipalInfo> principals = new LinkedHashMap<>();
        principals.put(authorizable.getPrincipal().getName(), new PrincipalInfo(authorizable));
        final Iterator<Group> groups = authorizable.memberOf();
        while (groups.hasNext()) {
            final Group group = groups.next();
            principals.put(group.getPrincipal().getName(), new PrincipalInfo(group));
        }
        // unbounded - the per-authorizable tab shows everything, unlike the search modes below,
        // which cap their (necessarily broader) result at the same QUERY_LIMIT their candidate
        // lookup already used
        return queryAffectedPaths(resolver, principals, null, Integer.MAX_VALUE);
    }

    /**
     * The actual ACE lookup shared by {@link #affectedPaths}, {@link #directAffectedPaths} and
     * {@link #affectedPathsForCandidates}: exactly two JCR-SQL2 queries total (one per ACE node
     * type), each with an OR-chained '[rep:principalName] = ...' condition covering every entry in
     * 'principalPaths' at once - scales with two queries regardless of how many principals (one
     * authorizable's own plus its groups, or a whole batch of search candidates') are being asked
     * about, not one query per principal. 'pathFilter', if given, additionally restricts hits to a
     * matching affected path (used by the search bar's combined name+path mode; 'null' for the
     * unfiltered per-authorizable tab and the name-only search mode).
     */
    private @NotNull List<AffectedPathEntry> queryAffectedPaths(@NotNull final ResourceResolver resolver,
                                                                 @NotNull final Map<String, PrincipalInfo> principals,
                                                                 @Nullable final Pattern pathFilter, final int limit)
            throws RepositoryException {
        if (principals.isEmpty()) {
            return List.of();
        }
        final String principalCondition = principals.keySet().stream()
                .map(name -> "[rep:principalName] = " + sql2Literal(name))
                .collect(Collectors.joining(" OR "));
        final List<AffectedPathEntry> result = new ArrayList<>();
        for (final String aceType : List.of("rep:GrantACE", "rep:DenyACE")) {
            final String type = "rep:GrantACE".equals(aceType) ? "grant" : "deny";
            final String query = "SELECT * FROM [" + aceType + "] WHERE " + principalCondition;
            final Iterator<Resource> iterator = resolver.findResources(query, "JCR-SQL2");
            while (iterator.hasNext() && result.size() < limit) {
                final Resource ace = iterator.next();
                final Resource acl = ace.getParent();
                final Resource controlled = acl != null ? acl.getParent() : null;
                final String principalName = ace.getValueMap().get("rep:principalName", String.class);
                final PrincipalInfo principal = principalName != null ? principals.get(principalName) : null;
                if (controlled != null && principalName != null && principal != null
                        && (pathFilter == null || pathFilter.matcher(controlled.getPath()).matches())) {
                    result.add(new AffectedPathEntry(controlled.getPath(), principalName, principal.path,
                            principal.icon, type, formatPrivileges(ace)));
                }
            }
        }
        result.sort(Comparator.comparing(AffectedPathEntry::getPath).thenComparing(AffectedPathEntry::getPrincipal));
        return result;
    }

    /**
     * A single, ready-to-display string for one 'rep:GrantACE'/'rep:DenyACE' node's own
     * privileges plus any restrictions (e.g. 'rep:glob', 'rep:ntNames', 'rep:subtrees', ...) -
     * every property other than the well-known 'jcr:primaryType'/'rep:privileges'/
     * 'rep:principalName' ones is treated generically as a restriction, matching the legacy
     * Composum Nodes tool's own "AC rule" formatting (no fixed restriction-name list to maintain).
     * Restrictions are read from two places: any legacy-format restriction stored directly on the
     * ACE node itself, and (Oak's current format, used by every ACE actually carrying a
     * restriction on a reasonably current repository) a 'rep:restrictions' child node's own
     * properties - without the latter, restrictions silently never showed up at all.
     */
    private @NotNull String formatPrivileges(@NotNull final Resource ace) {
        final ValueMap values = ace.getValueMap();
        final String[] privileges = values.get("rep:privileges", String[].class);
        final StringBuilder result = new StringBuilder(privileges != null ? String.join(", ", privileges) : "");
        final List<String> restrictions = new ArrayList<>();
        collectRestrictions(values, restrictions);
        final Resource restrictionsNode = ace.getChild("rep:restrictions");
        if (restrictionsNode != null) {
            collectRestrictions(restrictionsNode.getValueMap(), restrictions);
        }
        if (!restrictions.isEmpty()) {
            result.append(" (").append(String.join(", ", restrictions)).append(")");
        }
        return result.toString();
    }

    private void collectRestrictions(@NotNull final ValueMap values, @NotNull final List<String> restrictions) {
        for (final String name : values.keySet()) {
            if (!name.startsWith("jcr:") && !"rep:privileges".equals(name) && !"rep:principalName".equals(name)) {
                restrictions.add(name + "=" + formatRestrictionValue(values.get(name)));
            }
        }
    }

    // package-private, same reasoning as #wildcardToSqlLike
    @NotNull String formatRestrictionValue(@Nullable final Object value) {
        if (value instanceof Object[]) {
            final Object[] values = (Object[]) value;
            final String[] strings = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                strings[i] = String.valueOf(values[i]);
            }
            return "[" + String.join(",", strings) + "]";
        }
        return String.valueOf(value);
    }

    private @NotNull String sql2Literal(@NotNull final String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
