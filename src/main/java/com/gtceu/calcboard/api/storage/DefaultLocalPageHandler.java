package com.gtceu.calcboard.api.storage;

import com.gtceu.calcboard.api.model.FlowGraph;

/**
 * Default local implementation of {@link IWorkspacePageHandler}.
 * Manages pages within the local singleplayer or client-side {@link BoardManager}.
 */
public class DefaultLocalPageHandler implements IWorkspacePageHandler {

    @Override
    public boolean isApplicable(FlowGraph graph) {
        return true;
    }

    @Override
    public BoardPage resolvePage(String pageId) {
        if (pageId == null || pageId.isEmpty()) return null;
        return BoardManager.getInstance().getPage(pageId).orElse(null);
    }

    @Override
    public BoardPage findPageForGraph(FlowGraph graph) {
        if (graph == null) return null;
        BoardManager bm = BoardManager.getInstance();
        for (BoardPage page : bm.getPages()) {
            if (page.getGraph() == graph) {
                return page;
            }
        }
        return bm.getActivePage();
    }

    @Override
    public void addModuleSubPage(String subPageId, String title, FlowGraph subGraph, String parentPageId, String parentModuleNodeId) {
        BoardPage subPage = new BoardPage(subPageId, title, subGraph);
        subPage.setPageType(PageType.MODULE);
        subPage.setParentPageId(parentPageId);
        subPage.setParentModuleNodeId(parentModuleNodeId);
        BoardManager.getInstance().getPageManager().addPage(subPage);
    }

    @Override
    public void removeModuleSubPage(String subPageId) {
        if (subPageId != null && !subPageId.isEmpty()) {
            BoardManager.getInstance().removePage(subPageId);
        }
    }

    @Override
    public void restoreModuleSubPage(BoardPage page) {
        if (page == null) return;
        if (BoardManager.getInstance().getPage(page.getId()).isEmpty()) {
            BoardManager.getInstance().getPageManager().addPage(page);
        }
    }
}
