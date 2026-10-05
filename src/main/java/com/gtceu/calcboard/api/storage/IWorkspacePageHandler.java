package com.gtceu.calcboard.api.storage;

import com.gtceu.calcboard.api.model.FlowGraph;

/**
 * Service provider interface for handling workspace page resolution, creation,
 * and lifecycle management across different workspace environments (local vs. collaborative).
 */
public interface IWorkspacePageHandler {

    /**
     * Determines whether this handler applies to the given flow graph context.
     *
     * @param graph the flow graph to inspect
     * @return true if this handler manages the workspace containing the graph
     */
    boolean isApplicable(FlowGraph graph);

    /**
     * Resolves a board page by its unique identifier.
     *
     * @param pageId the unique page identifier
     * @return the resolved board page, or null if not found
     */
    BoardPage resolvePage(String pageId);

    /**
     * Finds the parent board page that owns the specified flow graph.
     *
     * @param graph the flow graph
     * @return the owning board page, or null if not found
     */
    BoardPage findPageForGraph(FlowGraph graph);

    /**
     * Creates and registers a new composite module subpage in the active workspace.
     *
     * @param subPageId          the unique identifier for the subpage
     * @param title              the display title of the subpage
     * @param subGraph           the flow graph contained within the subpage
     * @param parentPageId       the parent page identifier
     * @param parentModuleNodeId the parent module node identifier on the parent page
     */
    void addModuleSubPage(String subPageId, String title, FlowGraph subGraph, String parentPageId, String parentModuleNodeId);

    /**
     * Removes a composite module subpage from the active workspace.
     *
     * @param subPageId the identifier of the subpage to remove
     */
    void removeModuleSubPage(String subPageId);

    /**
     * Restores a previously removed composite module subpage (e.g. for undo operations).
     *
     * @param page the board page to restore
     */
    void restoreModuleSubPage(BoardPage page);
}
