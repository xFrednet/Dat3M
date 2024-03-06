#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v8(int idx) {
    int var = A[idx < SIZE ? (idx + 1) : 0];
    temp &= B[var];
    __VERIFIER_assert(var != SECRET_VALUE);
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v8(idx);
    return 0;
}