/**
 * A small, dependency-free text templating engine for rendering HTML with '${...}' placeholders,
 * built around four collaborating types:
 * <ul>
 * <li>{@link com.composum.sling.tools.template.TemplateReader} - a filtering {@link java.io.Reader}
 * that renders the placeholders of a text stream while it is read; see its class Javadoc for the
 * full placeholder syntax (value substitution, output encoding, formatting, and the 'include',
 * 'each' and 'if' control structures),</li>
 * <li>{@link com.composum.sling.tools.template.Template} - identifies one template to render: its
 * (informational) resource path, the context to render it against, and the builder providing its
 * rendering environment,</li>
 * <li>{@link com.composum.sling.tools.template.TemplateContext} - the hierarchical set of named
 * values a template's placeholders are resolved against,</li>
 * <li>{@link com.composum.sling.tools.template.TemplateBuilder} - the pluggable environment a
 * template is rendered in: resource lookup and loading, XSS output encoding, plugin link
 * generation and value normalization; typically implemented once per plugin.</li>
 * </ul>
 *
 * <p>A minimal setup: a {@code TemplateBuilder} implementation resolves and opens template
 * resources; a root {@code TemplateContext} is built from the values a template needs; a
 * {@code Template} bundles path, context and builder; and a {@code TemplateReader} reads the
 * rendered result from it, e.g.</p>
 * <pre>
 * TemplateContext context = new TemplateContext(new TemplateContext.Values()
 *         .with("title", "Hello"));
 * Template template = new Template("/my/template.html", context, builder);
 * Reader reader = new TemplateReader(template, builder.openTemplate(template));
 * String html = IOUtils.toString(reader);
 * </pre>
 *
 * <p>Nested placeholders ('include', 'each', 'if') recursively create further
 * {@code Template}/{@code TemplateReader} instances - reusing the same builder and the same or a
 * derived context - so a whole page can be composed from smaller template fragments.</p>
 */
package com.composum.sling.tools.template;
