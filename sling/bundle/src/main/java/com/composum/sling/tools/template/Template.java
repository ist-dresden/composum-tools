package com.composum.sling.tools.template;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Identifies a single template to render: the (informational) resource path it was loaded from,
 * the {@link TemplateContext} it is rendered against, and the {@link TemplateBuilder} providing
 * its rendering environment (resource lookup, XSS filtering, value transformation). A
 * {@link TemplateReader} is always constructed for one such 'Template' instance; nested
 * placeholders ('include', 'each', 'if') create further 'Template' instances - reusing the same
 * builder and the same or a derived context - for every template they embed.
 */
@Getter
public class Template {

    /** the resource path this template was loaded from, or an informational value for an inline template */
    protected final String path;
    /** the context this template is rendered against */
    protected final TemplateContext context;
    /** the builder providing this template's rendering environment */
    protected final TemplateBuilder builder;

    /**
     * @param path    the resource path this template was loaded from, or an informational value
     *                for a template not backed by a resource (e.g. an inline template string)
     * @param context the context to render this template against
     * @param builder the builder providing this template's rendering environment
     */
    public Template(@NotNull final String path, @NotNull final TemplateContext context,
                    @NotNull final TemplateBuilder builder) {
        this.path = path;
        this.context = context;
        this.builder = builder;
    }
}
