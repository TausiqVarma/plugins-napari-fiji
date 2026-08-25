package com.example.fiji;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import org.scijava.io.IOService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;

@Plugin(type = Command.class, menuPath = "Plugins>Open EVI File...")
public class EviOpenCommand implements Command {

    @Parameter
    private File eviFile;

    @Parameter
    private UIService uiService;
    
    @Parameter
    private IOService ioService;

    @Override
    public void run() {
        ij.IJ.showStatus("Extracting EVI package...");
        
        try {
            // 1. Create a temporary directory to extract the ZIP
            Path tempDir = Files.createTempDirectory("evi_extracted_");
            
            // 2. Extract the .evi (ZIP) file
            try (ZipFile zipFile = new ZipFile(eviFile)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    File entryDestination = new File(tempDir.toFile(), entry.getName());
                    if (entry.isDirectory()) {
                        entryDestination.mkdirs();
                    } else {
                        entryDestination.getParentFile().mkdirs();
                        try (InputStream in = zipFile.getInputStream(entry);
                             FileOutputStream out = new FileOutputStream(entryDestination)) {
                            byte[] buffer = new byte[8192];
                            int len;
                            while ((len = in.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                        }
                    }
                }
            }
            
            ij.IJ.showStatus("Opening Zarr data...");
            
            // Check if the Zarr is wrapped in a bioformats2raw "0" subdirectory (a common OME-Zarr structure)
            String targetPath = tempDir.toFile().getAbsolutePath();
            File innerZeroDir = new File(tempDir.toFile(), "0");
            
            // If 0/ exists, has .zattrs, but does NOT have .zarray (meaning it's a group, not an array)
            if (innerZeroDir.exists() && innerZeroDir.isDirectory() 
                && new File(innerZeroDir, ".zattrs").exists() 
                && !new File(innerZeroDir, ".zarray").exists()) {
                targetPath = innerZeroDir.getAbsolutePath();
            }
            
            // 3. Delegate to the official OME-Zarr reader via SciJava IOService
            Object dataset = ioService.open(targetPath);
            
            // 4. Show the result
            if (dataset != null) {
                uiService.show(dataset);
                
            } else {
                ij.IJ.showMessage("EVI Error", "Failed to decode OME-Zarr data. Please ensure the OME-Zarr update site is enabled in Fiji.");
            }
            
        } catch (Exception e) {
            ij.IJ.handleException(e);
            ij.IJ.showMessage("EVI Error", "Failed to open EVI: " + e.getMessage());
        }
    }
}
