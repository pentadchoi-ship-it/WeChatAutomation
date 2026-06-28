import json
import time

import board
import digitalio

from mouse_hid import Mouse
from touch_hid import TouchScreen

CONFIG_PATH = "/macro.json"


def make_led():
    led_pin = getattr(board, "LED", getattr(board, "GP25", None))
    if led_pin is None:
        return None

    led = digitalio.DigitalInOut(led_pin)
    led.switch_to_output(value=False)
    return led


def blink(led, count, duration=0.12):
    if led is None:
        return

    for _ in range(count):
        led.value = True
        time.sleep(duration)
        led.value = False
        time.sleep(duration)


def load_config():
    try:
        with open(CONFIG_PATH, "r") as fp:
            return json.load(fp)
    except OSError:
        return {
            "enabled": False,
            "require_arm_pin": True,
        "arm_pin": "GP14",
        "stop_if_arm_released": True,
        "repeat": 1,
        "startup_delay": 2.0,
        "steps": [],
        }


def arm_pin_active(pin_name):
    pin_object = getattr(board, pin_name, None)
    if pin_object is None:
        return False

    pin = digitalio.DigitalInOut(pin_object)
    pin.switch_to_input(pull=digitalio.Pull.UP)
    active = not pin.value
    pin.deinit()
    return active


def mouse_settings(config, step):
    settings = config.get("mouse_units", {})
    return {
        "width": int(step.get("width", settings.get("width", 1800))),
        "height": int(step.get("height", settings.get("height", 3300))),
        "home_x": int(step.get("home_x", settings.get("home_x", 5000))),
        "home_y": int(step.get("home_y", settings.get("home_y", 5000))),
        "chunk": int(step.get("chunk", settings.get("chunk", 80))),
        "delay": float(step.get("delay", settings.get("delay", 0.01))),
    }


def pct_to_units(value, total):
    value = max(0.0, min(100.0, float(value)))
    return round((value / 100.0) * int(total))


def mouse_home(devices, settings):
    devices.mouse.home(settings["home_x"], settings["home_y"], settings["delay"], settings["chunk"])


def mouse_goto_pct(devices, settings, x, y):
    mouse_home(devices, settings)
    devices.mouse.move_relative(
        pct_to_units(x, settings["width"]),
        pct_to_units(y, settings["height"]),
        settings["delay"],
        settings["chunk"],
    )


class Devices:
    def __init__(self):
        self._touch = None
        self._mouse = None

    @property
    def touch(self):
        if self._touch is None:
            self._touch = TouchScreen()
        return self._touch

    @property
    def mouse(self):
        if self._mouse is None:
            self._mouse = Mouse()
        return self._mouse

    def release(self):
        if self._touch is not None:
            self._touch.release()
        if self._mouse is not None:
            self._mouse.up()


def run_step(devices, config, step):
    op = step.get("op", "wait")
    settings = mouse_settings(config, step)

    if op == "wait":
        time.sleep(float(step.get("seconds", 0.0)))
    elif op == "tap":
        devices.touch.tap(step["x"], step["y"], float(step.get("hold", 0.08)))
    elif op == "tap_pct":
        devices.touch.tap_pct(step["x"], step["y"], float(step.get("hold", 0.08)))
    elif op == "swipe":
        devices.touch.swipe(
            step["x1"],
            step["y1"],
            step["x2"],
            step["y2"],
            float(step.get("duration", 0.45)),
            int(step.get("steps", 24)),
        )
    elif op == "swipe_pct":
        devices.touch.swipe_pct(
            step["x1"],
            step["y1"],
            step["x2"],
            step["y2"],
            float(step.get("duration", 0.45)),
            int(step.get("steps", 24)),
        )
    elif op == "mouse_move":
        devices.mouse.move(step.get("x", 0), step.get("y", 0), step.get("wheel", 0))
    elif op == "mouse_click":
        devices.mouse.click(float(step.get("hold", 0.06)))
    elif op == "mouse_down":
        devices.mouse.down()
    elif op == "mouse_up":
        devices.mouse.up()
    elif op == "mouse_wiggle":
        devices.mouse.wiggle(int(step.get("distance", 80)), float(step.get("pause", 0.08)))
    elif op == "mouse_home":
        mouse_home(devices, settings)
    elif op == "mouse_goto_pct":
        mouse_goto_pct(devices, settings, step["x"], step["y"])
    elif op == "mouse_tap_pct":
        mouse_goto_pct(devices, settings, step["x"], step["y"])
        devices.mouse.click(float(step.get("hold", 0.06)))
    elif op == "mouse_hold_pct":
        mouse_goto_pct(devices, settings, step["x"], step["y"])
        devices.mouse.down()
        time.sleep(float(step.get("hold", 4.0)))
        devices.mouse.up()
    elif op == "mouse_drag_pct":
        mouse_goto_pct(devices, settings, step["x1"], step["y1"])
        dx = pct_to_units(step["x2"], settings["width"]) - pct_to_units(step["x1"], settings["width"])
        dy = pct_to_units(step["y2"], settings["height"]) - pct_to_units(step["y1"], settings["height"])
        devices.mouse.drag_relative(dx, dy, float(step.get("duration", 0.45)), int(step.get("steps", 24)))
    else:
        print("Unknown macro op:", op)

    if "after" in step:
        time.sleep(float(step["after"]))


def main():
    led = make_led()
    blink(led, 1)

    config = load_config()
    if not config.get("enabled", False):
        print("Macro disabled in macro.json.")
        blink(led, 2)
        return

    if config.get("require_arm_pin", True):
        pin_name = config.get("arm_pin", "GP14")
        if not arm_pin_active(pin_name):
            print("Arm pin", pin_name, "is not active. Connect it to GND to run.")
            blink(led, 3)
            return

    time.sleep(float(config.get("startup_delay", 2.0)))
    devices = Devices()
    blink(led, 4)

    try:
        repeat = int(config.get("repeat", 1))
        steps = config.get("steps", [])
        stop_if_released = config.get("stop_if_arm_released", True)
        pin_name = config.get("arm_pin", "GP14")
        for _ in range(max(1, repeat)):
            for step in steps:
                if stop_if_released and not arm_pin_active(pin_name):
                    print("Arm pin released. Stopping macro.")
                    return
                run_step(devices, config, step)
    finally:
        devices.release()
        blink(led, 5)


main()
