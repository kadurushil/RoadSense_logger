import os
import re
import sys

def parse_sensors(filepath):
    print(f"Reading sensors from {filepath}...")
    encodings = ['utf-8', 'utf-16le', 'utf-16', 'latin-1']
    content = ""
    for enc in encodings:
        try:
            with open(filepath, 'r', encoding=enc, errors='ignore') as f:
                content = f.read()
            if "Sensor List:" in content or "LSM6DSL" in content:
                print(f"Successfully decoded with {enc}")
                break
        except Exception as e:
            continue

    sensors = []
    # Find sensor list section
    # Pattern: 0x...) Name | Vendor | ver: ... | type: ... | perm: ... | flags: ...
    # Following lines have details like continuous | minRate=... | maxRate=...
    lines = content.splitlines()
    in_sensor_list = False
    current_sensor = None

    for i, line in enumerate(lines):
        line_str = line.strip()
        if "Sensor List:" in line_str:
            in_sensor_list = True
            continue
        if in_sensor_list and ("Fusion States:" in line_str or "Recent Sensor events:" in line_str or "Active sensors:" in line_str):
            if current_sensor:
                sensors.append(current_sensor)
                current_sensor = None
            in_sensor_list = False

        if in_sensor_list:
            # Check for sensor header
            match = re.match(r'^(0x[0-9a-fA-F]+)\)\s+([^|]+)\|\s+([^|]+)\|\s+ver:\s*(\d+)\s*\|\s*type:\s*([^|]+)\|\s*perm:\s*([^|]+)\|\s*flags:\s*(0x[0-9a-fA-F]+)', line_str)
            if match:
                if current_sensor:
                    sensors.append(current_sensor)
                handle, name, vendor, ver, stype, perm, flags = match.groups()
                current_sensor = {
                    'handle': handle.strip(),
                    'name': name.strip(),
                    'vendor': vendor.strip(),
                    'version': ver.strip(),
                    'type': stype.strip(),
                    'permission': perm.strip(),
                    'flags': flags.strip(),
                    'details': []
                }
            elif current_sensor and line_str:
                current_sensor['details'].append(line_str)

    if current_sensor:
        sensors.append(current_sensor)

    return sensors

if __name__ == '__main__':
    sensor_dump_path = os.path.join(os.path.dirname(__file__), 'raw_sensorservice_dump.txt')
    sensors = parse_sensors(sensor_dump_path)
    print(f"Total sensors found: {len(sensors)}")
    for s in sensors:
        print(f"[{s['handle']}] {s['name']} ({s['vendor']}) - Type: {s['type']}")
        for d in s['details']:
            print(f"    {d}")
