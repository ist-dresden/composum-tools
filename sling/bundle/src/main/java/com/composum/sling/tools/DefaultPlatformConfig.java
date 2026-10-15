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
import static com.composum.sling.tools.Common.MIX_CREATED;
import static com.composum.sling.tools.Common.MIX_LAST_MODIFIED;
import static com.composum.sling.tools.Common.MIX_LOCKABLE;
import static com.composum.sling.tools.Common.MIX_REFERENCEABLE;
import static com.composum.sling.tools.Common.MIX_TITLE;
import static com.composum.sling.tools.Common.MIX_VERSIONABLE;
import static com.composum.sling.tools.Common.NT_BASE;
import static com.composum.sling.tools.Common.NT_FILE;
import static com.composum.sling.tools.Common.NT_FOLDER;
import static com.composum.sling.tools.Common.NT_RESOURCE;
import static com.composum.sling.tools.Common.NT_UNSTRUCTURED;
import static com.composum.sling.tools.Common.OAK_RESOURCE;
import static com.composum.sling.tools.Common.ORDERED_FOLDER;
import static com.composum.sling.tools.Common.REP_ACCESS_CONTROLLABLE;
import static com.composum.sling.tools.Common.SLING_FOLDER;

/** Default {@link PlatformConfig} implementation, configured through its OSGi {@link Config}. */
@Component(service = PlatformConfig.class, immediate = true)
@Designate(ocd = DefaultPlatformConfig.Config.class)
public class DefaultPlatformConfig implements PlatformConfig {

    /** Default constructor. */
    public DefaultPlatformConfig() {
    }

    /** OSGi metatype configuration of the platform config. */
    @ObjectClassDefinition(name = "Composum Tools Platform Config")
    public @interface Config {

        /**
         * The resource path whose accessibility is checked to determine if the tools are allowed.
         *
         * @return the resource path for checking the right to access the tools
         */
        @AttributeDefinition()
        String guardNode();

        /**
         * The resource types treated as binary/file-like.
         *
         * @return the file-like resource types
         */
        @AttributeDefinition()
        String[] fileTypes() default {
                NT_FILE,
                NT_RESOURCE,
                OAK_RESOURCE
        };

        /**
         * The primary node types suggested while editing jcr:primaryType.
         *
         * @return the primary node type names offered as jcr:primaryType autocomplete suggestions
         * (see {@link PlatformConfig#primaryTypes()})
         */
        @AttributeDefinition()
        String[] primaryTypes() default {
                NT_BASE,
                NT_UNSTRUCTURED,
                NT_FOLDER,
                NT_FILE,
                NT_RESOURCE,
                SLING_FOLDER,
                ORDERED_FOLDER
        };

        /**
         * The mixin node types suggested while editing jcr:mixinTypes.
         *
         * @return the mixin node type names offered as jcr:mixinTypes autocomplete suggestions
         * (see {@link PlatformConfig#mixinTypes()})
         */
        @AttributeDefinition()
        String[] mixinTypes() default {
                REP_ACCESS_CONTROLLABLE,
                MIX_VERSIONABLE,
                MIX_REFERENCEABLE,
                MIX_LOCKABLE,
                MIX_TITLE,
                MIX_CREATED,
                MIX_LAST_MODIFIED
        };

        /**
         * The service ranking of this platform config.
         *
         * @return the service ranking
         */
        @AttributeDefinition()
        int rank() default 1000;
    }

    /** the Sling settings service providing the run modes */
    @Reference
    protected SlingSettingsService settingsService;

    /** the configured resource path for checking the right to access the tools */
    protected String guardNode;
    /** the configured file/binary resource types (see {@link Config#fileTypes()}) */
    protected List<String> fileTypes;
    /** the configured primary node type name suggestions (see {@link Config#primaryTypes()}) */
    protected List<String> primaryTypes;
    /** the configured mixin node type name suggestions (see {@link Config#mixinTypes()}) */
    protected List<String> mixinTypes;

    /**
     * Applies the given configuration.
     *
     * @param config the current OSGi configuration
     */
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
