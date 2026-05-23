.data
shared_counter_unsafe: .word 0
shared_counter_safe:   .word 0

.text
.globl main
main:
    # ---------------------------------------------------------
    #  Multi-Core Test
    # This program tests:
    # 1. Multi-Core Execution & Shared Memory interleaving
    # 2. Race Condition Detection (unprotected RMW)
    # 3. LR/SC Atomic Operations (protected RMW)
    # 
    # Since all cores start at 'main' simultaneously in
    # multicore mode, we use a0 (x10) which holds the core ID.
    # ---------------------------------------------------------
    
    # Let's set up the test parameters
    li s0, 5        # Each core will loop 5 times
    li s1, 0        # Loop counter i = 0

loop:
    beq s1, s0, done
    
    # ---------------------------------------------------------
    # TEST 1 & 2: Shared Memory & Race Conditions
    # ---------------------------------------------------------
    # We will do a non-atomic read-modify-write on 'shared_counter_unsafe'
    # Since multiple cores are doing this at the same time,
    # the GUI Race Condition Panel should flag these accesses!
    la t0, shared_counter_unsafe
    lw t1, 0(t0)            # UNPROTECTED READ
    addi t1, t1, 1          # Increment
    # Small delay to increase chance of interleaving causing a race visually
    nop
    sw t1, 0(t0)            # UNPROTECTED WRITE
    
    # ---------------------------------------------------------
    # TEST 3: LR/SC Atomic Operations
    # ---------------------------------------------------------
    # Now we do a safe, atomic increment on 'shared_counter_safe'
    # using Load-Reserved (lr.w) and Store-Conditional (sc.w)
    la t2, shared_counter_safe
retry:
    lr.w t3, (t2)           # LOAD-RESERVED
    addi t3, t3, 1          # Increment
    sc.w t4, t3, (t2)       # STORE-CONDITIONAL
    
    # If sc.w fails (another core wrote to the address), t4 will be non-zero
    bnez t4, retry          # Retry if atomic operation failed
    
    # Increment loop counter
    addi s1, s1, 1
    j loop
    
done:
    # We are done, halt the core
halt:
    j halt
