package com.composum.aem;

import com.composum.sling.tools.Common;

/**
 * The AEM-specific counterpart of {@link Common}: the node type, property and resource names that
 * only exist on AEM (everything {@code cq:}/{@code dam:} that the plain Sling bundle never refers
 * to), on top of everything {@link Common} already provides - a single static import source for
 * the AEM bundle.
 */
public interface AemCommon extends Common {

    String CQ_PAGE = "cq:Page";
    String CQ_PAGE_CONTENT = "cq:PageContent";
    String CQ_COMPONENT = "cq:Component";
    String CQ_TEMPLATE = "cq:Template";
    String CQ_LIVE_COPY = "cq:LiveCopy";
    String CQ_LIVE_RELATIONSHIP = "cq:LiveRelationship";
    String CQ_LIVE_SYNC_CANCELLED = "cq:LiveSyncCancelled";
    String CQ_CLIENT_LIBRARY_FOLDER = "cq:ClientLibraryFolder";
    String DAM_ASSET = "dam:Asset";
    String DAM_ASSET_CONTENT = "dam:AssetContent";
}
