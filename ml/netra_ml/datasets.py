"""Dataset manifests.

Each loader reads a dataset's own annotation file and returns `Record`s with a
harmonised 0-4 DR grade (International Clinical DR scale). Grade 5 (UNGRADABLE)
is kept separately for image-quality evaluation and never used as a DR label.

Roots are configurable: pass explicit paths, or let `discover_roots` find the
datasets under a directory such as /kaggle/input. Nothing is downloaded here.
"""

from __future__ import annotations

import csv
import os
from dataclasses import asdict, dataclass

UNGRADABLE = 5


@dataclass(frozen=True)
class Record:
    dataset: str
    path: str
    label: int          # 0-4, or UNGRADABLE
    patient_id: str     # grouping key for leakage-safe splits
    source_split: str   # split defined by the dataset authors, if any ("" otherwise)

    def as_dict(self) -> dict:
        return asdict(self)


# Marker files used to locate each dataset under a search root.
MARKERS = {
    "aptos2019": "train.csv",                       # + train_images/
    "eyepacs": "trainLabels.csv",                   # tanlikesmath/diabetic-retinopathy-resized
    "ddr": os.path.join("DR_grading", "train.txt"),
    "idrid": "idrid_labels.csv",
    "messidor2_images": "messidor_data.csv",        # mariaherrerot/messidor2preprocess
    "messidor2_grades": "messidor_readme.txt",      # google-brain/messidor2-dr-grades
}


def discover_roots(search_root: str, max_depth: int = 5) -> dict[str, str]:
    """Walks search_root and returns {dataset_key: directory} for each marker found."""
    found: dict[str, str] = {}
    base_depth = search_root.rstrip(os.sep).count(os.sep)
    for dirpath, dirnames, filenames in os.walk(search_root):
        if dirpath.count(os.sep) - base_depth >= max_depth:
            dirnames[:] = []
        names = set(filenames)
        for key, marker in MARKERS.items():
            if key in found:
                continue
            marker_dir, marker_file = os.path.split(marker)
            if marker_dir:
                if os.path.isfile(os.path.join(dirpath, marker)):
                    found[key] = dirpath
            elif marker_file in names:
                if key == "aptos2019" and not os.path.isdir(os.path.join(dirpath, "train_images")):
                    continue
                found[key] = dirpath
        # Skip image folders: they are huge and contain no markers.
        dirnames[:] = [d for d in dirnames if not d.lower().startswith(("train_images", "test_images", "resized_train", "imagenes", "preprocess"))]
    return found


def load_aptos(root: str) -> list[Record]:
    out = []
    for row in _read_csv(os.path.join(root, "train.csv")):
        image_id = row["id_code"]
        out.append(Record("aptos2019", os.path.join(root, "train_images", f"{image_id}.png"),
                          int(row["diagnosis"]), f"aptos2019:{image_id}", ""))
    return out


def load_eyepacs(root: str) -> list[Record]:
    img_dir = _first_dir(root, [os.path.join("resized_train", "resized_train"), "resized_train", "train"])
    out = []
    for row in _read_csv(os.path.join(root, "trainLabels.csv")):
        name = row["image"]
        patient = name.split("_")[0]
        path = os.path.join(img_dir, f"{name}.jpeg")
        out.append(Record("eyepacs", path, int(row["level"]), f"eyepacs:{patient}", ""))
    return out


def load_ddr(root: str) -> list[Record]:
    """DDR: labels 0-4 plus 5 = ungradable. Official train/valid/test splits are kept."""
    base = os.path.join(root, "DR_grading")
    out = []
    for split in ("train", "valid", "test"):
        ann = os.path.join(base, f"{split}.txt")
        if not os.path.isfile(ann):
            continue
        with open(ann, encoding="utf-8") as fh:
            for line in fh:
                parts = line.split()
                if len(parts) != 2:
                    continue
                name, label = parts[0], int(parts[1])
                out.append(Record("ddr", os.path.join(base, split, name), label,
                                  f"ddr:{os.path.splitext(name)[0]}", split))
    return out


def load_idrid(root: str) -> list[Record]:
    img_dir = _first_dir(root, [os.path.join("Imagenes", "Imagenes"), "Imagenes", "images"])
    out = []
    for row in _read_csv(os.path.join(root, "idrid_labels.csv")):
        image_id = (row.get("id_code") or "").strip()
        grade = (row.get("diagnosis") or "").strip()
        if not image_id or not grade:
            continue
        path = os.path.join(img_dir, f"{image_id}.jpg")
        split = "test" if image_id.endswith("test") else "train"
        out.append(Record("idrid", path, int(grade), f"idrid:{image_id}", split))
    return out


def load_messidor2(images_root: str, grades_root: str | None) -> list[Record]:
    """Messidor-2 with the adjudicated grades of Krause et al. (2018).

    Ungradable images (adjudicated_gradable == 0) get label UNGRADABLE.
    Patients contribute two eyes; the CSV has no patient key, so the exam
    prefix of the filename is used for grouping.
    """
    img_dir = _first_dir(images_root, [os.path.join("messidor-2", "messidor-2", "preprocess"),
                                       os.path.join("messidor-2", "preprocess"), "preprocess"])
    if grades_root and os.path.isfile(os.path.join(grades_root, "messidor_data.csv")):
        rows = _read_csv(os.path.join(grades_root, "messidor_data.csv"))
        id_key, grade_key = "image_id", "adjudicated_dr_grade"
    else:
        rows = _read_csv(os.path.join(images_root, "messidor_data.csv"))
        id_key, grade_key = "id_code", "diagnosis"
    out = []
    for row in rows:
        name = row[id_key]
        gradable = str(row.get("adjudicated_gradable", "1")).strip() in ("1", "1.0", "True")
        raw = str(row.get(grade_key, "")).strip()
        label = int(float(raw)) if raw and gradable else UNGRADABLE
        path = os.path.join(img_dir, name)
        if not os.path.isfile(path):
            path = os.path.join(img_dir, os.path.splitext(name)[0] + ".png")
        exam = "_".join(name.split("_")[:2])
        out.append(Record("messidor2", path, label, f"messidor2:{exam}", ""))
    return out


def load_all(roots: dict[str, str]) -> dict[str, list[Record]]:
    data: dict[str, list[Record]] = {}
    if "aptos2019" in roots:
        data["aptos2019"] = load_aptos(roots["aptos2019"])
    if "eyepacs" in roots:
        data["eyepacs"] = load_eyepacs(roots["eyepacs"])
    if "ddr" in roots:
        data["ddr"] = load_ddr(roots["ddr"])
    if "idrid" in roots:
        data["idrid"] = load_idrid(roots["idrid"])
    if "messidor2_images" in roots:
        data["messidor2"] = load_messidor2(roots["messidor2_images"], roots.get("messidor2_grades"))
    return {k: [r for r in v if os.path.isfile(r.path)] for k, v in data.items()}


def _read_csv(path: str) -> list[dict]:
    with open(path, newline="", encoding="utf-8-sig") as fh:
        return [{(k or "").strip(): v for k, v in row.items()} for row in csv.DictReader(fh)]


def _first_dir(root: str, candidates: list[str]) -> str:
    for c in candidates:
        p = os.path.join(root, c)
        if os.path.isdir(p):
            return p
    return os.path.join(root, candidates[0])
