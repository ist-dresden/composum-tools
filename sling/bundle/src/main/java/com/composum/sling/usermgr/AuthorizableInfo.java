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

    /**
     * Creates an empty instance, to be filled through the setters (and by Jackson).
     */
    public AuthorizableInfo() {
    }

    /** the authorizable's own JCR path */
    protected String path;
    /** the authorizable's id (its user or group name) */
    protected String id;
    /** the authorizable's principal name, as referenced from ACEs */
    protected String principalName;
    /** 'user', 'system-user', or 'group' */
    protected String type;
    /** redundant with {@link #type}, but the '${if:...}' template placeholder only tests
     * truthiness (no per-value equality check) - this is what {@code details/user.html} branches
     * on to hide the password/disabled rows for a system user */
    protected boolean systemUser;

    /** users only */
    protected boolean disabled;
    /** the reason given when the user was disabled - 'null' if it is not disabled */
    @Nullable
    protected String disabledReason;

    /** the groups this authorizable declares membership in */
    protected List<AuthorizableRef> groups;
    /** this group's own declared members - 'null' for a user/system-user */
    @Nullable
    protected List<AuthorizableRef> members;

    /** regular users only - the informal 'profile' child node's properties (see
     * {@code JcrAuthorizableOperations#profileProperties}), 'null' for a system user or group */
    @Nullable
    protected List<ProfileEntry> profile;

    /** one profile property, as a name/value pair for template iteration - a plain DTO rather
     * than a {@code Map} entry so it round-trips predictably through the template engine's
     * {@code valuesOf} (Jackson) conversion the same way {@link AuthorizableRef} already does */
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProfileEntry {

        /** the property name */
        protected final String name;
        /** the property value, as display text */
        protected final String value;

        /**
         * Creates one profile entry.
         *
         * @param name  the property name
         * @param value the property value, as display text
         */
        public ProfileEntry(final String name, final String value) {
            this.name = name;
            this.value = value;
        }
    }
}
