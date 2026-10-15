package com.composum.aem;

import com.composum.sling.tools.Common;

/**
 * The AEM-specific counterpart of {@link Common}: the node type, property and resource names that
 * only exist on AEM (everything {@code cq:}/{@code dam:} that the plain Sling bundle never refers
 * to), on top of everything {@link Common} already provides - a single static import source for
 * the AEM bundle.
 */
public interface AemCommon extends Common {

    /** node type of an AEM page */
    String CQ_PAGE = "cq:Page";
    /** node type of the content node of an AEM page */
    String CQ_PAGE_CONTENT = "cq:PageContent";
    /** node type of an AEM component */
    String CQ_COMPONENT = "cq:Component";
    /** node type of an AEM page template */
    String CQ_TEMPLATE = "cq:Template";
    /** mixin type of a live copy root */
    String CQ_LIVE_COPY = "cq:LiveCopy";
    /** mixin type of a node with a live relationship to its blueprint */
    String CQ_LIVE_RELATIONSHIP = "cq:LiveRelationship";
    /** mixin type of a node with a cancelled live sync */
    String CQ_LIVE_SYNC_CANCELLED = "cq:LiveSyncCancelled";
    /** node type of a client library folder */
    String CQ_CLIENT_LIBRARY_FOLDER = "cq:ClientLibraryFolder";
    /** node type of a DAM asset */
    String DAM_ASSET = "dam:Asset";
    /** node type of the content node of a DAM asset */
    String DAM_ASSET_CONTENT = "dam:AssetContent";
}
