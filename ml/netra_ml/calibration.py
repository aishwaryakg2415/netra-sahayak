"""Post-hoc temperature scaling, fitted on the validation split only."""

from __future__ import annotations

import numpy as np

from .metrics import softmax


def nll(logits: np.ndarray, y: np.ndarray, temperature: float) -> float:
    p = softmax(logits, temperature)
    return float(-np.log(np.clip(p[np.arange(len(y)), y], 1e-12, 1.0)).mean())


def fit_temperature(logits: np.ndarray, y: np.ndarray, lo: float = 0.25, hi: float = 8.0) -> float:
    """Minimises validation NLL over T with a golden-section search on log T.

    NLL is convex in 1/T, so the 1-D search is reliable.
    """
    y = np.asarray(y)
    a, b = np.log(lo), np.log(hi)
    phi = (np.sqrt(5) - 1) / 2
    c, d = b - phi * (b - a), a + phi * (b - a)
    fc, fd = nll(logits, y, np.exp(c)), nll(logits, y, np.exp(d))
    for _ in range(60):
        if fc < fd:
            b, d, fd = d, c, fc
            c = b - phi * (b - a)
            fc = nll(logits, y, np.exp(c))
        else:
            a, c, fc = c, d, fd
            d = a + phi * (b - a)
            fd = nll(logits, y, np.exp(d))
    return float(np.exp((a + b) / 2))
