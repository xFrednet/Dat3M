#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v7(int idx) {
    static int last_idx = 0;
    if (idx == last_idx) {
        int var = A[idx];
        temp &= B[var];
        __VERIFIER_assert(var != SECRET_VALUE);
    }
    if (idx < SIZE) {
        last_idx = idx;
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v7(idx);
    return 0;
}