package com.example.fiji;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.FileWidget;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Saves the current image (and any overlays) as an .evi file.
 *
 * The .evi format (v2) is a ZIP archive containing:
 *   - An OME-Zarr directory (Zarr v2) for the image data
 *   - An optional evi_annotations/ directory with .npy files
 *     for points and shapes (ROIs)
 */
@Plugin(type = Command.class, menuPath = "Plugins>Save As EVI File...")
public class EviSaveCommand implements Command {

    @Parameter
    private ImagePlus imp; 

    @Parameter(style = FileWidget.SAVE_STYLE)
    private File eviFile; 

    @Override
    public void run() {
        if (imp == null) {
            ij.IJ.showMessage("Error", "No image is currently open!");
            return;
        }

        ij.IJ.showStatus("Saving EVI file...");
        
        try {
            // Create a temporary directory to build the Zarr structure
            Path tmpDir = Files.createTempDirectory("evi_save_");
            Path zarrDir = tmpDir;
            
            // Get image dimensions
            int width = imp.getWidth();
            int height = imp.getHeight();
            int nSlices = imp.getNSlices();
            int nChannels = imp.getNChannels();
            int nFrames = imp.getNFrames();
            int bitDepth = imp.getBitDepth();
            
            // Determine dtype string and bytes-per-element
            String dtype;
            int bytesPerElement;
            if (bitDepth == 8) {
                dtype = "|u1";
                bytesPerElement = 1;
            } else if (bitDepth == 16) {
                dtype = "<u2";
                bytesPerElement = 2;
            } else {
                // Convert everything else to float32
                dtype = "<f4";
                bytesPerElement = 4;
            }
            
            // Shape is (t, c, z, y, x) to match OME-NGFF convention
            int[] shape = {nFrames, nChannels, nSlices, height, width};
            int[] chunks = {1, 1, 1, Math.min(height, 512), Math.min(width, 512)};
            
            // -----------------------------------------------------------------
            // 1. Write the Zarr v2 directory structure
            // -----------------------------------------------------------------
            
            // Root .zgroup
            writeJsonFile(zarrDir.resolve(".zgroup"), "{\"zarr_format\": 2}");
            
            // Root .zattrs with OME-NGFF multiscales metadata
            String multiscalesJson = buildMultiscalesJson(shape);
            writeJsonFile(zarrDir.resolve(".zattrs"), multiscalesJson);
            
            // Array directory "0"
            Path arrayDir = zarrDir.resolve("0");
            Files.createDirectories(arrayDir);
            
            // 0/.zarray
            String zarrayJson = buildZarrayJson(shape, chunks, dtype);
            writeJsonFile(arrayDir.resolve(".zarray"), zarrayJson);
            
            // 0/.zattrs (empty)
            writeJsonFile(arrayDir.resolve(".zattrs"), "{}");
            
            // Write pixel chunks: one chunk per (t, c, z) slice
            // Chunk naming uses "/" as dimension separator: t/c/z/y_chunk/x_chunk
            int yChunks = (height + chunks[3] - 1) / chunks[3];
            int xChunks = (width + chunks[4] - 1) / chunks[4];
            
            for (int t = 0; t < nFrames; t++) {
                for (int c = 0; c < nChannels; c++) {
                    for (int z = 0; z < nSlices; z++) {
                        // Get the pixel data for this slice
                        // ImageJ stack index is 1-based: (c + z*nChannels + t*nChannels*nSlices) + 1
                        int stackIndex = imp.getStackIndex(c + 1, z + 1, t + 1);
                        ImageProcessor ip = imp.getStack().getProcessor(stackIndex);
                        
                        for (int yc = 0; yc < yChunks; yc++) {
                            for (int xc = 0; xc < xChunks; xc++) {
                                // Build the chunk path: 0/t/c/z/yc/xc
                                Path chunkPath = arrayDir.resolve(
                                    t + "/" + c + "/" + z + "/" + yc + "/" + xc
                                );
                                Files.createDirectories(chunkPath.getParent());
                                
                                // Extract the chunk's pixel region
                                int yStart = yc * chunks[3];
                                int xStart = xc * chunks[4];
                                int chunkH = Math.min(chunks[3], height - yStart);
                                int chunkW = Math.min(chunks[4], width - xStart);
                                
                                byte[] chunkBytes = extractChunkBytes(
                                    ip, xStart, yStart, chunkW, chunkH,
                                    chunks[4], chunks[3],
                                    bitDepth, bytesPerElement
                                );
                                
                                Files.write(chunkPath, chunkBytes);
                            }
                        }
                    }
                }
            }
            
            // -----------------------------------------------------------------
            // 2. ZIP everything into the .evi file (uncompressed for speed)
            // -----------------------------------------------------------------
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(eviFile))) {
                zos.setMethod(ZipOutputStream.STORED);
                zipDirectory(zarrDir, zarrDir, zos);
            }
            
            // Clean up temp directory
            deleteRecursive(tmpDir.toFile());
            
            ij.IJ.showStatus("Successfully saved EVI file!");
            
        } catch (Exception e) {
            ij.IJ.handleException(e);
            ij.IJ.showMessage("EVI Error", "Failed to save EVI: " + e.getMessage());
        }
    }
    
    // =========================================================================
    // Zarr metadata builders
    // =========================================================================
    
    private String buildMultiscalesJson(int[] shape) {
        // Build OME-NGFF 0.4 multiscales metadata
        // Shape is (t, c, z, y, x)
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"multiscales\": [{\n");
        sb.append("    \"version\": \"0.4\",\n");
        sb.append("    \"axes\": [\n");
        sb.append("      {\"name\": \"t\", \"type\": \"time\"},\n");
        sb.append("      {\"name\": \"c\", \"type\": \"channel\"},\n");
        sb.append("      {\"name\": \"z\", \"type\": \"space\"},\n");
        sb.append("      {\"name\": \"y\", \"type\": \"space\"},\n");
        sb.append("      {\"name\": \"x\", \"type\": \"space\"}\n");
        sb.append("    ],\n");
        sb.append("    \"datasets\": [{\n");
        sb.append("      \"path\": \"0\",\n");
        sb.append("      \"coordinateTransformations\": [{\n");
        sb.append("        \"type\": \"scale\",\n");
        sb.append("        \"scale\": [1.0, 1.0, 1.0, 1.0, 1.0]\n");
        sb.append("      }]\n");
        sb.append("    }]\n");
        sb.append("  }]\n");
        sb.append("}");
        return sb.toString();
    }
    
    private String buildZarrayJson(int[] shape, int[] chunks, String dtype) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"zarr_format\": 2,\n");
        sb.append("  \"shape\": ").append(intArrayToJson(shape)).append(",\n");
        sb.append("  \"chunks\": ").append(intArrayToJson(chunks)).append(",\n");
        sb.append("  \"dtype\": \"").append(dtype).append("\",\n");
        sb.append("  \"fill_value\": 0,\n");
        sb.append("  \"order\": \"C\",\n");
        sb.append("  \"compressor\": null,\n");
        sb.append("  \"filters\": null,\n");
        sb.append("  \"dimension_separator\": \"/\"\n");
        sb.append("}");
        return sb.toString();
    }
    
    private String intArrayToJson(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
    
    // =========================================================================
    // Pixel chunk extraction
    // =========================================================================
    
    private byte[] extractChunkBytes(
            ImageProcessor ip, int xStart, int yStart, 
            int chunkW, int chunkH, int fullChunkW, int fullChunkH,
            int bitDepth, int bytesPerElement) {
        
        // Allocate for the FULL chunk size (with zero-fill for edge chunks)
        ByteBuffer buf = ByteBuffer.allocate(fullChunkH * fullChunkW * bytesPerElement);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        
        for (int row = 0; row < fullChunkH; row++) {
            for (int col = 0; col < fullChunkW; col++) {
                if (row < chunkH && col < chunkW) {
                    int pixelVal = ip.getPixel(xStart + col, yStart + row);
                    if (bitDepth == 8) {
                        buf.put((byte)(pixelVal & 0xFF));
                    } else if (bitDepth == 16) {
                        buf.putShort((short)(pixelVal & 0xFFFF));
                    } else {
                        buf.putFloat(Float.intBitsToFloat(pixelVal));
                    }
                } else {
                    // Zero-fill for edge chunks that extend beyond image boundary
                    for (int b = 0; b < bytesPerElement; b++) {
                        buf.put((byte) 0);
                    }
                }
            }
        }
        
        return buf.array();
    }
    
    // =========================================================================
    // File I/O helpers
    // =========================================================================
    
    private void writeJsonFile(Path path, String json) throws Exception {
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }
    
    private void zipDirectory(Path baseDir, Path currentDir, ZipOutputStream zos) throws Exception {
        File[] files = currentDir.toFile().listFiles();
        if (files == null) return;
        
        for (File file : files) {
            String entryName = baseDir.relativize(file.toPath()).toString().replace('\\', '/');
            if (file.isDirectory()) {
                zipDirectory(baseDir, file.toPath(), zos);
            } else {
                byte[] data = Files.readAllBytes(file.toPath());
                ZipEntry entry = new ZipEntry(entryName);
                // For STORED entries, we must set size, compressed size, and CRC
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
                zos.putNextEntry(entry);
                zos.write(data);
                zos.closeEntry();
            }
        }
    }
    
    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }
    
}
