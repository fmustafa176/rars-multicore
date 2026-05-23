.data
    num1:   .float 5.5       # Define a float
    num2:   .float 2.2       # Define another float
    result: .float 0.0

.text
.globl main

main:
    # 1. Load floating-point values from memory into 'f' registers
    flw   f0, num1, t0       # Load 5.5 into f0
    flw   f1, num2, t0       # Load 2.2 into f1

    # 2. Perform arithmetic
    fadd.s f2, f0, f1        # f2 = 5.5 + 2.2 = 7.7
    fsub.s f3, f0, f1        # f3 = 5.5 - 2.2 = 3.3
    fmul.s f4, f0, f1        # f4 = 5.5 * 2.2 = 12.1
    fdiv.s f5, f0, f1        # f5 = 5.5 / 2.2 = 2.5

    # 3. Store the added result back to memory
    fsw   f2, result, t0

    # 4. Print the result (System Call)
    fmv.s fa0, f2            # Move f2 to fa0 (argument register for float print)
    li    a7, 2              # Service code 2 is "Print Float"
    ecall

    # Exit
    li    a7, 10
    ecall