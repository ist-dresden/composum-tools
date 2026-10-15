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

import static com.composum.aem.AemCommon.CQ_CLIENT_LIBRARY_FOLDER;
import static com.composum.aem.AemCommon.CQ_COMPONENT;
import static com.composum.aem.AemCommon.CQ_LIVE_COPY;
import static com.composum.aem.AemCommon.CQ_LIVE_RELATIONSHIP;
import static com.composum.aem.AemCommon.CQ_LIVE_SYNC_CANCELLED;
import static com.composum.aem.AemCommon.CQ_PAGE;
import static com.composum.aem.AemCommon.CQ_PAGE_CONTENT;
import static com.composum.aem.AemCommon.CQ_REPLICATION_STATUS;
import static com.composum.aem.AemCommon.CQ_TEMPLATE;
import static com.composum.aem.AemCommon.DAM_ASSET;
import static com.composum.aem.AemCommon.DAM_ASSET_CONTENT;
import static com.composum.aem.AemCommon.JCR_CONTENT;
import static com.composum.aem.AemCommon.JCR_PRIMARY_TYPE;
import static com.composum.aem.AemCommon.MIX_CREATED;
import static com.composum.aem.AemCommon.MIX_LAST_MODIFIED;
import static com.composum.aem.AemCommon.MIX_LOCKABLE;
import static com.composum.aem.AemCommon.MIX_REFERENCEABLE;
import static com.composum.aem.AemCommon.MIX_TITLE;
import static com.composum.aem.AemCommon.MIX_VERSIONABLE;
import static com.composum.aem.AemCommon.NT_BASE;
import static com.composum.aem.AemCommon.NT_FILE;
import static com.composum.aem.AemCommon.NT_FOLDER;
import static com.composum.aem.AemCommon.NT_RESOURCE;
import static com.composum.aem.AemCommon.NT_UNSTRUCTURED;
import static com.composum.aem.AemCommon.OAK_RESOURCE;
import static com.composum.aem.AemCommon.ORDERED_FOLDER;
import static com.composum.aem.AemCommon.REP_ACCESS_CONTROLLABLE;
import static com.composum.aem.AemCommon.SLING_FOLDER;

/**
 * AEM-specific extension of {@link DefaultPlatformConfig}: adds the resource types that count as
 * "binary/file-like" (see {@link #fileTypes()}) and resolves the underlying binary of AEM's own
 * asset/rendition structures (see {@link #originalOf}).
 */
@Component(service = PlatformConfig.class)
@Designate(ocd = AemPlatformConfig.Config.class)
public class AemPlatformConfig extends DefaultPlatformConfig {

    /** Default constructor. */
    public AemPlatformConfig() {
    }

    /** the AEM navigation console entry path guarding access to the tools */
    public static final String AEM_TOOLS_ENTRY = "/apps/cq/core/content/nav/tools/general/composum";

    /**
     * OSGi metatype configuration for this platform config's file types and rank.
     */
    @ObjectClassDefinition(name = "Composum Tools AEM Platform Config")
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
         * @return the resource types treated as binary/file-like
         */
        @AttributeDefinition()
        String[] fileTypes() default {
                NT_FILE,
                NT_RESOURCE,
                OAK_RESOURCE,
                DAM_ASSET,
                DAM_ASSET_CONTENT
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
                ORDERED_FOLDER,
                CQ_PAGE,
                CQ_PAGE_CONTENT,
                CQ_COMPONENT,
                CQ_TEMPLATE,
                CQ_LIVE_COPY,
                CQ_CLIENT_LIBRARY_FOLDER,
                DAM_ASSET,
                DAM_ASSET_CONTENT
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
                MIX_LAST_MODIFIED,
                CQ_REPLICATION_STATUS,
                CQ_LIVE_RELATIONSHIP,
                CQ_LIVE_SYNC_CANCELLED
        };

        /**
         * The service ranking of this platform config.
         *
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
    public @Nullable Resource originalOf(@Nullable final Resource resource) {
        Resource original = null;
        if (resource != null) {
            final String primaryType = resource.getValueMap().get(JCR_PRIMARY_TYPE, "");
            switch (primaryType) {
                case NT_RESOURCE:
                    original = resource;
                    break;
                case NT_FILE:
                    original = resource.getChild(JCR_CONTENT);
                    break;
                case DAM_ASSET:
                    original = resource.getChild(JCR_CONTENT + "/renditions/original/" + JCR_CONTENT);
                    break;
                case DAM_ASSET_CONTENT:
                    original = resource.getChild("renditions/original/" + JCR_CONTENT);
                    break;
            }
        }
        return original;
    }
}