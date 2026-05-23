# Simple Register Tracking Test
.text
.globl main

main:
    addi t0, x0, 5      # t0 = 0 + 5. (Tests I-type decoding)
    addi t1, x0, 10     # t1 = 0 + 10.
    
    add  t2, t0, t1     # t2 = 5 + 10 = 15 (0xF). (Tests R-type decoding)
    
    sub  t0, t1, t0     # t0 = 10 - 5 = 5.
    
    slli t1, t1, 1      # t1 = 10 << 1 = 20 (0x14). (Tests Shift operations)
    
    or   t2, t2, t1     # t2 = 15 | 20. (Tests Logic operations)
    
    # End of test
    li a7, 10           # Exit
    ecall