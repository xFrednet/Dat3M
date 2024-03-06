#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v9(int idx, int *is_safe) {
    if (*is_safe) {
        int var = A[idx];
        temp &= B[var];
        __VERIFIER_assert(var != SECRET_VALUE);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    int is_safe = idx < SIZE;
    __VERIFIER_assume(idx >= 0);

    victim_v9(idx, &is_safe);
    return 0;
}