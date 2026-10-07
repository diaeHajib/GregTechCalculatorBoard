package com.gtceu.calcboard.client.gui.interaction;

import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.history.BoardCommand;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import com.gtceu.calcboard.client.gui.BoardScreen;
import com.gtceu.calcboard.client.gui.widget.NodeWidget;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.Set;

public class CanvasContextMenuManagerTest {
    private static final class TargetScreen extends BoardScreen {
        final FlowGraph graph = new FlowGraph();
        BoardCommand command;
        boolean editable = true;

        @Override public FlowGraph getGraph() { return graph; }
        @Override public boolean ensureEditPermission() { return editable; }
        @Override public void recordCommand(BoardCommand command) { this.command = command; }
        @Override public void markSummaryDirty() { graph.markSummaryDirty(); }
        @Override public void markTeamDirty() {}
    }

    private static void invoke(CanvasContextMenuManager menu, String key) {
        menu.getItems().stream().filter(item -> key.equals(item.labelKey())).findFirst().orElseThrow().action().run();
    }

    @Test
    void machineTargetsAreIndependentOfSelectionAndClearableWithUndo() {
        TargetScreen screen = new TargetScreen();
        RecipeNode node = RecipeNode.create("Target", 20, 20, GTVoltageTier.MV);
        screen.graph.addNode(node);
        CanvasContextMenuManager menu = new CanvasContextMenuManager(screen);
        menu.openForNode(100, 100, new NodeWidget(node));
        invoke(menu, "gui.gtcalcboard.menu.optimize_machines");
        Assertions.assertEquals(Set.of(node.getId()), screen.graph.getRecommendationTargetIds());
        screen.getSelectedNodeIds().clear();
        Assertions.assertEquals(Set.of(node.getId()), screen.graph.getRecommendationTargetIds());
        menu.openForCanvas(100, 100, 100, 100);
        invoke(menu, "gui.gtcalcboard.menu.clear_optimization");
        Assertions.assertTrue(screen.graph.getRecommendationTargetIds().isEmpty());
        screen.command.undo(screen.graph);
        Assertions.assertEquals(Set.of(node.getId()), screen.graph.getRecommendationTargetIds());
    }

    @Test
    void multiSelectionTargetsMachinesOnlyAndHonorsEditPermission() {
        TargetScreen screen = new TargetScreen();
        RecipeNode first = RecipeNode.create("First", 20, 20, GTVoltageTier.MV);
        RecipeNode second = RecipeNode.create("Second", 20, 20, GTVoltageTier.MV);
        RecipeNode junction = RecipeNode.createReroute(0, 0);
        screen.graph.addNode(first);
        screen.graph.addNode(second);
        screen.graph.addNode(junction);
        screen.getSelectedNodeIds().addAll(Set.of(first.getId(), second.getId(), junction.getId()));
        CanvasContextMenuManager menu = new CanvasContextMenuManager(screen);
        menu.openForSelection(100, 100);
        screen.editable = false;
        invoke(menu, "gui.gtcalcboard.menu.optimize_machines");
        Assertions.assertTrue(screen.graph.getRecommendationTargetIds().isEmpty());
        screen.editable = true;
        invoke(menu, "gui.gtcalcboard.menu.optimize_machines");
        Assertions.assertEquals(Set.of(first.getId(), second.getId()), screen.graph.getRecommendationTargetIds());
    }

    @Test
    public void testJunctionContextMenuContainsFlipAction() {
        CanvasContextMenuManager menuManager = new CanvasContextMenuManager(null);
        RecipeNode reroute = RecipeNode.createReroute(100.0, 100.0);
        NodeWidget widget = new NodeWidget(reroute);
        menuManager.openForJunctionNode(100, 100, widget);

        var flipItemOpt = menuManager.getItems().stream()
                .filter(item -> "gui.gtcalcboard.menu.flip_node".equals(item.labelKey()) && "F".equals(item.shortcut()))
                .findFirst();
        Assertions.assertTrue(flipItemOpt.isPresent());

        var flipItem = flipItemOpt.get();
        Assertions.assertFalse(reroute.isFlipped());
        flipItem.action().run();
        Assertions.assertTrue(reroute.isFlipped());
        flipItem.action().run();
        Assertions.assertFalse(reroute.isFlipped());
    }

    @Test
    public void testStandardNodeContextMenuContainsFlipAction() {
        CanvasContextMenuManager menuManager = new CanvasContextMenuManager(null);
        RecipeNode node = RecipeNode.create("Centrifuge", 20.0, 32.0, GTVoltageTier.LV);
        NodeWidget widget = new NodeWidget(node);
        menuManager.openForNode(100, 100, widget);

        var flipItemOpt = menuManager.getItems().stream()
                .filter(item -> "gui.gtcalcboard.menu.flip_node".equals(item.labelKey()) && "F".equals(item.shortcut()))
                .findFirst();
        Assertions.assertTrue(flipItemOpt.isPresent());

        var flipItem = flipItemOpt.get();
        Assertions.assertFalse(node.isFlipped());
        flipItem.action().run();
        Assertions.assertTrue(node.isFlipped());
        flipItem.action().run();
        Assertions.assertFalse(node.isFlipped());
    }

    @Test
    public void testFrameContextMenuContainsConfigureAndDeleteActions() {
        CanvasContextMenuManager menuManager = new CanvasContextMenuManager(null);
        com.gtceu.calcboard.api.model.CanvasGroupFrame frame = new com.gtceu.calcboard.api.model.CanvasGroupFrame(
                "f1", "Test Frame", com.gtceu.calcboard.api.model.CanvasGroupFrame.COLOR_CYAN, 0, 0, 200, 200
        );
        menuManager.openForFrame(100, 100, frame);

        var configOpt = menuManager.getItems().stream()
                .filter(item -> "gui.gtcalcboard.menu.configure_frame".equals(item.labelKey()))
                .findFirst();
        Assertions.assertTrue(configOpt.isPresent(), "Configure frame menu item should be present");

        var deleteOpt = menuManager.getItems().stream()
                .filter(item -> "gui.gtcalcboard.menu.delete_frame".equals(item.labelKey()) && item.isDanger())
                .findFirst();
        Assertions.assertTrue(deleteOpt.isPresent(), "Delete frame menu item should be present");
        Assertions.assertEquals("Del", deleteOpt.get().shortcut());
    }

    @Test
    public void testSelectionContextMenuContainsAutoConnectWhenAtLeastTwoNodesSelected() {
        BoardScreen screen = new BoardScreen();
        CanvasContextMenuManager menuManager = new CanvasContextMenuManager(screen);

        screen.getSelectedNodeIds().add("n1");
        menuManager.openForSelection(100, 100);
        Assertions.assertFalse(menuManager.getItems().stream()
                .anyMatch(item -> "gui.gtcalcboard.menu.auto_connect".equals(item.labelKey())));

        screen.getSelectedNodeIds().add("n2");
        menuManager.openForSelection(100, 100);
        Assertions.assertTrue(menuManager.getItems().stream()
                .anyMatch(item -> "gui.gtcalcboard.menu.auto_connect".equals(item.labelKey())));
    }
}
