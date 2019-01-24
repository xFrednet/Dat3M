package dartagnan.program.event.linux.rmw;

import com.google.common.collect.ImmutableSet;
import dartagnan.expression.ExprInterface;
import dartagnan.expression.IExpr;
import dartagnan.program.Register;
import dartagnan.program.Seq;
import dartagnan.program.Thread;
import dartagnan.program.event.Fence;
import dartagnan.program.event.Local;
import dartagnan.program.event.MemEvent;
import dartagnan.program.event.rmw.cond.FenceCond;
import dartagnan.program.event.rmw.cond.RMWReadCond;
import dartagnan.program.event.utils.RegReaderData;
import dartagnan.program.event.utils.RegWriter;
import dartagnan.program.utils.linux.EType;

public abstract class RMWAbstract extends MemEvent implements RegWriter, RegReaderData {

    protected Register resultRegister;
    protected ExprInterface value;

    ImmutableSet<Register> dataRegs;

    RMWAbstract(IExpr address, Register register, ExprInterface value, String atomic) {
        this.address = address;
        this.resultRegister = register;
        this.value = value;
        this.atomic = atomic;
        this.condLevel = 0;
        this.dataRegs = value.getRegs();
        addFilters(EType.ANY, EType.MEMORY, EType.READ, EType.WRITE, EType.RMW);
    }

    @Override
    public Register getResultRegister() {
        return resultRegister;
    }

    @Override
    public ImmutableSet<Register> getDataRegs(){
        return dataRegs;
    }

    @Override
    public Thread compile(String target, boolean ctrl, boolean leading) {
        throw new RuntimeException("Compilation to " + target + " is not supported for " + this.getClass().getName() + " " + atomic);
    }

    String getLoadMO(){
        return atomic.equals("Acquire") ? "Acquire" : "Relaxed";
    }

    String getStoreMO(){
        return atomic.equals("Release") ? "Release" : "Relaxed";
    }

    Thread insertFencesOnMb(Thread result){
        if (atomic.equals("Mb")) {
            return new Seq(new Fence("Mb"), new Seq(result, new Fence("Mb")));
        }
        return result;
    }

    Thread insertCondFencesOnMb(Thread result, RMWReadCond load){
        if (atomic.equals("Mb")) {
            return new Seq(new FenceCond(load, "Mb"), new Seq(result, new FenceCond(load, "Mb")));
        }
        return result;
    }

    Thread copyFromDummyToResult(Thread result, Register dummy){
        if (dummy != resultRegister) {
            return new Seq(result, new Local(resultRegister, dummy));
        }
        return result;
    }

    void compileBasic(MemEvent event){
        event.setHLId(hlId);
        event.setCondLevel(condLevel);
        event.setMaxAddressSet(getMaxAddressSet());
    }

    String atomicToText(String atomic){
        switch (atomic){
            case "Relaxed":
                return "_relaxed";
            case "Acquire":
                return "_acquire";
            case "Release":
                return "_release";
            case "Mb":
                return "";
            default:
                throw new RuntimeException("Unrecognised memory order " + atomic + " in " + this.getClass().getName());
        }
    }
}