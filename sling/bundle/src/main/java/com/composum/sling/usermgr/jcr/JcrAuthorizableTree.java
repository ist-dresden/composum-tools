package com.composum.sling.usermgr.jcr;

import com.composum.sling.usermgr.AuthorizableRef;
import com.composum.sling.usermgr.AuthorizableTreeNode;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves the tree one node at a time, lazily - unlike
 * {@code com.composum.sling.packages.jcr.JcrPackageTree}, which builds its whole (bounded) tree
 * eagerly per request, '/home' can hold thousands of authorizables nested arbitrarily deep
 * (Jackrabbit's own id-hash buckets, plus any custom 'intermediatePath'), so only ever the
 * requested node's immediate children are resolved - modeled on
 * {@code com.composum.sling.browser.impl.TreeNode}'s per-resource walk, not on
 * {@code JcrPackageTree}'s full-scan-then-search model. This also makes {@link #ancestorsOf} a
 * plain parent-chain walk rather than a tree search, since every path here is a real, hierarchical
 * JCR path (unlike a package's synthetic group/name tree path).
 * <p>
 * The synthetic root has three children - 'Users' ({@link #USERS_PATH}), 'System'
 * ({@link #SYSTEM_PATH}) and 'Groups' ({@link #GROUPS_PATH}) - matching the legacy Composum Nodes
 * tool's own three-root layout, even though 'System' is really just a conventional subfolder of
 * 'Users' in the repository itself, not a genuinely separate top-level path; it is excluded from
 * 'Users' own child listing accordingly, both in the tree and in {@link #immediateChildren}, so it
 * never appears twice. Every level - the three roots and every folder's own children alike - is
 * sorted alphabetically by display name, not left in whatever order the repository's own node
 * iteration happens to return (Jackrabbit's id-hash buckets are not alphabetical at all).
 */
public class JcrAuthorizableTree {

    public static final String HOME_PATH = "/home";
    public static final String USERS_PATH = "/home/users";
    public static final String GROUPS_PATH = "/home/groups";
    /** Jackrabbit's own conventional location for system users - a plain subfolder of
     * {@link #USERS_PATH}, not a genuinely separate top-level path, but broken out into its own
     * synthetic tree root (matching the legacy Composum Nodes tool's own "Users/System/Groups"
     * three-root layout) so a large '/home/users' isn't dominated by non-interactive service
     * accounts when browsing for an actual person */
    public static final String SYSTEM_PATH = "/home/users/system";

    /** case-insensitive, by display name - applied at every level of the tree and to the folder
     * listing alike, so both are always alphabetically ordered rather than whatever order the
     * repository's own node iteration happens to return (Jackrabbit's id-hash buckets are not
     * alphabetical at all) */
    private static final Comparator<AuthorizableTreeNode> TREE_ORDER =
            Comparator.comparing(AuthorizableTreeNode::getName, String.CASE_INSENSITIVE_ORDER);
    private static final Comparator<AuthorizableRef> REF_ORDER =
            Comparator.comparing(AuthorizableRef::getLabel, String.CASE_INSENSITIVE_ORDER);

    private final Session session;

    public JcrAuthorizableTree(@NotNull final Session session) {
        this.session = session;
    }

    /**
     * The node at the given tree path, with its immediate children resolved.
     *
     * @param path the tree path to resolve ('/' for the synthetic root)
     * @return the resolved node, or 'null' if no such path exists
     */
    public @Nullable AuthorizableTreeNode nodeAt(@NotNull final String path) throws RepositoryException {
        if ("/".equals(path)) {
            final List<AuthorizableTreeNode> children = new ArrayList<>();
            if (session.nodeExists(USERS_PATH)) {
                children.add(toTreeNode(session.getNode(USERS_PATH), false));
            }
            if (session.nodeExists(SYSTEM_PATH)) {
                children.add(toTreeNode(session.getNode(SYSTEM_PATH), false));
            }
            if (session.nodeExists(GROUPS_PATH)) {
                children.add(toTreeNode(session.getNode(GROUPS_PATH), false));
            }
            children.sort(TREE_ORDER);
            return new AuthorizableTreeNode("/", "Home", "root", children, null);
        }
        return session.nodeExists(path) ? toTreeNode(session.getNode(path), true) : null;
    }

    /**
     * The tree (folder) paths leading from '/home/users' or '/home/groups' down to the given
     * path, top-down, not including the path itself or '/home' - lets the client drill a jstree
     * open down to a specific selection, exactly like
     * {@code com.composum.sling.packages.jcr.JcrPackageTree#ancestorsOf}, but computed here as a
     * plain walk up via {@link Node#getParent()} rather than a tree search, since every path here
     * is genuinely hierarchical.
     *
     * @param path the target path
     * @return the ancestor folder paths, top-down, or an empty list if no such path exists
     */
    public @NotNull List<String> ancestorsOf(@NotNull final String path) throws RepositoryException {
        final List<String> chain = new ArrayList<>();
        if (!session.nodeExists(path)) {
            return chain;
        }
        Node node = session.getNode(path);
        while (node.getDepth() > 0) {
            node = node.getParent();
            final String nodePath = node.getPath();
            if (HOME_PATH.equals(nodePath)) {
                break;
            }
            chain.add(nodePath);
        }
        Collections.reverse(chain);
        return chain;
    }

    /**
     * Every immediate child of the given (folder) path - not recursive, unlike
     * {@code com.composum.sling.packages.jcr.JcrPackageTree#leavesUnder} - walking a whole
     * id-hash bucket subtree recursively has no index behind it and does not scale; the
     * intermediate-node detail view (see {@code UserManager#viewAuthorizable}) therefore only
     * ever shows one level at a time, exactly like the tree itself already does for jstree
     * expansion.
     *
     * @param path the tree path (a folder) to list the immediate children of
     * @return the immediate children, or an empty list if the path does not exist or has none
     */
    public @NotNull List<AuthorizableRef> immediateChildren(@NotNull final String path) throws RepositoryException {
        final List<AuthorizableRef> result = new ArrayList<>();
        if (!session.nodeExists(path)) {
            return result;
        }
        final NodeIterator iterator = session.getNode(path).getNodes();
        while (iterator.hasNext()) {
            final Node child = iterator.nextNode();
            // same exclusion as the tree itself (see #toTreeNode) - 'system' is its own root
            if (USERS_PATH.equals(path) && SYSTEM_PATH.equals(child.getPath())) {
                continue;
            }
            final String type = typeOf(child);
            if (type != null) {
                result.add(toRef(child, type));
            }
        }
        result.sort(REF_ORDER);
        return result;
    }

    private @NotNull AuthorizableTreeNode toTreeNode(@NotNull final Node node, final boolean resolveChildren)
            throws RepositoryException {
        final String path = node.getPath();
        final String type = StringUtils.defaultString(typeOf(node), "folder");
        // for a leaf (user/system-user/group) node, prefer the readable 'rep:authorizableId' over
        // the raw JCR node name - same preference '#toRef' already applies for folder listings
        // and search results; the node name can be an opaque, Jackrabbit/Oak-generated id (e.g.
        // for an externally federated user), while 'rep:authorizableId' is always the actual
        // login/group id an admin would recognize
        final String name = USERS_PATH.equals(path) ? "Users" : GROUPS_PATH.equals(path) ? "Groups"
                : SYSTEM_PATH.equals(path) ? "System"
                : "folder".equals(type) ? node.getName()
                : node.hasProperty(REP_AUTHORIZABLE_ID) ? node.getProperty(REP_AUTHORIZABLE_ID).getString() : node.getName();
        List<AuthorizableTreeNode> children = null;
        AuthorizableTreeNode.State state = null;
        if ("folder".equals(type)) {
            if (resolveChildren) {
                children = new ArrayList<>();
                final NodeIterator iterator = node.getNodes();
                while (iterator.hasNext()) {
                    final Node child = iterator.nextNode();
                    // 'system' has its own synthetic root (see #nodeAt) - skip it here so it
                    // isn't also listed as an ordinary child while expanding 'Users' itself
                    if (USERS_PATH.equals(path) && SYSTEM_PATH.equals(child.getPath())) {
                        continue;
                    }
                    if (typeOf(child) != null) {
                        children.add(toTreeNode(child, false));
                    }
                }
                children.sort(TREE_ORDER);
            } else {
                state = new AuthorizableTreeNode.State(false);
            }
        }
        return new AuthorizableTreeNode(path, name, type, children, state);
    }

    private static final String REP_AUTHORIZABLE_ID = "rep:authorizableId";

    private @NotNull AuthorizableRef toRef(@NotNull final Node node, @NotNull final String type)
            throws RepositoryException {
        if ("folder".equals(type)) {
            return new AuthorizableRef(null, node.getPath(), type, node.getName());
        }
        final String id = node.hasProperty(REP_AUTHORIZABLE_ID)
                ? node.getProperty(REP_AUTHORIZABLE_ID).getString() : node.getName();
        return new AuthorizableRef(id, node.getPath(), type, id);
    }

    /**
     * The tree type of the given node, determined purely from its primary node type - a system
     * user's node has its own distinct 'rep:SystemUser' type (extending 'rep:User'), so this
     * needs no round trip through the {@code UserManager}/{@code Authorizable} API just to tell
     * a system user apart from a regular one.
     *
     * @return 'folder', 'user', 'system-user', or 'group', or 'null' if the node is none of those
     * (e.g. a 'rep:policy' ACL node) and should be skipped by the tree walk
     */
    private @Nullable String typeOf(@NotNull final Node node) throws RepositoryException {
        switch (node.getPrimaryNodeType().getName()) {
            case "rep:AuthorizableFolder":
                return "folder";
            case "rep:Group":
                return "group";
            case "rep:SystemUser":
                return "system-user";
            case "rep:User":
                return "user";
            default:
                return null;
        }
    }
}
