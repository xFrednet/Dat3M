package com.dat3m.dartagnan.analysis.saturation.graphs.relationGraphs.stat;

import com.dat3m.dartagnan.analysis.saturation.graphs.relationGraphs.Edge;
import com.dat3m.dartagnan.analysis.saturation.util.EdgeDirection;
import com.dat3m.dartagnan.verification.model.EventData;
import com.dat3m.dartagnan.verification.model.ExecutionModel;

import java.util.stream.Stream;

public class IdentityGraph extends StaticRelationGraph {

    @Override
    public boolean contains(Edge edge) {
        return edge.isLoop();
    }

    @Override
    public boolean contains(EventData a, EventData b) {
        return a == b;
    }

    @Override
    public int getMinSize(EventData e, EdgeDirection dir) {
        return 1;
    }

    @Override
    public Stream<Edge> edgeStream() {
        return model.getEventList().stream().map(e -> new Edge(e, e));
    }

    @Override
    public Stream<Edge> edgeStream(EventData e, EdgeDirection dir) {
        return Stream.of(new Edge(e, e));
    }

    @Override
    public void constructFromModel(ExecutionModel model) {
        super.constructFromModel(model);
        size = model.getEventList().size();
    }

}