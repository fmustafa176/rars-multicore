.text
.globl main

main:
    # 1. Read the lower 32 bits of the hardware cycle counter
    # This shows you how many clock cycles have passed
    csrr t0, cycle       # Read CSR 'cycle' into integer register t0
    
    # 2. Change Floating-Point Rounding Mode
    # The 'frm' (floating-point rounding mode) is part of the fcsr
    # We can set it directly using an immediate value
    csrwi frm, 1         # Set rounding mode to 1 (Round towards Zero)
    
    # 3. Use an atomic 'Read and Set' 
    # This reads the old status into t1 and sets new bits simultaneously
    csrrs t1, mstatus, t2 # Read 'mstatus' into t1 and set bits defined in t2
    
    # Exit
    li a7, 10
    ecall