package com.gtceu.calcboard.api.history.command;

import com.gtceu.calcboard.api.history.BoardCommand;
import com.gtceu.calcboard.api.model.FlowGraph;

import java.util.Set;

public class RecommendationTargetsCommand implements BoardCommand {
    private final Set<String> before;
    private final Set<String> after;

    public RecommendationTargetsCommand(Set<String> before, Set<String> after) {
        this.before = Set.copyOf(before);
        this.after = Set.copyOf(after);
    }

    @Override
    public void undo(FlowGraph graph) {
        graph.setRecommendationTargetIds(before);
    }

    @Override
    public void redo(FlowGraph graph) {
        graph.setRecommendationTargetIds(after);
    }

    @Override
    public String getDescription() {
        return "Change machine recommendation targets";
    }
}
