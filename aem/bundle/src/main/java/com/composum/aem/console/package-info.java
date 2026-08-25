/**
 * The Composum Tools "console" plugin: one page (see {@code com.composum.aem.console.impl.Console})
 * that aggregates several Felix Web Console plugins - Recent Requests, Sling Resource Resolver,
 * Servlet Resolver - as its navigation entries. Each entry is a {@link com.composum.aem.console.ConsoleProxy}
 * service, calling the proxied plugin's own servlet and rewriting its HTML response (URLs, embedded
 * script) to keep it working once embedded into this console's own page. See
 * {@code com.composum.aem.console.impl.AbstractConsoleProxy}'s Javadoc for how the rewriting pipeline
 * works, and {@code com.composum.aem.console.impl.Console}'s for how requests are routed to a proxy.
 */
package com.composum.aem.console;
