"""Leakage controls: near-duplicate detection and patient-grouped stratified splits."""

from __future__ import annotations

from collections import defaultdict

import numpy as np


HASH_BITS = 256
NEAR_DUPLICATE_BITS = 10


def dhash(gray: np.ndarray, size: int = 16) -> int:
    """256-bit difference hash of the central retina.

    Fundus photos all share the same layout (black corners, bright disc), so a
    small whole-image hash makes unrelated eyes look identical. Hashing only the
    central 60 % at 16x16 after contrast stretching captures vessel structure.
    """
    from PIL import Image

    h, w = gray.shape
    crop = gray[int(0.2 * h):int(0.8 * h), int(0.2 * w):int(0.8 * w)].astype(np.float64)
    lo, hi = np.percentile(crop, 2), np.percentile(crop, 98)
    crop = np.clip((crop - lo) / max(hi - lo, 1.0) * 255.0, 0, 255).astype(np.uint8)
    small = np.asarray(Image.fromarray(crop).resize((size + 1, size), Image.BILINEAR), dtype=np.int16)
    bits = (small[:, 1:] > small[:, :-1]).flatten()
    value = 0
    for b in bits:
        value = (value << 1) | int(b)
    return value


def near_duplicate_pairs(hashes: list[int], max_distance: int = NEAR_DUPLICATE_BITS,
                         bits: int = HASH_BITS) -> list[tuple[int, int]]:
    """Index pairs whose hashes differ in <= max_distance bits.

    Pigeonhole banding: with max_distance + 1 bands, any pair within
    max_distance bits agrees exactly on at least one band.
    """
    bands = max_distance + 1
    width = bits // bands
    assert width >= 8, "too many bands for the hash size"
    mask = (1 << width) - 1
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    for i, h in enumerate(hashes):
        for band in range(bands):
            buckets[(band, (h >> (width * band)) & mask)].append(i)
    pairs = set()
    for members in buckets.values():
        if len(members) < 2 or len(members) > 2000:
            continue  # a giant bucket means blank/degenerate images; handled by quality gate
        for a_i, a in enumerate(members):
            for b in members[a_i + 1:]:
                if bin(hashes[a] ^ hashes[b]).count("1") <= max_distance:
                    pairs.add((min(a, b), max(a, b)))
    return sorted(pairs)


def grouped_stratified_split(labels: list[int], groups: list[str], fractions: dict[str, float],
                             seed: int = 42) -> list[str]:
    """Assigns every item to a split so that no group crosses splits.

    Groups are stratified by their highest label (the most severe eye of a
    patient), shuffled with a fixed seed, and dealt into splits by fraction.
    """
    names = list(fractions)
    probs = np.array([fractions[n] for n in names], dtype=np.float64)
    probs = probs / probs.sum()
    group_max: dict[str, int] = {}
    for lab, g in zip(labels, groups):
        group_max[g] = max(group_max.get(g, -1), lab)
    strata: dict[int, list[str]] = defaultdict(list)
    for g, m in group_max.items():
        strata[m].append(g)
    rng = np.random.default_rng(seed)
    assignment: dict[str, str] = {}
    for m in sorted(strata):
        gs = sorted(strata[m])
        rng.shuffle(gs)
        bounds = np.round(np.cumsum(probs) * len(gs)).astype(int)
        start = 0
        for name, end in zip(names, bounds):
            for g in gs[start:end]:
                assignment[g] = name
            start = end
    return [assignment[g] for g in groups]
