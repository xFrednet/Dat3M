#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v4(int idx) {
    if (idx < SIZE) {
        int var = A[idx << 1];
        temp &= B[var];
        __VERIFIER_assert(var != SECRET_VALUE);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v4(idx);
    return 0;
}