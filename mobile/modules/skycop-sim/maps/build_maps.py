#!/usr/bin/env python3
"""Builds the simulator's Dhaka maps from OpenStreetMap data.

    python3 build_maps.py            # downloads both areas from Overpass, then builds
    python3 build_maps.py --offline  # rebuilds from the JSON already in ./osm

Each map is a 2 x 2 km square around a real place. The output, ../assets/maps/<name>.skymap, holds building
footprints with estimated heights, parks and water, the drivable road network (split into edges
between junctions, clipped at the map edge), charging docks at junctions, and a few landmarks.
Coordinates are meters from the map center: x east, z south, in units of 5 cm.

Map data (c) OpenStreetMap contributors, available under the Open Database License (ODbL).
"""
import hashlib
import json
import math
import os
import struct
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HALF = 1000.0
UNIT = 0.05

MAPS = {
    # NSU, Jamuna Future Park and the blocks of Bashundhara Residential Area.
    'bashundhara': dict(title='Bashundhara R/A', lat=23.8175, lon=90.4300, home=['NAC (North Academic Building)', 'North South University']),
    # Town Hall, Asad Gate, Saat Gambuj mosque and Japan Garden City.
    'mohammadpur': dict(title='Mohammadpur', lat=23.7615, lon=90.3640, home=['Saat Gambuj Jame Masjid']),
}

OVERPASS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter']

ROAD_CLASS = {
    'motorway': 0, 'motorway_link': 0, 'trunk': 1, 'trunk_link': 1, 'primary': 2, 'primary_link': 2,
    'secondary': 3, 'secondary_link': 3, 'tertiary': 4, 'tertiary_link': 4, 'unclassified': 5,
    'residential': 6, 'living_street': 7, 'service': 8,
}
# Lanes in each direction when OSM does not say.
DEFAULT_LANES = {0: 3, 1: 3, 2: 2, 3: 2, 4: 1, 5: 1, 6: 1, 7: 1, 8: 1}
LANE_WIDTH = {0: 3.5, 1: 3.4, 2: 3.3, 3: 3.2, 4: 3.1, 5: 3.0, 6: 2.9, 7: 2.7, 8: 2.6}

# Building kinds, shared with City.kt / City.swift.
APARTMENT, HOUSE, SHED, COMMERCIAL, TOWER, MALL, EDUCATION, HOSPITAL, MOSQUE, INDUSTRIAL, CONSTRUCTION = range(11)
# Area kinds.
GRASS, WATER, PITCH, PAVED, GRAVEYARD, BARE = range(6)
# Ribbon kinds (lines with a width that are not part of the traffic network).
FOOTPATH, CANAL, MINOR_ROAD = range(3)
# Landmark kinds.
SEVEN_DOMES, MALL_SIGN, CAMPUS, HOTEL = range(4)


def query(lat, lon):
    s, n = lat - HALF / 110574 * 1.08, lat + HALF / 110574 * 1.08
    k = 111320 * math.cos(math.radians(lat))
    w, e = lon - HALF / k * 1.08, lon + HALF / k * 1.08
    box = f'{s:.5f},{w:.5f},{n:.5f},{e:.5f}'
    return f"""[out:json][timeout:240];
(
  way["highway"]({box}); way["building"]({box}); relation["building"]({box});
  way["landuse"]({box}); way["leisure"]({box}); way["natural"]({box}); relation["natural"="water"]({box});
  way["waterway"]({box}); way["amenity"]({box}); node["highway"="traffic_signals"]({box});
);
out body; >; out skel qt;"""


def download(name, spec):
    os.makedirs(os.path.join(HERE, 'osm'), exist_ok=True)
    body = urllib.parse.urlencode({'data': query(spec['lat'], spec['lon'])}).encode()
    for url in OVERPASS:
        try:
            request = urllib.request.Request(url, body, {'User-Agent': 'SkyCop-sim-map-builder'})
            data = urllib.request.urlopen(request, timeout=400).read()
            json.loads(data)
            open(os.path.join(HERE, 'osm', name + '.json'), 'wb').write(data)
            return
        except Exception as error:  # noqa: BLE001 - try the next mirror
            print(f'  {url}: {error}')
    raise SystemExit(f'Could not download {name}')


def rng(seed, salt=''):
    """A stable pseudo-random number in [0, 1) per OSM id, so rebuilding gives the same city."""
    digest = hashlib.sha1(f'{seed}/{salt}'.encode()).digest()
    return int.from_bytes(digest[:4], 'little') / 2 ** 32


def area(points):
    return 0.5 * sum(points[i][0] * points[i - 1][1] - points[i - 1][0] * points[i][1] for i in range(len(points)))


def simplify(points, tolerance, closed):
    if len(points) < 4:
        return points
    def rdp(pts):
        if len(pts) < 3:
            return pts
        (ax, az), (bx, bz) = pts[0], pts[-1]
        dx, dz = bx - ax, bz - az
        length = math.hypot(dx, dz) or 1e-9
        best, index = 0, 0
        for i in range(1, len(pts) - 1):
            d = abs((pts[i][0] - ax) * dz - (pts[i][1] - az) * dx) / length
            if d > best:
                best, index = d, i
        if best <= tolerance:
            return [pts[0], pts[-1]]
        return rdp(pts[:index + 1])[:-1] + rdp(pts[index:])
    if closed:
        far = max(range(len(points)), key=lambda i: (points[i][0] - points[0][0]) ** 2 + (points[i][1] - points[0][1]) ** 2)
        out = rdp(points[:far + 1])[:-1] + rdp(points[far:] + [points[0]])[:-1]
        return out if len(out) >= 3 else points
    return rdp(points)


def oriented_box(points):
    """Smallest rotated rectangle around a footprint: (cx, cz, angle, half x, half z). Drone
    collisions use it; most Dhaka plots are close to rectangles."""
    pts = sorted(set(points))
    def turn(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and turn(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and turn(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    best = None
    for i in range(len(hull)):
        (ax, az), (bx, bz) = hull[i - 1], hull[i]
        angle = math.atan2(bz - az, bx - ax)
        c, s = math.cos(angle), math.sin(angle)
        us = [x * c + z * s for x, z in hull]
        vs = [-x * s + z * c for x, z in hull]
        size = (max(us) - min(us)) * (max(vs) - min(vs))
        if best is None or size < best[0]:
            mu, mv = (max(us) + min(us)) / 2, (max(vs) + min(vs)) / 2
            best = (size, mu * c - mv * s, mu * s + mv * c, angle, (max(us) - min(us)) / 2, (max(vs) - min(vs)) / 2)
    return best[1:]


def clip_polyline(points, half):
    """Splits a polyline at the square's edge and keeps the parts inside. Returns (parts, cut flags)."""
    def inside(p):
        return abs(p[0]) <= half and abs(p[1]) <= half
    def cross(a, b):
        t_best = None
        for axis in (0, 1):
            for bound in (-half, half):
                if (a[axis] - bound) * (b[axis] - bound) < 0:
                    t = (bound - a[axis]) / (b[axis] - a[axis])
                    q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                    if abs(q[0]) <= half + 1e-6 and abs(q[1]) <= half + 1e-6:
                        t_best = t if t_best is None else (min(t_best, t) if inside(a) else max(t_best, t))
        return t_best
    parts, current = [], []
    for i, p in enumerate(points):
        if inside(p):
            if not current and i > 0:
                t = cross(points[i - 1], p)
                if t is not None:
                    a = points[i - 1]
                    current.append(((a[0] + (p[0] - a[0]) * t, a[1] + (p[1] - a[1]) * t), 'cut'))
            current.append((p, None))
        elif current:
            t = cross(points[i - 1], p)
            if t is not None:
                a = points[i - 1]
                current.append(((a[0] + (p[0] - a[0]) * t, a[1] + (p[1] - a[1]) * t), 'cut'))
            parts.append(current)
            current = []
    if current:
        parts.append(current)
    return [part for part in parts if len(part) >= 2]


def build(name, spec):
    data = json.load(open(os.path.join(HERE, 'osm', name + '.json')))
    lat0, lon0 = spec['lat'], spec['lon']
    kx = 111320 * math.cos(math.radians(lat0))
    kz = 110574.0
    nodes, ways, relations = {}, {}, []
    signals = set()
    for e in data['elements']:
        if e['type'] == 'node':
            nodes[e['id']] = ((e['lon'] - lon0) * kx, -(e['lat'] - lat0) * kz)
            if e.get('tags', {}).get('highway') == 'traffic_signals':
                signals.add(e['id'])
        elif e['type'] == 'way':
            ways[e['id']] = e
        elif e['type'] == 'relation':
            relations.append(e)

    def coords(way):
        return [nodes[n] for n in way['nodes'] if n in nodes]

    def rings_of(relation):
        """Joins a multipolygon's outer member ways into closed rings."""
        pieces = [list(ways[m['ref']]['nodes']) for m in relation.get('members', [])
                  if m['type'] == 'way' and m.get('role') == 'outer' and m['ref'] in ways]
        rings = []
        while pieces:
            ring = pieces.pop()
            changed = True
            while ring[0] != ring[-1] and changed:
                changed = False
                for i, p in enumerate(pieces):
                    if p[0] == ring[-1]:
                        ring += p[1:]
                    elif p[-1] == ring[-1]:
                        ring += p[-2::-1]
                    else:
                        continue
                    pieces.pop(i)
                    changed = True
                    break
            if ring[0] == ring[-1] and len(ring) > 3:
                rings.append([nodes[n] for n in ring if n in nodes])
        return rings

    def polygon(points):
        if len(points) > 1 and points[0] == points[-1]:
            points = points[:-1]
        if len(points) < 3:
            return None
        points = simplify(points, 0.25, True)
        if len(points) < 3:
            return None
        if area(points) < 0:
            points = points[::-1]
        return points

    # ---- Buildings ---------------------------------------------------------------------------
    named_heights = {
        'যমুনা ফিউচার পার্ক': (MALL, 8, 52), 'Jamuna Future Park': (MALL, 8, 52),
        'জেডাব্লিউ ম্যারিয়ট': (TOWER, 18, 34), 'JW Marriott': (TOWER, 18, 34),
        'NAC (North Academic Building)': (EDUCATION, 9, 34), 'Japan Garden City': (APARTMENT, 14, 32),
    }
    road_near = []  # filled after roads; used for shopfronts
    buildings, landmarks = [], []
    sources = [(w['id'], w['tags'], coords(w)) for w in ways.values() if 'building' in w.get('tags', {})]
    for r in relations:
        if 'building' in r.get('tags', {}):
            for k, ring in enumerate(rings_of(r)):
                sources.append((f"r{r['id']}/{k}", r['tags'], ring))
    for oid, tags, pts in sources:
        poly = polygon(pts)
        if not poly:
            continue
        cx = sum(p[0] for p in poly) / len(poly); cz = sum(p[1] for p in poly) / len(poly)
        if abs(cx) > HALF - 8 or abs(cz) > HALF - 8 or max(abs(p[0]) for p in poly) > HALF or max(abs(p[1]) for p in poly) > HALF:
            continue
        footprint = area(poly)
        if footprint < 9:
            continue
        kind_tag = tags.get('building', 'yes')
        nm = tags.get('name', '') or ''
        r1, r2 = rng(oid, 'floors'), rng(oid, 'tint')
        floor_height = 3.2
        if kind_tag in ('roof', 'shed', 'hut', 'kiosk', 'cabin', 'shelter'):
            kind, floors = SHED, 1
        elif kind_tag in ('garage', 'garages', 'industrial', 'warehouse', 'service', 'transformer_tower', 'carport'):
            kind, floors = INDUSTRIAL, 1 + int(r1 * 2)
        elif kind_tag == 'construction':
            kind, floors = CONSTRUCTION, 3 + int(r1 * 6)
        elif kind_tag == 'mosque' or tags.get('amenity') == 'place_of_worship' and tags.get('religion', 'muslim') == 'muslim':
            kind, floors = MOSQUE, 2 + int(r1 * 2)
        elif kind_tag in ('school', 'university', 'college', 'kindergarten', 'dormitory'):
            kind, floors = EDUCATION, 4 + int(r1 * 5)
        elif kind_tag in ('hospital', 'clinic'):
            kind, floors = HOSPITAL, 6 + int(r1 * 6)
        elif kind_tag in ('commercial', 'office', 'retail', 'supermarket', 'hotel', 'bank'):
            kind = COMMERCIAL if footprint < 1800 else MALL
            floors = (5 + int(r1 * 9)) if kind == COMMERCIAL else (5 + int(r1 * 3))
            if kind == MALL:
                floor_height = 5.0
            if kind == COMMERCIAL and footprint > 500 and r2 < 0.45:
                kind = TOWER
        elif kind_tag == 'house':
            kind, floors = HOUSE, 2 + int(r1 * 3)
        else:  # residential, apartments, yes
            if footprint < 45:
                kind, floors = (SHED, 1) if r1 < 0.45 else (HOUSE, 2 + int(r1 * 2))
            elif footprint < 110:
                kind, floors = APARTMENT, 3 + int(r1 * 5)
            elif footprint < 450:
                kind, floors = APARTMENT, 6 + int(r1 * 5)
            else:
                kind, floors = APARTMENT, 7 + int(r1 * 6)
        levels = tags.get('building:levels')
        if levels:
            try:
                floors = max(1, min(60, int(float(levels.split(';')[0]))))
            except ValueError:
                pass
        if 'height' in tags:
            try:
                floors = max(1, round(float(tags['height'].replace('m', '').strip()) / floor_height))
            except ValueError:
                pass
        for key, (k2, f2, _) in named_heights.items():
            if key and key in nm:
                kind, floors = k2, f2
                if k2 == MALL:
                    floor_height = 5.5
        index = len(buildings)
        buildings.append(dict(kind=kind, floors=min(floors, 60), fh=floor_height, tint=int(r2 * 256), poly=poly, shops=0, cx=cx, cz=cz, area=footprint))
        if 'Saat Gambuj' in nm or 'সাত গম্বুজ' in nm:
            landmarks.append((SEVEN_DOMES, cx, cz, index, nm))
            buildings[-1]['kind'] = MOSQUE
            buildings[-1]['floors'] = 2
        elif 'ফিউচার পার্ক' in nm or 'Future Park' in nm:
            landmarks.append((MALL_SIGN, cx, cz, index, 'Jamuna Future Park'))
        elif 'ম্যারিয়ট' in nm or 'Marriott' in nm:
            landmarks.append((HOTEL, cx, cz, index, 'JW Marriott'))
        elif 'North Academic' in nm or 'North South University' in nm:
            landmarks.append((CAMPUS, cx, cz, index, 'North South University'))

    # ---- Areas -------------------------------------------------------------------------------
    areas = []
    def area_kind(tags):
        if tags.get('natural') == 'water' or tags.get('landuse') in ('reservoir', 'basin') or tags.get('waterway') == 'riverbank':
            return WATER
        if tags.get('leisure') in ('park', 'garden', 'playground', 'common') or tags.get('landuse') in ('grass', 'recreation_ground', 'village_green', 'meadow') or tags.get('natural') in ('grassland', 'scrub', 'wood'):
            return GRASS
        if tags.get('leisure') in ('pitch', 'stadium', 'track'):
            return PITCH
        if tags.get('amenity') in ('parking', 'marketplace', 'bus_station') or tags.get('highway') == 'pedestrian':
            return PAVED
        if tags.get('amenity') == 'grave_yard' or tags.get('landuse') == 'cemetery':
            return GRAVEYARD
        if tags.get('landuse') in ('construction', 'brownfield', 'greenfield'):
            return BARE
        return None
    for w in ways.values():
        tags = w.get('tags', {})
        if 'building' in tags or w['nodes'][0] != w['nodes'][-1]:
            continue
        kind = area_kind(tags)
        if kind is None:
            continue
        poly = polygon(coords(w))
        if poly and abs(area(poly)) > 20:
            poly = [(max(-HALF, min(HALF, x)), max(-HALF, min(HALF, z))) for x, z in poly]
            areas.append((kind, poly))
    for r in relations:
        kind = area_kind(r.get('tags', {}))
        if kind is not None:
            for ring in rings_of(r):
                poly = polygon(ring)
                if poly:
                    areas.append((kind, [(max(-HALF, min(HALF, x)), max(-HALF, min(HALF, z))) for x, z in poly]))
    # Big areas first, so smaller ones (a pond in a park) draw on top.
    areas.sort(key=lambda a: -abs(area(a[1])))

    # ---- Roads -------------------------------------------------------------------------------
    drivable, ribbons = [], []
    for w in ways.values():
        tags = w.get('tags', {})
        hw = tags.get('highway')
        if hw in ROAD_CLASS and not (hw == 'service' and tags.get('service') in ('parking_aisle', 'driveway', 'drive-through')) and tags.get('area') != 'yes':
            drivable.append(w)
        elif hw in ('footway', 'path', 'pedestrian', 'steps', 'cycleway', 'track') or (hw == 'service'):
            for part in clip_polyline(coords(w), HALF):
                pts = simplify([p for p, _ in part], 0.3, False)
                width = 5.0 if hw == 'service' else (6.0 if hw == 'pedestrian' else 2.4)
                ribbons.append((MINOR_ROAD if hw == 'service' else FOOTPATH, width, pts))
        if tags.get('waterway') in ('canal', 'river', 'drain', 'stream') and w['nodes'][0] != w['nodes'][-1]:
            width = {'river': 30.0, 'canal': 9.0, 'drain': 4.0, 'stream': 5.0}[tags['waterway']]
            try:
                width = float(tags.get('width', width))
            except ValueError:
                pass
            for part in clip_polyline(coords(w), HALF):
                ribbons.append((CANAL, min(width, 25.0), simplify([p for p, _ in part], 0.5, False)))

    # Graph nodes: way ends and nodes shared by more than one drivable way.
    use = {}
    for w in drivable:
        for i, n in enumerate(w['nodes']):
            use[n] = use.get(n, 0) + (2 if i in (0, len(w['nodes']) - 1) else 1)
    elevated = {}
    for w in drivable:
        tags = w['tags']
        bridge = tags.get('bridge') not in (None, 'no')
        layer = 0
        try:
            layer = int(tags.get('layer', '1' if bridge else '0'))
        except ValueError:
            layer = 1 if bridge else 0
        for n in w['nodes']:
            if not bridge or layer <= 0:
                elevated[n] = 0.0
            else:
                elevated.setdefault(n, 6.5 * layer)

    graph_nodes, node_index = [], {}
    def graph_node(key, x, z, elevation, flags):
        if key in node_index:
            return node_index[key]
        node_index[key] = len(graph_nodes)
        graph_nodes.append([x, z, elevation, flags])
        return node_index[key]

    edges = []
    for w in drivable:
        tags = w['tags']
        cls = ROAD_CLASS[tags['highway']]
        oneway = tags.get('oneway', 'yes' if tags.get('highway', '').startswith('motorway') or tags.get('junction') == 'roundabout' else 'no')
        ids = [n for n in w['nodes'] if n in nodes]
        if oneway == '-1':
            ids = ids[::-1]
        is_oneway = oneway in ('yes', '-1', 'true', '1')
        try:
            total_lanes = int(tags.get('lanes', '0').split(';')[0])
        except ValueError:
            total_lanes = 0
        if total_lanes > 0:
            forward = total_lanes if is_oneway else max(1, total_lanes // 2)
            backward = 0 if is_oneway else max(1, total_lanes - forward)
        else:
            forward = DEFAULT_LANES[cls]
            backward = 0 if is_oneway else DEFAULT_LANES[cls]
        if tags['highway'].endswith('_link'):
            forward = min(forward, 2); backward = min(backward, 1)
        width = (forward + backward) * LANE_WIDTH[cls] + (1.0 if cls <= 4 else 0.4)
        bridge = 1 if tags.get('bridge') not in (None, 'no') else 0
        points = [(nodes[n], n) for n in ids]
        for part in clip_polyline([p for p, _ in points], HALF - 2):
            # Map clipped points back to OSM ids where they coincide; cut points become portals.
            current, start_key = [], None
            for k, (p, flag) in enumerate(part):
                osm = next((n for q, n in points if q == p), None)
                elevation = elevated.get(osm, 0.0) if osm else 0.0
                current.append((p[0], p[1], elevation))
                is_end = k == 0 or k == len(part) - 1
                is_junction = osm is not None and use.get(osm, 0) >= 2
                if flag == 'cut':
                    key = ('cut', round(p[0], 2), round(p[1], 2))
                elif osm is not None:
                    key = osm
                else:
                    key = None
                if (is_end or is_junction) and key is not None and k > 0:
                    a = graph_node(start_key[0], *start_key[1])
                    b = graph_node(key, p[0], p[1], elevation, (1 if osm in signals else 0) | (2 if flag == 'cut' else 0))
                    if a != b and len(current) >= 2:
                        pts = simplify([(x, z) for x, z, _ in current], 0.3, False)
                        ys = [current[min(range(len(current)), key=lambda i: (current[i][0] - x) ** 2 + (current[i][1] - z) ** 2)][2] for x, z in pts]
                        edges.append(dict(a=a, b=b, cls=cls, lf=forward, lb=backward, width=width, bridge=bridge, pts=pts, ys=ys))
                    current = [(p[0], p[1], elevation)]
                if (is_end or is_junction) and key is not None:
                    start_key = (key, (p[0], p[1], elevation, (1 if osm in signals else 0) | (2 if flag == 'cut' else 0)))

    # Only edges in the largest strongly connected part of the lane graph carry traffic; cars that
    # reach the map edge come back in at another edge, so portals join the graph up.
    out_lanes = {}
    for i, e in enumerate(edges):
        out_lanes.setdefault(e['a'], []).append((i, 1))
        if e['lb'] > 0:
            out_lanes.setdefault(e['b'], []).append((i, -1))
    portals = [i for i, n in enumerate(graph_nodes) if n[3] & 2]
    def successors(lane):
        i, d = lane
        e = edges[i]
        end = e['b'] if d == 1 else e['a']
        if graph_nodes[end][3] & 2:
            return [l for p in portals for l in out_lanes.get(p, [])]
        nxt = [l for l in out_lanes.get(end, []) if l[0] != i]
        if nxt:
            return nxt
        # A dead end: turn around if the road has a lane back.
        return [(i, -d)] if e['lb'] > 0 else []
    lanes = [(i, 1) for i in range(len(edges))] + [(i, -1) for i, e in enumerate(edges) if e['lb'] > 0]
    index_of = {l: k for k, l in enumerate(lanes)}
    # Tarjan, iteratively.
    order, low, stack, on, comps, counter = {}, {}, [], set(), [], [0]
    for root in lanes:
        if root in order:
            continue
        work = [(root, iter(successors(root)))]
        order[root] = low[root] = counter[0]; counter[0] += 1; stack.append(root); on.add(root)
        while work:
            v, it = work[-1]
            for w2 in it:
                if w2 not in index_of:
                    continue
                if w2 not in order:
                    order[w2] = low[w2] = counter[0]; counter[0] += 1; stack.append(w2); on.add(w2)
                    work.append((w2, iter(successors(w2))))
                    break
                if w2 in on:
                    low[v] = min(low[v], order[w2])
            else:
                work.pop()
                if work:
                    low[work[-1][0]] = min(low[work[-1][0]], low[v])
                if low[v] == order[v]:
                    comp = []
                    while True:
                        x = stack.pop(); on.discard(x); comp.append(x)
                        if x == v:
                            break
                    comps.append(comp)
    biggest = set(max(comps, key=len)) if comps else set()
    for i, e in enumerate(edges):
        e['traffic'] = 1 if (i, 1) in biggest or (i, -1) in biggest else 0

    # Junctions with several big roads get signals, like the police-run crossings on main roads.
    degree = {}
    for e in edges:
        for n in (e['a'], e['b']):
            degree.setdefault(n, []).append(e['cls'])
    for n, classes in degree.items():
        if len(classes) >= 3 and sum(1 for c in classes if c <= 3) >= 3:
            graph_nodes[n][3] |= 1

    # ---- Docks -------------------------------------------------------------------------------
    grid = {}
    for k, b in enumerate(buildings):
        for x, z in b['poly']:
            grid.setdefault((int((x + HALF) // 40), int((z + HALF) // 40)), set()).add(k)
    def inside_polygon(x, z, poly):
        hit = False
        for i in range(len(poly)):
            (ax, az), (bx, bz) = poly[i - 1], poly[i]
            if (az > z) != (bz > z) and x < (bx - ax) * (z - az) / (bz - az) + ax:
                hit = not hit
        return hit
    def blocked(x, z, clearance):
        cell = (int((x + HALF) // 40), int((z + HALF) // 40))
        for gx in range(cell[0] - 1, cell[0] + 2):
            for gz in range(cell[1] - 1, cell[1] + 2):
                for k in grid.get((gx, gz), ()):
                    poly = buildings[k]['poly']
                    if inside_polygon(x, z, poly):
                        return True
                    for i in range(len(poly)):
                        (ax, az), (bx, bz) = poly[i - 1], poly[i]
                        dx, dz = bx - ax, bz - az
                        t = max(0, min(1, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz + 1e-9)))
                        if math.hypot(ax + dx * t - x, az + dz * t - z) < clearance:
                            return True
        return False
    incident = {}
    for i, e in enumerate(edges):
        for n, far in ((e['a'], e['pts'][1] if len(e['pts']) > 1 else e['pts'][-1]), (e['b'], e['pts'][-2] if len(e['pts']) > 1 else e['pts'][0])):
            incident.setdefault(n, []).append((e, far))
    candidates = []
    for n, inc in incident.items():
        node = graph_nodes[n]
        if len(inc) < 3 or node[3] & 2 or node[2] > 0.5 or abs(node[0]) > HALF - 40 or abs(node[1]) > HALF - 40:
            continue
        best = min(e['cls'] for e, _ in inc)
        if best > 6:
            continue
        candidates.append((best, -len(inc), n))
    candidates.sort()
    docks = []
    for _, _, n in candidates:
        x, z = graph_nodes[n][0], graph_nodes[n][1]
        if any(math.hypot(x - dx, z - dz) < 150 for dx, dz, _ in docks):
            continue
        inc = incident[n]
        bearings = sorted(math.atan2(fx - x, fz - z) for e, (fx, fz) in inc)
        gaps = []
        for i in range(len(bearings)):
            a, b = bearings[i - 1], bearings[i]
            gap = (b - a) % (2 * math.pi)
            gaps.append((gap, a + gap / 2))
        gaps.sort(reverse=True)
        reach = max(e['width'] for e, _ in inc) / 2 + 2.6
        placed = None
        for gap, mid in gaps:
            for extra in (0.0, 1.5, 3.0):
                px, pz = x + math.sin(mid) * (reach + extra) * 1.15, z + math.cos(mid) * (reach + extra) * 1.15
                if not blocked(px, pz, 2.2):
                    placed = (px, pz)
                    break
            if placed:
                break
        if placed:
            docks.append((placed[0], placed[1], n))
    home = 0
    for target in spec['home']:
        found = [l for l in landmarks if target in l[4] or l[4] in target]
        if found and docks:
            lx, lz = found[0][1], found[0][2]
            home = min(range(len(docks)), key=lambda d: math.hypot(docks[d][0] - lx, docks[d][1] - lz))
            break

    # Shops on the ground floor of buildings that face a bigger road.
    for e in edges:
        if e['cls'] > 5:
            continue
        for (ax, az), (bx, bz) in zip(e['pts'], e['pts'][1:]):
            steps = max(1, int(math.hypot(bx - ax, bz - az) / 12))
            for s in range(steps + 1):
                x, z = ax + (bx - ax) * s / steps, az + (bz - az) * s / steps
                cell = (int((x + HALF) // 40), int((z + HALF) // 40))
                for gx in range(cell[0] - 1, cell[0] + 2):
                    for gz in range(cell[1] - 1, cell[1] + 2):
                        for k in grid.get((gx, gz), ()):
                            b = buildings[k]
                            if b['kind'] in (APARTMENT, COMMERCIAL, HOUSE) and math.hypot(b['cx'] - x, b['cz'] - z) < 22 + math.sqrt(b['area']) / 2:
                                b['shops'] = 1

    # ---- Write -------------------------------------------------------------------------------
    def q(v):
        return max(-32767, min(32767, int(round(v / UNIT))))
    out = bytearray(b'SKYMAP01')
    title = spec['title'].encode()
    out += struct.pack('<H', len(title)) + title
    out += struct.pack('<fff', HALF, lat0, lon0)
    out += struct.pack('<I', len(buildings))
    for b in buildings:
        cx, cz, angle, hx, hz = oriented_box(b['poly'])
        out += struct.pack('<BBBBBhhhHHH', b['kind'], b['floors'], int(round(b['fh'] * 10)), b['tint'], b['shops'],
                           q(cx), q(cz), int(round(angle * 10000)), int(round(hx / UNIT)), int(round(hz / UNIT)), len(b['poly']))
        for x, z in b['poly']:
            out += struct.pack('<hh', q(x), q(z))
    out += struct.pack('<I', len(areas))
    for kind, poly in areas:
        out += struct.pack('<BH', kind, len(poly))
        for x, z in poly:
            out += struct.pack('<hh', q(x), q(z))
    out += struct.pack('<I', len(ribbons))
    for kind, width, pts in ribbons:
        out += struct.pack('<BBH', kind, int(round(width * 10)), len(pts))
        for x, z in pts:
            out += struct.pack('<hh', q(x), q(z))
    out += struct.pack('<I', len(graph_nodes))
    for x, z, y, flags in graph_nodes:
        out += struct.pack('<hhhB', q(x), q(z), int(round(y * 10)), flags)
    out += struct.pack('<I', len(edges))
    for e in edges:
        out += struct.pack('<HHBBBBBBH', e['a'], e['b'], e['cls'], e['lf'], e['lb'], int(round(e['width'] * 5)), e['bridge'], e['traffic'], len(e['pts']))
        for (x, z), y in zip(e['pts'], e['ys']):
            out += struct.pack('<hhh', q(x), q(z), int(round(y * 10)))
    out += struct.pack('<I', len(docks))
    for x, z, n in docks:
        out += struct.pack('<hhH', q(x), q(z), n)
    out += struct.pack('<H', home)
    out += struct.pack('<I', len(landmarks))
    for kind, x, z, index, nm in landmarks:
        label = nm.encode()
        out += struct.pack('<BhhHH', kind, q(x), q(z), index, len(label)) + label
    os.makedirs(os.path.join(HERE, '..', 'assets', 'maps'), exist_ok=True)
    open(os.path.join(HERE, '..', 'assets', 'maps', name + '.skymap'), 'wb').write(out)
    traffic_lanes = sum((e['lf'] + e['lb']) for e in edges if e['traffic'])
    print(f'{name}: {len(buildings)} buildings, {len(areas)} areas, {len(ribbons)} ribbons, {len(graph_nodes)} nodes, '
          f'{len(edges)} edges ({sum(e["traffic"] for e in edges)} with traffic, {traffic_lanes} lanes), {len(docks)} docks, '
          f'home dock {home}, {len(landmarks)} landmarks, {len(out) // 1024} KB')


if __name__ == '__main__':
    for name, spec in MAPS.items():
        if '--offline' not in sys.argv:
            print(f'Downloading {name}...')
            download(name, spec)
        build(name, spec)
