#include <stdbool.h>
#include <stdint.h>

#include "bsp/board.h"
#include "hardware/gpio.h"
#include "pico/stdlib.h"
#include "tusb.h"
#include "usb_descriptors.h"

#define ARM_PIN 14
#define TOUCH_MAX 4095

typedef enum {
    STEP_WAIT,
    STEP_TAP,
    STEP_SWIPE,
} step_type_t;

typedef struct {
    step_type_t type;
    uint16_t x1;
    uint16_t y1;
    uint16_t x2;
    uint16_t y2;
    uint16_t hold_ms;
    uint16_t duration_ms;
    uint16_t after_ms;
    uint8_t steps;
} macro_step_t;

static macro_step_t const macro_steps[] = {
    { .type = STEP_WAIT, .duration_ms = 3000 },
    { .type = STEP_TAP, .x1 = 2048, .y1 = 2048, .hold_ms = 80, .after_ms = 400 },
    { .type = STEP_SWIPE, .x1 = 2048, .y1 = 3300, .x2 = 2048, .y2 = 800, .duration_ms = 450, .steps = 24, .after_ms = 500 },
};

static void wait_ms_with_usb(uint32_t ms) {
    uint32_t start = board_millis();
    while ((board_millis() - start) < ms) {
        tud_task();
        sleep_ms(1);
    }
}

static void wait_hid_ready(uint8_t instance) {
    while (!tud_hid_n_ready(instance)) {
        tud_task();
        sleep_ms(1);
    }
}

static void send_touch(bool touching, uint16_t x, uint16_t y) {
    uint8_t report[6] = {
        touching ? 0x01 : 0x00,
        touching ? 0x01 : 0x00,
        (uint8_t)(x & 0xFF),
        (uint8_t)(x >> 8),
        (uint8_t)(y & 0xFF),
        (uint8_t)(y >> 8),
    };

    wait_hid_ready(HID_ITF_TOUCH);
    tud_hid_n_report(HID_ITF_TOUCH, 0, report, sizeof(report));
}

static void tap(uint16_t x, uint16_t y, uint16_t hold_ms) {
    send_touch(true, x, y);
    wait_ms_with_usb(hold_ms);
    send_touch(false, x, y);
}

static void swipe(uint16_t x1, uint16_t y1, uint16_t x2, uint16_t y2, uint16_t duration_ms, uint8_t steps) {
    if (steps == 0) {
        steps = 1;
    }

    send_touch(true, x1, y1);

    for (uint8_t i = 1; i <= steps; i++) {
        uint16_t x = x1 + ((int32_t)(x2 - x1) * i) / steps;
        uint16_t y = y1 + ((int32_t)(y2 - y1) * i) / steps;
        send_touch(true, x, y);
        wait_ms_with_usb(duration_ms / steps);
    }

    send_touch(false, x2, y2);
}

static bool arm_pin_is_active(void) {
    return !gpio_get(ARM_PIN);
}

static void run_macro_once(void) {
    for (uint32_t i = 0; i < sizeof(macro_steps) / sizeof(macro_steps[0]); i++) {
        macro_step_t const *step = &macro_steps[i];

        switch (step->type) {
            case STEP_WAIT:
                wait_ms_with_usb(step->duration_ms);
                break;
            case STEP_TAP:
                tap(step->x1, step->y1, step->hold_ms);
                wait_ms_with_usb(step->after_ms);
                break;
            case STEP_SWIPE:
                swipe(step->x1, step->y1, step->x2, step->y2, step->duration_ms, step->steps);
                wait_ms_with_usb(step->after_ms);
                break;
        }
    }

    send_touch(false, TOUCH_MAX / 2, TOUCH_MAX / 2);
}

int main(void) {
    board_init();

    gpio_init(ARM_PIN);
    gpio_set_dir(ARM_PIN, GPIO_IN);
    gpio_pull_up(ARM_PIN);

    tusb_init();

    bool macro_ran = false;

    while (true) {
        tud_task();

        if (!macro_ran && tud_mounted()) {
            if (arm_pin_is_active()) {
                run_macro_once();
                macro_ran = true;
            } else {
                board_led_write(board_millis() % 1000 < 100);
            }
        }

        sleep_ms(1);
    }
}

uint16_t tud_hid_get_report_cb(
    uint8_t instance,
    uint8_t report_id,
    hid_report_type_t report_type,
    uint8_t *buffer,
    uint16_t reqlen
) {
    (void)instance;
    (void)report_id;
    (void)report_type;
    (void)buffer;
    (void)reqlen;
    return 0;
}

void tud_hid_set_report_cb(
    uint8_t instance,
    uint8_t report_id,
    hid_report_type_t report_type,
    uint8_t const *buffer,
    uint16_t bufsize
) {
    (void)instance;
    (void)report_id;
    (void)report_type;
    (void)buffer;
    (void)bufsize;
}

