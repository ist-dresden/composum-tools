package com.composum.sling.packages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * One node of the Package Manager's lazily-loaded group/name/version tree - shared between the
 * JCR-backed tree ({@code jcr.JcrPackageTree}) and the registry-backed tree
 * ({@code registry.RegistryTree}), shaped for the same jstree lazy-load JSON contract as
 * {@code com.composum.sling.tools.TreeNode}.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageTreeNode {

    /**
     * The lazy-loading state of a tree node.
     */
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {

        /** whether this node's children are already loaded */
        protected final boolean loaded;

        /**
         * Creates a state.
         *
         * @param loaded whether the node's children are already loaded
         */
        public State(boolean loaded) {
            this.loaded = loaded;
        }
    }

    /** the tree path: a synthetic group/name path for a 'folder' node, or the real path/id relative to the backing store for a 'package' (version) leaf */
    protected final String path;
    /** the node's display name (group segment, package name, or version) */
    protected final String name;
    /** the label shown for the node (same as the name) */
    protected final String text;
    /** 'root', 'folder' (group or package name), or 'package' (one concrete version) */
    protected final String type;
    /** this node's immediate children, if already resolved (see {@link #state}) */
    protected final Collection<PackageTreeNode> children;
    /** 'null' if {@link #children} is already resolved; otherwise marks whether this node has unresolved children */
    protected final State state;

    /**
     * Creates a tree node.
     *
     * @param path     the tree path
     * @param name     the display name
     * @param type     the node type ('root', 'folder' or 'package')
     * @param children the resolved immediate children, or {@code null} if not resolved
     * @param state    the lazy-loading state, or {@code null} if the children are resolved
     */
    public PackageTreeNode(@NotNull final String path, @NotNull final String name, @NotNull final String type,
                           @Nullable final Collection<PackageTreeNode> children, @Nullable final State state) {
        this.path = path;
        this.name = name;
        this.text = name;
        this.type = type;
        this.children = children;
        this.state = state;
    }
}
