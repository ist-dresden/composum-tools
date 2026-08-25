package com.composum.aem.console.impl;

import com.composum.aem.console.ConsoleProxy;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Embeds the Felix Web Console "Recent Requests" plugin ({@code felix.webconsole.label = "requests"}).
 */
@Component(service = ConsoleProxy.class, immediate = true)
@Designate(ocd = RequestsConsoleProxy.Config.class)
public class RequestsConsoleProxy extends AbstractConsoleProxy {

    public static final String KEY = "requests";

    /**
     * OSGi metatype configuration for this proxy's {@link #key()}, {@link #label()} and {@link #rank()}.
     */
    @ObjectClassDefinition(name = "Composum Sling Requests Proxy")
    public @interface Config {

        @AttributeDefinition()
        String key() default RequestsConsoleProxy.KEY;

        @AttributeDefinition()
        String label() default "Requests";

        @AttributeDefinition()
        int rank() default 6000;
    }

    /**
     * The console this proxy is embedded in, injected via OSGi's standard {@code @Reference}
     * lifecycle; this instance registers/unregisters itself with {@link Console#proxies()} in
     * {@link #activate}/{@link #deactivate}.
     */
    @Reference
    protected Console console;

    @Override
    public Console console() {
        return console;
    }

    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext, final Config config) {
        super.activate(bundleContext, config.key(), config.label(), config.rank());
        console.proxies().attach(this);
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
        console.proxies().detach(this);
    }

    @Override
    protected @NotNull String webConsoleLabel() {
        return key();
    }

    @Override
    protected @NotNull String pageTitle() {
        return "Recent Requests";
    }

    /**
     * Rewrites a request detail link (originally e.g. {@code requests?index=1786648684186-291}) to
     * this same proxy's own route with the {@code index} parameter carried over - the Felix plugin's
     * servlet itself already renders the single-request detail view whenever that parameter is
     * present, so no separate detail route is needed here.
     */
    @Override
    protected @NotNull String rewriteContentLink(@NotNull String url) {
        return url.startsWith("requests?")
                ? console.manager().serverPath() + ".console.requests.html?" + url.substring("requests?".length())
                : url;
    }
}
