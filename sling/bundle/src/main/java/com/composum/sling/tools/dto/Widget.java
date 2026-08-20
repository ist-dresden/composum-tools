package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext.Values;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Widget implements Values.Provider {

    public enum Type { TILE, PAGE }

    protected String key;
    protected String label;

    public Widget(@NotNull final  String key, @NotNull final String label) {
        this.key = key;
        this.label = label;
    }
}
