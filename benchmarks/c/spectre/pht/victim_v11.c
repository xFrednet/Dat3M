#include <stdint.h>
#include <string.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v11(int idx) {
    if (idx < SIZE) {
        int var = A[idx];
        temp = memcpy(&temp, B + var, 1);
        __VERIFIER_assert(var != SECRET_VALUE);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v11(idx);
    return 0;
}