package com.composum.sling.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@link ReferencesService#getSupertypeChain}/{@link ReferencesService#getRelatedPathSet} -
 * a labeled path related to a resource (a supertype, an override/overlay location, a resource
 * type, ...).
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RelatedPath {

    /** the path of the related resource */
    @NotNull
    protected final String path;
    /** the label to show for the related path */
    @NotNull
    protected final String label;
    /** the icon to show for the related path, if any */
    @Nullable
    protected final String icon;
    /** a description of the relation, if any */
    @Nullable
    protected final String description;

    /** whether this path is the currently selected one */
    protected final boolean current;

    /**
     * Creates a related path without description that is not the current one.
     *
     * @param path  the path of the related resource
     * @param label the label to show for the path
     * @param icon  the icon to show for the path, if any
     */
    public RelatedPath(@NotNull final String path, @NotNull final String label, @Nullable final String icon) {
        this(path, label, icon, null, false);
    }

    /**
     * Creates a related path.
     *
     * @param path        the path of the related resource
     * @param label       the label to show for the path
     * @param icon        the icon to show for the path, if any
     * @param description a description of the relation, if any
     * @param current     whether this path is the currently selected one
     */
    public RelatedPath(@NotNull final String path, @NotNull final String label, @Nullable final String icon,
                       @Nullable final String description, boolean current) {
        this.path = path;
        this.label = label;
        this.icon = icon;
        this.description = description;
        this.current = current;
    }

    @Override
    public String toString() {
        return "RelatedPath{" + "label='" + label + '\'' +
                ", path='" + path + '\'' +
                '}';
    }
}
