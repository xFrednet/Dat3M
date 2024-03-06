#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void victim_v12(int a, int b) {
    if ((a + b) < SIZE) {
        int var = A[a + b];
        temp &= B[var];
        __VERIFIER_assert(var != SECRET_VALUE);
    }
}

int main()
{
    int a = __VERIFIER_nondet_int();
    int b = __VERIFIER_nondet_int();
    __VERIFIER_assume((a + b) >= 0);
    victim_v12(a, b);
    return 0;
}