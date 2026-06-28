#include <string.h>

#include "tusb.h"
#include "usb_descriptors.h"

#define USB_VID 0x4348
#define USB_PID 0x5537
#define USB_BCD 0x0400

#define EPNUM_KEYBOARD 0x81
#define EPNUM_MOUSE 0x82
#define EPNUM_TOUCH 0x83

uint8_t const desc_hid_report_keyboard[] = {
    TUD_HID_REPORT_DESC_KEYBOARD()
};

uint8_t const desc_hid_report_mouse[] = {
    TUD_HID_REPORT_DESC_MOUSE()
};

uint8_t const desc_hid_report_touch[] = {
    0x05, 0x0D,        // Usage Page (Digitizers)
    0x09, 0x04,        // Usage (Touch Screen)
    0xA1, 0x01,        // Collection (Application)
    0x05, 0x0D,        //   Usage Page (Digitizers)
    0x09, 0x22,        //   Usage (Finger)
    0xA1, 0x02,        //   Collection (Logical)
    0x05, 0x0D,        //     Usage Page (Digitizers)
    0x09, 0x42,        //     Usage (Tip Switch)
    0x15, 0x00,        //     Logical Minimum (0)
    0x25, 0x01,        //     Logical Maximum (1)
    0x75, 0x01,        //     Report Size (1)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x75, 0x07,        //     Report Size (7)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x01,        //     Input (Const,Array,Abs)
    0x09, 0x51,        //     Usage (In Range)
    0x75, 0x08,        //     Report Size (8)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x05, 0x01,        //     Usage Page (Generic Desktop)
    0x09, 0x30,        //     Usage (X)
    0x15, 0x00,        //     Logical Minimum (0)
    0x26, 0xFF, 0x0F,  //     Logical Maximum (4095)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0x09, 0x31,        //     Usage (Y)
    0x15, 0x00,        //     Logical Minimum (0)
    0x26, 0xFF, 0x0F,  //     Logical Maximum (4095)
    0x75, 0x10,        //     Report Size (16)
    0x95, 0x01,        //     Report Count (1)
    0x81, 0x02,        //     Input (Data,Var,Abs)
    0xC0,              //   End Collection
    0xC0,              // End Collection
};

uint16_t const desc_hid_report_keyboard_len = sizeof(desc_hid_report_keyboard);
uint16_t const desc_hid_report_mouse_len = sizeof(desc_hid_report_mouse);
uint16_t const desc_hid_report_touch_len = sizeof(desc_hid_report_touch);

tusb_desc_device_t const desc_device = {
    .bLength = sizeof(tusb_desc_device_t),
    .bDescriptorType = TUSB_DESC_DEVICE,
    .bcdUSB = 0x0110,
    .bDeviceClass = 0x00,
    .bDeviceSubClass = 0x00,
    .bDeviceProtocol = 0x00,
    .bMaxPacketSize0 = CFG_TUD_ENDPOINT0_SIZE,
    .idVendor = USB_VID,
    .idProduct = USB_PID,
    .bcdDevice = USB_BCD,
    .iManufacturer = 0x01,
    .iProduct = 0x02,
    .iSerialNumber = 0x00,
    .bNumConfigurations = 0x01,
};

uint8_t const *tud_descriptor_device_cb(void) {
    return (uint8_t const *)&desc_device;
}

#define CONFIG_TOTAL_LEN (TUD_CONFIG_DESC_LEN + TUD_HID_DESC_LEN * 3)

uint8_t const desc_configuration[] = {
    TUD_CONFIG_DESCRIPTOR(
        1,
        ITF_NUM_TOTAL,
        0,
        CONFIG_TOTAL_LEN,
        TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP,
        100
    ),

    TUD_HID_DESCRIPTOR(
        ITF_NUM_KEYBOARD,
        0,
        HID_ITF_PROTOCOL_KEYBOARD,
        sizeof(desc_hid_report_keyboard),
        EPNUM_KEYBOARD,
        8,
        10
    ),

    TUD_HID_DESCRIPTOR(
        ITF_NUM_MOUSE,
        0,
        HID_ITF_PROTOCOL_MOUSE,
        sizeof(desc_hid_report_mouse),
        EPNUM_MOUSE,
        8,
        10
    ),

    TUD_HID_DESCRIPTOR(
        ITF_NUM_TOUCH,
        0,
        HID_ITF_PROTOCOL_NONE,
        sizeof(desc_hid_report_touch),
        EPNUM_TOUCH,
        8,
        10
    ),
};

uint8_t const *tud_descriptor_configuration_cb(uint8_t index) {
    (void)index;
    return desc_configuration;
}

uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance) {
    switch (instance) {
        case HID_ITF_KEYBOARD:
            return desc_hid_report_keyboard;
        case HID_ITF_MOUSE:
            return desc_hid_report_mouse;
        case HID_ITF_TOUCH:
            return desc_hid_report_touch;
        default:
            return NULL;
    }
}

char const *string_desc_arr[] = {
    (const char[]){0x09, 0x04},
    "C86EC0",
    "C86EC0",
};

static uint16_t desc_str[32];

uint16_t const *tud_descriptor_string_cb(uint8_t index, uint16_t langid) {
    (void)langid;

    uint8_t chr_count;

    if (index == 0) {
        memcpy(&desc_str[1], string_desc_arr[0], 2);
        chr_count = 1;
    } else {
        if (index >= sizeof(string_desc_arr) / sizeof(string_desc_arr[0])) {
            return NULL;
        }

        char const *str = string_desc_arr[index];
        chr_count = strlen(str);
        if (chr_count > 31) {
            chr_count = 31;
        }

        for (uint8_t i = 0; i < chr_count; i++) {
            desc_str[1 + i] = str[i];
        }
    }

    desc_str[0] = (uint16_t)((TUSB_DESC_STRING << 8) | (2 * chr_count + 2));
    return desc_str;
}
