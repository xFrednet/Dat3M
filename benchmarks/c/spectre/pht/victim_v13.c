#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

int is_idx_safe(int idx) {
    if (idx < SIZE) {
        return 1;
    }
    return 0;
}
void victim_v13(int idx) {
    if (is_idx_safe(idx)) {
        int var = A[idx];
        temp &= B[var];
        __VERIFIER_assert(var != SECRET_VALUE);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v13(idx);
    return 0;
}