# Fiji EVI Read/Write Plugin (v2)

This plugin enables **Fiji (ImageJ2)** to read and write our custom `.evi` file format. The `.evi` format is an uncompressed ZIP archive that bundles standard OME-Zarr raster images with highly efficient NumPy (`.npy`) sidecar arrays for vector annotations (Points and Polygons). 

This plugin guarantees 100% interoperability with the napari `.evi` plugin.

## 🏗️ Architecture Overview

The plugin is built using Java and Maven, leveraging SciJava `Command` annotations so it integrates seamlessly into the Fiji plugin menu. It operates completely independently of heavy Java-based Zarr libraries for writing to guarantee fast, bug-free writes.

### 1. The Reader (`EviOpenCommand.java`)
- **Image Data**: Uses Fiji's modern `ioService.open()` to crack open the extracted OME-Zarr structure, which correctly maps chunks into an `ImagePlus` dataset.
- **Custom Numpy Parser**: Includes a lightweight, custom `NpyParser.java` class that parses binary `.npy` headers and streams coordinate data directly into a Java `ByteBuffer`. 
- **Dynamic Dimension Handling**: Automatically detects if napari saved vectors as `float32` or `float64` and handles 2D vs 3D shapes. It safely maps the *last* 2-3 dimensions of any 5D array to the spatial `Z, Y, X` coordinates to guarantee shapes never detach from the image.
- **Overlay Reconstruction**: Converts the parsed NumPy coordinates into ImageJ `PointRoi` and `PolygonRoi` objects, maps them to the correct Z-slice, assigns them high-visibility colors (Yellow, stroke width 2), and appends them to the `ImagePlus` Overlay.

### 2. The Writer (`EviSaveCommand.java`)
- **Manual Zarr Export**: To bypass dependency conflicts and Zarr v3 metadata mismatches found in `ome-zarr-imagej`, the writer manually extracts pixel data from the `ImagePlus` and writes it as a strict **Zarr v2** directory (`0/`). It manually chunk-encodes the arrays and generates the `.zattrs` NGFF `multiscales` JSON.
- **Overlay Extraction**: It loops over every ROI in the ImageJ Overlay. 
  - `PointRoi` objects are flattened into an N-by-3 NumPy array (`float64`).
  - `PolygonRoi` vertices are extracted, flattened into a continuous coordinate array (`_shapes_coords.npy`), and their vertex counts are logged (`_shapes_counts.npy`).
- **Packaging**: Both the Zarr chunks and the `evi_annotations/` sidecars are combined into a temporary directory and zipped with `STORED` (zero compression) before being safely moved to the target `.evi` location.

## 🚀 Building & Installation

To compile the plugin from source:

```bash
mvn clean package -Denforcer.skip=true
```

This will generate `target/fiji-evi-rw-0.1.0-SNAPSHOT.jar`. 
Drop this `.jar` file into your `Fiji.app/plugins/` directory and restart Fiji.

## 💻 Usage
- **To Open**: Click `Plugins > Open EVI` and select your `.evi` file.
- **To Save**: Click `Plugins > Save EVI`, enter a filename, and your image along with any Overlays will be exported.
