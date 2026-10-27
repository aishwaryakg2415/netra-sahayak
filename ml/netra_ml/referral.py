"""Transparent decision-support rules - reference for ReferralPolicy.kt.

PROVISIONAL: the thresholds are chosen on validation data to reach a target
sensitivity; they have NOT been reviewed by an ophthalmologist and must not be
described as a clinical protocol.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

URGENT_REFERRAL = "URGENT_REFERRAL"
REFERRAL = "REFERRAL"
HUMAN_REVIEW = "HUMAN_REVIEW"
ROUTINE_RESCREEN = "ROUTINE_RESCREEN"


@dataclass(frozen=True)
class Decision:
    grade: int
    referable: bool
    urgent: bool
    needs_review: bool
    category: str
    reasons: tuple[str, ...]


def decide(probs: np.ndarray, cfg: dict) -> Decision:
    """probs: calibrated 5-class probabilities for one ACCEPTED image."""
    p = np.asarray(probs, dtype=np.float64)
    grade = int(p.argmax())
    min_grade = cfg["referable_min_grade"]
    ref_score = float(p[min_grade:].sum())
    urgent_score = float(p[3:].sum())
    reasons: list[str] = []

    referable = grade >= min_grade or ref_score >= cfg["referable_threshold"]
    if referable and grade < min_grade:
        reasons.append("referable_probability_above_threshold")
    urgent = grade >= 3 or urgent_score >= cfg["urgent_threshold"]
    if urgent and grade < 3:
        reasons.append("severe_probability_above_threshold")
    low_confidence = float(p.max()) < cfg["review_min_confidence"]
    if low_confidence:
        reasons.append("low_model_confidence")
    needs_review = low_confidence or (referable and grade < min_grade)

    if urgent:
        category = URGENT_REFERRAL
    elif referable:
        category = REFERRAL
    elif needs_review:
        category = HUMAN_REVIEW
    else:
        category = ROUTINE_RESCREEN
    return Decision(grade, referable, urgent, needs_review, category, tuple(reasons))
