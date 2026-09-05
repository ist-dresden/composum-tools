package com.composum.sling.tools;

import org.apache.sling.api.resource.ResourceResolver;

public interface MergeMountpointService {

    String DEFAULT_OVERRIDE_ROOT = "/mnt/override";
    String DEFAULT_OVERLAY_ROOT = "/mnt/overlay";

    /**
     * The position of the Sling resource type hierarchy based resource merger , normally /mnt/override .
     */
    String overrideMergeMountPoint(ResourceResolver resolver);

    /**
     * The position of the Sling search based resource picker, normally /mnt/overlay .
     */
    String overlayMergeMountPoint(ResourceResolver resolver);
}
