package com.composum.sling.tools;

import org.apache.sling.api.resource.ResourceResolver;

/** Determines the mount points of the Sling resource merger providers. */
public interface MergeMountpointService {

    /** the default mount point of the resource type hierarchy based merger */
    String DEFAULT_OVERRIDE_ROOT = "/mnt/override";
    /** the default mount point of the search path based merger */
    String DEFAULT_OVERLAY_ROOT = "/mnt/overlay";

    /**
     * The position of the Sling resource type hierarchy based resource merger , normally /mnt/override .
     *
     * @param resolver the resolver used to inspect the repository
     * @return the mount point path of the override merger
     */
    String overrideMergeMountPoint(ResourceResolver resolver);

    /**
     * The position of the Sling search based resource picker, normally /mnt/overlay .
     *
     * @param resolver the resolver used to inspect the repository
     * @return the mount point path of the overlay merger
     */
    String overlayMergeMountPoint(ResourceResolver resolver);
}
