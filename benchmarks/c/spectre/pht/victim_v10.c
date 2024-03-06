#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v10(int idx, int guess) {
    if (idx < SIZE) {
        int var = A[idx];
        if (var == guess) {
            temp &= B[0];
        }
        // I think here we need a new assert, to catch this volunerability
        __VERIFIER_assert(var != SECRET_VALUE && guess != SECRET_VALUE);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    int guess = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    __VERIFIER_assume(guess >= 0);
    victim_v10(idx, guess);
    return 0;
}