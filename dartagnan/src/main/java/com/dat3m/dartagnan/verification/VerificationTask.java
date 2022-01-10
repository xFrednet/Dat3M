package com.dat3m.dartagnan.verification;

import com.dat3m.dartagnan.configuration.Arch;
import com.dat3m.dartagnan.encoding.ProgramEncoder;
import com.dat3m.dartagnan.encoding.PropertyEncoder;
import com.dat3m.dartagnan.encoding.SymmetryEncoder;
import com.dat3m.dartagnan.encoding.WmmEncoder;
import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.Thread;
import com.dat3m.dartagnan.program.analysis.AliasAnalysis;
import com.dat3m.dartagnan.program.analysis.BranchEquivalence;
import com.dat3m.dartagnan.program.analysis.ThreadSymmetry;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.program.processing.ProcessingManager;
import com.dat3m.dartagnan.utils.dependable.DependencyGraph;
import com.dat3m.dartagnan.witness.WitnessGraph;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.analysis.WmmAnalysis;
import com.dat3m.dartagnan.wmm.axiom.Axiom;
import com.dat3m.dartagnan.wmm.relation.Relation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.ConfigurationBuilder;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.java_smt.api.SolverContext;

import java.util.List;
import java.util.Set;

import static com.dat3m.dartagnan.configuration.OptionNames.*;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/*
Represents a verification task.
 */
//TODO: The encoders should go from this class
@Options
public class VerificationTask {

    private static final Logger logger = LogManager.getLogger(VerificationTask.class);

    // Data objects
    private final Program program;
    private final Wmm memoryModel;
    private final WitnessGraph witness;
    private final Configuration config;
    private final Context analysisContext;


    // Encoders
    protected ProgramEncoder progEncoder;
    protected PropertyEncoder propertyEncoder;
    protected WmmEncoder wmmEncoder;
    protected SymmetryEncoder symmetryEncoder;

    protected VerificationTask(Program program, Wmm memoryModel, WitnessGraph witness, Configuration config)
    throws InvalidConfigurationException {
        this.program = checkNotNull(program);
        this.memoryModel = checkNotNull(memoryModel);
        this.witness = checkNotNull(witness);
        this.config = checkNotNull(config);
        this.analysisContext = Context.create();

        config.recursiveInject(this);
    }

    public static VerificationTaskBuilder builder() {
        return new VerificationTaskBuilder();
    }

    public Program getProgram() {
    	return program;
    }
    public Wmm getMemoryModel() {
    	return memoryModel;
    }
    public Configuration getConfig() {
        return this.config;
    }
    public WitnessGraph getWitness() {
    	return witness;
    }
    public Context getAnalysisContext() { return analysisContext; }

    public Set<Relation> getRelations() {
    	return memoryModel.getRelationRepository().getRelations();
    }
    public List<Axiom> getAxioms() {
    	return memoryModel.getAxioms();
    }
    public DependencyGraph<Relation> getRelationDependencyGraph() {
        return memoryModel.getRelationDependencyGraph();
    }

    public ProgramEncoder getProgramEncoder() { return progEncoder; }
    public PropertyEncoder getPropertyEncoder() { return propertyEncoder; }
    public WmmEncoder getWmmEncoder() { return wmmEncoder; }
    public SymmetryEncoder getSymmetryEncoder() { return symmetryEncoder; }


    // ===================== Utility Methods ====================

    public void preprocessProgram() throws InvalidConfigurationException {
        ProcessingManager.fromConfig(config).run(program);
    }

    public void performStaticProgramAnalyses() throws InvalidConfigurationException {
        analysisContext.register(BranchEquivalence.class, BranchEquivalence.fromConfig(program, config));
        analysisContext.register(AliasAnalysis.class, AliasAnalysis.fromConfig(program, config));
        analysisContext.register(ThreadSymmetry.class, ThreadSymmetry.fromConfig(program, config));

        for (Thread thread : program.getThreads()) {
            for (Event e : thread.getEvents()) {
                // Some events perform static analyses by themselves (e.g. Svcomp's EndAtomic)
                // which may rely on previous "global" analyses
                // TODO: We misuse the <task> object as analysis information object for now.
                e.runLocalAnalysis(this);
            }
        }
    }

    public void performStaticWmmAnalyses() throws InvalidConfigurationException {
        analysisContext.register(WmmAnalysis.class, WmmAnalysis.fromConfig(memoryModel, config));
        analysisContext.register(RelationAnalysis.class, RelationAnalysis.fromConfig(program, memoryModel, this, config));
    }

    public void initializeEncoders(SolverContext ctx) throws InvalidConfigurationException {
        progEncoder = ProgramEncoder.fromConfig(program, this, config);
        propertyEncoder = PropertyEncoder.fromConfig(program, memoryModel,this, config);
        wmmEncoder = WmmEncoder.fromConfig(memoryModel, this, config);
        symmetryEncoder = SymmetryEncoder.fromConfig(this, config);

        progEncoder.initializeEncoding(ctx);
        propertyEncoder.initializeEncoding(ctx);
        wmmEncoder.initializeEncoding(ctx);
        symmetryEncoder.initializeEncoding(ctx);
    }


    // ==================== Builder =====================

    public static class VerificationTaskBuilder {
        protected WitnessGraph witness = new WitnessGraph();
        protected ConfigurationBuilder config = Configuration.builder();

        protected VerificationTaskBuilder() { }

        public VerificationTaskBuilder withWitness(WitnessGraph witness) {
            this.witness = checkNotNull(witness, "Witness may not be null.");
            return this;
        }

        public VerificationTaskBuilder withTarget(Arch target) {
            checkNotNull(target, "Target may not be null.");
            this.config.setOption(TARGET, target.toString());
            return this;
        }

        public VerificationTaskBuilder withBound(int k) {
            checkArgument(k > 0 , "Unrolling bound must be positive.");
            this.config.setOption(BOUND, Integer.toString(k));
            return this;
        }

        public VerificationTaskBuilder withSolverTimeout(int t) {
            this.config.setOption(TIMEOUT, Integer.toString(t));
            return this;
        }

        public VerificationTaskBuilder withConfig(Configuration config) {
            this.config.copyFrom(config);
            return this;
        }

        public VerificationTask build(Program program, Wmm memoryModel) throws InvalidConfigurationException {
            return new VerificationTask(program, memoryModel, witness, config.build());
        }
    }
}