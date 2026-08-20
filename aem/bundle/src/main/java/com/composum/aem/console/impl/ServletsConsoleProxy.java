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
 * Embeds the Felix Web Console "Servlet/Script Resolver" plugin
 * ({@code felix.webconsole.label = "servletresolver"}).
 */
@Component(service = ConsoleProxy.class, immediate = true)
@Designate(ocd = ServletsConsoleProxy.Config.class)
public class ServletsConsoleProxy extends AbstractConsoleProxy {

    public static final String KEY = "servlets";

    /**
     * OSGi metatype configuration for this proxy's {@link #key()}, {@link #label()} and {@link #rank()}.
     */
    @ObjectClassDefinition(name = "Composum Servlet Resolver Proxy")
    public @interface Config {

        @AttributeDefinition()
        String key() default ServletsConsoleProxy.KEY;

        @AttributeDefinition()
        String label() default "Servlets";

        @AttributeDefinition()
        int rank() default 2000;
    }

    /**
     * The console this proxy is embedded in; {@code null} before {@link #attach} was called (or after
     * this proxy was unbound), see {@link Console#bindProxy}/{@code unbindProxy}.
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
        return "servletresolver";
    }

    @Override
    protected @NotNull String pageTitle() {
        return "Servlet Resolver";
    }
}
