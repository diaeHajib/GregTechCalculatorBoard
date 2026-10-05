package com.gtceu.calcboard.api.storage;

import com.gtceu.calcboard.api.model.FlowGraph;

/**
 * Global registry and dispatcher for workspace page operations.
 * Routes page resolution and module subpage management to the appropriate
 * handler based on the active graph and collaboration context.
 */
public final class WorkspacePageRegistry {

    private static final IWorkspacePageHandler DEFAULT_HANDLER = new DefaultLocalPageHandler();
    private static volatile IWorkspacePageHandler collaborativeHandler = null;

    private WorkspacePageRegistry() {}

    /**
     * Registers a collaborative workspace page handler.
     *
     * @param handler the collaborative handler to register
     */
    public static void registerCollaborativeHandler(IWorkspacePageHandler handler) {
        collaborativeHandler = handler;
    }

    /**
     * Unregisters the active collaborative workspace page handler.
     */
    public static void unregisterCollaborativeHandler() {
        collaborativeHandler = null;
    }

    /**
     * Determines and returns the appropriate page handler for the given flow graph.
     *
     * @param graph the flow graph context
     * @return the applicable page handler
     */
    public static IWorkspacePageHandler getHandler(FlowGraph graph) {
        IWorkspacePageHandler handler = collaborativeHandler;
        if (handler != null && handler.isApplicable(graph)) {
            return handler;
        }
        return DEFAULT_HANDLER;
    }

    /**
     * Resolves a board page by ID across collaborative and local registries.
     *
     * @param pageId the page identifier
     * @return the resolved board page, or null if not found
     */
    public static BoardPage resolvePage(String pageId) {
        IWorkspacePageHandler handler = collaborativeHandler;
        if (handler != null) {
            BoardPage page = handler.resolvePage(pageId);
            if (page != null) {
                return page;
            }
        }
        return DEFAULT_HANDLER.resolvePage(pageId);
    }

    /**
     * Finds the board page that contains the specified flow graph.
     *
     * @param graph the flow graph to look up
     * @return the owning board page, or null if not found
     */
    public static BoardPage findPageForGraph(FlowGraph graph) {
        return getHandler(graph).findPageForGraph(graph);
    }

    /**
     * Dispatches the addition of a composite module subpage to the appropriate handler.
     *
     * @param graph              the context flow graph
     * @param subPageId          the unique identifier for the subpage
     * @param title              the display title of the subpage
     * @param subGraph           the flow graph contained within the subpage
     * @param parentPageId       the parent page identifier
     * @param parentModuleNodeId the parent module node identifier
     */
    public static void addModuleSubPage(FlowGraph graph, String subPageId, String title, FlowGraph subGraph, String parentPageId, String parentModuleNodeId) {
        getHandler(graph).addModuleSubPage(subPageId, title, subGraph, parentPageId, parentModuleNodeId);
    }

    /**
     * Dispatches the removal of a composite module subpage to the appropriate handler.
     *
     * @param subPageId the identifier of the subpage to remove
     */
    public static void removeModuleSubPage(String subPageId) {
        IWorkspacePageHandler handler = collaborativeHandler;
        if (handler != null && handler.resolvePage(subPageId) != null) {
            handler.removeModuleSubPage(subPageId);
            return;
        }
        DEFAULT_HANDLER.removeModuleSubPage(subPageId);
    }

    /**
     * Restores a previously removed composite module subpage to the appropriate handler.
     *
     * @param page the board page to restore
     */
    public static void restoreModuleSubPage(BoardPage page) {
        if (page == null) return;
        IWorkspacePageHandler handler = collaborativeHandler;
        if (handler != null && handler.isApplicable(page.getGraph())) {
            handler.restoreModuleSubPage(page);
            return;
        }
        DEFAULT_HANDLER.restoreModuleSubPage(page);
    }
}
