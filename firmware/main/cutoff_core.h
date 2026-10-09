#ifndef CUTOFF_CORE_H
#define CUTOFF_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define CC_VERSION 1u
#define CC_COMMAND_SIZE 16u
#define CC_STATUS_SIZE 20u
#define CC_BATTERY_UNKNOWN 255u

enum cc_operation { CC_START = 1, CC_REPORT = 2, CC_STOP = 3 };
enum cc_state { CC_READY = 0, CC_CHARGING = 1, CC_ENDED = 2, CC_FAULT = 3 };
enum cc_reason {
    CC_NONE = 0, CC_LIMIT = 1, CC_MANUAL_STOP = 2, CC_DISCONNECTED = 3,
    CC_TIMEOUT = 4, CC_BAD_MESSAGE = 5, CC_BAD_SESSION = 6, CC_REPLAY = 7,
    CC_RESET = 8
};

typedef struct {
    uint32_t connection_nonce;
    uint32_t session_id;
    uint32_t last_sequence;
    uint32_t timeout_ms;
    uint64_t last_report_ms;
    uint8_t battery_percent;
    uint8_t state;
    uint8_t reason;
    bool connected;
    bool relay_on;
} cc_controller;

void cc_init(cc_controller *c, uint32_t timeout_ms);
void cc_connect(cc_controller *c, uint32_t nonce, uint64_t now_ms);
void cc_disconnect(cc_controller *c);
void cc_tick(cc_controller *c, uint64_t now_ms);
bool cc_command(cc_controller *c, const uint8_t *data, size_t length, uint64_t now_ms);
void cc_encode_status(const cc_controller *c, uint8_t out[CC_STATUS_SIZE]);

#endif
