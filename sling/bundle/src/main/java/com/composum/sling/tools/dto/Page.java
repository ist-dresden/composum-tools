package com.composum.sling.tools.dto;

import com.composum.sling.tools.template.TemplateContext.Values;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Page extends Widget {

    protected Type type = Type.PAGE;
    protected Boolean newGroup;

    @JsonIgnore
    protected Supplier<String> link;

    public Page(@NotNull final String key, @NotNull final String label, @Nullable final Supplier<String> link) {
        this(key, label, link, null);
    }

    public Page(@NotNull final String key, @NotNull final String label, @Nullable final Supplier<String> link, @Nullable final Boolean newGroup) {
        super(key, label);
        this.link = link;
        this.newGroup = newGroup;
    }

    public @Nullable String getLink() {
        return link != null ? link.get() : null;
    }

    @Override
    public @NotNull Values values(@NotNull Values values) {
        return values
                .with("label", label)
                .with("link", link)
                .with("newGroup", newGroup);
    }
}
