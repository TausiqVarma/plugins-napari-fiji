# The .evi File Format

### What is it?
An `.evi` file is a standard **OME-Zarr** image directory that has simply been packaged into an uncompressed **ZIP archive** with a custom extension to make it easily shareable as a single file.

### How is it created?
We created the `.evi` format by generating a standard OME-Zarr folder structure in a temporary directory (using standard `zarr` libraries in Python, or raw bytes in Java), and then using built-in ZIP libraries (`zipfile` in Python, `ZipOutputStream` in Java) to bundle that entire folder into a single uncompressed archive.
