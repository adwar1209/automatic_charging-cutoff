#include <inttypes.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_sm.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "cutoff_core.h"

static const char *TAG = "charging_cutoff";
static const char *DEVICE_NAME = "ChargeCutoff";
static SemaphoreHandle_t controller_mutex;
static cc_controller controller;
static uint16_t connection = BLE_HS_CONN_HANDLE_NONE;
static uint16_t status_handle;
static bool subscribed;
static bool applied_relay;
static uint8_t own_address_type;

/* Canonical UUID: 7d7e0001-8c6e-4d26-b11c-63430d8b23cc.
 * NimBLE's 128-bit initializer stores the bytes least-significant first. */
static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(
    0xcc,0x23,0x8b,0x0d,0x43,0x63,0x1c,0xb1,0x26,0x4d,0x6e,0x8c,0x01,0x00,0x7e,0x7d);
static const ble_uuid128_t command_uuid = BLE_UUID128_INIT(
    0xcc,0x23,0x8b,0x0d,0x43,0x63,0x1c,0xb1,0x26,0x4d,0x6e,0x8c,0x02,0x00,0x7e,0x7d);
static const ble_uuid128_t status_uuid = BLE_UUID128_INIT(
    0xcc,0x23,0x8b,0x0d,0x43,0x63,0x1c,0xb1,0x26,0x4d,0x6e,0x8c,0x03,0x00,0x7e,0x7d);

void ble_store_config_init(void);
static void advertise(void);
static int gap_event(struct ble_gap_event *event, void *arg);

static void require_ok(int rc)
{
    if (rc != 0) {
        ESP_LOGE(TAG, "BLE initialization failed: %d", rc);
        abort();
    }
}

static uint64_t now_ms(void) { return (uint64_t)esp_timer_get_time() / 1000u; }
static void lock_controller(void) { xSemaphoreTake(controller_mutex, portMAX_DELAY); }
static void unlock_controller(void) { xSemaphoreGive(controller_mutex); }

static void relay_init(void)
{
#if CONFIG_CUTOFF_RELAY_GPIO >= 0
    const gpio_num_t pin = (gpio_num_t)CONFIG_CUTOFF_RELAY_GPIO;
    if (!GPIO_IS_VALID_OUTPUT_GPIO(pin)) {
        ESP_LOGE(TAG, "Configured relay GPIO is not output-capable");
        abort();
    }
#ifdef CONFIG_CUTOFF_RELAY_ACTIVE_LOW
    const int off_level = 1;
#else
    const int off_level = 0;
#endif
    /* External bias is still required during reset, before this code executes. */
    ESP_ERROR_CHECK(gpio_set_level(pin, off_level));
    const gpio_config_t config = {
        .pin_bit_mask = 1ULL << CONFIG_CUTOFF_RELAY_GPIO,
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&config));
    ESP_ERROR_CHECK(gpio_set_level(pin, off_level));
#else
    ESP_LOGW(TAG, "Relay GPIO is -1: physical output disabled. Configure it in menuconfig.");
#endif
}

/* Called with the mutex held; GPIO changes and the state transition are atomic
 * with respect to BLE callbacks and the timeout task. */
static void apply_relay(void)
{
    if (applied_relay == controller.relay_on) return;
    applied_relay = controller.relay_on;
#if CONFIG_CUTOFF_RELAY_GPIO >= 0
#ifdef CONFIG_CUTOFF_RELAY_ACTIVE_LOW
    const int on_level = 0;
#else
    const int on_level = 1;
#endif
    ESP_ERROR_CHECK(gpio_set_level((gpio_num_t)CONFIG_CUTOFF_RELAY_GPIO,
                                  applied_relay ? on_level : !on_level));
#endif
    ESP_LOGI(TAG, "Relay command %s; battery=%u state=%u reason=%u",
             applied_relay ? "ON" : "OFF", controller.battery_percent,
             controller.state, controller.reason);
}

static void notify_status(void)
{
    uint8_t bytes[CC_STATUS_SIZE];
    lock_controller();
    const uint16_t handle = connection;
    const bool send = subscribed && handle != BLE_HS_CONN_HANDLE_NONE;
    cc_encode_status(&controller, bytes);
    unlock_controller();
    if (!send) return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(bytes, sizeof(bytes));
    if (om != NULL) {
        /* NimBLE consumes om, including when this call fails. */
        const int rc = ble_gatts_notify_custom(handle, status_handle, om);
        if (rc != 0) ESP_LOGD(TAG, "Status notification unavailable: %d", rc);
    }
}

static int access_characteristic(uint16_t conn, uint16_t attr,
                                 struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr;
    (void)arg;
    struct ble_gap_conn_desc desc;
    if (ble_gap_conn_find(conn, &desc) != 0 || !desc.sec_state.encrypted ||
        !desc.sec_state.authenticated) return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;

    uint8_t bytes[CC_STATUS_SIZE];
    lock_controller();
    if (conn != connection) {
        unlock_controller();
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR &&
        ble_uuid_cmp(ctxt->chr->uuid, &status_uuid.u) == 0) {
        cc_tick(&controller, now_ms());
        apply_relay();
        cc_encode_status(&controller, bytes);
        unlock_controller();
        return os_mbuf_append(ctxt->om, bytes, sizeof(bytes)) == 0
            ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR &&
        ble_uuid_cmp(ctxt->chr->uuid, &command_uuid.u) == 0) {
        uint16_t copied = 0;
        const uint16_t length = OS_MBUF_PKTLEN(ctxt->om);
        if (length == CC_COMMAND_SIZE &&
            ble_hs_mbuf_to_flat(ctxt->om, bytes, CC_COMMAND_SIZE, &copied) == 0)
            cc_command(&controller, bytes, copied, now_ms());
        else
            cc_command(&controller, NULL, 0, now_ms());
        apply_relay();
        unlock_controller();
        notify_status();
        /* ATT completion is transport success. The status read is the
         * application-level acknowledgement and reports rejected commands too. */
        return 0;
    }
    unlock_controller();
    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &service_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &command_uuid.u,
                .access_cb = access_characteristic,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_ENC,
            },
            {
                .uuid = &status_uuid.u,
                .access_cb = access_characteristic,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_READ_ENC |
                         BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &status_handle,
            },
            {0}
        },
    },
    {0}
};

static void advertise(void)
{
    const struct ble_hs_adv_fields fields = {
        .flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
        .uuids128 = (ble_uuid128_t *)&service_uuid,
        .num_uuids128 = 1,
        .uuids128_is_complete = 1,
    };
    struct ble_hs_adv_fields response = {0};
    response.name = (const uint8_t *)DEVICE_NAME;
    response.name_len = strlen(DEVICE_NAME);
    response.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc == 0) rc = ble_gap_adv_rsp_set_fields(&response);
    const struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
    };
    if (rc == 0)
        rc = ble_gap_adv_start(own_address_type, NULL, BLE_HS_FOREVER,
                              &params, gap_event, NULL);
    if (rc != 0) ESP_LOGE(TAG, "Advertising failed: %d", rc);
}

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status != 0) { advertise(); return 0; }
        lock_controller();
        if (connection != BLE_HS_CONN_HANDLE_NONE) {
            unlock_controller();
            ble_gap_terminate(event->connect.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
            return 0;
        }
        connection = event->connect.conn_handle;
        subscribed = false;
        uint32_t nonce;
        do { nonce = esp_random(); } while (nonce == 0);
        cc_connect(&controller, nonce, now_ms());
        apply_relay();
        unlock_controller();
        ESP_LOGI(TAG, "Phone connected; charging remains OFF until START");
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        lock_controller();
        if (connection == event->disconnect.conn.conn_handle) {
            cc_disconnect(&controller);
            apply_relay();
            connection = BLE_HS_CONN_HANDLE_NONE;
            subscribed = false;
        }
        unlock_controller();
        advertise();
        return 0;
    case BLE_GAP_EVENT_SUBSCRIBE:
        lock_controller();
        if (event->subscribe.conn_handle == connection &&
            event->subscribe.attr_handle == status_handle)
            subscribed = event->subscribe.cur_notify;
        unlock_controller();
        return 0;
    case BLE_GAP_EVENT_PASSKEY_ACTION: {
        if (event->passkey.params.action == BLE_SM_IOACT_DISP) {
            struct ble_sm_io io = {0};
            io.action = BLE_SM_IOACT_DISP;
            io.passkey = esp_random() % 1000000u;
            ESP_LOGW(TAG, "PAIRING PIN: %06" PRIu32 " (enter on the Android phone)", io.passkey);
            return ble_sm_inject_io(event->passkey.conn_handle, &io);
        }
        return BLE_HS_ENOTSUP;
    }
    case BLE_GAP_EVENT_REPEAT_PAIRING:
        /* Do not silently discard a stored bond. Re-provision explicitly. */
        return BLE_GAP_REPEAT_PAIRING_IGNORE;
    case BLE_GAP_EVENT_ENC_CHANGE:
        if (event->enc_change.status != 0)
            ble_gap_terminate(event->enc_change.conn_handle, BLE_ERR_REM_USER_CONN_TERM);
        return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;
    default:
        return 0;
    }
}

static void on_reset(int reason)
{
    lock_controller();
    cc_disconnect(&controller);
    apply_relay();
    connection = BLE_HS_CONN_HANDLE_NONE;
    subscribed = false;
    unlock_controller();
    ESP_LOGE(TAG, "BLE reset (%d): charging OFF", reason);
}

static void on_sync(void)
{
    require_ok(ble_hs_util_ensure_addr(0));
    require_ok(ble_hs_id_infer_auto(0, &own_address_type));
    advertise();
}

static void host_task(void *arg)
{
    (void)arg;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

static void timeout_task(void *arg)
{
    (void)arg;
    for (;;) {
        lock_controller();
        const uint8_t previous = controller.state;
        cc_tick(&controller, now_ms());
        apply_relay();
        const bool changed = previous != controller.state;
        unlock_controller();
        if (changed) notify_status();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void app_main(void)
{
    controller_mutex = xSemaphoreCreateMutex();
    if (controller_mutex == NULL) abort();
    cc_init(&controller, CONFIG_CUTOFF_REPORT_TIMEOUT_MS);
    relay_init();
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    require_ok(ble_gatts_count_cfg(services));
    require_ok(ble_gatts_add_svcs(services));
    require_ok(ble_svc_gap_device_name_set(DEVICE_NAME));
    ble_store_config_init();
    if (xTaskCreate(timeout_task, "cutoff_timeout", 3072, NULL, 5, NULL) != pdPASS) abort();
    nimble_port_freertos_init(host_task);
}
