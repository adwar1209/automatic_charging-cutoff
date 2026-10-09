#include "cutoff_core.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

static cc_controller c;
static uint8_t packet[16];
static unsigned checks;
#define CHECK(x) do { assert(x); ++checks; } while (0)

static void put32(uint8_t *out, uint32_t value)
{
    for (unsigned i = 0; i < 4; ++i) out[i] = (uint8_t)(value >> (i * 8));
}
static void fresh(void) { cc_init(&c, 90000); cc_connect(&c, 0x11223344, 0); }
static bool command(uint8_t op, uint8_t percent, uint32_t sequence, uint64_t time)
{
    memset(packet, 0, sizeof(packet));
    packet[0] = 1; packet[1] = op; packet[2] = percent;
    put32(packet + 4, 0x11223344); put32(packet + 8, 0xaabbccdd); put32(packet + 12, sequence);
    return cc_command(&c, packet, sizeof(packet), time);
}
static void charging(void) { fresh(); CHECK(command(CC_START, 50, 1, 0)); CHECK(c.relay_on); }

int main(int argc, char **argv)
{
    if (argc == 2 && strcmp(argv[1], "--wire") == 0) {
        fresh(); char line[256]; uint64_t time = 0;
        while (fgets(line, sizeof(line), stdin)) {
            assert(strlen(line) == 33);
            for (unsigned i = 0, byte; i < 16; ++i) {
                assert(sscanf(line + 2 * i, "%2x", &byte) == 1); packet[i] = (uint8_t)byte;
            }
            cc_command(&c, packet, sizeof(packet), time);
            uint8_t status[20]; cc_encode_status(&c, status);
            for (unsigned i = 0; i < 20; ++i) printf("%02x", status[i]);
            puts(""); time += 1000;
        }
        return 0;
    }

    cc_init(&c, 90000);
    CHECK(!c.relay_on && !c.connected && c.reason == CC_RESET);
    CHECK(!command(CC_START, 50, 1, 0));
    fresh(); CHECK(c.state == CC_READY && !c.relay_on);
    CHECK(command(CC_START, 0, 1, 0)); CHECK(c.relay_on); // Empty but running phone can charge.
    CHECK(command(CC_REPORT, 99, 2, 30000)); CHECK(c.relay_on);
    CHECK(command(CC_REPORT, 100, 3, 60000));
    CHECK(!c.relay_on && c.state == CC_ENDED && c.reason == CC_LIMIT);
    CHECK(!command(CC_REPORT, 50, 4, 60001)); CHECK(!c.relay_on);
    CHECK(!command(CC_START, 50, 1, 60002)); CHECK(c.reason == CC_LIMIT);
    cc_disconnect(&c); CHECK(c.reason == CC_LIMIT);
    fresh(); CHECK(command(CC_START, 100, 1, 0)); CHECK(!c.relay_on && c.reason == CC_LIMIT);

    charging(); CHECK(command(CC_STOP, 255, 2, 1)); CHECK(!c.relay_on && c.reason == CC_MANUAL_STOP);
    charging(); cc_disconnect(&c); CHECK(!c.relay_on && c.reason == CC_DISCONNECTED);
    CHECK(!command(CC_REPORT, 50, 2, 1)); CHECK(!c.relay_on);
    charging(); cc_tick(&c, 89999); CHECK(c.relay_on);
    cc_tick(&c, 90000); CHECK(!c.relay_on && c.reason == CC_TIMEOUT);
    charging(); CHECK(!command(CC_REPORT, 50, 2, 90000)); CHECK(c.reason == CC_TIMEOUT);
    charging(); CHECK(command(CC_REPORT, 51, 2, 89999));
    cc_tick(&c, 179998); CHECK(c.relay_on); cc_tick(&c, 179999); CHECK(!c.relay_on);
    charging(); CHECK(command(CC_REPORT, 50, 2, 1000)); cc_tick(&c, 999);
    CHECK(!c.relay_on && c.reason == CC_TIMEOUT); // Backward clock cannot extend a session.

    charging(); CHECK(!command(CC_REPORT, 50, 1, 1)); CHECK(c.reason == CC_REPLAY && !c.relay_on);
    charging(); CHECK(command(CC_REPORT, 50, 0xffffffffu, 1));
    CHECK(!command(CC_REPORT, 50, 1, 2)); CHECK(c.reason == CC_REPLAY);
    charging(); CHECK(!command(CC_START, 50, 1, 1)); CHECK(c.reason == CC_BAD_SESSION);
    fresh(); CHECK(!command(CC_REPORT, 50, 1, 0)); CHECK(!c.relay_on);
    fresh(); CHECK(!command(CC_START, 50, 2, 0)); CHECK(c.reason == CC_BAD_SESSION);
    fresh(); CHECK(!command(CC_START, 50, 0, 0)); CHECK(c.reason == CC_BAD_SESSION);

    for (unsigned percent = 101; percent <= 255; ++percent) {
        charging(); CHECK(!command(CC_REPORT, (uint8_t)percent, 2, 1));
        CHECK(!c.relay_on && c.reason == CC_BAD_MESSAGE);
    }
    charging(); CHECK(!command(CC_STOP, 50, 2, 1)); CHECK(!c.relay_on);
    charging(); CHECK(!command(99, 50, 2, 1)); CHECK(!c.relay_on);
    for (size_t size = 0; size < 32; ++size) {
        if (size == 16) continue;
        charging(); uint8_t bad[32] = {1, 2, 50};
        CHECK(!cc_command(&c, bad, size, 1)); CHECK(!c.relay_on);
    }
    charging(); CHECK(!cc_command(&c, NULL, 16, 1)); CHECK(!c.relay_on);
    for (unsigned field = 0; field < 4; ++field) {
        charging(); packet[field == 0 ? 0 : field == 1 ? 3 : field == 2 ? 4 : 8] ^= 1;
        CHECK(!cc_command(&c, packet, 16, 1)); CHECK(!c.relay_on);
    }
    charging(); put32(packet + 8, 0); CHECK(!cc_command(&c, packet, 16, 1)); CHECK(!c.relay_on);
    charging(); packet[1] = CC_REPORT; put32(packet + 8, 42); put32(packet + 12, 2);
    CHECK(!cc_command(&c, packet, 16, 1)); CHECK(!c.relay_on && c.reason == CC_BAD_SESSION);
    charging(); packet[1] = CC_REPORT; put32(packet + 4, 42); put32(packet + 12, 2);
    CHECK(!cc_command(&c, packet, 16, 1)); CHECK(!c.relay_on && c.reason == CC_BAD_SESSION);
    charging(); cc_connect(&c, 0x55667788, 2); // Old connection packet rejected after reconnect.
    CHECK(!cc_command(&c, packet, 16, 3)); CHECK(!c.relay_on);
    cc_connect(&c, 0, 0); CHECK(c.state == CC_FAULT && !c.connected && !c.relay_on);
    charging(); cc_init(&c, 90000); CHECK(!c.relay_on && !c.connected);

    // Deterministic malformed-packet exploration; no malformed first message may enable power.
    uint32_t random = 1234567;
    for (unsigned n = 0; n < 10000; ++n) {
        fresh();
        for (unsigned i = 0; i < sizeof(packet); ++i) {
            random = 1664525u * random + 1013904223u; packet[i] = (uint8_t)(random >> 24);
        }
        packet[0] = 0; // An explicitly unsupported version.
        cc_command(&c, packet, 16, n);
        CHECK(!c.relay_on && c.state == CC_FAULT);
    }
    printf("PASS: cutoff core (%u assertions, including 10000 malformed packets)\n", checks);
    return 0;
}
