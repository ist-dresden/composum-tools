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

        /**
         * @return the primary node type names offered as jcr:primaryType autocomplete suggestions
         * (see {@link PlatformConfig#primaryTypes()})
         */
        @AttributeDefinition()
        String[] primaryTypes() default {
                "nt:base",
                "nt:unstructured",
                "nt:folder",
                "nt:file",
                "nt:resource",
                "sling:Folder",
                "sling:OrderedFolder"
        };

        /**
         * @return the mixin node type names offered as jcr:mixinTypes autocomplete suggestions
         * (see {@link PlatformConfig#mixinTypes()})
         */
        @AttributeDefinition()
        String[] mixinTypes() default {
                "rep:AccessControllable",
                "mix:versionable",
                "mix:referenceable",
                "mix:lockable",
                "mix:title",
                "mix:created",
                "mix:lastModified"
        };

        @AttributeDefinition()
        int rank() default 1000;
    }

    @Reference
    protected SlingSettingsService settingsService;

    protected String guardNode;
    /** the configured file/binary resource types (see {@link Config#fileTypes()}) */
    protected List<String> fileTypes;
    /** the configured primary node type name suggestions (see {@link Config#primaryTypes()}) */
    protected List<String> primaryTypes;
    /** the configured mixin node type name suggestions (see {@link Config#mixinTypes()}) */
    protected List<String> mixinTypes;

    @Activate
    @Modified
    protected void activate(final Config config) {
        guardNode = config.guardNode();
        fileTypes = Arrays.asList(config.fileTypes());
        primaryTypes = Arrays.asList(config.primaryTypes());
        mixinTypes = Arrays.asList(config.mixinTypes());
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
    public @NotNull Collection<String> primaryTypes() {
        return primaryTypes;
    }

    @Override
    public @NotNull Collection<String> mixinTypes() {
        return mixinTypes;
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
