package com.dat3m.dartagnan.program.processing.compilation;

import com.dat3m.dartagnan.expression.*;
import com.dat3m.dartagnan.expression.op.IOpBin;
import com.dat3m.dartagnan.program.Register;
import com.dat3m.dartagnan.program.event.Tag;
import com.dat3m.dartagnan.program.event.Tag.C11;
import com.dat3m.dartagnan.program.event.core.*;
import com.dat3m.dartagnan.program.event.lang.catomic.*;
import com.dat3m.dartagnan.program.event.lang.pthread.Create;
import com.dat3m.dartagnan.program.event.lang.pthread.End;
import com.dat3m.dartagnan.program.event.lang.pthread.Join;
import com.dat3m.dartagnan.program.event.lang.pthread.Start;
import com.dat3m.dartagnan.program.event.visitors.EventVisitor;

import java.util.Collections;
import java.util.List;

import static com.dat3m.dartagnan.expression.op.COpBin.EQ;
import static com.dat3m.dartagnan.expression.op.COpBin.NEQ;
import static com.dat3m.dartagnan.program.event.EventFactory.*;
import static com.dat3m.dartagnan.program.event.Tag.C11.extractLoadMo;
import static com.dat3m.dartagnan.program.event.Tag.C11.extractStoreMo;

class VisitorIMM extends VisitorBase implements EventVisitor<List<Event>> {

	protected VisitorIMM() {}

	@Override
	public List<Event> visitCreate(Create e) {
        Store store = newStore(e.getAddress(), e.getMemValue(), e.getMo(), e.getCLine());
        store.addFilters(C11.PTHREAD);
        
        return eventSequence(
        		newFence(Tag.C11.MO_SC),
                store
        );
	}

	@Override
	public List<Event> visitEnd(End e) {
        return eventSequence(
        		newFence(Tag.C11.MO_SC),
        		newStore(e.getAddress(), IValue.ZERO, extractStoreMo(e.getMo()))
        );
	}

	@Override
	public List<Event> visitJoin(Join e) {
        Register resultRegister = e.getResultRegister();
		Load load = newLoad(resultRegister, e.getAddress(), extractLoadMo(e.getMo()));
        load.addFilters(C11.PTHREAD);
        
        return eventSequence(
        		load,
        		newJumpUnless(new Atom(resultRegister, EQ, IValue.ZERO), e.getLabel()),
        		newFence(Tag.C11.MO_SC)
        );
	}

	@Override
	public List<Event> visitStart(Start e) {
        Register resultRegister = e.getResultRegister();

        return eventSequence(
        		newLoad(resultRegister, e.getAddress(), extractLoadMo(e.getMo())),
        		newJumpUnless(new Atom(resultRegister, EQ, IValue.ONE), e.getLabel()),
        		newFence(Tag.C11.MO_SC)
        );
	}

	@Override
	public List<Event> visitAtomicCmpXchg(AtomicCmpXchg e) {
		Register resultRegister = e.getResultRegister();
		IExpr address = e.getAddress();
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;
		IExpr expectedAddr = e.getExpectedAddr();
		int precision = resultRegister.getPrecision();

		Register regExpected = e.getThread().newRegister(precision);
        Register regValue = e.getThread().newRegister(precision);
        Load loadExpected = newLoad(regExpected, expectedAddr, null);
        loadExpected.addFilters(Tag.IMM.CASDEPORIGIN);
        Store storeExpected = newStore(expectedAddr, regValue, null);
        Label casFail = newLabel("CAS_fail");
        Label casEnd = newLabel("CAS_end");
        Local casCmpResult = newLocal(resultRegister, new Atom(regValue, EQ, regExpected));
        CondJump branchOnCasCmpResult = newJump(new Atom(resultRegister, NEQ, IValue.ONE), casFail);
        CondJump gotoCasEnd = newGoto(casEnd);
        Load loadValue = newRMWLoad(regValue, address, extractLoadMo(mo));
        Store storeValue = newRMWStore(loadValue, address, e.getMemValue(), extractStoreMo(mo));

        return eventSequence(
                // Indentation shows the branching structure
                loadExpected,
                optionalFence,
                loadValue,
                casCmpResult,
                branchOnCasCmpResult,
                	optionalFence,
                    storeValue,
                    gotoCasEnd,
                casFail,
                    storeExpected,
                casEnd
        );
	}

	@Override
	public List<Event> visitAtomicFetchOp(AtomicFetchOp e) {
		Register resultRegister = e.getResultRegister();
		IOpBin op = e.getOp();
		IExpr address = e.getAddress();
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;
		
        Register dummyReg = e.getThread().newRegister(resultRegister.getPrecision());
        Load load = newRMWLoad(resultRegister, address, extractLoadMo(mo));

        return eventSequence(
        		optionalFence,
                load,
                newLocal(dummyReg, new IExprBin(resultRegister, op, (IExpr) e.getMemValue())),
                newRMWStore(load, address, dummyReg, extractStoreMo(mo))
        );
	}

	@Override
	public List<Event> visitAtomicLoad(AtomicLoad e) {
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;
        return eventSequence(
        		optionalFence,
        		newLoad(e.getResultRegister(), e.getAddress(), extractLoadMo(mo))
        );
	}

	@Override
	public List<Event> visitAtomicStore(AtomicStore e) {
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;
        return eventSequence(
        		optionalFence,
        		newStore(e.getAddress(), e.getMemValue(), extractStoreMo(mo))
        );
	}

	@Override
	public List<Event> visitAtomicThreadFence(AtomicThreadFence e) {
		return Collections.singletonList(newFence(e.getMo()));
	}

	@Override
	public List<Event> visitAtomicXchg(AtomicXchg e) {
		IExpr address = e.getAddress();
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;

        Load load = newRMWLoad(e.getResultRegister(), address, extractLoadMo(mo));
        
        return eventSequence(
        		optionalFence,
                load,
                optionalFence,
                newRMWStore(load, address, e.getMemValue(), extractStoreMo(mo))
        );
	}

	@Override
	public List<Event> visitDat3mCAS(Dat3mCAS e) {
		Register resultRegister = e.getResultRegister();
		ExprInterface value = e.getMemValue();
		IExpr address = e.getAddress();
		String mo = e.getMo();
		Fence optionalFence = mo.equals(Tag.C11.MO_SC) ? newFence(Tag.C11.MO_SC) : null;
		ExprInterface expectedValue = e.getExpectedValue();

        Register regValue = e.getThread().newRegister(resultRegister.getPrecision());
        Local casCmpResult = newLocal(resultRegister, new Atom(regValue, EQ, expectedValue));
        Label casEnd = newLabel("CAS_end");
        CondJump branchOnCasCmpResult = newJump(new Atom(resultRegister, NEQ, IValue.ONE), casEnd);

        Load load = newRMWLoad(regValue, address, extractLoadMo(mo));
        Store store = newRMWStore(load, address, value, extractStoreMo(mo));

        return eventSequence(
                // Indentation shows the branching structure
        		optionalFence,
                load,
                casCmpResult,
                branchOnCasCmpResult,
                	optionalFence,
                    store,
                casEnd
        );
	}
}