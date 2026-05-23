package rars.riscv.instructions;

import rars.Globals;
import rars.riscv.BasicInstruction;
import rars.riscv.BasicInstructionFormat;
import rars.ProgramStatement;
import rars.SimulationException;
import rars.riscv.hardware.RegisterFile;
import rars.simulator.Simulator;
import rars.simulator.SharedMemory;

/**
 * Load-Reserved Word (lr.w)
 * Format: R-type (but acts like a load with rs2=0)
 * Usage: lr.w rd, (rs1)
 */
public class LRW extends BasicInstruction {
    public LRW() {
        super("lr.w t1, (t2)", "Load-Reserved Word: Load from effective address and register a reservation",
              BasicInstructionFormat.R_FORMAT, "0001000 00000 sssss 010 fffff 0101111");
    }

    public void simulate(ProgramStatement statement) throws SimulationException {
        int[] operands = statement.getOperands();
        int rd = operands[0];
        int rs1 = operands[1];
        
        int address = RegisterFile.getValue(rs1);
        int value = 0;

        try {
            // Try to use SharedMemory if we are in a visualized multi-core run
            SharedMemory sharedMem = Simulator.getInstance().getSharedMemory();
            if (sharedMem != null) {
                // Core ID is in register a0 (x10) by convention in our multi-core model
                int coreId = RegisterFile.getValue(10);
                value = sharedMem.loadReserved(coreId, address, RegisterFile.getProgramCounter() - 4);
            } else {
                // Fallback for normal execution
                value = Globals.memory.getWord(address);
            }
        } catch (rars.riscv.hardware.AddressErrorException e) {
            throw new SimulationException(statement, "Address error in lr.w", SimulationException.LOAD_ACCESS_FAULT);
        }

        RegisterFile.updateRegister(rd, value);
    }
}
