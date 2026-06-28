#ifndef USB_DESCRIPTORS_H
#define USB_DESCRIPTORS_H

#include <stdint.h>

enum {
    HID_ITF_KEYBOARD = 0,
    HID_ITF_MOUSE = 1,
    HID_ITF_TOUCH = 2,
};

enum {
    ITF_NUM_KEYBOARD = 0,
    ITF_NUM_MOUSE,
    ITF_NUM_TOUCH,
    ITF_NUM_TOTAL,
};

extern uint8_t const desc_hid_report_keyboard[];
extern uint8_t const desc_hid_report_mouse[];
extern uint8_t const desc_hid_report_touch[];

extern uint16_t const desc_hid_report_keyboard_len;
extern uint16_t const desc_hid_report_mouse_len;
extern uint16_t const desc_hid_report_touch_len;

#endif

