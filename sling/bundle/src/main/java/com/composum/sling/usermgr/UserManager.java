package com.composum.sling.usermgr;

import com.composum.sling.tools.AbstractToolsPlugin;
import com.composum.sling.tools.Manager;
import com.composum.sling.tools.Result;
import com.composum.sling.tools.ToolsPlugin;
import com.composum.sling.tools.dto.Page;
import com.composum.sling.tools.dto.Widget;
import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import com.composum.sling.tools.template.TemplateContext.Values;
import com.composum.sling.usermgr.jcr.JcrAuthorizableOperations;
import com.composum.sling.usermgr.jcr.JcrAuthorizableTree;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.ResourceResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.http.HttpServletResponse;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.composum.sling.tools.Common.HTML_TYPE;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * The User Manager: browses and manages Jackrabbit users, system users and groups under '/home'.
 * Unlike {@link com.composum.sling.packages.PackageManager}, there is exactly one backend
 * (Jackrabbit's own {@code UserManager}, via {@code ((JackrabbitSession) session).getUserManager()})
 * so this plugin carries none of the mode-abstraction machinery that the two-backend Package
 * Manager needs. Mutating operations (create/delete/enable/disable/password/profile/group
 * membership) are gated by {@link Config#writeEnabled()} and, further, by
 * {@link Config#writePrincipals()} (see {@link AbstractToolsPlugin#isPrincipal}), the same pattern
 * {@code PackageManager}/{@code Changes} use - actual enforcement otherwise relies entirely on the
 * JCR session's own ACLs.
 * <p>
 * <b>Tree</b> ({@link JcrAuthorizableTree}) - a three-root, alphabetically sorted, lazily-loaded
 * tree: 'Users' ('/home/users'), 'System' ('/home/users/system', a plain subfolder promoted to
 * its own root so a large '/home/users' isn't dominated by service accounts) and 'Groups'
 * ('/home/groups'), arbitrarily nested further via intermediate 'rep:AuthorizableFolder' paths.
 * <p>
 * <b>Detail panel</b> - the selected authorizable's details, visually modeled on the Browser's own
 * tab-row-plus-action-toolbar view header (but hardcoded for this plugin's own fixed, type-varying
 * tab set rather than reusing Browser's generic per-resource View plugin system): a <i>Principal</i>
 * tab (id/path/principal/type/status, plus any 'profile' properties - see
 * {@link JcrAuthorizableOperations#profileProperties}); <i>Groups</i> (every user/system-user) and/
 * or <i>Members</i> (every group) for the declared membership relations; and a lazily-loaded
 * <i>Affected Paths</i> tab (see {@link JcrAuthorizableOperations#affectedPaths}) - every ACL rule
 * affecting this authorizable's own principal or any group it (declaratively or transitively)
 * belongs to, as a Principal/Path/Rule table whose Principal and Path cells are themselves
 * navigable (to that principal's own detail view, or to the Browser at that path). An intermediate
 * folder node shows a plain, non-recursive list of its immediate children instead of any of this.
 * <p>
 * <b>Search</b> ({@link #query}) - a permanently visible, two-field bar (name pattern / affected-
 * path pattern, both accepting '*'/'?' wildcards) rendering the same Principal/Path/Rule table as
 * the Affected Paths tab; see that method's own Javadoc for exactly what each of its three modes
 * computes (name only, name-and-path as a genuine AND with no group inheritance, or path only
 * across every principal repository-wide).
 */
@Component(service = {ToolsPlugin.class, UserManager.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = UserManager.Config.class)
public class UserManager extends AbstractToolsPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(UserManager.class);

    public static final String KEY = "users";
    public static final String LABEL = "Users";
    public static final int RANK = 3000;

    private static final String DIALOGS_ROOT = "/sling/usermgr/dialogs/";

    @ObjectClassDefinition(name = "Composum Tools User Manager")
    public @interface Config {

        @AttributeDefinition()
        String key() default UserManager.KEY;

        @AttributeDefinition()
        String label() default UserManager.LABEL;

        @AttributeDefinition()
        int rank() default UserManager.RANK;

        @AttributeDefinition()
        boolean enabled() default true;

        @AttributeDefinition(name = "Write Enabled",
                description = "whether mutating operations (create/delete/enable/disable/password/group membership) are allowed")
        boolean writeEnabled() default true;

        @AttributeDefinition(name = "Write Principals",
                description = "restricts write access to these users/groups (member check), or empty for no restriction")
        String[] writePrincipals() default {};
    }

    /** the manager this plugin is registered with */
    @Reference
    private void bindManager(Manager service) {
        manager = service;
    }

    protected Config config;
    protected JcrAuthorizableOperations jcrOperations;

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        this.config = config;
        this.jcrOperations = new JcrAuthorizableOperations();
        manager.plugins().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        manager.plugins().detach(this);
    }

    @Override
    public @NotNull String key() {
        return Optional.ofNullable(config).map(Config::key).orElse(KEY);
    }

    @Override
    public @NotNull String label() {
        return Optional.ofNullable(config).map(Config::label).orElse(LABEL);
    }

    @Override
    public int rank() {
        return Optional.ofNullable(config).map(Config::rank).orElse(RANK);
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Whether the current request's user may perform mutating operations - the same two-layer
     * check {@code Changes}/{@code PackageManager} use (see {@link AbstractToolsPlugin#isPrincipal}):
     * {@link Config#writeEnabled()} gates it globally, {@link Config#writePrincipals()} optionally
     * narrows it further to a configured set of users/groups.
     */
    protected boolean writeEnabled(@NotNull final SlingHttpServletRequest request) {
        return config.writeEnabled() && isPrincipal(request, config.writePrincipals());
    }

    public @NotNull String pageLink() {
        return manager.serverPath() + "." + key() + ".html";
    }

    protected @NotNull String actionLink(@NotNull final String action) {
        return manager.serverPath() + "." + key() + "." + action + ".json";
    }

    /**
     * The Browser plugin's own page URI, without any path suffix - a plain string built from its
     * well-known selector key ('browser'), the same lightweight cross-plugin linking convention
     * every other plugin-to-plugin URL in this codebase already uses (no service lookup, just the
     * other plugin's known key) - used by the "Affected Paths" tab to link a path to where it can
     * actually be inspected.
     */
    protected @NotNull String browserLink() {
        return manager.serverPath() + ".browser.html";
    }

    @Override
    public @NotNull List<Widget> widgets() {
        return List.of(new Page(key(), label(), rank(), this::pageLink));
    }

    @Override
    public @NotNull Result<?> process(@NotNull final SlingHttpServletRequest request,
                                      @NotNull final SlingHttpServletResponse response,
                                      @NotNull List<String> selectors) {
        switch (request.getMethod()) {
            case "GET":
                return processGet(request, response, selectors);
            case "POST":
                return processPost(request, response, selectors);
            default:
                return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    protected @Nullable Session session(@NotNull final SlingHttpServletRequest request) {
        return request.getResourceResolver().adaptTo(Session.class);
    }

    protected @NotNull String targetPath(@NotNull final SlingHttpServletRequest request) {
        final RequestPathInfo pathInfo = request.getRequestPathInfo();
        return Optional.ofNullable(pathInfo.getSuffix()).filter(p -> !p.isEmpty()).orElse("/");
    }

    public @NotNull Result<?> processGet(@NotNull final SlingHttpServletRequest request,
                                         @NotNull final SlingHttpServletResponse response,
                                         @NotNull List<String> selectors) {
        switch (Manager.consume(selectors, "")) {
            case "resource":
                return resource(request);
            case "tree":
                return treeNode(request);
            case "ancestors":
                return ancestorsOf(request);
            case "view":
                return viewAuthorizable(request);
            case "affectedPathsTab":
                return affectedPathsTab(request);
            case "authorizableIdSuggest":
                return authorizableIdSuggest(request);
            case "query":
                return query(request);
            case "dialog":
                return dialog(request, Manager.consume(selectors, ""));
            default: {
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                        // pre-selects this path in the tree on initial load, see UsersTree
                        .with("users.path", targetPath(request))
                        // request-scoped (unlike everything else the "page" template needs, which
                        // is request-invariant and lives in the static 'templates' map below) -
                        // writeEnabled() now depends on the request's own user, see #writeEnabled
                        .with("users.writeEnabled", writeEnabled(request))
                ), "page"));
                return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
            }
        }
    }

    protected @NotNull Result<?> treeNode(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(SC_INTERNAL_SERVER_ERROR);
            }
            final AuthorizableTreeNode node = new JcrAuthorizableTree(session).nodeAt(targetPath(request));
            return node != null ? new Result<>(node) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The tree (folder) paths leading down to the authorizable addressed by the request's suffix
     * path - lets the client drill a jstree open down to a specific selection (initial page load,
     * browser back/forward navigation), see 'UsersTree#openNode'.
     */
    protected @NotNull Result<?> ancestorsOf(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final List<String> ancestors = session != null
                    ? new JcrAuthorizableTree(session).ancestorsOf(targetPath(request)) : List.of();
            return new Result<>(ancestors);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected static final int QUERY_LIMIT = 25;

    /**
     * Searches by the request's 'text' parameter (id/principal name wildcard pattern) and/or its
     * 'path' parameter (affected-path wildcard pattern), optionally restricted to the 'type'
     * parameter ('user'/'group') - backing the fixed search bar above the detail panel. The result
     * is always a Principal/Path/Rule table (same shape as the "Affected Paths" tab), but computed
     * differently per mode:
     * <ul>
     * <li>'text' given (with or without 'path'): each name-matching authorizable's own
     * <em>direct</em> affected paths, optionally further narrowed to just the ones matching 'path'
     * - a genuine AND of both criteria on the same principal, deliberately with no group-membership
     * inheritance (see {@link JcrAuthorizableOperations#directAffectedPaths}) - inheriting would
     * make this mode near-indistinguishable from the 'path'-only mode below, whose whole point is
     * to show every principal (very often a group) regardless of name.</li>
     * <li>'path' only: every ACL rule anywhere whose affected path matches the pattern, whichever
     * principal it names (see {@link JcrAuthorizableOperations#affectedPathsByPathPattern}).</li>
     * </ul>
     */
    protected @NotNull Result<?> query(@NotNull final SlingHttpServletRequest request) {
        final String text = StringUtils.trimToEmpty(request.getParameter("text"));
        final String path = StringUtils.trimToEmpty(request.getParameter("path"));
        if (text.isEmpty() && path.isEmpty()) {
            return new Result<>(List.of());
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(List.of());
            }
            final String type = request.getParameter("type");
            final ResourceResolver resolver = request.getResourceResolver();
            final List<AffectedPathEntry> result = !text.isEmpty()
                    ? jcrOperations.directAffectedPaths(resolver, session, text, path, type, QUERY_LIMIT)
                    : jcrOperations.affectedPathsByPathPattern(resolver, session, path, type, QUERY_LIMIT);
            return new Result<>(result);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Autocomplete suggestions (plain id strings) for the Add to Group/Add Member dialogs'
     * "authorizableId" text field - deliberately reads the 'path' request parameter, not 'text',
     * the same generic convention the shared, unmodified {@code PathPicker} client widget
     * (sling/tools/script.js) already uses for path and node-type suggestions alike (see
     * {@code Changes#primaryTypeSuggest} for the precedent this follows) - lets that widget serve
     * this case too without knowing the difference. An optional 'type' parameter (see
     * {@link JcrAuthorizableOperations#find}) narrows the suggestions to just groups, for Add to
     * Group's own "authorizableId" field.
     */
    protected @NotNull Result<?> authorizableIdSuggest(@NotNull final SlingHttpServletRequest request) {
        final String text = StringUtils.trimToEmpty(request.getParameter("path"));
        if (text.isEmpty()) {
            return new Result<>(List.of());
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(List.of());
            }
            final List<String> ids = new ArrayList<>();
            for (final AuthorizableRef ref : jcrOperations.find(session, text, request.getParameter("type"), QUERY_LIMIT)) {
                if (ref.getId() != null) {
                    ids.add(ref.getId());
                }
            }
            return new Result<>(ids);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    public @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull List<String> selectors) {
        if (!writeEnabled(request)) {
            return errorResult(SC_FORBIDDEN, "Write operations are disabled.");
        }
        switch (Manager.consume(selectors, "")) {
            case "createUser":
                return createUser(request);
            case "createSystemUser":
                return createSystemUser(request);
            case "createGroup":
                return createGroup(request);
            case "delete":
                return deleteAuthorizable(request);
            case "enable":
                return enableAuthorizable(request);
            case "disable":
                return disableAuthorizable(request);
            case "password":
                return changePassword(request);
            case "changeProfile":
                return changeProfile(request);
            case "addToGroup":
                return changeMembership(request, true);
            case "removeFromGroup":
                return changeMembership(request, false);
            default:
                return new Result<>(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    // Dialogs (loaded on demand, see the shared 'CPM.Dialog' client framework)

    protected @NotNull Result<?> dialog(@NotNull final SlingHttpServletRequest request, @NotNull final String name) {
        switch (name) {
            case "createUser":
                return renderDialog(DIALOGS_ROOT + "createUser.html", new Values()
                        .with("dialog.action", actionLink("createUser")));
            case "createSystemUser":
                return renderDialog(DIALOGS_ROOT + "createSystemUser.html", new Values()
                        .with("dialog.action", actionLink("createSystemUser")));
            case "createGroup":
                return renderDialog(DIALOGS_ROOT + "createGroup.html", new Values()
                        .with("dialog.action", actionLink("createGroup")));
            case "enable":
            case "delete": {
                // both reuse the generic confirm.html - only the message/title differ
                final String title = "enable".equals(name) ? "Enable" : "Delete";
                return authorizableDialog(request, "/sling/tools/dialogs/confirm.html", info -> new Values()
                        .with("dialog.action", actionLink(name) + info.getPath())
                        .with("dialog.title", title + " " + typeLabel(info.getType()))
                        .with("dialog.message", title + " " + typeLabel(info.getType()) + " '" + info.getId() + "'?"));
            }
            case "disable":
                return authorizableDialog(request, DIALOGS_ROOT + "disable.html", info -> new Values()
                        .with("dialog.action", actionLink("disable") + info.getPath()));
            case "password":
                return authorizableDialog(request, DIALOGS_ROOT + "password.html", info -> new Values()
                        .with("dialog.action", actionLink("password") + info.getPath()));
            case "changeProfile":
                return changeProfileDialog(request);
            case "addToGroup":
                return authorizableDialog(request, DIALOGS_ROOT + "addToGroup.html", info -> new Values()
                        .with("dialog.action", actionLink("addToGroup") + info.getPath())
                        .with("dialog.authorizableIdSuggest", actionLink("authorizableIdSuggest")));
            case "addMember":
                return authorizableDialog(request, DIALOGS_ROOT + "addMember.html", info -> new Values()
                        .with("dialog.action", actionLink("addToGroup") + info.getPath())
                        .with("dialog.authorizableIdSuggest", actionLink("authorizableIdSuggest")));
            case "removeFromGroup": {
                // both sides of the relationship are known here: the path-resolved authorizable
                // (the current selection) and 'authorizableId' (the row the Remove button was
                // clicked on) - 'role' says which one is the member and which is the group, same
                // meaning as in #changeMembership
                final String authorizableId = StringUtils.defaultString(request.getParameter("authorizableId"));
                final boolean memberRole = "member".equals(request.getParameter("role"));
                return authorizableDialog(request, "/sling/tools/dialogs/confirm.html", info -> {
                    final String memberLabel = memberRole ? info.getId() : authorizableId;
                    final String groupLabel = memberRole ? authorizableId : info.getId();
                    return new Values()
                            .with("dialog.action", actionLink("removeFromGroup") + info.getPath()
                                    + "?authorizableId=" + urlEncode(authorizableId)
                                    + "&role=" + (memberRole ? "member" : "group"))
                            .with("dialog.title", "Remove Membership")
                            .with("dialog.message", "Remove '" + memberLabel + "' from group '" + groupLabel + "'?");
                });
            }
            default:
                return new Result<>(SC_NOT_FOUND);
        }
    }

    /**
     * The "Affected Paths" tab's content - a Path/Type/Privileges table of every ACL rule that
     * grants or denies a privilege to the addressed authorizable's principal, the User Manager
     * equivalent of Package Manager's Coverage dialog. Lazily loaded (see 'LazyTabPane' in
     * {@code tools/script.js}) on first activating that tab, not eagerly with the rest of the
     * detail view, since it is a full ACL scan ({@link JcrAuthorizableOperations#affectedPaths})
     * not always looked at. Always available, not gated by {@link Config#writeEnabled()}.
     */
    protected @NotNull Result<?> affectedPathsTab(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (authorizable == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            final List<AffectedPathEntry> entries = jcrOperations.affectedPaths(request.getResourceResolver(), authorizable);
            final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                    .with("affectedPaths.entries", entries)
                    .with("affectedPaths.browserUri", browserLink())
            ), "/sling/usermgr/details/affectedPathsTab.html"));
            return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Opens the "Change Profile" dialog for a regular user, pre-filled with the user's current
     * 'profile' properties (see {@link JcrAuthorizableOperations#profileProperties}) - a generic
     * name/value editor, not a fixed set of form fields, since 'profile' properties have no fixed
     * schema (matching the legacy Composum Nodes tool this was ported from). Not reusing
     * {@link #authorizableDialog} since it only exposes {@link AuthorizableInfo}, not the session
     * this also needs.
     */
    protected @NotNull Result<?> changeProfileDialog(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (!(authorizable instanceof User)) {
                return new Result<>(SC_NOT_FOUND);
            }
            return renderDialog(DIALOGS_ROOT + "changeProfile.html", new Values()
                    .with("dialog.action", actionLink("changeProfile") + authorizable.getPath())
                    .with("profile.entries", jcrOperations.profileProperties(session, authorizable.getPath())));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Applies the "Change Profile" dialog's submission - 'name'/'value' are parallel, repeated
     * request parameters (one pair per row, same convention the Changes plugin's own multi-value
     * property editor uses), replacing the user's entire 'profile' property set (see
     * {@link JcrAuthorizableOperations#updateProfile}). A row with a blank name is silently
     * dropped (an unfilled extra "Add" row), matching the same "stray empty row" handling this
     * project's other row-based editors already use.
     */
    protected @NotNull Result<?> changeProfile(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (!(authorizable instanceof User)) {
                return new Result<>(SC_NOT_FOUND);
            }
            final String[] names = request.getParameterValues("name");
            final String[] values = request.getParameterValues("value");
            final Map<String, String> properties = new LinkedHashMap<>();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    final String name = StringUtils.trimToEmpty(names[i]);
                    if (!name.isEmpty()) {
                        properties.put(name, values != null && i < values.length
                                ? StringUtils.defaultString(values[i]) : "");
                    }
                }
            }
            jcrOperations.updateProfile(session, authorizable.getPath(), properties);
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull String urlEncode(@NotNull final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Renders the given dialog template against the authorizable addressed by the request.
     */
    protected @NotNull Result<?> authorizableDialog(@NotNull final SlingHttpServletRequest request,
                                                     @NotNull final String templatePath,
                                                     @NotNull final Function<AuthorizableInfo, Values> values) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (authorizable == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            return renderDialog(templatePath, values.apply(jcrOperations.info(authorizable)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull String typeLabel(@NotNull final String type) {
        switch (type) {
            case "system-user":
                return "System User";
            case "group":
                return "Group";
            default:
                return "User";
        }
    }

    protected @NotNull Result<?> renderDialog(@NotNull final String templatePath, @NotNull final Values values) {
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values().with(values)), templatePath));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    // Write operations

    protected @NotNull Result<?> errorResult(final int statusCode, @Nullable final String message) {
        return new Result<>(statusCode, Map.of("message", StringUtils.defaultString(message, "Request failed.")));
    }

    protected @NotNull Result<?> createUser(@NotNull final SlingHttpServletRequest request) {
        final String id = request.getParameter("id");
        final String password = request.getParameter("password");
        if (StringUtils.isBlank(id) || StringUtils.isBlank(password)) {
            return errorResult(SC_BAD_REQUEST, "Id and password are required.");
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(SC_INTERNAL_SERVER_ERROR);
            }
            final Authorizable authorizable = jcrOperations.createUser(session, id, password,
                    request.getParameter("intermediatePath"));
            return new Result<>(Map.of("path", StringUtils.defaultString(authorizable.getPath())));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> createSystemUser(@NotNull final SlingHttpServletRequest request) {
        final String id = request.getParameter("id");
        if (StringUtils.isBlank(id)) {
            return errorResult(SC_BAD_REQUEST, "Id is required.");
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(SC_INTERNAL_SERVER_ERROR);
            }
            final Authorizable authorizable = jcrOperations.createSystemUser(session, id,
                    request.getParameter("intermediatePath"));
            return new Result<>(Map.of("path", StringUtils.defaultString(authorizable.getPath())));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> createGroup(@NotNull final SlingHttpServletRequest request) {
        final String id = request.getParameter("id");
        if (StringUtils.isBlank(id)) {
            return errorResult(SC_BAD_REQUEST, "Id is required.");
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(SC_INTERNAL_SERVER_ERROR);
            }
            final Authorizable authorizable = jcrOperations.createGroup(session, id,
                    request.getParameter("intermediatePath"));
            return new Result<>(Map.of("path", StringUtils.defaultString(authorizable.getPath())));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> deleteAuthorizable(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, path) : null;
            if (authorizable == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            // unlike a package's synthetic tree path, an authorizable's path is a real,
            // persistent JCR node - its parent 'rep:AuthorizableFolder' is never pruned just
            // because it becomes empty, so (unlike PackageManager#deletePackage) the parent is
            // always still there after the delete, no "surviving ancestor" search needed
            final String parent = StringUtils.defaultIfBlank(StringUtils.substringBeforeLast(path, "/"), "/");
            jcrOperations.delete(session, authorizable);
            return new Result<>(Map.of("deleted", path, "parent", parent));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> enableAuthorizable(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (!(authorizable instanceof User)) {
                return new Result<>(SC_NOT_FOUND);
            }
            jcrOperations.enable(session, (User) authorizable);
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> disableAuthorizable(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (!(authorizable instanceof User)) {
                return new Result<>(SC_NOT_FOUND);
            }
            jcrOperations.disable(session, (User) authorizable, request.getParameter("reason"));
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    protected @NotNull Result<?> changePassword(@NotNull final SlingHttpServletRequest request) {
        final String password = request.getParameter("password");
        if (StringUtils.isBlank(password)) {
            return errorResult(SC_BAD_REQUEST, "Password is required.");
        }
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (!(authorizable instanceof User)) {
                return new Result<>(SC_NOT_FOUND);
            }
            jcrOperations.changePassword(session, (User) authorizable, password);
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    /**
     * Adds or removes a group membership - the single, symmetric operation backing both entry
     * points (a user/system-user/group's own "Groups" tab, and a group's own "Members" tab): the
     * request's suffix path is always the <em>currently selected</em> authorizable, and 'role'
     * says which side of the relationship that is - 'member' (the "Add to Group"/Groups-tab
     * case: the selection is the member, 'authorizableId' names the target group) or 'group'
     * (the "Add Member"/Members-tab case: the selection is the group, 'authorizableId' names the
     * target member). No divergent handling per entry point beyond resolving that one flag.
     */
    protected @NotNull Result<?> changeMembership(@NotNull final SlingHttpServletRequest request, final boolean add) {
        final String authorizableId = request.getParameter("authorizableId");
        final boolean memberRole = "member".equals(request.getParameter("role"));
        if (StringUtils.isBlank(authorizableId)) {
            return errorResult(SC_BAD_REQUEST, "authorizableId is required.");
        }
        try {
            final Session session = session(request);
            final Authorizable selected = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (selected == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            final Group group;
            final Authorizable member;
            if (memberRole) {
                member = selected;
                final Authorizable target = jcrOperations.findById(session, authorizableId);
                if (!(target instanceof Group)) {
                    return errorResult(SC_BAD_REQUEST, "'" + authorizableId + "' is not a group.");
                }
                group = (Group) target;
            } else {
                if (!(selected instanceof Group)) {
                    return errorResult(SC_BAD_REQUEST, "'" + targetPath(request) + "' is not a group.");
                }
                group = (Group) selected;
                member = jcrOperations.findById(session, authorizableId);
                if (member == null) {
                    return errorResult(SC_BAD_REQUEST, "'" + authorizableId + "' does not exist.");
                }
            }
            if (add) {
                jcrOperations.addToGroup(session, group, member);
            } else {
                jcrOperations.removeFromGroup(session, group, member);
            }
            return new Result<>(Map.of("path", targetPath(request)));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return errorResult(SC_INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    // Read operations

    /**
     * The detail view for the request's suffix path: for an authorizable (leaf), a property
     * table + action bar (branching between {@code details/user.html} - used for both regular
     * and system users, with {@code info.systemUser} distinguishing them - and
     * {@code details/group.html}); for an intermediate ('rep:AuthorizableFolder') node, a
     * navigable, non-recursive list of its immediate children instead (see
     * {@link JcrAuthorizableTree#immediateChildren} - unlike
     * {@link com.composum.sling.packages.PackageManager#viewPackage}'s recursive
     * {@code leavesUnder}, walking a whole id-hash bucket subtree does not scale).
     */
    protected @NotNull Result<?> viewAuthorizable(@NotNull final SlingHttpServletRequest request) {
        final String path = targetPath(request);
        if ("/".equals(path)) {
            return new Result<>(SC_NOT_FOUND);
        }
        try {
            final Session session = session(request);
            if (session == null) {
                return new Result<>(SC_INTERNAL_SERVER_ERROR);
            }
            // an authorizable path is resolved directly against the UserManager, independent of
            // the tree structure - only if that fails is the path treated as an intermediate
            // ('rep:AuthorizableFolder') node instead, same ordering as PackageManager#viewPackage
            final Authorizable authorizable = jcrOperations.open(session, path);
            if (authorizable != null) {
                final AuthorizableInfo info = jcrOperations.info(authorizable);
                if ("user".equals(info.getType())) {
                    // regular users only - see AuthorizableInfo#profile's own Javadoc
                    info.setProfile(jcrOperations.profileProperties(session, authorizable.getPath()));
                }
                final String template = "group".equals(info.getType())
                        ? "/sling/usermgr/details/group.html" : "/sling/usermgr/details/user.html";
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                        .with("users.actions", (Supplier<?>) () -> actions(request, info))
                        .with("users.info", (Supplier<?>) () -> valuesOf(info))
                        .with("users.writeEnabled", writeEnabled(request))
                        .with("users.affectedPathsTabUri", actionLink("affectedPathsTab") + info.getPath())
                ), template));
                return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
            }
            // a genuine 'rep:AuthorizableFolder' node with zero children (e.g. a Jackrabbit
            // id-hash bucket emptied out by deleting the authorizables that used to live in it)
            // is a real, navigable node - it renders as an empty folder view, not a 404; only a
            // path that isn't a node at all gets the 404
            if (!session.nodeExists(path)) {
                return new Result<>(SC_NOT_FOUND);
            }
            final List<AuthorizableRef> children = new JcrAuthorizableTree(session).immediateChildren(path);
            return viewFolder(children);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    protected @NotNull Result<?> viewFolder(@NotNull final List<AuthorizableRef> entries) {
        final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                .with("users.actions", List.<Values>of())
                .with("users.entries", entries)
        ), "/sling/usermgr/details/folder.html"));
        return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
    }

    // The Principal tab's own action group (rendered server-side, see details/toolbar.html +
    // details/action*.html, reused verbatim from the Package Manager's own generic pattern) -
    // Enable/Disable/Change Password/Change Profile/Delete. "Add to Group"/"Add Member" are
    // separate, always-present action groups of their own (details/addToGroupAction.html/
    // addMemberAction.html) that TabActionSwitcher (script.js) shows only while the matching tab
    // (Groups/Members) is active; a Groups/Members row's own "Remove" button lives inline on the
    // row itself (details/groupsEntry.html/membersEntry.html), not in any action group at all.

    protected @NotNull Values action(@NotNull final String key, @NotNull final String icon, @NotNull final String label) {
        return new Values().with("key", key).with("icon", icon).with("label", label);
    }

    protected @NotNull Values actionGroup(@NotNull final Values... groupedActions) {
        return new Values().with("group", true).with("actions", List.of(groupedActions));
    }

    protected @NotNull List<Values> actions(@NotNull final SlingHttpServletRequest request,
                                            @NotNull final AuthorizableInfo info) {
        final List<Values> result = new ArrayList<>();
        final boolean writeEnabled = writeEnabled(request);
        if (writeEnabled) {
            // enable/disable/password only make sense for a regular user - a system user has no
            // interactive login, and a group has neither state
            if ("user".equals(info.getType())) {
                result.add(actionGroup(
                        info.isDisabled() ? action("enable", "box-arrow-in-right", "Enable")
                                : action("disable", "box-arrow-right", "Disable"),
                        action("password", "key", "Change Password"),
                        action("changeProfile", "person-vcard", "Change Profile")));
            }
        }
        if (writeEnabled) {
            // 'admin'/'anonymous' never get a Delete button at all, not just a disabled one - the
            // same hard block JcrAuthorizableOperations#delete enforces server-side too
            if (!JcrAuthorizableOperations.PROTECTED_IDS.contains(info.getId())) {
                result.add(action("delete", "trash", "Delete"));
            }
        }
        return result;
    }

    public final Map<String, TemplateBuilder.Factory> templates = Map.of(
            "page", current -> new Template("/sling/usermgr/page.html",
                    new TemplateContext(current, new Values()
                            .with("page", new Values()
                                    .with("key", key())
                                    .with("link", pageLink())
                                    .with("label", label())
                                    .with("title", "Composum User Manager"))
                            .with("users", new Values()
                                    .with("uri", pageLink())
                                    .with("tree", manager.serverPath() + "." + key() + ".tree.json")
                                    .with("ancestors", manager.serverPath() + "." + key() + ".ancestors.json")
                                    .with("view", manager.serverPath() + "." + key() + ".view.json")
                                    .with("query", manager.serverPath() + "." + key() + ".query.json")
                                    .with("dialog", manager.serverPath() + "." + key() + ".dialog.")
                                    .with("browser", browserLink()))
                            .with("html.cssClasses", (Supplier<?>) () -> getHtmlCssClasses("usermgr-page"))
                            .with(toolsValues())
                    ), this)
    );

    @Override
    public @Nullable Template getTemplate(@NotNull TemplateContext context, @NotNull String key) {
        return Optional.ofNullable(templates.get(key))
                .map(factory -> factory.create(context))
                .orElse(key.startsWith("/") ? new Template(key, context, this) : null);
    }
}
