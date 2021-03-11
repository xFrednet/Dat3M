package com.dat3m.dartagnan.analysis.graphRefinement;

import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.utils.Result;
import com.dat3m.dartagnan.verification.VerificationTask;
import com.dat3m.dartagnan.verification.model.ExecutionModel;
import com.dat3m.dartagnan.analysis.graphRefinement.coreReason.AbstractEdgeLiteral;
import com.dat3m.dartagnan.analysis.graphRefinement.coreReason.CoreLiteral;
import com.dat3m.dartagnan.analysis.graphRefinement.coreReason.EventLiteral;
import com.dat3m.dartagnan.analysis.graphRefinement.graphs.ExecutionGraph;
import com.dat3m.dartagnan.analysis.graphRefinement.logic.Conjunction;
import com.dat3m.dartagnan.analysis.graphRefinement.logic.DNF;
import com.dat3m.dartagnan.analysis.graphRefinement.logic.Literal;
import com.dat3m.dartagnan.analysis.graphRefinement.logic.SortedClauseSet;
import com.dat3m.dartagnan.analysis.graphRefinement.resolution.TreeResolution;
import com.dat3m.dartagnan.analysis.graphRefinement.searchTree.DecisionNode;
import com.dat3m.dartagnan.analysis.graphRefinement.searchTree.LeafNode;
import com.dat3m.dartagnan.analysis.graphRefinement.searchTree.SearchNode;
import com.dat3m.dartagnan.analysis.graphRefinement.searchTree.SearchTree;
import com.dat3m.dartagnan.verification.model.Edge;
import com.dat3m.dartagnan.verification.model.EventData;
import com.dat3m.dartagnan.analysis.graphRefinement.graphs.eventGraph.EventGraph;
import com.dat3m.dartagnan.analysis.graphRefinement.graphs.eventGraph.axiom.GraphAxiom;
import com.dat3m.dartagnan.utils.timeable.Timestamp;
import com.dat3m.dartagnan.wmm.relation.Relation;
import com.dat3m.dartagnan.wmm.utils.Tuple;
import com.dat3m.dartagnan.wmm.utils.TupleSet;
import com.microsoft.z3.Context;
import com.microsoft.z3.Model;

import java.util.*;


public class GraphRefinement {

    private final VerificationTask context;
    private final ExecutionGraph execGraph;

    // ====== Data specific for a single refinement =======
    private final ExecutionModel executionModel;


    public GraphRefinement(VerificationTask context) {
        this.context = context;
        this.execGraph = new ExecutionGraph(context);
        executionModel = new ExecutionModel(context);
    }


    private void populateFromModel(Model model, Context ctx) {
        executionModel.initialize(model, ctx, false);
        execGraph.initializeFromModel(executionModel);
        // TODO: Remove testing code
        testIteration();
        testStaticGraphs();
    }

    private final Map<Long, Set<Edge>> possibleCoEdges = new HashMap<>();
    private final SortedClauseSet<CoreLiteral> coreReasonsSorted = new SortedClauseSet<>();


    // Statistics of the last call to kSearch
    private RefinementStats stats;

    /*
    kSearch performs a sequence of k-Saturations, starting from 0 up to <maxSaturationDepth>
    It returns whether it was successful, what violations where found (if any) and the maximal saturation depth
    that was used.
     */
    public RefinementResult kSearch(Model model, Context ctx, int maxSaturationDepth) {
        RefinementResult result = new RefinementResult();
        stats = new RefinementStats();
        result.setStats(stats);

        // ====== Populate from model ======
        long curTime = System.currentTimeMillis();
        populateFromModel(model, ctx);
        stats.modelConstructionTime = System.currentTimeMillis() - curTime;
        // =================================

        // ======= Initialize search =======
        SearchTree sTree = new SearchTree();
        coreReasonsSorted.clear();
        possibleCoEdges.clear();
        initSearch();

        List<Edge> coSearchList = new ArrayList<>();
        for (Set<Edge> coEdges : possibleCoEdges.values()) {
            coSearchList.addAll(coEdges);
        }
        sortCoSearchList(coSearchList);
        // =================================

        // ========= Actual search =========
        curTime = System.currentTimeMillis();
        for (int k = 0; k <= maxSaturationDepth; k++) {
            stats.saturationDepth = k;
            // There should always exist a single empty node unless we found a violation
            SearchNode start = sTree.findNodes(SearchNode::isEmptyNode).get(0);
            Result r = kSaturation(start, Timestamp.ZERO, k, coSearchList, 0);
            if (r != Result.UNKNOWN) {
                result.setResult(r);
                if (r == Result.FAIL) {
                    long temp = System.currentTimeMillis();
                    //result.setViolations(resolveViolations());
                    result.setViolations(computeResolventsFromTree(sTree));
                    stats.resolutionTime = System.currentTimeMillis() - temp;
                }
                break;
            }
            if (k > 0) {
                // For k=0, it is impossible to exclude coherences since no search is happening at all
                coSearchList.removeIf(this::coExists);
            }
            /*TODO: Maybe reduce k, whenever progress is made?
                if e.g. 2-SAT makes progress (finds some edge), then 1-SAT might be able to
                make use of that progress.
             */
        }
        // ==============================

        stats.searchTime = System.currentTimeMillis() - curTime;
        if (result.getResult() == Result.PASS) {
            testCoherence();
        }
        return result;
    }


    private DNF<CoreLiteral> computeResolventsFromTree(SearchTree tree) {
        SortedClauseSet<CoreLiteral> res = new TreeResolution(tree).computeViolations();
        SortedClauseSet<CoreLiteral> res2 = new SortedClauseSet<>();
        res.forEach(clause -> res2.add(clause.removeIf(x -> canBeRemoved(x, clause))));
        res2.simplify();
        return res2.toDNF();
    }



    /*
        <searchList> is a list of coherences that need to be tested. It is assumed
        that for each write-pair (w1,w2) there is exactly one edge in the list, either co(w1, w2) or
        co(w2, w1).
     */
    private Result kSaturation(SearchNode node, Timestamp curTime, int k, List<Edge> searchList, int searchStart) {
        searchList = searchList.subList(searchStart, searchList.size());
        if (k == 0) {
            // 0-SAT amounts to a simple violation check
            if (checkViolations()) {
                long time = System.currentTimeMillis();
                //computeViolations();
                node.replaceBy(new LeafNode(computeViolationList()));
                stats.violationComputationTime += (System.currentTimeMillis() - time);
                return Result.FAIL;
            } else if (searchList.stream().allMatch(this::coExists)) {
                // All remaining edges in the search list are already in the graph (due to transitivity and totality of co)
                return Result.PASS;
            } else {
                return Result.UNKNOWN;
            }
        }

        searchList = new ArrayList<>(searchList);
        boolean progress = true;
        while (progress) {
            progress = false;

            for (int i = 0; i < searchList.size(); i++) {
                Edge coEdge = searchList.get(i);
                if (coExists(coEdge))
                    continue;

                Timestamp nextTime = curTime.next();
                coEdge = coEdge.withTimestamp(nextTime);
                DecisionNode decNode = new DecisionNode(coEdge);

                execGraph.addCoherenceEdge(coEdge);
                stats.numGuessedCoherences++;
                Result r = kSaturation(decNode.getPositive(), nextTime, k - 1, searchList, i + 1);
                if (r == Result.PASS && searchList.stream().allMatch(this::coExists)) {
                    return Result.PASS;
                }
                backtrackOn(nextTime);

                if (r == Result.FAIL) {
                    node.replaceBy(decNode);
                    node = decNode.getNegative();
                    execGraph.addCoherenceEdge(coEdge.getInverse().withTimestamp(curTime));
                    r = kSaturation(decNode.getNegative(), curTime, k - 1, searchList, i + 1);
                    if (r == Result.FAIL) {
                        return r;
                    } else if (r == Result.PASS && searchList.stream().allMatch(this::coExists)) {
                        return r;
                    }
                    // We made progress
                    //TODO: We might want to restart the search or do some other heuristic
                    // to guide our search.
                    progress = true;
                } else {
                    nextTime = curTime.next();
                    Edge coEdgeInv = coEdge.getInverse().withTimestamp(nextTime);
                    execGraph.addCoherenceEdge(coEdgeInv);
                    stats.numGuessedCoherences++;
                    r = kSaturation(decNode.getNegative(), nextTime, k - 1, searchList, i + 1);
                    if (r == Result.PASS) {
                        return Result.PASS;
                    }
                    backtrackOn(nextTime);

                    if(r == Result.FAIL) {
                        node.replaceBy(decNode);
                        node = decNode.getPositive();
                        execGraph.addCoherenceEdge(coEdge.withTimestamp(curTime));
                        progress = true;
                    }
                }
                searchList.removeIf(this::coExists);
            }
        }
        return Result.UNKNOWN;
    }

    private boolean coExists(Edge coEdge) {
        return execGraph.getCoGraph().contains(coEdge) || execGraph.getCoGraph().contains(coEdge.getInverse());
    }

    private boolean checkViolations() {
        boolean hasViolation = false;
        for (GraphAxiom axiom : execGraph.getGraphAxioms()) {
            hasViolation |= axiom.checkForViolations();
        }

        return hasViolation;
    }

    private List<Conjunction<CoreLiteral>> computeViolationList() {
        List<Conjunction<CoreLiteral>> violations = new ArrayList<>();
        for (GraphAxiom axiom : execGraph.getGraphAxioms()) {
            // Computes a single reason
            /*Conjunction<CoreLiteral> clause = axiom.computeSomeReason();
            if (!clause.isFalse()) {
                coreReasonsSorted.add(clause.removeIf(x -> canBeRemoved(x, clause)));
            }*/

            // Computes many reasons
            DNF<CoreLiteral> clauses = axiom.computeReasons();
            if (!clauses.isFalse()) {
                for (Conjunction<CoreLiteral> clause : clauses.getCubes()) {
                    violations.add(clause.removeIf(x -> canBeRemoved(x, clause)));
                }
            }
        }
        stats.numComputedViolations += violations.size();
        return violations;
    }

    private void computeViolations() {
        //TODO: Compute more than just some reason. What we do depends on the implementation
        // very important!!!!!!!! Finding all violations causes HUGE resolvents in the end
        // finding just some reason can cause many, many iterations!!!!
        int curSize = coreReasonsSorted.getClauseSize();
        for (GraphAxiom axiom : execGraph.getGraphAxioms()) {
            // Computes a single reason
            /*Conjunction<CoreLiteral> clause = axiom.computeSomeReason();
            if (!clause.isFalse()) {
                coreReasonsSorted.add(clause.removeIf(x -> canBeRemoved(x, clause)));
            }*/

            // Computes many reasons
            DNF<CoreLiteral> clauses = axiom.computeReasons();
            if (!clauses.isFalse()) {
                for (Conjunction<CoreLiteral> clause : clauses.getCubes()) {
                    coreReasonsSorted.add(clause.removeIf(x -> canBeRemoved(x, clause)));
                }
            }
        }
        stats.numComputedViolations += coreReasonsSorted.getClauseSize() - curSize;
    }

    // Returns TRUE, if <literal> is an EventLiteral that is implied by some non-coherence
    // EdgeLiteral (cause CoLiterals may get removed by resolution)
    private boolean canBeRemoved(CoreLiteral literal, Conjunction<CoreLiteral> clause) {
        if (!(literal instanceof EventLiteral))
            return false;
        EventLiteral eventLit = (EventLiteral)literal;
        return clause.getLiterals().stream().anyMatch(x -> {
            if (!(x instanceof AbstractEdgeLiteral) || x.hasOpposite())
                return false;
            AbstractEdgeLiteral edgeLiteral = (AbstractEdgeLiteral)x;
            return edgeLiteral.getEdge().getFirst().equals(eventLit.getEvent())
                    || edgeLiteral.getEdge().getSecond().equals(eventLit.getEvent());
        });
    }

    private DNF<CoreLiteral> resolveViolations() {
        coreReasonsSorted.simplify();
        //SortedClauseSet<CoreLiteral> res =  coreReasonsSorted.computeAllResolvents();
        //TODO: replace this test code by some rigorous code
        SortedClauseSet<CoreLiteral> res =  coreReasonsSorted.computeProductiveResolvents();
        res.removeIf(Literal::hasOpposite);
        //TODO: replace following temporary code. We should already simplify during resolution
        SortedClauseSet<CoreLiteral> res2 = new SortedClauseSet<>();
        res.forEach(clause -> res2.add(clause.removeIf(x -> canBeRemoved(x, clause))));
        return new DNF<>(res2.getClauses());
    }

    /*
    A simple heuristic which moves all coherences to the front, which involve
    some write that was read from.
     */
    private void sortCoSearchList(List<Edge> list) {
        list.sort(Comparator.comparingInt(x -> (x.getFirst().getImportance() + x.getSecond().getImportance())));

        /*int index = 0;
        EventGraph rf = execGraph.getRfGraph();
        for (int i = 0; i < list.size(); i++) {
            Edge t = list.get(i);
            if (i > index && (rf.inEdgeIterator(t.getFirst()).hasNext() || rf.inEdgeIterator(t.getSecond()).hasNext()
            || rf.outEdgeIterator(t.getSecond()).hasNext() || rf.outEdgeIterator(t.getSecond()).hasNext()))
            {
                list.set(i, list.get(index));
                list.set(index++, t);
            }
        }*/
    }

    private void backtrackOn(Timestamp time) {
        time.invalidate();
        execGraph.backtrack();
    }


    private void initSearch() {
        for (Map.Entry<Long, Set<EventData>> addressedWrites : executionModel.getAddressWritesMap().entrySet()) {
            Set<EventData> writes = addressedWrites.getValue();
            Long address = addressedWrites.getKey();
            Set<Edge> coEdges = new HashSet<>();
            possibleCoEdges.put(address, coEdges);

            for (EventData e1 : writes) {
                for (EventData e2: writes) {
                    // We only add edges in one direction
                    if (e2.getId() >= e1.getId())
                        continue;

                    if (e1.isInit() && !e2.isInit()) {
                        execGraph.addCoherenceEdge(new Edge(e1, e2));
                    } else if (!e1.isInit() && !e2.isInit()) {
                        coEdges.add(new Edge(e1, e2));
                    }
                }
            }
        }
        possibleCoEdges.values().removeIf(Collection::isEmpty);
    }



    private final static boolean DEBUG = false;
    private void testIteration() {
        if (!DEBUG)
            return;
        for (EventGraph g : execGraph.getEventGraphs()) {
            int size = g.size();
            for (Edge e : g) {
                size--;
                if (size < 0) {
                    throw new RuntimeException();
                }
            }
            if (size > 0) {
                throw new RuntimeException();
            }
        }
    }

    private void testStaticGraphs() {
        if (!DEBUG)
            return;
        for (Relation relData : context.getRelations()) {
            if (relData.isStaticRelation()) {
                EventGraph g = execGraph.getEventGraph(relData);
                for (Tuple t : relData.getMaxTupleSet()) {
                    if (executionModel.eventExists(t.getFirst()) && executionModel.eventExists(t.getSecond())) {
                        if (!g.contains(executionModel.getEdge(t))) {
                            throw new RuntimeException();
                        }
                    }
                }
            }
        }
    }

    private void testCoherence() {
        if (!DEBUG)
            return;
        TupleSet tSet = new TupleSet();
        for (Edge e : execGraph.getWoGraph()) {
            tSet.add(new Tuple(e.getFirst().getEvent(), e.getSecond().getEvent()));
        }
        Map<Event, Set<Event>> map = tSet.transMap();
        for (Event e1 : map.keySet()) {
            for (Event e2 : map.get(e1)) {
                Edge edge = executionModel.getEdge(new Tuple(e1,e2));
                if (!execGraph.getCoGraph().contains(edge)) {
                    throw new RuntimeException();
                }
            }
        }

        for (Set<EventData> writes : executionModel.getAddressWritesMap().values()) {
            for (EventData e1 : writes) {
                for (EventData e2 : writes) {
                    if (e1 == e2) {
                        continue;
                    }

                    if (!coExists(new Edge(e1,e2))) {
                        throw new RuntimeException();
                    }
                }
            }
        }
    }

}