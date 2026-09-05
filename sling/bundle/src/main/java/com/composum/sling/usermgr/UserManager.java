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
 * The User Manager: browses and manages Jackrabbit users, system users and groups under '/home' -
 * a tree on the left ('/home/users'/'/home/groups', arbitrarily nested via intermediate
 * 'rep:AuthorizableFolder' paths), the selected authorizable's details and actions on the right.
 * Unlike {@link com.composum.sling.packages.PackageManager}, there is exactly one backend
 * (Jackrabbit's own {@code UserManager}, via {@code ((JackrabbitSession) session).getUserManager()})
 * so this plugin carries none of the mode-abstraction machinery that the two-backend Package
 * Manager needs. Mutating operations (create/delete/enable/disable/password/group membership) are
 * gated by {@link Config#writeEnabled()}, same pattern as {@code PackageManager.Config#writeEnabled()}
 * - actual enforcement otherwise relies entirely on the JCR session's own ACLs.
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

    public @NotNull String pageLink() {
        return manager.serverPath() + "." + key() + ".html";
    }

    protected @NotNull String actionLink(@NotNull final String action) {
        return manager.serverPath() + "." + key() + "." + action + ".json";
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
            case "query":
                return query(request);
            case "dialog":
                return dialog(request, Manager.consume(selectors, ""));
            default: {
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                        // pre-selects this path in the tree on initial load, see UsersTree
                        .with("users.path", targetPath(request))
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
     * Searches for authorizables whose id contains the request's 'text' parameter, optionally
     * restricted to the 'type' parameter ('user'/'group') - see
     * {@link JcrAuthorizableOperations#find}, backing the tree-bar's "Find" input.
     */
    protected @NotNull Result<?> query(@NotNull final SlingHttpServletRequest request) {
        final String text = StringUtils.trimToEmpty(request.getParameter("text"));
        if (text.isEmpty()) {
            return new Result<>(List.of());
        }
        try {
            final Session session = session(request);
            final List<AuthorizableRef> result = session != null
                    ? jcrOperations.find(session, text, request.getParameter("type"), QUERY_LIMIT) : List.of();
            return new Result<>(result);
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
        }
    }

    public @NotNull Result<?> processPost(@NotNull final SlingHttpServletRequest request,
                                          @NotNull final SlingHttpServletResponse response,
                                          @NotNull List<String> selectors) {
        if (!config.writeEnabled()) {
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
                return authorizableDialog(request, DIALOGS_ROOT + "confirm.html", info -> new Values()
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
            case "addToGroup":
                return authorizableDialog(request, DIALOGS_ROOT + "addToGroup.html", info -> new Values()
                        .with("dialog.action", actionLink("addToGroup") + info.getPath()));
            case "addMember":
                return authorizableDialog(request, DIALOGS_ROOT + "addMember.html", info -> new Values()
                        .with("dialog.action", actionLink("addToGroup") + info.getPath()));
            case "removeFromGroup": {
                // both sides of the relationship are known here: the path-resolved authorizable
                // (the current selection) and 'authorizableId' (the row the Remove button was
                // clicked on) - 'role' says which one is the member and which is the group, same
                // meaning as in #changeMembership
                final String authorizableId = StringUtils.defaultString(request.getParameter("authorizableId"));
                final boolean memberRole = "member".equals(request.getParameter("role"));
                return authorizableDialog(request, DIALOGS_ROOT + "confirm.html", info -> {
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
            case "affectedPaths":
                return affectedPathsDialog(request);
            default:
                return new Result<>(SC_NOT_FOUND);
        }
    }

    /**
     * A read-only report of every repository path where an ACL grants or denies a privilege to
     * the addressed authorizable's principal - the User Manager equivalent of Package Manager's
     * Coverage dialog. Always available, not gated by {@link Config#writeEnabled()}.
     */
    protected @NotNull Result<?> affectedPathsDialog(@NotNull final SlingHttpServletRequest request) {
        try {
            final Session session = session(request);
            final Authorizable authorizable = session != null ? jcrOperations.open(session, targetPath(request)) : null;
            if (authorizable == null) {
                return new Result<>(SC_NOT_FOUND);
            }
            List<String> lines = jcrOperations.affectedPaths(request.getResourceResolver(), authorizable);
            if (lines.isEmpty()) {
                // not a failure: this authorizable's principal simply isn't referenced by any
                // rep:GrantACE/rep:DenyACE currently in the repository
                lines = List.of("(no ACL entries reference this authorizable's principal)");
            }
            return renderDialog(DIALOGS_ROOT + "affectedPaths.html", new Values().with("affectedPaths.lines", lines));
        } catch (RepositoryException ex) {
            LOG.error(ex.getMessage(), ex);
            return new Result<>(SC_INTERNAL_SERVER_ERROR);
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
                final String template = "group".equals(info.getType())
                        ? "/sling/usermgr/details/group.html" : "/sling/usermgr/details/user.html";
                final Reader content = templateReader(getTemplate(new TemplateContext(new Values()
                        .with("users.actions", (Supplier<?>) () -> actions(info))
                        .with("users.info", (Supplier<?>) () -> valuesOf(info))
                        .with("users.writeEnabled", config.writeEnabled())
                ), template));
                return content != null ? new Result<>(content, HTML_TYPE) : new Result<>(SC_NOT_FOUND);
            }
            final List<AuthorizableRef> children = new JcrAuthorizableTree(session).immediateChildren(path);
            return children.isEmpty() ? new Result<>(SC_NOT_FOUND) : viewFolder(children);
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

    // Detail-panel action bar (rendered server-side, see details/toolbar.html + details/action*.html,
    // reused verbatim from the Package Manager's own generic pattern). Group-membership actions
    // (Add to Group/Add Member/Remove) live in the Groups/Members tab content, not here, see the
    // next milestone.

    protected @NotNull Values action(@NotNull final String key, @NotNull final String icon, @NotNull final String label) {
        return new Values().with("key", key).with("icon", icon).with("label", label);
    }

    protected @NotNull Values actionGroup(@NotNull final Values... groupedActions) {
        return new Values().with("group", true).with("actions", List.of(groupedActions));
    }

    protected @NotNull List<Values> actions(@NotNull final AuthorizableInfo info) {
        final List<Values> result = new ArrayList<>();
        if (config.writeEnabled()) {
            // enable/disable/password only make sense for a regular user - a system user has no
            // interactive login, and a group has neither state
            if ("user".equals(info.getType())) {
                result.add(actionGroup(
                        info.isDisabled() ? action("enable", "box-arrow-in-right", "Enable")
                                : action("disable", "box-arrow-right", "Disable"),
                        action("password", "key", "Change Password")));
            }
        }
        // read-only, always available regardless of writeEnabled - the equivalent of Package
        // Manager's Coverage dialog
        result.add(action("affectedPaths", "shield-lock", "Affected Paths"));
        if (config.writeEnabled()) {
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
                                    .with("writeEnabled", config.writeEnabled()))
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
