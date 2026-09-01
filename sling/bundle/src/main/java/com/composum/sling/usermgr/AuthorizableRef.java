package com.composum.sling.usermgr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single reference to a tree node - either a real authorizable (user/system-user/group) or a
 * plain intermediate 'rep:AuthorizableFolder' - used for a folder's immediate-children list (see
 * {@code jcr.JcrAuthorizableTree#immediateChildren}), the Groups/Members tabs, and search results.
 * The User Manager equivalent of {@code com.composum.sling.packages.PackageListEntry}.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizableRef {

    /** the authorizable id, or 'null' for a plain intermediate folder (folders have no id) */
    protected final String id;
    protected final String path;
    /** 'folder', 'user', 'system-user', or 'group' */
    protected final String type;
    /** the display label - the id for an authorizable, or the node name for a folder */
    protected final String label;
    /** the Bootstrap Icons name (without the 'bi-' prefix) for {@link #type} - computed here so
     * the (server-rendered, no per-type branching primitive) list templates don't need one */
    protected final String icon;

    public AuthorizableRef(@Nullable final String id, @NotNull final String path, @NotNull final String type,
                           @NotNull final String label) {
        this.id = id;
        this.path = path;
        this.type = type;
        this.label = label;
        this.icon = iconOf(type);
    }

    private static @NotNull String iconOf(@NotNull final String type) {
        switch (type) {
            case "user":
                return "person";
            case "system-user":
                return "gear";
            case "group":
                return "people";
            default:
                return "folder";
        }
    }
}
