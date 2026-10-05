package com.gtceu.calcboard.api.history.command;

import com.gtceu.calcboard.api.history.BoardCommand;
import com.gtceu.calcboard.api.model.CanvasGroupFrame;
import com.gtceu.calcboard.api.model.CanvasStickyNote;
import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.api.type.OverclockMode;
import com.gtceu.calcboard.api.type.SteamMode;
import net.minecraft.resources.ResourceLocation;

import com.gtceu.calcboard.api.storage.BoardManager;
import com.gtceu.calcboard.api.storage.BoardPage;

import java.util.*;

/**
 * Compound grouping of multiple nodes into a single compound module.
 */
public class GroupModuleCommand implements BoardCommand {
    private final List<RecipeNode> groupedNodes;
    private final RecipeNode moduleNode;
    private final List<FlowGraph.ConnectionEdge> originalEdges;
    private final List<FlowGraph.ConnectionEdge> rewires;
    private final List<CanvasGroupFrame> capturedFrames;
    private final List<CanvasStickyNote> capturedNotes;
    private final BoardPage capturedSubPage;

    public GroupModuleCommand(
            List<RecipeNode> groupedNodes,
            RecipeNode moduleNode,
            List<FlowGraph.ConnectionEdge> originalEdges,
            List<FlowGraph.ConnectionEdge> rewires,
            List<CanvasGroupFrame> capturedFrames,
            List<CanvasStickyNote> capturedNotes,
            BoardPage capturedSubPage
    ) {
        this.groupedNodes = new ArrayList<>(groupedNodes);
        this.moduleNode = moduleNode;
        this.originalEdges = new ArrayList<>(originalEdges);
        this.rewires = new ArrayList<>(rewires);
        this.capturedFrames = capturedFrames != null ? new ArrayList<>(capturedFrames) : Collections.emptyList();
        this.capturedNotes = capturedNotes != null ? new ArrayList<>(capturedNotes) : Collections.emptyList();
        this.capturedSubPage = capturedSubPage != null ? capturedSubPage
                : ((moduleNode != null && moduleNode.getSubPageId() != null)
                        ? com.gtceu.calcboard.api.storage.WorkspacePageRegistry.resolvePage(moduleNode.getSubPageId())
                        : null);
    }

    public GroupModuleCommand(
            List<RecipeNode> groupedNodes,
            RecipeNode moduleNode,
            List<FlowGraph.ConnectionEdge> originalEdges,
            List<FlowGraph.ConnectionEdge> rewires,
            List<CanvasGroupFrame> capturedFrames,
            List<CanvasStickyNote> capturedNotes
    ) {
        this(groupedNodes, moduleNode, originalEdges, rewires, capturedFrames, capturedNotes, null);
    }

    public GroupModuleCommand(List<RecipeNode> groupedNodes, RecipeNode moduleNode, List<FlowGraph.ConnectionEdge> originalEdges, List<FlowGraph.ConnectionEdge> rewires) {
        this(groupedNodes, moduleNode, originalEdges, rewires,
                moduleNode != null && moduleNode.getSubGraph() != null ? moduleNode.getSubGraph().getFrames() : Collections.emptyList(),
                moduleNode != null && moduleNode.getSubGraph() != null ? moduleNode.getSubGraph().getStickyNotes() : Collections.emptyList(),
                null);
    }

    @Override
    public void undo(FlowGraph graph) {
        graph.removeNode(moduleNode);
        for (FlowGraph.ConnectionEdge r : rewires) {
            graph.removeConnection(r);
        }
        for (RecipeNode n : groupedNodes) {
            if (!graph.getNodes().contains(n)) {
                graph.addNode(n);
            }
        }
        for (FlowGraph.ConnectionEdge e : originalEdges) {
            graph.addConnection(e);
        }
        for (CanvasGroupFrame f : capturedFrames) {
            if (!graph.getFrames().contains(f)) {
                graph.addFrame(f);
            }
        }
        for (CanvasStickyNote note : capturedNotes) {
            if (!graph.getStickyNotes().contains(note)) {
                graph.addStickyNote(note);
            }
        }
        cleanUpSubPage();
    }

    @Override
    public void redo(FlowGraph graph) {
        for (RecipeNode n : groupedNodes) {
            graph.removeNode(n);
        }
        if (!graph.getNodes().contains(moduleNode)) {
            graph.addNode(moduleNode);
        }
        for (FlowGraph.ConnectionEdge r : rewires) {
            graph.addConnection(r);
        }
        for (CanvasGroupFrame f : capturedFrames) {
            graph.removeFrame(f);
        }
        for (CanvasStickyNote note : capturedNotes) {
            graph.removeStickyNote(note);
        }
        restoreSubPage();
    }

    private void cleanUpSubPage() {
        String subPageId = capturedSubPage != null ? capturedSubPage.getId() : (moduleNode != null ? moduleNode.getSubPageId() : null);
        if (subPageId != null) {
            com.gtceu.calcboard.api.solver.FlowGraphModuleHandler.removeModuleSubPageSafely(subPageId);
        }
    }

    private void restoreSubPage() {
        if (capturedSubPage == null) return;
        com.gtceu.calcboard.api.storage.WorkspacePageRegistry.restoreModuleSubPage(capturedSubPage);
    }

    @Override
    public String getDescription() {
        return "Group " + groupedNodes.size() + " components into Module";
    }
}
