#include <stdint.h>
#include <dat3m.h>

#define SIZE 16
#define SECRET_VALUE 42

uint8_t A[SIZE] = { 0 };
uint8_t B[SIZE] = { 0 };
uint8_t secret = SECRET_VALUE;

volatile uint8_t temp = 0;

void leakByteLocalFunction(uint8_t k) {
    temp &= B[(k)];
    __VERIFIER_assert(k != SECRET_VALUE);
}
void victim_v2(int idx) {
    if (idx < SIZE) {
        int var = A[idx];
        leakByteLocalFunction(var);
    }
}

int main()
{
    int idx = __VERIFIER_nondet_int();
    __VERIFIER_assume(idx >= 0);
    victim_v2(idx);
    return 0;
}