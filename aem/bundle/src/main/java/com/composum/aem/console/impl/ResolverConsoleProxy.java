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
 * Embeds the Felix Web Console "Sling Resource Resolver" plugin
 * ({@code felix.webconsole.label = "jcrresolver"}).
 */
@Component(service = ConsoleProxy.class, immediate = true)
@Designate(ocd = ResolverConsoleProxy.Config.class)
public class ResolverConsoleProxy extends AbstractConsoleProxy {

    public static final String KEY = "resolver";

    /**
     * OSGi metatype configuration for this proxy's {@link #key()}, {@link #label()} and {@link #rank()}.
     */
    @ObjectClassDefinition(name = "Composum Sling Resolver Proxy")
    public @interface Config {

        @AttributeDefinition()
        String key() default ResolverConsoleProxy.KEY;

        @AttributeDefinition()
        String label() default "Resolver";

        @AttributeDefinition()
        int rank() default 4000;
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
        return "jcrresolver";
    }

    @Override
    protected @NotNull String pageTitle() {
        return "Resource Resolver";
    }
}
