"""Capture actual bridge UDP packets and report local transport diagnostics."""
import argparse
import json
from pathlib import Path
import socket
import statistics
import struct
import time

parser = argparse.ArgumentParser()
parser.add_argument('--host', default='127.0.0.1')
parser.add_argument('--seconds', type=float, default=6)
parser.add_argument('--output', default='artifacts')
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
magic = 0x31464341
frames, maps, rtts, times, sequences = [], [], [], [], []
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.connect((args.host, 9876))
    sock.settimeout(.1)
    deadline, hello_at = time.monotonic() + args.seconds, 0
    while time.monotonic() < deadline:
        if time.monotonic() >= hello_at:
            sock.send(struct.pack('<iiq', magic, 3, time.perf_counter_ns()))
            hello_at = time.monotonic() + .2
        try:
            data = sock.recv(1200)
        except (socket.timeout, ConnectionResetError):
            continue
        assert len(data) >= 8 and struct.unpack_from('<i', data)[0] == magic
        kind = struct.unpack_from('<i', data, 4)[0]
        if kind == 1:
            assert len(data) in (268,344,412,420,944,964,1028,1032), len(data)
            fields = struct.unpack_from('<8i3f7i17f', data)
            frames.append(data)
            times.append(time.monotonic())
            sequences.append(fields[2])
        elif kind == 2:
            assert len(data) >= 20
            count = struct.unpack_from('<i', data, 12)[0]
            assert 0 <= count <= 128 and len(data) == 20 + count * 8
            maps.append(data)
        elif kind == 4:
            assert len(data) == 16
            rtts.append((time.perf_counter_ns() - struct.unpack_from('<q', data, 8)[0]) / 1e6)
assert frames, 'No bridge telemetry received'
out.joinpath('telemetry.bin').write_bytes(frames[-1])
if maps:
    out.joinpath('track.bin').write_bytes(maps[-1])
fields = struct.unpack_from('<8i3f7i17f', frames[-1])
intervals = [(b-a)*1000 for a,b in zip(times, times[1:])]
result = {
    'frames': len(frames),
    'hz': round((len(frames)-1) / (times[-1]-times[0]), 2) if len(frames) > 1 else 0,
    'interval_median_ms': round(statistics.median(intervals), 2) if intervals else None,
    'RTT_median_ms': round(statistics.median(rtts), 2) if rtts else None,
    'status': fields[4], 'raw_gear': fields[5], 'rpm': fields[6], 'max_rpm': fields[7],
    'speed_kmh': round(fields[8], 2), 'fuel_litres': round(fields[9], 2),
    'current_ms': fields[11], 'last_ms': fields[12], 'best_ms': fields[13],
    'track': frames[-1][140:204].split(b'\0')[0].decode('utf-8'),
    'car': frames[-1][204:268].split(b'\0')[0].decode('utf-8'),
    'map_points': struct.unpack_from('<i', maps[-1], 12)[0] if maps else 0,
    'sequence_gaps': sum(max(0,b-a-1) for a,b in zip(sequences, sequences[1:])),
}
values = [struct.unpack_from('<8i3f7i17f', frame) for frame in frames]
result['raw_gears_seen'] = sorted({v[5] for v in values})
result['rpm_range'] = [min(v[6] for v in values), max(v[6] for v in values)]
result['max_speed_kmh'] = round(max(v[8] for v in values), 2)
result['completed_laps_range'] = [min(v[14] for v in values), max(v[14] for v in values)]
result['position_changed'] = any((v[19], v[20]) != (values[0][19], values[0][20]) for v in values)
if len(frames[-1]) >= 344:
    result['tyre_health_raw'] = list(struct.unpack_from('<4f',frames[-1],268))
    result['sector_index'], result['last_sector_ms'], result['sector_count'] = struct.unpack_from('<3i',frames[-1],284)
    result['progress'], result['drs_flags'], result['cue_count'] = struct.unpack_from('<f2i',frames[-1],296)
    result['cues'] = [struct.unpack_from('<iif',frames[-1],308+i*12) for i in range(result['cue_count'])]
if len(frames[-1]) >= 412:
    result['position'] = struct.unpack_from('<i',frames[-1],344)[0]
    result['driver'] = frames[-1][348:412].split(b'\0')[0].decode('utf-8')
if len(frames[-1]) >= 420:
    result['rpm_scale'], result['drs_distance_m'] = struct.unpack_from('<if',frames[-1],412)
if len(frames[-1]) >= 944:
    result['players'], result['pit_speed_limit'], result['race_context'] = struct.unpack_from('<ifi',frames[-1],420)
    result['personal_sectors'] = list(struct.unpack_from('<64i',frames[-1],432))[:result['sector_count']]
    result['world_sectors'] = list(struct.unpack_from('<64i',frames[-1],688))[:result['sector_count']]
if len(frames[-1]) >= 964:
    result['road_temp'], result['grip'], result['wetness'], result['water'], result['fuel_per_lap'] = struct.unpack_from('<5f',frames[-1],944)
if len(frames[-1]) >= 1028:
    result['compound'] = frames[-1][964:1028].split(b'\0')[0].decode('utf-8')
if len(frames[-1]) == 1032:
    result['track_length_m'] = struct.unpack_from('<f',frames[-1],1028)[0]
out.joinpath('probe.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
