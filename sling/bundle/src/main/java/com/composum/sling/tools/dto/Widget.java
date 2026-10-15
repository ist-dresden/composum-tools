package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext.Values;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The base class of the dashboard widgets (tiles and pages) identified by a key and arranged by a rank.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Widget implements Values.Provider {

    /**
     * The kinds of widgets.
     */
    public enum Type {
        /** A dashboard tile. */
        TILE,
        /** A dashboard page. */
        PAGE
    }

    /** The unique key of the widget. */
    protected final String key;
    /** The label of the widget. */
    protected final String label;
    /** The rank which determines the widget's position in the arrangement. */
    protected final int rank;

    /**
     * Creates a widget.
     *
     * @param key   the unique key of the widget
     * @param label the label of the widget
     * @param rank  the rank which determines the widget's position in the arrangement
     */
    public Widget(@NotNull final  String key, @NotNull final String label, int rank) {
        this.key = key;
        this.label = label;
        this.rank = rank;
    }
}
