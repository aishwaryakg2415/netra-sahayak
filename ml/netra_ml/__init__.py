"""Netra Sahayak ML pipeline: preprocessing, quality gating, training, evaluation, export.

The preprocessing and quality code in this package is the *reference* implementation.
The Android app re-implements the same arithmetic in Kotlin
(app/src/main/java/com/sih/netrasahayak/ml/), and golden files produced by
`netra_ml.golden` keep the two in lock-step.
"""

__version__ = "1.0.0"
