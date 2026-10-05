package com.gtceu.calcboard.client.team;

import com.gtceu.calcboard.api.history.HistoryManager;
import com.gtceu.calcboard.api.history.command.ExpandModuleCommand;
import com.gtceu.calcboard.api.history.command.GroupModuleCommand;
import com.gtceu.calcboard.api.model.BoundaryPinNode;
import com.gtceu.calcboard.api.model.FlowGraph;
import com.gtceu.calcboard.api.model.IngredientStack;
import com.gtceu.calcboard.api.model.ModuleInputPin;
import com.gtceu.calcboard.api.model.ModuleOutputPin;
import com.gtceu.calcboard.api.model.RecipeNode;
import com.gtceu.calcboard.api.solver.FlowGraphModuleHandler;
import com.gtceu.calcboard.api.storage.BoardManager;
import com.gtceu.calcboard.api.storage.BoardPage;
import com.gtceu.calcboard.api.storage.PageType;
import com.gtceu.calcboard.api.team.TeamWorkspacePage;
import com.gtceu.calcboard.api.type.GTVoltageTier;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TeamSubPageModuleIntegrationTest {

    private ClientWorkspaceState state;
    private FlowGraph teamMainGraph;
    private final String mainPageId = "page_team_main";

    @BeforeEach
    public void setUp() {
        BoardManager.getInstance().getPageManager().resetToDefault();
        BoardManager.getInstance().getPageManager().getPages().get(0).setName("Local Personal Factory");

        state = ClientWorkspaceState.getInstance();
        state.clear();
        state.setCurrentMode(ClientWorkspaceState.WorkspaceMode.TEAM);
        state.setCurrentTeamId(UUID.randomUUID());
        state.setCurrentTeamName("Engineering Team");

        TeamWorkspacePage mainTeamPage = new TeamWorkspacePage(mainPageId, "Team Main Factory");
        teamMainGraph = new FlowGraph();
        state.addTeamPage(mainTeamPage, teamMainGraph);
        state.setActiveTeamPageId(mainPageId);
    }

    @AfterEach
    public void tearDown() {
        state.clear();
        BoardManager.getInstance().getPageManager().resetToDefault();
    }

    private RecipeNode[] createTwoConnectedNodes() {
        RecipeNode n1 = RecipeNode.create("Distillation Tower", 200, 30, GTVoltageTier.EV);
        n1.setPos(150, 150);
        IngredientStack oil = IngredientStack.fluid(ResourceLocation.tryParse("gtceu:oil"), "Crude Oil", 1000);
        IngredientStack nap = IngredientStack.fluid(ResourceLocation.tryParse("gtceu:naphtha"), "Naphtha", 500);
        n1.getInputs().add(oil);
        n1.getOutputs().add(nap);

        RecipeNode n2 = RecipeNode.create("Cracking Unit", 150, 25, GTVoltageTier.EV);
        n2.setPos(350, 150);
        IngredientStack eth = IngredientStack.fluid(ResourceLocation.tryParse("gtceu:ethylene"), "Ethylene", 400);
        n2.getInputs().add(nap);
        n2.getOutputs().add(eth);

        teamMainGraph.addNode(n1);
        teamMainGraph.addNode(n2);
        teamMainGraph.addConnection(n1.getId(), 0, n2.getId(), 0);

        return new RecipeNode[]{n1, n2};
    }

    @Test
    public void testGroupIntoModuleInTeamModeAddsSubPageToTeamWorkspaceAndKeepsLocalClean() {
        RecipeNode[] nodes = createTwoConnectedNodes();
        int initialLocalPageCount = BoardManager.getInstance().getPages().size();
        Assertions.assertEquals(1, initialLocalPageCount);

        RecipeNode moduleNode = FlowGraphModuleHandler.groupIntoModule(
                teamMainGraph,
                Set.of(nodes[0].getId(), nodes[1].getId()),
                "Hydrocarbon Processing"
        );

        Assertions.assertNotNull(moduleNode);
        Assertions.assertTrue(moduleNode.isModule());

        String subPageId = moduleNode.getSubPageId();
        Assertions.assertNotNull(subPageId);
        Assertions.assertFalse(subPageId.isEmpty());

        Assertions.assertEquals(initialLocalPageCount, BoardManager.getInstance().getPages().size());
        Assertions.assertTrue(BoardManager.getInstance().getPage(subPageId).isEmpty());

        TeamWorkspacePage teamSubPage = state.getRemotePage(subPageId);
        Assertions.assertNotNull(teamSubPage);
        Assertions.assertEquals(PageType.MODULE, teamSubPage.getPageType());
        Assertions.assertEquals(mainPageId, teamSubPage.getParentPageId());
        Assertions.assertEquals(moduleNode.getId(), teamSubPage.getParentModuleNodeId());
        Assertions.assertEquals("Hydrocarbon Processing", teamSubPage.getTitle());

        BoardPage subBoardPage = state.getTeamPageAsBoardPage(subPageId);
        Assertions.assertNotNull(subBoardPage);
        Assertions.assertTrue(subBoardPage.isModuleSubPage());
        Assertions.assertEquals(mainPageId, subBoardPage.getParentPageId());
        Assertions.assertEquals(moduleNode.getId(), subBoardPage.getParentModuleNodeId());

        FlowGraph subGraph = state.getTeamGraph(subPageId);
        Assertions.assertNotNull(subGraph);
        Assertions.assertEquals(2, moduleNode.getContainedMachineCount());

        boolean hasInPin = subGraph.getNodes().stream().anyMatch(n -> n instanceof ModuleInputPin);
        boolean hasOutPin = subGraph.getNodes().stream().anyMatch(n -> n instanceof ModuleOutputPin);
        Assertions.assertTrue(hasInPin);
        Assertions.assertTrue(hasOutPin);
    }

    @Test
    public void testExpandModuleInTeamModeRemovesSubPageFromTeamWorkspace() {
        RecipeNode[] nodes = createTwoConnectedNodes();
        RecipeNode moduleNode = FlowGraphModuleHandler.groupIntoModule(
                teamMainGraph,
                Set.of(nodes[0].getId(), nodes[1].getId()),
                "Hydrocarbon Processing"
        );
        Assertions.assertNotNull(moduleNode);
        String subPageId = moduleNode.getSubPageId();
        Assertions.assertNotNull(state.getRemotePage(subPageId));

        boolean expanded = FlowGraphModuleHandler.expandModule(teamMainGraph, moduleNode);
        Assertions.assertTrue(expanded);

        Assertions.assertNull(state.getRemotePage(subPageId));
        Assertions.assertNull(state.getTeamPageAsBoardPage(subPageId));
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());

        boolean hasBoundaryPins = teamMainGraph.getNodes().stream().anyMatch(n -> n instanceof BoundaryPinNode);
        Assertions.assertFalse(hasBoundaryPins);
        Assertions.assertTrue(teamMainGraph.getNodes().stream().anyMatch(n -> n.getId().equals(nodes[0].getId())));
        Assertions.assertTrue(teamMainGraph.getNodes().stream().anyMatch(n -> n.getId().equals(nodes[1].getId())));
    }

    @Test
    public void testUndoRedoGroupModuleInTeamMode() {
        RecipeNode[] nodes = createTwoConnectedNodes();
        HistoryManager historyManager = new HistoryManager();

        List<RecipeNode> origNodes = new ArrayList<>(teamMainGraph.getNodes());
        List<FlowGraph.ConnectionEdge> origEdges = new ArrayList<>(teamMainGraph.getConnections());

        RecipeNode moduleNode = FlowGraphModuleHandler.groupIntoModule(
                teamMainGraph,
                Set.of(nodes[0].getId(), nodes[1].getId()),
                "Hydrocarbon Module"
        );
        Assertions.assertNotNull(moduleNode);
        String subPageId = moduleNode.getSubPageId();

        List<RecipeNode> groupedNodes = new ArrayList<>();
        for (RecipeNode n : origNodes) {
            if (!teamMainGraph.getNodes().contains(n)) {
                groupedNodes.add(n);
            }
        }
        List<FlowGraph.ConnectionEdge> rewires = new ArrayList<>(teamMainGraph.getConnections());

        BoardPage capturedSubPage = state.getTeamPageAsBoardPage(subPageId);
        historyManager.record(new GroupModuleCommand(
                groupedNodes, moduleNode, origEdges, rewires,
                Collections.emptyList(), Collections.emptyList(), capturedSubPage
        ));

        Assertions.assertNotNull(state.getRemotePage(subPageId));
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());

        historyManager.undo(teamMainGraph);

        Assertions.assertNull(state.getRemotePage(subPageId));
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());
        Assertions.assertTrue(teamMainGraph.getNodes().contains(nodes[0]));
        Assertions.assertTrue(teamMainGraph.getNodes().contains(nodes[1]));

        historyManager.redo(teamMainGraph);

        Assertions.assertNotNull(state.getRemotePage(subPageId));
        Assertions.assertEquals(PageType.MODULE, state.getRemotePage(subPageId).getPageType());
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());
        Assertions.assertTrue(teamMainGraph.getNodes().contains(moduleNode));
    }

    @Test
    public void testUndoRedoExpandModuleInTeamMode() {
        RecipeNode[] nodes = createTwoConnectedNodes();
        HistoryManager historyManager = new HistoryManager();

        RecipeNode moduleNode = FlowGraphModuleHandler.groupIntoModule(
                teamMainGraph,
                Set.of(nodes[0].getId(), nodes[1].getId()),
                "Hydrocarbon Module"
        );
        Assertions.assertNotNull(moduleNode);
        String subPageId = moduleNode.getSubPageId();
        BoardPage capturedSubPage = state.getTeamPageAsBoardPage(subPageId);

        List<FlowGraph.ConnectionEdge> moduleEdges = new ArrayList<>(teamMainGraph.getConnections());

        FlowGraphModuleHandler.expandModule(teamMainGraph, moduleNode);
        Assertions.assertNull(state.getRemotePage(subPageId));

        List<RecipeNode> expandedNodes = new ArrayList<>(teamMainGraph.getNodes());
        List<FlowGraph.ConnectionEdge> restoredEdges = new ArrayList<>(teamMainGraph.getConnections());

        historyManager.record(new ExpandModuleCommand(
                moduleNode, expandedNodes, restoredEdges, moduleEdges,
                Collections.emptyList(), Collections.emptyList(), capturedSubPage
        ));

        historyManager.undo(teamMainGraph);

        Assertions.assertTrue(teamMainGraph.getNodes().contains(moduleNode));
        Assertions.assertNotNull(state.getRemotePage(subPageId));
        Assertions.assertEquals(PageType.MODULE, state.getRemotePage(subPageId).getPageType());
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());

        historyManager.redo(teamMainGraph);

        Assertions.assertFalse(teamMainGraph.getNodes().contains(moduleNode));
        Assertions.assertNull(state.getRemotePage(subPageId));
        Assertions.assertEquals(1, BoardManager.getInstance().getPages().size());
    }

    @Test
    public void testResolveActiveWorkspacePageHierarchyInTeamMode() {
        RecipeNode[] nodes = createTwoConnectedNodes();
        RecipeNode moduleNode = FlowGraphModuleHandler.groupIntoModule(
                teamMainGraph,
                Set.of(nodes[0].getId(), nodes[1].getId()),
                "Hydrocarbon Hierarchy"
        );
        Assertions.assertNotNull(moduleNode);
        String subPageId = moduleNode.getSubPageId();

        BoardPage resolvedSubPage = ClientWorkspaceState.resolveActiveWorkspacePage(subPageId);
        Assertions.assertNotNull(resolvedSubPage);
        Assertions.assertTrue(resolvedSubPage.isModuleSubPage());
        Assertions.assertEquals(mainPageId, resolvedSubPage.getParentPageId());
        Assertions.assertEquals(moduleNode.getId(), resolvedSubPage.getParentModuleNodeId());

        BoardPage resolvedParent = ClientWorkspaceState.resolveActiveWorkspacePage(resolvedSubPage.getParentPageId());
        Assertions.assertNotNull(resolvedParent);
        Assertions.assertEquals(mainPageId, resolvedParent.getId());
        Assertions.assertEquals("Team Main Factory", resolvedParent.getName());
    }
}
