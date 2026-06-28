import struct
import time

import usb_hid

DIGITIZER_USAGE_PAGE = 0x0D
TOUCH_SCREEN_USAGE = 0x04
MAX_COORD = 4095


def _clip(value, lower=0, upper=MAX_COORD):
    value = int(value)
    if value < lower:
        return lower
    if value > upper:
        return upper
    return value


def _find_touch_device():
    for device in usb_hid.devices:
        if (
            getattr(device, "usage_page", None) == DIGITIZER_USAGE_PAGE
            and getattr(device, "usage", None) == TOUCH_SCREEN_USAGE
        ):
            return device
    raise RuntimeError("Touch HID device not found. Check boot.py and reboot.")


class TouchScreen:
    def __init__(self):
        self.device = _find_touch_device()
        self.last_x = MAX_COORD // 2
        self.last_y = MAX_COORD // 2

    def _send(self, touching, x=None, y=None):
        if x is None:
            x = self.last_x
        if y is None:
            y = self.last_y

        x = _clip(x)
        y = _clip(y)
        self.last_x = x
        self.last_y = y

        flags = 0x01 if touching else 0x00
        in_range = 0x01 if touching else 0x00
        self.device.send_report(struct.pack("<BBHH", flags, in_range, x, y))

    def down(self, x, y):
        self._send(True, x, y)

    def up(self, x=None, y=None):
        self._send(False, x, y)

    def tap(self, x, y, hold=0.08):
        self.down(x, y)
        time.sleep(hold)
        self.up(x, y)

    def swipe(self, x1, y1, x2, y2, duration=0.45, steps=24):
        steps = max(1, int(steps))
        delay = max(0.0, float(duration)) / steps
        self.down(x1, y1)
        for index in range(1, steps + 1):
            ratio = index / steps
            x = x1 + (x2 - x1) * ratio
            y = y1 + (y2 - y1) * ratio
            self.down(x, y)
            time.sleep(delay)
        self.up(x2, y2)

    def tap_pct(self, x, y, hold=0.08):
        self.tap(_pct(x), _pct(y), hold)

    def swipe_pct(self, x1, y1, x2, y2, duration=0.45, steps=24):
        self.swipe(_pct(x1), _pct(y1), _pct(x2), _pct(y2), duration, steps)

    def release(self):
        self.up()


def _pct(value):
    return _clip((float(value) / 100.0) * MAX_COORD)

