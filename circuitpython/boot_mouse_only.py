import board
import digitalio
import storage
import supervisor
import usb_cdc
import usb_hid

DEVICE_NAME = "C86EC0"
VID = 0x4348
PID = 0x5537
RECOVERY_PIN = "GP15"


def pin_grounded(pin_name):
    pin_object = getattr(board, pin_name, None)
    if pin_object is None:
        return False

    pin = digitalio.DigitalInOut(pin_object)
    pin.switch_to_input(pull=digitalio.Pull.UP)
    active = not pin.value
    pin.deinit()
    return active


supervisor.set_usb_identification(
    manufacturer=DEVICE_NAME,
    product=DEVICE_NAME,
    vid=VID,
    pid=PID,
)

if not pin_grounded(RECOVERY_PIN):
    storage.disable_usb_drive()
    usb_cdc.disable()

usb_hid.enable((usb_hid.Device.KEYBOARD, usb_hid.Device.MOUSE))

