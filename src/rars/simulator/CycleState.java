package rars.simulator;

import rars.ProgramStatement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captures the complete microarchitectural state at one clock cycle.
 * Used by all three execution modes to communicate state to the GUI
 * visualization panels.
 */
public class CycleState {

    /**
     * Pipeline stages / execution phases.
     */
    public enum Stage {
        FETCH("IF", java.awt.Color.decode("#4A90D9")),       // blue
        DECODE("ID", java.awt.Color.decode("#27AE60")),      // green
        EXECUTE("EX", java.awt.Color.decode("#F39C12")),     // yellow/orange
        MEMORY("MEM", java.awt.Color.decode("#E67E22")),     // orange
        WRITEBACK("WB", java.awt.Color.decode("#E74C3C")),   // red
        STALL("STALL", java.awt.Color.decode("#95A5A6")),    // gray
        BUBBLE("BUBBLE", java.awt.Color.decode("#BDC3C7")),  // light gray
        FLUSH("FLUSH", java.awt.Color.decode("#C0392B"));    // dark red

        private final String label;
        private final java.awt.Color color;

        Stage(String label, java.awt.Color color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() { return label; }
        public java.awt.Color getColor() { return color; }
    }

    // Cycle identification
    private int cycleNumber;
    private int coreId;

    // Instruction info
    private ProgramStatement instruction;
    private int pc;
    private Stage currentStage;
    private String instructionText; // human-readable assembly, e.g., "add x1, x2, x3"
    private String binaryEncoding;  // 32-bit binary string

    // Register operands
    private int rs1;
    private int rs2;
    private int rd;
    private long rs1Val;
    private long rs2Val;
    private long rdVal;      // value written to rd (after writeback)
    private long immediate;

    // ALU
    private long aluInput1;
    private long aluInput2;
    private long aluResult;
    private String aluOperation; // e.g., "ADD", "SUB", "AND", "SLT"

    // Memory
    private long memAddress;
    private long memReadData;
    private long memWriteData;
    private boolean memRead;
    private boolean memWrite;

    // Branch
    private boolean isBranch;
    private boolean branchTaken;
    private int branchTarget;

    // Control signals (human-readable for visualization)
    private Map<String, Integer> controlSignals;

    // Pipeline-specific
    private boolean isStall;
    private boolean isFlush;
    private boolean isBubble;
    private String hazardDescription;

    public CycleState() {
        this.controlSignals = new LinkedHashMap<>(); // preserve insertion order
        this.hazardDescription = "";
        this.instructionText = "";
        this.binaryEncoding = "";
        this.aluOperation = "";
    }

    // ==================== Control Signal Helpers ====================

    /**
     * Set standard RISC-V single-cycle control signals based on instruction type.
     */
    public void deriveControlSignals(ProgramStatement stmt) {
        if (stmt == null || stmt.getInstruction() == null) return;

        int binary = stmt.getBinaryStatement();
        int opcode = binary & 0x7F;

        // Default all signals to 0
        controlSignals.put("RegWrite", 0);
        controlSignals.put("MemRead", 0);
        controlSignals.put("MemWrite", 0);
        controlSignals.put("MemToReg", 0);
        controlSignals.put("ALUSrc", 0);
        controlSignals.put("Branch", 0);
        controlSignals.put("Jump", 0);

        switch (opcode) {
            case 0x33: // R-type (add, sub, and, or, etc.)
            case 0x3B: // R-type (64-bit W variants)
                controlSignals.put("RegWrite", 1);
                break;
            case 0x13: // I-type arithmetic (addi, andi, etc.)
            case 0x1B: // I-type (64-bit W variants)
                controlSignals.put("RegWrite", 1);
                controlSignals.put("ALUSrc", 1);
                break;
            case 0x03: // Load (lw, lh, lb, etc.)
                controlSignals.put("RegWrite", 1);
                controlSignals.put("MemRead", 1);
                controlSignals.put("MemToReg", 1);
                controlSignals.put("ALUSrc", 1);
                break;
            case 0x23: // Store (sw, sh, sb)
                controlSignals.put("MemWrite", 1);
                controlSignals.put("ALUSrc", 1);
                break;
            case 0x63: // Branch (beq, bne, blt, etc.)
                controlSignals.put("Branch", 1);
                break;
            case 0x6F: // JAL
                controlSignals.put("RegWrite", 1);
                controlSignals.put("Jump", 1);
                break;
            case 0x67: // JALR
                controlSignals.put("RegWrite", 1);
                controlSignals.put("Jump", 1);
                controlSignals.put("ALUSrc", 1);
                break;
            case 0x37: // LUI
            case 0x17: // AUIPC
                controlSignals.put("RegWrite", 1);
                controlSignals.put("ALUSrc", 1);
                break;
            case 0x73: // SYSTEM (ecall, ebreak, CSR)
                // Varies, but generally no memory access
                break;
            default:
                break;
        }
    }

    /**
     * Extract register numbers from the binary instruction encoding.
     */
    public void deriveRegisterFields(ProgramStatement stmt) {
        if (stmt == null) return;
        int binary = stmt.getBinaryStatement();

        this.rd  = (binary >> 7)  & 0x1F;
        this.rs1 = (binary >> 15) & 0x1F;
        this.rs2 = (binary >> 20) & 0x1F;

        // Extract instruction text
        if (stmt.getInstruction() != null) {
            this.instructionText = stmt.getPrintableBasicAssemblyStatement();
        }
        this.binaryEncoding = String.format("%32s", Integer.toBinaryString(binary)).replace(' ', '0');
    }

    // ==================== Standard Getters & Setters ====================

    public int getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(int n) { this.cycleNumber = n; }

    public int getCoreId() { return coreId; }
    public void setCoreId(int id) { this.coreId = id; }

    public ProgramStatement getInstruction() { return instruction; }
    public void setInstruction(ProgramStatement stmt) { this.instruction = stmt; }

    public int getPc() { return pc; }
    public void setPc(int pc) { this.pc = pc; }

    public Stage getCurrentStage() { return currentStage; }
    public void setCurrentStage(Stage stage) { this.currentStage = stage; }

    public String getInstructionText() { return instructionText; }
    public void setInstructionText(String t) { this.instructionText = t; }

    public String getBinaryEncoding() { return binaryEncoding; }

    public int getRs1() { return rs1; }
    public void setRs1(int r) { this.rs1 = r; }
    public int getRs2() { return rs2; }
    public void setRs2(int r) { this.rs2 = r; }
    public int getRd() { return rd; }
    public void setRd(int r) { this.rd = r; }

    public long getRs1Val() { return rs1Val; }
    public void setRs1Val(long v) { this.rs1Val = v; }
    public long getRs2Val() { return rs2Val; }
    public void setRs2Val(long v) { this.rs2Val = v; }
    public long getRdVal() { return rdVal; }
    public void setRdVal(long v) { this.rdVal = v; }

    public long getImmediate() { return immediate; }
    public void setImmediate(long imm) { this.immediate = imm; }

    public long getAluInput1() { return aluInput1; }
    public void setAluInput1(long v) { this.aluInput1 = v; }
    public long getAluInput2() { return aluInput2; }
    public void setAluInput2(long v) { this.aluInput2 = v; }
    public long getAluResult() { return aluResult; }
    public void setAluResult(long v) { this.aluResult = v; }
    public String getAluOperation() { return aluOperation; }
    public void setAluOperation(String op) { this.aluOperation = op; }

    public long getMemAddress() { return memAddress; }
    public void setMemAddress(long a) { this.memAddress = a; }
    public long getMemReadData() { return memReadData; }
    public void setMemReadData(long v) { this.memReadData = v; }
    public long getMemWriteData() { return memWriteData; }
    public void setMemWriteData(long v) { this.memWriteData = v; }
    public boolean isMemRead() { return memRead; }
    public void setMemRead(boolean b) { this.memRead = b; }
    public boolean isMemWrite() { return memWrite; }
    public void setMemWrite(boolean b) { this.memWrite = b; }

    public boolean isBranch() { return isBranch; }
    public void setIsBranch(boolean b) { this.isBranch = b; }
    public boolean isBranchTaken() { return branchTaken; }
    public void setBranchTaken(boolean b) { this.branchTaken = b; }
    public int getBranchTarget() { return branchTarget; }
    public void setBranchTarget(int t) { this.branchTarget = t; }

    public Map<String, Integer> getControlSignals() { return controlSignals; }
    public void setControlSignal(String name, int value) { controlSignals.put(name, value); }

    public boolean isStall() { return isStall; }
    public void setStall(boolean s) { this.isStall = s; }
    public boolean isFlush() { return isFlush; }
    public void setFlush(boolean f) { this.isFlush = f; }
    public boolean isBubble() { return isBubble; }
    public void setBubble(boolean b) { this.isBubble = b; }
    public String getHazardDescription() { return hazardDescription; }
    public void setHazardDescription(String d) { this.hazardDescription = d; }

    @Override
    public String toString() {
        if (isStall) return String.format("Cycle %d: STALL", cycleNumber);
        if (isFlush) return String.format("Cycle %d: FLUSH", cycleNumber);
        if (isBubble) return String.format("Cycle %d: BUBBLE", cycleNumber);
        return String.format("Cycle %d [%s] PC=0x%08x %s",
                cycleNumber,
                currentStage != null ? currentStage.getLabel() : "?",
                pc,
                instructionText);
    }
}
