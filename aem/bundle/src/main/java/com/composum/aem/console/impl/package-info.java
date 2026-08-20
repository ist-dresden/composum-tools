/**
 * Implementation of the {@code com.composum.aem.console} package: {@link com.composum.aem.console.impl.Console},
 * the aggregating page, and {@link com.composum.aem.console.impl.AbstractConsoleProxy}, the base class
 * for one embedded Felix Web Console plugin - together with its concrete subclasses, one per proxied
 * plugin:
 * <ul>
 * <li>{@link com.composum.aem.console.impl.RequestsConsoleProxy} - "Recent Requests"
 * ({@code felix.webconsole.label = "requests"}),</li>
 * <li>{@link com.composum.aem.console.impl.ResolverConsoleProxy} - "Sling Resource Resolver"
 * ({@code felix.webconsole.label = "jcrresolver"}),</li>
 * <li>{@link com.composum.aem.console.impl.ServletsConsoleProxy} - "Servlet/Script Resolver"
 * ({@code felix.webconsole.label = "servletresolver"}).</li>
 * </ul>
 * A new proxy usually only needs: an {@code @ObjectClassDefinition Config}, an {@code @Activate}
 * method forwarding to {@link com.composum.aem.console.impl.AbstractConsoleProxy#activate(
 * org.osgi.framework.BundleContext, String, String, int)}, {@code webConsoleLabel()} naming the Felix
 * plugin, and {@code pageTitle()}; overriding {@code rewriteResourceLink}/{@code rewriteContentLink}/
 * {@code rewriteScriptContent}/{@code isExcludedElement} is only needed for a plugin whose markup
 * needs more than the default link rewriting and stylesheet/script stripping.
 */
package com.composum.aem.console.impl;
