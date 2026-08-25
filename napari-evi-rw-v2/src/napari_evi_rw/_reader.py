"""
_reader.py
==========
Core reader logic for the napari-evi-reader plugin.

This module exposes two functions that together satisfy the npe2 reader
contract:

1. ``get_reader(path)``  — the *reader contribution* callable.
   napari calls this first to ask: "can you handle this file?"
   Return ``reader_function`` if yes, or ``None`` if no.

2. ``reader_function(path)`` — the actual I/O routine.
   Opens the ``.evi`` file, deserializes the NumPy array, and returns
   it in the canonical ``LayerData`` format that napari expects.

npe2 contract reference
-----------------------
A reader contribution must return ``Optional[ReaderFunction]`` where
``ReaderFunction`` is ``Callable[[PathOrPaths], List[LayerData]]`` and
``LayerData = Tuple[Any, Dict, str]``  →  ``(data, kwargs, layer_type)``.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple, Union

import numpy as np

# ---------------------------------------------------------------------------
# Type aliases (matching napari / npe2 conventions)
# ---------------------------------------------------------------------------
PathLike = Union[str, os.PathLike]
PathOrPaths = Union[PathLike, Sequence[PathLike]]
LayerData = Tuple[Any, Dict[str, Any], str]
ReaderFunction = Any  # Callable[[PathOrPaths], List[LayerData]]


# ---------------------------------------------------------------------------
# Public API — wired from napari.yaml
# ---------------------------------------------------------------------------

def get_reader(path: PathOrPaths) -> Optional[ReaderFunction]:
    """Decide whether this plugin can read the given *path*.

    Parameters
    ----------
    path : str | os.PathLike | Sequence[str | os.PathLike]
        A single file path or a list of paths that napari wants to open.
        For multi-file formats you'd iterate over the list; for ``.evi``
        we only support single files.

    Returns
    -------
    reader_function : callable or None
        If the path ends with ``.evi``, return ``reader_function`` so
        napari knows to hand the path to us.  Otherwise return ``None``
        to let other plugins (or napari's built-in readers) try.
    """
    # npe2 may hand us a list of paths (e.g. drag-and-drop of multiple files).
    # Normalise to a single string so we can inspect the extension.
    if isinstance(path, (list, tuple)):
        # We only support single-file reads — reject multi-file drops.
        if len(path) != 1:
            return None
        path = path[0]

    # Gate on the file extension (case-insensitive).
    if str(path).lower().endswith(".evi"):
        return reader_function

    return None


def reader_function(path: PathOrPaths) -> List[LayerData]:
    """Read an ``.evi`` file and return napari-compatible layer data.
    
    The ``.evi`` format is now a ZIP-compressed OME-Zarr archive. 
    We extract it to a temporary directory and delegate to napari-ome-zarr.
    """
    if isinstance(path, (list, tuple)):
        path = path[0]

    path = Path(path)
    
    # We must extract the zip to a permanent location while napari is running
    # because Zarr uses lazy-loading. If we use a temporary directory that 
    # gets deleted immediately, napari will crash when scrolling through slices.
    import tempfile
    import zipfile
    from napari_ome_zarr._reader import napari_get_reader
    
    cache_dir = Path.home() / ".cache" / "evi_extracted"
    cache_dir.mkdir(parents=True, exist_ok=True)
    
    # Create a unique temp folder for this specific file
    extracted_path = tempfile.mkdtemp(dir=cache_dir, prefix=f"{path.stem}_")
    
    print(f"Extracting .evi package to {extracted_path}...")
    with zipfile.ZipFile(path, 'r') as zipf:
        zipf.extractall(extracted_path)
        
    # Delegate completely to the official OME-Zarr reader
    reader = napari_get_reader(extracted_path)
    if not reader:
        raise ValueError(f"Failed to find OME-Zarr data inside {path.name}")
        
    layer_data = reader(extracted_path)
    return layer_data
