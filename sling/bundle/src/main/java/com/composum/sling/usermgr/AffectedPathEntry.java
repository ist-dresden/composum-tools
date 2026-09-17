package com.composum.sling.usermgr;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * One ACL rule affecting a path for a specific authorizable, either directly (the authorizable's
 * own principal) or via one of the groups it is a (declared or inherited) member of - one row of
 * the "Affected Paths" tab's table (Principal / Path / Rule), see
 * {@code JcrAuthorizableOperations#affectedPaths}. The principal column is what makes this useful
 * for the common "why does this user have access to X" question, matching the legacy Composum
 * Nodes tool's own version of this table - dropped in an earlier pass under the (wrong) assumption
 * that a table scoped to one authorizable would only ever show that authorizable's own principal;
 * group-inherited rules make it vary per row after all.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AffectedPathEntry {

    protected final String path;
    /** the id of whichever authorizable (the selected one itself, or a group it belongs to) this
     * particular rule's ACE names as 'rep:principalName' */
    protected final String principal;
    /** that principal's own JCR path - not necessarily the currently selected authorizable's path
     * - used to link the principal cell to its own detail view */
    protected final String principalPath;
    /** the Bootstrap Icons name (without the 'bi-' prefix) for the principal's own type
     * ('user'/'system-user'/'group') - the same icon the tree uses for that type, see
     * {@link AuthorizableRef#icon} (not reused directly here since a principal resolved from
     * an ACE is a plain Jackrabbit {@code Authorizable}, not already wrapped as one) */
    protected final String principalIcon;
    /** 'grant' or 'deny' */
    protected final String type;
    /** the privileges this rule grants/denies (comma-joined if more than one), plus any
     * restrictions in parentheses (e.g. 'jcr:read (rep:glob=/foo/*)') - a single, ready-to-display
     * string, matching the legacy tool's own combined "AC rule" formatting */
    protected final String privileges;

    public AffectedPathEntry(@NotNull final String path, @NotNull final String principal,
                             @NotNull final String principalPath, @NotNull final String principalIcon,
                             @NotNull final String type, @NotNull final String privileges) {
        this.path = path;
        this.principal = principal;
        this.principalPath = principalPath;
        this.principalIcon = principalIcon;
        this.type = type;
        this.privileges = privileges;
    }
}
