#include "cutoff_core.h"
#include <string.h>

static uint32_t read_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void write_u32(uint8_t *p, uint32_t v)
{
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(v >> (8 * i));
}

static void end_session(cc_controller *c, uint8_t state, uint8_t reason)
{
    c->relay_on = false;
    c->state = state;
    c->reason = reason;
}

void cc_init(cc_controller *c, uint32_t timeout_ms)
{
    memset(c, 0, sizeof(*c));
    c->timeout_ms = timeout_ms;
    c->battery_percent = CC_BATTERY_UNKNOWN;
    c->reason = CC_RESET;
}

void cc_connect(cc_controller *c, uint32_t nonce, uint64_t now_ms)
{
    const uint32_t timeout = c->timeout_ms;
    cc_init(c, timeout);
    c->connection_nonce = nonce;
    c->last_report_ms = now_ms;
    c->connected = nonce != 0;
    c->reason = nonce != 0 ? CC_NONE : CC_BAD_SESSION;
    if (nonce == 0) c->state = CC_FAULT;
}

void cc_disconnect(cc_controller *c)
{
    c->connected = false;
    c->relay_on = false;
    if (c->state == CC_CHARGING || c->state == CC_READY)
        end_session(c, CC_FAULT, CC_DISCONNECTED);
}

void cc_tick(cc_controller *c, uint64_t now_ms)
{
    if (c->state == CC_CHARGING &&
        (now_ms < c->last_report_ms || now_ms - c->last_report_ms >= c->timeout_ms))
        end_session(c, CC_FAULT, CC_TIMEOUT);
}

bool cc_command(cc_controller *c, const uint8_t *data, size_t length, uint64_t now_ms)
{
    cc_tick(c, now_ms); /* A late packet must never rescue an expired session. */
    if (!c->connected || c->state == CC_ENDED || c->state == CC_FAULT) return false;
    if (data == NULL || length != CC_COMMAND_SIZE || data[0] != CC_VERSION || data[3] != 0) {
        end_session(c, CC_FAULT, CC_BAD_MESSAGE);
        return false;
    }

    const uint8_t op = data[1];
    const uint8_t percent = data[2];
    const uint32_t nonce = read_u32(data + 4);
    const uint32_t session = read_u32(data + 8);
    const uint32_t sequence = read_u32(data + 12);
    if (nonce != c->connection_nonce || session == 0 || sequence == 0) {
        end_session(c, CC_FAULT, CC_BAD_SESSION);
        return false;
    }
    if ((op != CC_START && op != CC_REPORT && op != CC_STOP) ||
        (op == CC_STOP ? percent != CC_BATTERY_UNKNOWN : percent > 100)) {
        end_session(c, CC_FAULT, CC_BAD_MESSAGE);
        return false;
    }

    if (op == CC_START) {
        if (c->state != CC_READY || sequence != 1) {
            end_session(c, CC_FAULT, CC_BAD_SESSION);
            return false;
        }
        c->session_id = session;
    } else if (c->state != CC_CHARGING || session != c->session_id) {
        end_session(c, CC_FAULT, CC_BAD_SESSION);
        return false;
    }
    if (sequence <= c->last_sequence) {
        end_session(c, CC_FAULT, CC_REPLAY);
        return false;
    }
    c->last_sequence = sequence;
    if (op == CC_STOP) {
        end_session(c, CC_ENDED, CC_MANUAL_STOP);
        return true;
    }

    c->battery_percent = percent;
    c->last_report_ms = now_ms;
    if (percent == 100) {
        end_session(c, CC_ENDED, CC_LIMIT);
    } else {
        c->state = CC_CHARGING;
        c->reason = CC_NONE;
        c->relay_on = true;
    }
    return true;
}

void cc_encode_status(const cc_controller *c, uint8_t out[CC_STATUS_SIZE])
{
    memset(out, 0, CC_STATUS_SIZE);
    out[0] = CC_VERSION;
    out[1] = c->state;
    out[2] = c->reason;
    out[3] = c->relay_on ? 1 : 0;
    write_u32(out + 4, c->connection_nonce);
    write_u32(out + 8, c->session_id);
    write_u32(out + 12, c->last_sequence);
    out[16] = c->battery_percent;
}
