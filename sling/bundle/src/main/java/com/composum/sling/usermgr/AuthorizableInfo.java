package com.composum.sling.usermgr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The detail-view DTO for a single authorizable - the User Manager equivalent of
 * {@code com.composum.sling.packages.PackageInfo} (a plain concrete class here, not an
 * interface, since there is only one backend to abstract over).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizableInfo {

    protected String path;
    protected String id;
    protected String principalName;
    /** 'user', 'system-user', or 'group' */
    protected String type;
    /** redundant with {@link #type}, but the '${if:...}' template placeholder only tests
     * truthiness (no per-value equality check) - this is what {@code details/user.html} branches
     * on to hide the password/disabled rows for a system user */
    protected boolean systemUser;

    /** users only */
    protected boolean disabled;
    @Nullable
    protected String disabledReason;

    /** the groups this authorizable declares membership in */
    protected List<AuthorizableRef> groups;
    /** this group's own declared members - 'null' for a user/system-user */
    @Nullable
    protected List<AuthorizableRef> members;
}
