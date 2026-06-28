import time

import usb_hid

GENERIC_DESKTOP_USAGE_PAGE = 0x01
MOUSE_USAGE = 0x02
LEFT_BUTTON = 0x01
DEFAULT_DELAY = 0.01
DEFAULT_CHUNK = 80


def _clip_delta(value):
    value = int(value)
    if value < -127:
        return -127
    if value > 127:
        return 127
    return value


def _find_mouse_device():
    for device in usb_hid.devices:
        if (
            getattr(device, "usage_page", None) == GENERIC_DESKTOP_USAGE_PAGE
            and getattr(device, "usage", None) == MOUSE_USAGE
        ):
            return device
    raise RuntimeError("Mouse HID device not found. Check boot.py and reboot.")


class Mouse:
    def __init__(self):
        self.device = _find_mouse_device()
        self.buttons = 0

    def _send(self, buttons=0, x=0, y=0, wheel=0):
        self.buttons = buttons & 0xFF
        self.device.send_report(
            bytes((
                self.buttons,
                _clip_delta(x) & 0xFF,
                _clip_delta(y) & 0xFF,
                _clip_delta(wheel) & 0xFF,
            ))
        )

    def move(self, x=0, y=0, wheel=0):
        self._send(self.buttons, x, y, wheel)

    def click(self, hold=0.06):
        self._send(LEFT_BUTTON, 0, 0, 0)
        time.sleep(hold)
        self._send(0, 0, 0, 0)

    def down(self):
        self._send(self.buttons | LEFT_BUTTON, 0, 0, 0)

    def up(self):
        self._send(self.buttons & ~LEFT_BUTTON, 0, 0, 0)

    def wiggle(self, distance=80, pause=0.08):
        self.move(distance, 0)
        time.sleep(pause)
        self.move(0, distance)
        time.sleep(pause)
        self.move(-distance, 0)
        time.sleep(pause)
        self.move(0, -distance)
        time.sleep(pause)

    def move_relative(self, x=0, y=0, delay=DEFAULT_DELAY, chunk=DEFAULT_CHUNK):
        x = int(x)
        y = int(y)
        chunk = max(1, min(127, int(chunk)))

        while x or y:
            dx = _take_step(x, chunk)
            dy = _take_step(y, chunk)
            self.move(dx, dy)
            x -= dx
            y -= dy
            time.sleep(delay)

    def home(self, overshoot_x=5000, overshoot_y=5000, delay=DEFAULT_DELAY, chunk=DEFAULT_CHUNK):
        self.move_relative(-abs(int(overshoot_x)), -abs(int(overshoot_y)), delay, chunk)

    def drag_relative(self, x=0, y=0, duration=0.45, steps=24):
        steps = max(1, int(steps))
        delay = max(0.0, float(duration)) / steps
        remaining_x = int(x)
        remaining_y = int(y)

        self.down()
        for index in range(steps, 0, -1):
            dx = round(remaining_x / index)
            dy = round(remaining_y / index)
            self.move(dx, dy)
            remaining_x -= dx
            remaining_y -= dy
            time.sleep(delay)
        self.up()


def _take_step(value, chunk):
    if value > chunk:
        return chunk
    if value < -chunk:
        return -chunk
    return value
