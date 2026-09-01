package com.composum.sling.packages;

import java.util.Calendar;
import java.util.Set;

public interface PackageInfo {
    String getPath();

    String getGroup();

    String getName();

    String getVersion();

    String getDescription();

    long getSize();

    boolean isInstalled();

    Calendar getCreated();

    String getCreatedBy();

    Calendar getLastModified();

    String getLastModifiedBy();

    Calendar getLastUnpacked();

    String getLastUnpackedBy();

    String[] getDependencies();

    Set<String> getFilterRoots();
}
