package com.dat3m.dartagnan.wmm.utils;

import com.dat3m.dartagnan.program.event.core.Event;

import java.util.Collections;
import java.util.Set;
import java.util.function.BiPredicate;

public class EmptyEventGraph extends EventGraph {

    protected static final EmptyEventGraph singleton = new EmptyEventGraph();

    protected EmptyEventGraph() {
        super(Collections.emptyMap());
    }

    @Override
    public boolean add(Event e1, Event e2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Event e1, Event e2) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(EventGraph other) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(EventGraph other) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(EventGraph other) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeIf(BiPredicate<Event, Event> f) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean addRange(Event e, Set<Event> range) {
        throw new UnsupportedOperationException();
    }
}
