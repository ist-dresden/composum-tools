package com.composum.sling.browser;

import com.composum.sling.tools.template.Template;
import com.composum.sling.tools.template.TemplateBuilder;
import com.composum.sling.tools.template.TemplateContext;
import org.apache.sling.xss.XSSAPI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.Collection;
import java.util.Collections;

public abstract class AbstractView implements View, TemplateBuilder {

    public abstract Browser browser();

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @Nullable Reader openTemplate(@Nullable Template template) {
        return browser().openTemplate(template);
    }

    @Override
    public @NotNull XSSAPI xssapi() {
        return browser().xssapi();
    }

    @Override
    public @NotNull String pluginLink(@NotNull String path) {
        return browser().pluginLink(path);
    }

    @Override
    public @NotNull String toString(@NotNull Object value) {
        return browser().toString(value);
    }

    @Override
    public @NotNull Object valuesOf(@NotNull Object value) {
        return browser().valuesOf(value);
    }

    @Override
    public TemplateContext.@NotNull Values values(TemplateContext.@NotNull Values values) {
        return values
                .with("id", key())
                .with("label", label());
    }

    @Override
    public @NotNull Collection<String> styles() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull Collection<String> scripts() {
        return Collections.emptyList();
    }
}
