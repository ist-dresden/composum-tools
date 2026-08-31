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
 * {@code com.composum.sling.browser.dto.TreeNode}.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageTreeNode {

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {

        protected final boolean loaded;

        public State(boolean loaded) {
            this.loaded = loaded;
        }
    }

    /** the tree path: a synthetic group/name path for a 'folder' node, or the real path/id relative to the backing store for a 'package' (version) leaf */
    protected final String path;
    /** the node's display name (group segment, package name, or version) */
    protected final String name;
    protected final String text;
    /** 'root', 'folder' (group or package name), or 'package' (one concrete version) */
    protected final String type;
    /** this node's immediate children, if already resolved (see {@link #state}) */
    protected final Collection<PackageTreeNode> children;
    /** 'null' if {@link #children} is already resolved; otherwise marks whether this node has unresolved children */
    protected final State state;

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
