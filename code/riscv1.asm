# Simple Fibonacci Script for RARS
.data
    fib_sequence: .word 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 # Space for 10 numbers

.text
.globl main

main:
    li   t0, 10              # Set loop counter (10 terms)
    la   t1, fib_sequence    # Load address of the array
    
    li   t2, 0               # F(n-2)
    li   t3, 1               # F(n-1)

fib_loop:
    sw   t2, 0(t1)           # Store the current number in the array
    
    add  t4, t2, t3          # Calculate next number: t4 = t2 + t3
    mv   t2, t3              # Shift: F(n-2) becomes F(n-1)
    mv   t3, t4              # Shift: F(n-1) becomes the new number
    
    addi t1, t1, 4           # Move array pointer (4 bytes per word)
    addi t0, t0, -1          # Decrement loop counter
    bnez t0, fib_loop        # Repeat if counter != 0

    # Exit the program (Standard RISC-V environment call)
    li   a7, 10              # Service code 10 = exit
    ecall