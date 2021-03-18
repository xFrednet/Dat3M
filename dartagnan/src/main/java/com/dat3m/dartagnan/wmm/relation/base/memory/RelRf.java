package com.dat3m.dartagnan.wmm.relation.base.memory;

import com.dat3m.dartagnan.program.event.Store;
import com.dat3m.dartagnan.program.utils.EType;
import com.dat3m.dartagnan.utils.Settings;
import com.dat3m.dartagnan.utils.equivalence.BranchEquivalence;
import com.dat3m.dartagnan.wmm.filter.FilterAbstract;
import com.dat3m.dartagnan.wmm.filter.FilterBasic;
import com.dat3m.dartagnan.wmm.filter.FilterMinus;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.IntExpr;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.program.event.MemEvent;
import com.dat3m.dartagnan.wmm.relation.Relation;
import com.dat3m.dartagnan.wmm.utils.Tuple;
import com.dat3m.dartagnan.wmm.utils.TupleSet;
import com.dat3m.dartagnan.program.Thread;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.wmm.utils.Utils.edge;

public class RelRf extends Relation {

    public RelRf(){
        term = "rf";
        forceDoEncode = true;
    }

    @Override
    public TupleSet getMinTupleSet(){
        if(minTupleSet == null){
            minTupleSet = new TupleSet();
        }
        return minTupleSet;
    }

    @Override
    public TupleSet getMaxTupleSet(){
        if(maxTupleSet == null){
            maxTupleSet = new TupleSet();

            List<Event> eventsLoad = task.getProgram().getCache().getEvents(FilterBasic.get(EType.READ));
            List<Event> eventsInit = task.getProgram().getCache().getEvents(FilterBasic.get(EType.INIT));
            List<Event> eventsStore = task.getProgram().getCache().getEvents(FilterMinus.get(
                    FilterBasic.get(EType.WRITE),
                    FilterBasic.get(EType.INIT)
            ));

            for(Event e1 : eventsInit){
                for(Event e2 : eventsLoad){
                    if(MemEvent.canAddressTheSameLocation((MemEvent) e1, (MemEvent) e2)){
                        maxTupleSet.add(new Tuple(e1, e2));
                    }
                }
            }

            for(Event e1 : eventsStore){
                for(Event e2 : eventsLoad){
                    if(MemEvent.canAddressTheSameLocation((MemEvent) e1, (MemEvent) e2)){
                    	maxTupleSet.add(new Tuple(e1, e2));
                    }
                }
            }
            removeMutuallyExclusiveTuples(maxTupleSet);

            if (task.getMemoryModel().isLocallyConsistent()) {
                int before = maxTupleSet.size();
                System.out.println("Read-From before: " + before);
                // Remove future reads
                maxTupleSet.removeIf(t -> t.getFirst().getThread() == t.getSecond().getThread()
                                && t.getFirst().getCId() >= t.getSecond().getCId());
                System.out.println("Removed future reads: " + (before - maxTupleSet.size()));

                // Remove past reads
                BranchEquivalence eq = task.getBranchEquivalence();
                Set<Tuple> deletedTuples = new HashSet<>();
                for (Event read: task.getProgram().getCache().getEvents(FilterBasic.get(EType.READ))) {
                    if (((MemEvent)read).getMaxAddressSet().size() != 1)
                        continue;

                    List<MemEvent> possibleWrites = maxTupleSet.getBySecond(read).stream().map(Tuple::getFirst)
                            .filter(e -> e.getThread() == read.getThread() || e.is(EType.INIT))
                            .map(x -> (MemEvent)x)
                            .sorted((o1, o2) -> o1.is(EType.INIT) == o2.is(EType.INIT) ? (o1.getCId() - o2.getCId()) : o1.is(EType.INIT) ? -1 : 1)
                            .collect(Collectors.toList());
                    possibleWrites.removeIf(x -> x.getMaxAddressSet().size() != 1);
                    Set<MemEvent> deletedWrites = new HashSet<>();

                    if (((MemEvent)read).getMaxAddressSet().size() == 1){
                        List<Event> impliedWrites = possibleWrites.stream().filter(x -> x.getMaxAddressSet().size() == 1 && eq.isImplied(read, x)).collect(Collectors.toList());
                        if (!impliedWrites.isEmpty()) {
                            Event lastImplied = impliedWrites.get(impliedWrites.size() - 1);
                            if (!lastImplied.is(EType.INIT)) {
                                Predicate<Event> pred = x -> x.is(EType.INIT) || x.getCId() < lastImplied.getCId();
                                possibleWrites.stream().filter(pred).forEach(deletedWrites::add);
                                possibleWrites.removeIf(pred);
                            }
                        }
                    }
                    possibleWrites.stream().filter(x ->
                            (x.is(EType.INIT) && possibleWrites.stream().anyMatch(y -> !y.is(EType.INIT) && eq.isImplied(x ,y)))
                             || (!x.is(EType.INIT) && possibleWrites.stream().anyMatch(y -> !y.is(EType.INIT) && x.getCId() < y.getCId() && y.getMaxAddressSet().size() == 1 && eq.isImplied(x ,y))))
                            .forEach(deletedWrites::add);
                    for (Event w : deletedWrites) {
                        deletedTuples.add(new Tuple(w, read));
                    }
                }
                maxTupleSet.removeAll(deletedTuples);
                System.out.println("Removed past reads: " + deletedTuples.size());
                System.out.println("Read-From after: " + maxTupleSet.size());
            }
        }
        return maxTupleSet;
    }

    @Override
    protected BoolExpr encodeApprox(Context ctx) {
        BoolExpr enc = ctx.mkTrue();
        Map<MemEvent, List<BoolExpr>> edgeMap = new HashMap<>();
        Map<MemEvent, BoolExpr> memInitMap = new HashMap<>();

        boolean canAccNonInitMem = task.getSettings().getFlag(Settings.FLAG_CAN_ACCESS_UNINITIALIZED_MEMORY);
        boolean useSeqEncoding = task.getSettings().getFlag(Settings.FLAG_USE_SEQ_ENCODING_REL_RF);

        for(Tuple tuple : maxTupleSet){
            MemEvent w = (MemEvent) tuple.getFirst();
            MemEvent r = (MemEvent) tuple.getSecond();
            BoolExpr edge = this.getSMTVar(tuple, ctx);
            
            IntExpr a1 = w.getMemAddressExpr().isBV() ? ctx.mkBV2Int((BitVecExpr)w.getMemAddressExpr(), false) : (IntExpr)w.getMemAddressExpr();
            IntExpr a2 = r.getMemAddressExpr().isBV() ? ctx.mkBV2Int((BitVecExpr)r.getMemAddressExpr(), false) : (IntExpr)r.getMemAddressExpr();
            BoolExpr sameAddress = ctx.mkEq(a1, a2);
            
            IntExpr v1 = w.getMemValueExpr().isBV() ? ctx.mkBV2Int((BitVecExpr)w.getMemValueExpr(), false) : (IntExpr)w.getMemValueExpr();
            IntExpr v2 = r.getMemValueExpr().isBV() ? ctx.mkBV2Int((BitVecExpr)r.getMemValueExpr(), false) : (IntExpr)r.getMemValueExpr();
            BoolExpr sameValue = ctx.mkEq(v1, v2);

            edgeMap.putIfAbsent(r, new ArrayList<>());
            edgeMap.get(r).add(edge);
            if(canAccNonInitMem && w.is(EType.INIT)){
                memInitMap.put(r, ctx.mkOr(memInitMap.getOrDefault(r, ctx.mkFalse()), sameAddress));
            }
            enc = ctx.mkAnd(enc, ctx.mkImplies(edge, ctx.mkAnd(w.exec(), r.exec(), sameAddress, sameValue)));
        }

        for(MemEvent r : edgeMap.keySet()){
            enc = ctx.mkAnd(enc, useSeqEncoding
                    ? encodeEdgeSeq(r, memInitMap.get(r), edgeMap.get(r), ctx)
                    : encodeEdgeNaive(r, memInitMap.get(r), edgeMap.get(r), ctx));
        }
        return enc;
    }

    private BoolExpr encodeEdgeNaive(Event read, BoolExpr isMemInit, List<BoolExpr> edges, Context ctx){
        BoolExpr atMostOne = ctx.mkTrue();
        BoolExpr atLeastOne = ctx.mkFalse();
        for(int i = 0; i < edges.size(); i++){
            atLeastOne = ctx.mkOr(atLeastOne, edges.get(i));
            for(int j = i + 1; j < edges.size(); j++){
                atMostOne = ctx.mkAnd(atMostOne, ctx.mkNot(ctx.mkAnd(edges.get(i), edges.get(j))));
            }
        }

        if(task.getSettings().getFlag(Settings.FLAG_CAN_ACCESS_UNINITIALIZED_MEMORY)) {
            atLeastOne = ctx.mkImplies(ctx.mkAnd(read.exec(), isMemInit), atLeastOne);
        } else {
            atLeastOne = ctx.mkImplies(read.exec(), atLeastOne);
        }
        return ctx.mkAnd(atMostOne, atLeastOne);
    }

    private BoolExpr encodeEdgeSeq(Event read, BoolExpr isMemInit, List<BoolExpr> edges, Context ctx){
        int num = edges.size();
        int readId = read.getCId();
        BoolExpr lastSeqVar = mkSeqVar(readId, 0, ctx);
        BoolExpr newSeqVar = lastSeqVar;
        BoolExpr atMostOne = ctx.mkEq(lastSeqVar, edges.get(0));

        for(int i = 1; i < num; i++){
            newSeqVar = mkSeqVar(readId, i, ctx);
            atMostOne = ctx.mkAnd(atMostOne, ctx.mkEq(newSeqVar, ctx.mkOr(lastSeqVar, edges.get(i))));
            atMostOne = ctx.mkAnd(atMostOne, ctx.mkNot(ctx.mkAnd(edges.get(i), lastSeqVar)));
            lastSeqVar = newSeqVar;
        }
        BoolExpr atLeastOne = ctx.mkOr(newSeqVar, edges.get(edges.size() - 1));

        if(task.getSettings().getFlag(Settings.FLAG_CAN_ACCESS_UNINITIALIZED_MEMORY)) {
            atLeastOne = ctx.mkImplies(ctx.mkAnd(read.exec(), isMemInit), atLeastOne);
        } else {
            atLeastOne = ctx.mkImplies(read.exec(), atLeastOne);
        }
        return ctx.mkAnd(atMostOne, atLeastOne);
    }

    private BoolExpr mkSeqVar(int readId, int i, Context ctx) {
        return (BoolExpr) ctx.mkConst("s(" + term + ",E" + readId + "," + i + ")", ctx.mkBoolSort());
    }
}
