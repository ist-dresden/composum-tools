package com.composum.sling.packages.jcr;

import com.composum.sling.packages.PackageTreeNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.vault.packaging.JcrPackage;
import org.apache.jackrabbit.vault.packaging.JcrPackageDefinition;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the group/name/version tree of the installed JCR packages ({@link JcrPackageManager#listPackages()})
 * once per request and resolves a single node's immediate children on demand, matching the lazy jstree
 * loading contract also used by the Browser's own resource tree.
 */
public class JcrPackageTree {

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

    /**
     * @param manager the package manager whose {@link JcrPackageManager#listPackages()} to build the tree from
     */
    public JcrPackageTree(@NotNull final JcrPackageManager manager) throws RepositoryException {
        for (final JcrPackage jcrPackage : manager.listPackages()) {
            final JcrPackageDefinition definition = jcrPackage.getDefinition();
            final String name = definition != null ? definition.get(JcrPackageDefinition.PN_NAME) : null;
            if (definition == null || StringUtils.isBlank(name)) {
                continue;
            }
            final String group = StringUtils.defaultString(definition.get(JcrPackageDefinition.PN_GROUP));
            Node current = root;
            String currentPath = "";
            for (final String segment : StringUtils.split(group, "/")) {
                currentPath = currentPath + "/" + segment;
                final String folderPath = currentPath;
                current = current.children.computeIfAbsent(segment, key -> new Node(folderPath, segment, "folder"));
            }
            final String namePath = currentPath + "/" + name;
            final Node nameNode = current.children.computeIfAbsent(name, key -> new Node(namePath, name, "folder"));
            final String versionPath = JcrPackageOperations.relativePath(manager, jcrPackage);
            if (versionPath != null) {
                final String version = definition.get(JcrPackageDefinition.PN_VERSION);
                final String label = StringUtils.isNotBlank(version) ? version : name;
                nameNode.children.put(versionPath, new Node(versionPath, label, "package"));
            }
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
