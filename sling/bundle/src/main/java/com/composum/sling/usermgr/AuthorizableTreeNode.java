package com.composum.sling.usermgr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * One node of the User Manager's lazily-loaded '/home/users'/'/home/groups' tree, shaped for the
 * same jstree lazy-load JSON contract as {@code com.composum.sling.packages.PackageTreeNode} and
 * {@code com.composum.sling.browser.impl.TreeNode}.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizableTreeNode {

    /** the jsTree node state - whether the node's children are already loaded. */
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {

        /** whether the node's children are already loaded */
        protected final boolean loaded;

        /**
         * Creates a node state.
         *
         * @param loaded whether the node's children are already loaded
         */
        public State(boolean loaded) {
            this.loaded = loaded;
        }
    }

    /** the real JCR path ('/' for the synthetic root) */
    protected final String path;
    /** the node's display name */
    protected final String name;
    /** the label jsTree displays (the same as {@link #name}) */
    protected final String text;
    /** 'root', 'folder' (an intermediate 'rep:AuthorizableFolder'), 'user', 'system-user', or 'group' */
    protected final String type;
    /** this node's immediate children, if already resolved (see {@link #state}) */
    protected final Collection<AuthorizableTreeNode> children;
    /** 'null' if {@link #children} is already resolved; otherwise marks whether this node has unresolved children */
    protected final State state;

    /**
     * Creates a tree node.
     *
     * @param path     the real JCR path
     * @param name     the display name
     * @param type     'root', 'folder', 'user', 'system-user', or 'group'
     * @param children the immediate children, if already resolved
     * @param state    'null' if the children are already resolved
     */
    public AuthorizableTreeNode(@NotNull final String path, @NotNull final String name, @NotNull final String type,
                                @Nullable final Collection<AuthorizableTreeNode> children, @Nullable final State state) {
        this.path = path;
        this.name = name;
        this.text = name;
        this.type = type;
        this.children = children;
        this.state = state;
    }
}
