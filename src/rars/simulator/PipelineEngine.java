package rars.simulator;

import rars.Globals;
import rars.ProgramStatement;
import rars.riscv.BasicInstruction;
import rars.riscv.hardware.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 5-stage pipeline engine with hazard detection, data forwarding, and
 * branch handling (always-not-taken prediction).
 * 
 * Stages: IF → ID → EX → MEM → WB
 * 
 * Features:
 *  - RAW hazard detection with forwarding (EX→EX, MEM→EX)
 *  - Load-use hazard detection with stall insertion
 *  - Branch resolution in EX stage with flush on misprediction
 *  - Per-cycle snapshot for visualization
 */
public class PipelineEngine {

    // Pipeline latches between stages
    private PipelineLatch ifId;   // between IF and ID
    private PipelineLatch idEx;   // between ID and EX
    private PipelineLatch exMem;  // between EX and MEM
    private PipelineLatch memWb;  // between MEM and WB

    private int cycleCount;
    private boolean done;
    private boolean stalling;  // pipeline is stalled this cycle

    // Forwarding paths active this cycle
    private List<ForwardingPath> activeForwards;

    // History of pipeline snapshots for the pipeline table
    private List<PipelineSnapshot> history;

    /**
     * A record of a forwarding path for visualization.
     */
    public static class ForwardingPath {
        public final String fromStage;  // "EX/MEM" or "MEM/WB"
        public final String toStage;    // "ID/EX"
        public final int register;      // which register is being forwarded
        public final long value;        // the forwarded value
        public final String description;

        public ForwardingPath(String from, String to, int reg, long val, String desc) {
            this.fromStage = from;
            this.toStage = to;
            this.register = reg;
            this.value = val;
            this.description = desc;
        }

        @Override
        public String toString() { return description; }
    }

    /**
     * Snapshot of the entire pipeline at one cycle.
     */
    public static class PipelineSnapshot {
        public final int cycleNumber;
        public final CycleState ifState;
        public final CycleState idState;
        public final CycleState exState;
        public final CycleState memState;
        public final CycleState wbState;
        public final List<ForwardingPath> forwards;
        public final boolean stallInserted;
        public final boolean flushOccurred;
        public final String hazardDescription;

        public PipelineSnapshot(int cycle, CycleState ifS, CycleState idS, CycleState exS,
                                CycleState memS, CycleState wbS, List<ForwardingPath> fwds,
                                boolean stall, boolean flush, String hazard) {
            this.cycleNumber = cycle;
            this.ifState = ifS;
            this.idState = idS;
            this.exState = exS;
            this.memState = memS;
            this.wbState = wbS;
            this.forwards = fwds;
            this.stallInserted = stall;
            this.flushOccurred = flush;
            this.hazardDescription = hazard;
        }
    }

    public PipelineEngine() {
        this.cycleCount = 0;
        this.done = false;
        this.stalling = false;
        this.activeForwards = new ArrayList<>();
        this.history = new ArrayList<>();

        // Initialize all latches as bubbles
        ifId = PipelineLatch.bubble();
        idEx = PipelineLatch.bubble();
        exMem = PipelineLatch.bubble();
        memWb = PipelineLatch.bubble();
    }

    /**
     * Advance the pipeline by one clock cycle.
     * Returns a snapshot of all 5 stages.
     */
    public PipelineSnapshot advanceOneCycle() {
        if (done && !hasInFlightInstructions()) return null;

        cycleCount++;
        activeForwards = new ArrayList<>();
        boolean stallThisCycle = false;
        boolean flushThisCycle = false;
        StringBuilder hazardDesc = new StringBuilder();

        // ===== WB Stage: Write Back =====
        if (memWb.isValid() && !memWb.isBubble()) {
            doWriteBack(memWb);
        }

        // ===== MEM Stage: Memory Access =====
        PipelineLatch newMemWb = exMem;
        if (exMem.isValid() && !exMem.isBubble()) {
            doMemoryAccess(exMem);
        }

        // ===== EX Stage: Execute + Branch Resolution =====
        PipelineLatch newExMem = idEx;
        boolean branchMispredict = false;
        if (idEx.isValid() && !idEx.isBubble()) {
            // Apply forwarding BEFORE execution
            applyForwarding(idEx, exMem, memWb, hazardDesc);

            doExecute(idEx);

            // Branch resolution: check if branch was taken
            if (idEx.isBranch() || idEx.isJump()) {
                int resolvedPc = RegisterFile.getProgramCounter();
                if (resolvedPc != idEx.getPc() + 4) {
                    // Branch taken — misprediction (we predicted not-taken)
                    branchMispredict = true;
                    idEx.setBranchTaken(true);
                    idEx.setBranchTarget(resolvedPc);
                    hazardDesc.append("Branch misprediction: flushing IF and ID. ");
                }
            }
        }

        // ===== Hazard Detection: Load-Use =====
        boolean loadUseHazard = false;
        if (idEx.isLoad() && idEx.isValid()) {
            // Check if the next instruction (in ifId) reads the load destination
            if (ifId.isValid() && !ifId.isBubble()) {
                if ((ifId.getRs1() != 0 && ifId.getRs1() == idEx.getRd())
                        || (ifId.getRs2() != 0 && ifId.getRs2() == idEx.getRd())) {
                    loadUseHazard = true;
                    stallThisCycle = true;
                    hazardDesc.append(String.format("Load-use hazard: x%d from %s. Stall inserted. ",
                            idEx.getRd(), idEx.getInstructionText()));
                }
            }
        }

        // ===== ID Stage: Decode =====
        PipelineLatch newIdEx;
        if (loadUseHazard) {
            // Insert bubble into ID/EX, keep IF/ID frozen (stall)
            newIdEx = PipelineLatch.bubble();
            newIdEx.setControlSignal("RegWrite", 0);
            newIdEx.setControlSignal("MemRead", 0);
            newIdEx.setControlSignal("MemWrite", 0);
        } else if (branchMispredict) {
            // Flush the instruction in ID
            newIdEx = PipelineLatch.bubble();
            flushThisCycle = true;
        } else if (ifId.isValid() && !ifId.isBubble()) {
            newIdEx = ifId;
            doDecode(newIdEx);
        } else {
            newIdEx = ifId; // pass the bubble through
        }

        // ===== IF Stage: Fetch =====
        PipelineLatch newIfId;
        if (loadUseHazard) {
            // Stall: keep the same instruction in IF/ID
            newIfId = ifId;
        } else if (branchMispredict) {
            // Flush: fetch from the branch target
            newIfId = fetchInstruction();
            flushThisCycle = true;
        } else {
            newIfId = fetchInstruction();
        }

        // ===== Create snapshot BEFORE updating latches =====
        CycleState ifState = newIfId.toCycleState(cycleCount, CycleState.Stage.FETCH);
        CycleState idState = (loadUseHazard ? ifId : newIdEx).toCycleState(cycleCount, CycleState.Stage.DECODE);
        CycleState exState = newExMem.toCycleState(cycleCount, CycleState.Stage.EXECUTE);
        CycleState memState = newMemWb.toCycleState(cycleCount, CycleState.Stage.MEMORY);
        CycleState wbState = memWb.toCycleState(cycleCount, CycleState.Stage.WRITEBACK);

        if (stallThisCycle) {
            idState.setStall(true);
            idState.setCurrentStage(CycleState.Stage.STALL);
        }

        // Apply forwarding info to states
        for (ForwardingPath fp : activeForwards) {
            exState.setHazardDescription(exState.getHazardDescription() + fp.description + " ");
        }

        PipelineSnapshot snapshot = new PipelineSnapshot(
                cycleCount, ifState, idState, exState, memState, wbState,
                new ArrayList<>(activeForwards), stallThisCycle, flushThisCycle,
                hazardDesc.toString());

        // ===== Update pipeline latches =====
        memWb = newMemWb;
        exMem = newExMem;
        idEx = newIdEx;
        ifId = newIfId;

        history.add(snapshot);
        return snapshot;
    }

    /**
     * Fetch the next instruction from memory.
     */
    private PipelineLatch fetchInstruction() {
        if (done) return PipelineLatch.bubble();

        int pc = RegisterFile.getProgramCounter();
        ProgramStatement stmt;
        try {
            stmt = Globals.memory.getStatement(pc);
        } catch (AddressErrorException e) {
            done = true;
            return PipelineLatch.bubble();
        }

        if (stmt == null) {
            done = true;
            return PipelineLatch.bubble();
        }

        // Pre-increment PC (always-not-taken: PC += 4)
        RegisterFile.initializeProgramCounter(pc + 4);

        return PipelineLatch.fromFetch(stmt, pc);
    }

    /**
     * Decode stage: read register values, extract immediate, determine control signals.
     */
    private void doDecode(PipelineLatch latch) {
        if (!latch.isValid() || latch.isBubble()) return;

        // Read register values
        latch.setRs1Val(RegisterFile.getValueLong(latch.getRs1()));
        latch.setRs2Val(RegisterFile.getValueLong(latch.getRs2()));

        // Extract immediate
        extractImmediate(latch);

        // Derive control signals
        deriveControlSignals(latch);

        // Derive ALU operation
        deriveAluOperation(latch);
    }

    /**
     * Execute stage: perform ALU operation using RARS's instruction.simulate().
     */
    private void doExecute(PipelineLatch latch) {
        if (!latch.isValid() || latch.isBubble()) return;

        // Save PC state
        int savedPc = RegisterFile.getProgramCounter();
        RegisterFile.initializeProgramCounter(latch.getPc());
        RegisterFile.incrementPC();

        // Set register values (with forwarding applied)
        // We don't actually need to set register values since simulate() reads from RegisterFile
        // and forwarding has already been applied

        try {
            BasicInstruction instruction = (BasicInstruction) latch.getInstruction().getInstruction();
            if (instruction != null) {
                instruction.simulate(latch.getInstruction());
            }
        } catch (Exception e) {
            if (e instanceof rars.ExitingException) {
                done = true;
            }
        }

        // Capture ALU result (rd value after execution)
        if (latch.getRd() != 0) {
            latch.setAluResult(RegisterFile.getValueLong(latch.getRd()));
        }
    }

    /**
     * Memory stage: handled by the instruction.simulate() already, but we
     * capture the memory read data if this is a load.
     */
    private void doMemoryAccess(PipelineLatch latch) {
        if (!latch.isValid() || latch.isBubble()) return;

        if (latch.isLoad()) {
            latch.setMemReadData(latch.getAluResult()); // Load result is in rd
        }
    }

    /**
     * Write-back stage: the register write was already done by simulate(),
     * so this is mainly for visualization.
     */
    private void doWriteBack(PipelineLatch latch) {
        // simulate() already wrote to the register file during EX
        // This stage exists for pipeline correctness and visualization
    }

    /**
     * Apply data forwarding from EX/MEM and MEM/WB to the current ID/EX latch.
     */
    private void applyForwarding(PipelineLatch current, PipelineLatch exMemLatch,
                                  PipelineLatch memWbLatch, StringBuilder hazardDesc) {
        // EX/MEM → EX forwarding (from the instruction one ahead)
        if (exMemLatch.isValid() && !exMemLatch.isBubble() && exMemLatch.writesRegister()) {
            int fwdRd = exMemLatch.getRd();
            if (fwdRd != 0 && fwdRd == current.getRs1()) {
                current.setRs1Val(exMemLatch.getAluResult());
                RegisterFile.updateRegister(current.getRs1(), exMemLatch.getAluResult());
                ForwardingPath fp = new ForwardingPath("EX/MEM", "ID/EX (rs1)",
                        fwdRd, exMemLatch.getAluResult(),
                        String.format("FWD EX/MEM→rs1: x%d = 0x%x", fwdRd, exMemLatch.getAluResult()));
                activeForwards.add(fp);
                hazardDesc.append(fp.description).append(". ");
            }
            if (fwdRd != 0 && fwdRd == current.getRs2()) {
                current.setRs2Val(exMemLatch.getAluResult());
                RegisterFile.updateRegister(current.getRs2(), exMemLatch.getAluResult());
                ForwardingPath fp = new ForwardingPath("EX/MEM", "ID/EX (rs2)",
                        fwdRd, exMemLatch.getAluResult(),
                        String.format("FWD EX/MEM→rs2: x%d = 0x%x", fwdRd, exMemLatch.getAluResult()));
                activeForwards.add(fp);
                hazardDesc.append(fp.description).append(". ");
            }
        }

        // MEM/WB → EX forwarding (from the instruction two ahead)
        if (memWbLatch.isValid() && !memWbLatch.isBubble() && memWbLatch.writesRegister()) {
            int fwdRd = memWbLatch.getRd();
            // Only forward if EX/MEM doesn't already forward the same register
            boolean exMemForwardsRs1 = exMemLatch.isValid() && exMemLatch.writesRegister()
                    && exMemLatch.getRd() == current.getRs1();
            boolean exMemForwardsRs2 = exMemLatch.isValid() && exMemLatch.writesRegister()
                    && exMemLatch.getRd() == current.getRs2();

            if (fwdRd != 0 && fwdRd == current.getRs1() && !exMemForwardsRs1) {
                long fwdVal = memWbLatch.isLoad() ? memWbLatch.getMemReadData() : memWbLatch.getAluResult();
                current.setRs1Val(fwdVal);
                RegisterFile.updateRegister(current.getRs1(), fwdVal);
                ForwardingPath fp = new ForwardingPath("MEM/WB", "ID/EX (rs1)",
                        fwdRd, fwdVal,
                        String.format("FWD MEM/WB→rs1: x%d = 0x%x", fwdRd, fwdVal));
                activeForwards.add(fp);
                hazardDesc.append(fp.description).append(". ");
            }
            if (fwdRd != 0 && fwdRd == current.getRs2() && !exMemForwardsRs2) {
                long fwdVal = memWbLatch.isLoad() ? memWbLatch.getMemReadData() : memWbLatch.getAluResult();
                current.setRs2Val(fwdVal);
                RegisterFile.updateRegister(current.getRs2(), fwdVal);
                ForwardingPath fp = new ForwardingPath("MEM/WB", "ID/EX (rs2)",
                        fwdRd, fwdVal,
                        String.format("FWD MEM/WB→rs2: x%d = 0x%x", fwdRd, fwdVal));
                activeForwards.add(fp);
                hazardDesc.append(fp.description).append(". ");
            }
        }
    }

    /**
     * Check if there are still instructions flowing through the pipeline.
     */
    private boolean hasInFlightInstructions() {
        return (ifId.isValid() && !ifId.isBubble())
                || (idEx.isValid() && !idEx.isBubble())
                || (exMem.isValid() && !exMem.isBubble())
                || (memWb.isValid() && !memWb.isBubble());
    }

    // ===== Helper methods for decode =====

    private void extractImmediate(PipelineLatch latch) {
        if (latch.getInstruction() == null) return;
        int binary = latch.getInstruction().getBinaryStatement();
        int opcode = binary & 0x7F;
        long imm = 0;
        switch (opcode) {
            case 0x13: case 0x03: case 0x67: case 0x1B:
                imm = binary >> 20;
                break;
            case 0x23:
                imm = ((binary >> 25) << 5) | ((binary >> 7) & 0x1F);
                if ((imm & 0x800) != 0) imm |= 0xFFFFF000;
                break;
            case 0x63:
                imm = ((binary >> 31) << 12) | (((binary >> 7) & 1) << 11)
                        | (((binary >> 25) & 0x3F) << 5) | (((binary >> 8) & 0xF) << 1);
                if ((imm & 0x1000) != 0) imm |= 0xFFFFE000;
                break;
            case 0x37: case 0x17:
                imm = binary & 0xFFFFF000;
                break;
            case 0x6F:
                imm = ((binary >> 31) << 20) | (((binary >> 12) & 0xFF) << 12)
                        | (((binary >> 20) & 1) << 11) | (((binary >> 21) & 0x3FF) << 1);
                if ((imm & 0x100000) != 0) imm |= 0xFFE00000;
                break;
        }
        latch.setImmediate(imm);
    }

    private void deriveControlSignals(PipelineLatch latch) {
        int opcode = latch.getOpcode();
        latch.setControlSignal("RegWrite", 0);
        latch.setControlSignal("MemRead", 0);
        latch.setControlSignal("MemWrite", 0);
        latch.setControlSignal("MemToReg", 0);
        latch.setControlSignal("ALUSrc", 0);
        latch.setControlSignal("Branch", 0);
        latch.setControlSignal("Jump", 0);

        switch (opcode) {
            case 0x33: case 0x3B:
                latch.setControlSignal("RegWrite", 1);
                break;
            case 0x13: case 0x1B:
                latch.setControlSignal("RegWrite", 1);
                latch.setControlSignal("ALUSrc", 1);
                break;
            case 0x03:
                latch.setControlSignal("RegWrite", 1);
                latch.setControlSignal("MemRead", 1);
                latch.setControlSignal("MemToReg", 1);
                latch.setControlSignal("ALUSrc", 1);
                break;
            case 0x23:
                latch.setControlSignal("MemWrite", 1);
                latch.setControlSignal("ALUSrc", 1);
                break;
            case 0x63:
                latch.setControlSignal("Branch", 1);
                break;
            case 0x6F:
                latch.setControlSignal("RegWrite", 1);
                latch.setControlSignal("Jump", 1);
                break;
            case 0x67:
                latch.setControlSignal("RegWrite", 1);
                latch.setControlSignal("Jump", 1);
                latch.setControlSignal("ALUSrc", 1);
                break;
            case 0x37: case 0x17:
                latch.setControlSignal("RegWrite", 1);
                latch.setControlSignal("ALUSrc", 1);
                break;
        }
    }

    private void deriveAluOperation(PipelineLatch latch) {
        if (latch.getInstruction() == null || latch.getInstruction().getInstruction() == null) return;
        String name = latch.getInstruction().getInstruction().getName().toUpperCase();
        if (name.startsWith("ADD") || name.startsWith("LW") || name.startsWith("LH")
                || name.startsWith("LB") || name.startsWith("SW") || name.startsWith("SH")
                || name.startsWith("SB") || name.startsWith("JAL") || name.startsWith("AUIPC")) {
            latch.setAluOperation("ADD");
        } else if (name.startsWith("SUB")) {
            latch.setAluOperation("SUB");
        } else if (name.startsWith("AND")) {
            latch.setAluOperation("AND");
        } else if (name.startsWith("OR")) {
            latch.setAluOperation("OR");
        } else if (name.startsWith("XOR")) {
            latch.setAluOperation("XOR");
        } else if (name.startsWith("SLT")) {
            latch.setAluOperation("SLT");
        } else if (name.startsWith("BEQ") || name.startsWith("BNE") || name.startsWith("BLT")
                || name.startsWith("BGE")) {
            latch.setAluOperation("SUB");
        } else if (name.startsWith("LUI")) {
            latch.setAluOperation("PASS");
        } else {
            latch.setAluOperation(name);
        }
    }

    // ==================== Public API ====================

    public List<PipelineSnapshot> getHistory() { return history; }
    public int getCycleCount() { return cycleCount; }
    public boolean isDone() { return done && !hasInFlightInstructions(); }

    public void reset() {
        cycleCount = 0;
        done = false;
        stalling = false;
        ifId = PipelineLatch.bubble();
        idEx = PipelineLatch.bubble();
        exMem = PipelineLatch.bubble();
        memWb = PipelineLatch.bubble();
        activeForwards = new ArrayList<>();
        history = new ArrayList<>();
    }
}
