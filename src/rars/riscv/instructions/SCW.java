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
 * Store-Conditional Word (sc.w)
 * Format: R-type
 * Usage: sc.w rd, rs2, (rs1)
 * Stores rs2 to address in rs1 ONLY IF the reservation from lr.w is still valid.
 * Writes 0 to rd on success, non-zero (1) on failure.
 */
public class SCW extends BasicInstruction {
    public SCW() {
        super("sc.w t1, t2, (t3)", "Store-Conditional Word: Store conditionally and write status to rd",
              BasicInstructionFormat.R_FORMAT, "0001100 ttttt sssss 010 fffff 0101111");
    }

    public void simulate(ProgramStatement statement) throws SimulationException {
        int[] operands = statement.getOperands();
        int rd = operands[0];
        int rs2 = operands[1];
        int rs1 = operands[2];
        
        int address = RegisterFile.getValue(rs1);
        int valueToStore = RegisterFile.getValue(rs2);
        int status = 0; // 0 = success, 1 = failure

        try {
            SharedMemory sharedMem = Simulator.getInstance().getSharedMemory();
            if (sharedMem != null) {
                int coreId = RegisterFile.getValue(10);
                status = sharedMem.storeConditional(coreId, address, valueToStore, RegisterFile.getProgramCounter() - 4);
            } else {
                // Fallback for normal execution (assume always succeeds)
                Globals.memory.setWord(address, valueToStore);
                status = 0;
            }
        } catch (rars.riscv.hardware.AddressErrorException e) {
            throw new SimulationException(statement, "Address error in sc.w", SimulationException.STORE_ACCESS_FAULT);
        }

        RegisterFile.updateRegister(rd, status);
    }
}
