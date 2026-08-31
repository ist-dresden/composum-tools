package com.composum.sling.packages.registry;

import com.composum.sling.packages.PackageTreeNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.packaging.PackageId;
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
        Node node = root;
        if (!"/".equals(path)) {
            for (final String segment : StringUtils.split(path, "/")) {
                node = node.children.get(segment);
                if (node == null) {
                    return null;
                }
            }
        }
        return toTreeNode(node, true);
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
