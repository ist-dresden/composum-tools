package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Tile extends Widget {

    protected final Type type = Type.TILE;

    public Tile(@NotNull final String key, @NotNull final String label, int rank) {
        super(key, label, rank);
    }

    @Override
    public @NotNull TemplateContext.Values values(@NotNull TemplateContext.Values values) {
        return values.with(key, new TemplateContext.Values()
                .with("label", label)
        );
    }
}
