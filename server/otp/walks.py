"""Mends a few places where OpenStreetMap leaves a bus stop's sidewalk unconnected, so the planner walks to it the way
riders do. Each fix names the exact ways and nodes it expects and is skipped as soon as the map there changes.

KERBS joins a sidewalk to the carriageway the bus stops on, across the kerb at the stop: OTP ties a stop to the one
street nearest it, and where the two aren't joined anywhere near, half the riders around get sent the long way round.
UNTAG takes a tag off a way it was wrongly put on. LINKS joins two ways the map leaves apart where the ground between
them is open (one parking lot drawn as two unconnected aisles, say), by a footway between two nodes they already have.

    python3 walks.py <dir>/israel.osm.pbf

mends the file in place and keeps its time, which says when prepare.py last fetched it. Run again, it changes nothing.
Needs pyosmium (pip install osmium).
"""

import math
import os
import sys

# Stop code: (stop point, (sidewalk way, node, next node), (road way, node, next node)).
KERBS = {
    # Kiryat Shmona, Tel Hai/Henrietta Szold: the sidewalk runs 9 m from Tel Hai street for 200 m without meeting it.
    "51445": ((33.201333, 35.569591), (1304546803, 12081921638, 12081921639), (578571050, 5543216780, 5543216773)),
    # Kfar Yona, Hani'el junction: the promenade beside road 57 meets it nowhere near the stop.
    "30617": ((32.31947, 34.94127), (1294753278, 14081479303, 8949485767), (819371132, 2510061096, 301026207)),
    # Kiryat Bialik, Kalil junction: the bike-and-foot path beside the Acre-Haifa road meets it 220 m south.
    "42536": ((32.858202, 35.09459), (246332216, 4717298857, 4717298856), (668598504, 1916662869, 1033603718)),
    # Shoham, Emek Ayalon/Yasur square: the footway at the stop only climbs to the plaza above, not down to the street.
    "32764": ((32.005175, 34.946687), (1368754441, 12676724415, 12676724402), (639526554, 6026080623, 6026080997)),
    # Petah Tikva, Kaplan/Arlozorov: the sidewalk at the stop ends 11 m short of the corner and meets Kaplan street only
    # 160 m north, so from the stop to the corner 129 m south was a 460 m walk, where the corner is 129 m away.
    "38311": ((32.086123, 34.868377), (1469846906, 13479652905, 13479652906), (267404129, 2728609074, 12865202674)),
}
# Way: tags it has wrongly and loses. Ahuza street's sidewalk in Raanana is tagged one-way, which OTP holds walkers to.
UNTAG = {1096980397: {"oneway": "yes"}}
# Name: ((way, node), (way, node)) to join on foot.
LINKS = {
    # Jerusalem, Malha Mall: the lane along the mall's east side and the car park's loop end 10 m apart on the same
    # asphalt (Esri and Bing imagery), so from the mall's north side the stop at Agudat Sport HaPoel (2922), 114 m
    # away, was a 617 m walk round by the Teddy bridge.
    "malha-mall-car-park": ((176297174, 1868435517), (176297150, 1868435599)),
}
# New nodes and ways count up from here, far above OpenStreetMap's own ids (some tools refuse negative ones).
FIRST_ID = 9_000_000_000_000


def foot(point, a, b):
    """Where on the segment a-b (lat, lon pairs) is nearest to point."""
    k = math.cos(math.radians(point[0]))
    ax, ay, bx, by = a[1] * k, a[0], b[1] * k, b[0]
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((point[1] * k - ax) * dx + (point[0] - ay) * dy) / (dx * dx + dy * dy)))
    return round(a[0] + t * (b[0] - a[0]), 7), round(a[1] + t * (b[1] - a[1]), 7)


def plan(ways, points, kerbs=KERBS, untag=UNTAG, links=LINKS, first=FIRST_ID, ours=()):
    """The edits for this map. ways: id -> (node ids, tags) of the ways the fixes name; points: node id -> (lat, lon);
    first: the lowest id free for new nodes and ways; ours: the node ids of each way an earlier run added.
    Returns (new nodes {id: (lat, lon)}, changed ways {id: (node ids, tags)}, new ways [(id, node ids, tags)])."""
    nodes, changed, added, new = {}, {}, [], first
    for stop, (point, *sides) in sorted(kerbs.items()):
        ends = []
        for way, a, b in sides:
            refs, tags = changed.get(way) or ways.get(way, (None, None))
            pairs = list(zip(refs, refs[1:])) if refs else []
            if (a, b) not in pairs or a not in points or b not in points:
                break
            at = foot(point, points[a], points[b])
            if at in (points[a], points[b]):  # the kerb is at a node the way already has
                ends.append(a if at == points[a] else b)
                continue
            i = pairs.index((a, b)) + 1
            changed[way] = (refs[:i] + [new] + refs[i:], tags)
            nodes[new] = at
            ends.append(new)
            new += 1
        if len(ends) == 2:
            added.append((new, ends, {"highway": "footway", "note": "kerb at bus stop " + stop}))
            new += 1
        else:  # the map changed there: leave it all as it is
            for n in ends:
                if n not in nodes:
                    continue
                del nodes[n]
                way = next(w for w, (r, _) in changed.items() if n in r)
                changed[way] = ([r for r in changed[way][0] if r != n], changed[way][1])
    for name, ((way, a), (other, b)) in sorted(links.items()):
        joined = {frozenset(refs) for refs in ours}
        if (a in (ways.get(way) or ([],))[0] and b in (ways.get(other) or ([],))[0] and a in points and b in points
                and frozenset((a, b)) not in joined):
            added.append((new, [a, b], {"highway": "footway", "note": "kav link " + name}))
            new += 1
    for way, wrong in untag.items():
        refs, tags = changed.get(way) or ways.get(way, (None, None))
        if tags and all(tags.get(k) == v for k, v in wrong.items()):
            changed[way] = (refs, {k: v for k, v in tags.items() if k not in wrong})
    changed = {w: v for w, v in changed.items() if v != ways.get(w)}
    return nodes, changed, added


def named(kerbs=KERBS, untag=UNTAG, links=LINKS):
    ways = {w for _, *sides in kerbs.values() for w, _, _ in sides} | set(untag) | {w for l in links.values() for w, _ in l}
    points = {n for _, *sides in kerbs.values() for _, a, b in sides for n in (a, b)} | {n for l in links.values() for _, n in l}
    return ways, points


def main(path):
    import osmium

    want_ways, want_points = named()
    ways, points, ours, first = {}, {}, [], FIRST_ID
    for o in osmium.FileProcessor(path):
        if o.id >= FIRST_ID:  # added by an earlier run
            first = max(first, o.id + 1)
            if o.is_way():
                ours.append((o.id, [n.ref for n in o.nodes], dict(o.tags)))
        if o.is_node() and o.id in want_points:
            points[o.id] = (o.location.lat, o.location.lon)
        elif o.is_way() and o.id in want_ways:
            ways[o.id] = ([n.ref for n in o.nodes], dict(o.tags))
    nodes, changed, added = plan(ways, points, first=first, ours=[refs for _, refs, _ in ours])
    if changed or added:
        mended = path + ".mending"
        # New ids are the highest, so new nodes go after the nodes and new ways after the ways, with the ways earlier
        # runs added (which once went after the relations): the file stays sorted by type and id, as osmium's tools need.
        with osmium.SimpleWriter(osmium.io.File(mended, "pbf"), overwrite=True) as w:
            todo = {"node": True, "way": True}

            def flush(kind):
                if todo.pop(kind, False):
                    if kind == "node":
                        for i, (lat, lon) in nodes.items():
                            w.add_node(osmium.osm.mutable.Node(id=i, location=(lon, lat), version=1))
                    else:
                        for i, refs, tags in sorted(ours) + added:
                            w.add_way(osmium.osm.mutable.Way(id=i, nodes=refs, tags=tags, version=1))

            for o in osmium.FileProcessor(path):
                if not o.is_node():
                    flush("node")
                if o.is_relation():
                    flush("way")
                if o.is_way() and o.id >= FIRST_ID:
                    continue
                if o.is_way() and o.id in changed:
                    refs, tags = changed[o.id]
                    w.add_way(o.replace(nodes=refs, tags=tags))
                else:
                    w.add(o)
            flush("node")
            flush("way")
        was = os.stat(path)
        os.utime(mended, (was.st_atime, was.st_mtime))
        os.replace(mended, path)
    links = sum(1 for _, _, tags in added if tags["note"].startswith("kav link"))
    print("walks mended:", len(added) - links, "kerbs,", links, "links,", len(changed), "ways", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
