package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * A dashboard tile widget.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tile extends Widget {

    /** The widget type, always {@link Type#TILE}. */
    protected final Type type = Type.TILE;

    /**
     * Creates a tile.
     *
     * @param key   the unique key of the tile
     * @param label the label of the tile
     * @param rank  the rank which determines the tile's position in the arrangement
     */
    public Tile(@NotNull final String key, @NotNull final String label, int rank) {
        super(key, label, rank);
    }

    @Override
    public @NotNull TemplateContext.Values values(@NotNull TemplateContext.Values values) {
        return values.with(key, new TemplateContext.Values()
                .with("key", key)
                .with("label", label)
        );
    }
}
