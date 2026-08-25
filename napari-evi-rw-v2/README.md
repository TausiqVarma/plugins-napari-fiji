# Napari EVI Read/Write Plugin (v2)

This plugin enables **napari** to read and write our custom `.evi` file format. The `.evi` format is an interoperable, uncompressed ZIP archive containing standard OME-Zarr image data alongside sidecar NumPy arrays for vector annotations (Points and Shapes).

## 🏗️ Architecture Overview

The plugin operates via two main hooks defined in the `npe2` manifest (`napari.yaml`):

### 1. The Reader (`_reader.py`)
- **Extraction**: When an `.evi` file is opened, the plugin extracts the uncompressed ZIP archive to a persistent local cache (`~/.cache/evi_extracted/`). This is necessary because the underlying `ome-zarr` library relies on lazy-loading chunks from a real filesystem over time.
- **Image Data**: The plugin delegates the heavy lifting of reading the OME-Zarr image pyramid to the official `napari-ome-zarr` reader.
- **Annotations**: After the image is loaded, the reader scans the `evi_annotations/` folder for `.npy` sidecar files.
  - **Points** are loaded directly from `<LayerName>_coords.npy`.
  - **Shapes** are reconstructed by reading a flattened list of vertices from `<LayerName>_shapes_coords.npy` and chunking them back into individual polygons using the counts in `<LayerName>_shapes_counts.npy`.

### 2. The Writer (`_writer.py`)
- **Image Stacking**: If a multi-channel image was split by napari into multiple layers (due to `channel_axis` metadata), the writer dynamically stacks them back together into a single multi-dimensional NumPy array.
- **Zarr Serialization**: The image data is chunked and written into an OME-Zarr v2 directory (`0/`). The writer generates strict OME-NGFF `multiscales` metadata, automatically assigning the correct axes (`t`, `c`, `z`, `y`, `x`) based on the dimensions so that other software doesn't improperly split the channels.
- **Annotation Export**: It intercepts Points and Shapes layers and exports their spatial coordinates as raw NumPy (`.npy`) binary arrays into an `evi_annotations/` subfolder.
- **Packaging**: The entire temporary directory is packaged into a ZIP archive with `compression=STORED` (uncompressed) to ensure seamless, zero-copy reads in Fiji, and renamed to `.evi`.

## 🚀 Installation & Usage

```bash
# Install locally in editable mode
pip install -e .
```

Once installed, simply drag-and-drop `.evi` files into Napari to open them, and use `File > Save As` (choosing the `.evi` format) to write them.
