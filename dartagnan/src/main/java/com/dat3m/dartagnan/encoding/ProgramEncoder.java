package com.dat3m.dartagnan.encoding;

import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.Register;
import com.dat3m.dartagnan.program.Thread;
import com.dat3m.dartagnan.program.analysis.BranchEquivalence;
import com.dat3m.dartagnan.program.analysis.Dependency;
import com.dat3m.dartagnan.program.analysis.ExecutionAnalysis;
import com.dat3m.dartagnan.program.event.core.CondJump;
import com.dat3m.dartagnan.program.event.core.Event;
import com.dat3m.dartagnan.program.event.core.Label;
import com.dat3m.dartagnan.program.event.core.utils.RegWriter;
import com.dat3m.dartagnan.program.memory.Memory;
import com.google.common.base.Preconditions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula;

import java.util.*;
import java.util.function.BiFunction;

import static com.dat3m.dartagnan.GlobalSettings.ARCH_PRECISION;
import static com.dat3m.dartagnan.configuration.OptionNames.*;
import static com.google.common.collect.Lists.reverse;

@Options
public class ProgramEncoder implements Encoder {

    private static final Logger logger = LogManager.getLogger(ProgramEncoder.class);

    // =========================== Configurables ===========================

    @Option(name = ALLOW_PARTIAL_EXECUTIONS,
            description = "Allows to terminate executions on the first found violation. " +
                    "This is not allowed on Litmus tests due to their different assertion condition.",
            secure = true)
    private boolean shouldAllowPartialExecutions = false;

    @Option(name = MERGE_CF_VARS,
            description = "Merges control flow variables of events with identical control-flow behaviour.",
            secure = true)
    private boolean shouldMergeCFVars = true;

    @Option(name = INITIALIZE_REGISTERS,
            description = "Assume thread-local variables start off containing zero.",
            secure = true)
    private boolean initializeRegisters = false;

    // =====================================================================

    private final EncodingContext context;
    private final BranchEquivalence eq;
    private final ExecutionAnalysis exec;
    private final Dependency dep;
    private boolean isInitialized = false;

    private ProgramEncoder(EncodingContext c) {
        Preconditions.checkArgument(c.task().getProgram().isCompiled(), "The program must be compiled before encoding.");
        context = c;
        this.eq = c.analysisContext().requires(BranchEquivalence.class);
        this.exec = c.analysisContext().requires(ExecutionAnalysis.class);
        this.dep = c.analysisContext().requires(Dependency.class);
    }

    public static ProgramEncoder of(EncodingContext context) throws InvalidConfigurationException {
        ProgramEncoder encoder = new ProgramEncoder(context);
        context.task().getConfig().inject(encoder);
        logger.info("{}: {}", ALLOW_PARTIAL_EXECUTIONS, encoder.shouldAllowPartialExecutions);
        logger.info("{}: {}", MERGE_CF_VARS, encoder.shouldMergeCFVars);
        logger.info("{}: {}", INITIALIZE_REGISTERS, encoder.initializeRegisters);
        return encoder;
    }

    // ============================== Initialization ==============================

    public void initializeEncoding(SolverContext ctx) {
        for(Event e : context.task().getProgram().getEvents()){
            initEvent(e, ctx);
        }
        isInitialized = true;
    }

    private void initEvent(Event e, SolverContext ctx){
        BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();

        boolean mergeVars = shouldMergeCFVars && !shouldAllowPartialExecutions;
        String repr = mergeVars ? eq.getRepresentative(e).repr() : e.repr();
        e.setCfVar(bmgr.makeVariable("cf(" + repr + ")"));
        e.initializeEncoding(ctx);
    }

    private void checkInitialized() {
        Preconditions.checkState(isInitialized, "initializeEncoding must get called before encoding.");
    }

    // ============================== Encoding ==============================

    public BooleanFormula encodeFullProgram() {
        return context.getFormulaManager().getBooleanFormulaManager().and(
                encodeMemory(),
                encodeControlFlow(),
                encodeFinalRegisterValues(),
                encodeFilter(),
                encodeDependencies());
    }

    public BooleanFormula encodeControlFlow() {
        checkInitialized();
        logger.info("Encoding program control flow");

        BooleanFormulaManager bmgr = context.getFormulaManager().getBooleanFormulaManager();
        
        BooleanFormula enc = bmgr.makeTrue();
        for(Thread t : context.task().getProgram().getThreads()){
            enc = bmgr.and(enc, encodeThreadCF(t));
        }
        return enc;
    }

    private BooleanFormula encodeThreadCF(Thread thread) {
        checkInitialized();
        SolverContext ctx = context.solverContext();
        BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        BooleanFormula enc = bmgr.makeTrue();
        BiFunction<BooleanFormula, BooleanFormula, BooleanFormula> cfEncoder = shouldAllowPartialExecutions ?
                bmgr::implication : bmgr::equivalence;
        Map<Label, Set<Event>> labelJumpMap = new HashMap<>();

        Event pred = null;
        for(Event e : thread.getEntry().getSuccessors()) {

            // Immediate control flow
            BooleanFormula cfCond = pred == null ? bmgr.makeTrue() : context.controlFlow(pred);
            if (pred instanceof CondJump) {
                CondJump jump = (CondJump) pred;
                cfCond = bmgr.and(cfCond, bmgr.not(jump.getGuard().toBoolFormula(jump, ctx)));
                // NOTE: we need to register the actual jumps here, because the
                // listener sets of labels is too large (it contains old copies)
                labelJumpMap.computeIfAbsent(jump.getLabel(), key -> new HashSet<>()).add(jump);
            }

            // Control flow via jumps
            if (e instanceof Label) {
                for (Event jump : labelJumpMap.getOrDefault(e, Collections.emptySet())) {
                    CondJump j = (CondJump)jump;
                    cfCond = bmgr.or(cfCond, bmgr.and(context.controlFlow(j), context.jumpCondition(j)));
                }
            }

            enc = bmgr.and(enc, cfEncoder.apply(context.controlFlow(e), cfCond), e.encodeExec(context));
            pred = e;
        }
        return enc;
    }

    // Assigns each Address a fixed memory address.
    public BooleanFormula encodeMemory() {
        checkInitialized();
        SolverContext ctx = context.solverContext();
        logger.info("Encoding fixed memory");

        Memory memory = context.task().getProgram().getMemory();
        FormulaManager fmgr = ctx.getFormulaManager();
        
        BooleanFormula[] addrExprs;

        if(ARCH_PRECISION > -1) {
        	BitvectorFormulaManager bvmgr = fmgr.getBitvectorFormulaManager();	
            addrExprs = memory.getObjects().stream()
                    .map(addr -> bvmgr.equal((BitvectorFormula)addr.toIntFormula(ctx), 
                    		bvmgr.makeBitvector(ARCH_PRECISION, addr.getValue().intValue())))
                    .toArray(BooleanFormula[]::new);        	
        } else {
            IntegerFormulaManager imgr = fmgr.getIntegerFormulaManager();
            addrExprs = memory.getObjects().stream()
                    .map(addr -> imgr.equal((IntegerFormula)addr.toIntFormula(ctx),
                    		imgr.makeNumber(addr.getValue().intValue())))
                    .toArray(BooleanFormula[]::new);        	
        }
        return fmgr.getBooleanFormulaManager().and(addrExprs);
    }

    /**
     * @return
     * Describes that for each pair of events, if the reader uses the result of the writer,
     * then the value the reader gets from the register is exactly the value that the writer computed.
     * Also, the reader may only use the value of the latest writer that is executed.
     * Also, if no fitting writer is executed, the reader uses 0.
     */
    public BooleanFormula encodeDependencies() {
        logger.info("Encoding dependencies");
        SolverContext ctx = context.solverContext();
        BooleanFormulaManager bmgr = ctx.getFormulaManager().getBooleanFormulaManager();
        BooleanFormula enc = bmgr.makeTrue();
        for(Map.Entry<Event,Map<Register,Dependency.State>> e : dep.getAll()) {
            Event reader = e.getKey();
            for(Map.Entry<Register,Dependency.State> r : e.getValue().entrySet()) {
                Formula value = r.getKey().toIntFormula(reader, ctx);
                Dependency.State state = r.getValue();
                BooleanFormula overwrite = bmgr.makeFalse();
                for(Event writer : reverse(state.may)) {
                    assert writer instanceof RegWriter;
                    BooleanFormula edge;
                    if(state.must.contains(writer)) {
                        if (exec.isImplied(reader, writer) && reader.cfImpliesExec()) {
                            // This special case is important. Usually, we encode "dep => regValue = regWriterResult"
                            // By getting rid of the guard "dep" in this special case, we end up with an unconditional
                            // "regValue = regWriterResult", which allows the solver to eliminate one of the variables
                            // in preprocessing.
                            assert state.may.size() == 1;
                            edge = bmgr.makeTrue();
                        } else {
                            edge = bmgr.and(context.execution(writer), context.controlFlow(reader));
                        }
                    } else {
                        edge = context.dependency(writer, reader);
                        enc = bmgr.and(enc, bmgr.equivalence(edge, bmgr.and(context.execution(writer), context.controlFlow(reader), bmgr.not(overwrite))));
                    }
                    enc = bmgr.and(enc, bmgr.implication(edge, context.equal(value, context.result((RegWriter) writer))));
                    overwrite = bmgr.or(overwrite, context.execution(writer));
                }
                if(initializeRegisters && !state.initialized) {
                    enc = bmgr.and(enc, bmgr.or(overwrite, bmgr.not(context.controlFlow(reader)), context.equalZero(value)));
                }
            }
        }
        return enc;
    }

    public BooleanFormula encodeFilter() {
    	return context.task().getProgram().getAssFilter() != null ?
                context.task().getProgram().getAssFilter().encode(context) :
    			context.getFormulaManager().getBooleanFormulaManager().makeTrue();
    }
    
    public BooleanFormula encodeFinalRegisterValues() {
        checkInitialized();
        SolverContext ctx = context.solverContext();
        logger.info("Encoding final register values");

        FormulaManager fmgr = ctx.getFormulaManager();
        BooleanFormulaManager bmgr = fmgr.getBooleanFormulaManager();

        if (context.task().getProgram().getFormat() == Program.SourceLanguage.BOOGIE) {
            // Boogie does not have assertions over final register values, so we do not need to encode them.
            return bmgr.makeTrue();
        }

        BooleanFormula enc = bmgr.makeTrue();
        for(Map.Entry<Register,Dependency.State> e : dep.finalWriters().entrySet()) {
            Formula value = e.getKey().getLastValueExpr(ctx);
            Dependency.State state = e.getValue();
            List<Event> writers = state.may;
            if(initializeRegisters && !state.initialized) {
                BooleanFormula clause = context.equalZero(value);
                for(Event w : writers) {
                    clause = bmgr.or(clause, context.execution(w));
                }
                enc = bmgr.and(enc, clause);
            }
            for(int i = 0; i < writers.size(); i++) {
                Event writer = writers.get(i);
                BooleanFormula clause = bmgr.or(
                        context.equal(value, context.result((RegWriter) writer)),
                        bmgr.not(context.execution(writer)));
                for(Event w : writers.subList(i + 1, writers.size())) {
                    if(!exec.areMutuallyExclusive(writer, w)) {
                        clause = bmgr.or(clause, context.execution(w));
                    }
                }
                enc = bmgr.and(enc, clause);
            }
        }
        return enc;
    }
}
