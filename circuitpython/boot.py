import supervisor
import usb_hid

# Optional production mode:
# Uncomment these after testing if you want the Pico to stop exposing the
# CIRCUITPY drive and serial console. Keep a recovery path before doing this.
# import storage
# import usb_cdc
# storage.disable_usb_drive()
# usb_cdc.disable()

DEVICE_NAME = "C86EC0"
VID = 0x4348
PID = 0x5537

TOUCH_REPORT_DESCRIPTOR = bytes((
    0x05, 0x0D,        # Usage Page (Digitizers)
    0x09, 0x04,        # Usage (Touch Screen)
    0xA1, 0x01,        # Collection (Application)
    0x05, 0x0D,        #   Usage Page (Digitizers)
    0x09, 0x22,        #   Usage (Finger)
    0xA1, 0x02,        #   Collection (Logical)
    0x05, 0x0D,        #     Usage Page (Digitizers)
    0x09, 0x42,        #     Usage (Tip Switch)
    0x15, 0x00,        #     Logical Minimum (0)
    0x25, 0x01,        #     Logical Maximum (1)
    0x75, 0x01,        #     Report Size (1)
    0x95, 0x01,        #     Report Count (1)
    0x81, 0x02,        #     Input (Data,Var,Abs)
    0x75, 0x07,        #     Report Size (7)
    0x95, 0x01,        #     Report Count (1)
    0x81, 0x01,        #     Input (Const,Array,Abs)
    0x09, 0x51,        #     Usage (In Range)
    0x75, 0x08,        #     Report Size (8)
    0x95, 0x01,        #     Report Count (1)
    0x81, 0x02,        #     Input (Data,Var,Abs)
    0x05, 0x01,        #     Usage Page (Generic Desktop)
    0x09, 0x30,        #     Usage (X)
    0x15, 0x00,        #     Logical Minimum (0)
    0x26, 0xFF, 0x0F,  #     Logical Maximum (4095)
    0x75, 0x10,        #     Report Size (16)
    0x95, 0x01,        #     Report Count (1)
    0x81, 0x02,        #     Input (Data,Var,Abs)
    0x09, 0x31,        #     Usage (Y)
    0x15, 0x00,        #     Logical Minimum (0)
    0x26, 0xFF, 0x0F,  #     Logical Maximum (4095)
    0x75, 0x10,        #     Report Size (16)
    0x95, 0x01,        #     Report Count (1)
    0x81, 0x02,        #     Input (Data,Var,Abs)
    0xC0,              #   End Collection
    0xC0,              # End Collection
))

touch = usb_hid.Device(
    report_descriptor=TOUCH_REPORT_DESCRIPTOR,
    usage_page=0x0D,
    usage=0x04,
    report_ids=(0,),
    in_report_lengths=(6,),
    out_report_lengths=(0,),
)

supervisor.set_usb_identification(
    manufacturer=DEVICE_NAME,
    product=DEVICE_NAME,
    vid=VID,
    pid=PID,
)

# Put the mouse before the digitizer. CircuitPython documents this as important
# for macOS with digitizer devices.
usb_hid.enable((usb_hid.Device.KEYBOARD, usb_hid.Device.MOUSE, touch))
