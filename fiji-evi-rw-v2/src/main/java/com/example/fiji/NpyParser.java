package com.example.fiji;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class NpyParser {
    public String descr;
    public int[] shape;
    public ByteBuffer data;

    public static NpyParser parse(InputStream is) throws IOException {
        NpyParser parser = new NpyParser();
        
        byte[] prefix = new byte[10];
        int read = 0;
        while (read < 10) {
            int r = is.read(prefix, read, 10 - read);
            if (r == -1) break;
            read += r;
        }
        
        ByteBuffer prefixBuf = ByteBuffer.wrap(prefix);
        prefixBuf.order(ByteOrder.LITTLE_ENDIAN);
        prefixBuf.position(8);
        short headerLen = prefixBuf.getShort();
        
        byte[] headerBytes = new byte[headerLen];
        read = 0;
        while (read < headerLen) {
            int r = is.read(headerBytes, read, headerLen - read);
            if (r == -1) break;
            read += r;
        }
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        
        // Parse descr
        int descrIdx = header.indexOf("'descr':");
        if (descrIdx != -1) {
            int start = header.indexOf("'", descrIdx + 8) + 1;
            int end = header.indexOf("'", start);
            parser.descr = header.substring(start, end);
        }
        
        // Parse shape
        int shapeIdx = header.indexOf("'shape':");
        if (shapeIdx != -1) {
            int start = header.indexOf("(", shapeIdx) + 1;
            int end = header.indexOf(")", start);
            String shapeStr = header.substring(start, end).trim();
            if (shapeStr.endsWith(",")) {
                shapeStr = shapeStr.substring(0, shapeStr.length() - 1);
            }
            if (shapeStr.isEmpty()) {
                parser.shape = new int[0];
            } else {
                String[] parts = shapeStr.split(",");
                parser.shape = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    parser.shape[i] = Integer.parseInt(parts[i].trim());
                }
            }
        }
        
        // Read remaining data
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        
        parser.data = ByteBuffer.wrap(buffer.toByteArray());
        parser.data.order(ByteOrder.LITTLE_ENDIAN);
        
        return parser;
    }
}
