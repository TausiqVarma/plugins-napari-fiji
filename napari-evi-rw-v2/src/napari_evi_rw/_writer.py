"""
_writer.py
==========
Core writer logic for the napari-evi-rw plugin.

The .evi format (v2) is a ZIP archive containing:
  - An OME-Zarr directory (Zarr v2 format) for image data
  - An optional evi_annotations/ directory with .npy files for
    points and shapes layers (since OME-Zarr has no spec for vector
    annotations).
"""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np


def write_multiple(path: str, data: List[Tuple[Any, Dict, str]]) -> List[str]:
    """
    Write multiple napari layers to a single .evi file.

    Parameters
    ----------
    path : str
        The file path to save to (e.g., "my_data.evi").
    data : List[Tuple[Any, Dict, str]]
        A list of layer tuples: (layer_data, metadata, layer_type).

    Returns
    -------
    List[str]
        A list of file paths that were successfully written.
    """
    import zarr

    # Create a temporary directory to build the OME-Zarr structure
    tmp_root = tempfile.mkdtemp(prefix="evi_write_")

    try:
        zarr_dir = os.path.join(tmp_root, "zarr_data")

        # -----------------------------------------------------------------
        # 1. Write image layers as OME-Zarr (Zarr v2 format)
        # -----------------------------------------------------------------
        image_arrays = []
        image_scale = None
        
        for i, (layer_data, metadata, layer_type) in enumerate(data):
            if layer_type == "image":
                image_arrays.append(_resolve_to_numpy(layer_data))
                if image_scale is None:
                    image_scale = list(metadata.get("scale", [1.0] * image_arrays[-1].ndim))
                
        if image_arrays:
            target_ndim = image_arrays[0].ndim
            
            # Stack all image layers along a new channel axis (axis 1)
            if len(image_arrays) > 1:
                final_image = np.stack(image_arrays, axis=1)
                # insert a scale of 1.0 for the channel dimension
                if len(image_scale) == target_ndim:
                    image_scale.insert(1 if target_ndim >= 4 else 0, 1.0)
            else:
                final_image = image_arrays[0]
                
            root = zarr.open_group(zarr_dir, mode="w", zarr_format=2)

            chunks = _auto_chunks(final_image.shape, final_image.ndim)
            root.create_array("0", data=final_image, chunks=chunks)

            # Write OME-NGFF 0.4 multiscales metadata
            axes = _make_axes(final_image.ndim, is_multichannel=(len(image_arrays) > 1))
            
            # Ensure image_scale has correct length, pad with 1.0 if necessary
            if len(image_scale) < final_image.ndim:
                image_scale = [1.0] * (final_image.ndim - len(image_scale)) + image_scale
                
            root.attrs["multiscales"] = [{
                "version": "0.4",
                "axes": axes,
                "datasets": [{
                    "path": "0",
                    "coordinateTransformations": [{
                        "type": "scale",
                        "scale": image_scale,
                    }],
                }],
            }]

        # -----------------------------------------------------------------
        # 2. ZIP the entire directory into the .evi file (uncompressed)
        # -----------------------------------------------------------------
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as zf:
            for root_dir, dirs, files in os.walk(zarr_dir):
                for fname in files:
                    abs_path = os.path.join(root_dir, fname)
                    arc_name = os.path.relpath(abs_path, zarr_dir)
                    zf.write(abs_path, arcname=arc_name)

        return [path]

    finally:
        # Clean up the temporary directory
        shutil.rmtree(tmp_root, ignore_errors=True)


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _resolve_to_numpy(data) -> np.ndarray:
    """Convert dask arrays or lists of dask arrays to numpy."""
    # Handle Napari's MultiScaleData wrapper
    if type(data).__name__ == "MultiScaleData" or type(data).__name__ == "_MultiScaleData":
        data = data[0]
        
    # If it's a list (multiscale), take the highest resolution
    if isinstance(data, (list, tuple)) and len(data) > 0:
        # Check if it's a list of arrays (multiscale) vs a list of shape vertices
        first = data[0]
        if hasattr(first, 'ndim') and first.ndim > 1 and len(data) > 1:
            # Likely multiscale — take highest resolution (first element)
            data = first
        elif hasattr(first, 'ndim') and first.ndim >= 2 and len(data) == 1:
            data = first
        else:
            # Probably a list of shape vertex arrays — leave it for caller
            return data
            
    # If it's a dask array, compute it
    if hasattr(data, "compute"):
        return np.asarray(data.compute())
    return np.asarray(data)


def _make_axes(ndim: int, is_multichannel: bool = False) -> list:
    """Build OME-NGFF axis descriptors from the number of dimensions."""
    if ndim == 2:
        return [
            {"name": "y", "type": "space"},
            {"name": "x", "type": "space"},
        ]
    elif ndim == 3:
        if is_multichannel:
            return [
                {"name": "c", "type": "channel"},
                {"name": "y", "type": "space"},
                {"name": "x", "type": "space"},
            ]
        else:
            return [
                {"name": "z", "type": "space"},
                {"name": "y", "type": "space"},
                {"name": "x", "type": "space"},
            ]
    elif ndim == 4:
        if is_multichannel:
            return [
                {"name": "c", "type": "channel"},
                {"name": "z", "type": "space"},
                {"name": "y", "type": "space"},
                {"name": "x", "type": "space"},
            ]
        else:
            return [
                {"name": "t", "type": "time"},
                {"name": "z", "type": "space"},
                {"name": "y", "type": "space"},
                {"name": "x", "type": "space"},
            ]
    elif ndim == 5:
        return [
            {"name": "t", "type": "time"},
            {"name": "c", "type": "channel"},
            {"name": "z", "type": "space"},
            {"name": "y", "type": "space"},
            {"name": "x", "type": "space"},
        ]
    else:
        # Fallback: generic spatial axes
        axes = []
        for i in range(ndim):
            axes.append({"name": f"dim_{i}", "type": "space"})
        return axes


def _auto_chunks(shape: tuple, ndim: int) -> tuple:
    """Generate sensible chunk sizes for Zarr storage."""
    chunks = []
    for i, s in enumerate(shape):
        if i >= ndim - 2:
            # Spatial dimension (y, x): chunk up to 512
            chunks.append(min(s, 512))
        else:
            # Time, channel, z: chunk by 1
            chunks.append(1)
    return tuple(chunks)
