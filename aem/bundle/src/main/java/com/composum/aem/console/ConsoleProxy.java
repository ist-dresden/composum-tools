package com.composum.aem.console;

import com.composum.aem.console.impl.Console;
import com.composum.sling.tools.Plugin;

/**
 * A single Felix Web Console plugin embedded as one page of the Composum {@link Console}. The usual
 * base implementation, {@code AbstractConsoleProxy}, proxies the plugin's own servlet and rewrites its
 * HTML output (see its Javadoc for details); this interface is the contract {@link Console} talks to.
 * <p>
 * Implementations are registered as OSGi services and picked up by {@link Console} via a
 * {@code MULTIPLE}/{@code DYNAMIC} reference, keyed by {@link #key()} and ordered by {@link #rank()}
 * in the console's navigation.
 */
public interface ConsoleProxy extends Plugin {
}
