"""Leakage controls: near-duplicate detection and patient-grouped stratified splits."""

from __future__ import annotations

from collections import defaultdict

import numpy as np


def dhash(gray: np.ndarray, size: int = 8) -> int:
    """64-bit difference hash of a greyscale image (any size)."""
    from PIL import Image

    small = np.asarray(Image.fromarray(gray).resize((size + 1, size), Image.BILINEAR), dtype=np.int16)
    bits = (small[:, 1:] > small[:, :-1]).flatten()
    value = 0
    for b in bits:
        value = (value << 1) | int(b)
    return value


def near_duplicate_pairs(hashes: list[int], max_distance: int = 3) -> list[tuple[int, int]]:
    """Index pairs whose 64-bit hashes differ in <= max_distance bits.

    Uses the pigeonhole principle: with 4 bands of 16 bits and max_distance <= 3,
    every near-duplicate pair agrees exactly on at least one band.
    """
    assert max_distance <= 3
    buckets: dict[tuple[int, int], list[int]] = defaultdict(list)
    for i, h in enumerate(hashes):
        for band in range(4):
            buckets[(band, (h >> (16 * band)) & 0xFFFF)].append(i)
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
