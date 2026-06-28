#!/usr/bin/env python3
import argparse

HID_MAX = 4095


def px_to_hid(value, size):
    if size <= 1:
        raise ValueError("screen size must be greater than 1")
    value = max(0, min(size - 1, value))
    return round(value * HID_MAX / (size - 1))


def hid_to_px(value, size):
    value = max(0, min(HID_MAX, value))
    return round(value * (size - 1) / HID_MAX)


def pct_to_hid(value):
    value = max(0.0, min(100.0, value))
    return round(value * HID_MAX / 100.0)


def main():
    parser = argparse.ArgumentParser(
        description="Convert Huawei P30 portrait pixel or percent coordinates to 0..4095 HID coordinates."
    )
    parser.add_argument("x", type=float, help="x coordinate, pixels by default or percent with --percent")
    parser.add_argument("y", type=float, help="y coordinate, pixels by default or percent with --percent")
    parser.add_argument("--width", type=int, default=1080, help="screen width in portrait pixels")
    parser.add_argument("--height", type=int, default=2340, help="screen height in portrait pixels")
    parser.add_argument("--percent", action="store_true", help="treat x/y as percentages")
    parser.add_argument("--reverse", action="store_true", help="convert HID coordinates back to pixels")
    args = parser.parse_args()

    if args.percent:
        hid_x = pct_to_hid(args.x)
        hid_y = pct_to_hid(args.y)
        print(f"percent=({args.x:g},{args.y:g}) hid=({hid_x},{hid_y})")
    elif args.reverse:
        px_x = hid_to_px(round(args.x), args.width)
        px_y = hid_to_px(round(args.y), args.height)
        print(f"hid=({round(args.x)},{round(args.y)}) px=({px_x},{px_y})")
    else:
        hid_x = px_to_hid(round(args.x), args.width)
        hid_y = px_to_hid(round(args.y), args.height)
        print(f"px=({round(args.x)},{round(args.y)}) hid=({hid_x},{hid_y})")


if __name__ == "__main__":
    main()

