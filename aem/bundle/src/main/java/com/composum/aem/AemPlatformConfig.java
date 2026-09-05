package com.composum.aem;

import com.composum.sling.tools.DefaultPlatformConfig;
import com.composum.sling.tools.PlatformConfig;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.settings.SlingSettingsService;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import java.util.Arrays;

import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;

/**
 * AEM-specific extension of {@link DefaultPlatformConfig}: adds the resource types that count as
 * "binary/file-like" (see {@link #fileTypes()}) and resolves the underlying binary of AEM's own
 * asset/rendition structures (see {@link #originalOf}).
 */
@Component(service = PlatformConfig.class)
@Designate(ocd = AemPlatformConfig.Config.class)
public class AemPlatformConfig extends DefaultPlatformConfig {

    /** the AEM navigation console entry path guarding access to the tools */
    public static final String AEM_TOOLS_ENTRY = "/apps/cq/core/content/nav/tools/general/composum";

    /**
     * OSGi metatype configuration for this platform config's file types and rank.
     */
    @ObjectClassDefinition(name = "Composum Tools AEM Platform Config")
    public @interface Config {

        /**
         * @return the resource path for checking the right to access the tools
         */
        @AttributeDefinition()
        String guardNode();

        /**
         * @return the resource types treated as binary/file-like
         */
        @AttributeDefinition()
        String[] fileTypes() default {
                "nt:file",
                "nt:resource",
                "oak:Resource",
                "dam:Asset",
                "dam:AssetContent"
        };

        /**
         * @return this platform config's service ranking
         */
        @AttributeDefinition()
        int rank() default 3000;
    }

    @Reference
    private void bindSettingsService(SlingSettingsService service) {
        settingsService = service;
    }

    /**
     * @param config the current OSGi configuration
     */
    @Activate
    @Modified
    protected void activate(final Config config) {
        guardNode = config.guardNode();
        fileTypes = Arrays.asList(config.fileTypes());
    }

    @Override
    public @Nullable Resource originalOf(@Nullable final Resource resource) {
        Resource original = null;
        if (resource != null) {
            final String primaryType = resource.getValueMap().get(JCR_PRIMARY_TYPE, "");
            switch (primaryType) {
                case "nt:resource":
                    original = resource;
                    break;
                case "nt:file":
                    original = resource.getChild(JCR_CONTENT);
                    break;
                case "dam:Asset":
                    original = resource.getChild(JCR_CONTENT + "/renditions/original/" + JCR_CONTENT);
                    break;
                case "dam:AssetContent":
                    original = resource.getChild("renditions/original/" + JCR_CONTENT);
                    break;
            }
        }
        return original;
    }
}