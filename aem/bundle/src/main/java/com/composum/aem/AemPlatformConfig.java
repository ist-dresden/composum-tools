package com.composum.aem;

import com.composum.sling.browser.view.PropertiesView;
import com.composum.sling.tools.DefaultPlatformConfig;
import com.composum.sling.tools.PlatformConfig;
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

import static com.composum.sling.tools.Common.JCR_CONTENT;
import static com.composum.sling.tools.Common.JCR_PRIMARY_TYPE;

@Component(service = PlatformConfig.class)
@Designate(ocd = AemPlatformConfig.Config.class)
public class AemPlatformConfig extends DefaultPlatformConfig {

    @ObjectClassDefinition(name = "Composum Tools AEM Platform Config")
    public @interface Config {

        @AttributeDefinition()
        String key() default PropertiesView.KEY;

        @AttributeDefinition()
        String[] fileTypes() default {
                "nt:file",
                "nt:resource",
                "oak:Resource",
                "dam:Asset",
                "dam:AssetContent"
        };

        @AttributeDefinition()
        int rank() default 3000;
    }

    @Reference
    private SlingSettingsService settingsService;

    protected List<String> fileTypes;

    @Activate
    @Modified
    protected void activate(final Config config) {
        fileTypes = Arrays.asList(config.fileTypes());
    }

    protected SlingSettingsService settingsService() {
        return settingsService;
    }

    @Override
    public @NotNull Collection<String> fileTypes() {
        return fileTypes;
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