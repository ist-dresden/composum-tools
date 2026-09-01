package com.composum.sling.packages.registry;

import com.composum.sling.packages.PackageListEntry;
import com.composum.sling.packages.PackageTreeNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.Version;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the group/name/version tree of every package registered in any of the bound
 * {@code PackageRegistry} services (merged into a single tree, not separated by registry), using
 * the same lazy jstree-loading contract as {@code jcr.JcrPackageTree}.
 */
public class RegistryTree {

    private static class Node {

        final String path;
        final String name;
        final String type;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(@NotNull final String path, @NotNull final String name, @NotNull final String type) {
            this.path = path;
            this.name = name;
            this.type = type;
        }
    }

    private final Node root = new Node("/", "Packages", "root");

    public RegistryTree(@NotNull final Set<PackageId> packageIds) {
        for (final PackageId id : packageIds) {
            final String group = StringUtils.defaultString(id.getGroup());
            final String name = id.getName();
            if (StringUtils.isBlank(name)) {
                continue;
            }
            Node current = root;
            String currentPath = "";
            for (final String segment : StringUtils.split(group, "/")) {
                currentPath = currentPath + "/" + segment;
                final String folderPath = currentPath;
                current = current.children.computeIfAbsent(segment, key -> new Node(folderPath, segment, "folder"));
            }
            final String namePath = currentPath + "/" + name;
            final Node nameNode = current.children.computeIfAbsent(name, key -> new Node(namePath, name, "folder"));
            // a leaf's own 'path' is '/' + the PackageId's string form (parseable back via
            // PackageId.fromString, see RegistryOperations#open) - the leading '/' is only there
            // so it works as a Sling request suffix; unlike a folder path it is never itself split
            // for tree navigation, only used directly by the view/download/install/uninstall/
            // delete operations, exactly like JcrPackageTree's real vault path
            final String version = id.getVersionString();
            final String label = StringUtils.isNotBlank(version) ? version : name;
            final String leafPath = "/" + id;
            nameNode.children.put(leafPath, new Node(leafPath, label, "package"));
        }
    }

    /**
     * The node at the given tree path, with its immediate children resolved.
     *
     * @param path the tree path to resolve ('/' for the root)
     * @return the resolved node, or 'null' if no such path exists
     */
    public @Nullable PackageTreeNode nodeAt(@NotNull final String path) {
        final Node node = findNode(path);
        return node != null ? toTreeNode(node, true) : null;
    }

    /**
     * Every package (leaf) nested anywhere under the given tree path, recursively - for the
     * intermediate (group/name) node's "packages under here" list view. A leaf's label is its
     * tree node names from (but not including) the given path down to it, joined by '/' - not
     * its path, which (unlike a folder path) is not a hierarchical continuation of it, see
     * {@link #ancestorsOf}.
     *
     * @param path the tree path (group or name folder) to list packages under
     * @return the nested packages, or an empty list if the path does not exist or has none
     */
    public @NotNull List<PackageListEntry> leavesUnder(@NotNull final String path) {
        final Node node = findNode(path);
        final List<PackageListEntry> result = new ArrayList<>();
        if (node != null) {
            collectLeaves(node, "", result);
        }
        return result;
    }

    private @Nullable Node findNode(@NotNull final String path) {
        Node node = root;
        if (!"/".equals(path)) {
            for (final String segment : StringUtils.split(path, "/")) {
                node = node.children.get(segment);
                if (node == null) {
                    return null;
                }
            }
        }
        return node;
    }

    private void collectLeaves(@NotNull final Node node, @NotNull final String prefix, @NotNull final List<PackageListEntry> result) {
        for (final Node child : node.children.values()) {
            final String label = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            if ("package".equals(child.type)) {
                result.add(new PackageListEntry(label, child.path));
            } else {
                collectLeaves(child, label, result);
            }
        }
    }

    /**
     * The leaf (version) paths that a "purge" under the given tree path would delete: every
     * version except the highest one in each name-folder found (recursively) under it - so
     * calling this on a name folder purges that one package's old versions, and calling it on a
     * group folder purges every package nested under it. A name-folder is recognized structurally
     * (all of its children are "package" leaves), not by path depth, so this works regardless of
     * how many group segments lead down to it.
     *
     * @param path the tree path (group or name folder) to purge old versions under
     * @return the leaf (version) paths to delete, or an empty list if there is nothing to purge
     */
    public @NotNull List<String> purgeCandidates(@NotNull final String path) {
        final Node node = findNode(path);
        final List<String> result = new ArrayList<>();
        if (node != null) {
            collectPurgeCandidates(node, result);
        }
        return result;
    }

    private void collectPurgeCandidates(@NotNull final Node node, @NotNull final List<String> result) {
        final boolean isNameFolder = !node.children.isEmpty()
                && node.children.values().stream().allMatch(child -> "package".equals(child.type));
        if (isNameFolder) {
            Node latest = null;
            for (final Node child : node.children.values()) {
                if (latest == null || Version.create(child.name).compareTo(Version.create(latest.name)) > 0) {
                    latest = child;
                }
            }
            for (final Node child : node.children.values()) {
                if (child != latest) {
                    result.add(child.path);
                }
            }
        } else {
            for (final Node child : node.children.values()) {
                if (!"package".equals(child.type)) {
                    collectPurgeCandidates(child, result);
                }
            }
        }
    }

    /**
     * The tree (folder) paths leading from the root down to the given leaf (version) path, in
     * top-down order, not including the leaf itself - see {@code jcr.JcrPackageTree#ancestorsOf}
     * for why this needs an explicit tree search rather than just splitting the leaf path.
     *
     * @param leafPath the target leaf's tree path
     * @return the ancestor folder paths, top-down, or an empty list if no such leaf exists
     */
    public @NotNull List<String> ancestorsOf(@NotNull final String leafPath) {
        final List<String> chain = new ArrayList<>();
        findAncestors(root, leafPath, chain);
        return chain;
    }

    private boolean findAncestors(@NotNull final Node node, @NotNull final String leafPath, @NotNull final List<String> chain) {
        for (final Node child : node.children.values()) {
            if (child.path.equals(leafPath)) {
                return true;
            }
            chain.add(child.path);
            if (findAncestors(child, leafPath, chain)) {
                return true;
            }
            chain.remove(chain.size() - 1);
        }
        return false;
    }

    private @NotNull PackageTreeNode toTreeNode(@NotNull final Node node, final boolean resolveChildren) {
        List<PackageTreeNode> children = null;
        PackageTreeNode.State state = null;
        if (!node.children.isEmpty()) {
            if (resolveChildren) {
                children = new ArrayList<>();
                for (final Node child : node.children.values()) {
                    children.add(toTreeNode(child, false));
                }
            } else {
                state = new PackageTreeNode.State(false);
            }
        }
        return new PackageTreeNode(node.path, node.name, node.type, children, state);
    }
}
