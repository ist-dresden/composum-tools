package com.composum.sling.tools;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.settings.SlingSettingsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.composum.sling.tools.Common.JCR_CONTENT;

@Component(service = PlatformConfig.class, immediate = true)
@Designate(ocd = DefaultPlatformConfig.Config.class)
public class DefaultPlatformConfig implements PlatformConfig {

    @ObjectClassDefinition(name = "Composum Tools Platform Config")
    public @interface Config {

        /**
         * @return the resource path for checking the right to access the tools
         */
        @AttributeDefinition()
        String guardNode();

        @AttributeDefinition()
        String[] fileTypes() default {
                "nt:file",
                "nt:resource",
                "oak:Resource"
        };

        @AttributeDefinition()
        int rank() default 1000;
    }

    @Reference
    protected SlingSettingsService settingsService;

    protected String guardNode;
    /** the configured file/binary resource types (see {@link Config#fileTypes()}) */
    protected List<String> fileTypes;

    @Activate
    @Modified
    protected void activate(final Config config) {
        guardNode = config.guardNode();
        fileTypes = Arrays.asList(config.fileTypes());
    }

    @Override
    public boolean toolsAllowed(@NotNull final SlingHttpServletRequest request) {
        return StringUtils.isBlank(guardNode) || request.getResourceResolver().getResource(guardNode) != null;
    }

    @Override
    public @NotNull Collection<String> fileTypes() {
        return fileTypes;
    }

    @Override
    public @Nullable String nameOf(@Nullable Resource resource) {
        String name = null;
        if (resource != null) {
            if (JCR_CONTENT.equals(resource.getName())) {
                name = Optional.ofNullable(nameOf(resource.getParent())).orElse(resource.getName());
            } else {
                name = resource.getName();
            }
        }
        return name;
    }

    @Override
    public @Nullable Resource contentOf(@Nullable Resource resource) {
        return Optional.ofNullable(resource)
                .filter(r -> !JCR_CONTENT.equals(r.getName()))
                .map(r -> r.getChild(JCR_CONTENT))
                .orElse(resource);
    }

    @Override
    public @Nullable Resource originalOf(@Nullable Resource resource) {
        return contentOf(resource);
    }

    @Override
    public @NotNull Set<String> runmodes() {
        return settingsService.getRunModes();
    }
}
