package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext.Values;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * A dashboard page widget which can provide a link to the page.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Page extends Widget {

    /** The widget type, always {@link Type#PAGE}. */
    protected final Type type = Type.PAGE;
    /** Marks the page as the first one of a new group of pages (optional). */
    protected final Boolean newGroup;

    /** The supplier of the link to the page (optional). */
    @JsonIgnore
    protected Supplier<String> link;

    /**
     * Creates a page without a group marker.
     *
     * @param key   the unique key of the page
     * @param label the label of the page
     * @param rank  the rank which determines the page's position in the arrangement
     * @param link  the supplier of the link to the page
     */
    public Page(@NotNull final String key, @NotNull final String label,
                int rank, @Nullable final Supplier<String> link) {
        this(key, label, rank, link, null);
    }

    /**
     * Creates a page.
     *
     * @param key      the unique key of the page
     * @param label    the label of the page
     * @param rank     the rank which determines the page's position in the arrangement
     * @param link     the supplier of the link to the page
     * @param newGroup marks the page as the first one of a new group of pages
     */
    public Page(@NotNull final String key, @NotNull final String label,
                int rank, @Nullable final Supplier<String> link, @Nullable final Boolean newGroup) {
        super(key, label, rank);
        this.link = link;
        this.newGroup = newGroup;
    }

    /**
     * Returns the link to the page.
     *
     * @return the link, or {@code null} if no link supplier is available
     */
    public @Nullable String getLink() {
        return link != null ? link.get() : null;
    }

    @Override
    public @NotNull Values values(@NotNull Values values) {
        return values
                .with("key", key)
                .with("label", label)
                .with("link", link)
                .with("newGroup", newGroup);
    }
}
