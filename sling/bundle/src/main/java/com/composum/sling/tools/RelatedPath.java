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

    @NotNull
    protected final String path;
    @NotNull
    protected final String label;
    @Nullable
    protected final String icon;
    @Nullable
    protected final String description;

    protected final boolean current;

    public RelatedPath(@NotNull final String path, @NotNull final String label, @Nullable final String icon) {
        this(path, label, icon, null, false);
    }

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
